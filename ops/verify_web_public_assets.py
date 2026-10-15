"""Verify actual HTTP delivery of the public Web assets against source bytes."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import selectors
import subprocess
import time
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
ASSETS = {
    "brand/dcompany-app-mark.png": {"image/png"},
    "brand/den-emblem-gold.png": {"image/png"},
    "favicon-32.png": {"image/png"},
    "icon-192.png": {"image/png"},
    "manifest.webmanifest": {"application/manifest+json"},
    "service-worker.js": {"application/javascript", "text/javascript"},
}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


open_url = build_opener(NoRedirect()).open
MAX_HEADER_BYTES = 8192
CONTAINER_TIMEOUT_SECONDS = 10.0


def _bounded_command(command: list[str], request: bytes, limit: int) -> bytes:
    """Bound a local HTTP client's output and lifetime without invoking a shell."""
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    output = bytearray()
    errors = bytearray()
    deadline = time.monotonic() + CONTAINER_TIMEOUT_SECONDS
    try:
        assert process.stdin is not None and process.stdout is not None and process.stderr is not None
        process.stdin.write(request)
        process.stdin.close()
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, output)
            selector.register(process.stderr, selectors.EVENT_READ, errors)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError("Public asset container HTTP request timed out")
                for key, _ in selector.select(remaining):
                    destination = key.data
                    maximum = limit if destination is output else MAX_HEADER_BYTES
                    chunk = os.read(key.fd, min(65536, maximum - len(destination) + 1))
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    destination.extend(chunk)
                    if len(destination) > maximum:
                        raise RuntimeError("Public asset container HTTP response exceeded its size bound")
        if process.wait(timeout=max(0.01, deadline - time.monotonic())) != 0:
            raise RuntimeError("Public asset container HTTP transport failed")
        return bytes(output)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("Public asset container HTTP transport failed") from exc
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None:
                stream.close()


def _parse_container_response(raw: bytes, expected_size: int) -> tuple[int, str, bytes]:
    header, separator, body = raw.partition(b"\r\n\r\n")
    if not separator or len(header) > MAX_HEADER_BYTES:
        raise RuntimeError("Public asset HTTP headers are missing or oversized")
    lines = header.split(b"\r\n")
    if re.fullmatch(rb"HTTP/1\.[01] 200(?: [\x20-\x7e]*)?", lines[0]) is None:
        raise RuntimeError("Public asset HTTP response must be one successful response without redirects")
    headers: dict[bytes, list[bytes]] = {}
    for line in lines[1:]:
        name, colon, value = line.partition(b":")
        if not colon or re.fullmatch(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) is None or any(byte < 32 or byte == 127 for byte in value):
            raise RuntimeError("Public asset HTTP header is malformed")
        headers.setdefault(name.lower(), []).append(value.strip())
    lengths = headers.get(b"content-length", [])
    types = headers.get(b"content-type", [])
    if len(lengths) != 1 or lengths[0] != str(expected_size).encode() or len(body) != expected_size:
        raise RuntimeError("Public asset HTTP content length differs from the reviewed asset")
    if len(types) != 1 or b"transfer-encoding" in headers or headers.get(b"content-encoding", [b"identity"]) != [b"identity"]:
        raise RuntimeError("Public asset HTTP type or encoding is ambiguous")
    try:
        content_type = types[0].decode("ascii").split(";", 1)[0].strip().lower()
    except UnicodeDecodeError as exc:
        raise RuntimeError("Public asset HTTP content type is malformed") from exc
    return 200, content_type, body


def _container_response(container: str, relative: str, expected_size: int) -> tuple[int, str, bytes]:
    if re.fullmatch(r"[0-9a-f]{64}", container) is None or relative not in ASSETS:
        raise ValueError("Container verification requires an exact container ID and reviewed asset path")
    request = f"GET /{relative} HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n".encode("ascii")
    raw = _bounded_command(
        ["docker", "exec", "-i", container, "nc", "-w", "5", "127.0.0.1", "80"],
        request,
        MAX_HEADER_BYTES + 4 + expected_size,
    )
    return _parse_container_response(raw, expected_size)


def verify(base_url: str | None = None, host: str | None = None, *, container: str | None = None) -> None:
    if bool(base_url) == bool(container) or (container is not None and host is not None):
        raise ValueError("Select exactly one host URL or container HTTP transport")
    for relative, content_types in ASSETS.items():
        expected = (ROOT / "frontend/public" / relative).read_bytes()
        if container is not None:
            status, content_type, body = _container_response(container, relative, len(expected))
        else:
            assert base_url is not None
            headers = {"Host": host} if host else {}
            request = Request(f"{base_url.rstrip('/')}/{relative}", headers=headers)
            with open_url(request, timeout=15) as response:
                body = response.read(len(expected) + 1)
                status, content_type = response.status, response.headers.get_content_type()
        if status != 200 or content_type not in content_types:
            raise RuntimeError(f"Public asset {relative} did not return its expected HTTP status/type")
        if not expected or hashlib.sha256(body).digest() != hashlib.sha256(expected).digest():
            raise RuntimeError(f"Public asset {relative} does not match the reviewed source bytes")
        print(f"Verified public asset /{relative}: HTTP 200, expected type and SHA-256")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    transport = parser.add_mutually_exclusive_group(required=True)
    transport.add_argument("--base-url")
    transport.add_argument("--container")
    parser.add_argument("--host")
    args = parser.parse_args()
    if args.container and args.host:
        parser.error("--host is available only with --base-url")
    verify(args.base_url, args.host, container=args.container)

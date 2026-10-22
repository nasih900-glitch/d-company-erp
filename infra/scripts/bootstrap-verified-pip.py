#!/usr/bin/env python3
"""Bootstrap only the fixed, independently pinned official pip wheel.

Uses stdlib HTTPS and verifies exact bytes before executing anything from the
wheel. No current pip network access, proxy, redirect, arbitrary URL or version
selection is supported. This installs into the invoking Python environment.
"""

from __future__ import annotations

import hashlib
import http.client
import os
from pathlib import Path
import re
import ssl
import stat
import subprocess
import sys
import tempfile
import time


HOST = "files.pythonhosted.org"
PATH = "/packages/3f/06/3e4420302363fbb71be3c7f35ce042f54bfca330e3e66b3a2e3c4cca579f/pip-26.2.2-py3-none-any.whl"
FILENAME = "pip-26.2.2-py3-none-any.whl"
SHA256 = "fd7f4dfa8190e014013b9f08f3e973f4e4c26ffd179a204895533a7254813a70"
SIZE = 1_819_850
DOWNLOAD_ADMISSION_SECONDS = 30
IO_TIMEOUT_SECONDS = 10
INSTALL_TIMEOUT_SECONDS = 120
VENDORED_CA_SHA256 = "50f79f98dac192b20dbf73f35c56f0b0c557ab267ff5199d1117cc1180b70785"
REVOKED_CA_SHA256 = "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b"
CA_TRUST_DIRECTORIES = (Path("/etc/ssl"), Path("/usr/share/ca-certificates"),
                        Path("/usr/local/share/ca-certificates"))
MAX_CA_FILE_BYTES = 2 * 1024 * 1024
MAX_CA_TOTAL_BYTES = 16 * 1024 * 1024
MAX_CA_DIRECTORY_ENTRIES = 1024
MAX_CA_CERTIFICATES = 2048


class BootstrapError(RuntimeError):
    """A categorical failure; URLs, environment contents and payloads stay private."""


def _download_wheel() -> bytes:
    deadline = time.monotonic() + DOWNLOAD_ADMISSION_SECONDS
    connection = http.client.HTTPSConnection(
        HOST, timeout=IO_TIMEOUT_SECONDS, context=ssl.create_default_context()
    )
    try:
        connection.request("GET", PATH, headers={"Accept": "application/octet-stream"})
        response = connection.getresponse()
        if response.status != 200:
            raise BootstrapError("Official wheel download was refused; redirects are not accepted.")
        declared = response.getheader("Content-Length")
        if declared is not None and declared != str(SIZE):
            raise BootstrapError("Official wheel length does not match the reviewed artifact.")
        body = bytearray()
        while True:
            if time.monotonic() > deadline:
                raise BootstrapError("Official wheel download exceeded the admission deadline.")
            # read1 makes at most one underlying read, so slow-drip input cannot
            # hide indefinitely inside a read(exact_size) call. Each I/O is also
            # socket-timeout bounded. Late bytes are never admitted for execution.
            chunk = response.read1(min(65_536, SIZE + 1 - len(body)))
            if not chunk:
                break
            body.extend(chunk)
            if len(body) > SIZE:
                raise BootstrapError("Official wheel exceeds the reviewed artifact length.")
        if time.monotonic() > deadline:
            raise BootstrapError("Official wheel download exceeded the admission deadline.")
        if len(body) != SIZE or hashlib.sha256(body).hexdigest() != SHA256:
            raise BootstrapError("Official wheel failed exact size/SHA256 verification.")
        return bytes(body)
    finally:
        connection.close()


def download_wheel() -> bytes:
    # A separate owned worker gives DNS/TLS/slow-drip a hard overall boundary;
    # socket timeouts alone do not bound getaddrinfo or a stream of tiny reads.
    result = subprocess.run(
        [sys.executable, "-I", "-B", "-c",
         "import runpy,sys; module=runpy.run_path(sys.argv[1]); "
         "sys.stdout.buffer.write(module['_download_wheel']())",
         str(Path(__file__).resolve())],
        env=isolated_environment(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        check=True, timeout=DOWNLOAD_ADMISSION_SECONDS + 5,
    )
    return result.stdout


def isolated_environment() -> dict[str, str]:
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("PIP_") and key not in {"PYTHONPATH", "PYTHONHOME"}}
    env.update(PYTHONNOUSERSITE="1", PYTHONDONTWRITEBYTECODE="1", PIP_CONFIG_FILE=os.devnull)
    return env


def verify_installation() -> None:
    subprocess.run(
        [sys.executable, "-I", "-B", "-c",
         "import pip,pip._vendor.certifi as c,ssl,hashlib,sys; from pathlib import Path; "
         "assert pip.__version__ == '26.2.2'; "
         "assert c.__version__ == '2026.10.19'; "
         "assert Path(pip.__file__).resolve().is_relative_to(Path(sys.prefix).resolve()); "
         "bundle=Path(c.where()).resolve(); "
         "assert bundle == Path(pip.__file__).resolve().parent/'_vendor/certifi/cacert.pem'; "
         "assert hashlib.sha256(bundle.read_bytes()).hexdigest() == '" + VENDORED_CA_SHA256 + "'; "
         "ssl.create_default_context(cafile=c.where()); "
         "print('Verified pip 26.2.2 and vendored certifi 2026.10.19')"],
        env=isolated_environment(), check=True, timeout=15,
    )


def _verify_system_ca() -> dict[str, int]:
    """Read the actual default store, including OpenSSL's lazy hash directory."""
    if os.environ.get("SSL_CERT_FILE") or os.environ.get("SSL_CERT_DIR"):
        raise BootstrapError("System CA build verification requires unmodified default paths.")
    paths = ssl.get_default_verify_paths()
    if not paths.cafile or not paths.capath:
        raise BootstrapError("Both effective CA file and CA directory are required.")
    cafile = Path(paths.cafile).resolve(strict=True)
    capath = Path(paths.capath).resolve(strict=True)
    if not cafile.is_absolute() or not capath.is_absolute() or not capath.is_dir():
        raise BootstrapError("Effective CA paths are unavailable.")
    roots = tuple(path.resolve() for path in CA_TRUST_DIRECTORIES)
    if not any(cafile.is_relative_to(root) for root in roots) or not any(capath.is_relative_to(root) for root in roots):
        raise BootstrapError("Effective CA paths escape the reviewed system trust directories.")
    entries = []
    for entry in capath.iterdir():
        entries.append(entry)
        if len(entries) > MAX_CA_DIRECTORY_ENTRIES:
            raise BootstrapError("CA directory exceeds the reviewed bound.")
    if not entries:
        raise BootstrapError("CA directory is empty or exceeds the reviewed bound.")
    applicable = [entry for entry in entries
                  if re.fullmatch(r"[0-9A-Fa-f]{8}\.[0-9]+", entry.name)
                  or entry.suffix.lower() in {".pem", ".crt"}]
    # OpenSSL .rN entries are revocation lists, not certificate trust anchors.
    if not applicable:
        raise BootstrapError("No applicable CA directory certificates were found.")
    files = {cafile, *applicable}
    total = 0
    certificate_count = 0
    for path in sorted(files):
        resolved = path.resolve(strict=True)
        if not any(resolved.is_relative_to(root) for root in roots):
            raise BootstrapError("CA certificate link escapes reviewed trust directories.")
        fd = os.open(resolved, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as stream:
            metadata = os.fstat(stream.fileno())
            if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= MAX_CA_FILE_BYTES:
                raise BootstrapError("CA certificate input is not a bounded regular file.")
            body = stream.read(MAX_CA_FILE_BYTES + 1)
        total += len(body)
        if len(body) != metadata.st_size or total > MAX_CA_TOTAL_BYTES:
            raise BootstrapError("CA certificate store exceeds the reviewed byte bound.")
        blocks = re.findall(br"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", body, re.DOTALL)
        if (not blocks or body.count(b"-----BEGIN CERTIFICATE-----") != len(blocks)
                or body.count(b"-----END CERTIFICATE-----") != len(blocks)
                or re.search(br"-----BEGIN (?!CERTIFICATE-----)", body)):
            raise BootstrapError("Applicable CA input contains missing or malformed certificates.")
        for block in blocks:
            certificate_count += 1
            if certificate_count > MAX_CA_CERTIFICATES:
                raise BootstrapError("CA certificate count exceeds the reviewed bound.")
            try:
                pem = block.decode("ascii")
                der = ssl.PEM_cert_to_DER_cert(pem)
                # Decode with OpenSSL too: base64 validity alone is not X.509
                # validity. This does not make any network request.
                parsed = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                parsed.load_verify_locations(cadata=pem)
                canonical = parsed.get_ca_certs(binary_form=True)
                if len(canonical) != 1 or canonical[0] != der:
                    raise BootstrapError("CA certificate input is not one canonical CA certificate.")
            except (ValueError, UnicodeError, ssl.SSLError) as error:
                raise BootstrapError("CA certificate input is malformed.") from error
            if hashlib.sha256(canonical[0]).hexdigest() == REVOKED_CA_SHA256:
                raise BootstrapError("Advisory-specific compromised CA remains trusted.")
    context = ssl.create_default_context()
    loaded = context.get_ca_certs(binary_form=True)
    if not loaded or any(hashlib.sha256(der).hexdigest() == REVOKED_CA_SHA256 for der in loaded):
        raise BootstrapError("Default SSL context is empty or retains the compromised CA.")
    return {"certificate_inputs": len(files), "decoded_certificates": certificate_count,
            "default_loaded_roots": len(loaded)}


def verify_system_ca() -> None:
    subprocess.run(
        [sys.executable, "-I", "-B", "-c",
         "import runpy,json,sys; module=runpy.run_path(sys.argv[1]); "
         "print('Verified system CA stores: '+json.dumps(module['_verify_system_ca']()))",
         str(Path(__file__).resolve())],
        env=isolated_environment(), check=True, timeout=20, stderr=subprocess.DEVNULL,
    )


def verify_runtime() -> None:
    verify_system_ca()
    verify_installation()
    codec_probe = """import certifi
import hashlib
import io
import PIL
import ssl
from pathlib import Path
from PIL import Image

assert certifi.__version__ == "2026.10.19"
assert PIL.__version__ == "13.0.0"
assert hashlib.sha256(Path(certifi.where()).read_bytes()).hexdigest() == "50f79f98dac192b20dbf73f35c56f0b0c557ab267ff5199d1117cc1180b70785"
ssl.create_default_context(cafile=certifi.where())
# Exercise the actual Alpine wheel's native codecs, not only its metadata.
for image_format, mode in (("PNG", "RGBA"), ("PNG", "L"), ("JPEG", "RGB"), ("WEBP", "RGB")):
    color = (12, 34, 56, 78) if mode == "RGBA" else 42 if mode == "L" else (12, 34, 56)
    with Image.new(mode, (2, 2), color) as original:
        encoded = io.BytesIO()
        original.save(encoded, format=image_format)
    assert 0 < len(encoded.getvalue()) < 65536
    with Image.open(io.BytesIO(encoded.getvalue())) as decoded:
        assert decoded.format == image_format and decoded.size == (2, 2)
        decoded.load()
        assert decoded.mode == mode
        assert len(decoded.tobytes()) == 4 * len(decoded.getbands())
print("Verified runtime trust and PNG/JPEG/WebP codecs with Pillow 13.0.0")
"""
    subprocess.run(
        [sys.executable, "-I", "-B", "-c", codec_probe],
        env=isolated_environment(), check=True, timeout=15,
    )
    packages = subprocess.run(
        ["apk", "info", "-v"], env=isolated_environment(), check=True,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10,
    ).stdout.splitlines()
    for expected in ("ca-certificates-20261020-r0", "ca-certificates-bundle-20261020-r0"):
        if packages.count(expected) != 1:
            raise BootstrapError("Runtime CA package identity differs from the reviewed build.")


def trusted_temp_parent() -> Path:
    # Validate before tempfile can probe an inherited TMPDIR. Root must not
    # create executable artifacts below another user's writable directory.
    configured = next((os.environ[key] for key in ("TMPDIR", "TEMP", "TMP")
                       if os.environ.get(key)), "/tmp")
    parent = Path(configured)
    if not parent.is_absolute():
        raise BootstrapError("Temporary directory must be absolute.")
    parent = parent.resolve(strict=True)
    for directory in (parent, *parent.parents):
        metadata = directory.stat()
        if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid not in {0, os.getuid()}:
            raise BootstrapError("Temporary directory ancestry is not trusted.")
        writable = stat.S_IMODE(metadata.st_mode) & 0o022
        root_sticky = metadata.st_uid == 0 and metadata.st_mode & stat.S_ISVTX
        if writable and not root_sticky:
            raise BootstrapError("Temporary directory ancestry is writable without protection.")
    return parent


def bootstrap() -> None:
    if sys.version_info < (3, 10):
        raise BootstrapError("Verified pip requires Python 3.10 or newer.")
    parent = trusted_temp_parent()
    body = download_wheel()
    if len(body) != SIZE or hashlib.sha256(body).hexdigest() != SHA256:
        raise BootstrapError("Downloaded wheel failed exact size/SHA256 verification.")
    with tempfile.TemporaryDirectory(prefix="dcompany-verified-pip-", dir=parent) as temporary:
        directory = Path(temporary)
        metadata = directory.stat()
        if (metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != 0o700
                or directory.is_symlink()):
            raise BootstrapError("A private, owned temporary directory is required.")
        wheel = directory / FILENAME
        fd = os.open(wheel, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(body)
        env = isolated_environment()
        subprocess.run(
            [sys.executable, "-I", "-B", str(wheel) + "/pip", "--isolated", "--disable-pip-version-check",
             "install", "--no-index", "--no-deps", "--no-cache-dir", str(wheel)],
            env=env, check=True, timeout=INSTALL_TIMEOUT_SECONDS,
        )
        # A fresh interpreter avoids retaining the invoking process's old pip
        # modules. Top-level certifi is separately pinned by the runtime closure.
        verify_installation()


def main() -> int:
    if sys.argv[1:] not in ([], ["--verify-only"], ["--verify-system-ca"], ["--verify-runtime"]):
        print("Verified pip bootstrap accepts only explicit verification modes.", file=sys.stderr)
        return 1
    try:
        if sys.argv[1:] == ["--verify-only"]:
            verify_installation()
        elif sys.argv[1:] == ["--verify-system-ca"]:
            verify_system_ca()
        elif sys.argv[1:] == ["--verify-runtime"]:
            verify_runtime()
        else:
            bootstrap()
    except (BootstrapError, OSError, http.client.HTTPException, subprocess.SubprocessError) as error:
        # Do not interpolate network/library exception text into CI or installer
        # logs; only this fixed classification leaves the helper.
        print("Verified pip bootstrap failed (" + type(error).__name__ + ").", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

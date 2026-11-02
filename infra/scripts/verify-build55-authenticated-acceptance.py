#!/usr/bin/env python3
"""Bounded read-only acceptance using a legitimate normal-login access token.

The caller supplies private credential data; this tool never acquires, resets,
refreshes, decodes or mints credentials. Neither receipts nor failures echo it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

MAX_INPUT_BYTES = 16_384
MAX_RESPONSE_BYTES = 1_048_576
TOTAL_DEADLINE_SECONDS = 12


class AcceptanceError(ValueError):
    pass


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AcceptanceError("duplicate credential field")
        result[key] = value
    return result


def credentials(raw: bytes) -> dict:
    if not raw or len(raw) > MAX_INPUT_BYTES:
        raise AcceptanceError("credential input size is invalid")
    try:
        value = json.loads(raw, object_pairs_hook=unique_pairs)
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise AcceptanceError("credential input must be strict JSON") from exc
    fields = {
        "schema_version",
        "access_token",
        "expected_company_id",
        "expected_user_id",
        "expected_branch_id",
        "expected_terminal_id",
    }
    if (
        not isinstance(value, dict)
        or set(value) != fields
        or type(value["schema_version"]) is not int
        or value["schema_version"] != 1
    ):
        raise AcceptanceError("credential contract changed")
    if (
        not isinstance(value["access_token"], str)
        or not re.fullmatch(
            r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", value["access_token"]
        )
        or len(value["access_token"]) > 8192
    ):
        raise AcceptanceError("normal-login access token is missing or malformed")
    for key in fields - {"schema_version", "access_token"}:
        try:
            if str(UUID(value[key])) != value[key]:
                raise ValueError()
        except (ValueError, TypeError, AttributeError) as exc:
            raise AcceptanceError(
                "expected account/workspace identity must be canonical UUIDs"
            ) from exc
    return value


def private_input(path: Path) -> bytes:
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise AcceptanceError(
            "credential input must be an absolute regular nonlinked private file"
        )
    # Traverse parent directories through descriptors so a symlink swap or an
    # attacker-writable ancestor cannot redirect the final O_NOFOLLOW read.
    if any(part in {".", ".."} for part in path.parts):
        raise AcceptanceError("credential path must be canonical")
    resolved = path
    if str(resolved).startswith(("/var/lib/dcompany-erp/", "/opt/d-company-erp/")):
        raise AcceptanceError(
            "credential input must remain outside release and rollback archives"
        )
    parent_fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in resolved.parts[1:-1]:
            next_fd = os.open(
                part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd
            )
            os.close(parent_fd)
            parent_fd = next_fd
            ancestor = os.fstat(parent_fd)
            if ancestor.st_uid != 0 or stat.S_IMODE(ancestor.st_mode) & 0o022:
                raise AcceptanceError(
                    "credential ancestry must be protected and root-owned"
                )
        descriptor = os.open(
            resolved.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent_fd
        )
    finally:
        os.close(parent_fd)
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != 0
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or metadata.st_nlink != 1
            or metadata.st_size > MAX_INPUT_BYTES
        ):
            raise AcceptanceError(
                "credential input requires bounded root-owned mode0600 regular data"
            )
        return os.read(descriptor, MAX_INPUT_BYTES + 1)
    finally:
        os.close(descriptor)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise AcceptanceError("acceptance redirects are refused")


def verify(value: dict, base_url: str, *, opener=None, clock=time.monotonic) -> dict:
    opener = opener or urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect()
    )
    deadline = clock() + TOTAL_DEADLINE_SECONDS
    headers = {
        "Authorization": "Bearer " + value["access_token"],
        "X-Terminal-Id": value["expected_terminal_id"],
        "Accept": "application/json",
        "Cache-Control": "no-store",
    }

    def read(path: str):
        remaining = deadline - clock()
        if remaining <= 0:
            raise AcceptanceError("authenticated acceptance deadline expired")
        try:
            request = urllib.request.Request(
                base_url + path, headers=headers, method="GET"
            )
            with opener.open(request, timeout=remaining) as response:
                if response.status != 200:
                    raise AcceptanceError("authenticated read was not accepted")
                raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise AcceptanceError("authenticated response exceeded its bound")
            return json.loads(raw, object_pairs_hook=unique_pairs)
        except AcceptanceError:
            raise
        except (OSError, ValueError, urllib.error.URLError) as exc:
            raise AcceptanceError(
                "authenticated read could not be verified; keep ingress closed"
            ) from exc

    me = read("/api/v1/auth/me")
    if not isinstance(me, dict) or any(
        me.get(field) != value[expected]
        for field, expected in (
            ("company_id", "expected_company_id"),
            ("user_id", "expected_user_id"),
            ("branch_id", "expected_branch_id"),
        )
    ):
        raise AcceptanceError(
            "authoritative account/workspace did not match the reviewed identity"
        )
    permissions = me.get("effective_permissions")
    if not isinstance(permissions, list) or not {"gaming.read", "pos.read"}.issubset(
        permissions
    ):
        raise AcceptanceError(
            "normal account does not have the required operational read permissions"
        )
    stations = read("/api/v1/gaming/stations")
    shifts = read("/api/v1/pos/shifts?only_open=true&limit=200")
    for records in (stations, shifts):
        if not isinstance(records, list) or any(
            not isinstance(row, dict)
            or row.get("branch_id") != value["expected_branch_id"]
            for row in records
        ):
            raise AcceptanceError(
                "scoped operational read returned an unexpected workspace"
            )
    if shifts:
        raise AcceptanceError(
            "a shift opened during acceptance; preserve current work and stop deployment"
        )
    return {
        "schema_version": 1,
        "status": "authenticated_reads_verified",
        "verified_at_utc": datetime.now(UTC).isoformat(),
        "company_id": value["expected_company_id"],
        "user_id": value["expected_user_id"],
        "branch_id": value["expected_branch_id"],
        "terminal_id": value["expected_terminal_id"],
        "account_read": True,
        "gaming_read": True,
        "shift_read": True,
        "mutations": False,
    }


def internal_probe(raw: bytes, expected_digest: str, container_id: str) -> dict:
    if (
        not re.fullmatch(r"[a-f0-9]{64}", expected_digest)
        or hashlib.sha256(raw).hexdigest() != expected_digest
    ):
        raise AcceptanceError("credential input changed since authenticated preflight")
    if not re.fullmatch(r"[a-f0-9]{64}", container_id):
        raise AcceptanceError("candidate backend container identity is invalid")
    value = credentials(raw)
    # Only this reviewed checker and a fixed Docker argv are executable. The
    # access token is supplied through stdin, never environment or argv.
    try:
        result = subprocess.run(
            [
                "docker",
                "exec",
                "-i",
                container_id,
                "python",
                "-c",
                Path(__file__).read_text(),
                "internal",
            ],
            input=raw,
            capture_output=True,
            check=False,
            timeout=TOTAL_DEADLINE_SECONDS + 2,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise AcceptanceError(
            "internal authenticated acceptance did not complete"
        ) from exc
    if result.returncode or len(result.stdout) > MAX_INPUT_BYTES:
        raise AcceptanceError("internal authenticated acceptance was refused")
    try:
        receipt = json.loads(result.stdout, object_pairs_hook=unique_pairs)
        verified_at = datetime.fromisoformat(receipt["verified_at_utc"])
        age = (datetime.now(UTC) - verified_at).total_seconds()
    except (ValueError, TypeError, KeyError) as exc:
        raise AcceptanceError("internal receipt is invalid") from exc
    if (
        not isinstance(receipt, dict)
        or receipt.get("status") != "authenticated_reads_verified"
        or receipt.get("schema_version") != 1
        or receipt.get("mutations") is not False
        or verified_at.utcoffset() is None
        or verified_at.utcoffset().total_seconds() != 0
        or not -2 <= age <= TOTAL_DEADLINE_SECONDS + 2
        or any(
            receipt.get(field) != value[expected]
            for field, expected in (
                ("company_id", "expected_company_id"),
                ("user_id", "expected_user_id"),
                ("branch_id", "expected_branch_id"),
                ("terminal_id", "expected_terminal_id"),
            )
        )
        or any(
            receipt.get(field) is not True
            for field in ("account_read", "gaming_read", "shift_read")
        )
    ):
        raise AcceptanceError("internal authenticated receipt does not match preflight")
    # Return a fresh allowlisted receipt, never arbitrary subprocess output.
    return {
        key: receipt[key]
        for key in (
            "schema_version",
            "status",
            "verified_at_utc",
            "company_id",
            "user_id",
            "branch_id",
            "terminal_id",
            "account_read",
            "gaming_read",
            "shift_read",
            "mutations",
        )
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("public", "internal", "internal-probe"))
    parser.add_argument("--domain")
    parser.add_argument("--credential-file", type=Path)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--container-id")
    args = parser.parse_args()

    def expired(_signal, _frame):
        raise AcceptanceError("authenticated acceptance deadline expired")

    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(
        signal.ITIMER_REAL,
        TOTAL_DEADLINE_SECONDS + 3
        if args.mode == "internal-probe"
        else TOTAL_DEADLINE_SECONDS,
    )
    try:
        if args.mode == "internal-probe":
            if (
                args.domain
                or args.credential_file is None
                or not args.expected_sha256
                or not args.container_id
            ):
                raise AcceptanceError(
                    "internal probe requires the private input and attested container"
                )
            result = internal_probe(
                private_input(args.credential_file),
                args.expected_sha256,
                args.container_id,
            )
            print(json.dumps(result, sort_keys=True, separators=(",", ":")))
            return 0
        if args.expected_sha256 or args.container_id:
            raise AcceptanceError("unexpected acceptance inputs")
        if args.mode == "public":
            if (
                not args.domain
                or not re.fullmatch(
                    r"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}",
                    args.domain,
                )
                or args.credential_file is None
            ):
                raise AcceptanceError(
                    "public acceptance requires the configured plain HTTPS domain and private credential file"
                )
            raw = private_input(args.credential_file)
            base = "https://" + args.domain
        else:
            if args.domain or args.credential_file:
                raise AcceptanceError(
                    "internal acceptance uses only fixed localhost and private stdin"
                )
            raw = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
            base = "http://127.0.0.1:8000"
        result = verify(credentials(raw), base)
        if args.mode == "public":
            result["credential_input_sha256"] = hashlib.sha256(raw).hexdigest()
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except (AcceptanceError, OSError, ValueError):
        print(
            "Authenticated acceptance refused. Obtain a fresh normal-login credential through the approved workflow; saved work is unchanged.",
            file=sys.stderr,
        )
        return 1
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == "__main__":
    raise SystemExit(main())

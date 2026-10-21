"""Legitimate-token acceptance stays bounded, read-only and workspace-specific."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def tool():
    spec = importlib.util.spec_from_file_location(
        "build55_auth_acceptance",
        ROOT / "infra/scripts/verify-build55-authenticated-acceptance.py",
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def credential():
    return {
        "schema_version": 1,
        "access_token": "synthetic.normal.signature",
        **{
            "expected_" + key + "_id": str(UUID(int=index))
            for index, key in enumerate(("company", "user", "branch", "terminal"), 1)
        },
    }


class Response:
    status = 200

    def __init__(self, value):
        self.raw = value if isinstance(value, bytes) else json.dumps(value).encode()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, limit):
        return self.raw[:limit]


class Opener:
    def __init__(self, credential):
        self.responses = [
            {
                "company_id": credential["expected_company_id"],
                "user_id": credential["expected_user_id"],
                "branch_id": credential["expected_branch_id"],
                "effective_permissions": ["gaming.read", "pos.read"],
            },
            [{"branch_id": credential["expected_branch_id"], "id": str(UUID(int=99))}],
            [],
        ]
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        return Response(self.responses.pop(0))


def test_only_three_normal_readonly_endpoints_are_used_without_receipt_secrets(
    tool, credential
):
    opener = Opener(credential)
    receipt = tool.verify(credential, "https://erp.synthetic.invalid", opener=opener)
    assert [request.full_url for request, _ in opener.requests] == [
        "https://erp.synthetic.invalid/api/v1/auth/me",
        "https://erp.synthetic.invalid/api/v1/gaming/stations",
        "https://erp.synthetic.invalid/api/v1/pos/shifts?only_open=true&limit=200",
    ]
    assert all(
        request.get_method() == "GET" and timeout <= 12
        for request, timeout in opener.requests
    )
    assert all(
        request.get_header("X-terminal-id") == credential["expected_terminal_id"]
        for request, _ in opener.requests
    )
    assert receipt["status"] == "authenticated_reads_verified"
    assert receipt["mutations"] is False
    assert credential["access_token"] not in json.dumps(receipt)
    assert "Authorization" not in json.dumps(receipt)


@pytest.mark.parametrize(
    "change",
    ["company", "user", "branch", "station", "shift", "permissions", "oversize"],
)
def test_wrong_identity_new_shift_or_unbounded_response_refuses_acceptance(
    tool, credential, change
):
    opener = Opener(credential)
    if change in {"company", "user", "branch"}:
        opener.responses[0][change + "_id"] = str(UUID(int=100))
    elif change == "station":
        opener.responses[1][0]["branch_id"] = str(UUID(int=100))
    elif change == "shift":
        opener.responses[2] = [{"branch_id": credential["expected_branch_id"]}]
    elif change == "permissions":
        opener.responses[0]["effective_permissions"] = ["gaming.read"]
    else:
        opener.responses[0] = b"x" * (tool.MAX_RESPONSE_BYTES + 1)
    with pytest.raises(tool.AcceptanceError):
        tool.verify(credential, "https://erp.synthetic.invalid", opener=opener)


def test_input_is_closed_strict_and_bounded(tool, credential):
    assert tool.credentials(json.dumps(credential).encode()) == credential
    invalid = [
        b'{"schema_version":1,"schema_version":2}',
        b"{}",
        b"[]",
        b"x" * (tool.MAX_INPUT_BYTES + 1),
    ]
    for change in (
        {"schema_version": True},
        {"access_token": "not a token"},
        {"expected_company_id": "not-uuid"},
        {"unreviewed_command": "sh"},
    ):
        invalid.append(json.dumps({**credential, **change}).encode())
    for raw in invalid:
        with pytest.raises(tool.AcceptanceError):
            tool.credentials(raw)


def test_no_redirect_or_environment_proxy_can_change_the_fixed_destination(
    tool, credential, monkeypatch
):
    captured = []
    opener = Opener(credential)

    def build(*handlers):
        captured.extend(handlers)
        return opener

    monkeypatch.setattr(tool.urllib.request, "build_opener", build)
    tool.verify(credential, "https://erp.synthetic.invalid")
    proxies = [
        handler
        for handler in captured
        if isinstance(handler, tool.urllib.request.ProxyHandler)
    ]
    assert len(proxies) == 1
    assert proxies[0].proxies == {}
    redirect = next(
        handler for handler in captured if isinstance(handler, tool.NoRedirect)
    )
    with pytest.raises(tool.AcceptanceError, match="redirects"):
        redirect.redirect_request(
            None, None, 302, "redirect", {}, "https://other.invalid"
        )


def _receipt(credential, *, seconds_ago=0):
    return {
        "schema_version": 1,
        "status": "authenticated_reads_verified",
        "verified_at_utc": (
            datetime.now(UTC) - timedelta(seconds=seconds_ago)
        ).isoformat(),
        **{
            key + "_id": credential["expected_" + key + "_id"]
            for key in ("company", "user", "branch", "terminal")
        },
        "account_read": True,
        "gaming_read": True,
        "shift_read": True,
        "mutations": False,
    }


def test_internal_probe_transfers_only_descriptor_verified_input_through_stdin(
    tool, credential, monkeypatch
):
    raw = json.dumps(credential).encode()
    receipt = _receipt(credential)
    receipt["unexpected_secret"] = credential["access_token"]
    calls = []

    def run(argv, **kwargs):
        calls.append((argv, kwargs))
        return SimpleNamespace(
            returncode=0, stdout=json.dumps(receipt).encode(), stderr=b""
        )

    monkeypatch.setattr(tool.subprocess, "run", run)
    result = tool.internal_probe(raw, hashlib.sha256(raw).hexdigest(), "a" * 64)
    assert len(calls) == 1
    argv, kwargs = calls[0]
    assert argv[:6] == ["docker", "exec", "-i", "a" * 64, "python", "-c"]
    assert argv[-1] == "internal"
    assert credential["access_token"] not in " ".join(argv)
    assert kwargs["input"] == raw
    assert "env" not in kwargs
    assert kwargs["timeout"] == 14
    assert "unexpected_secret" not in result
    assert credential["access_token"] not in json.dumps(result)


def test_input_change_or_untrusted_container_never_invokes_docker(
    tool, credential, monkeypatch
):
    raw = json.dumps(credential).encode()
    calls = []
    monkeypatch.setattr(
        tool.subprocess, "run", lambda *args, **kwargs: calls.append(args)
    )
    for digest, container in (
        ("b" * 64, "a" * 64),
        (hashlib.sha256(raw).hexdigest(), "backend; arbitrary"),
    ):
        with pytest.raises(tool.AcceptanceError):
            tool.internal_probe(raw, digest, container)
    assert calls == []


@pytest.mark.parametrize(
    "change", ["stale", "identity", "failed", "unbounded", "mutated"]
)
def test_internal_receipt_is_fresh_exact_and_categorical(
    tool, credential, monkeypatch, change
):
    raw = json.dumps(credential).encode()
    receipt = _receipt(credential, seconds_ago=60 if change == "stale" else 0)
    if change == "identity":
        receipt["branch_id"] = str(UUID(int=100))
    if change == "mutated":
        receipt["mutations"] = True
    result = SimpleNamespace(
        returncode=1 if change == "failed" else 0,
        stdout=b"x" * (tool.MAX_INPUT_BYTES + 1)
        if change == "unbounded"
        else json.dumps(receipt).encode(),
        stderr=credential["access_token"].encode(),
    )
    monkeypatch.setattr(tool.subprocess, "run", lambda *args, **kwargs: result)
    with pytest.raises(tool.AcceptanceError) as error:
        tool.internal_probe(raw, hashlib.sha256(raw).hexdigest(), "a" * 64)
    assert credential["access_token"] not in str(error.value)


def test_linked_private_input_is_refused_without_reading_its_target(
    tool, credential, tmp_path
):
    target = tmp_path / "credential"
    target.write_text(json.dumps(credential))
    target.chmod(0o600)
    linked = tmp_path / "linked"
    linked.symlink_to(target)
    with pytest.raises(tool.AcceptanceError):
        tool.private_input(linked)

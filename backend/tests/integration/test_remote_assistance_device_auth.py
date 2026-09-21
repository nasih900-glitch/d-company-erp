"""Real P-256, tenant, replay, pairing, rotation, and lifecycle proof."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from sqlalchemy import event, or_, select, text

from app.api.v1.client_installations.router import (
    GamingCleanupLocalSnapshot,
    GamingCleanupReportWrite,
    gaming_cleanup_action_hashes,
    gaming_cleanup_candidate_sha256,
    gaming_cleanup_snapshot_sha256,
)
from app.api.v1.remote_assistance import router as remote_router
from app.core.security import hash_password, issue_access_token
from app.models import (
    AuditLog,
    Branch,
    ClientGamingCleanupReconciliation,
    ClientInstallation,
    Company,
    RemoteAssistanceDeviceKey,
    RemoteAssistanceGrant,
    RemoteAssistanceSession,
    Role,
    Station,
    Terminal,
    User,
    UserRole,
)
from app.services.audit.recorder import install_audit_listeners
from app.services.remote_assistance.device_auth import (
    canonical_enrollment_statement,
    canonical_request_statement,
)

_CLEANUP_PROTOCOL_FIXTURE = (
    Path(__file__).resolve().parents[3] / "protocol-fixtures" / "gaming_cleanup_full_flow_v1.json"
)
_ANDROID_CLEANUP_DIRECTIVE_FIELDS = (
    "id",
    "installation_id",
    "branch_id",
    "terminal_id",
    "station_id",
    "local_action_id",
    "server_session_id",
    "revision",
    "is_current",
    "reported_local_state",
    "local_evidence_revision",
    "local_snapshot_sha256",
    "candidate_sha256",
    "unresolved_child_count",
    "cleanup_receipt_audit_id",
    "status",
    "approval_reason",
    "device_directive",
)


def _cleanup_protocol_fixture() -> dict[str, object]:
    return json.loads(_CLEANUP_PROTOCOL_FIXTURE.read_text(encoding="utf-8"))


def _android_cleanup_directive(body: dict[str, object]) -> dict[str, object]:
    return {field: body[field] for field in _ANDROID_CLEANUP_DIRECTIVE_FIELDS}


@pytest_asyncio.fixture(autouse=True)
async def require_device_key_migration(session) -> None:
    try:
        await session.execute(text("select 1 from remote_assistance_device_keys limit 1"))
    except Exception as exc:
        pytest.skip(f"device-key migration/local PostgreSQL unavailable: {exc}")


@pytest.fixture(autouse=True)
def bypass_non_auth_coordination(monkeypatch) -> None:
    async def allow(*_args, **_kwargs) -> None:
        return None

    monkeypatch.setattr(remote_router, "enforce_client_heartbeat_rate_limit", allow)
    monkeypatch.setattr(remote_router, "delete_latest_frame", allow)


def _auth_headers(seed: dict, *, roles: list[str], android: bool) -> dict[str, str]:
    user = seed["owner"]
    token = issue_access_token(
        user_id=user.id,
        company_id=user.company_id,
        roles=roles,
        branch_id=seed["branch"].id,
        auth_version=user.auth_version,
        extra={
            "protected_access": "super_owner" in roles,
            "audit_access": "super_owner" in roles,
        },
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed["terminal"].id),
    }
    if android:
        headers.update(
            {
                "X-Client-Platform": "android",
                "X-Client-Version-Code": "37",
                "X-Client-Distribution-Channel": "direct",
            }
        )
    return headers


async def _installation(
    session,
    seed: dict,
    *,
    installation_id: UUID | None = None,
) -> ClientInstallation:
    now = datetime.now(UTC)
    row = ClientInstallation(
        id=uuid4(),
        company_id=seed["company"].id,
        installation_id=installation_id or uuid4(),
        registered_by_user_id=seed["owner"].id,
        last_user_id=seed["owner"].id,
        terminal_id=seed["terminal"].id,
        platform="android",
        distribution_channel="direct",
        version_name="3.1.29",
        version_code=37,
        pending_outbox_count=0,
        last_successful_sync_at=now,
        update_state="idle",
        update_error_code=None,
        last_seen_at=now,
    )
    session.add(row)
    await session.commit()
    return row


async def _second_tenant(session) -> dict:
    company = Company(id=uuid4(), name=f"OtherCo-{uuid4().hex[:6]}")
    branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Other Main",
        invoice_series_code="OT",
    )
    terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Other Hybrid",
        device_id=f"other-{uuid4()}",
    )
    role = Role(
        id=uuid4(),
        company_id=company.id,
        code="super_owner",
        name="Protected Owner",
        permissions=[],
    )
    user = User(
        id=uuid4(),
        company_id=company.id,
        email=f"other-{uuid4().hex[:8]}@test.local",
        name="Other Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add_all([company, branch, terminal, role, user])
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=user.id, role_id=role.id))
    await session.commit()
    await session.refresh(user)
    return {"company": company, "branch": branch, "terminal": terminal, "owner": user}


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


@dataclass(frozen=True, slots=True)
class DeviceKeyMaterial:
    key_id: UUID
    enrollment_id: UUID
    private_key: ec.EllipticCurvePrivateKey
    spki_der: bytes
    fingerprint: str


def _new_key() -> DeviceKeyMaterial:
    private_key = ec.generate_private_key(ec.SECP256R1())
    spki_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return DeviceKeyMaterial(
        key_id=uuid4(),
        enrollment_id=uuid4(),
        private_key=private_key,
        spki_der=spki_der,
        fingerprint=hashlib.sha256(spki_der).hexdigest(),
    )


def _enrollment_payload(
    *,
    seed: dict,
    installation_id: UUID,
    key: DeviceKeyMaterial,
    epoch: int | None = None,
    nonce: UUID | None = None,
    signer: ec.EllipticCurvePrivateKey | None = None,
) -> dict[str, object]:
    epoch = epoch if epoch is not None else int(datetime.now(UTC).timestamp())
    nonce = nonce or uuid4()
    statement = canonical_enrollment_statement(
        company_id=seed["company"].id,
        installation_id=installation_id,
        key_id=key.key_id,
        enrollment_id=key.enrollment_id,
        signed_at_epoch_seconds=epoch,
        nonce=nonce,
        fingerprint_sha256=key.fingerprint,
    )
    signature = (signer or key.private_key).sign(statement, ec.ECDSA(hashes.SHA256()))
    return {
        "key_id": str(key.key_id),
        "enrollment_id": str(key.enrollment_id),
        "installation_id": str(installation_id),
        "public_key_spki": _b64url(key.spki_der),
        "signed_at_epoch_seconds": epoch,
        "nonce": str(nonce),
        "signature": _b64url(signature),
    }


def _device_signature_headers(
    *,
    key: DeviceKeyMaterial,
    method: str,
    raw_target: str,
    content: bytes,
    epoch: int | None = None,
    nonce: UUID | None = None,
    signer: ec.EllipticCurvePrivateKey | None = None,
) -> tuple[dict[str, str], UUID]:
    epoch = epoch if epoch is not None else int(datetime.now(UTC).timestamp())
    nonce = nonce or uuid4()
    content_hash = hashlib.sha256(content).hexdigest()
    statement = canonical_request_statement(
        method=method,
        raw_target=raw_target.encode("ascii"),
        content_sha256=content_hash,
        signed_at_epoch_seconds=epoch,
        nonce=nonce,
        key_id=key.key_id,
    )
    signature = (signer or key.private_key).sign(statement, ec.ECDSA(hashes.SHA256()))
    return (
        {
            "X-ERP-Device-Key-Id": str(key.key_id),
            "X-ERP-Device-Timestamp": str(epoch),
            "X-ERP-Device-Nonce": str(nonce),
            "X-ERP-Content-SHA256": content_hash,
            "X-ERP-Device-Signature": _b64url(signature),
        },
        nonce,
    )


async def _enroll(client, seed: dict, installation_id: UUID, key: DeviceKeyMaterial):
    return await client.post(
        "/api/v1/remote-assistance/device/keys/enroll",
        headers=_auth_headers(seed, roles=["staff"], android=True),
        json=_enrollment_payload(seed=seed, installation_id=installation_id, key=key),
    )


async def _status(client, seed: dict, installation_id: UUID, key: DeviceKeyMaterial):
    target = (
        f"/api/v1/remote-assistance/device/keys/{key.key_id}/status"
        f"?installation_id={installation_id}"
    )
    signature_headers, _ = _device_signature_headers(
        key=key,
        method="GET",
        raw_target=target,
        content=b"",
    )
    return await client.get(
        target,
        headers={
            **_auth_headers(seed, roles=["staff"], android=True),
            **signature_headers,
        },
    )


async def _approve(client, seed: dict, key_id: UUID, pairing: str, approval_id: UUID):
    return await client.post(
        f"/api/v1/remote-assistance/device-keys/{key_id}/approve",
        headers=_auth_headers(seed, roles=["super_owner"], android=False),
        json={"approval_id": str(approval_id), "pairing_code": pairing},
    )


async def _signed_json(
    client,
    *,
    seed: dict,
    key: DeviceKeyMaterial,
    method: str,
    target: str,
    payload: dict[str, object],
    nonce: UUID | None = None,
    epoch: int | None = None,
    signer: ec.EllipticCurvePrivateKey | None = None,
    signed_content: bytes | None = None,
    extra_headers: dict[str, str] | None = None,
    roles: list[str] | None = None,
):
    content = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    signature_headers, used_nonce = _device_signature_headers(
        key=key,
        method=method,
        raw_target=target,
        content=signed_content if signed_content is not None else content,
        nonce=nonce,
        epoch=epoch,
        signer=signer,
    )
    response = await client.request(
        method,
        target,
        headers={
            **_auth_headers(seed, roles=roles or ["staff"], android=True),
            **signature_headers,
            **(extra_headers or {}),
            "Content-Type": "application/json",
        },
        content=content,
    )
    return response, used_nonce


def _cleanup_receipt(
    *,
    seed: dict,
    report: GamingCleanupReportWrite,
    audit_id: int | None = None,
) -> AuditLog:
    local_ids = [report.local_action_id, *(uuid4() for _ in range(4))]
    session_ids = [report.server_session_id, *(uuid4() for _ in range(4))]
    fence: list[dict[str, object]] = []
    for local_id in local_ids:
        for action in ("start", "stop"):
            is_target = local_id == report.local_action_id
            request_hash = (
                (report.start_request_hash if action == "start" else report.stop_request_hash)
                if is_target
                else ("a" if action == "start" else "b") * 64
            )
            fence.append(
                {
                    "action_type": "idempotency",
                    "action_key": f"gaming-session-{action}:{local_id}",
                    "request_hash": request_hash,
                    "user_id": str(seed["owner"].id),
                    "terminal_id": str(seed["terminal"].id),
                    "source_entity_id": None,
                }
            )
    for index in range(3):
        fence.append(
            {
                "action_type": "shift_open",
                "action_key": f"shift-open:{index + 1:02d}",
                "request_hash": "c" * 64,
                "user_id": str(seed["owner"].id),
                "terminal_id": str(seed["terminal"].id),
                "source_entity_id": str(uuid4()),
            }
        )
    fence.sort(key=lambda item: str(item["action_key"]))
    idempotency_keys = [
        item["action_key"] for item in fence if item["action_type"] == "idempotency"
    ]
    source = "1" * 40
    values: dict[str, object] = {
        "actor_user_id": seed["owner"].id,
        "company_id": seed["company"].id,
        "action": "production_trial_cleanup",
        "entity_type": "ReleaseCleanup",
        "entity_id": "code30.1-20260920",
        "before": {
            "schema_revision": "0078",
            "state_fingerprint": "d" * 64,
            "backup_sha256": "e" * 64,
            "quarantine_evidence_sha256": "f" * 64,
            "counts": {},
            "audit_log": {},
        },
        "after": {
            "source_git_sha": source,
            "backend_image_id": f"sha256:{'2' * 64}",
            "executor": f"install-on-vm:{source}",
            "executed_at": "2026-09-20T04:30:00+00:00",
            "deleted_counts": {
                "audit_log": 37,
                "idempotency_keys": 10,
                "gaming_sessions": 5,
                "order_lines": 3,
                "orders": 3,
                "menu_items": 1,
                "shifts": 3,
            },
            "deleted_shift_ids": [],
            "deleted_order_ids": [],
            "deleted_order_line_ids": [],
            "deleted_gaming_session_ids": sorted(str(value) for value in session_ids),
            "deleted_menu_item_ids": [],
            "retired_test_installation": {},
            "local_avd_quarantine": {},
            "deleted_idempotency_keys": idempotency_keys,
            "deleted_audit_ids": [],
            "replay_fence": fence,
            "expected_post_counts": {},
        },
        "user_agent": "cleanup-code30-production-trial-data/2",
        "terminal_id": seed["terminal"].id,
        "request_id": "code30.1-production-trial-cleanup-20260920",
        "client_action_id": "production-trial-cleanup-20260920",
        "client_was_offline": None,
        "synced_at": None,
    }
    if audit_id is not None:
        values["id"] = audit_id
    return AuditLog(**values)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cleanup_report_requires_exact_active_installation_proof(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    key = _new_key()
    enrolled = await _enroll(client, seed_owner, installation.installation_id, key)
    assert enrolled.status_code == 200, enrolled.text
    approved = await _approve(
        client, seed_owner, key.key_id, enrolled.json()["pairing_code"], uuid4()
    )
    assert approved.status_code == 200, approved.text

    snapshot = GamingCleanupLocalSnapshot(
        shift_id=uuid4(),
        started_at_millis=1_795_000_000_123,
        ended_at_millis=1_795_003_600_456,
        rate_per_hour_minor=15_000,
        extra_controllers=0,
        evidence_revision=7,
    )
    report = GamingCleanupReportWrite(
        installation_id=installation.installation_id,
        local_action_id=uuid4(),
        server_session_id=uuid4(),
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        station_id=uuid4(),
        reported_local_state="stop_pending",
        local_snapshot=snapshot,
        local_snapshot_sha256=gaming_cleanup_snapshot_sha256(snapshot),
        start_request_hash="0" * 64,
        stop_request_hash="0" * 64,
        candidate_sha256="0" * 64,
        unresolved_child_count=0,
    )
    start_hash, stop_hash = gaming_cleanup_action_hashes(report)
    report = report.model_copy(
        update={"start_request_hash": start_hash, "stop_request_hash": stop_hash}
    )
    report = report.model_copy(update={"candidate_sha256": gaming_cleanup_candidate_sha256(report)})
    payload = report.model_dump(mode="json")
    target = "/api/v1/client-installations/gaming-cleanup-reconciliations/report"
    installation_headers = {"X-Installation-Id": str(installation.installation_id)}

    browser = await client.post(
        target,
        headers=_auth_headers(seed_owner, roles=["super_owner"], android=False),
        json=payload,
    )
    assert browser.status_code in {401, 403, 422}
    acknowledgement_target = (
        f"/api/v1/client-installations/gaming-cleanup-reconciliations/{uuid4()}/acknowledge"
    )
    browser_ack = await client.post(
        acknowledgement_target,
        headers=_auth_headers(seed_owner, roles=["super_owner"], android=False),
        json={
            "installation_id": str(installation.installation_id),
            "expected_candidate_sha256": report.candidate_sha256,
        },
    )
    assert browser_ack.status_code in {401, 403, 422}

    valid, nonce = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target=target,
        payload=payload,
        extra_headers=installation_headers,
        roles=["gaming_supervisor"],
    )
    # Device proof passed; the deliberately absent historical receipt blocks later.
    assert valid.status_code == 422, valid.text
    assert "cleanup receipt" in valid.text.lower()

    content = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    proof_headers, _ = _device_signature_headers(
        key=key,
        method="POST",
        raw_target=target + "?changed=true",
        content=content,
    )
    wrong_target = await client.post(
        target,
        headers={
            **_auth_headers(seed_owner, roles=["gaming_supervisor"], android=True),
            **installation_headers,
            **proof_headers,
            "Content-Type": "application/json",
        },
        content=content,
    )
    assert wrong_target.status_code == 401

    wrong_installation, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target=target,
        payload=payload,
        extra_headers={"X-Installation-Id": str(uuid4())},
        roles=["gaming_supervisor"],
    )
    assert wrong_installation.status_code == 422

    replay_headers, _ = _device_signature_headers(
        key=key, method="POST", raw_target=target, content=content, nonce=nonce
    )
    replay = await client.post(
        target,
        headers={
            **_auth_headers(seed_owner, roles=["gaming_supervisor"], android=True),
            **installation_headers,
            **replay_headers,
            "Content-Type": "application/json",
        },
        content=content,
    )
    assert replay.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cleanup_report_approved_revision_race_and_acknowledgement_full_flow(
    client,
    session,
    seed_owner,
    request,
) -> None:
    install_audit_listeners()
    company_id = seed_owner["company"].id
    fixture = _cleanup_protocol_fixture()
    assert fixture["schema"] == "dcompany.gaming-cleanup-full-flow.v1"
    initial_payload = fixture["initial_report"]
    approved_payload = fixture["approved_report"]
    expected_directive = fixture["approved_directive"]
    expected_acknowledgement = fixture["acknowledgement"]
    assert isinstance(initial_payload, dict)
    assert isinstance(approved_payload, dict)
    assert isinstance(expected_directive, dict)
    assert isinstance(expected_acknowledgement, dict)

    branch = Branch(
        id=UUID(str(initial_payload["branch_id"])),
        company_id=seed_owner["company"].id,
        name="Cleanup protocol fixture branch",
        code="CPF",
        invoice_series_code="PX",
    )
    terminal = Terminal(
        id=UUID(str(initial_payload["terminal_id"])),
        branch_id=branch.id,
        name="Cleanup protocol fixture terminal",
        device_id="cleanup-protocol-fixture-terminal",
    )
    fixture_seed = {**seed_owner, "branch": branch, "terminal": terminal}
    session.add_all([branch, terminal])
    await session.flush()
    installation = await _installation(
        session,
        fixture_seed,
        installation_id=UUID(str(initial_payload["installation_id"])),
    )

    reconciliation_ids = iter(
        (
            UUID(str(fixture["initial_reconciliation_id"])),
            UUID(str(expected_directive["id"])),
        )
    )
    fixture_local_action_id = UUID(str(initial_payload["local_action_id"]))

    def assign_fixture_reconciliation_id(
        _mapper: object,
        _connection: object,
        target: ClientGamingCleanupReconciliation,
    ) -> None:
        if target.local_action_id == fixture_local_action_id and target.id is None:
            target.id = next(reconciliation_ids)

    event.listen(
        ClientGamingCleanupReconciliation,
        "before_insert",
        assign_fixture_reconciliation_id,
    )
    request.addfinalizer(
        lambda: event.remove(
            ClientGamingCleanupReconciliation,
            "before_insert",
            assign_fixture_reconciliation_id,
        )
    )

    key = _new_key()
    enrolled = await _enroll(client, fixture_seed, installation.installation_id, key)
    assert enrolled.status_code == 200, enrolled.text
    key_approval = await _approve(
        client, fixture_seed, key.key_id, enrolled.json()["pairing_code"], uuid4()
    )
    assert key_approval.status_code == 200, key_approval.text

    station = Station(
        id=UUID(str(initial_payload["station_id"])),
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        code="PS5-01",
        name="PS5 Station 1",
        type="ps5",
        rate_per_hour_minor=12_000,
        is_active=True,
        notes=None,
        sac_code="999692",
        tax_rate=0.18,
        rate_includes_tax=True,
    )
    report = GamingCleanupReportWrite.model_validate(initial_payload)
    newer = GamingCleanupReportWrite.model_validate(approved_payload)
    assert gaming_cleanup_snapshot_sha256(report.local_snapshot) == report.local_snapshot_sha256
    assert gaming_cleanup_action_hashes(report) == (
        report.start_request_hash,
        report.stop_request_hash,
    )
    assert gaming_cleanup_candidate_sha256(report) == report.candidate_sha256
    assert gaming_cleanup_snapshot_sha256(newer.local_snapshot) == newer.local_snapshot_sha256
    assert gaming_cleanup_action_hashes(newer) == (
        newer.start_request_hash,
        newer.stop_request_hash,
    )
    assert gaming_cleanup_candidate_sha256(newer) == newer.candidate_sha256
    assert newer.local_snapshot.evidence_revision == report.local_snapshot.evidence_revision + 1
    session.add_all(
        [
            station,
            _cleanup_receipt(
                seed=fixture_seed,
                report=report,
                audit_id=int(expected_directive["cleanup_receipt_audit_id"]),
            ),
        ]
    )
    await session.commit()

    report_target = "/api/v1/client-installations/gaming-cleanup-reconciliations/report"
    install_headers = {"X-Installation-Id": str(installation.installation_id)}
    first_report, _ = await _signed_json(
        client,
        seed=fixture_seed,
        key=key,
        method="POST",
        target=report_target,
        payload=report.model_dump(mode="json"),
        extra_headers=install_headers,
        roles=["gaming_supervisor"],
    )
    assert first_report.status_code == 200, first_report.text
    first = first_report.json()
    assert first["id"] == fixture["initial_reconciliation_id"]
    assert first["status"] == "reported"
    assert first["review"] == {
        "amount_minor": 12_000,
        "billable_minutes": 54,
        "started_at": "2026-11-18T11:06:40.123000Z",
        "ended_at": "2026-11-18T12:00:40.456000Z",
    }
    serialized_first = json.dumps(first).lower()
    assert "private cleanup customer" not in serialized_first
    assert "+919999999999" not in serialized_first
    assert "customer_name" not in serialized_first
    assert "customer_phone" not in serialized_first
    assert "customer_id" not in serialized_first

    owner_headers = _auth_headers(fixture_seed, roles=["super_owner"], android=False)
    owner_listing = await client.get(
        "/api/v1/client-installations/gaming-cleanup-reconciliations",
        headers=owner_headers,
    )
    assert owner_listing.status_code == 200, owner_listing.text
    listed = next(item for item in owner_listing.json()["items"] if item["id"] == first["id"])
    assert listed["station_id"] == str(station.id)
    assert listed["review"] == first["review"]
    assert "private cleanup customer" not in json.dumps(listed).lower()
    assert "+919999999999" not in json.dumps(listed)

    first_approval_body = {
        "expected_candidate_sha256": first["candidate_sha256"],
        "reason": "Reviewed exact Station 1 amount and duration",
    }
    first_approval = await client.post(
        f"/api/v1/client-installations/gaming-cleanup-reconciliations/{first['id']}/approve",
        headers={**owner_headers, "Idempotency-Key": str(uuid4())},
        json=first_approval_body,
    )
    assert first_approval.status_code == 200, first_approval.text
    assert first_approval.json()["status"] == "approved"

    second_report, _ = await _signed_json(
        client,
        seed=fixture_seed,
        key=key,
        method="POST",
        target=report_target,
        payload=newer.model_dump(mode="json"),
        extra_headers=install_headers,
        roles=["gaming_supervisor"],
    )
    assert second_report.status_code == 200, second_report.text
    second = second_report.json()
    assert second["revision"] == 2
    assert second["status"] == "reported"
    assert second["candidate_sha256"] != first["candidate_sha256"]

    await session.rollback()
    historical = (
        (
            await session.execute(
                select(ClientGamingCleanupReconciliation)
                .where(
                    ClientGamingCleanupReconciliation.client_installation_id == installation.id,
                    ClientGamingCleanupReconciliation.local_action_id == report.local_action_id,
                )
                .order_by(ClientGamingCleanupReconciliation.revision)
            )
        )
        .scalars()
        .all()
    )
    assert [row.status for row in historical] == ["superseded", "reported"]
    assert historical[0].approved_at is not None
    assert historical[0].approved_by == seed_owner["owner"].id
    assert historical[0].approval_reason == first_approval_body["reason"]
    assert historical[0].approval_idempotency_key is not None
    assert historical[0].superseded_at is not None

    second_approval = await client.post(
        f"/api/v1/client-installations/gaming-cleanup-reconciliations/{second['id']}/approve",
        headers={**owner_headers, "Idempotency-Key": str(uuid4())},
        json={
            "expected_candidate_sha256": second["candidate_sha256"],
            "reason": expected_directive["approval_reason"],
        },
    )
    assert second_approval.status_code == 200, second_approval.text
    approved = second_approval.json()
    assert _android_cleanup_directive(approved) == expected_directive

    acknowledgement_target = (
        f"/api/v1/client-installations/gaming-cleanup-reconciliations/{second['id']}/acknowledge"
    )
    mismatched_acknowledgement, _ = await _signed_json(
        client,
        seed=fixture_seed,
        key=key,
        method="POST",
        target=acknowledgement_target,
        payload={
            "installation_id": str(installation.installation_id),
            "expected_candidate_sha256": "0" * 64,
        },
        extra_headers=install_headers,
        roles=["gaming_supervisor"],
    )
    assert mismatched_acknowledgement.status_code == 409

    acknowledgement, _ = await _signed_json(
        client,
        seed=fixture_seed,
        key=key,
        method="POST",
        target=acknowledgement_target,
        payload={
            "installation_id": str(installation.installation_id),
            "expected_candidate_sha256": second["candidate_sha256"],
        },
        extra_headers=install_headers,
        roles=["gaming_supervisor"],
    )
    assert acknowledgement.status_code == 200, acknowledgement.text
    assert _android_cleanup_directive(acknowledgement.json()) == expected_acknowledgement

    repeated_ack, _ = await _signed_json(
        client,
        seed=fixture_seed,
        key=key,
        method="POST",
        target=acknowledgement_target,
        payload={
            "installation_id": str(installation.installation_id),
            "expected_candidate_sha256": second["candidate_sha256"],
        },
        extra_headers=install_headers,
        roles=["gaming_supervisor"],
    )
    assert repeated_ack.status_code == 200, repeated_ack.text
    assert repeated_ack.json()["applied_at"] == acknowledgement.json()["applied_at"]

    await session.rollback()
    audit_rows = (
        (
            await session.execute(
                select(AuditLog).where(AuditLog.company_id == company_id).order_by(AuditLog.id)
            )
        )
        .scalars()
        .all()
    )
    cleanup_audits = [
        row for row in audit_rows if row.entity_type == "ClientGamingCleanupReconciliation"
    ]
    assert [row.action for row in cleanup_audits].count("create") == 2
    assert [row.action for row in cleanup_audits].count("update") == 4
    create_snapshots = [row.after for row in cleanup_audits if row.action == "create"]
    assert all(snapshot is not None for snapshot in create_snapshots)
    assert {
        snapshot["local_snapshot_sha256"] for snapshot in create_snapshots if snapshot is not None
    } == {
        report.local_snapshot_sha256,
        newer.local_snapshot_sha256,
    }
    assert all(
        snapshot["local_snapshot"] == "***REDACTED***"
        for snapshot in create_snapshots
        if snapshot is not None
    )

    # Scan every field from both generic and explicit tenant audit rows. This
    # covers create/update before+after payloads, action/reason/request
    # metadata, the explicit production cleanup receipt, and remote-device
    # enrollment/approval audit entries created by this signed flow.
    audit_material = [
        {column.name: getattr(row, column.name) for column in AuditLog.__table__.columns}
        for row in audit_rows
    ]
    serialized_audit = json.dumps(audit_material, default=str).lower()
    for private_sentinel in (
        "private cleanup customer",
        "+919999999999",
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        '"customer_name"',
        '"customer_phone"',
        '"customer_id"',
    ):
        assert private_sentinel not in serialized_audit
    assert any(row.action == "production_trial_cleanup" for row in audit_rows)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_device_list_returns_every_tenant_installation_in_recency_order(
    client,
    session,
    seed_owner,
) -> None:
    older = await _installation(session, seed_owner)
    newer = await _installation(session, seed_owner)
    other = await _second_tenant(session)
    other_installation = await _installation(session, other)

    listing = await client.get(
        "/api/v1/remote-assistance/devices",
        headers=_auth_headers(seed_owner, roles=["super_owner"], android=False),
    )
    assert listing.status_code == 200, listing.text
    body = listing.json()
    assert body["total"] == 2
    assert [row["installation_id"] for row in body["items"]] == [
        str(newer.installation_id),
        str(older.installation_id),
    ]
    assert str(other_installation.installation_id) not in {
        row["installation_id"] for row in body["items"]
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pairing_hides_pending_fingerprint_and_gates_device_requests(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    key = _new_key()
    wrong_key = _new_key()
    device_headers = _auth_headers(seed_owner, roles=["staff"], android=True)

    forged_enrollment = await client.post(
        "/api/v1/remote-assistance/device/keys/enroll",
        headers=device_headers,
        json=_enrollment_payload(
            seed=seed_owner,
            installation_id=installation.installation_id,
            key=key,
            signer=wrong_key.private_key,
        ),
    )
    assert forged_enrollment.status_code == 401

    enrolled = await _enroll(client, seed_owner, installation.installation_id, key)
    assert enrolled.status_code == 200, enrolled.text
    pending = enrolled.json()
    assert pending["status"] == "pending"
    assert pending["fingerprint_sha256"] == key.fingerprint
    assert len(pending["pairing_code"]) == 12
    assert pending["server_time"]

    listing = await client.get(
        "/api/v1/remote-assistance/devices",
        headers=_auth_headers(seed_owner, roles=["super_owner"], android=False),
    )
    assert listing.status_code == 200, listing.text
    listed = next(
        row
        for row in listing.json()["items"]
        if row["installation_id"] == str(installation.installation_id)
    )
    assert listed["device_key_status"] == "pending"
    assert listed["device_key_fingerprint_sha256"] is None
    assert listed["pending_device_key_id"] == str(key.key_id)
    assert listed["pairing_required"] is True
    assert "pairing_code" not in listed
    assert "public_key_spki" not in listed

    heartbeat_payload = {
        "installation_id": str(installation.installation_id),
        "protocol_version": 1,
        "sharing_capability": "available",
    }
    pending_heartbeat, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target="/api/v1/remote-assistance/device/heartbeat",
        payload=heartbeat_payload,
    )
    assert pending_heartbeat.status_code == 401

    wrong_code = await _approve(client, seed_owner, key.key_id, "000000000000", uuid4())
    assert wrong_code.status_code == 403
    assert wrong_code.json()["error"]["code"] == "remote_pairing_code_mismatch"

    approval_id = uuid4()
    displayed_code = pending["pairing_code"]
    human_entered_code = f"{displayed_code[:4].lower()}-{displayed_code[4:8]} {displayed_code[8:]}"
    approved = await _approve(
        client,
        seed_owner,
        key.key_id,
        human_entered_code,
        approval_id,
    )
    assert approved.status_code == 200, approved.text
    assert approved.json()["status"] == "active"
    assert approved.json()["fingerprint_sha256"] == key.fingerprint
    approved_replay = await _approve(
        client,
        seed_owner,
        key.key_id,
        "111111111111",
        approval_id,
    )
    assert approved_replay.status_code == 200

    heartbeat, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target="/api/v1/remote-assistance/device/heartbeat",
        payload=heartbeat_payload,
    )
    assert heartbeat.status_code == 200, heartbeat.text

    await session.rollback()
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_type == "RemoteAssistanceDeviceKey",
                    AuditLog.entity_id == str(key.key_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert {row.action for row in audits} >= {
        "remote_assistance.device_key.enrolled",
        "remote_assistance.device_key.approved",
    }
    assert all(row.actor_user_id == seed_owner["owner"].id for row in audits)
    assert all(row.terminal_id == seed_owner["terminal"].id for row in audits)
    assert all("pairing_code" not in str(row.after) for row in audits)
    enrollment_audit = next(
        row for row in audits if row.action == "remote_assistance.device_key.enrolled"
    )
    assert key.fingerprint not in str(enrollment_audit.after)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_signed_request_rejects_forgery_alteration_replay_and_bad_time(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    key = _new_key()
    wrong_key = _new_key()
    enrolled = await _enroll(client, seed_owner, installation.installation_id, key)
    assert enrolled.status_code == 200, enrolled.text
    approved = await _approve(
        client,
        seed_owner,
        key.key_id,
        enrolled.json()["pairing_code"],
        uuid4(),
    )
    assert approved.status_code == 200, approved.text

    state_target = (
        "/api/v1/remote-assistance/device/state"
        f"?installation_id={installation.installation_id}&after_sequence=0"
    )
    valid_headers, replay_nonce = _device_signature_headers(
        key=key,
        method="GET",
        raw_target=state_target,
        content=b"",
    )
    headers = {
        **_auth_headers(seed_owner, roles=["staff"], android=True),
        **valid_headers,
    }
    first = await client.get(state_target, headers=headers)
    assert first.status_code == 200, first.text
    replay = await client.get(state_target, headers=headers)
    assert replay.status_code == 401
    assert replay.json()["error"]["code"] == "unauthorized"

    forged_headers, _ = _device_signature_headers(
        key=key,
        method="GET",
        raw_target=state_target,
        content=b"",
        signer=wrong_key.private_key,
    )
    forged = await client.get(
        state_target,
        headers={
            **_auth_headers(seed_owner, roles=["staff"], android=True),
            **forged_headers,
        },
    )
    assert forged.status_code == 401

    reordered_target = (
        "/api/v1/remote-assistance/device/state"
        f"?after_sequence=0&installation_id={installation.installation_id}"
    )
    altered_query_headers, _ = _device_signature_headers(
        key=key,
        method="GET",
        raw_target=state_target,
        content=b"",
    )
    altered_query = await client.get(
        reordered_target,
        headers={
            **_auth_headers(seed_owner, roles=["staff"], android=True),
            **altered_query_headers,
        },
    )
    assert altered_query.status_code == 401

    heartbeat_target = "/api/v1/remote-assistance/device/heartbeat"
    original = {
        "installation_id": str(installation.installation_id),
        "protocol_version": 1,
        "sharing_capability": "available",
    }
    original_content = json.dumps(original, separators=(",", ":")).encode()
    changed = {**original, "sharing_capability": "permission_required"}
    altered_body, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target=heartbeat_target,
        payload=changed,
        signed_content=original_content,
    )
    assert altered_body.status_code == 401

    stale, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target=heartbeat_target,
        payload=original,
        epoch=int(datetime.now(UTC).timestamp()) - 600,
    )
    assert stale.status_code == 401
    huge, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target=heartbeat_target,
        payload=original,
        epoch=9_223_372_036_854_775_807,
    )
    assert huge.status_code == 401
    negative, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=key,
        method="POST",
        target=heartbeat_target,
        payload=original,
        epoch=-1,
    )
    assert negative.status_code == 401

    huge_enrollment_key = _new_key()
    huge_enrollment = await client.post(
        "/api/v1/remote-assistance/device/keys/enroll",
        headers=_auth_headers(seed_owner, roles=["staff"], android=True),
        json=_enrollment_payload(
            seed=seed_owner,
            installation_id=installation.installation_id,
            key=huge_enrollment_key,
            epoch=9_223_372_036_854_775_807,
        ),
    )
    assert huge_enrollment.status_code == 401
    negative_payload = _enrollment_payload(
        seed=seed_owner,
        installation_id=installation.installation_id,
        key=huge_enrollment_key,
        epoch=-1,
    )
    negative_enrollment = await client.post(
        "/api/v1/remote-assistance/device/keys/enroll",
        headers=_auth_headers(seed_owner, roles=["staff"], android=True),
        json=negative_payload,
    )
    assert negative_enrollment.status_code == 422

    other = await _second_tenant(session)
    cross_tenant_headers, _ = _device_signature_headers(
        key=key,
        method="GET",
        raw_target=(
            f"/api/v1/remote-assistance/device/keys/{key.key_id}/status"
            f"?installation_id={installation.installation_id}"
        ),
        content=b"",
    )
    cross_tenant = await client.get(
        f"/api/v1/remote-assistance/device/keys/{key.key_id}/status"
        f"?installation_id={installation.installation_id}",
        headers={
            **_auth_headers(other, roles=["staff"], android=True),
            **cross_tenant_headers,
        },
    )
    assert cross_tenant.status_code == 404
    assert replay_nonce


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_replacement_approval_rotates_key_and_ends_support(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    first_key = _new_key()
    enrolled_first = await _enroll(client, seed_owner, installation.installation_id, first_key)
    assert enrolled_first.status_code == 200, enrolled_first.text
    approved_first = await _approve(
        client,
        seed_owner,
        first_key.key_id,
        enrolled_first.json()["pairing_code"],
        uuid4(),
    )
    assert approved_first.status_code == 200, approved_first.text

    heartbeat, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=first_key,
        method="POST",
        target="/api/v1/remote-assistance/device/heartbeat",
        payload={
            "installation_id": str(installation.installation_id),
            "protocol_version": 1,
            "sharing_capability": "available",
        },
    )
    assert heartbeat.status_code == 200, heartbeat.text
    requested = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=_auth_headers(seed_owner, roles=["super_owner"], android=False),
        json={
            "request_id": str(uuid4()),
            "installation_id": str(installation.installation_id),
            "grant_kind": "anytime",
            "grant_ttl_seconds": 86_400,
            "session_ttl_seconds": 900,
        },
    )
    assert requested.status_code == 200, requested.text
    grant_id = UUID(requested.json()["grant"]["id"])
    support_session_id = UUID(requested.json()["session"]["id"])

    replacement = _new_key()
    enrolled_replacement = await _enroll(
        client,
        seed_owner,
        installation.installation_id,
        replacement,
    )
    assert enrolled_replacement.status_code == 200, enrolled_replacement.text
    approval_id = uuid4()
    responses = await asyncio.gather(
        _approve(
            client,
            seed_owner,
            replacement.key_id,
            enrolled_replacement.json()["pairing_code"],
            approval_id,
        ),
        _approve(
            client,
            seed_owner,
            replacement.key_id,
            enrolled_replacement.json()["pairing_code"],
            approval_id,
        ),
    )
    assert [response.status_code for response in responses] == [200, 200]
    assert {response.json()["status"] for response in responses} == {"active"}

    await session.rollback()
    keys = (
        (
            await session.execute(
                select(RemoteAssistanceDeviceKey).where(
                    RemoteAssistanceDeviceKey.id.in_((first_key.key_id, replacement.key_id))
                )
            )
        )
        .scalars()
        .all()
    )
    by_id = {row.id: row for row in keys}
    assert by_id[first_key.key_id].status == "revoked"
    assert by_id[first_key.key_id].revocation_id != approval_id
    assert by_id[replacement.key_id].status == "active"
    assert by_id[replacement.key_id].approval_id == approval_id
    action_matches = (
        (
            await session.execute(
                select(RemoteAssistanceDeviceKey.id).where(
                    RemoteAssistanceDeviceKey.company_id == seed_owner["company"].id,
                    or_(
                        RemoteAssistanceDeviceKey.enrollment_id == approval_id,
                        RemoteAssistanceDeviceKey.approval_id == approval_id,
                        RemoteAssistanceDeviceKey.revocation_id == approval_id,
                    ),
                )
            )
        )
        .scalars()
        .all()
    )
    assert action_matches == [replacement.key_id]
    grant = await session.get(RemoteAssistanceGrant, grant_id)
    support_session = await session.get(RemoteAssistanceSession, support_session_id)
    assert grant is not None
    assert grant.status == "revoked"
    assert support_session is not None
    assert support_session.status == "ended"

    old_status = await _status(client, seed_owner, installation.installation_id, first_key)
    assert old_status.status_code == 200, old_status.text
    assert old_status.json()["status"] == "revoked"
    old_heartbeat, _ = await _signed_json(
        client,
        seed=seed_owner,
        key=first_key,
        method="POST",
        target="/api/v1/remote-assistance/device/heartbeat",
        payload={
            "installation_id": str(installation.installation_id),
            "protocol_version": 1,
            "sharing_capability": "available",
        },
    )
    assert old_heartbeat.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_explicit_key_revocation_is_idempotent_and_status_remains_queryable(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    key = _new_key()
    enrolled = await _enroll(client, seed_owner, installation.installation_id, key)
    assert enrolled.status_code == 200, enrolled.text
    approved = await _approve(
        client,
        seed_owner,
        key.key_id,
        enrolled.json()["pairing_code"],
        uuid4(),
    )
    assert approved.status_code == 200, approved.text
    revocation_id = uuid4()
    admin_headers = _auth_headers(seed_owner, roles=["super_owner"], android=False)
    revoked = await client.post(
        f"/api/v1/remote-assistance/device-keys/{key.key_id}/revoke",
        headers=admin_headers,
        json={"revocation_id": str(revocation_id)},
    )
    assert revoked.status_code == 200, revoked.text
    assert revoked.json()["status"] == "revoked"
    replay = await client.post(
        f"/api/v1/remote-assistance/device-keys/{key.key_id}/revoke",
        headers=admin_headers,
        json={"revocation_id": str(revocation_id)},
    )
    assert replay.status_code == 200
    conflict = await client.post(
        f"/api/v1/remote-assistance/device-keys/{key.key_id}/revoke",
        headers=admin_headers,
        json={"revocation_id": str(uuid4())},
    )
    assert conflict.status_code == 409
    status = await _status(client, seed_owner, installation.installation_id, key)
    assert status.status_code == 200, status.text
    assert status.json()["status"] == "revoked"

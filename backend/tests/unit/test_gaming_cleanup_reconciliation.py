from __future__ import annotations

from copy import deepcopy
from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import inspect
from sqlalchemy.orm.attributes import set_committed_value

from app.api.v1.client_installations import router as cleanup_router
from app.api.v1.client_installations.router import (
    MAX_GAMING_CLEANUP_EPOCH_MILLIS,
    MAX_GAMING_CLEANUP_EVIDENCE_REVISION,
    MAX_GAMING_CLEANUP_REVISIONS_PER_ACTION,
    GamingCleanupAcknowledgeWrite,
    GamingCleanupApprovalWrite,
    GamingCleanupLocalSnapshot,
    GamingCleanupReportWrite,
    _cleanup_read,
    _java_instant,
    _millis_datetime,
    acknowledge_gaming_cleanup_reconciliation,
    approve_gaming_cleanup_reconciliation,
    gaming_cleanup_action_hashes,
    gaming_cleanup_candidate_sha256,
    gaming_cleanup_snapshot_sha256,
    report_gaming_cleanup_reconciliation,
)
from app.core.cleanup_replay_fence import (
    CanonicalGamingCleanupReceipt,
    require_canonical_gaming_cleanup_receipt,
)
from app.core.errors import BusinessRuleError, ConflictError
from app.core.tenant import TenantContext
from app.models.client_gaming_cleanup_reconciliation import (
    ClientGamingCleanupReconciliation,
    _guard_cleanup_reconciliation,
)


def _result(*, rows=None, scalar=None, one=None):
    result = MagicMock()
    result.scalars.return_value.all.return_value = [] if rows is None else rows
    result.scalar_one_or_none.return_value = scalar
    result.one_or_none.return_value = one
    return result


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("super_owner",),
        audit_access=True,
    )


def _payload(tenant: TenantContext) -> GamingCleanupReportWrite:
    snapshot = GamingCleanupLocalSnapshot(
        shift_id=uuid4(),
        started_at_millis=1_795_000_000_123,
        ended_at_millis=1_795_003_600_456,
        timer_minutes=60,
        timer_ends_at_millis=1_795_003_600_123,
        billable_minutes=60,
        amount_minor=15_000,
        rate_per_hour_minor=15_000,
        customer_name="Reviewed customer",
        extra_controllers=0,
        evidence_revision=7,
    )
    draft = GamingCleanupReportWrite(
        installation_id=uuid4(),
        local_action_id=uuid4(),
        server_session_id=uuid4(),
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        station_id=uuid4(),
        reported_local_state="stop_pending",
        local_snapshot=snapshot,
        local_snapshot_sha256=gaming_cleanup_snapshot_sha256(snapshot),
        start_request_hash="0" * 64,
        stop_request_hash="0" * 64,
        candidate_sha256="0" * 64,
        unresolved_child_count=0,
    )
    start_hash, stop_hash = gaming_cleanup_action_hashes(draft)
    draft = draft.model_copy(
        update={"start_request_hash": start_hash, "stop_request_hash": stop_hash}
    )
    return draft.model_copy(update={"candidate_sha256": gaming_cleanup_candidate_sha256(draft)})


def _receipt(payload: GamingCleanupReportWrite, *, original_user_id: UUID | None = None):
    source = "1" * 40
    original_user_id = original_user_id or uuid4()
    local_ids = [payload.local_action_id, *(uuid4() for _ in range(4))]
    session_ids = [payload.server_session_id, *(uuid4() for _ in range(4))]
    fence: list[dict[str, object]] = []
    for local_id in local_ids:
        for action in ("start", "stop"):
            candidate = local_id == payload.local_action_id
            request_hash = (
                (payload.start_request_hash if action == "start" else payload.stop_request_hash)
                if candidate
                else ("a" if action == "start" else "b") * 64
            )
            fence.append(
                {
                    "action_type": "idempotency",
                    "action_key": f"gaming-session-{action}:{local_id}",
                    "request_hash": request_hash,
                    "user_id": str(original_user_id),
                    "terminal_id": str(payload.terminal_id),
                    "source_entity_id": None,
                }
            )
    for index in range(3):
        fence.append(
            {
                "action_type": "shift_open",
                "action_key": f"shift-open:{index + 1:02d}",
                "request_hash": "c" * 64,
                "user_id": str(original_user_id),
                "terminal_id": str(payload.terminal_id),
                "source_entity_id": str(uuid4()),
            }
        )
    fence.sort(key=lambda item: str(item["action_key"]))
    idempotency_keys = [
        item["action_key"] for item in fence if item["action_type"] == "idempotency"
    ]
    return SimpleNamespace(
        id=28204,
        actor_user_id=uuid4(),
        terminal_id=payload.terminal_id,
        request_id="code30.1-production-trial-cleanup-20260920",
        client_action_id="production-trial-cleanup-20260920",
        client_was_offline=None,
        synced_at=None,
        user_agent="cleanup-code30-production-trial-data/2",
        before={
            "schema_revision": "0078",
            "state_fingerprint": "d" * 64,
            "backup_sha256": "e" * 64,
            "quarantine_evidence_sha256": "f" * 64,
            "counts": {},
            "audit_log": {},
        },
        after={
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
    )


def _ledger_row(
    payload: GamingCleanupReportWrite, tenant: TenantContext, *, status: str = "reported"
):
    now = datetime.now(UTC)
    approved = status in {"approved", "applied"}
    return SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        client_installation_id=uuid4(),
        branch_id=payload.branch_id,
        terminal_id=payload.terminal_id,
        station_id=payload.station_id,
        local_action_id=payload.local_action_id,
        server_session_id=payload.server_session_id,
        revision=1,
        reported_local_state=payload.reported_local_state,
        local_evidence_revision=payload.local_snapshot.evidence_revision,
        reported_app_version_name="3.1.30",
        reported_app_version_code=38,
        local_snapshot=payload.local_snapshot.model_dump(mode="json"),
        local_snapshot_sha256=payload.local_snapshot_sha256,
        start_request_hash=payload.start_request_hash,
        stop_request_hash=payload.stop_request_hash,
        original_action_user_id=uuid4(),
        candidate_sha256=payload.candidate_sha256,
        unresolved_child_count=payload.unresolved_child_count,
        cleanup_receipt_audit_id=28204,
        status=status,
        reported_at=now,
        approved_at=now if approved else None,
        approved_by=tenant.user_id if approved else None,
        approval_reason="Owner reviewed exact production cleanup evidence" if approved else None,
        approval_idempotency_key="approval-key-1" if approved else None,
        applied_at=now if status == "applied" else None,
        applied_by=tenant.user_id if status == "applied" else None,
        superseded_at=None,
    )


def _guarded_model_row(*, status: str) -> ClientGamingCleanupReconciliation:
    """Build a persisted-state model without requiring a database session."""

    now = datetime.now(UTC)
    approved = status in {"approved", "applied"}
    row = ClientGamingCleanupReconciliation(
        id=uuid4(),
        company_id=uuid4(),
        client_installation_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        station_id=uuid4(),
        local_action_id=uuid4(),
        server_session_id=uuid4(),
        revision=1,
        reported_local_state="stop_pending",
        local_evidence_revision=7,
        reported_app_version_name="3.1.30",
        reported_app_version_code=38,
        local_snapshot={"evidence_revision": 7},
        local_snapshot_sha256="a" * 64,
        start_request_hash="b" * 64,
        stop_request_hash="c" * 64,
        original_action_user_id=uuid4(),
        candidate_sha256="d" * 64,
        unresolved_child_count=0,
        cleanup_receipt_audit_id=28204,
        status=status,
        reported_at=now,
        approved_at=now if approved else None,
        approved_by=uuid4() if approved else None,
        approval_reason="Owner approved exact evidence" if approved else None,
        approval_idempotency_key="approval-key" if approved else None,
        applied_at=now if status == "applied" else None,
        applied_by=uuid4() if status == "applied" else None,
        superseded_at=None,
        created_at=now,
        updated_at=now,
    )
    for attribute in inspect(row).mapper.column_attrs:
        set_committed_value(row, attribute.key, getattr(row, attribute.key))
    return row


@pytest.mark.parametrize(
    "field",
    [
        "approved_at",
        "approved_by",
        "approval_reason",
        "approval_idempotency_key",
    ],
)
def test_model_guard_rejects_approval_mutation_while_superseding_approved(
    field: str,
) -> None:
    row = _guarded_model_row(status="approved")
    row.status = "superseded"
    row.superseded_at = datetime.now(UTC)
    replacements = {
        "approved_at": datetime.fromtimestamp(row.approved_at.timestamp() + 1, UTC),
        "approved_by": uuid4(),
        "approval_reason": "Tampered approval reason",
        "approval_idempotency_key": "tampered-approval-key",
    }
    setattr(row, field, replacements[field])

    with pytest.raises(ValueError, match="approval evidence is immutable"):
        _guard_cleanup_reconciliation(None, None, row)


def test_model_guard_rejects_fabricated_approval_while_superseding_reported() -> None:
    row = _guarded_model_row(status="reported")
    row.status = "superseded"
    row.superseded_at = datetime.now(UTC)
    row.approved_at = datetime.now(UTC)
    row.approved_by = uuid4()
    row.approval_reason = "Fabricated approval"
    row.approval_idempotency_key = "fabricated-approval-key"

    with pytest.raises(ValueError, match="approval evidence is immutable"):
        _guard_cleanup_reconciliation(None, None, row)


@pytest.mark.parametrize("status", ["reported", "approved"])
def test_model_guard_allows_supersession_only_with_exact_approval_tuple(status: str) -> None:
    row = _guarded_model_row(status=status)
    expected_approval = (
        row.approved_at,
        row.approved_by,
        row.approval_reason,
        row.approval_idempotency_key,
    )
    row.status = "superseded"
    row.superseded_at = datetime.now(UTC)

    _guard_cleanup_reconciliation(None, None, row)

    assert (
        row.approved_at,
        row.approved_by,
        row.approval_reason,
        row.approval_idempotency_key,
    ) == expected_approval


@pytest.mark.parametrize(
    ("field", "replacement"),
    [
        ("reported_app_version_name", "3.1.31"),
        ("reported_app_version_code", 39),
    ],
)
def test_model_guard_rejects_report_time_app_identity_mutation(
    field: str,
    replacement: object,
) -> None:
    row = _guarded_model_row(status="reported")
    setattr(row, field, replacement)

    with pytest.raises(ValueError, match="identity is immutable"):
        _guard_cleanup_reconciliation(None, None, row)


def _stub_device_and_receipt(monkeypatch, payload: GamingCleanupReportWrite):
    installation = SimpleNamespace(
        id=uuid4(),
        installation_id=payload.installation_id,
        platform="android",
        version_name="3.1.30",
        version_code=38,
    )
    original_user_id = uuid4()
    monkeypatch.setattr(
        cleanup_router, "_cleanup_installation", AsyncMock(return_value=installation)
    )
    monkeypatch.setattr(cleanup_router, "_authenticate_cleanup_device", AsyncMock())
    monkeypatch.setattr(
        cleanup_router,
        "require_canonical_gaming_cleanup_receipt",
        AsyncMock(
            return_value=CanonicalGamingCleanupReceipt(
                audit_id=28204,
                deleted_gaming_session_ids=(payload.server_session_id,),
                original_action_user_id=original_user_id,
            )
        ),
    )
    return installation, original_user_id


async def _verify_receipt(payload: GamingCleanupReportWrite, receipt):
    session = AsyncMock()
    session.execute.side_effect = [
        _result(rows=[receipt]),
        _result(scalar=payload.station_id),
        _result(scalar=None),
    ]
    return await require_canonical_gaming_cleanup_receipt(
        session,
        company_id=uuid4(),
        branch_id=payload.branch_id,
        terminal_id=payload.terminal_id,
        station_id=payload.station_id,
        local_action_id=payload.local_action_id,
        server_session_id=payload.server_session_id,
        start_request_hash=payload.start_request_hash,
        stop_request_hash=payload.stop_request_hash,
    )


@pytest.mark.asyncio
async def test_exact_cleanup_receipt_binds_both_actions_and_absent_server_row() -> None:
    payload = _payload(_tenant())
    original_user_id = uuid4()
    verified = await _verify_receipt(payload, _receipt(payload, original_user_id=original_user_id))
    assert verified.audit_id == 28204
    assert payload.server_session_id in verified.deleted_gaming_session_ids
    assert verified.original_action_user_id == original_user_id


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [
        "arbitrary_session",
        "live_session",
        "wrong_station_scope",
        "malformed",
        "duplicate_receipt",
        "empty_fence",
        "duplicate_fence",
        "wrong_action",
        "wrong_start_hash",
        "wrong_stop_hash",
        "wrong_terminal",
    ],
)
async def test_cleanup_receipt_validation_fails_closed(failure: str) -> None:
    payload = _payload(_tenant())
    receipt = _receipt(payload)
    if failure == "arbitrary_session":
        payload = payload.model_copy(update={"server_session_id": uuid4()})
    elif failure == "malformed":
        receipt.after["executed_at"] = "not-a-time"
    elif failure == "empty_fence":
        receipt.after["replay_fence"] = []
    elif failure == "duplicate_fence":
        receipt.after["replay_fence"][-1] = deepcopy(receipt.after["replay_fence"][0])
    elif failure == "wrong_action":
        key = f"gaming-session-start:{payload.local_action_id}"
        next(item for item in receipt.after["replay_fence"] if item["action_key"] == key)[
            "action_type"
        ] = "shift_open"
    elif failure == "wrong_start_hash":
        payload = payload.model_copy(update={"start_request_hash": "9" * 64})
    elif failure == "wrong_stop_hash":
        payload = payload.model_copy(update={"stop_request_hash": "9" * 64})
    elif failure == "wrong_terminal":
        payload = payload.model_copy(update={"terminal_id": uuid4()})
    receipts = [receipt, receipt] if failure == "duplicate_receipt" else [receipt]
    station = None if failure == "wrong_station_scope" else payload.station_id
    live = payload.server_session_id if failure == "live_session" else None
    session = AsyncMock()
    session.execute.side_effect = [
        _result(rows=receipts),
        _result(scalar=station),
        _result(scalar=live),
    ]
    with pytest.raises(BusinessRuleError):
        await require_canonical_gaming_cleanup_receipt(
            session,
            company_id=uuid4(),
            branch_id=payload.branch_id,
            terminal_id=payload.terminal_id,
            station_id=payload.station_id,
            local_action_id=payload.local_action_id,
            server_session_id=payload.server_session_id,
            start_request_hash=payload.start_request_hash,
            stop_request_hash=payload.stop_request_hash,
        )


def test_candidate_hash_binds_full_snapshot_and_action_hashes() -> None:
    payload = _payload(_tenant())
    snapshot = payload.local_snapshot.model_copy(update={"amount_minor": 15_001})
    changed = payload.model_copy(
        update={
            "local_snapshot": snapshot,
            "local_snapshot_sha256": gaming_cleanup_snapshot_sha256(snapshot),
            "candidate_sha256": "0" * 64,
        }
    )
    changed = changed.model_copy(
        update={"candidate_sha256": gaming_cleanup_candidate_sha256(changed)}
    )
    assert payload.candidate_sha256 != changed.candidate_sha256
    assert payload.candidate_sha256 != gaming_cleanup_candidate_sha256(
        payload.model_copy(update={"stop_request_hash": "9" * 64})
    )


@pytest.mark.parametrize(
    "field",
    [
        "started_at_millis",
        "ended_at_millis",
        "timer_ends_at_millis",
    ],
)
def test_cleanup_snapshot_rejects_epoch_millis_above_datetime_limit(field: str) -> None:
    values: dict[str, object] = {
        "shift_id": uuid4(),
        "started_at_millis": 1_795_000_000_123,
        "ended_at_millis": 1_795_003_600_456,
        "timer_ends_at_millis": 1_795_003_600_123,
        "rate_per_hour_minor": 15_000,
        "extra_controllers": 0,
        "evidence_revision": 7,
    }
    values[field] = MAX_GAMING_CLEANUP_EPOCH_MILLIS + 1

    with pytest.raises(ValidationError) as error:
        GamingCleanupLocalSnapshot.model_validate(values)

    assert any(
        issue["loc"] == (field,) and issue["type"] == "less_than_equal"
        for issue in error.value.errors()
    )


@pytest.mark.parametrize("field", ["evidence_revision", "customer_directory_revision"])
def test_cleanup_snapshot_rejects_revision_above_signed_bigint(field: str) -> None:
    values: dict[str, object] = {
        "shift_id": uuid4(),
        "started_at_millis": 1_795_000_000_123,
        "ended_at_millis": 1_795_003_600_456,
        "rate_per_hour_minor": 15_000,
        "extra_controllers": 0,
        "evidence_revision": 7,
    }
    if field == "customer_directory_revision":
        values["customer_directory_company_id"] = uuid4()
    values[field] = MAX_GAMING_CLEANUP_EVIDENCE_REVISION + 1

    with pytest.raises(ValidationError) as error:
        GamingCleanupLocalSnapshot.model_validate(values)

    assert any(
        issue["loc"] == (field,) and issue["type"] == "less_than_equal"
        for issue in error.value.errors()
    )


def test_cleanup_snapshot_accepts_and_serializes_maximum_epoch_millis() -> None:
    snapshot = GamingCleanupLocalSnapshot(
        shift_id=uuid4(),
        started_at_millis=MAX_GAMING_CLEANUP_EPOCH_MILLIS,
        ended_at_millis=MAX_GAMING_CLEANUP_EPOCH_MILLIS,
        timer_ends_at_millis=MAX_GAMING_CLEANUP_EPOCH_MILLIS,
        rate_per_hour_minor=15_000,
        extra_controllers=0,
        evidence_revision=MAX_GAMING_CLEANUP_EVIDENCE_REVISION,
    )

    assert _java_instant(snapshot.started_at_millis) == "9999-12-31T23:59:59.999Z"
    assert _millis_datetime(snapshot.ended_at_millis) == datetime(
        9999, 12, 31, 23, 59, 59, 999_000, tzinfo=UTC
    )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("started_at_millis", MAX_GAMING_CLEANUP_EPOCH_MILLIS + 1),
        ("ended_at_millis", MAX_GAMING_CLEANUP_EPOCH_MILLIS + 1),
        ("timer_ends_at_millis", MAX_GAMING_CLEANUP_EPOCH_MILLIS + 1),
        ("evidence_revision", MAX_GAMING_CLEANUP_EVIDENCE_REVISION + 1),
    ],
)
def test_cleanup_report_http_validation_returns_422_for_unserializable_evidence(
    field: str,
    value: int,
) -> None:
    app = FastAPI()

    @app.post("/report")
    async def validate_report(payload: GamingCleanupReportWrite) -> dict[str, bool]:
        return {"accepted": payload is not None}

    snapshot = {
        "shift_id": str(uuid4()),
        "started_at_millis": 1_795_000_000_123,
        "ended_at_millis": 1_795_003_600_456,
        "timer_ends_at_millis": 1_795_003_600_123,
        "rate_per_hour_minor": 15_000,
        "extra_controllers": 0,
        "evidence_revision": 7,
    }
    snapshot[field] = value
    response = TestClient(app).post(
        "/report",
        json={
            "installation_id": str(uuid4()),
            "local_action_id": str(uuid4()),
            "server_session_id": str(uuid4()),
            "branch_id": str(uuid4()),
            "terminal_id": str(uuid4()),
            "station_id": str(uuid4()),
            "reported_local_state": "stop_pending",
            "local_snapshot": snapshot,
            "local_snapshot_sha256": "a" * 64,
            "start_request_hash": "b" * 64,
            "stop_request_hash": "c" * 64,
            "candidate_sha256": "d" * 64,
            "unresolved_child_count": 0,
        },
    )

    assert response.status_code == 422
    assert any(
        error["loc"] == ["body", "local_snapshot", field]
        and error["type"] == "less_than_equal"
        for error in response.json()["detail"]
    )


def test_locked_android_backend_cleanup_protocol_vector() -> None:
    snapshot = GamingCleanupLocalSnapshot(
        shift_id=UUID("77777777-7777-4777-8777-777777777777"),
        started_at_millis=1_795_000_000_123,
        ended_at_millis=1_795_003_600_456,
        timer_minutes=60,
        timer_ends_at_millis=1_795_003_600_123,
        billable_minutes=60,
        amount_minor=15_000,
        rate_per_hour_minor=15_000,
        customer_name="Reviewed customer",
        extra_controllers=0,
        evidence_revision=7,
    )
    snapshot_hash = gaming_cleanup_snapshot_sha256(snapshot)
    assert snapshot_hash == "b4696654edb865f03bb56620ba017b7f59cc43553d798251aac542605d63936e"
    draft = GamingCleanupReportWrite(
        installation_id=UUID("11111111-1111-4111-8111-111111111111"),
        local_action_id=UUID("22222222-2222-4222-8222-222222222222"),
        server_session_id=UUID("33333333-3333-4333-8333-333333333333"),
        branch_id=UUID("44444444-4444-4444-8444-444444444444"),
        terminal_id=UUID("55555555-5555-4555-8555-555555555555"),
        station_id=UUID("66666666-6666-4666-8666-666666666666"),
        reported_local_state="stop_pending",
        local_snapshot=snapshot,
        local_snapshot_sha256=snapshot_hash,
        start_request_hash="0" * 64,
        stop_request_hash="0" * 64,
        candidate_sha256="0" * 64,
        unresolved_child_count=0,
    )
    start_hash, stop_hash = gaming_cleanup_action_hashes(draft)
    assert start_hash == "f6e013c354f5887d7a57a6d4fe85554dbc5f6e6c6bf7b116be9d30532aa59d1b"
    assert stop_hash == "9cc5a00061afc4a8e497db2effb54a5a58db720f030d27af5c85fcb2cb8ef3e3"
    payload = draft.model_copy(
        update={"start_request_hash": start_hash, "stop_request_hash": stop_hash}
    )
    assert gaming_cleanup_candidate_sha256(payload) == (
        "3d7bbe08e7d0695e6e7bc4d78b6f4a59558ca8b63d53b3a5a0af87bbc10bcaea"
    )


def test_owner_review_projection_excludes_customer_identity() -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    row = _ledger_row(payload, tenant)
    installation_id = uuid4()
    read = _cleanup_read(row, installation_id)
    encoded = read.model_dump(mode="json")
    assert encoded["review"] == {
        "amount_minor": 15_000,
        "billable_minutes": 60,
        "started_at": "2026-11-18T11:06:40.123000Z",
        "ended_at": "2026-11-18T12:06:40.456000Z",
    }
    assert encoded["reported_app_version_name"] == "3.1.30"
    assert encoded["reported_app_version_code"] == 38
    serialized = str(encoded).lower()
    assert "reviewed customer" not in serialized
    assert "customer_name" not in serialized
    assert "customer_phone" not in serialized
    assert "customer_id" not in serialized


@pytest.mark.asyncio
async def test_report_repeat_is_idempotent(monkeypatch) -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    installation, original_user_id = _stub_device_and_receipt(monkeypatch, payload)
    row = _ledger_row(payload, tenant)
    row.client_installation_id = installation.id
    row.original_action_user_id = original_user_id
    session = AsyncMock()
    session.execute.return_value = _result(scalar=row)
    repeated = await report_gaming_cleanup_reconciliation(MagicMock(), payload, session, tenant)
    assert repeated.id == row.id
    assert repeated.installation_id == installation.installation_id
    session.add.assert_not_called()


@pytest.mark.asyncio
async def test_new_report_captures_authenticated_installation_version(monkeypatch) -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    installation, _original_user_id = _stub_device_and_receipt(monkeypatch, payload)
    session = AsyncMock()
    session.add = MagicMock()
    session.execute.side_effect = [_result(scalar=None), _result(scalar=0), _result(scalar=None)]

    async def flush() -> None:
        added = session.add.call_args.args[0]
        added.id = uuid4()
        added.created_at = datetime.now(UTC)
        added.updated_at = added.created_at

    session.flush.side_effect = flush
    reported = await report_gaming_cleanup_reconciliation(
        MagicMock(), payload, session, tenant
    )
    persisted = session.add.call_args.args[0]

    assert persisted.reported_app_version_name == installation.version_name
    assert persisted.reported_app_version_code == installation.version_code
    assert reported.reported_app_version_name == "3.1.30"
    assert reported.reported_app_version_code == 38


@pytest.mark.asyncio
async def test_report_changed_evidence_supersedes_reported_or_approved_revision(
    monkeypatch,
) -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    installation, original_user_id = _stub_device_and_receipt(monkeypatch, payload)
    row = _ledger_row(payload, tenant)
    row.client_installation_id = installation.id
    row.original_action_user_id = original_user_id
    snapshot = payload.local_snapshot.model_copy(
        update={"amount_minor": 15_001, "evidence_revision": 8}
    )
    changed = payload.model_copy(
        update={
            "local_snapshot": snapshot,
            "local_snapshot_sha256": gaming_cleanup_snapshot_sha256(snapshot),
            "candidate_sha256": "0" * 64,
        }
    )
    changed = changed.model_copy(
        update={"candidate_sha256": gaming_cleanup_candidate_sha256(changed)}
    )
    monkeypatch.setattr(
        cleanup_router,
        "gaming_cleanup_action_hashes",
        MagicMock(return_value=(changed.start_request_hash, changed.stop_request_hash)),
    )
    session = AsyncMock()
    session.add = MagicMock()
    session.execute.side_effect = [_result(scalar=row), _result(scalar=None)]
    flush_count = 0

    async def flush() -> None:
        nonlocal flush_count
        flush_count += 1
        if flush_count == 2:
            added = session.add.call_args.args[0]
            added.id = uuid4()
            added.created_at = datetime.now(UTC)
            added.updated_at = added.created_at

    session.flush.side_effect = flush
    reported = await report_gaming_cleanup_reconciliation(MagicMock(), changed, session, tenant)
    assert row.status == "superseded"
    assert row.superseded_at is not None
    assert reported.revision == 2
    assert reported.is_current is True
    approved = _ledger_row(payload, tenant, status="approved")
    approved.client_installation_id = installation.id
    approved.original_action_user_id = original_user_id
    approval_evidence = (
        approved.approved_at,
        approved.approved_by,
        approved.approval_reason,
        approved.approval_idempotency_key,
    )
    approved_session = AsyncMock()
    approved_session.add = MagicMock()
    approved_session.execute.side_effect = [_result(scalar=approved), _result(scalar=None)]
    approved_flush_count = 0

    async def approved_flush() -> None:
        nonlocal approved_flush_count
        approved_flush_count += 1
        if approved_flush_count == 2:
            added = approved_session.add.call_args.args[0]
            added.id = uuid4()
            added.created_at = datetime.now(UTC)
            added.updated_at = added.created_at

    approved_session.flush.side_effect = approved_flush
    rereported = await report_gaming_cleanup_reconciliation(
        MagicMock(), changed, approved_session, tenant
    )
    assert approved.status == "superseded"
    assert approved.superseded_at is not None
    assert (
        approved.approved_at,
        approved.approved_by,
        approved.approval_reason,
        approved.approval_idempotency_key,
    ) == approval_evidence
    assert rereported.status == "reported"
    assert rereported.revision == 2

    applied = _ledger_row(payload, tenant, status="applied")
    applied.client_installation_id = installation.id
    applied.original_action_user_id = original_user_id
    applied_session = AsyncMock()
    applied_session.execute.return_value = _result(scalar=applied)
    with pytest.raises(ConflictError, match="applied cleanup revision"):
        await report_gaming_cleanup_reconciliation(MagicMock(), changed, applied_session, tenant)
    assert applied.status == "applied"
    assert applied.superseded_at is None


@pytest.mark.asyncio
async def test_report_revision_cap_fails_before_superseding_current_evidence(monkeypatch) -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    installation, original_user_id = _stub_device_and_receipt(monkeypatch, payload)
    current = _ledger_row(payload, tenant, status="approved")
    current.client_installation_id = installation.id
    current.original_action_user_id = original_user_id
    current.revision = MAX_GAMING_CLEANUP_REVISIONS_PER_ACTION
    repeat_session = AsyncMock()
    repeat_session.execute.return_value = _result(scalar=current)
    repeated = await report_gaming_cleanup_reconciliation(
        MagicMock(), payload, repeat_session, tenant
    )
    assert repeated.id == current.id
    repeat_session.add.assert_not_called()
    snapshot = payload.local_snapshot.model_copy(
        update={"amount_minor": 15_001, "evidence_revision": 8}
    )
    changed = payload.model_copy(
        update={
            "local_snapshot": snapshot,
            "local_snapshot_sha256": gaming_cleanup_snapshot_sha256(snapshot),
            "candidate_sha256": "0" * 64,
        }
    )
    changed = changed.model_copy(
        update={"candidate_sha256": gaming_cleanup_candidate_sha256(changed)}
    )
    monkeypatch.setattr(
        cleanup_router,
        "gaming_cleanup_action_hashes",
        MagicMock(return_value=(changed.start_request_hash, changed.stop_request_hash)),
    )
    session = AsyncMock()
    session.execute.return_value = _result(scalar=current)
    with pytest.raises(BusinessRuleError, match="revision limit"):
        await report_gaming_cleanup_reconciliation(MagicMock(), changed, session, tenant)
    assert current.status == "approved"
    assert current.superseded_at is None
    session.add.assert_not_called()
    session.flush.assert_not_awaited()


@pytest.mark.asyncio
async def test_approve_is_idempotent_and_rejects_hash_or_key_conflict(monkeypatch) -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    installation, original_user_id = _stub_device_and_receipt(monkeypatch, payload)
    row = _ledger_row(payload, tenant)
    row.original_action_user_id = original_user_id
    session = AsyncMock()
    session.execute.return_value = _result(one=(row, installation.installation_id))
    approval = GamingCleanupApprovalWrite(
        expected_candidate_sha256=payload.candidate_sha256,
        reason="Owner reviewed exact production cleanup evidence",
    )
    approved = await approve_gaming_cleanup_reconciliation(
        row.id, approval, session, tenant, "approval-key-1"
    )
    assert approved.status == "approved"
    assert row.approved_by == tenant.user_id
    repeated = await approve_gaming_cleanup_reconciliation(
        row.id, approval, session, tenant, "approval-key-1"
    )
    assert repeated.approved_at == approved.approved_at
    with pytest.raises(ConflictError, match="different evidence"):
        await approve_gaming_cleanup_reconciliation(
            row.id, approval, session, tenant, "approval-key-2"
        )
    with pytest.raises(ConflictError, match="changed"):
        await approve_gaming_cleanup_reconciliation(
            row.id,
            approval.model_copy(update={"expected_candidate_sha256": "f" * 64}),
            session,
            tenant,
            "approval-key-1",
        )


@pytest.mark.asyncio
async def test_acknowledge_is_device_bound_idempotent_and_hash_checked(monkeypatch) -> None:
    tenant = _tenant()
    payload = _payload(tenant)
    installation, original_user_id = _stub_device_and_receipt(monkeypatch, payload)
    row = _ledger_row(payload, tenant, status="approved")
    row.client_installation_id = installation.id
    row.original_action_user_id = original_user_id
    session = AsyncMock()
    session.execute.return_value = _result(scalar=row)
    acknowledgement = GamingCleanupAcknowledgeWrite(
        installation_id=payload.installation_id,
        expected_candidate_sha256=payload.candidate_sha256,
    )
    applied = await acknowledge_gaming_cleanup_reconciliation(
        row.id, MagicMock(), acknowledgement, session, tenant
    )
    assert applied.status == "applied"
    applied_at = row.applied_at
    repeated = await acknowledge_gaming_cleanup_reconciliation(
        row.id, MagicMock(), acknowledgement, session, tenant
    )
    assert repeated.applied_at == applied_at
    with pytest.raises(ConflictError, match="does not match"):
        await acknowledge_gaming_cleanup_reconciliation(
            row.id,
            MagicMock(),
            acknowledgement.model_copy(update={"expected_candidate_sha256": "f" * 64}),
            session,
            tenant,
        )

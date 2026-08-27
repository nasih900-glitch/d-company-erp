"""Protected-owner gaming bill recovery without a database dependency."""

from __future__ import annotations

from datetime import UTC, datetime
from inspect import signature
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy.dialects import postgresql

from app.api.v1.gaming import router as gaming_router
from app.core.errors import (
    BusinessRuleError,
    ForbiddenError,
    GamingBillingRepairRequiredError,
    GamingSourceShiftClosedError,
)
from app.core.tenant import TenantContext
from app.models import AuditLog, Order, OrderLine, Shift, Station


class _Result:
    def __init__(self, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar


class _Session:
    def __init__(self, *results: _Result, entities=None) -> None:
        self.results = list(results)
        self.entities = entities or {}
        self.statements = []
        self.added = []
        self.flush_count = 0

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    async def get(self, model, entity_id):
        return self.entities.get((model, entity_id))

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(*, audit_access: bool = True, terminal_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=terminal_id or uuid4(),
        roles=("owner",),
        protected_access=audit_access,
        audit_access=audit_access,
    )


def _shift(tenant: TenantContext, *, status: str, terminal_id: UUID | None = None):
    return SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=terminal_id or tenant.terminal_id,
        opened_by=tenant.user_id,
        status=status,
    )


def _station(tenant: TenantContext):
    return SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        name="PS5 1",
        type="ps5",
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )


def _gaming_session(tenant: TenantContext, station, source_shift, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "station_id": station.id,
        "shift_id": source_shift.id,
        "opened_by": uuid4(),
        "order_id": None,
        "start_at": datetime(2026, 8, 26, 10, tzinfo=UTC),
        "end_at": datetime(2026, 8, 26, 10, 47, tzinfo=UTC),
        "billable_minutes": 47,
        "rate_per_hour_minor": 20_000,
        "amount_minor": 15_667,
        "status": "ended",
        "timer_minutes": None,
        "cancel_reason": None,
        "package_id": None,
        "extra_controllers": 0,
        "customer_name": "Cafe Guest",
        "customer_phone": None,
        "tax_rate": 0.18,
        "sac_code": "999692",
        "rate_includes_tax": True,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _route_permissions(endpoint) -> tuple[str, ...]:
    dependency = signature(endpoint).parameters["tenant"].default.dependency
    closure = dict(
        zip(
            dependency.__code__.co_freevars,
            (cell.cell_contents for cell in dependency.__closure__ or ()),
            strict=True,
        )
    )
    return tuple(closure["perms"])


def _install_order_dependencies(monkeypatch) -> None:
    async def _menu_item(_session, *, company_id, station):
        assert company_id == station.company_id
        return SimpleNamespace(id=uuid4(), hsn_code="999692")

    class _Pricing:
        def __init__(self, _session) -> None:
            pass

        async def price_time_based_line(self, **kwargs):
            assert kwargs["amount_minor"] == 15_667
            return SimpleNamespace(
                taxable_minor=13_277,
                discount_minor=0,
                cgst_minor=1_195,
                sgst_minor=1_195,
                igst_minor=0,
                total_minor=15_667,
            )

    monkeypatch.setattr(gaming_router, "_ensure_session_menu_item", _menu_item)
    monkeypatch.setattr(gaming_router, "OrderPricingService", _Pricing)


def test_reconciliation_contract_is_audit_owner_only_and_requires_a_reason() -> None:
    assert _route_permissions(gaming_router.reconcile_session_to_pos) == (
        "admin.audit.read",
    )
    with pytest.raises(PydanticValidationError, match="at least 3"):
        gaming_router.SessionReconcileToPos(
            target_shift_id=uuid4(),
            reason=" x ",
        )

    assert _route_permissions(gaming_router.repair_session_billing) == (
        "admin.audit.read",
    )
    assert _route_permissions(gaming_router.resolve_legacy_gaming_outbox) == (
        "admin.audit.read",
    )


def test_legacy_outbox_resolution_schema_requires_traceable_evidence() -> None:
    common = {
        "local_action_id": uuid4(),
        "station_id": uuid4(),
        "captured_started_at": datetime(2026, 8, 26, 10, tzinfo=UTC),
        "expected_rate_per_hour_minor": 20_000,
        "reason": "Owner reviewed the retained local evidence",
    }
    with pytest.raises(PydanticValidationError, match="requires reference_order_id"):
        gaming_router.LegacyOutboxResolution(
            **common,
            resolution="manual_bill_recorded",
        )
    with pytest.raises(PydanticValidationError, match="must not include"):
        gaming_router.LegacyOutboxResolution(
            **common,
            resolution="confirmed_no_play",
            reference_order_id=uuid4(),
        )
    with pytest.raises(PydanticValidationError, match="must not include"):
        gaming_router.LegacyOutboxResolution(
            **common,
            shift_id=uuid4(),
            resolution="server_session_recovered",
            reference_order_id=uuid4(),
        )
    recovered = gaming_router.LegacyOutboxResolution(
        **common,
        shift_id=uuid4(),
        resolution="server_session_recovered",
    )
    assert recovered.shift_id is not None
    with pytest.raises(PydanticValidationError, match="requires expected_rate"):
        gaming_router.LegacyOutboxResolution(
            **{**common, "expected_rate_per_hour_minor": None},
            resolution="confirmed_no_play",
        )
    with pytest.raises(PydanticValidationError, match="must not include"):
        gaming_router.LegacyOutboxResolution(
            **common,
            package_id=uuid4(),
            resolution="confirmed_no_play",
        )
    with pytest.raises(PydanticValidationError, match="at least 3"):
        gaming_router.LegacyOutboxResolution(
            **{**common, "reason": " x "},
            resolution="confirmed_no_play",
        )


@pytest.mark.asyncio
async def test_non_audit_owner_is_rejected_before_reading_or_writing() -> None:
    tenant = _tenant(audit_access=False)
    with pytest.raises(ForbiddenError, match="Only the protected owner"):
        await gaming_router.reconcile_session_to_pos(
            uuid4(),
            gaming_router.SessionReconcileToPos(
                target_shift_id=uuid4(),
                reason="Recover missed bill",
            ),
            _Session(),
            tenant,
        )


@pytest.mark.asyncio
async def test_closed_shift_session_is_moved_to_target_without_rewriting_source(
    monkeypatch,
) -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed")
    target_shift = _shift(tenant, status="open")
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    original_source_shift_id = gaming_session.shift_id
    _install_order_dependencies(monkeypatch)
    session = _Session(
        _Result(gaming_session),
        _Result(target_shift),
        entities={
            (Station, station.id): station,
            (Shift, source_shift.id): source_shift,
        },
    )

    response = await gaming_router.reconcile_session_to_pos(
        gaming_session.id,
        gaming_router.SessionReconcileToPos(
            target_shift_id=target_shift.id,
            reason="Shift was closed before staff sent the stopped session",
        ),
        session,
        tenant,
    )

    order = next(row for row in session.added if isinstance(row, Order))
    line = next(row for row in session.added if isinstance(row, OrderLine))
    audit = next(row for row in session.added if isinstance(row, AuditLog))
    assert response.order_id == order.id
    assert response.amount_minor == 15_700
    assert response.source_shift_id == source_shift.id
    assert response.target_shift_id == target_shift.id
    assert response.already_linked is False
    assert gaming_session.shift_id == original_source_shift_id == source_shift.id
    assert gaming_session.order_id == order.id
    assert order.shift_id == target_shift.id
    assert order.branch_id == target_shift.branch_id
    assert order.terminal_id == target_shift.terminal_id
    assert order.status == "held"
    assert line.order_id == order.id
    assert audit.action == "gaming_session_reconcile_to_pos"
    assert audit.entity_id == str(gaming_session.id)
    assert audit.before == {
        "source_shift_id": str(source_shift.id),
        "order_id": None,
    }
    assert audit.after == {
        "source_shift_id": str(source_shift.id),
        "target_shift_id": str(target_shift.id),
        "order_id": str(order.id),
        "reason": "Shift was closed before staff sent the stopped session",
    }
    assert audit.reason == "Shift was closed before staff sent the stopped session"
    assert all(
        "FOR UPDATE" in str(statement.compile(dialect=postgresql.dialect()))
        for statement in session.statements[:2]
    )


@pytest.mark.asyncio
async def test_reconciliation_retry_returns_linked_order_without_a_second_write() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed")
    station = _station(tenant)
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=uuid4(),
        total_minor=15_700,
    )
    gaming_session = _gaming_session(
        tenant,
        station,
        source_shift,
        order_id=order.id,
    )
    session = _Session(
        _Result(gaming_session),
        entities={
            (Station, station.id): station,
            (Order, order.id): order,
        },
    )

    response = await gaming_router.reconcile_session_to_pos(
        gaming_session.id,
        gaming_router.SessionReconcileToPos(
            target_shift_id=uuid4(),
            reason="Safe response-loss retry",
        ),
        session,
        tenant,
    )

    assert response.order_id == order.id
    assert response.source_shift_id == source_shift.id
    assert response.target_shift_id == order.shift_id
    assert response.already_linked is True
    assert session.added == []
    assert session.flush_count == 0
    assert len(session.statements) == 1


@pytest.mark.asyncio
async def test_reconciliation_rejects_a_closed_or_wrong_terminal_target_without_mutation(
    monkeypatch,
) -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed")
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    _install_order_dependencies(monkeypatch)

    for target_shift, expected_message in (
        (_shift(tenant, status="closed"), "Shift is closed"),
        (
            _shift(tenant, status="open", terminal_id=uuid4()),
            "different terminal",
        ),
    ):
        session = _Session(
            _Result(gaming_session),
            _Result(target_shift),
            entities={
                (Station, station.id): station,
                (Shift, source_shift.id): source_shift,
            },
        )
        with pytest.raises(BusinessRuleError, match=expected_message):
            await gaming_router.reconcile_session_to_pos(
                gaming_session.id,
                gaming_router.SessionReconcileToPos(
                    target_shift_id=target_shift.id,
                    reason="Recover missed bill",
                ),
                session,
                tenant,
            )
        assert gaming_session.order_id is None
        assert session.added == []


@pytest.mark.asyncio
async def test_normal_send_exposes_the_closed_source_recovery_code() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed", terminal_id=uuid4())
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    session = _Session(
        _Result(gaming_session),
        _Result(source_shift),
        entities={(Station, station.id): station},
    )

    with pytest.raises(GamingSourceShiftClosedError) as raised:
        await gaming_router.send_session_to_pos(gaming_session.id, session, tenant)

    assert raised.value.code == "gaming_source_shift_closed"
    assert gaming_session.order_id is None
    assert session.added == []


@pytest.mark.asyncio
async def test_send_cancel_and_reconcile_fail_closed_for_missing_ended_amount() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed")
    station = _station(tenant)
    gaming_session = _gaming_session(
        tenant,
        station,
        source_shift,
        amount_minor=None,
    )

    with pytest.raises(GamingBillingRepairRequiredError, match="Repair billing"):
        await gaming_router.send_session_to_pos(
            gaming_session.id,
            _Session(_Result(gaming_session), entities={(Station, station.id): station}),
            tenant,
        )

    open_shift = _shift(tenant, status="open")
    gaming_session.shift_id = open_shift.id
    with pytest.raises(GamingBillingRepairRequiredError, match="Repair billing"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Owner reviewed duplicate"),
            _Session(
                _Result(gaming_session),
                _Result(open_shift),
                entities={(Station, station.id): station},
            ),
            tenant,
        )

    gaming_session.shift_id = source_shift.id
    with pytest.raises(GamingBillingRepairRequiredError, match="Repair billing"):
        await gaming_router.reconcile_session_to_pos(
            gaming_session.id,
            gaming_router.SessionReconcileToPos(
                target_shift_id=uuid4(),
                reason="Recover missing bill",
            ),
            _Session(_Result(gaming_session), entities={(Station, station.id): station}),
            tenant,
        )

    assert gaming_session.status == "ended"
    assert gaming_session.amount_minor is None
    assert gaming_session.order_id is None


@pytest.mark.asyncio
async def test_owner_billing_repair_is_reasoned_audited_and_idempotency_stored(
    monkeypatch,
) -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed")
    station = _station(tenant)
    gaming_session = _gaming_session(
        tenant,
        station,
        source_shift,
        amount_minor=None,
    )
    db = _Session(
        _Result(gaming_session),
        entities={(Station, station.id): station},
    )
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="gaming-billing-repair:test",
            idempotency_request_hash="same-hash",
        )
    )
    stored: dict = {}

    async def reserve(_session, **_kwargs):
        return None

    async def store(_session, **kwargs):
        stored.update(kwargs)

    monkeypatch.setattr(gaming_router, "check_or_reserve", reserve)
    monkeypatch.setattr(gaming_router, "store_response", store)

    response = await gaming_router.repair_session_billing(
        gaming_session.id,
        gaming_router.SessionBillingRepair(
            expected_amount_minor=None,
            amount_minor=15_667,
            reason="Recovered from locked rate and billable minutes",
        ),
        db,
        request,
        tenant,
    )

    audit = next(row for row in db.added if isinstance(row, AuditLog))
    assert response.amount_minor == 15_667
    assert gaming_session.amount_minor == 15_667
    assert audit.action == "gaming_session_billing_repair"
    assert audit.before["amount_minor"] is None
    assert audit.after["amount_minor"] == 15_667
    assert audit.reason == "Recovered from locked rate and billable minutes"
    assert stored["key"] == "gaming-billing-repair:test"
    assert stored["status_code"] == 200

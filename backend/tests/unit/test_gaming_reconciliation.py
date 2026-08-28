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
    ConflictError,
    ForbiddenError,
    GamingBillingRepairRequiredError,
    GamingSourceShiftClosedError,
    NotFoundError,
)
from app.core.tenant import TenantContext
from app.models import (
    AuditLog,
    GamingSession,
    Order,
    OrderLine,
    Shift,
    Station,
    Terminal,
)


class _Result:
    def __init__(self, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = rows or []

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


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
        opened_at=datetime(2026, 8, 26, 9, tzinfo=UTC),
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


def _terminal(
    tenant: TenantContext,
    *,
    purpose: str,
    terminal_id: UUID | None = None,
    name: str | None = None,
):
    return SimpleNamespace(
        id=terminal_id or tenant.terminal_id,
        branch_id=tenant.branch_id,
        name=name or ("Cafe POS" if purpose == "cafe_pos" else "Gaming Area"),
        purpose=purpose,
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
        return SimpleNamespace(
            id=uuid4(),
            name="Gaming session",
            type="gaming",
            hsn_code="999692",
        )

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


def test_normal_cross_terminal_contract_requires_gaming_and_pos_access() -> None:
    expected = ("gaming.write", "pos.read")
    assert _route_permissions(gaming_router.list_session_pos_target_shifts) == expected
    assert _route_permissions(gaming_router.handoff_session_to_pos) == expected


@pytest.mark.asyncio
async def test_target_shift_list_is_explicit_and_same_branch_only() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="open")
    target_shift = _shift(tenant, status="open", terminal_id=uuid4())
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    source_terminal = _terminal(tenant, purpose="gaming")
    target_terminal = _terminal(
        tenant,
        terminal_id=target_shift.terminal_id,
        purpose="cafe_pos",
        name="Cafe POS",
    )
    target_opener = SimpleNamespace(
        id=target_shift.opened_by,
        name="Rafi",
    )
    session = _Session(
        _Result(rows=[(target_shift, target_terminal, target_opener)]),
        entities={
            (GamingSession, gaming_session.id): gaming_session,
            (Station, station.id): station,
            (Shift, source_shift.id): source_shift,
            (Terminal, source_terminal.id): source_terminal,
        },
    )

    response = await gaming_router.list_session_pos_target_shifts(
        gaming_session.id,
        session,
        tenant,
    )

    assert response == [
        gaming_router.PosTargetShiftRead(
            shift_id=target_shift.id,
            terminal_id=target_terminal.id,
            terminal_name="Cafe POS",
            opened_by=target_opener.id,
            opened_by_name="Rafi",
            opened_at=target_shift.opened_at,
        )
    ]
    compiled = str(session.statements[0].compile(dialect=postgresql.dialect()))
    assert "shifts.company_id" in compiled
    assert "shifts.branch_id" in compiled
    assert "shifts.status" in compiled
    assert "shifts.terminal_id !=" in compiled
    assert "terminals.purpose IN" in compiled
    assert "users.company_id" in compiled


@pytest.mark.asyncio
async def test_open_source_session_handoff_preserves_source_and_targets_pos_drawer(
    monkeypatch,
) -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="open")
    target_shift = _shift(tenant, status="open", terminal_id=uuid4())
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    original_source_shift_id = gaming_session.shift_id
    source_terminal = _terminal(tenant, purpose="gaming")
    target_terminal = _terminal(
        tenant,
        terminal_id=target_shift.terminal_id,
        purpose="cafe_pos",
        name="Cafe POS",
    )
    _install_order_dependencies(monkeypatch)
    session = _Session(
        _Result(gaming_session),
        _Result(rows=[source_shift, target_shift]),
        entities={
            (Station, station.id): station,
            (Shift, source_shift.id): source_shift,
            (Terminal, source_terminal.id): source_terminal,
            (Terminal, target_terminal.id): target_terminal,
        },
    )

    response = await gaming_router.handoff_session_to_pos(
        gaming_session.id,
        gaming_router.SessionPosHandoff(target_shift_id=target_shift.id),
        session,
        tenant,
    )

    order = next(row for row in session.added if isinstance(row, Order))
    audit = next(row for row in session.added if isinstance(row, AuditLog))
    assert gaming_session.shift_id == original_source_shift_id == source_shift.id
    assert gaming_session.order_id == order.id
    assert order.shift_id == target_shift.id
    assert order.terminal_id == target_shift.terminal_id
    assert order.branch_id == source_shift.branch_id
    assert order.status == "held"
    assert response == gaming_router.SessionPosHandoffRead(
        order_id=order.id,
        amount_minor=15_700,
        source_shift_id=source_shift.id,
        source_terminal_id=source_shift.terminal_id,
        target_shift_id=target_shift.id,
        target_terminal_id=target_shift.terminal_id,
        already_linked=False,
    )
    assert audit.action == "gaming_session_handoff_to_pos"
    assert audit.before == {
        "source_shift_id": str(source_shift.id),
        "source_terminal_id": str(source_shift.terminal_id),
        "order_id": None,
    }
    assert audit.after == {
        "source_shift_id": str(source_shift.id),
        "source_terminal_id": str(source_shift.terminal_id),
        "target_shift_id": str(target_shift.id),
        "target_terminal_id": str(target_shift.terminal_id),
        "order_id": str(order.id),
    }
    assert audit.reason == "Explicit cross-terminal Gaming to POS handoff"
    shift_lock = str(session.statements[1].compile(dialect=postgresql.dialect()))
    assert "FOR UPDATE" in shift_lock
    assert "ORDER BY shifts.id" in shift_lock
    assert session.statements[1].get_execution_options()["populate_existing"] is True


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("target_overrides", "message"),
    [
        ({"status": "closed"}, "not open"),
        ({"branch_id": uuid4()}, "different branch"),
        ({"company_id": uuid4()}, "not found"),
    ],
)
async def test_handoff_rejects_ineligible_target_without_creating_an_order(
    monkeypatch,
    target_overrides,
    message,
) -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="open")
    target_shift = _shift(tenant, status="open", terminal_id=uuid4())
    for field, value in target_overrides.items():
        setattr(target_shift, field, value)
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    source_terminal = _terminal(tenant, purpose="gaming")
    _install_order_dependencies(monkeypatch)
    session = _Session(
        _Result(gaming_session),
        _Result(rows=[source_shift, target_shift]),
        entities={
            (Station, station.id): station,
            (Shift, source_shift.id): source_shift,
            (Terminal, source_terminal.id): source_terminal,
        },
    )

    with pytest.raises((BusinessRuleError, NotFoundError), match=message):
        await gaming_router.handoff_session_to_pos(
            gaming_session.id,
            gaming_router.SessionPosHandoff(target_shift_id=target_shift.id),
            session,
            tenant,
        )

    assert gaming_session.shift_id == source_shift.id
    assert gaming_session.order_id is None
    assert not any(isinstance(row, (Order, OrderLine, AuditLog)) for row in session.added)


@pytest.mark.asyncio
async def test_handoff_rejects_closed_source_or_same_terminal_target() -> None:
    tenant = _tenant()
    station = _station(tenant)

    for source_status, target_terminal_id, message in (
        ("closed", uuid4(), "Shift is closed"),
        ("open", tenant.terminal_id, "another terminal"),
    ):
        source_shift = _shift(tenant, status=source_status)
        target_shift = _shift(
            tenant,
            status="open",
            terminal_id=target_terminal_id,
        )
        gaming_session = _gaming_session(tenant, station, source_shift)
        source_terminal = _terminal(tenant, purpose="gaming")
        session = _Session(
            _Result(gaming_session),
            _Result(rows=[source_shift, target_shift]),
            entities={
                (Station, station.id): station,
                (Shift, source_shift.id): source_shift,
                (Terminal, source_terminal.id): source_terminal,
            },
        )

        with pytest.raises(BusinessRuleError, match=message):
            await gaming_router.handoff_session_to_pos(
                gaming_session.id,
                gaming_router.SessionPosHandoff(target_shift_id=target_shift.id),
                session,
                tenant,
            )
        assert gaming_session.order_id is None
        assert session.added == []


@pytest.mark.asyncio
async def test_handoff_retry_is_exact_target_idempotent_and_conflicts_elsewhere() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="closed")
    station = _station(tenant)
    existing_order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=uuid4(),
        shift_id=uuid4(),
        total_minor=15_700,
    )
    gaming_session = _gaming_session(
        tenant,
        station,
        source_shift,
        order_id=existing_order.id,
    )
    entities = {
        (Station, station.id): station,
        (Shift, source_shift.id): source_shift,
        (Order, existing_order.id): existing_order,
    }

    response = await gaming_router.handoff_session_to_pos(
        gaming_session.id,
        gaming_router.SessionPosHandoff(target_shift_id=existing_order.shift_id),
        _Session(_Result(gaming_session), entities=entities),
        tenant,
    )
    assert response.already_linked is True
    assert response.order_id == existing_order.id
    assert response.source_shift_id == source_shift.id
    assert response.target_shift_id == existing_order.shift_id

    retry_session = _Session(_Result(gaming_session), entities=entities)
    with pytest.raises(ConflictError, match="different POS shift"):
        await gaming_router.handoff_session_to_pos(
            gaming_session.id,
            gaming_router.SessionPosHandoff(target_shift_id=uuid4()),
            retry_session,
            tenant,
        )
    assert retry_session.added == []
    assert retry_session.flush_count == 0


@pytest.mark.asyncio
async def test_handoff_rejects_a_gaming_only_destination() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="open")
    target_shift = _shift(tenant, status="open", terminal_id=uuid4())
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    source_terminal = _terminal(tenant, purpose="gaming")
    target_terminal = _terminal(
        tenant,
        terminal_id=target_shift.terminal_id,
        purpose="gaming",
        name="Second Gaming Area",
    )
    db = _Session(
        _Result(gaming_session),
        _Result(rows=[source_shift, target_shift]),
        entities={
            (Station, station.id): station,
            (Shift, source_shift.id): source_shift,
            (Terminal, source_terminal.id): source_terminal,
            (Terminal, target_terminal.id): target_terminal,
        },
    )

    with pytest.raises(BusinessRuleError, match="cannot receive POS bills"):
        await gaming_router.handoff_session_to_pos(
            gaming_session.id,
            gaming_router.SessionPosHandoff(target_shift_id=target_shift.id),
            db,
            tenant,
        )

    assert gaming_session.order_id is None
    assert db.added == []


@pytest.mark.asyncio
async def test_gaming_only_terminal_must_handoff_instead_of_local_send() -> None:
    tenant = _tenant()
    source_shift = _shift(tenant, status="open")
    station = _station(tenant)
    gaming_session = _gaming_session(tenant, station, source_shift)
    source_terminal = _terminal(tenant, purpose="gaming")
    db = _Session(
        _Result(gaming_session),
        _Result(source_shift),
        entities={
            (Station, station.id): station,
            (Terminal, source_terminal.id): source_terminal,
        },
    )

    with pytest.raises(BusinessRuleError, match="cross-terminal.*handoff"):
        await gaming_router.send_session_to_pos(gaming_session.id, db, tenant)

    assert gaming_session.order_id is None
    assert db.added == []


@pytest.mark.asyncio
async def test_cafe_pos_terminal_cannot_start_a_gaming_session(monkeypatch) -> None:
    tenant = _tenant()
    station = _station(tenant)
    station.is_active = True
    cafe_terminal = _terminal(tenant, purpose="cafe_pos")

    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(gaming_router, "check_or_reserve", reserve)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="gaming-start-purpose-contract",
            idempotency_request_hash="same-request",
        ),
        headers={},
    )
    db = _Session(
        _Result(station),
        entities={(Terminal, cafe_terminal.id): cafe_terminal},
    )

    with pytest.raises(BusinessRuleError, match="Cafe POS.*cannot start gaming"):
        await gaming_router.start_session(
            gaming_router.SessionStart(
                station_id=station.id,
                shift_id=uuid4(),
                expected_rate_per_hour_minor=20_000,
            ),
            db,
            request,
            tenant,
        )

    assert db.added == []
    assert len(db.statements) == 1


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

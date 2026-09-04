"""Database-free route regressions for the cafe's operational handoff rules.

These tests call the real endpoint functions with a deliberately small async
session double.  They protect state-transition and response contracts without
turning the unit suite into an implicit PostgreSQL integration suite.
"""

from __future__ import annotations

from datetime import UTC, datetime
from inspect import signature
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from fastapi import BackgroundTasks
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy.dialects import postgresql

from app.api.v1.gaming import router as gaming_router
from app.api.v1.memberships import router as memberships_router
from app.api.v1.pos import router as pos_router
from app.core.errors import (
    BusinessRuleError,
    CheckoutClaimConflictError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
)
from app.core.permissions import ROLE_PERMISSIONS
from app.core.tenant import TenantContext
from app.events.events import OrderPaid
from app.models import (
    AuditLog,
    Branch,
    Order,
    OrderCheckoutClaim,
    OrderLine,
    Payment,
    Station,
    Table,
    Terminal,
    User,
)


class _Result:
    def __init__(self, *, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows

    def first(self):
        return self.rows[0] if self.rows else self.scalar


class _Session:
    def __init__(self, *results: _Result, entities=None) -> None:
        self.results = list(results)
        self.entities = {} if entities is None else entities
        self.statements = []
        self.added = []
        self.flush_count = 0
        self.refreshed = []

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

    async def refresh(self, entity, attribute_names=None) -> None:
        self.refreshed.append((entity, attribute_names))
        if attribute_names and "checkout_version" in attribute_names:
            entity.checkout_version = int(entity.checkout_version) + 1


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


def test_table_bill_routes_enforce_required_domain_permissions() -> None:
    assert _route_permissions(pos_router.add_order_lines) == (
        "tables.write",
        "pos.write",
    )
    assert _route_permissions(pos_router.send_order_to_pos) == (
        "tables.write",
        "pos.write",
    )
    assert _route_permissions(pos_router.void_order_line) == (
        "tables.write",
        "pos.void",
    )
    assert _route_permissions(pos_router.list_active_table_orders) == ("tables.read",)

    # Self-service floor staff can prepare table drafts but cannot cancel
    # already-released kitchen work. Cashiers and managers retain the audited
    # reasoned-void action.
    assert "tables.write" in ROLE_PERMISSIONS["staff"]
    assert "pos.void" not in ROLE_PERMISSIONS["staff"]
    assert "pos.void" in ROLE_PERMISSIONS["cashier"]
    assert "pos.void" in ROLE_PERMISSIONS["manager"]


def test_membership_operations_do_not_widen_legacy_evidence_controls() -> None:
    """Co-owners can sell/refund memberships, but not rewrite recovery evidence."""
    assert _route_permissions(memberships_router.prepare_membership_payment) == (
        "memberships.manage",
    )
    assert _route_permissions(memberships_router.refund_membership) == (
        "memberships.manage",
    )

    protected_support_endpoints = (
        memberships_router.resolve_rejected_membership_payment_attempt,
        memberships_router.register_rejected_membership_refund_attempt,
        memberships_router.list_rejected_membership_refund_attempts,
        memberships_router.resolve_rejected_membership_refund_attempt,
        memberships_router.reconcile_membership_evidence,
        memberships_router.list_membership_evidence_reconciliations,
    )
    for endpoint in protected_support_endpoints:
        assert _route_permissions(endpoint) == ("admin.system",)


@pytest.mark.asyncio
async def test_whole_order_void_replay_requires_the_original_reason() -> None:
    tenant = _tenant()
    order_id = uuid4()
    voided_at = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        status="void",
    )

    same_reason_session = _Session(
        _Result(scalar=order),
        _Result(scalar=voided_at),
        _Result(rows=["Customer left"]),
    )
    assert (
        await pos_router.void_held_order(
            order_id,
            pos_router.VoidOrderRequest(reason="Customer left"),
            same_reason_session,
            tenant,
        )
        is None
    )

    changed_reason_session = _Session(
        _Result(scalar=order),
        _Result(scalar=voided_at),
        _Result(rows=["Customer left"]),
    )
    with pytest.raises(BusinessRuleError, match="already voided with a different reason"):
        await pos_router.void_held_order(
            order_id,
            pos_router.VoidOrderRequest(reason="Duplicate order"),
            changed_reason_session,
            tenant,
        )


@pytest.mark.asyncio
async def test_table_order_create_requires_tables_access_before_reserving_request(
    monkeypatch,
) -> None:
    tenant = _tenant()
    checked: list[str] = []

    async def _deny(_session, _tenant, permission: str) -> None:
        checked.append(permission)
        raise ForbiddenError(f"missing permission: {permission}")

    monkeypatch.setattr(pos_router, "require_permission", _deny)
    client_line_id = uuid4()
    payload = pos_router.OrderCreate(
        type="dine_in",
        table_id=uuid4(),
        shift_id=uuid4(),
        lines=[
            pos_router.OrderLineCreate(
                client_line_id=client_line_id,
                menu_item_id=uuid4(),
                qty=1,
            )
        ],
    )
    request = SimpleNamespace(state=SimpleNamespace())

    with pytest.raises(ForbiddenError, match="tables.write"):
        await pos_router.create_order(payload, _Session(), request, tenant)

    assert checked == ["tables.write"]


@pytest.mark.asyncio
async def test_protected_recovery_holds_only_safe_unpaid_direct_order(
    monkeypatch,
) -> None:
    tenant = _tenant(protected_access=True)
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=uuid4(),
        opened_by=tenant.user_id,
        table_id=None,
        type="takeaway",
        status="open",
        checkout_version=7,
        total_minor=2_500,
        held_at=None,
        invoice_no=None,
        invoice_issued_at=None,
        closed_at=None,
    )
    shift = _shift(tenant, id=order.shift_id)
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(scalar=1),
    )
    stored = {}

    async def _reserve(*_args, **_kwargs):
        return None

    async def _read(_session, current):
        assert current is order
        return SimpleNamespace(
            model_dump=lambda **_kwargs: {
                "id": str(order.id),
                "status": order.status,
                "checkout_version": order.checkout_version,
            }
        )

    async def _store(*_args, **kwargs):
        stored.update(kwargs)

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    monkeypatch.setattr(pos_router, "_build_order_read", _read)
    monkeypatch.setattr(pos_router, "store_response", _store)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="recover-direct-order",
            idempotency_request_hash="request-hash",
        )
    )

    response = await pos_router.hold_direct_order_for_checkout(
        order.id,
        pos_router.HoldDirectOrderForCheckoutRequest(
            expected_checkout_version=7,
            reason="Recovered after the first tablet crashed",
        ),
        session,
        request,
        tenant,
    )

    assert response.model_dump()["status"] == "held"
    assert order.status == "held"
    assert order.held_at is not None
    assert order.checkout_version == 8
    audit = next(entity for entity in session.added if isinstance(entity, AuditLog))
    assert audit.action == "pos_direct_order_hold_for_checkout"
    assert audit.actor_user_id == tenant.user_id
    assert audit.reason == "Recovered after the first tablet crashed"
    assert audit.before == {"status": "open", "checkout_version": 7}
    assert audit.after["checkout_version"] == 8
    assert stored["status_code"] == 200

    # A replay with the original key is served from idempotency storage in the
    # real route. A different key must not adopt an already-shared held bill as
    # a fresh, unaudited recovery action.
    with pytest.raises(BusinessRuleError, match="status=held"):
        await pos_router.hold_direct_order_for_checkout(
            order.id,
            pos_router.HoldDirectOrderForCheckoutRequest(
                expected_checkout_version=8,
                reason="Second recovery must not be accepted",
            ),
            _Session(_Result(scalar=order)),
            SimpleNamespace(
                state=SimpleNamespace(
                    idempotency_key="different-recovery-key",
                    idempotency_request_hash="different-request-hash",
                )
            ),
            tenant,
        )


@pytest.mark.asyncio
async def test_direct_order_recovery_rejects_unprotected_actor_before_idempotency(
    monkeypatch,
) -> None:
    tenant = _tenant(protected_access=False)
    reserved = False

    async def _reserve(*_args, **_kwargs):
        nonlocal reserved
        reserved = True
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    with pytest.raises(ForbiddenError, match="protected owner"):
        await pos_router.hold_direct_order_for_checkout(
            uuid4(),
            pos_router.HoldDirectOrderForCheckoutRequest(
                expected_checkout_version=1,
                reason="Recover abandoned direct bill",
            ),
            _Session(),
            SimpleNamespace(state=SimpleNamespace()),
            tenant,
        )

    assert reserved is False


@pytest.mark.asyncio
async def test_direct_order_recovery_rejects_empty_or_fully_voided_order(
    monkeypatch,
) -> None:
    tenant = _tenant(protected_access=True)
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=uuid4(),
        table_id=None,
        type="takeaway",
        status="open",
        checkout_version=4,
        total_minor=0,
        held_at=None,
        invoice_no=None,
        invoice_issued_at=None,
        closed_at=None,
    )
    shift = _shift(tenant, id=order.shift_id)

    async def _reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    with pytest.raises(BusinessRuleError, match="no active items"):
        await pos_router.hold_direct_order_for_checkout(
            order.id,
            pos_router.HoldDirectOrderForCheckoutRequest(
                expected_checkout_version=4,
                reason="Abandoned empty legacy draft",
            ),
            _Session(
                _Result(scalar=order),
                _Result(scalar=shift),
                _Result(scalar=0),
                _Result(scalar=0),
            ),
            SimpleNamespace(
                state=SimpleNamespace(
                    idempotency_key="recover-empty-direct-order",
                    idempotency_request_hash="request-hash",
                )
            ),
            tenant,
        )

    assert order.status == "open"


@pytest.mark.asyncio
async def test_direct_publish_is_atomic_and_same_instance_retry_rotates_claim(
    monkeypatch,
) -> None:
    tenant = _tenant()
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=uuid4(),
        opened_by=tenant.user_id,
        table_id=None,
        type="takeaway",
        status="open",
        checkout_version=7,
        total_minor=2_500,
        held_at=None,
        invoice_no=None,
        invoice_issued_at=None,
        closed_at=None,
    )
    shift = _shift(tenant, id=order.shift_id)
    client_instance = uuid4()
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="publish-direct-order",
            idempotency_request_hash="request-hash",
        )
    )
    first_session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(scalar=1),
        _Result(scalar=None),
    )
    first_http_response = SimpleNamespace(headers={})

    async def _must_not_store_raw_claim(*_args, **_kwargs):
        raise AssertionError("raw checkout claim must not enter idempotency storage")

    monkeypatch.setattr(pos_router, "check_or_reserve", _must_not_store_raw_claim)
    monkeypatch.setattr(pos_router, "store_response", _must_not_store_raw_claim)

    first = await pos_router.publish_direct_order_checkout_claim(
        order.id,
        pos_router.PublishDirectCheckoutClaimRequest(expected_checkout_version=7),
        first_session,
        request,
        first_http_response,
        client_instance,
        tenant,
    )

    assert order.status == "held"
    assert order.held_at is not None
    assert order.checkout_version == 8
    assert first.order_version == 8
    assert first.reused is False
    assert first_http_response.headers["Cache-Control"] == "no-store"
    claim = next(
        entity
        for entity in first_session.added
        if isinstance(entity, OrderCheckoutClaim)
    )
    assert claim.client_instance_hash != str(client_instance)
    assert len(claim.client_instance_hash) == 64

    retry_session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(scalar=1),
        _Result(scalar=claim),
    )
    retried = await pos_router.publish_direct_order_checkout_claim(
        order.id,
        pos_router.PublishDirectCheckoutClaimRequest(expected_checkout_version=7),
        retry_session,
        request,
        SimpleNamespace(headers={}),
        client_instance,
        tenant,
    )

    assert retried.reused is True
    assert retried.claim_token != first.claim_token

    competing_session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(scalar=1),
        _Result(scalar=claim),
    )
    with pytest.raises(CheckoutClaimConflictError):
        await pos_router.publish_direct_order_checkout_claim(
            order.id,
            pos_router.PublishDirectCheckoutClaimRequest(expected_checkout_version=7),
            competing_session,
            request,
            SimpleNamespace(headers={}),
            uuid4(),
            tenant,
        )


@pytest.mark.asyncio
async def test_direct_publish_rejects_empty_or_fully_voided_order() -> None:
    tenant = _tenant()
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=uuid4(),
        opened_by=tenant.user_id,
        table_id=None,
        type="takeaway",
        status="open",
        checkout_version=3,
        total_minor=0,
        held_at=None,
        invoice_no=None,
        invoice_issued_at=None,
        closed_at=None,
    )
    shift = _shift(tenant, id=order.shift_id)

    with pytest.raises(BusinessRuleError, match="no active items"):
        await pos_router.publish_direct_order_checkout_claim(
            order.id,
            pos_router.PublishDirectCheckoutClaimRequest(
                expected_checkout_version=3,
            ),
            _Session(
                _Result(scalar=order),
                _Result(scalar=shift),
                _Result(scalar=0),
                _Result(scalar=0),
            ),
            SimpleNamespace(
                state=SimpleNamespace(
                    idempotency_key="publish-empty-direct-order",
                    idempotency_request_hash="request-hash",
                )
            ),
            SimpleNamespace(headers={}),
            uuid4(),
            tenant,
        )

    assert order.status == "open"


@pytest.mark.asyncio
async def test_private_direct_open_is_owner_scoped_but_protected_discoverable() -> None:
    creator = _tenant()
    order = SimpleNamespace(
        id=uuid4(),
        company_id=creator.company_id,
        branch_id=creator.branch_id,
        terminal_id=creator.terminal_id,
        opened_by=creator.user_id,
        table_id=None,
        type="takeaway",
        status="open",
    )
    other = _tenant(
        company_id=creator.company_id,
        branch_id=creator.branch_id,
        terminal_id=creator.terminal_id,
    )
    protected = _tenant(
        company_id=creator.company_id,
        branch_id=creator.branch_id,
        terminal_id=creator.terminal_id,
        protected_access=True,
    )

    with pytest.raises(NotFoundError):
        await pos_router.get_order(
            order.id,
            _Session(entities={(Order, order.id): order}),
            other,
        )

    with pytest.raises(NotFoundError):
        await pos_router.publish_direct_order_checkout_claim(
            order.id,
            pos_router.PublishDirectCheckoutClaimRequest(expected_checkout_version=1),
            _Session(_Result(scalar=order)),
            SimpleNamespace(
                state=SimpleNamespace(
                    idempotency_key="private-direct-publish",
                    idempotency_request_hash="request-hash",
                )
            ),
            SimpleNamespace(headers={}),
            uuid4(),
            other,
        )

    with pytest.raises(BusinessRuleError, match="Recover to POS"):
        await pos_router.publish_direct_order_checkout_claim(
            order.id,
            pos_router.PublishDirectCheckoutClaimRequest(expected_checkout_version=1),
            _Session(_Result(scalar=order)),
            SimpleNamespace(
                state=SimpleNamespace(
                    idempotency_key="protected-direct-publish",
                    idempotency_request_hash="request-hash",
                )
            ),
            SimpleNamespace(headers={}),
            uuid4(),
            protected,
        )

    # An empty list is enough to inspect the SQL visibility boundary without
    # invoking the response aggregation queries.
    ordinary_list = _Session(_Result(rows=[]))
    assert await pos_router.list_orders(
        ordinary_list,
        other,
        status_filter=["open"],
    ) == []
    ordinary_where = str(ordinary_list.statements[0].whereclause)
    assert "orders.opened_by" in ordinary_where

    protected_list = _Session(_Result(rows=[]))
    assert await pos_router.list_orders(
        protected_list,
        protected,
        status_filter=["open"],
    ) == []
    protected_where = str(protected_list.statements[0].whereclause)
    assert "orders.opened_by" not in protected_where

    # Publication removes the private-draft read restriction for the shared
    # held queue; a protected owner also retains open-draft reconciliation.
    pos_router._require_order_read_visibility(order, creator)
    pos_router._require_order_read_visibility(order, protected)
    order.status = "held"
    pos_router._require_order_read_visibility(order, other)


def test_private_direct_draft_mutations_are_creator_only() -> None:
    creator = _tenant()
    ordinary_other = _tenant(
        company_id=creator.company_id,
        branch_id=creator.branch_id,
        terminal_id=creator.terminal_id,
    )
    protected_other = _tenant(
        company_id=creator.company_id,
        branch_id=creator.branch_id,
        terminal_id=creator.terminal_id,
        protected_access=True,
    )
    draft = SimpleNamespace(
        status="open",
        table_id=None,
        type="takeaway",
        opened_by=creator.user_id,
    )

    pos_router._require_private_direct_draft_creator(
        draft,
        creator,
        operation="changed",
    )
    with pytest.raises(NotFoundError):
        pos_router._require_private_direct_draft_creator(
            draft,
            ordinary_other,
            operation="changed",
        )
    with pytest.raises(BusinessRuleError, match="Recover to POS"):
        pos_router._require_private_direct_draft_creator(
            draft,
            protected_other,
            operation="changed",
        )

    # Once explicitly recovered, the held bill is shared and the normal claim
    # machinery governs it. Table and session orders are collaborative too.
    for shared in (
        SimpleNamespace(**{**draft.__dict__, "status": "held"}),
        SimpleNamespace(**{**draft.__dict__, "table_id": uuid4()}),
        SimpleNamespace(**{**draft.__dict__, "type": "session"}),
    ):
        pos_router._require_private_direct_draft_creator(
            shared,
            protected_other,
            operation="changed",
        )

    guarded_endpoints = (
        pos_router.add_order_lines,
        pos_router.attach_order_customer,
        pos_router.apply_order_discount,
        pos_router.redeem_points,
        pos_router.redeem_reward,
        pos_router.publish_direct_order_checkout_claim,
        pos_router.finalize_zero_total_order,
        pos_router.record_payment,
    )
    for endpoint in guarded_endpoints:
        assert "_require_private_direct_draft_creator" in endpoint.__code__.co_names

    # These are the two deliberate, reasoned escape hatches for an abandoned
    # draft and must remain usable by protected owners.
    assert (
        "_require_private_direct_draft_creator"
        not in pos_router.hold_direct_order_for_checkout.__code__.co_names
    )
    assert (
        "_require_private_direct_draft_creator"
        not in pos_router.void_held_order.__code__.co_names
    )


def test_create_batch_rejects_duplicate_client_line_identity() -> None:
    client_line_id = uuid4()
    lines = [
        pos_router.OrderLineCreate(
            client_line_id=client_line_id,
            menu_item_id=uuid4(),
            qty=1,
        ),
        pos_router.OrderLineCreate(
            client_line_id=client_line_id,
            menu_item_id=uuid4(),
            qty=1,
        ),
    ]

    with pytest.raises(BusinessRuleError, match="same offline line action"):
        pos_router._validate_client_line_ids(lines)


@pytest.mark.asyncio
async def test_append_reused_client_line_id_is_stable_conflict_without_partial_write(
    monkeypatch,
) -> None:
    tenant = _tenant()
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=uuid4(),
        table_id=uuid4(),
        status="open",
        checkout_version=7,
    )
    client_line_id = uuid4()
    session = _Session(
        _Result(scalar=order),
        _Result(rows=[client_line_id]),
    )

    async def _reserve(*_args, **_kwargs):
        return None

    async def _guard(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    monkeypatch.setattr(pos_router, "guard_checkout_relevant_mutation", _guard)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="different-request-key",
            idempotency_request_hash="different-body-hash",
        )
    )

    with pytest.raises(ConflictError, match="already saved"):
        await pos_router.add_order_lines(
            order.id,
            pos_router.OrderLinesAppend(
                expected_checkout_version=7,
                lines=[
                    pos_router.OrderLineCreate(
                        client_line_id=client_line_id,
                        menu_item_id=uuid4(),
                        qty=1,
                    )
                ],
            ),
            session,
            request,
            tenant,
        )

    assert session.added == []
    assert len(session.statements) == 2


@pytest.mark.asyncio
async def test_active_table_lookup_keeps_held_bill_visible_as_read_only(
    monkeypatch,
) -> None:
    tenant = _tenant()
    open_bill = SimpleNamespace(id=uuid4(), status="open")
    held_bill = SimpleNamespace(id=uuid4(), status="held")
    session = _Session(_Result(rows=[open_bill, held_bill]))

    async def _identity(_session, order):
        return order

    monkeypatch.setattr(pos_router, "_build_order_read", _identity)
    result = await pos_router.list_active_table_orders(session, tenant)

    assert result == [open_bill, held_bill]
    values = list(session.statements[0].compile().params.values())
    assert ["open", "held"] in values


def _tenant(
    *,
    company_id: UUID | None = None,
    branch_id: UUID | None = None,
    terminal_id: UUID | None = None,
    user_id: UUID | None = None,
    protected_access: bool = False,
) -> TenantContext:
    return TenantContext(
        user_id=user_id or uuid4(),
        company_id=company_id or uuid4(),
        branch_id=branch_id or uuid4(),
        terminal_id=terminal_id or uuid4(),
        roles=("owner",),
        protected_access=protected_access,
    )


def _shift(tenant: TenantContext, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "branch_id": tenant.branch_id,
        "terminal_id": tenant.terminal_id,
        "opened_by": tenant.user_id,
        "opened_at": datetime.now(UTC),
        "closed_by": None,
        "closed_at": None,
        "opening_float_minor": 0,
        "expected_minor": 5_000,
        "counted_minor": None,
        "variance_minor": None,
        "status": "open",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _billable_non_kitchen_line_row():
    return (
        SimpleNamespace(
            id=uuid4(),
            kitchen_released_at=None,
            kitchen_round_no=None,
            kitchen_status="queued",
        ),
        SimpleNamespace(type="gaming"),
    )


def _gaming_session(tenant: TenantContext, station_id: UUID, shift_id: UUID, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "station_id": station_id,
        "shift_id": shift_id,
        "opened_by": tenant.user_id,
        "order_id": None,
        "start_at": datetime(2026, 7, 15, 10, tzinfo=UTC),
        "end_at": datetime(2026, 7, 15, 10, 47, tzinfo=UTC),
        "paused_minutes": 0,
        "timer_minutes": None,
        "billable_minutes": 47,
        "rate_per_hour_minor": 20_000,
        "amount_minor": 15_667,
        "status": "ended",
        "cancelled_at": None,
        "cancelled_by": None,
        "cancel_reason": None,
        "customer_name": "Cafe Guest",
        "customer_phone": "9000000000",
        "tax_rate": 0.18,
        "sac_code": "999692",
        "rate_includes_tax": True,
        "package_id": None,
        "extra_controllers": 0,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _station(tenant: TenantContext, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "branch_id": tenant.branch_id,
        "name": "PS5 1",
        "type": "ps5",
        "tax_rate": 0.18,
        "sac_code": "999692",
        "rate_includes_tax": True,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def test_confirmed_payment_rejects_a_stale_or_partial_full_settlement() -> None:
    exact = pos_router.PaymentCreate(
        method="upi",
        amount_minor=10_000,
        expected_order_total_minor=12_000,
        expected_due_minor=10_000,
    )
    pos_router._validate_confirmed_payment_balance(
        exact,
        order_total_minor=12_000,
        due_minor=10_000,
    )

    with pytest.raises(BusinessRuleError, match="Order total changed"):
        pos_router._validate_confirmed_payment_balance(
            exact,
            order_total_minor=12_100,
            due_minor=10_000,
        )
    with pytest.raises(BusinessRuleError, match="Order balance changed"):
        pos_router._validate_confirmed_payment_balance(
            exact,
            order_total_minor=12_000,
            due_minor=9_000,
        )

    partial = exact.model_copy(update={"amount_minor": 5_000})
    with pytest.raises(BusinessRuleError, match="must equal"):
        pos_router._validate_confirmed_payment_balance(
            partial,
            order_total_minor=12_000,
            due_minor=10_000,
        )

    partial_without_client_expectations = pos_router.PaymentCreate(
        method="cash",
        amount_minor=5_000,
        tendered_minor=5_000,
    )
    with pytest.raises(BusinessRuleError, match="Split payments are not enabled"):
        pos_router._validate_confirmed_payment_balance(
            partial_without_client_expectations,
            order_total_minor=10_000,
            due_minor=10_000,
        )


@pytest.mark.asyncio
async def test_record_payment_with_tip_grows_order_total_and_settles_in_one_shot(
    monkeypatch,
) -> None:
    """A tip is additional money collected alongside the bill.

    It must never be folded into amount_minor or the exact-amount-due match
    in _validate_confirmed_payment_balance (that check protects the bill
    itself from stale reads/split payments, and is exercised untouched by
    the amount_minor==due_minor path above). Instead the endpoint layers it
    on afterward: Order.tip_minor and Order.total_minor both grow by the tip,
    and the Payment row records what was actually collected (bill + tip) —
    exactly what ledger.py's TIPS_PAYABLE line and the reports balance check
    (app/api/v1/reports/router.py) already expect from order.total_minor.
    """
    tenant = _tenant()
    shift = _shift(tenant, opened_by=tenant.user_id, status="open")
    branch = SimpleNamespace(
        id=tenant.branch_id,
        company_id=tenant.company_id,
        deleted_at=None,
        timezone="Asia/Kolkata",
        code="MN",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        opened_by=tenant.user_id,
        type="takeaway",
        status="open",
        total_minor=10_000,
        tip_minor=0,
        table_id=None,
        customer_phone=None,
        # Pre-set so _finalize_order skips invoice allocation — this test is
        # about the tip/settlement math, not the invoice-numbering service.
        invoice_no="INV-PRESET-0001",
        fiscal_year="2026-27",
        closed_at=None,
        invoice_issued_at=None,
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    stored: dict = {}

    async def _store_response(*_args, **kwargs):
        stored.update(kwargs)
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)

    async def _no_inventory(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "deduct_for_order", _no_inventory)

    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="payment-with-tip-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),  # order lookup
        _Result(scalar=shift),  # shift lookup
        _Result(scalar=0),  # _paid_total: nothing paid yet
        _Result(rows=[]),  # consume_membership_benefits: no reservations
        _Result(scalar=None),  # consume_points_redemption: no redemption row
        _Result(rows=[_billable_non_kitchen_line_row()]),
        entities={(Branch, tenant.branch_id): branch},
    )

    background_tasks = BackgroundTasks()
    response = await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="upi",
            amount_minor=10_000,
            tip_minor=1_500,
            expected_order_total_minor=10_000,
            expected_due_minor=10_000,
        ),
        session,
        request,
        background_tasks,
        tenant,
    )

    # The bill itself was matched exactly (amount_minor == due_minor == the
    # pre-tip total) — the tip rode along on top without touching that check.
    assert order.tip_minor == 1_500
    assert order.total_minor == 11_500
    assert order.status == "paid"

    payment = next(entity for entity in session.added if isinstance(entity, Payment))
    assert payment.amount_minor == 11_500  # bill + tip actually collected

    assert response.amount_minor == 11_500
    assert response.bill_amount_minor == 10_000
    assert response.tip_minor == 1_500
    assert response.order_status == "paid"
    assert stored["status_code"] == 201

    # A fully-settled payment must queue exactly one OrderPaid publish as a
    # BackgroundTask — never awaited inline (that would delay the response
    # on a slow webhook) and never fired before the response, since
    # BackgroundTasks only run after this request's session has committed.
    assert len(background_tasks.tasks) == 1


@pytest.mark.asyncio
async def test_record_payment_schedules_order_paid_event_with_correct_shape(
    monkeypatch,
) -> None:
    """The queued background task must publish OrderPaid with the real
    order/company/branch ids, the settled total, and the payment method —
    and must never raise even if the bus itself blows up.
    """
    tenant = _tenant()
    shift = _shift(tenant, opened_by=tenant.user_id, status="open")
    branch = SimpleNamespace(
        id=tenant.branch_id,
        company_id=tenant.company_id,
        deleted_at=None,
        timezone="Asia/Kolkata",
        code="MN",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        opened_by=tenant.user_id,
        type="takeaway",
        status="open",
        total_minor=5_000,
        tip_minor=0,
        table_id=None,
        customer_phone=None,
        invoice_no="INV-PRESET-0002",
        fiscal_year="2026-27",
        closed_at=None,
        invoice_issued_at=None,
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    async def _store_response(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)

    async def _no_inventory(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "deduct_for_order", _no_inventory)

    published: list[OrderPaid] = []

    class _FakeBus:
        async def publish(self, event):
            published.append(event)

    monkeypatch.setattr(pos_router, "get_event_bus", lambda: _FakeBus())

    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="payment-event-shape-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(rows=[]),
        _Result(scalar=None),
        _Result(rows=[_billable_non_kitchen_line_row()]),
        entities={(Branch, tenant.branch_id): branch},
    )

    background_tasks = BackgroundTasks()
    await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="cash",
            amount_minor=5_000,
            tendered_minor=5_000,
            expected_order_total_minor=5_000,
            expected_due_minor=5_000,
        ),
        session,
        request,
        background_tasks,
        tenant,
    )

    assert published == []  # not fired inline — only queued so far
    await background_tasks()  # simulate Starlette running it post-response

    assert len(published) == 1
    event = published[0]
    assert event.order_id == order.id
    assert event.company_id == tenant.company_id
    assert event.branch_id == tenant.branch_id
    assert event.total_minor == 5_000
    assert event.method == "cash"


@pytest.mark.asyncio
async def test_record_payment_order_paid_publish_failure_never_raises(
    monkeypatch,
) -> None:
    """A broken/unreachable event bus must not surface as an error — the
    payment already succeeded and the response was already sent by the time
    this background task runs.
    """
    tenant = _tenant()
    shift = _shift(tenant, opened_by=tenant.user_id, status="open")
    branch = SimpleNamespace(
        id=tenant.branch_id,
        company_id=tenant.company_id,
        deleted_at=None,
        timezone="Asia/Kolkata",
        code="MN",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        opened_by=tenant.user_id,
        type="takeaway",
        status="open",
        total_minor=5_000,
        tip_minor=0,
        table_id=None,
        customer_phone=None,
        invoice_no="INV-PRESET-0003",
        fiscal_year="2026-27",
        closed_at=None,
        invoice_issued_at=None,
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    async def _store_response(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)

    async def _no_inventory(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "deduct_for_order", _no_inventory)

    class _ExplodingBus:
        async def publish(self, _event):
            raise RuntimeError("event bus is down")

    monkeypatch.setattr(pos_router, "get_event_bus", lambda: _ExplodingBus())

    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="payment-event-failure-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(rows=[]),
        _Result(scalar=None),
        _Result(rows=[_billable_non_kitchen_line_row()]),
        entities={(Branch, tenant.branch_id): branch},
    )

    background_tasks = BackgroundTasks()
    response = await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="cash",
            amount_minor=5_000,
            tendered_minor=6_000,
            expected_order_total_minor=5_000,
            expected_due_minor=5_000,
        ),
        session,
        request,
        background_tasks,
        tenant,
    )
    assert response.method == "cash"
    assert response.amount_minor == 5_000
    assert response.bill_amount_minor == 5_000
    assert response.tip_minor == 0
    assert response.tendered_minor == 6_000
    assert response.change_minor == 1_000
    assert response.paid_at is not None
    assert response.order_status == "paid"

    # Must not raise even though the bus explodes.
    await background_tasks()


@pytest.mark.asyncio
async def test_gaming_points_ratio_excludes_tip_on_a_tipped_and_discounted_order() -> None:
    """A tip folded into order.total_minor by record_payment (see the test
    above) must not inflate the loyalty-points paid ratio.

    Setup: a ₹1000 gaming line (200 raw points before ratio scaling), a ₹400
    manual discount, and a ₹600 tip collected on top of the discounted ₹600
    bill — the same 100%-of-bill tip a generous, fully-discount-covered
    customer might leave. order.total_minor therefore already includes the
    tip (600 bill + 600 tip = 1200), exactly as it sits post-record_payment.

    Correct ratio uses the taxable (pre-tip) amounts on both sides:
    600 / (600 + 400) = 0.6, so points = 200 * 0.6 = 120.

    The pre-fix bug used order.total_minor (tip included) as the numerator
    and folded it into the denominator too: 1200 / (1200 + 400) = 0.75,
    over-awarding 150 points on money that was actually a tip, not bill
    payment.
    """
    item_id = uuid4()
    gaming_item = SimpleNamespace(id=item_id, type="gaming")
    order_line = SimpleNamespace(menu_item_id=item_id, line_total_minor=100_000)
    order = SimpleNamespace(
        total_minor=120_000,  # 600 bill + 600 tip, in minor units (paise)
        tip_minor=60_000,
        manual_discount_minor=40_000,
        points_redeemed_minor=0,
    )
    session = _Session(_Result(rows=[gaming_item]))

    points_earned = await pos_router._compute_points_with_multiplier(
        session,
        order=order,
        order_lines=[order_line],
        membership_multiplier=1.0,
    )

    assert points_earned == 120
    assert points_earned != 150  # the tip-inflated result the bug produced


def test_customer_repricing_recovers_stored_gross_without_current_menu_price() -> None:
    inclusive = SimpleNamespace(
        line_total_minor=16_000,
        taxable_value_minor=13_559,
        discount_minor=4_000,
    )
    exclusive = SimpleNamespace(
        line_total_minor=18_880,
        taxable_value_minor=16_000,
        discount_minor=4_000,
    )

    assert pos_router._stored_line_gross_amount(
        inclusive,
        price_includes_tax=True,
    ) == 20_000
    assert pos_router._stored_line_gross_amount(
        exclusive,
        price_includes_tax=False,
    ) == 20_000


@pytest.mark.asyncio
async def test_customer_repricing_updates_existing_lines_and_canonical_order_total(
    monkeypatch,
) -> None:
    company_id = uuid4()
    branch_id = uuid4()
    item_id = uuid4()
    order = SimpleNamespace(
        id=uuid4(),
        branch_id=branch_id,
        customer_phone="9000000000",
        place_of_supply_state_code="32",
        delivery_via=None,
        subtotal_minor=0,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        tax_minor=0,
        round_off_minor=0,
        total_minor=0,
    )
    line = SimpleNamespace(
        menu_item_id=item_id,
        qty=1,
        unit_price_minor=20_000,
        line_total_minor=20_000,
        discount_minor=0,
        taxable_value_minor=16_949,
        tax_rate=0.18,
        cgst_minor=1_525,
        sgst_minor=1_526,
        igst_minor=0,
        cess_minor=0,
    )
    item = SimpleNamespace(
        id=item_id,
        sku="FOOD-1",
        type="food",
        price_includes_tax=True,
    )

    class _Pricing:
        def __init__(self, _session) -> None:
            pass

        async def price_time_based_line(self, **kwargs):
            assert kwargs["amount_minor"] == 20_000
            assert kwargs["customer_phone"] == "9000000000"
            assert kwargs["item_type"] == "food"
            return SimpleNamespace(
                total_minor=16_000,
                discount_minor=4_000,
                taxable_minor=13_559,
                cgst_minor=1_220,
                sgst_minor=1_221,
                igst_minor=0,
            )

    monkeypatch.setattr(pos_router, "OrderPricingService", _Pricing)

    async def _no_free_allowance(*_args, **_kwargs):
        return SimpleNamespace(gaming_minutes=0, hookah_count=0)

    monkeypatch.setattr(pos_router, "reserve_membership_benefits", _no_free_allowance)
    session = _Session(
        _Result(rows=[line]),
        _Result(rows=[item]),
        _Result(rows=[]),
        _Result(),  # points redemption: existing reservation lookup (none)
        _Result(),  # points redemption: reserve_points_redemption's own lookup (none)
    )

    await pos_router._reprice_unpaid_order_for_customer(
        session,
        order=order,
        company_id=company_id,
    )

    assert line.line_total_minor == 16_000
    assert line.discount_minor == 4_000
    assert order.total_minor == 16_000
    assert order.discount_minor == 4_000
    assert order.tax_minor == 2_441
    assert order.round_off_minor == 0


@pytest.mark.asyncio
async def test_deleted_package_session_cannot_receive_hourly_membership_waiver(
    monkeypatch,
) -> None:
    company_id = uuid4()
    branch_id = uuid4()
    item_id = uuid4()
    order = SimpleNamespace(
        id=uuid4(),
        branch_id=branch_id,
        customer_phone="9000000000",
        place_of_supply_state_code="32",
        delivery_via=None,
        subtotal_minor=0,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        tax_minor=0,
        round_off_minor=0,
        total_minor=0,
    )
    line = SimpleNamespace(
        menu_item_id=item_id,
        qty=1,
        unit_price_minor=15_000,
        line_total_minor=15_000,
        discount_minor=0,
        taxable_value_minor=12_712,
        tax_rate=0.18,
        cgst_minor=1_144,
        sgst_minor=1_144,
        igst_minor=0,
        cess_minor=0,
    )
    item = SimpleNamespace(
        id=item_id,
        sku="SESSION-PS5",
        type="gaming",
        price_includes_tax=True,
    )
    station = SimpleNamespace(type="ps5")
    gaming_session = SimpleNamespace(
        package_id=None,
        billing_mode="legacy_ambiguous",
        package_price_minor_snapshot=None,
        package_duration_minutes_snapshot=None,
        package_variant_snapshot=None,
        package_station_type_snapshot=None,
        status="ended",
        amount_minor=15_000,
        billable_minutes=60,
        rate_per_hour_minor=15_000,
        rate_includes_tax=True,
        tax_rate=0.18,
    )
    requested: dict[str, int] = {}

    async def _reserve(*_args, **kwargs):
        requested["gaming_minutes"] = kwargs["requested_gaming_minutes"]
        return SimpleNamespace(gaming_minutes=60, hookah_count=0)

    class _Pricing:
        def __init__(self, _session) -> None:
            pass

        async def price_time_based_line(self, **kwargs):
            assert kwargs["amount_minor"] == 15_000
            assert kwargs["allowance_minor"] == 0
            return SimpleNamespace(
                total_minor=15_000,
                discount_minor=0,
                taxable_minor=12_712,
                cgst_minor=1_144,
                sgst_minor=1_144,
                igst_minor=0,
            )

    monkeypatch.setattr(pos_router, "reserve_membership_benefits", _reserve)
    monkeypatch.setattr(pos_router, "OrderPricingService", _Pricing)
    db = _Session(
        _Result(rows=[line]),
        _Result(rows=[item]),
        _Result(rows=[(gaming_session, station)]),
        _Result(),
        _Result(),
    )

    await pos_router._reprice_unpaid_order_for_customer(
        db,
        order=order,
        company_id=company_id,
    )

    assert requested["gaming_minutes"] == 0
    assert line.line_total_minor == 15_000
    assert line.discount_minor == 0
    assert order.total_minor == 15_000


def test_cancel_reason_is_trimmed_and_whitespace_only_is_rejected() -> None:
    assert gaming_router.SessionCancel(reason="  Customer left  ").reason == "Customer left"
    with pytest.raises(PydanticValidationError):
        gaming_router.SessionCancel(reason="   \t  ")


def test_shift_close_count_schema_rejects_negative_but_preserves_zero() -> None:
    with pytest.raises(PydanticValidationError):
        pos_router.ShiftCloseRequest(counted_minor=-1)

    assert pos_router.ShiftCloseRequest(counted_minor=0).counted_minor == 0


@pytest.mark.asyncio
async def test_close_shift_replay_allows_another_authorized_staff_member() -> None:
    tenant = _tenant()
    closed = _shift(
        tenant,
        status="closed",
        counted_minor=4_900,
        variance_minor=-100,
        closed_by=tenant.user_id,
        closed_at=datetime.now(UTC),
    )

    replay = await pos_router.close_shift(
        closed.id,
        pos_router.ShiftCloseRequest(counted_minor=4_900),
        _Session(_Result(scalar=closed)),
        tenant,
    )
    expected = {
        "id": str(closed.id),
        "status": "closed",
        "variance_minor": -100,
        "opened_by": str(closed.opened_by),
        "closed_by": str(closed.closed_by),
        "closed_by_was_opener": True,
    }
    assert replay == expected

    with pytest.raises(BusinessRuleError, match="different cash count"):
        await pos_router.close_shift(
            closed.id,
            pos_router.ShiftCloseRequest(counted_minor=4_800),
            _Session(_Result(scalar=closed)),
            tenant,
        )

    other_staff = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
    )
    replay_by_colleague = await pos_router.close_shift(
        closed.id,
        pos_router.ShiftCloseRequest(counted_minor=4_900),
        _Session(_Result(scalar=closed)),
        other_staff,
    )
    assert replay_by_colleague == expected
    assert closed.closed_by == tenant.user_id


@pytest.mark.asyncio
async def test_close_shift_replay_rejects_missing_saved_count_even_when_payload_is_zero() -> None:
    tenant = _tenant()
    closed_at = datetime.now(UTC)
    closed = _shift(
        tenant,
        status="closed",
        counted_minor=None,
        variance_minor=None,
        closed_at=closed_at,
    )

    with pytest.raises(BusinessRuleError, match="saved cash count is missing"):
        await pos_router.close_shift(
            closed.id,
            pos_router.ShiftCloseRequest(counted_minor=0),
            _Session(_Result(scalar=closed)),
            tenant,
        )

    assert closed.status == "closed"
    assert closed.closed_at == closed_at
    assert closed.counted_minor is None
    assert closed.variance_minor is None


@pytest.mark.asyncio
async def test_close_shift_blocks_stopped_sessions_not_sent_to_pos() -> None:
    tenant = _tenant()
    shift = _shift(tenant)
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=0),  # no unacknowledged kitchen cancellation
        _Result(scalar=0),  # running sessions
        _Result(scalar=1),  # stopped, unbilled sessions
        entities={
            (User, shift.opened_by): SimpleNamespace(
                company_id=shift.company_id,
                name="Rafi",
            ),
            (Branch, shift.branch_id): SimpleNamespace(
                company_id=shift.company_id,
                name="Main Shop",
            ),
            (Terminal, shift.terminal_id): SimpleNamespace(
                branch_id=shift.branch_id,
                name="Gaming Centre",
            ),
        },
    )

    with pytest.raises(
        BusinessRuleError,
        match="1 stopped gaming session.*not been sent to POS.*Rafi.*Gaming Centre",
    ) as raised:
        await pos_router.close_shift(
            shift.id,
            pos_router.ShiftCloseRequest(counted_minor=5_000),
            session,
            tenant,
        )
    assert raised.value.details == {
        "issue": "unbilled_gaming_sessions",
        "shift_id": str(shift.id),
        "shift_status": "open",
        "opened_by_name": "Rafi",
        "opened_at": shift.opened_at.isoformat(),
        "opened_at_display": shift.opened_at.strftime("%d %b %Y at %H:%M UTC"),
        "branch_name": "Main Shop",
        "workspace_name": "Gaming Centre",
        "next_action": (
            "Open Gaming, send every payment-due session to POS and complete or "
            "properly void its bill, then close the shift again."
        ),
        "blocker_count": 1,
    }
    assert shift.status == "open"
    assert shift.closed_at is None


@pytest.mark.asyncio
async def test_close_shift_blocks_unacknowledged_kitchen_cancellation() -> None:
    tenant = _tenant()
    shift = _shift(tenant)
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=1),  # released cancellation still waiting on KDS
    )

    with pytest.raises(
        BusinessRuleError,
        match="1 kitchen cancellation.*Open KDS.*acknowledge",
    ):
        await pos_router.close_shift(
            shift.id,
            pos_router.ShiftCloseRequest(counted_minor=5_000),
            session,
            tenant,
        )
    assert shift.status == "open"
    assert shift.closed_at is None


@pytest.mark.asyncio
async def test_shift_summary_keeps_pos_and_membership_receipts_explicit() -> None:
    """The QA fixture is Rs836 POS + Rs1,999 membership = Rs2,835 gross.

    A membership refund is separately visible and must not silently rewrite
    the immutable gross-receipt columns into something labelled as sales.
    """
    tenant = _tenant()
    shift = _shift(tenant, expected_minor=5_000)
    session = _Session(
        _Result(
            rows=[
                (
                    shift,
                    "QA Owner",
                    "qa-owner@example.test",
                    None,
                    None,
                    83_600,
                    199_900,
                    93_600,
                    60_000,
                    100_000,
                    2_000,
                    1_000,
                )
            ]
        )
    )

    rows = await pos_router.list_shifts(session, tenant, only_open=True)

    assert len(rows) == 1
    statement_sql = str(
        session.statements[0].compile(dialect=postgresql.dialect())
    )
    assert "FROM refunds" in statement_sql
    assert "refunds.settlement_shift_id = shifts.id" in statement_sql
    assert "FROM membership_refund_settlements" in statement_sql
    assert "membership_refund_settlements.shift_id = shifts.id" in statement_sql
    assert "FROM pos_refund_requests" not in statement_sql
    assert "FROM membership_refunds " not in statement_sql

    summary = rows[0]
    assert summary.pos_sales_minor == 83_600
    assert summary.membership_sales_minor == 199_900
    assert summary.gross_collections_minor == 283_500
    assert summary.cash_collections_minor == 93_600
    assert summary.card_collections_minor == 60_000
    assert summary.upi_collections_minor == 100_000
    assert summary.other_collections_minor == 29_900
    assert summary.total_sales_minor == 283_500  # compatibility alias only
    assert summary.settled_pos_refunds_minor == 2_000
    assert summary.settled_membership_refunds_minor == 1_000
    assert summary.total_refunds_minor == 3_000
    assert summary.net_collections_minor == 280_500
    assert summary.expected_minor == 5_000  # UPI receipts never alter drawer cash
    assert summary.opened_by_name == "QA Owner"
    assert summary.closed_by is None
    assert summary.closed_by_name is None
    assert summary.closed_by_email is None

    # Exercise the API serialization boundary too.  A model-level assertion
    # alone would not catch an accidentally optional/excluded response field,
    # which is exactly how a mobile client can end up rendering misleading
    # zero defaults even though the server calculated the right numbers.
    payload = summary.model_dump(mode="json")
    assert {
        key: payload[key]
        for key in (
            "pos_sales_minor",
            "membership_sales_minor",
            "gross_collections_minor",
            "cash_collections_minor",
            "card_collections_minor",
            "upi_collections_minor",
            "other_collections_minor",
            "settled_pos_refunds_minor",
            "settled_membership_refunds_minor",
            "total_refunds_minor",
            "net_collections_minor",
            "total_sales_minor",
        )
    } == {
        "pos_sales_minor": 83_600,
        "membership_sales_minor": 199_900,
        "gross_collections_minor": 283_500,
        "cash_collections_minor": 93_600,
        "card_collections_minor": 60_000,
        "upi_collections_minor": 100_000,
        "other_collections_minor": 29_900,
        "settled_pos_refunds_minor": 2_000,
        "settled_membership_refunds_minor": 1_000,
        "total_refunds_minor": 3_000,
        "net_collections_minor": 280_500,
        "total_sales_minor": 283_500,
    }


@pytest.mark.asyncio
async def test_close_shift_blocks_unresolved_membership_payment() -> None:
    tenant = _tenant()
    shift = _shift(tenant)
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=0),  # no unacknowledged kitchen cancellation
        _Result(scalar=0),  # running gaming sessions
        _Result(scalar=0),  # stopped, unbilled gaming sessions
        _Result(scalar=1),  # accepted membership payment still unresolved
    )

    with pytest.raises(
        BusinessRuleError,
        match="accepted membership payment task.*cash or provider collection",
    ):
        await pos_router.close_shift(
            shift.id,
            pos_router.ShiftCloseRequest(counted_minor=5_000),
            session,
            tenant,
        )

    assert shift.status == "open"
    assert shift.closed_at is None


@pytest.mark.asyncio
async def test_close_shift_blocks_unresolved_membership_refund_on_any_rail() -> None:
    tenant = _tenant()
    shift = _shift(tenant)
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=0),  # no unacknowledged kitchen cancellation
        _Result(scalar=0),  # running gaming sessions
        _Result(scalar=0),  # stopped, unbilled gaming sessions
        _Result(scalar=0),  # membership payments resolved
        _Result(rows=[]),  # no unresolved saved refund recovery
        _Result(scalar=1),  # membership cash/provider refund unresolved
    )

    with pytest.raises(
        BusinessRuleError,
        match="accepted membership refund task.*cash handover or provider payout",
    ):
        await pos_router.close_shift(
            shift.id,
            pos_router.ShiftCloseRequest(counted_minor=5_000),
            session,
            tenant,
        )

    assert shift.status == "open"
    assert shift.closed_at is None


@pytest.mark.asyncio
async def test_close_shift_blocks_only_scoped_unresolved_membership_refund_recovery() -> None:
    tenant = _tenant()
    shift = _shift(tenant)
    recovery_id = uuid4()
    later_recovery_id = uuid4()
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=0),  # no unacknowledged kitchen cancellation
        _Result(scalar=0),  # running gaming sessions
        _Result(scalar=0),  # stopped, unbilled gaming sessions
        _Result(scalar=0),  # membership payments resolved
        _Result(
            rows=[recovery_id, later_recovery_id]
        ),  # exact scoped recoveries remain unresolved
    )

    with pytest.raises(
        BusinessRuleError,
        match=rf"because 2 saved membership refund recovery.*{recovery_id}",
    ):
        await pos_router.close_shift(
            shift.id,
            pos_router.ShiftCloseRequest(counted_minor=5_000),
            session,
            tenant,
        )

    recovery_query = session.statements[-1]
    compiled = recovery_query.compile(dialect=postgresql.dialect())
    statement = str(compiled)
    for scoped_column in (
        "membership_refund_attempt_recoveries.company_id",
        "membership_refund_attempt_recoveries.source_branch_id",
        "membership_refund_attempt_recoveries.source_terminal_id",
        "membership_refund_attempt_recoveries.source_shift_id",
        "membership_refund_attempt_resolutions.id IS NULL",
    ):
        assert scoped_column in statement
    assert "FOR UPDATE OF membership_refund_attempt_recoveries" in statement
    assert shift.id in compiled.params.values()
    assert shift.company_id in compiled.params.values()
    assert shift.branch_id in compiled.params.values()
    assert shift.terminal_id in compiled.params.values()
    assert shift.status == "open"
    assert shift.closed_at is None


@pytest.mark.asyncio
async def test_close_shift_succeeds_only_after_all_financial_tasks_resolve() -> None:
    tenant = _tenant()
    opener_id = uuid4()
    shift = _shift(tenant, opened_by=opener_id)
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=0),  # no unacknowledged kitchen cancellation
        _Result(scalar=0),  # running gaming sessions
        _Result(scalar=0),  # stopped, unbilled gaming sessions
        _Result(scalar=0),  # membership payments resolved
        _Result(rows=[]),  # saved membership refund recoveries resolved
        _Result(scalar=0),  # membership refunds resolved
        _Result(scalar=0),  # POS refunds resolved
    )

    response = await pos_router.close_shift(
        shift.id,
        pos_router.ShiftCloseRequest(counted_minor=4_900),
        session,
        tenant,
    )

    assert response == {
        "id": str(shift.id),
        "status": "closed",
        "variance_minor": -100,
        "opened_by": str(opener_id),
        "closed_by": str(tenant.user_id),
        "closed_by_was_opener": False,
    }
    assert shift.closed_at is not None
    assert shift.closed_by == tenant.user_id


@pytest.mark.asyncio
async def test_pos_refund_evidence_reconciliation_requires_protected_owner() -> None:
    tenant = _tenant(protected_access=False)
    payload = pos_router.PosRefundEvidenceReconciliationCreate(
        refund_id=uuid4(),
        evidence_kind="provider_reference",
        proof_reference="Provider case 123",
        reason="Verified against the provider dashboard",
    )

    with pytest.raises(ForbiddenError, match="Only a protected owner"):
        await pos_router.reconcile_pos_refund_evidence(
            payload,
            _Session(),
            SimpleNamespace(),
            tenant,
        )


@pytest.mark.asyncio
async def test_legacy_one_call_refund_is_rejected_before_authorization_or_money_writes(
    monkeypatch,
) -> None:
    tenant = _tenant()
    original_shift = _shift(
        tenant,
        opened_by=uuid4(),
        status="closed",
        closed_at=datetime.now(UTC),
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=original_shift.id,
        status="paid",
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="refund-accountability-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=original_shift),
    )

    with pytest.raises(BusinessRuleError, match="older app.*No refund or drawer"):
        await pos_router.issue_refund(
            order.id,
            pos_router.RefundCreate(
                reason_code="CUSTOMER_REQUEST",
                amount_minor=1_000,
                mode="original",
            ),
            session,
            request,
            tenant,
        )

    assert session.statements == []
    assert session.added == []


@pytest.mark.asyncio
async def test_protected_owner_can_refund_original_non_cash_on_closed_shift(
    monkeypatch,
) -> None:
    tenant = _tenant(protected_access=True)
    original_shift = _shift(
        tenant,
        opened_by=uuid4(),
        status="closed",
        closed_at=datetime.now(UTC),
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=original_shift.id,
        status="paid",
        total_minor=5_000,
        tip_minor=0,
    )
    payment = SimpleNamespace(
        amount_minor=5_000,
        method="upi",
        paid_at=datetime.now(UTC),
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    async def _store_response(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="protected-owner-refund-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=original_shift),
        _Result(rows=[payment]),
        _Result(scalar=0),
        _Result(rows=[]),  # sale StockMovements fetched for refund-restock; none found
    )

    with pytest.raises(BusinessRuleError, match="older app.*No refund or drawer"):
        await pos_router.issue_refund(
            order.id,
            pos_router.RefundCreate(
                reason_code="CUSTOMER_REQUEST",
                amount_minor=5_000,
                mode="original",
            ),
            session,
            request,
            tenant,
        )
    assert session.statements == []
    assert session.added == []
    assert order.status == "paid"


@pytest.mark.asyncio
async def test_cash_refund_still_requires_a_current_open_drawer_shift(
    monkeypatch,
) -> None:
    tenant = _tenant()
    original_shift = _shift(
        tenant,
        status="closed",
        closed_at=datetime.now(UTC),
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=original_shift.id,
        status="paid",
    )
    payment = SimpleNamespace(
        amount_minor=5_000,
        method="upi",
        paid_at=datetime.now(UTC),
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="cash-refund-open-drawer-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=original_shift),
        _Result(rows=[payment]),
        _Result(scalar=0),
        _Result(rows=[]),
    )

    with pytest.raises(BusinessRuleError, match="older app.*No refund or drawer"):
        await pos_router.issue_refund(
            order.id,
            pos_router.RefundCreate(
                reason_code="CUSTOMER_REQUEST",
                amount_minor=1_000,
                mode="cash",
            ),
            session,
            request,
            tenant,
        )


@pytest.mark.asyncio
async def test_session_send_uses_the_sessions_original_shift(monkeypatch) -> None:
    tenant = _tenant()
    station = _station(tenant)
    original_shift = _shift(tenant)
    gaming_session = _gaming_session(tenant, station.id, original_shift.id)
    menu_item = SimpleNamespace(
        id=uuid4(),
        name="Gaming session",
        type="gaming",
        hsn_code="999692",
    )
    terminal = SimpleNamespace(
        id=tenant.terminal_id,
        branch_id=tenant.branch_id,
        purpose="hybrid",
    )
    session = _Session(
        _Result(scalar=gaming_session),
        _Result(scalar=original_shift),
        _Result(rows=[]),  # no staged Gaming add-ons to copy into this bill
        entities={
            (Station, station.id): station,
            (Terminal, terminal.id): terminal,
        },
    )

    async def _menu_item(_session, *, company_id, station):
        assert company_id == tenant.company_id
        assert station.id == gaming_session.station_id
        return menu_item

    class _Pricing:
        def __init__(self, _session) -> None:
            pass

        async def price_time_based_line(self, **kwargs):
            assert kwargs["branch_id"] == original_shift.branch_id
            assert kwargs["amount_minor"] == gaming_session.amount_minor
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

    async def _reprice_membership(_session, *, order, company_id):
        assert order.customer_phone == gaming_session.customer_phone
        assert company_id == tenant.company_id

    monkeypatch.setattr(
        gaming_router,
        "_reprice_session_order_for_customer",
        _reprice_membership,
    )

    response = await gaming_router.send_session_to_pos(
        gaming_session.id,
        session,
        tenant,
    )

    created_order = next(entity for entity in session.added if isinstance(entity, Order))
    created_line = next(entity for entity in session.added if isinstance(entity, OrderLine))
    assert response == {"order_id": str(created_order.id), "amount_minor": 15_700}
    assert created_order.shift_id == gaming_session.shift_id == original_shift.id
    assert created_order.branch_id == original_shift.branch_id
    assert created_order.terminal_id == original_shift.terminal_id
    assert created_order.held_at is not None
    assert created_order.total_minor == 15_700
    assert created_order.round_off_minor == 33
    assert created_order.discount_minor == 0
    assert gaming_session.order_id == created_order.id
    assert created_line.note == "47 min @ 200.00/hr"
    assert gaming_session.shift_id in session.statements[1].compile().params.values()


@pytest.mark.asyncio
async def test_session_send_retry_returns_the_existing_order_without_creating_another() -> None:
    tenant = _tenant()
    station = _station(tenant)
    order_id = uuid4()
    gaming_session = _gaming_session(tenant, station.id, uuid4(), order_id=order_id)
    existing_order = SimpleNamespace(
        id=order_id,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        total_minor=15_667,
    )
    session = _Session(
        _Result(scalar=gaming_session),
        entities={(Station, station.id): station, (Order, order_id): existing_order},
    )

    response = await gaming_router.send_session_to_pos(gaming_session.id, session, tenant)

    assert response == {"order_id": str(order_id), "amount_minor": 15_667}
    assert session.added == []
    assert session.flush_count == 0
    assert len(session.statements) == 1


@pytest.mark.asyncio
async def test_cancel_session_records_trace_and_retry_preserves_the_original_reason() -> None:
    tenant = _tenant()
    station = _station(tenant)
    shift = _shift(tenant)
    gaming_session = _gaming_session(tenant, station.id, shift.id)
    first_session = _Session(
        _Result(scalar=gaming_session),
        _Result(scalar=shift),
        _Result(scalar=0),  # no active add-ons block whole-session cancellation
        entities={(Station, station.id): station},
    )

    first = await gaming_router.cancel_session(
        gaming_session.id,
        gaming_router.SessionCancel(reason="  Customer left before billing  "),
        first_session,
        tenant,
    )

    assert first.status == "cancelled"
    assert first.cancel_reason == "Customer left before billing"
    assert gaming_session.cancelled_by == tenant.user_id
    assert gaming_session.cancelled_at is not None
    assert gaming_session.end_at is not None
    assert gaming_session.billable_minutes == 0
    assert gaming_session.amount_minor == 0
    original_cancelled_at = gaming_session.cancelled_at

    # The original opener can safely recover a lost response even after the
    # exact shift has closed; scope and opener checks still run first.
    shift.status = "closed"
    retry_session = _Session(
        _Result(scalar=gaming_session),
        _Result(scalar=shift),
        entities={(Station, station.id): station},
    )
    retry = await gaming_router.cancel_session(
        gaming_session.id,
        gaming_router.SessionCancel(reason="Different retry body"),
        retry_session,
        tenant,
    )
    assert retry.cancel_reason == "Customer left before billing"
    assert gaming_session.cancelled_at == original_cancelled_at
    assert retry_session.flush_count == 0

    wrong_terminal = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=uuid4(),
        user_id=tenant.user_id,
    )
    with pytest.raises(BusinessRuleError, match="different terminal"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Retry from wrong drawer"),
            _Session(
                _Result(scalar=gaming_session),
                _Result(scalar=shift),
                _Result(scalar=0),
                entities={(Station, station.id): station},
            ),
            wrong_terminal,
        )

    # Cancelling removes a billable session, so ordinary staff on the same
    # terminal still need the accountable shift opener to perform it.
    gaming_session.status = "ended"
    shift.status = "open"
    other_staff = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
    )
    with pytest.raises(BusinessRuleError, match="Only the staff member who opened this shift"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Waive this charge"),
            _Session(
                _Result(scalar=gaming_session),
                _Result(scalar=shift),
                _Result(scalar=0),
                entities={(Station, station.id): station},
            ),
            other_staff,
        )

    gaming_session.status = "active"
    shift.status = "closed"
    protected_owner = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        protected_access=True,
    )
    with pytest.raises(BusinessRuleError, match="only cancel a legacy ended session"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Invalid closed-shift cleanup"),
            _Session(
                _Result(scalar=gaming_session),
                _Result(scalar=shift),
                _Result(scalar=0),
                entities={(Station, station.id): station},
            ),
            protected_owner,
        )


@pytest.mark.asyncio
async def test_start_session_replay_returns_stored_response_without_recreating() -> None:
    """A dropped-response retry of session start must replay the stored
    response, not re-run the "station already has an active session" check
    against the session it itself just created — that false rejection is
    exactly what the idempotency wiring in start_session fixes. Only the
    idempotency-key lookup should touch the database on a replay; no second
    GamingSession may be added.
    """
    tenant = _tenant()
    station = _station(tenant)
    shift = _shift(tenant)
    stored_body = {
        "id": str(uuid4()),
        "station_id": str(station.id),
        "status": "active",
        "start_at": datetime.now(UTC).isoformat(),
        "end_at": None,
        "timer_minutes": None,
        "timer_ends_at": None,
        "billable_minutes": None,
        "amount_minor": None,
        "customer_name": None,
        "customer_phone": None,
        "rate_per_hour_minor": 20_000,
        "order_id": None,
        "cancel_reason": None,
        "package_id": None,
        "extra_controllers": 0,
    }
    stored_key_row = SimpleNamespace(
        request_hash="same-hash",
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        response_status=201,
        response_body=stored_body,
    )
    session = _Session(_Result(scalar=stored_key_row))
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="session-start-retry-key",
            idempotency_request_hash="same-hash",
        )
    )

    response = await gaming_router.start_session(
        gaming_router.SessionStart(
            station_id=station.id,
            shift_id=shift.id,
            expected_rate_per_hour_minor=20_000,
        ),
        session,
        request,
        tenant,
    )

    assert response.id == UUID(stored_body["id"])
    assert response.status == "active"
    assert session.added == []
    assert len(session.statements) == 1


@pytest.mark.asyncio
async def test_order_detail_returns_held_timestamp_and_line_preparation_note() -> None:
    table_id = uuid4()
    held_at = datetime(2026, 7, 15, 11, 30, tzinfo=UTC)
    order = SimpleNamespace(
        id=uuid4(),
        invoice_no=None,
        fiscal_year=None,
        status="held",
        type="dine_in",
        table_id=table_id,
        subtotal_minor=1_000,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        cgst_minor=25,
        sgst_minor=25,
        igst_minor=0,
        cess_minor=0,
        tax_minor=50,
        round_off_minor=0,
        tip_minor=0,
        total_minor=1_050,
        delivery_via=None,
        place_of_supply_state_code="32",
        customer_name=None,
        customer_phone=None,
        customer_gstin=None,
        customer_state_code=None,
        opened_at=datetime(2026, 7, 15, 11, tzinfo=UTC),
        closed_at=None,
        invoice_issued_at=None,
        held_at=held_at,
    )
    line = SimpleNamespace(
        id=uuid4(),
        menu_item_id=uuid4(),
        menu_item_name_snapshot="Sandwich",
        menu_item_type_snapshot="food",
        variant_id=None,
        modifiers=None,
        qty=1,
        unit_price_minor=1_050,
        line_total_minor=1_050,
        taxable_value_minor=1_000,
        tax_rate=0.05,
        cgst_minor=25,
        sgst_minor=25,
        igst_minor=0,
        note="No onions, extra spicy",
        hsn_or_sac="996331",
        kitchen_status="queued",
        voided_at=None,
        voided_by=None,
    )
    item = SimpleNamespace(name="Sandwich", sku="FOOD-1", hsn_code="996331")
    session = _Session(
        _Result(rows=[(line, item)]),
        _Result(scalar=0),
        _Result(rows=[]),
        _Result(),  # points redemption lookup (none)
        entities={(Table, table_id): SimpleNamespace(code="T1")},
    )

    response = await pos_router._build_order_read(session, order)

    assert response.held_at == held_at
    assert response.paid_minor == 0
    assert response.due_minor == 1_050
    assert response.source_label == "Table T1"
    assert response.lines[0].note == "No onions, extra spicy"


@pytest.mark.asyncio
async def test_held_order_list_returns_authoritative_held_timestamp() -> None:
    tenant = _tenant()
    held_at = datetime(2026, 7, 15, 11, 30, tzinfo=UTC)
    order = SimpleNamespace(
        id=uuid4(),
        invoice_no=None,
        type="session",
        status="held",
        table_id=None,
        total_minor=15_667,
        customer_name="Cafe Guest",
        created_at=datetime(2026, 7, 15, 10, tzinfo=UTC),
        held_at=held_at,
        checkout_version=7,
    )
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(order.id, 1)]),
        _Result(rows=[(order.id, "PS5 1")]),
        _Result(rows=[]),  # payment rows — nothing paid on a held order
        _Result(rows=[]),  # refunded_by_order
        _Result(rows=[]),  # accepted, unresolved refund requests
    )

    response = await pos_router.list_orders(
        session,
        tenant,
        status_filter=["held"],
    )

    assert response[0].held_at == held_at
    assert response[0].checkout_version == 7
    assert response[0].source_label == "PS5 1"
    assert response[0].paid_minor == 0
    assert response[0].refundable_minor == 0
    assert response[0].payment_methods == []
    order_params = session.statements[0].compile().params.values()
    assert tenant.company_id in order_params
    assert tenant.branch_id in order_params
    assert tenant.terminal_id in order_params


@pytest.mark.asyncio
async def test_order_list_computes_refundable_balance_net_of_prior_refunds() -> None:
    """The Refunds screen trusts refundable_minor as its ceiling — it must be
    paid minus every refund already issued, never the raw paid figure, or a
    second partial refund could be entered for more than is actually left."""
    tenant = _tenant()
    order = SimpleNamespace(
        id=uuid4(),
        invoice_no="D/MN/26-27/00099",
        type="dine_in",
        status="paid",
        table_id=None,
        total_minor=1_000,
        customer_name=None,
        created_at=datetime(2026, 7, 15, 10, tzinfo=UTC),
        held_at=None,
        checkout_version=3,
    )
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(order.id, 3)]),  # counts_by_order
        _Result(rows=[]),  # station_by_order
        _Result(
            rows=[
                (order.id, "upi", 600),
                (order.id, "cash", 400),
            ]
        ),  # legacy mixed payment rows; response order is canonical
        _Result(rows=[(order.id, 400)]),  # refunded_by_order — one prior partial refund
        _Result(rows=[]),  # accepted, unresolved refund requests
    )

    response = await pos_router.list_orders(session, tenant, status_filter=["paid"])

    assert response[0].checkout_version == 3
    assert response[0].paid_minor == 1_000
    assert response[0].refundable_minor == 600
    assert response[0].payment_methods == ["cash", "upi"]

"""Database-free contract tests for reservation-first membership collection.

Real PostgreSQL tests cover concurrent transitions and database triggers. These
tests keep permission, provenance, exact-shift, price-snapshot, and idempotency
rules cheap to run in every unit suite.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.api.v1.memberships.router as memberships_router
from app.api.v1.memberships.router import (
    MembershipPaymentRequestCreate,
    MembershipPaymentRequestRead,
    prepare_membership_payment,
)
from app.core.errors import BusinessRuleError, ForbiddenError
from app.core.tenant import TenantContext
from app.models import MembershipPayment, MembershipPaymentRequest

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("12111111-1111-1111-1111-111111111111")
TERMINAL_ID = UUID("13111111-1111-1111-1111-111111111111")
SHIFT_ID = UUID("14111111-1111-1111-1111-111111111111")
CUSTOMER_ID = UUID("22222222-2222-2222-2222-222222222222")
TIER_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")
NOW = datetime.now(UTC).replace(microsecond=0)
ACTION_ID = "membership-prepare:test-action"


def _tenant(*, protected_access: bool = True) -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        roles=("owner",),
        protected_access=protected_access,
    )


def _request(
    *,
    key: str | None = ACTION_ID,
    action_id: str | None = ACTION_ID,
) -> SimpleNamespace:
    state = SimpleNamespace()
    if key is not None:
        state.idempotency_key = key
        state.idempotency_request_hash = "request-hash"
    headers = {"X-Client-Action-Id": action_id} if action_id is not None else {}
    return SimpleNamespace(state=state, headers=headers)


def _payload(**overrides) -> MembershipPaymentRequestCreate:
    fields = {
        "customer_id": CUSTOMER_ID,
        "tier_id": TIER_ID,
        "shift_id": SHIFT_ID,
        "expected_amount_minor": 199_900,
        "billing_cycle": "monthly",
        "paid_via": "upi",
        "client_action_id": ACTION_ID,
        **overrides,
    }
    return MembershipPaymentRequestCreate(**fields)


def _shift(*, status: str = "open") -> SimpleNamespace:
    return SimpleNamespace(
        id=SHIFT_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        opened_by=USER_ID,
        opened_at=NOW - timedelta(hours=1),
        status=status,
        expected_minor=10_000,
    )


def _customer() -> SimpleNamespace:
    return SimpleNamespace(
        id=CUSTOMER_ID,
        company_id=COMPANY_ID,
        deleted_at=None,
        total_spent_minor=500,
        name="QA Member",
        phone="9999999999",
    )


def _tier() -> SimpleNamespace:
    return SimpleNamespace(
        id=TIER_ID,
        company_id=COMPANY_ID,
        deleted_at=None,
        code="GOLD",
        name="D Club Gold",
        monthly_price_minor=199_900,
        annual_price_minor=1_999_000,
    )


class _Result:
    def __init__(self, value=None) -> None:
        self.value = value

    def scalar_one_or_none(self):
        return self.value

    def scalar_one(self):
        return self.value


class _Session:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.added: list[object] = []
        self.flushes = 0

    async def execute(self, statement):
        assert self.results, f"unexpected SQL: {statement}"
        return self.results.pop(0)

    def add(self, row) -> None:
        self.added.append(row)

    async def flush(self) -> None:
        self.flushes += 1


def _read(row: MembershipPaymentRequest) -> MembershipPaymentRequestRead:
    return MembershipPaymentRequestRead(
        id=row.id,
        customer_id=row.customer_id,
        tier_id=row.tier_id,
        shift_id=row.shift_id,
        billing_cycle=row.billing_cycle,
        paid_via=row.method,
        amount_minor=row.amount_minor,
        customer_name=row.customer_name_snapshot,
        customer_phone=row.customer_phone_snapshot,
        tier_code=row.tier_code_snapshot,
        tier_name=row.tier_name_snapshot,
        status="accepted_payment_due",
        accepted_at=row.accepted_at,
        prepared_by=row.prepared_by,
        client_action_id=row.client_action_id,
    )


@pytest.mark.asyncio
async def test_prepare_requires_protected_owner_before_database_work() -> None:
    session = _Session([])
    with pytest.raises(ForbiddenError, match="protected owner"):
        await prepare_membership_payment(
            _payload(), session, _request(), _tenant(protected_access=False)
        )
    assert session.added == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("request_obj", "match"),
    [
        (_request(key=None), "Idempotency-Key"),
        (_request(action_id=None), "audit provenance"),
        (_request(key="different-action"), "must match"),
    ],
)
async def test_prepare_requires_matching_durable_action_provenance(
    request_obj: SimpleNamespace,
    match: str,
) -> None:
    session = _Session([])
    with pytest.raises(BusinessRuleError, match=match):
        await prepare_membership_payment(_payload(), session, request_obj, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_annual_membership_is_disabled_on_receipt_basis() -> None:
    with pytest.raises(BusinessRuleError, match="Annual memberships are unavailable"):
        await prepare_membership_payment(
            _payload(billing_cycle="annual", expected_amount_minor=1_999_000),
            _Session([]),
            _request(),
            _tenant(),
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["cash", "upi", "card", "razorpay"])
async def test_prepare_snapshots_terms_without_posting_money(
    monkeypatch,
    method: str,
) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    async def response(_session, row):
        return _read(row)

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)
    monkeypatch.setattr(memberships_router, "store_response", store)
    monkeypatch.setattr(
        memberships_router, "_membership_payment_request_to_read", response
    )

    shift = _shift()
    customer = _customer()
    session = _Session(
        [
            _Result(shift),
            _Result(None),
            _Result(None),
            _Result(customer),
            _Result(None),
            _Result(None),
            _Result(None),
            _Result(_tier()),
        ]
    )

    result = await prepare_membership_payment(
        _payload(paid_via=method), session, _request(), _tenant()
    )

    requests = [row for row in session.added if isinstance(row, MembershipPaymentRequest)]
    payments = [row for row in session.added if isinstance(row, MembershipPayment)]
    assert len(requests) == 1
    assert payments == []
    prepared = requests[0]
    assert prepared.shift_id == SHIFT_ID
    assert prepared.terminal_id == TERMINAL_ID
    assert prepared.amount_minor == 199_900
    assert prepared.method == method
    assert prepared.customer_name_snapshot == "QA Member"
    assert prepared.tier_name_snapshot == "D Club Gold"
    assert shift.expected_minor == 10_000
    assert customer.total_spent_minor == 500
    assert result.status == "accepted_payment_due"
    assert session.flushes == 1


@pytest.mark.asyncio
async def test_prepare_rejects_exact_shift_mismatch(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)
    wrong = _shift()
    wrong.terminal_id = uuid4()
    with pytest.raises(BusinessRuleError, match="terminal"):
        await prepare_membership_payment(
            _payload(), _Session([_Result(wrong)]), _request(), _tenant()
        )


@pytest.mark.asyncio
async def test_prepare_rejects_stale_price_snapshot(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)
    session = _Session(
        [
            _Result(_shift()),
            _Result(None),
            _Result(None),
            _Result(_customer()),
            _Result(None),
            _Result(None),
            _Result(None),
            _Result(_tier()),
        ]
    )
    with pytest.raises(BusinessRuleError, match="price changed"):
        await prepare_membership_payment(
            _payload(expected_amount_minor=199_800), session, _request(), _tenant()
        )
    assert session.added == []


@pytest.mark.asyncio
async def test_prepare_exact_replay_creates_no_second_fact(monkeypatch) -> None:
    existing = _read(
        MembershipPaymentRequest(
            id=uuid4(),
            company_id=COMPANY_ID,
            customer_id=CUSTOMER_ID,
            tier_id=TIER_ID,
            branch_id=BRANCH_ID,
            terminal_id=TERMINAL_ID,
            shift_id=SHIFT_ID,
            billing_cycle="monthly",
            method="upi",
            amount_minor=199_900,
            customer_name_snapshot="QA Member",
            customer_phone_snapshot="9999999999",
            tier_code_snapshot="GOLD",
            tier_name_snapshot="D Club Gold",
            accepted_at=NOW,
            prepared_by=USER_ID,
            client_action_id=ACTION_ID,
            idempotency_key=ACTION_ID,
        )
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(memberships_router, "check_or_reserve", replay)
    session = _Session([])
    assert (
        await prepare_membership_payment(_payload(), session, _request(), _tenant())
        == existing
    )
    assert session.added == []

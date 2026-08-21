"""Database-free regression for the concurrent-create race on POST /customers.

Covers a real gap found while adding the native Android app's offline
Customers outbox: `upsert_customer` did a plain SELECT-then-INSERT with no
protection against two concurrent requests for the same brand-new phone
number both seeing "no existing customer" and both trying to insert. The
partial unique index (uq_customer_phone_per_company_live) would catch the
loser, but as a raw, unhandled IntegrityError — a 500, not the clean "return
the existing customer" an upsert is supposed to give. This became more than
a theoretical risk once an offline outbox exists: several tablets could
plausibly drain a queued "create this walk-in" write for the same new
customer at close to the same moment on reconnect.

Same database-free style as
`test_reservations_and_bookings.py::test_create_booking_converts_exclude_constraint_violation_to_conflict`
— a real race is awkward to force deterministically through the ASGI test
client (the two requests tend to serialize rather than actually interleave),
so the constraint violation is injected directly via a fake session's
`flush()`, and the assertions cover the actual recovery code path: rollback,
re-query, return the winner.

The fake session records every statement it's given (not just the queued
results) so a test can assert on the *content* of the re-query — a mock that
only cares about call order would stay green even if a future edit dropped
the tenant scope from that query, which is exactly the kind of regression
this recovery path exists to not have.
"""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from sqlalchemy.exc import IntegrityError, NoResultFound

from app.api.v1.customers import router as customers_router
from app.core.errors import ConflictError
from app.core.tenant import TenantContext
from app.models import Customer


class _Result:
    def __init__(self, *, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        # Mirrors real SQLAlchemy: raises on an empty result rather than
        # quietly returning None — a fake that always just returns
        # `self.scalar` (including when it's None) can't tell the difference
        # between "found the row" and "found nothing" for any code under
        # test that calls the raising variant on purpose.
        if self.scalar is None:
            raise NoResultFound("no row was found when one was required")
        return self.scalar


def _statement_params(statement) -> dict[str, object]:
    return dict(statement.compile().params)


class _Session:
    def __init__(self, *results: _Result, flush_error: Exception | None = None) -> None:
        self.results = list(results)
        self.statements = []
        self.flush_count = 0
        self.rollback_count = 0
        self.flush_error = flush_error

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    def add(self, _obj) -> None:
        pass

    async def flush(self) -> None:
        self.flush_count += 1
        if self.flush_error is not None:
            error, self.flush_error = self.flush_error, None
            raise error

    async def rollback(self) -> None:
        self.rollback_count += 1


def _tenant(*, company_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=company_id or uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


def _customer(tenant: TenantContext, **overrides) -> Customer:
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "name": "Race Winner",
        "phone": "+919876500000",
        "email": None,
        "birthday": None,
        "first_visit_at": None,
        "last_visit_at": None,
        "visit_count": 0,
        "total_spent_minor": 0,
        "loyalty_points": 0,
        "lifetime_gaming_points_earned": 0,
        "notes": None,
        "deleted_at": None,
        "created_at": datetime(2026, 1, 1, tzinfo=UTC),
        "updated_at": datetime(2026, 1, 1, tzinfo=UTC),
    }
    values.update(overrides)
    return Customer(**values)


@pytest.mark.asyncio
async def test_upsert_customer_heals_into_the_winner_on_a_concurrent_create_race() -> None:
    """Two terminals draining a queued 'create this new customer' write for
    the same brand-new phone at close to the same moment: the loser's
    request must return the winner's actual customer record, not a 500."""
    tenant = _tenant()
    phone = "+919876500000"
    winner = _customer(tenant, phone=phone, name="Whoever Won The Race")

    session = _Session(
        _Result(scalar=None),  # this request's own "does it already exist" check — sees nothing yet
        _Result(scalar=winner),  # post-IntegrityError re-query, finds the row the other request created
        flush_error=IntegrityError(
            "INSERT INTO customers ...", {}, Exception("uq_customer_phone_per_company_live"),
        ),
    )

    result = await customers_router.upsert_customer(
        customers_router.CustomerUpsert(phone=phone, name="This Request's Own Name"),
        session,
        tenant,
    )

    assert session.flush_count == 1
    assert session.rollback_count == 1
    # The response is the winner's real row — not a second, orphaned record,
    # and not this request's own (never persisted) name.
    assert result.id == winner.id
    assert result.name == "Whoever Won The Race"

    # The re-query itself is scoped to this exact tenant and phone — not just
    # "returns whatever was queued." A future edit that dropped the
    # company_id filter would still make this test pass on the assertions
    # above alone (the fake doesn't care what query produced a result it was
    # told to hand back), so check the actual statement content too.
    assert len(session.statements) == 2
    requery_params = _statement_params(session.statements[1])
    assert phone in requery_params.values()
    assert tenant.company_id in requery_params.values()

    # The ordinary, no-collision path (a customer already on file — the far
    # more common case this endpoint exists for) is exercised end-to-end
    # against a real database by test_customer_delete.py, which posts a
    # fresh customer as setup for every one of its tests — no need to
    # duplicate that here with a mock that would have to hand-simulate
    # SQLAlchemy's server-side column defaults to be meaningful.


@pytest.mark.asyncio
async def test_upsert_customer_raises_a_clean_conflict_if_the_winner_vanished_before_the_requery() -> None:
    """Vanishingly unlikely (the winner would have to be soft-deleted in the
    exact instant between the IntegrityError and the re-query), but the
    re-query's `scalar_one_or_none()` finding nothing must raise a clean,
    retryable error — not let a NoResultFound-shaped 500 through."""
    tenant = _tenant()
    phone = "+919876500000"

    session = _Session(
        _Result(scalar=None),  # own existence check — sees nothing
        _Result(scalar=None),  # re-query also finds nothing — the vanished-winner case
        flush_error=IntegrityError(
            "INSERT INTO customers ...", {}, Exception("uq_customer_phone_per_company_live"),
        ),
    )

    with pytest.raises(ConflictError):
        await customers_router.upsert_customer(
            customers_router.CustomerUpsert(phone=phone, name="Doesn't Matter"),
            session,
            tenant,
        )

    assert session.flush_count == 1
    assert session.rollback_count == 1

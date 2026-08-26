"""Unit contract for checkout-lease exclusion around held-bill edits."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.core.errors import CheckoutClaimConflictError
from app.services.pos.checkout_claims import guard_checkout_relevant_mutation


class _Result:
    def __init__(self, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar


class _Session:
    def __init__(self, claim=None) -> None:
        self.claim = claim
        self.statements = []
        self.deleted = []
        self.flush_count = 0

    async def execute(self, statement):
        self.statements.append(statement)
        return _Result(self.claim)

    async def delete(self, entity) -> None:
        self.deleted.append(entity)
        if self.claim is entity:
            self.claim = None

    async def flush(self) -> None:
        self.flush_count += 1


def _order(*, status: str = "held", version: int = 8):
    return SimpleNamespace(
        id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        status=status,
        total_minor=12_500,
        checkout_version=version,
        table_id=None,
        type="dine_in",
    )


def _claim(order, *, expires_at: datetime, version: int | None = None):
    return SimpleNamespace(
        id=uuid4(),
        order_id=order.id,
        company_id=order.company_id,
        branch_id=order.branch_id,
        terminal_id=order.terminal_id,
        claimed_by_user_id=uuid4(),
        expires_at=expires_at,
        order_total_minor=order.total_minor,
        order_version=order.checkout_version if version is None else version,
    )


@pytest.mark.asyncio
async def test_active_current_claim_blocks_checkout_relevant_mutation() -> None:
    now = datetime(2026, 8, 25, 12, tzinfo=UTC)
    order = _order()
    claim = _claim(order, expires_at=now + timedelta(minutes=5))
    session = _Session(claim)

    with pytest.raises(CheckoutClaimConflictError) as error:
        await guard_checkout_relevant_mutation(
            session,
            order=order,
            operation="change the discount on this order",
            now=now,
        )

    assert error.value.code == "checkout_claim_conflict"
    assert error.value.details == {
        "order_id": str(order.id),
        "expires_at": claim.expires_at.isoformat(),
        "release_or_wait": True,
    }
    assert "change the discount" in error.value.message
    assert session.deleted == []
    assert session.flush_count == 0
    assert "FOR UPDATE" in str(session.statements[0]).upper()


@pytest.mark.asyncio
async def test_expired_claim_is_cleaned_and_does_not_block_mutation() -> None:
    now = datetime(2026, 8, 25, 12, tzinfo=UTC)
    order = _order()
    claim = _claim(order, expires_at=now)
    session = _Session(claim)

    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="add items to this order",
        now=now,
    )

    assert session.deleted == [claim]
    assert session.claim is None
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_stale_claim_is_cleaned_and_does_not_block_mutation() -> None:
    now = datetime(2026, 8, 25, 12, tzinfo=UTC)
    order = _order(version=9)
    claim = _claim(
        order,
        expires_at=now + timedelta(minutes=5),
        version=order.checkout_version - 1,
    )
    session = _Session(claim)

    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="void this order",
        now=now,
    )

    assert session.deleted == [claim]
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_direct_open_pos_order_keeps_pre_checkout_mutation_flow() -> None:
    order = _order(status="open")
    session = _Session()

    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="add items to this order",
    )

    assert session.statements == []

"""Concurrency-safe checkout leases for shared POS orders.

Tables and Gaming can hand a bill to a queue visible on more than one device.
New clients atomically publish direct bills into that same queue before
checkout; legacy direct bills stay open for rolling compatibility. The order
row remains the serialization point: callers lock it first, then acquire or
validate the one claim row for that order in the same transaction.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import select

from app.core.config import get_settings
from app.core.errors import (
    CheckoutClaimConflictError,
    CheckoutClaimExpiredError,
    CheckoutClaimInvalidError,
    CheckoutClaimRequiredError,
    CheckoutClaimStaleError,
    CheckoutClaimUnavailableError,
)
from app.models import Order, OrderCheckoutClaim

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True, slots=True)
class CheckoutClaimGrant:
    claim: OrderCheckoutClaim
    token: str
    paid_minor: int
    reused: bool


def requires_checkout_claim(order: Order) -> bool:
    """Return whether the bill can be visible/owned outside one POS device.

    A table/session order is shared operational work even before somebody
    presses Send to POS.  Requiring a claim there deliberately makes payment
    fail closed until it is moved to ``held`` and can actually be claimed.
    """
    # Historical paid/void/refunded orders are reads, not live checkout work.
    # This also keeps a replay of an already-finalized zero bill compatible
    # when the caller uses a new idempotency key and no longer has its consumed
    # claim token.
    if order.status not in {"open", "held"}:
        return False
    return (
        order.status == "held"
        or getattr(order, "table_id", None) is not None
        or getattr(order, "type", None) == "session"
    )


def checkout_version(order: Order) -> int:
    # New in migration 0031.  The fallback keeps old in-memory test objects
    # and rolling application deploys safe while every existing row migrates.
    return max(1, int(getattr(order, "checkout_version", 1) or 1))


def _token_hash(token: str) -> str:
    if not isinstance(token, str) or not 20 <= len(token) <= 200:
        raise CheckoutClaimInvalidError(
            "Checkout claim is invalid. Select the held bill again."
        )
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _client_instance_hash(client_instance_id: UUID | None) -> str | None:
    if client_instance_id is None:
        return None
    return hashlib.sha256(str(client_instance_id).encode("ascii")).hexdigest()


def _new_token() -> str:
    # 32 random bytes (256 bits), URL/header safe.  Only its digest is stored.
    return secrets.token_urlsafe(32)


def _due(*, order: Order, paid_minor: int) -> int:
    return max(0, int(order.total_minor or 0) - max(0, int(paid_minor)))


def _claim_matches_snapshot(
    claim: OrderCheckoutClaim,
    *,
    order: Order,
    paid_minor: int,
) -> bool:
    return (
        claim.company_id == order.company_id
        and claim.branch_id == order.branch_id
        and claim.terminal_id == order.terminal_id
        and int(claim.order_total_minor) == int(order.total_minor or 0)
        and int(claim.due_minor) == _due(order=order, paid_minor=paid_minor)
        and int(claim.order_version) == checkout_version(order)
    )


def _claim_matches_order_snapshot(
    claim: OrderCheckoutClaim,
    *,
    order: Order,
) -> bool:
    """Compare the checkout-relevant order fields available without a paid query.

    Mutation routes call this before any edit and before loading ancillary
    payment/points data. The database-maintained order version covers every
    checkout-relevant order-field change; total is retained as an explicit
    defense-in-depth comparison for rolling deployments.
    """
    return (
        claim.company_id == order.company_id
        and claim.branch_id == order.branch_id
        and claim.terminal_id == order.terminal_id
        and int(claim.order_total_minor) == int(order.total_minor or 0)
        and int(claim.order_version) == checkout_version(order)
    )


def _set_claim_snapshot(
    claim: OrderCheckoutClaim,
    *,
    order: Order,
    claimant_user_id: UUID,
    terminal_id: UUID,
    paid_minor: int,
    token_hash: str,
    client_instance_hash: str | None,
    expires_at: datetime,
) -> None:
    claim.company_id = order.company_id
    claim.branch_id = order.branch_id
    claim.terminal_id = terminal_id
    claim.claimed_by_user_id = claimant_user_id
    claim.token_hash = token_hash
    claim.client_instance_hash = client_instance_hash
    claim.expires_at = expires_at
    claim.order_total_minor = int(order.total_minor or 0)
    claim.due_minor = _due(order=order, paid_minor=paid_minor)
    claim.order_version = checkout_version(order)


def _require_claimable_order(
    order: Order,
    *,
    paid_minor: int,
    terminal_id: UUID,
) -> None:
    if terminal_id != order.terminal_id:
        raise CheckoutClaimInvalidError(
            "Order and checkout terminal do not match. Select the bill on its own terminal."
        )
    if order.status != "held":
        raise CheckoutClaimUnavailableError(
            f"Only a held order awaiting POS billing can be claimed; status={order.status}.",
            details={"order_status": order.status},
        )
    total_minor = int(order.total_minor or 0)
    paid_minor = max(0, int(paid_minor))
    due_minor = _due(order=order, paid_minor=paid_minor)
    # An exact-zero held bill is legitimate when membership/reward benefits
    # cover the full charge.  It still needs an exclusive claim because
    # finalize-zero issues the invoice and consumes those reservations.  Any
    # positive bill with no remaining due is already settled/inconsistent and
    # must not become claimable again.
    exact_zero_unpaid = total_minor == 0 and paid_minor == 0
    if total_minor < 0 or (due_minor == 0 and not exact_zero_unpaid):
        raise CheckoutClaimUnavailableError(
            "This held order has no payable balance.",
            details={
                "order_total_minor": total_minor,
                "due_minor": due_minor,
            },
        )


async def acquire_checkout_claim(
    session: AsyncSession,
    *,
    order: Order,
    claimant_user_id: UUID,
    terminal_id: UUID,
    paid_minor: int,
    client_instance_id: UUID | None = None,
    now: datetime | None = None,
    ttl_seconds: int | None = None,
) -> CheckoutClaimGrant:
    """Acquire, renew, or take over an expired/stale held-order lease.

    The caller must already hold ``SELECT ... FOR UPDATE`` on ``order``.
    Lock ordering is always order then claim, matching payment and release.
    """
    _require_claimable_order(
        order,
        paid_minor=paid_minor,
        terminal_id=terminal_id,
    )
    now = now or datetime.now(UTC)
    ttl = ttl_seconds or get_settings().checkout_claim_ttl_seconds
    expires_at = now + timedelta(seconds=ttl)
    claim = (
        await session.execute(
            select(OrderCheckoutClaim)
            .where(OrderCheckoutClaim.order_id == order.id)
            .with_for_update()
        )
    ).scalar_one_or_none()

    active_and_current = bool(
        claim
        and claim.expires_at > now
        and _claim_matches_snapshot(claim, order=order, paid_minor=paid_minor)
    )
    same_claimant = bool(
        claim
        and claim.claimed_by_user_id == claimant_user_id
        and claim.terminal_id == terminal_id
    )
    supplied_client_instance_hash = _client_instance_hash(client_instance_id)
    stored_client_instance_hash = (
        getattr(claim, "client_instance_hash", None) if claim is not None else None
    )
    same_client_instance = bool(
        claim
        and (
            (
                stored_client_instance_hash is None
                and supplied_client_instance_hash is None
            )
            or (
                stored_client_instance_hash is not None
                and supplied_client_instance_hash is not None
                and hmac.compare_digest(
                    stored_client_instance_hash,
                    supplied_client_instance_hash,
                )
            )
        )
    )
    same_lease_holder = same_claimant and same_client_instance
    if active_and_current and not same_lease_holder:
        assert claim is not None  # narrowed by active_and_current
        raise CheckoutClaimConflictError(
            "Another cashier or POS client is already billing this order. Wait for "
            "their checkout or try again after the claim expires.",
            details={"expires_at": claim.expires_at.isoformat()},
        )

    token = _new_token()
    token_hash = _token_hash(token)
    reused = bool(active_and_current and same_lease_holder)
    if claim is None:
        claim = OrderCheckoutClaim(
            id=uuid4(),
            order_id=order.id,
            company_id=order.company_id,
            branch_id=order.branch_id,
            terminal_id=terminal_id,
            claimed_by_user_id=claimant_user_id,
            client_instance_hash=supplied_client_instance_hash,
            token_hash=token_hash,
            expires_at=expires_at,
            order_total_minor=int(order.total_minor or 0),
            due_minor=_due(order=order, paid_minor=paid_minor),
            order_version=checkout_version(order),
        )
        session.add(claim)
    else:
        # Same-cashier response-loss retry renews and rotates the credential.
        # An expired or bill-stale row is safe for any eligible cashier to
        # take over.  The previous token immediately becomes unusable.
        _set_claim_snapshot(
            claim,
            order=order,
            claimant_user_id=claimant_user_id,
            terminal_id=terminal_id,
            paid_minor=paid_minor,
            token_hash=token_hash,
            client_instance_hash=supplied_client_instance_hash,
            expires_at=expires_at,
        )
    await session.flush()
    return CheckoutClaimGrant(
        claim=claim,
        token=token,
        paid_minor=max(0, int(paid_minor)),
        reused=reused,
    )


async def guard_checkout_relevant_mutation(
    session: AsyncSession,
    *,
    order: Order,
    operation: str,
    now: datetime | None = None,
) -> None:
    """Refuse bill edits while a current checkout lease is active.

    The caller must already hold ``SELECT ... FOR UPDATE`` on ``order``. This
    preserves the global order-then-claim lock order used by acquisition and
    settlement. Expired or already-stale claims no longer protect a canonical
    bill snapshot, so they are removed transactionally and the mutation may
    proceed; a future cashier can claim the resulting authoritative bill.
    """
    if not requires_checkout_claim(order):
        return
    claim = (
        await session.execute(
            select(OrderCheckoutClaim)
            .where(OrderCheckoutClaim.order_id == order.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if claim is None:
        return

    now = now or datetime.now(UTC)
    if claim.expires_at <= now or not _claim_matches_order_snapshot(claim, order=order):
        await session.delete(claim)
        await session.flush()
        return

    raise CheckoutClaimConflictError(
        f"Cannot {operation} while checkout is in progress for this held bill. "
        "Release the checkout or wait for the claim to expire, then reload the bill.",
        details={
            "order_id": str(order.id),
            "expires_at": claim.expires_at.isoformat(),
            "release_or_wait": True,
        },
    )


async def validate_checkout_claim(
    session: AsyncSession,
    *,
    order: Order,
    claimant_user_id: UUID,
    terminal_id: UUID,
    paid_minor: int,
    token: str | None,
    now: datetime | None = None,
) -> OrderCheckoutClaim | None:
    """Validate a held-order lease and return the locked row for consumption.

    ``None`` is returned for a direct POS order, which is the explicit
    backwards-compatibility boundary.
    """
    if not requires_checkout_claim(order):
        return None
    if not token:
        raise CheckoutClaimRequiredError(
            "Select this held order before collecting payment.",
            details={"reacquire": True},
        )
    supplied_hash = _token_hash(token)
    claim = (
        await session.execute(
            select(OrderCheckoutClaim)
            .where(OrderCheckoutClaim.order_id == order.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if claim is None:
        raise CheckoutClaimRequiredError(
            "This held order is not claimed for checkout. Select it again.",
            details={"reacquire": True},
        )

    now = now or datetime.now(UTC)
    if claim.expires_at <= now:
        raise CheckoutClaimExpiredError(
            "Checkout claim expired before payment. Select the bill again.",
            details={"expired_at": claim.expires_at.isoformat(), "reacquire": True},
        )
    claimant_matches = (
        claim.company_id == order.company_id
        and claim.branch_id == order.branch_id
        and claim.terminal_id == terminal_id
        and claim.claimed_by_user_id == claimant_user_id
    )
    token_matches = hmac.compare_digest(claim.token_hash, supplied_hash)
    if not claimant_matches or not token_matches:
        raise CheckoutClaimInvalidError(
            "Checkout claim does not belong to this cashier and terminal. "
            "Select the held bill again.",
            details={"reacquire": True},
        )
    if not _claim_matches_snapshot(claim, order=order, paid_minor=paid_minor):
        raise CheckoutClaimStaleError(
            "The bill changed after checkout began. Reload and claim the exact bill again.",
            details={
                "order_total_minor": int(order.total_minor or 0),
                "due_minor": _due(order=order, paid_minor=paid_minor),
                "order_version": checkout_version(order),
                "reacquire": True,
            },
        )
    return claim


async def authorize_checkout_claim_for_void(
    session: AsyncSession,
    *,
    order: Order,
    claimant_user_id: UUID,
    terminal_id: UUID,
    paid_minor: int,
    token: str | None,
    now: datetime | None = None,
) -> OrderCheckoutClaim | None:
    """Authorize a reasoned whole-order void without breaking old clients.

    Historically a held bill could be voided without first taking a checkout
    lease.  Preserve that path only when no current lease protects the bill.
    Once a live lease exists, the same cashier/terminal and bearer must
    authorize the void, and the caller must consume the returned row in the
    successful void transaction.

    An active but stale lease deliberately fails closed.  Silently deleting it
    here would let a legacy request void a bill while a cashier is looking at a
    different checkout snapshot; the cashier must release/reacquire first.
    """
    if not requires_checkout_claim(order):
        return None

    claim = (
        await session.execute(
            select(OrderCheckoutClaim)
            .where(OrderCheckoutClaim.order_id == order.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if claim is None:
        if token:
            raise CheckoutClaimInvalidError(
                "This bill no longer has that checkout claim. Reload it before voiding."
            )
        return None

    now = now or datetime.now(UTC)
    if claim.expires_at <= now:
        if token:
            raise CheckoutClaimExpiredError(
                "Checkout claim expired before the void. Reload the bill before continuing.",
                details={"expired_at": claim.expires_at.isoformat(), "reacquire": True},
            )
        # A headerless legacy client may use the historical void workflow once
        # exclusivity has genuinely ended. Remove the dead row transactionally
        # so it cannot obstruct later recovery.
        await session.delete(claim)
        await session.flush()
        return None

    if not token:
        raise CheckoutClaimRequiredError(
            "Checkout is active for this bill. Void it from the client that claimed it.",
            details={"reacquire": True},
        )

    supplied_hash = _token_hash(token)
    claimant_matches = (
        claim.company_id == order.company_id
        and claim.branch_id == order.branch_id
        and claim.terminal_id == terminal_id
        and claim.claimed_by_user_id == claimant_user_id
    )
    if not claimant_matches or not hmac.compare_digest(claim.token_hash, supplied_hash):
        raise CheckoutClaimInvalidError(
            "Checkout claim does not belong to this cashier and terminal. "
            "Reload the bill before voiding.",
            details={"reacquire": True},
        )
    if not _claim_matches_snapshot(claim, order=order, paid_minor=paid_minor):
        raise CheckoutClaimStaleError(
            "The bill changed after checkout began. Reload and claim the exact bill before voiding.",
            details={
                "order_total_minor": int(order.total_minor or 0),
                "due_minor": _due(order=order, paid_minor=paid_minor),
                "order_version": checkout_version(order),
                "reacquire": True,
            },
        )
    return claim


async def release_checkout_claim(
    session: AsyncSession,
    *,
    order: Order,
    claimant_user_id: UUID,
    terminal_id: UUID,
    token: str | None,
    now: datetime | None = None,
) -> bool:
    """Release a lease. Missing/expired rows are idempotent success."""
    claim = (
        await session.execute(
            select(OrderCheckoutClaim)
            .where(OrderCheckoutClaim.order_id == order.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if claim is None:
        return False
    now = now or datetime.now(UTC)
    if claim.expires_at <= now:
        await session.delete(claim)
        await session.flush()
        return True
    if not token:
        raise CheckoutClaimInvalidError(
            "Checkout claim token is required to release this bill."
        )
    supplied_hash = _token_hash(token)
    if (
        claim.company_id != order.company_id
        or claim.branch_id != order.branch_id
        or claim.terminal_id != terminal_id
        or claim.claimed_by_user_id != claimant_user_id
        or not hmac.compare_digest(claim.token_hash, supplied_hash)
    ):
        raise CheckoutClaimInvalidError(
            "Checkout claim does not belong to this cashier and terminal."
        )
    await session.delete(claim)
    await session.flush()
    return True


async def consume_checkout_claim(
    session: AsyncSession,
    claim: OrderCheckoutClaim | None,
) -> None:
    """Delete a validated lease inside the successful settlement transaction."""
    if claim is not None:
        await session.delete(claim)

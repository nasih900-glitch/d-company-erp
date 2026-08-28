"""Customer model — phone-based loyalty foundation.

A row per unique phone number. Captured at POS checkout. Visit count,
total spent, and loyalty points accumulate automatically as orders are
attached. Later: WhatsApp birthday wishes, loyalty redemption flow.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    event,
    inspect,
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Customer(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "customers"
    __table_args__ = (
        # Scoped to live rows only — a deleted customer's phone number must
        # be free for reuse (see DELETE /customers/{id}). A plain
        # UniqueConstraint here would keep blocking it forever, since it
        # doesn't know about deleted_at.
        Index(
            "uq_customer_phone_per_company_live",
            "company_id", "phone",
            unique=True,
            postgresql_where=text("deleted_at IS NULL"),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str | None] = mapped_column(String(200))
    phone: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    email: Mapped[str | None] = mapped_column(String(254))
    birthday: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    first_visit_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_visit_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    visit_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    total_spent_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    loyalty_points: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    # Monotonically increasing — never decremented by redemption, unlike
    # loyalty_points above. Drives the gaming rank ladder (see
    # app/services/pos/points.py) so spending points never demotes you.
    lifetime_gaming_points_earned: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))


class PointsRedemption(Base, TimestampMixin):
    """A customer's loyalty points reserved by an unpaid order, spent for
    playtime value at POINTS_PER_RUPEE (see app/services/pos/points.py).

    Mirrors MembershipBenefitReservation's reserve-then-consume-at-settlement
    discipline (row stays for audit even if the order voids; only a live or
    consumed row counts against the customer's real points balance) — but
    against Customer.loyalty_points directly rather than a period allowance,
    since points redemption isn't tied to having a paid membership tier.
    """

    __tablename__ = "points_redemptions"
    __table_args__ = (
        UniqueConstraint("order_id", name="uq_points_redemption_order"),
        CheckConstraint("points_spent > 0", name="ck_points_redemption_positive"),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="CASCADE"), nullable=False, index=True
    )
    points_spent: Mapped[int] = mapped_column(Integer, nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class OrderLoyaltySettlement(Base, TimestampMixin, TenantMixin):
    """Immutable loyalty facts captured with a successfully settled order.

    Loyalty earn rules and membership multipliers are mutable configuration.
    Refunds must therefore reverse the values that were actually posted at
    checkout, rather than attempting to recompute history from today's menu.

    ``legacy_redemption_only`` rows are migration evidence: the exact consumed
    redemption is known, but pre-ledger earned points are not.  A refund may
    safely restore that redemption while explicitly declining to invent an
    earned-points reversal.
    """

    __tablename__ = "order_loyalty_settlements"
    __table_args__ = (
        UniqueConstraint("order_id", name="uq_order_loyalty_settlement_order"),
        CheckConstraint(
            "order_paid_minor > 0",
            name="ck_order_loyalty_settlement_positive_paid",
        ),
        CheckConstraint(
            "points_redeemed >= 0 AND points_earned >= 0 "
            "AND rank_bonus_points >= 0",
            name="ck_order_loyalty_settlement_nonnegative_points",
        ),
        CheckConstraint(
            "provenance IN ('exact', 'legacy_redemption_only')",
            name="ck_order_loyalty_settlement_provenance",
        ),
        CheckConstraint(
            "provenance <> 'legacy_redemption_only' "
            "OR (points_redeemed > 0 AND points_earned = 0 "
            "AND rank_bonus_points = 0)",
            name="ck_order_loyalty_settlement_legacy_scope",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customers.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="RESTRICT"),
        nullable=False,
    )
    order_paid_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    points_redeemed: Mapped[int] = mapped_column(Integer, nullable=False)
    points_earned: Mapped[int] = mapped_column(Integer, nullable=False)
    rank_bonus_points: Mapped[int] = mapped_column(Integer, nullable=False)
    settled_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    provenance: Mapped[str] = mapped_column(String(40), nullable=False)


class RefundLoyaltyAdjustment(Base, TimestampMixin, TenantMixin):
    """Append-only points projection applied for one immutable POS refund.

    Each row stores the cumulative allocation basis and the *delta* applied by
    this refund.  That distinction removes rounding drift across multiple
    partial refunds and the unique ``refund_id`` prevents response-loss retries
    from changing a customer's balance twice.
    """

    __tablename__ = "refund_loyalty_adjustments"
    __table_args__ = (
        UniqueConstraint("refund_id", name="uq_refund_loyalty_adjustment_refund"),
        CheckConstraint(
            "cumulative_refunded_minor > 0",
            name="ck_refund_loyalty_adjustment_positive_refunded",
        ),
        CheckConstraint(
            "redeemed_points_restored >= 0 AND points_earned_reversed >= 0 "
            "AND rank_bonus_points_reversed >= 0",
            name="ck_refund_loyalty_adjustment_nonnegative_components",
        ),
        CheckConstraint(
            "net_points_delta = redeemed_points_restored "
            "- points_earned_reversed - rank_bonus_points_reversed",
            name="ck_refund_loyalty_adjustment_net_delta",
        ),
        CheckConstraint(
            "balance_after = balance_before + net_points_delta",
            name="ck_refund_loyalty_adjustment_balance_delta",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customers.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    order_loyalty_settlement_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("order_loyalty_settlements.id", ondelete="RESTRICT"),
        nullable=False,
    )
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    cumulative_refunded_minor: Mapped[int] = mapped_column(
        BigInteger, nullable=False
    )
    redeemed_points_restored: Mapped[int] = mapped_column(Integer, nullable=False)
    points_earned_reversed: Mapped[int] = mapped_column(Integer, nullable=False)
    rank_bonus_points_reversed: Mapped[int] = mapped_column(
        Integer, nullable=False
    )
    net_points_delta: Mapped[int] = mapped_column(Integer, nullable=False)
    balance_before: Mapped[int] = mapped_column(Integer, nullable=False)
    balance_after: Mapped[int] = mapped_column(Integer, nullable=False)
    applied_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )


def _guard_loyalty_ledger_row(row: object, label: str) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(f"{label} is immutable: " + ", ".join(changed))


@event.listens_for(OrderLoyaltySettlement, "before_update")
def _guard_order_loyalty_settlement_update(
    _mapper, _connection, row: OrderLoyaltySettlement
) -> None:
    _guard_loyalty_ledger_row(row, "order loyalty settlement")


@event.listens_for(RefundLoyaltyAdjustment, "before_update")
def _guard_refund_loyalty_adjustment_update(
    _mapper, _connection, row: RefundLoyaltyAdjustment
) -> None:
    _guard_loyalty_ledger_row(row, "refund loyalty adjustment")

"""Events module — projector screenings (football, cricket, movies, esports).

A second revenue stream alongside food and per-hour gaming stations.
GST treatment: SAC 999692 (amusement & recreation), 18% (CGST 9% + SGST 9%) —
same as gaming sessions. See docs/INDIA_TAX_COMPLIANCE.md §6.

Domain model:

    Event             one screening
      ├── starts_at, ends_at
      ├── capacity (max tickets)
      ├── base_ticket_price_minor (tax-inclusive)
      └── EventTicket[]  one per attendee
            ├── ticket_no (unique, QR-codeable)
            ├── order_id  (FK to the sale Order — that's where tax + payment live)
            ├── customer_name / phone
            ├── seat (optional)
            └── checked_in_at
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Event(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    """A scheduled projector screening — football match, IPL game, movie, esports final."""

    __tablename__ = "events"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    # Free-text description for the public-facing listing.
    description: Mapped[str | None] = mapped_column(String(1000))
    # Sport/movie taxonomy for analytics + iconography.
    event_type: Mapped[str] = mapped_column(
        String(20), nullable=False
    )  # football | cricket | movie | esports | other
    # Where on premises — useful when a branch has multiple projector areas.
    screen: Mapped[str] = mapped_column(String(50), nullable=False, default="Main Screen")

    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    capacity: Mapped[int] = mapped_column(Integer, nullable=False)
    # Tax-inclusive ticket price (cashier reads ₹250, customer pays ₹250).
    base_ticket_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    # Same SAC and rate as gaming. Stored so future rate-change history is preserved.
    sac_code: Mapped[str] = mapped_column(String(8), nullable=False, default="999692")
    tax_rate: Mapped[float] = mapped_column(Numeric(5, 4), nullable=False, default=0.18)

    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="scheduled"
    )  # scheduled | live | ended | cancelled

    # Optional cover image for the listing UI.
    poster_url: Mapped[str | None] = mapped_column(String(500))


class EventTicket(Base, TimestampMixin):
    """One ticket per attendee. Refunds/voids keep the row (audit), update status only."""

    __tablename__ = "event_tickets"
    __table_args__ = (
        # Ticket numbers are globally unique within a company so QR scans
        # never collide across events.
        UniqueConstraint("ticket_no", name="uq_event_ticket_no"),
    )

    id: Mapped[UUID] = _uuid_pk()
    event_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("events.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    # Human-readable ticket code shown on the printed slip. E.g. "EVT-2026-CL-001".
    ticket_no: Mapped[str] = mapped_column(String(40), nullable=False)
    # The sale order this ticket was paid for. NULL only on comped/staff tickets.
    order_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="SET NULL")
    )
    customer_name: Mapped[str | None] = mapped_column(String(200))
    customer_phone: Mapped[str | None] = mapped_column(String(20))
    seat: Mapped[str | None] = mapped_column(String(10))
    price_paid_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)

    checked_in_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    checked_in_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="sold"
    )  # sold | checked_in | cancelled | refunded | no_show
    sold_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    note: Mapped[str | None] = mapped_column(String(500))

"""Tables (floor) module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, ForeignKey, Integer, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, _uuid_pk


class Floor(Base, TimestampMixin):
    __tablename__ = "floors"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    layout: Mapped[dict | None] = mapped_column(JSONB)


class Table(Base, TimestampMixin):
    __tablename__ = "tables"
    __table_args__ = (UniqueConstraint("floor_id", "code", name="uq_table_code_per_floor"),)

    id: Mapped[UUID] = _uuid_pk()
    floor_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("floors.id", ondelete="CASCADE"), nullable=False, index=True
    )
    code: Mapped[str] = mapped_column(String(20), nullable=False)
    seats: Mapped[int] = mapped_column(Integer, default=2, nullable=False)
    x: Mapped[float] = mapped_column(Numeric(8, 2), default=0)
    y: Mapped[float] = mapped_column(Numeric(8, 2), default=0)
    shape: Mapped[str] = mapped_column(String(20), default="rect")  # rect|round|booth
    status: Mapped[str] = mapped_column(String(20), default="available")  # available|occupied|reserved|cleaning|merged
    merged_into: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("tables.id", ondelete="SET NULL")
    )


class Reservation(Base, TimestampMixin):
    __tablename__ = "reservations"

    id: Mapped[UUID] = _uuid_pk()
    table_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("tables.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    created_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    guest_name: Mapped[str] = mapped_column(String(200), nullable=False)
    party_size: Mapped[int] = mapped_column(Integer, nullable=False)
    contact: Mapped[str | None] = mapped_column(String(50))
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))
    status: Mapped[str] = mapped_column(String(20), default="held")  # held|seated|no_show|cancelled

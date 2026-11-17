"""Durable historical receipt for atomic online shift opening and attendance."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003
from uuid import UUID  # noqa: TC003

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, _uuid_pk


class ShiftStartWorkReceipt(Base, TenantMixin):
    __tablename__ = "shift_start_work_receipts"
    __table_args__ = (UniqueConstraint("action_key", name="uq_shift_start_work_action"),)

    id: Mapped[UUID] = _uuid_pk()
    actor_user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="RESTRICT"))
    branch_id: Mapped[UUID] = mapped_column(ForeignKey("branches.id", ondelete="RESTRICT"))
    terminal_id: Mapped[UUID] = mapped_column(ForeignKey("terminals.id", ondelete="RESTRICT"))
    installation_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True), nullable=True)
    shift_id: Mapped[UUID] = mapped_column(ForeignKey("shifts.id", ondelete="RESTRICT"))
    attendance_id: Mapped[UUID] = mapped_column(ForeignKey("attendance.id", ondelete="RESTRICT"))
    action_key: Mapped[str] = mapped_column(String(160), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    request_path: Mapped[str] = mapped_column(String(80), nullable=False)
    request_body: Mapped[dict] = mapped_column(JSONB, nullable=False)
    response_body: Mapped[dict] = mapped_column(JSONB, nullable=False)
    opening_float_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    clock_in_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    attendance_created: Mapped[bool] = mapped_column(Boolean, nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ShiftStartWorkCancellation(Base, TenantMixin):
    __tablename__ = "shift_start_work_cancellations"
    __table_args__ = (UniqueConstraint("action_key", name="uq_start_work_cancellation_action"),)
    id: Mapped[UUID] = _uuid_pk()
    actor_user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="RESTRICT"))
    branch_id: Mapped[UUID] = mapped_column(ForeignKey("branches.id", ondelete="RESTRICT"))
    terminal_id: Mapped[UUID] = mapped_column(ForeignKey("terminals.id", ondelete="RESTRICT"))
    installation_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True), nullable=True)
    action_key: Mapped[str] = mapped_column(String(160), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    request_path: Mapped[str] = mapped_column(String(80), nullable=False)
    request_body: Mapped[dict] = mapped_column(JSONB, nullable=False)
    client_platform: Mapped[str] = mapped_column(String(10), nullable=False)
    cancelled_by: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="RESTRICT"))
    cancelled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    response_body: Mapped[dict] = mapped_column(JSONB, nullable=False)

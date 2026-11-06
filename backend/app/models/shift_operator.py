"""Append-only responsibility receipts, independent of cash reconciliation."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy runtime mapped type
from uuid import UUID  # noqa: TC003 - SQLAlchemy runtime mapped type

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, _uuid_pk


class ShiftOperatorHandover(Base, TenantMixin):
    __tablename__ = "shift_operator_handovers"
    __table_args__ = (
        UniqueConstraint(
            "company_id", "shift_id", "to_revision", name="uq_shift_operator_revision"
        ),
        UniqueConstraint("action_key", name="uq_shift_operator_action"),
        CheckConstraint(
            "from_revision >= 0 AND to_revision = from_revision + 1",
            name="ck_shift_operator_revision",
        ),
        CheckConstraint(
            "handover_kind = 'owner_recovery' OR initiated_by_user_id <> incoming_user_id",
            name="ck_shift_operator_people",
        ),
        CheckConstraint(
            "cash_count_status = 'NOT_COUNTED' AND counted_cash_minor IS NULL",
            name="ck_shift_operator_no_cash_count",
        ),
        CheckConstraint("length(request_hash) = 64", name="ck_shift_operator_request_hash"),
        CheckConstraint(
            "(handover_kind='handover' AND reason IS NULL AND resolves_handover_id IS NULL) OR "
            "(handover_kind='owner_recovery' AND length(trim(reason)) BETWEEN 12 AND 500 "
            "AND reason IS NOT NULL AND resolves_handover_id IS NOT NULL)",
            name="ck_shift_operator_recovery",
        ),
    )
    id: Mapped[UUID] = _uuid_pk()
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), index=True
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT")
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT")
    )
    initiated_by_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    previous_operator_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=True
    )
    incoming_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    from_revision: Mapped[int] = mapped_column(Integer)
    to_revision: Mapped[int] = mapped_column(Integer)
    handover_kind: Mapped[str] = mapped_column(
        String(24), default="handover", server_default="handover"
    )
    reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    resolves_handover_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("shift_operator_handovers.id", ondelete="RESTRICT"),
        nullable=True,
    )
    action_key: Mapped[str] = mapped_column(String(160))
    request_hash: Mapped[str] = mapped_column(String(64))
    cash_count_status: Mapped[str] = mapped_column(
        String(16), default="NOT_COUNTED", server_default="NOT_COUNTED"
    )
    counted_cash_minor: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.clock_timestamp()
    )

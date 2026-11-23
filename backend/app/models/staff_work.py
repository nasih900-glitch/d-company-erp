"""Versioned staff working-time and draft salary agreements, never pay postings."""

from __future__ import annotations

from datetime import date  # noqa: TC003 - SQLAlchemy resolves mapped types
from uuid import UUID  # noqa: TC003

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Date,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class StaffWorkPolicy(Base, TimestampMixin, TenantMixin):
    __tablename__ = "staff_work_policies"
    __table_args__ = (
        UniqueConstraint("company_id", "user_id", "revision", name="uq_staff_work_policy_revision"),
        CheckConstraint("revision > 0", name="ck_staff_work_policy_revision"),
        CheckConstraint(
            "monthly_salary_minor BETWEEN 0 AND 1000000000000", name="ck_staff_work_policy_salary"
        ),
        CheckConstraint(
            "daily_presence_minutes BETWEEN 1 AND 1440 AND "
            "daily_break_minutes >= 0 AND daily_break_minutes < daily_presence_minutes",
            name="ck_staff_work_policy_hours",
        ),
        CheckConstraint(
            "working_weekday_mask BETWEEN 1 AND 127", name="ck_staff_work_policy_weekdays"
        ),
        CheckConstraint("pay_status = 'draft'", name="ck_staff_work_policy_draft_only"),
        CheckConstraint(
            "overtime_multiplier_basis_points IS NULL OR "
            "overtime_multiplier_basis_points BETWEEN 10000 AND 40000",
            name="ck_staff_work_policy_overtime",
        ),
        CheckConstraint(
            "length(trim(reason)) BETWEEN 1 AND 500", name="ck_staff_work_policy_reason"
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    actor_user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    effective_from: Mapped[date] = mapped_column(Date, nullable=False)
    monthly_salary_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    daily_presence_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    daily_break_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    working_weekday_mask: Mapped[int] = mapped_column(Integer, nullable=False)
    overtime_multiplier_basis_points: Mapped[int | None] = mapped_column(Integer)
    pay_status: Mapped[str] = mapped_column(
        String(16), nullable=False, default="draft", server_default="draft"
    )
    reason: Mapped[str] = mapped_column(String(500), nullable=False)

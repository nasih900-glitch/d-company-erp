"""Users, roles, attendance, payroll."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class User(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("company_id", "email", name="uq_user_email_per_company"),)

    id: Mapped[UUID] = _uuid_pk()
    email: Mapped[str] = mapped_column(String(254), nullable=False, index=True)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    phone: Mapped[str | None] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="active")
    mfa_secret: Mapped[str | None] = mapped_column(String(64))
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    failed_login_count: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Role(Base, TimestampMixin, TenantMixin):
    __tablename__ = "roles"
    __table_args__ = (UniqueConstraint("company_id", "code", name="uq_role_code_per_company"),)

    id: Mapped[UUID] = _uuid_pk()
    code: Mapped[str] = mapped_column(String(50), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    description: Mapped[str | None] = mapped_column(String(500))
    permissions: Mapped[list[str]] = mapped_column(JSONB, default=list)


class UserRole(Base, TimestampMixin):
    __tablename__ = "user_roles"
    __table_args__ = (
        UniqueConstraint("user_id", "role_id", "branch_id", name="uq_user_role_branch"),
    )

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    role_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("roles.id", ondelete="CASCADE"), nullable=False
    )
    branch_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="SET NULL")
    )
    granted_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )


class Attendance(Base, TimestampMixin, TenantMixin):
    __tablename__ = "attendance"

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="CASCADE"), nullable=False
    )
    clock_in_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    clock_out_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    source: Mapped[str] = mapped_column(String(20), default="manual")  # manual|biometric|qr
    break_minutes: Mapped[int] = mapped_column(Integer, default=0)
    notes: Mapped[str | None] = mapped_column(String(500))


class PayrollEntry(Base, TimestampMixin, TenantMixin):
    __tablename__ = "payroll_entries"

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    period_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    period_end: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    base_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    tips_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    bonus_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    deductions_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    paid_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

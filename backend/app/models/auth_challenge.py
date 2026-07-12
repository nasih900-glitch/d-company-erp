"""Single-use approval codes for account and password operations."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class AuthOtpChallenge(Base, TimestampMixin, TenantMixin):
    __tablename__ = "auth_otp_challenges"
    __table_args__ = (
        Index("ix_auth_otp_target_created", "purpose", "target_email", "created_at"),
        Index("ix_auth_otp_ip_created", "requested_ip", "created_at"),
    )

    id: Mapped[UUID] = _uuid_pk()
    purpose: Mapped[str] = mapped_column(String(40), nullable=False, index=True)
    target_email: Mapped[str] = mapped_column(String(254), nullable=False, index=True)
    target_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=True
    )
    code_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    failed_attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    requested_by_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    requested_ip: Mapped[str | None] = mapped_column(String(64))
    request_user_agent: Mapped[str | None] = mapped_column(String(500))

    pending_name: Mapped[str | None] = mapped_column(String(200))
    pending_phone: Mapped[str | None] = mapped_column(String(20))
    pending_role_code: Mapped[str | None] = mapped_column(String(50))
    pending_password_hash: Mapped[str | None] = mapped_column(String(255))

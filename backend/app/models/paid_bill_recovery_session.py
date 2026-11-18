"""Short-lived recovery credentials; plain provenance, never operational authentication."""

# SQLAlchemy resolves annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, Index, Integer, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, _uuid_pk


class PaidBillRecoverySession(Base):
    __tablename__ = "paid_bill_recovery_sessions"
    __table_args__ = (
        UniqueConstraint("token_hash", name="uq_paid_recovery_token"),
        Index("ix_paid_recovery_exact", "company_id", "user_id", "action_key_hash", "command_hash"),
        Index("ix_paid_recovery_expiry", "expires_at"),
    )
    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    user_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    branch_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    terminal_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    order_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    purpose: Mapped[str] = mapped_column(String(40))
    request_path: Mapped[str] = mapped_column(String(100))
    action_key_hash: Mapped[str] = mapped_column(String(64))
    command_hash: Mapped[str] = mapped_column(String(64))
    token_hash: Mapped[str] = mapped_column(String(64))
    auth_version: Mapped[int] = mapped_column(Integer)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_reason: Mapped[str | None] = mapped_column(String(20))

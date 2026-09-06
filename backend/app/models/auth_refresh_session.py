"""Hashed, single-use refresh-token session ledger.

The signed JWT remains the client credential.  This table stores only its
SHA-256 digest and the non-secret session-family identifier needed to rotate
and revoke it safely.  Raw refresh credentials must never be persisted.
"""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped annotations at runtime
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped annotations at runtime

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, String, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class AuthRefreshSession(Base, TimestampMixin, TenantMixin):
    """One issued refresh credential in a revocable token family."""

    __tablename__ = "auth_refresh_sessions"
    __table_args__ = (
        UniqueConstraint(
            "company_id",
            "token_hash",
            name="uq_auth_refresh_sessions_company_token_hash",
        ),
        Index(
            "ix_auth_refresh_sessions_company_user_family",
            "company_id",
            "user_id",
            "family_id",
        ),
        Index(
            "ix_auth_refresh_sessions_expires_at",
            "expires_at",
        ),
        Index(
            "ix_auth_refresh_sessions_active_family",
            "company_id",
            "user_id",
            "family_id",
            postgresql_where=text("revoked_at IS NULL"),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    family_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    auth_version: Mapped[int] = mapped_column(Integer, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_reason: Mapped[str | None] = mapped_column(String(32))
    legacy_exchange: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
    )

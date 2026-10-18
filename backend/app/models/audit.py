"""Audit log — append-only."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Index,
    Integer,
    String,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class AuditLog(Base):
    """Single application-level append-only audit table."""

    __tablename__ = "audit_log"
    __table_args__ = (
        Index("ix_audit_company", "company_id"),
        Index("ix_audit_action", "action"),
        Index("ix_audit_entity_type", "entity_type"),
        Index("ix_audit_entity_id", "entity_id"),
        Index("ix_audit_created_at", "created_at"),
        Index("ix_audit_log_company_id_id", "company_id", "id"),
        Index("ix_audit_company_code_id", "company_id", "action_code", "id"),
        Index("ix_audit_company_category_id", "company_id", "category", "id"),
        Index("ix_audit_company_actor_id", "company_id", "actor_user_id", "id"),
        Index("ix_audit_company_reference_id", "company_id", "source_reference", "id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    actor_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True)
    )
    company_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    action: Mapped[str] = mapped_column(String(100), nullable=False)
    entity_type: Mapped[str] = mapped_column(String(100), nullable=False)
    entity_id: Mapped[str] = mapped_column(String(64), nullable=False)
    # Additive semantic evidence. Null on legacy rows: never backfill guessed history.
    action_code: Mapped[str | None] = mapped_column(String(24))
    category: Mapped[str | None] = mapped_column(String(32))
    stage: Mapped[str | None] = mapped_column(String(32))
    outcome: Mapped[str | None] = mapped_column(String(32))
    operation_id: Mapped[str | None] = mapped_column(String(160))
    source_reference: Mapped[str | None] = mapped_column(String(120))
    actor_kind: Mapped[str | None] = mapped_column(String(24))
    target_user_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    business_details: Mapped[dict | None] = mapped_column(JSONB)
    catalogue_version: Mapped[int | None] = mapped_column(Integer)
    before: Mapped[dict | None] = mapped_column(JSONB)
    after: Mapped[dict | None] = mapped_column(JSONB)
    ip: Mapped[str | None] = mapped_column(String(45))
    user_agent: Mapped[str | None] = mapped_column(String(500))
    terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), index=True
    )
    request_id: Mapped[str | None] = mapped_column(String(64), index=True)
    client_platform: Mapped[str | None] = mapped_column(String(20))
    client_version_code: Mapped[int | None] = mapped_column(Integer)
    client_action_id: Mapped[str | None] = mapped_column(String(160))
    client_reported_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    client_was_offline: Mapped[bool | None] = mapped_column(Boolean)
    synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    reason: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

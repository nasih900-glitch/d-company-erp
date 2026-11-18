"""GRN-only control-plane facts; no cancellation-side business-parent FKs."""

# SQLAlchemy resolves mapped types at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, _uuid_pk


class GrnActionDecision(Base):
    __tablename__ = "grn_action_decisions"
    __table_args__ = (
        UniqueConstraint("action_key", name="uq_grn_action_key"),
        UniqueConstraint("accepted_grn_id", name="uq_grn_action_receipt"),
        Index("ix_grn_action_actor", "company_id", "actor_user_id"),
    )
    id: Mapped[UUID] = _uuid_pk()
    action_key: Mapped[str] = mapped_column(String(160))
    company_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    branch_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    actor_user_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    terminal_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    terminal_known: Mapped[bool] = mapped_column(Boolean)
    request_hash: Mapped[str] = mapped_column(String(64))
    outcome: Mapped[str] = mapped_column(String(9))
    accepted_grn_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("grns.id", ondelete="RESTRICT")
    )
    original_wire_json: Mapped[str | None] = mapped_column(Text)
    cancellation_reason: Mapped[str | None] = mapped_column(String(500))
    recovery_session_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    decided_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class GrnCompletionProof(Base):
    __tablename__ = "grn_completion_proofs"
    __table_args__ = (
        Index(
            "ix_grn_proof_source",
            "source",
            postgresql_using="gin",
            postgresql_ops={"source": "jsonb_path_ops"},
        ),
    )
    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("grn_action_decisions.id", ondelete="RESTRICT"),
        primary_key=True,
    )
    profile: Mapped[str] = mapped_column(String(20))
    original_wire_json: Mapped[str | None] = mapped_column(Text)
    terminal_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    terminal_known: Mapped[bool] = mapped_column(Boolean)
    source: Mapped[dict] = mapped_column(JSONB)
    response: Mapped[dict] = mapped_column(JSONB)


class GrnRecoverySession(Base):
    __tablename__ = "grn_recovery_sessions"
    __table_args__ = (
        UniqueConstraint("token_hash", name="uq_grn_recovery_token"),
        Index("ix_grn_recovery_exact", "company_id", "user_id", "action_key_hash", "request_hash"),
        Index("ix_grn_recovery_expiry", "expires_at"),
    )
    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    user_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    branch_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    terminal_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    action_key_hash: Mapped[str] = mapped_column(String(64))
    request_hash: Mapped[str] = mapped_column(String(64))
    token_hash: Mapped[str] = mapped_column(String(64))
    auth_version: Mapped[int] = mapped_column(Integer)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_reason: Mapped[str | None] = mapped_column(String(20))

"""Exact-key terminal decision; plain context UUIDs deliberately avoid business-parent locks."""

# SQLAlchemy resolves mapped annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, ForeignKey, String, UniqueConstraint, event
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, _uuid_pk


class PaidBillActionDecision(Base):
    __tablename__ = "paid_bill_action_decisions"
    __table_args__ = (
        UniqueConstraint("company_id", "action_key", name="uq_paid_bill_decision_key"),
        UniqueConstraint("accepted_intent_id", name="uq_paid_bill_decision_intent"),
    )
    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    recorded_by: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    order_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    branch_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    terminal_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True))
    action_key: Mapped[str] = mapped_column(String(160))
    request_path: Mapped[str] = mapped_column(String(100))
    request_hash: Mapped[str] = mapped_column(String(64))
    request_body: Mapped[dict] = mapped_column(JSONB, info={"audit_redact": True})
    outcome: Mapped[str] = mapped_column(String(9))
    accepted_intent_id: Mapped[UUID | None] = mapped_column(
        ForeignKey(
            "paid_bill_amendment_intents.id",
            ondelete="RESTRICT",
            deferrable=True,
            initially="DEFERRED",
        )
    )
    cancellation_reason: Mapped[str | None] = mapped_column(
        String(500), info={"audit_redact": True}
    )
    response_body: Mapped[dict] = mapped_column(JSONB, info={"audit_redact": True})
    decided_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


@event.listens_for(PaidBillActionDecision, "before_update")
@event.listens_for(PaidBillActionDecision, "before_delete")
def _immutable(*_):
    raise ValueError("Paid bill action decisions are append-only")

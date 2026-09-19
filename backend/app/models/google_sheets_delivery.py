"""Durable tenant-scoped delivery ledger for the Google Sheets ERP mirror."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Index,
    Integer,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk

GOOGLE_SHEETS_DELIVERY_STATES = ("pending", "leased", "delivered", "quarantined")


class GoogleSheetsDelivery(Base, TimestampMixin, TenantMixin):
    """One immutable ERP event plus its mutable delivery lifecycle.

    The payload and source identity are immutable by convention. Dispatchers only
    update lease, attempt, error, delivery, and quarantine columns.
    """

    __tablename__ = "google_sheets_deliveries"
    __table_args__ = (
        UniqueConstraint(
            "company_id",
            "event_key",
            name="uq_google_sheets_deliveries_company_event_key",
        ),
        UniqueConstraint("event_id", name="uq_google_sheets_deliveries_event_id"),
        CheckConstraint(
            "status IN ('pending', 'leased', 'delivered', 'quarantined')",
            name="ck_google_sheets_deliveries_status",
        ),
        CheckConstraint(
            "schema_version BETWEEN 1 AND 32767",
            name="ck_google_sheets_deliveries_schema_version",
        ),
        CheckConstraint(
            "attempt_count >= 0",
            name="ck_google_sheets_deliveries_attempt_count",
        ),
        CheckConstraint(
            "payload_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_google_sheets_deliveries_payload_sha256",
        ),
        CheckConstraint(
            "length(trim(event_key)) BETWEEN 1 AND 96 "
            "AND length(trim(event_type)) BETWEEN 1 AND 80 "
            "AND length(trim(source_type)) BETWEEN 1 AND 80 "
            "AND length(trim(source_id)) BETWEEN 1 AND 200 "
            "AND length(trim(source_revision)) BETWEEN 1 AND 100",
            name="ck_google_sheets_deliveries_identity",
        ),
        CheckConstraint(
            "(status = 'pending' AND lease_owner IS NULL AND lease_expires_at IS NULL "
            " AND delivered_at IS NULL AND quarantined_at IS NULL) OR "
            "(status = 'leased' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL "
            " AND delivered_at IS NULL AND quarantined_at IS NULL) OR "
            "(status = 'delivered' AND lease_owner IS NULL AND lease_expires_at IS NULL "
            " AND delivered_at IS NOT NULL AND quarantined_at IS NULL) OR "
            "(status = 'quarantined' AND lease_owner IS NULL AND lease_expires_at IS NULL "
            " AND delivered_at IS NULL AND quarantined_at IS NOT NULL "
            " AND quarantine_reason IS NOT NULL)",
            name="ck_google_sheets_deliveries_state_evidence",
        ),
        Index(
            "ix_google_sheets_deliveries_due",
            "status",
            "available_at",
            "lease_expires_at",
            postgresql_where=text("status IN ('pending', 'leased')"),
        ),
        Index(
            "ix_google_sheets_deliveries_company_occurred",
            "company_id",
            "occurred_at",
        ),
        Index(
            "ix_google_sheets_deliveries_company_configuration_status",
            "company_id",
            "configuration_id",
            "event_type",
            "status",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    configuration_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), nullable=False
    )
    event_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    event_key: Mapped[str] = mapped_column(String(96), nullable=False)
    event_type: Mapped[str] = mapped_column(String(80), nullable=False)
    source_type: Mapped[str] = mapped_column(String(80), nullable=False)
    source_id: Mapped[str] = mapped_column(String(200), nullable=False)
    source_revision: Mapped[str] = mapped_column(String(100), nullable=False)
    schema_version: Mapped[int] = mapped_column(Integer, nullable=False)
    payload: Mapped[dict[str, object]] = mapped_column(JSONB, nullable=False)
    payload_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, server_default="pending"
    )
    lease_owner: Mapped[str | None] = mapped_column(String(128))
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    attempt_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    last_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_error_code: Mapped[str | None] = mapped_column(String(80))
    last_error_detail: Mapped[str | None] = mapped_column(String(500))
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    quarantined_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    quarantine_reason: Mapped[str | None] = mapped_column(String(500))


__all__ = ["GOOGLE_SHEETS_DELIVERY_STATES", "GoogleSheetsDelivery"]

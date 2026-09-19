"""Durable Google Sheets ERP Mirror v1 delivery outbox.

Revision ID: 0075
Revises: 0074
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0075"
down_revision = "0074"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "google_sheets_deliveries",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("company_id", sa.UUID(), nullable=False),
        sa.Column("configuration_id", sa.UUID(), nullable=False),
        sa.Column("event_id", sa.UUID(), nullable=False),
        sa.Column("event_key", sa.String(length=96), nullable=False),
        sa.Column("event_type", sa.String(length=80), nullable=False),
        sa.Column("source_type", sa.String(length=80), nullable=False),
        sa.Column("source_id", sa.String(length=200), nullable=False),
        sa.Column("source_revision", sa.String(length=100), nullable=False),
        sa.Column("schema_version", sa.Integer(), nullable=False),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("payload_sha256", sa.String(length=64), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("status", sa.String(length=20), server_default="pending", nullable=False),
        sa.Column("lease_owner", sa.String(length=128), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("attempt_count", sa.Integer(), server_default="0", nullable=False),
        sa.Column("last_attempt_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_error_code", sa.String(length=80), nullable=True),
        sa.Column("last_error_detail", sa.String(length=500), nullable=True),
        sa.Column("delivered_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("quarantined_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("quarantine_reason", sa.String(length=500), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "status IN ('pending', 'leased', 'delivered', 'quarantined')",
            name="ck_google_sheets_deliveries_status",
        ),
        sa.CheckConstraint(
            "schema_version BETWEEN 1 AND 32767",
            name="ck_google_sheets_deliveries_schema_version",
        ),
        sa.CheckConstraint(
            "attempt_count >= 0", name="ck_google_sheets_deliveries_attempt_count"
        ),
        sa.CheckConstraint(
            "payload_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_google_sheets_deliveries_payload_sha256",
        ),
        sa.CheckConstraint(
            "length(trim(event_key)) BETWEEN 1 AND 96 "
            "AND length(trim(event_type)) BETWEEN 1 AND 80 "
            "AND length(trim(source_type)) BETWEEN 1 AND 80 "
            "AND length(trim(source_id)) BETWEEN 1 AND 200 "
            "AND length(trim(source_revision)) BETWEEN 1 AND 100",
            name="ck_google_sheets_deliveries_identity",
        ),
        sa.CheckConstraint(
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
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "event_key",
            name="uq_google_sheets_deliveries_company_event_key",
        ),
        sa.UniqueConstraint("event_id", name="uq_google_sheets_deliveries_event_id"),
    )
    op.create_index(
        "ix_google_sheets_deliveries_company_id",
        "google_sheets_deliveries",
        ["company_id"],
    )
    op.create_index(
        "ix_google_sheets_deliveries_company_occurred",
        "google_sheets_deliveries",
        ["company_id", "occurred_at"],
    )
    op.create_index(
        "ix_google_sheets_deliveries_company_configuration_status",
        "google_sheets_deliveries",
        ["company_id", "configuration_id", "event_type", "status"],
    )
    op.create_index(
        "ix_google_sheets_deliveries_due",
        "google_sheets_deliveries",
        ["status", "available_at", "lease_expires_at"],
        postgresql_where=sa.text("status IN ('pending', 'leased')"),
    )
    op.execute(
        """
        CREATE FUNCTION protect_google_sheets_delivery_event()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'Google Sheets delivery events are append-only';
            END IF;
            IF OLD.id IS DISTINCT FROM NEW.id
               OR OLD.company_id IS DISTINCT FROM NEW.company_id
               OR OLD.configuration_id IS DISTINCT FROM NEW.configuration_id
               OR OLD.event_id IS DISTINCT FROM NEW.event_id
               OR OLD.event_key IS DISTINCT FROM NEW.event_key
               OR OLD.event_type IS DISTINCT FROM NEW.event_type
               OR OLD.source_type IS DISTINCT FROM NEW.source_type
               OR OLD.source_id IS DISTINCT FROM NEW.source_id
               OR OLD.source_revision IS DISTINCT FROM NEW.source_revision
               OR OLD.schema_version IS DISTINCT FROM NEW.schema_version
               OR OLD.payload IS DISTINCT FROM NEW.payload
               OR OLD.payload_sha256 IS DISTINCT FROM NEW.payload_sha256
               OR OLD.occurred_at IS DISTINCT FROM NEW.occurred_at
               OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
                RAISE EXCEPTION 'Google Sheets delivery event evidence is immutable';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_google_sheets_delivery_event_immutable
        BEFORE UPDATE OR DELETE ON google_sheets_deliveries
        FOR EACH ROW EXECUTE FUNCTION protect_google_sheets_delivery_event()
        """
    )


def downgrade() -> None:
    delivery_evidence_exists = bool(
        op.get_bind()
        .execute(
            sa.text(
                "SELECT EXISTS (SELECT 1 FROM google_sheets_deliveries)"
            )
        )
        .scalar_one()
    )
    if delivery_evidence_exists:
        raise RuntimeError(
            "0075 downgrade refused: Google Sheets delivery evidence exists"
        )
    op.execute(
        "DROP TRIGGER trg_google_sheets_delivery_event_immutable "
        "ON google_sheets_deliveries"
    )
    op.execute("DROP FUNCTION protect_google_sheets_delivery_event()")
    op.drop_index("ix_google_sheets_deliveries_due", table_name="google_sheets_deliveries")
    op.drop_index(
        "ix_google_sheets_deliveries_company_configuration_status",
        table_name="google_sheets_deliveries",
    )
    op.drop_index(
        "ix_google_sheets_deliveries_company_occurred",
        table_name="google_sheets_deliveries",
    )
    op.drop_index(
        "ix_google_sheets_deliveries_company_id", table_name="google_sheets_deliveries"
    )
    op.drop_table("google_sheets_deliveries")

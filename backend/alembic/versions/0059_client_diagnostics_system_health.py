"""Privacy-preserving client diagnostics for owner System Health.

Revision ID: 0059
Revises: 0058
Create Date: 2026-08-30

Only allowlisted categorical evidence is retained.  Tenant, actor, terminal,
and receipt time are server-authoritative.  Events are immutable during the
90-day support window, replay-idempotent, and bounded per installation, user,
and company.  Expired rows may be deleted so diagnostics do not become an
unbounded shadow audit log.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0059"
down_revision = "0058"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "client_diagnostic_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_event_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True)),
        sa.Column("event_type", sa.String(length=32), nullable=False),
        sa.Column("severity", sa.String(length=16), nullable=False),
        sa.Column("component", sa.String(length=24), nullable=False),
        sa.Column("reason_code", sa.String(length=64), nullable=False),
        sa.Column("failure_fingerprint", sa.String(length=64)),
        sa.Column("version_name", sa.String(length=80), nullable=False),
        sa.Column("version_code", sa.Integer(), nullable=False),
        sa.Column("os_api_level", sa.Integer()),
        sa.Column("http_status", sa.Integer()),
        sa.Column("duration_bucket", sa.String(length=20)),
        sa.Column("connectivity", sa.String(length=16), nullable=False),
        sa.Column("pending_outbox_count", sa.Integer()),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint(
            "event_type IN ('crash', 'anr', 'api_failure', 'sync_stall')",
            name="ck_client_diagnostic_events_type",
        ),
        sa.CheckConstraint(
            "severity IN ('warning', 'error', 'critical')",
            name="ck_client_diagnostic_events_severity",
        ),
        sa.CheckConstraint(
            "component IN ('app', 'auth', 'gaming', 'pos', 'finance', 'sync', "
            "'network', 'updates', 'storage')",
            name="ck_client_diagnostic_events_component",
        ),
        sa.CheckConstraint(
            "connectivity IN ('online', 'offline', 'unknown')",
            name="ck_client_diagnostic_events_connectivity",
        ),
        sa.CheckConstraint(
            "duration_bucket IS NULL OR duration_bucket IN "
            "('under_5s', '5_to_30s', '30s_to_2m', '2_to_10m', 'over_10m')",
            name="ck_client_diagnostic_events_duration_bucket",
        ),
        sa.CheckConstraint(
            "version_code BETWEEN 1 AND 2147483647",
            name="ck_client_diagnostic_events_version_code",
        ),
        sa.CheckConstraint(
            "version_name ~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$'",
            name="ck_client_diagnostic_events_version_name",
        ),
        sa.CheckConstraint(
            "os_api_level IS NULL OR os_api_level BETWEEN 21 AND 100",
            name="ck_client_diagnostic_events_os_api_level",
        ),
        sa.CheckConstraint(
            "reason_code ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'",
            name="ck_client_diagnostic_events_reason_code",
        ),
        sa.CheckConstraint(
            "failure_fingerprint IS NULL OR failure_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_client_diagnostic_events_fingerprint",
        ),
        sa.CheckConstraint(
            "http_status IS NULL OR http_status BETWEEN 100 AND 599",
            name="ck_client_diagnostic_events_http_status",
        ),
        sa.CheckConstraint(
            "pending_outbox_count IS NULL OR pending_outbox_count BETWEEN 0 AND 1000000",
            name="ck_client_diagnostic_events_pending_outbox_count",
        ),
        sa.CheckConstraint(
            "event_type = 'api_failure' OR http_status IS NULL",
            name="ck_client_diagnostic_events_http_status_scope",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["actor_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "installation_id",
            "client_event_id",
            name="uq_client_diagnostic_events_company_installation_event",
        ),
    )
    op.create_index(
        "ix_client_diagnostic_events_company_id",
        "client_diagnostic_events",
        ["company_id"],
    )
    op.create_index(
        "ix_client_diagnostic_events_company_received",
        "client_diagnostic_events",
        ["company_id", "received_at"],
    )
    op.create_index(
        "ix_client_diagnostic_events_company_type_occurred",
        "client_diagnostic_events",
        ["company_id", "event_type", "occurred_at"],
    )
    op.create_index(
        "ix_client_diagnostic_events_installation_occurred",
        "client_diagnostic_events",
        ["installation_id", "occurred_at"],
    )
    op.create_index(
        "ix_client_diagnostic_events_company_severity",
        "client_diagnostic_events",
        ["company_id", "severity", "occurred_at"],
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_client_diagnostic_event()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'UPDATE' THEN
                RAISE EXCEPTION 'client diagnostic events are immutable'
                    USING ERRCODE = '23514';
            END IF;

            IF TG_OP = 'DELETE' THEN
                IF OLD.received_at >= now() - interval '90 days' THEN
                    RAISE EXCEPTION
                        'client diagnostic events may be deleted only after retention expires'
                        USING ERRCODE = '23514';
                END IF;
                RETURN OLD;
            END IF;

            PERFORM pg_advisory_xact_lock(
                hashtextextended('dcompany-client-diagnostics:' || NEW.company_id::text, 0)
            );

            -- Receipt time is server authority even for direct maintenance
            -- writers. Clients can report occurred_at but never retention age.
            NEW.received_at := now();

            IF NOT EXISTS (
                SELECT 1 FROM client_diagnostic_events cde
                 WHERE cde.company_id = NEW.company_id
                   AND cde.installation_id = NEW.installation_id
                   AND cde.client_event_id = NEW.client_event_id
            ) THEN
                IF (
                    SELECT count(*) FROM client_diagnostic_events cde
                     WHERE cde.company_id = NEW.company_id
                       AND cde.installation_id = NEW.installation_id
                ) >= 10000 THEN
                    RAISE EXCEPTION 'client diagnostic installation capacity reached'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    SELECT count(*) FROM client_diagnostic_events cde
                     WHERE cde.company_id = NEW.company_id
                       AND cde.actor_user_id = NEW.actor_user_id
                ) >= 20000 THEN
                    RAISE EXCEPTION 'client diagnostic user capacity reached'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    SELECT count(*) FROM client_diagnostic_events cde
                     WHERE cde.company_id = NEW.company_id
                ) >= 50000 THEN
                    RAISE EXCEPTION 'client diagnostic company capacity reached'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.actor_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client diagnostic actor crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.terminal_id IS NOT NULL AND NOT EXISTS (
                SELECT 1
                  FROM terminals t
                  JOIN branches b ON b.id = t.branch_id
                 WHERE t.id = NEW.terminal_id
                   AND b.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client diagnostic terminal crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.occurred_at > now() + interval '24 hours'
               OR NEW.occurred_at < now() - interval '90 days' THEN
                RAISE EXCEPTION 'client diagnostic occurrence time is outside retention bounds'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_client_diagnostic_events_immutable_scope
        BEFORE INSERT OR UPDATE OR DELETE ON client_diagnostic_events
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_client_diagnostic_event();
        """
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS trg_client_diagnostic_events_immutable_scope "
        "ON client_diagnostic_events"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_client_diagnostic_event()")
    op.drop_index(
        "ix_client_diagnostic_events_company_severity",
        table_name="client_diagnostic_events",
    )
    op.drop_index(
        "ix_client_diagnostic_events_installation_occurred",
        table_name="client_diagnostic_events",
    )
    op.drop_index(
        "ix_client_diagnostic_events_company_type_occurred",
        table_name="client_diagnostic_events",
    )
    op.drop_index(
        "ix_client_diagnostic_events_company_received",
        table_name="client_diagnostic_events",
    )
    op.drop_index(
        "ix_client_diagnostic_events_company_id",
        table_name="client_diagnostic_events",
    )
    op.drop_table("client_diagnostic_events")

"""Consent-gated ERP-only remote assistance.

Revision ID: 0061
Revises: 0060
Create Date: 2026-08-30

The database retains consent, session, command, and actor evidence only.  JPEG
frames are intentionally absent: the application relays one latest frame in
Redis under a short TTL and fails closed when that relay is unavailable.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0061"
down_revision = "0060"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "client_installations",
        sa.Column("remote_support_protocol_version", sa.Integer()),
    )
    op.add_column(
        "client_installations",
        sa.Column("remote_support_capability", sa.String(length=32)),
    )
    op.add_column(
        "client_installations",
        sa.Column("remote_support_last_seen_at", sa.DateTime(timezone=True)),
    )
    op.create_check_constraint(
        "ck_client_installations_remote_protocol",
        "client_installations",
        "remote_support_protocol_version IS NULL OR "
        "remote_support_protocol_version BETWEEN 1 AND 10",
    )
    op.create_check_constraint(
        "ck_client_installations_remote_capability",
        "client_installations",
        "remote_support_capability IS NULL OR remote_support_capability IN "
        "('available', 'permission_required', 'unsupported')",
    )
    op.create_check_constraint(
        "ck_client_installations_remote_heartbeat",
        "client_installations",
        "(remote_support_protocol_version IS NULL "
        "AND remote_support_capability IS NULL "
        "AND remote_support_last_seen_at IS NULL) OR "
        "(remote_support_protocol_version IS NOT NULL "
        "AND remote_support_capability IS NOT NULL "
        "AND remote_support_last_seen_at IS NOT NULL)",
    )

    op.create_table(
        "remote_assistance_grants",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("requested_by_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("responded_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("revoked_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("requested_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("responded_at", sa.DateTime(timezone=True)),
        sa.Column("decision_id", postgresql.UUID(as_uuid=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
        sa.Column("revocation_id", postgresql.UUID(as_uuid=True)),
        sa.Column("consumed_at", sa.DateTime(timezone=True)),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "kind IN ('one_time', 'anytime')",
            name="ck_remote_assistance_grants_kind",
        ),
        sa.CheckConstraint(
            "status IN ('requested', 'active', 'declined', 'revoked', 'expired', 'consumed')",
            name="ck_remote_assistance_grants_status",
        ),
        sa.CheckConstraint(
            "expires_at > requested_at",
            name="ck_remote_assistance_grants_expiry",
        ),
        sa.CheckConstraint(
            "(status = 'requested' AND responded_at IS NULL "
            "AND responded_by_user_id IS NULL AND decision_id IS NULL) OR "
            "(status IN ('active', 'declined', 'consumed') "
            "AND responded_at IS NOT NULL AND responded_by_user_id IS NOT NULL "
            "AND decision_id IS NOT NULL) OR "
            "(status IN ('revoked', 'expired') AND ((responded_at IS NULL "
            "AND responded_by_user_id IS NULL AND decision_id IS NULL) OR "
            "(responded_at IS NOT NULL AND responded_by_user_id IS NOT NULL "
            "AND decision_id IS NOT NULL)))",
            name="ck_remote_assistance_grants_response_evidence",
        ),
        sa.CheckConstraint(
            "(status <> 'revoked' AND revoked_at IS NULL "
            "AND revoked_by_user_id IS NULL AND revocation_id IS NULL) OR "
            "(status = 'revoked' AND revoked_at IS NOT NULL "
            "AND revoked_by_user_id IS NOT NULL AND revocation_id IS NOT NULL)",
            name="ck_remote_assistance_grants_revocation_evidence",
        ),
        sa.CheckConstraint(
            "(status <> 'consumed' AND consumed_at IS NULL) OR "
            "(status = 'consumed' AND kind = 'one_time' AND consumed_at IS NOT NULL) OR "
            "(status = 'revoked' AND kind = 'one_time' AND consumed_at IS NOT NULL)",
            name="ck_remote_assistance_grants_consumption",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_remote_assistance_grants_scoped_installation",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(["requested_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["responded_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["revoked_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("company_id", "id", name="uq_remote_assistance_grants_company_id_id"),
        sa.UniqueConstraint(
            "company_id",
            "decision_id",
            name="uq_remote_assistance_grants_company_decision_id",
        ),
        sa.UniqueConstraint(
            "company_id",
            "revocation_id",
            name="uq_remote_assistance_grants_company_revocation_id",
        ),
    )
    op.create_index(
        "ix_remote_assistance_grants_company_id",
        "remote_assistance_grants",
        ["company_id"],
    )
    op.create_index(
        "ix_remote_assistance_grants_company_device_requested",
        "remote_assistance_grants",
        ["company_id", "client_installation_id", "requested_at"],
    )
    op.create_index(
        "uq_remote_assistance_grants_device_open",
        "remote_assistance_grants",
        ["company_id", "client_installation_id"],
        unique=True,
        postgresql_where=sa.text("status IN ('requested', 'active')"),
    )
    op.create_index(
        "ix_remote_assistance_grants_expires_at",
        "remote_assistance_grants",
        ["expires_at"],
    )

    op.create_table(
        "remote_assistance_sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("grant_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("requested_by_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("started_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("ended_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("duration_seconds", sa.Integer(), nullable=False),
        sa.Column("requested_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("request_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True)),
        sa.Column("expires_at", sa.DateTime(timezone=True)),
        sa.Column("start_id", postgresql.UUID(as_uuid=True)),
        sa.Column("ended_at", sa.DateTime(timezone=True)),
        sa.Column("end_id", postgresql.UUID(as_uuid=True)),
        sa.Column("end_reason", sa.String(length=32)),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('requested', 'active', 'ended', 'expired')",
            name="ck_remote_assistance_sessions_status",
        ),
        sa.CheckConstraint(
            "duration_seconds BETWEEN 60 AND 900",
            name="ck_remote_assistance_sessions_duration",
        ),
        sa.CheckConstraint(
            "request_expires_at > requested_at",
            name="ck_remote_assistance_sessions_request_expiry",
        ),
        sa.CheckConstraint(
            "(status = 'requested' AND started_at IS NULL AND expires_at IS NULL "
            "AND started_by_user_id IS NULL AND start_id IS NULL) OR "
            "(status = 'active' AND started_at IS NOT NULL AND expires_at IS NOT NULL "
            "AND started_by_user_id IS NOT NULL AND start_id IS NOT NULL "
            "AND expires_at > started_at) OR "
            "(status IN ('ended', 'expired') AND ((started_at IS NULL "
            "AND expires_at IS NULL AND started_by_user_id IS NULL AND start_id IS NULL) OR "
            "(started_at IS NOT NULL AND expires_at IS NOT NULL "
            "AND started_by_user_id IS NOT NULL AND start_id IS NOT NULL "
            "AND expires_at > started_at)))",
            name="ck_remote_assistance_sessions_start_evidence",
        ),
        sa.CheckConstraint(
            "(status <> 'ended' AND ended_at IS NULL AND ended_by_user_id IS NULL "
            "AND end_id IS NULL AND end_reason IS NULL) OR "
            "(status = 'ended' AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL "
            "AND end_id IS NOT NULL AND end_reason IS NOT NULL)",
            name="ck_remote_assistance_sessions_end_evidence",
        ),
        sa.CheckConstraint(
            "end_reason IS NULL OR end_reason IN "
            "('owner_ended', 'user_ended', 'permission_revoked', 'capture_stopped', "
            "'app_backgrounded', 'grant_revoked', 'grant_declined')",
            name="ck_remote_assistance_sessions_end_reason",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["company_id", "grant_id"],
            ["remote_assistance_grants.company_id", "remote_assistance_grants.id"],
            name="fk_remote_assistance_sessions_scoped_grant",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_remote_assistance_sessions_scoped_installation",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(["requested_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["started_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["ended_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("company_id", "id", name="uq_remote_assistance_sessions_company_id_id"),
        sa.UniqueConstraint(
            "company_id",
            "start_id",
            name="uq_remote_assistance_sessions_company_start_id",
        ),
        sa.UniqueConstraint(
            "company_id",
            "end_id",
            name="uq_remote_assistance_sessions_company_end_id",
        ),
    )
    op.create_index(
        "ix_remote_assistance_sessions_company_id",
        "remote_assistance_sessions",
        ["company_id"],
    )
    op.create_index(
        "ix_remote_assistance_sessions_company_device_requested",
        "remote_assistance_sessions",
        ["company_id", "client_installation_id", "requested_at"],
    )
    op.create_index(
        "uq_remote_assistance_sessions_device_open",
        "remote_assistance_sessions",
        ["company_id", "client_installation_id"],
        unique=True,
        postgresql_where=sa.text("status IN ('requested', 'active')"),
    )
    op.create_index(
        "ix_remote_assistance_sessions_expires_at",
        "remote_assistance_sessions",
        ["expires_at"],
    )

    op.create_table(
        "remote_assistance_commands",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sequence", sa.BigInteger(), nullable=False),
        sa.Column("issued_by_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("resolved_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("command_type", sa.String(length=32), nullable=False),
        sa.Column("module", sa.String(length=32)),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("resolved_at", sa.DateTime(timezone=True)),
        sa.Column("rejection_reason_code", sa.String(length=32)),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint(
            "command_type IN ('navigate', 'refresh', 'collect_diagnostics')",
            name="ck_remote_assistance_commands_type",
        ),
        sa.CheckConstraint(
            "module IS NULL OR module = 'help'",
            name="ck_remote_assistance_commands_module",
        ),
        sa.CheckConstraint(
            "(command_type = 'navigate' AND module IS NOT NULL) OR "
            "(command_type <> 'navigate' AND module IS NULL)",
            name="ck_remote_assistance_commands_payload",
        ),
        sa.CheckConstraint(
            "status IN ('pending', 'acknowledged', 'rejected')",
            name="ck_remote_assistance_commands_status",
        ),
        sa.CheckConstraint(
            "sequence BETWEEN 1 AND 100",
            name="ck_remote_assistance_commands_sequence",
        ),
        sa.CheckConstraint(
            "(status = 'pending' AND resolved_at IS NULL AND resolved_by_user_id IS NULL "
            "AND rejection_reason_code IS NULL) OR "
            "(status = 'acknowledged' AND resolved_at IS NOT NULL "
            "AND resolved_by_user_id IS NOT NULL AND rejection_reason_code IS NULL) OR "
            "(status = 'rejected' AND resolved_at IS NOT NULL "
            "AND rejection_reason_code IS NOT NULL "
            "AND (resolved_by_user_id IS NOT NULL "
            "OR rejection_reason_code = 'session_ended'))",
            name="ck_remote_assistance_commands_resolution",
        ),
        sa.CheckConstraint(
            "rejection_reason_code IS NULL OR rejection_reason_code IN "
            "('unsupported_command', 'module_unavailable', 'permission_denied', "
            "'not_in_foreground', 'session_inactive', 'execution_failed', "
            "'session_ended')",
            name="ck_remote_assistance_commands_rejection_reason",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["company_id", "session_id"],
            ["remote_assistance_sessions.company_id", "remote_assistance_sessions.id"],
            name="fk_remote_assistance_commands_scoped_session",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(["issued_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["resolved_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "session_id",
            "sequence",
            name="uq_remote_assistance_commands_session_sequence",
        ),
    )
    op.create_index(
        "ix_remote_assistance_commands_company_id",
        "remote_assistance_commands",
        ["company_id"],
    )
    op.create_index(
        "ix_remote_assistance_commands_session_status_sequence",
        "remote_assistance_commands",
        ["session_id", "status", "sequence"],
    )
    op.create_index(
        "ix_remote_assistance_commands_company_issued",
        "remote_assistance_commands",
        ["company_id", "issued_at"],
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_remote_assistance_grant()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'remote assistance grants are retained audit evidence'
                    USING ERRCODE = '23514';
            END IF;

            IF NOT EXISTS (
                SELECT 1 FROM client_installations ci
                 WHERE ci.id = NEW.client_installation_id
                   AND ci.company_id = NEW.company_id
                   AND ci.platform = 'android'
            ) THEN
                RAISE EXCEPTION 'remote assistance grant device crosses company or platform scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.requested_by_user_id AND u.company_id = NEW.company_id
            ) OR (NEW.responded_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.responded_by_user_id AND u.company_id = NEW.company_id
            )) OR (NEW.revoked_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.revoked_by_user_id AND u.company_id = NEW.company_id
            )) THEN
                RAISE EXCEPTION 'remote assistance grant actor crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF TG_OP = 'UPDATE' THEN
                IF NEW.id IS DISTINCT FROM OLD.id
                   OR NEW.company_id IS DISTINCT FROM OLD.company_id
                   OR NEW.client_installation_id IS DISTINCT FROM OLD.client_installation_id
                   OR NEW.requested_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
                   OR NEW.kind IS DISTINCT FROM OLD.kind
                   OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
                   OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
                   OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                    RAISE EXCEPTION 'remote assistance grant identity is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF NOT (
                    NEW.status = OLD.status OR
                    (OLD.status = 'requested' AND
                     NEW.status IN ('active','declined','revoked','expired')) OR
                    (OLD.status = 'active' AND NEW.status IN ('revoked','expired','consumed')) OR
                    (OLD.status = 'consumed' AND NEW.status = 'revoked')
                ) THEN
                    RAISE EXCEPTION 'invalid remote assistance grant transition'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    NEW.responded_at IS DISTINCT FROM OLD.responded_at OR
                    NEW.responded_by_user_id IS DISTINCT FROM OLD.responded_by_user_id OR
                    NEW.decision_id IS DISTINCT FROM OLD.decision_id
                ) AND NOT (
                    OLD.status = 'requested' AND NEW.status IN ('active', 'declined')
                ) THEN
                    RAISE EXCEPTION 'remote assistance grant decision is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    NEW.revoked_at IS DISTINCT FROM OLD.revoked_at OR
                    NEW.revoked_by_user_id IS DISTINCT FROM OLD.revoked_by_user_id OR
                    NEW.revocation_id IS DISTINCT FROM OLD.revocation_id
                ) AND NOT (
                    OLD.status IN ('requested', 'active', 'consumed')
                    AND NEW.status = 'revoked'
                ) THEN
                    RAISE EXCEPTION 'remote assistance grant revocation is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF NEW.consumed_at IS DISTINCT FROM OLD.consumed_at AND NOT (
                    OLD.status = 'active' AND NEW.status = 'consumed'
                    AND NEW.kind = 'one_time'
                ) THEN
                    RAISE EXCEPTION 'remote assistance grant consumption is immutable'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_remote_assistance_grants_guard
        BEFORE INSERT OR UPDATE OR DELETE ON remote_assistance_grants
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_remote_assistance_grant();

        CREATE FUNCTION dcompany_guard_remote_assistance_session()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'remote assistance sessions are retained audit evidence'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM remote_assistance_grants rag
                 WHERE rag.id = NEW.grant_id
                   AND rag.company_id = NEW.company_id
                   AND rag.client_installation_id = NEW.client_installation_id
            ) THEN
                RAISE EXCEPTION 'remote assistance session grant crosses device scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.requested_by_user_id AND u.company_id = NEW.company_id
            ) OR (NEW.started_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.started_by_user_id AND u.company_id = NEW.company_id
            )) OR (NEW.ended_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.ended_by_user_id AND u.company_id = NEW.company_id
            )) THEN
                RAISE EXCEPTION 'remote assistance session actor crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF TG_OP = 'UPDATE' THEN
                IF NEW.id IS DISTINCT FROM OLD.id
                   OR NEW.company_id IS DISTINCT FROM OLD.company_id
                   OR NEW.grant_id IS DISTINCT FROM OLD.grant_id
                   OR NEW.client_installation_id IS DISTINCT FROM OLD.client_installation_id
                   OR NEW.requested_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
                   OR NEW.duration_seconds IS DISTINCT FROM OLD.duration_seconds
                   OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
                   OR NEW.request_expires_at IS DISTINCT FROM OLD.request_expires_at
                   OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                    RAISE EXCEPTION 'remote assistance session identity is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF NOT (
                    NEW.status = OLD.status OR
                    (OLD.status = 'requested' AND NEW.status IN ('active','ended','expired')) OR
                    (OLD.status = 'active' AND NEW.status IN ('ended','expired'))
                ) THEN
                    RAISE EXCEPTION 'invalid remote assistance session transition'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    NEW.started_at IS DISTINCT FROM OLD.started_at OR
                    NEW.started_by_user_id IS DISTINCT FROM OLD.started_by_user_id OR
                    NEW.start_id IS DISTINCT FROM OLD.start_id OR
                    NEW.expires_at IS DISTINCT FROM OLD.expires_at
                ) AND NOT (OLD.status = 'requested' AND NEW.status = 'active') THEN
                    RAISE EXCEPTION 'remote assistance session start evidence is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    NEW.ended_at IS DISTINCT FROM OLD.ended_at OR
                    NEW.ended_by_user_id IS DISTINCT FROM OLD.ended_by_user_id OR
                    NEW.end_id IS DISTINCT FROM OLD.end_id OR
                    NEW.end_reason IS DISTINCT FROM OLD.end_reason
                ) AND NOT (
                    OLD.status IN ('requested', 'active') AND NEW.status = 'ended'
                ) THEN
                    RAISE EXCEPTION 'remote assistance session end evidence is immutable'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_remote_assistance_sessions_guard
        BEFORE INSERT OR UPDATE OR DELETE ON remote_assistance_sessions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_remote_assistance_session();

        CREATE FUNCTION dcompany_guard_remote_assistance_command()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'remote assistance commands are retained audit evidence'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM remote_assistance_sessions ras
                 WHERE ras.id = NEW.session_id AND ras.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'remote assistance command crosses session scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.issued_by_user_id AND u.company_id = NEW.company_id
            ) OR (NEW.resolved_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.resolved_by_user_id AND u.company_id = NEW.company_id
            )) THEN
                RAISE EXCEPTION 'remote assistance command actor crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF TG_OP = 'UPDATE' THEN
                IF NEW.id IS DISTINCT FROM OLD.id
                   OR NEW.company_id IS DISTINCT FROM OLD.company_id
                   OR NEW.session_id IS DISTINCT FROM OLD.session_id
                   OR NEW.sequence IS DISTINCT FROM OLD.sequence
                   OR NEW.issued_by_user_id IS DISTINCT FROM OLD.issued_by_user_id
                   OR NEW.command_type IS DISTINCT FROM OLD.command_type
                   OR NEW.module IS DISTINCT FROM OLD.module
                   OR NEW.issued_at IS DISTINCT FROM OLD.issued_at THEN
                    RAISE EXCEPTION 'remote assistance command identity is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF NOT (NEW.status = OLD.status OR (
                    OLD.status = 'pending' AND NEW.status IN ('acknowledged','rejected')
                )) THEN
                    RAISE EXCEPTION 'invalid remote assistance command transition'
                        USING ERRCODE = '23514';
                END IF;
                IF OLD.resolved_at IS NOT NULL AND (
                    NEW.resolved_at IS DISTINCT FROM OLD.resolved_at OR
                    NEW.resolved_by_user_id IS DISTINCT FROM OLD.resolved_by_user_id OR
                    NEW.rejection_reason_code IS DISTINCT FROM OLD.rejection_reason_code
                ) THEN
                    RAISE EXCEPTION 'remote assistance command resolution is immutable'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_remote_assistance_commands_guard
        BEFORE INSERT OR UPDATE OR DELETE ON remote_assistance_commands
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_remote_assistance_command();
        """
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS trg_remote_assistance_commands_guard ON remote_assistance_commands"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_remote_assistance_command()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_remote_assistance_sessions_guard ON remote_assistance_sessions"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_remote_assistance_session()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_remote_assistance_grants_guard ON remote_assistance_grants"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_remote_assistance_grant()")
    op.drop_index(
        "ix_remote_assistance_commands_company_issued",
        table_name="remote_assistance_commands",
    )
    op.drop_index(
        "ix_remote_assistance_commands_session_status_sequence",
        table_name="remote_assistance_commands",
    )
    op.drop_index(
        "ix_remote_assistance_commands_company_id",
        table_name="remote_assistance_commands",
    )
    op.drop_table("remote_assistance_commands")
    op.drop_index(
        "ix_remote_assistance_sessions_expires_at",
        table_name="remote_assistance_sessions",
    )
    op.drop_index(
        "uq_remote_assistance_sessions_device_open",
        table_name="remote_assistance_sessions",
    )
    op.drop_index(
        "ix_remote_assistance_sessions_company_device_requested",
        table_name="remote_assistance_sessions",
    )
    op.drop_index(
        "ix_remote_assistance_sessions_company_id",
        table_name="remote_assistance_sessions",
    )
    op.drop_table("remote_assistance_sessions")
    op.drop_index(
        "ix_remote_assistance_grants_expires_at",
        table_name="remote_assistance_grants",
    )
    op.drop_index(
        "uq_remote_assistance_grants_device_open",
        table_name="remote_assistance_grants",
    )
    op.drop_index(
        "ix_remote_assistance_grants_company_device_requested",
        table_name="remote_assistance_grants",
    )
    op.drop_index(
        "ix_remote_assistance_grants_company_id",
        table_name="remote_assistance_grants",
    )
    op.drop_table("remote_assistance_grants")
    op.drop_constraint(
        "ck_client_installations_remote_heartbeat",
        "client_installations",
        type_="check",
    )
    op.drop_constraint(
        "ck_client_installations_remote_capability",
        "client_installations",
        type_="check",
    )
    op.drop_constraint(
        "ck_client_installations_remote_protocol",
        "client_installations",
        type_="check",
    )
    op.drop_column("client_installations", "remote_support_last_seen_at")
    op.drop_column("client_installations", "remote_support_capability")
    op.drop_column("client_installations", "remote_support_protocol_version")

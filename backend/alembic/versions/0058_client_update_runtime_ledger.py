"""Client installation telemetry and verified Android release registry.

Revision ID: 0058
Revises: 0057
Create Date: 2026-08-29

The runtime ledger is deliberately bounded and tenant-scoped.  It stores no
hardware identifiers or free-form client logs.  Update events are immutable,
and Android artifact metadata can only be staged by the backend promotion CLI;
the owner API may transition release state but cannot rewrite provenance.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0058"
down_revision = "0057"
branch_labels = None
depends_on = None


_ERROR_CODES_SQL = (
    "'network_error', 'http_error', 'insufficient_storage', 'invalid_metadata', "
    "'size_mismatch', 'checksum_mismatch', 'archive_unreadable', 'package_mismatch', "
    "'version_mismatch', 'signer_mismatch', 'installer_permission_denied', "
    "'installer_unavailable', 'installer_not_completed', 'unknown'"
)


def upgrade() -> None:
    op.create_table(
        "client_installations",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("registered_by_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("last_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True)),
        sa.Column("platform", sa.String(length=20), nullable=False),
        sa.Column("distribution_channel", sa.String(length=20), nullable=False),
        sa.Column("version_name", sa.String(length=80), nullable=False),
        sa.Column("version_code", sa.Integer(), nullable=False),
        sa.Column("pending_outbox_count", sa.Integer(), nullable=False),
        sa.Column("last_successful_sync_at", sa.DateTime(timezone=True)),
        sa.Column("update_state", sa.String(length=32), nullable=False),
        sa.Column("update_error_code", sa.String(length=64)),
        sa.Column(
            "last_seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
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
        sa.CheckConstraint("platform = 'android'", name="ck_client_installations_platform"),
        sa.CheckConstraint(
            "distribution_channel IN ('direct', 'play', 'managed')",
            name="ck_client_installations_distribution_channel",
        ),
        sa.CheckConstraint(
            "update_state IN ('idle', 'update_available', 'downloading', 'verifying', "
            "'verified', 'installer_opened', 'failed')",
            name="ck_client_installations_update_state",
        ),
        sa.CheckConstraint(
            f"update_error_code IS NULL OR update_error_code IN ({_ERROR_CODES_SQL})",
            name="ck_client_installations_error_code",
        ),
        sa.CheckConstraint(
            "(update_state = 'failed' AND update_error_code IS NOT NULL) OR "
            "(update_state <> 'failed' AND update_error_code IS NULL)",
            name="ck_client_installations_failure_evidence",
        ),
        sa.CheckConstraint(
            "version_code BETWEEN 1 AND 2147483647",
            name="ck_client_installations_version_code",
        ),
        sa.CheckConstraint(
            "pending_outbox_count BETWEEN 0 AND 1000000",
            name="ck_client_installations_pending_outbox_count",
        ),
        sa.CheckConstraint(
            "length(trim(version_name)) BETWEEN 1 AND 80",
            name="ck_client_installations_version_name",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["registered_by_user_id"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(["last_user_id"], ["users.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="SET NULL"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "installation_id",
            name="uq_client_installations_company_installation",
        ),
        sa.UniqueConstraint("company_id", "id", name="uq_client_installations_company_id_id"),
    )
    op.create_index(
        "ix_client_installations_company_id",
        "client_installations",
        ["company_id"],
    )
    op.create_index(
        "ix_client_installations_company_last_seen",
        "client_installations",
        ["company_id", "last_seen_at"],
    )
    op.create_index(
        "ix_client_installations_company_version",
        "client_installations",
        ["company_id", "platform", "version_code"],
    )
    op.create_index(
        "ix_client_installations_company_terminal",
        "client_installations",
        ["company_id", "terminal_id"],
    )
    op.create_index(
        "ix_client_installations_company_registered_by",
        "client_installations",
        ["company_id", "registered_by_user_id"],
    )

    op.create_table(
        "client_update_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_event_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True)),
        sa.Column("event_type", sa.String(length=32), nullable=False),
        sa.Column("target_version_name", sa.String(length=80), nullable=False),
        sa.Column("target_version_code", sa.Integer(), nullable=False),
        sa.Column("error_code", sa.String(length=64)),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint(
            "event_type IN ('update_offered', 'download_started', 'download_verified', "
            "'installer_opened', 'upgrade_confirmed', 'update_cancelled', 'update_failed')",
            name="ck_client_update_events_type",
        ),
        sa.CheckConstraint(
            f"error_code IS NULL OR error_code IN ({_ERROR_CODES_SQL})",
            name="ck_client_update_events_error_code",
        ),
        sa.CheckConstraint(
            "(event_type = 'update_failed' AND error_code IS NOT NULL) OR "
            "(event_type <> 'update_failed' AND error_code IS NULL)",
            name="ck_client_update_events_failure_evidence",
        ),
        sa.CheckConstraint(
            "target_version_code BETWEEN 1 AND 2147483647",
            name="ck_client_update_events_target_version_code",
        ),
        sa.CheckConstraint(
            "length(trim(target_version_name)) BETWEEN 1 AND 80",
            name="ck_client_update_events_target_version_name",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_client_update_events_scoped_installation",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(["actor_user_id"], ["users.id"], ondelete="RESTRICT"),
        # Event context is evidence.  RESTRICT preserves it and avoids the
        # implicit UPDATE that ON DELETE SET NULL would perform against an
        # append-only table.
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "client_installation_id",
            "client_event_id",
            name="uq_client_update_events_installation_client_event",
        ),
    )
    op.create_index("ix_client_update_events_company_id", "client_update_events", ["company_id"])
    op.create_index(
        "ix_client_update_events_company_received",
        "client_update_events",
        ["company_id", "received_at"],
    )
    op.create_index(
        "ix_client_update_events_installation_occurred",
        "client_update_events",
        ["client_installation_id", "occurred_at"],
    )
    op.create_index(
        "ix_client_update_events_company_actor",
        "client_update_events",
        ["company_id", "actor_user_id"],
    )

    op.create_table(
        "android_releases",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("channel", sa.String(length=20), server_default="direct", nullable=False),
        sa.Column("version_code", sa.Integer(), nullable=False),
        sa.Column("version_name", sa.String(length=80), nullable=False),
        sa.Column("update_url", sa.String(length=1000), nullable=False),
        sa.Column("release_notes", sa.String(length=2000), nullable=False),
        sa.Column("apk_sha256", sa.String(length=64), nullable=False),
        sa.Column("apk_size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("apk_signing_cert_sha256", sa.String(length=64), nullable=False),
        sa.Column("manifest_sha256", sa.String(length=64), nullable=False),
        sa.Column("source_git_sha", sa.String(length=40), nullable=False),
        sa.Column("source_release_ref", sa.String(length=81), nullable=False),
        sa.Column("source_workflow_run_id", sa.BigInteger(), nullable=False),
        sa.Column("source_workflow_run_attempt", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(length=20), server_default="staged", nullable=False),
        sa.Column(
            "registered_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("activated_at", sa.DateTime(timezone=True)),
        sa.Column("activated_by", postgresql.UUID(as_uuid=True)),
        sa.Column("withdrawn_at", sa.DateTime(timezone=True)),
        sa.Column("withdrawn_by", postgresql.UUID(as_uuid=True)),
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
        sa.CheckConstraint("channel = 'direct'", name="ck_android_releases_channel"),
        sa.CheckConstraint(
            "status IN ('staged', 'active', 'withdrawn')",
            name="ck_android_releases_status",
        ),
        sa.CheckConstraint(
            "version_code BETWEEN 15 AND 2147483647",
            name="ck_android_releases_version_code",
        ),
        sa.CheckConstraint(
            "version_name ~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$'",
            name="ck_android_releases_version_name",
        ),
        sa.CheckConstraint(
            "length(release_notes) BETWEEN 1 AND 2000 AND release_notes !~ '[^ -~]'",
            name="ck_android_releases_release_notes",
        ),
        sa.CheckConstraint(
            "apk_size_bytes BETWEEN 1 AND 536870912",
            name="ck_android_releases_apk_size",
        ),
        sa.CheckConstraint(
            "apk_sha256 ~ '^[0-9a-f]{64}$' AND "
            "apk_signing_cert_sha256 ~ '^[0-9a-f]{64}$' AND "
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "source_git_sha ~ '^[0-9a-f]{40}$'",
            name="ck_android_releases_hashes",
        ),
        sa.CheckConstraint(
            "length(source_release_ref) BETWEEN 2 AND 81 "
            "AND source_release_ref = 'v' || version_name "
            "AND source_release_ref !~ '[^ -~]'",
            name="ck_android_releases_source_release_ref",
        ),
        sa.CheckConstraint(
            "source_workflow_run_id BETWEEN 1 AND 9223372036854775807",
            name="ck_android_releases_source_workflow_run_id",
        ),
        sa.CheckConstraint(
            "source_workflow_run_attempt BETWEEN 1 AND 2147483647",
            name="ck_android_releases_source_workflow_run_attempt",
        ),
        sa.CheckConstraint(
            "left(update_url, 8) = 'https://'",
            name="ck_android_releases_https_url",
        ),
        sa.CheckConstraint(
            "(status = 'staged' AND activated_at IS NULL AND activated_by IS NULL "
            "AND withdrawn_at IS NULL AND withdrawn_by IS NULL) OR "
            "(status = 'active' AND activated_at IS NOT NULL AND activated_by IS NOT NULL "
            "AND withdrawn_at IS NULL AND withdrawn_by IS NULL) OR "
            "(status = 'withdrawn' AND withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL)",
            name="ck_android_releases_state_evidence",
        ),
        sa.ForeignKeyConstraint(["activated_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["withdrawn_by"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("channel", "version_code", name="uq_android_releases_channel_version"),
    )
    op.create_index(
        "ix_android_releases_status_registered",
        "android_releases",
        ["status", "registered_at"],
    )
    op.create_index(
        "uq_android_releases_one_active_direct",
        "android_releases",
        ["channel"],
        unique=True,
        postgresql_where=sa.text("status = 'active'"),
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_validate_client_installation_scope()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                PERFORM pg_advisory_xact_lock(
                    hashtextextended('dcompany-client-telemetry:' || NEW.company_id::text, 0)
                );
                -- INSERT .. ON CONFLICT invokes BEFORE INSERT triggers even for
                -- a heartbeat that will become an UPDATE. Exact replays must
                -- remain usable after the admission ceiling is reached.
                IF NOT EXISTS (
                    SELECT 1 FROM client_installations ci
                     WHERE ci.company_id = NEW.company_id
                       AND ci.installation_id = NEW.installation_id
                ) THEN
                    IF (
                        SELECT count(*) FROM client_installations ci
                         WHERE ci.company_id = NEW.company_id
                    ) >= 32 THEN
                        RAISE EXCEPTION 'client installation company capacity reached'
                            USING ERRCODE = '23514';
                    END IF;
                    IF (
                        SELECT count(*) FROM client_installations ci
                         WHERE ci.company_id = NEW.company_id
                           AND ci.registered_by_user_id = NEW.registered_by_user_id
                    ) >= 8 THEN
                        RAISE EXCEPTION 'client installation user capacity reached'
                            USING ERRCODE = '23514';
                    END IF;
                END IF;
            END IF;

            IF TG_OP = 'UPDATE' AND (
                NEW.id IS DISTINCT FROM OLD.id
                OR NEW.company_id IS DISTINCT FROM OLD.company_id
                OR NEW.installation_id IS DISTINCT FROM OLD.installation_id
                OR NEW.registered_by_user_id IS DISTINCT FROM OLD.registered_by_user_id
                OR NEW.created_at IS DISTINCT FROM OLD.created_at
            ) THEN
                RAISE EXCEPTION 'client installation tenant identity is immutable'
                    USING ERRCODE = '23514';
            END IF;

            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.registered_by_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client installation registering user crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.last_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.last_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client installation user crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.terminal_id IS NOT NULL AND NOT EXISTS (
                SELECT 1
                  FROM terminals t
                  JOIN branches b ON b.id = t.branch_id
                 WHERE t.id = NEW.terminal_id
                   AND b.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client installation terminal crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.last_successful_sync_at > now() + interval '24 hours' THEN
                RAISE EXCEPTION 'client installation sync time is implausibly in the future'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_client_installations_scope
        BEFORE INSERT OR UPDATE ON client_installations
        FOR EACH ROW EXECUTE FUNCTION dcompany_validate_client_installation_scope();
        """
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_client_update_event()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION 'client update events are immutable'
                    USING ERRCODE = '23514';
            END IF;
            PERFORM pg_advisory_xact_lock(
                hashtextextended('dcompany-client-telemetry:' || NEW.company_id::text, 0)
            );
            -- Idempotent event replay remains valid at capacity. The API
            -- separately proves that an identical id carries identical data.
            IF NOT EXISTS (
                SELECT 1 FROM client_update_events cue
                 WHERE cue.company_id = NEW.company_id
                   AND cue.client_installation_id = NEW.client_installation_id
                   AND cue.client_event_id = NEW.client_event_id
            ) THEN
                IF (
                    SELECT count(*) FROM client_update_events cue
                     WHERE cue.company_id = NEW.company_id
                       AND cue.client_installation_id = NEW.client_installation_id
                ) >= 1000 THEN
                    RAISE EXCEPTION 'client update event installation capacity reached'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    SELECT count(*) FROM client_update_events cue
                     WHERE cue.company_id = NEW.company_id
                       AND cue.actor_user_id = NEW.actor_user_id
                ) >= 2000 THEN
                    RAISE EXCEPTION 'client update event user capacity reached'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    SELECT count(*) FROM client_update_events cue
                     WHERE cue.company_id = NEW.company_id
                ) >= 10000 THEN
                    RAISE EXCEPTION 'client update event company capacity reached'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.actor_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client update event actor crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.terminal_id IS NOT NULL AND NOT EXISTS (
                SELECT 1
                  FROM terminals t
                  JOIN branches b ON b.id = t.branch_id
                 WHERE t.id = NEW.terminal_id
                   AND b.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client update event terminal crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.occurred_at > now() + interval '24 hours' THEN
                RAISE EXCEPTION 'client update event time is implausibly in the future'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_client_update_events_immutable_scope
        BEFORE INSERT OR UPDATE OR DELETE ON client_update_events
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_client_update_event();
        """
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_android_release()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'Android release evidence cannot be deleted'
                    USING ERRCODE = '23514';
            END IF;
            IF TG_OP = 'INSERT' THEN
                IF NEW.status <> 'staged' THEN
                    RAISE EXCEPTION 'Android releases must be registered as staged'
                        USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;

            IF ROW(
                NEW.id, NEW.channel, NEW.version_code, NEW.version_name,
                NEW.update_url, NEW.release_notes, NEW.apk_sha256,
                NEW.apk_size_bytes, NEW.apk_signing_cert_sha256,
                NEW.manifest_sha256, NEW.source_git_sha,
                NEW.source_release_ref, NEW.source_workflow_run_id,
                NEW.source_workflow_run_attempt,
                NEW.registered_at, NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.id, OLD.channel, OLD.version_code, OLD.version_name,
                OLD.update_url, OLD.release_notes, OLD.apk_sha256,
                OLD.apk_size_bytes, OLD.apk_signing_cert_sha256,
                OLD.manifest_sha256, OLD.source_git_sha,
                OLD.source_release_ref, OLD.source_workflow_run_id,
                OLD.source_workflow_run_attempt,
                OLD.registered_at, OLD.created_at
            ) THEN
                RAISE EXCEPTION 'Android release artifact metadata is immutable'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.status = OLD.status THEN
                IF ROW(NEW.activated_at, NEW.activated_by, NEW.withdrawn_at, NEW.withdrawn_by)
                   IS DISTINCT FROM
                   ROW(OLD.activated_at, OLD.activated_by, OLD.withdrawn_at, OLD.withdrawn_by)
                THEN
                    RAISE EXCEPTION 'Android release state evidence requires a transition'
                        USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;

            IF NOT (
                (OLD.status = 'staged' AND NEW.status IN ('active', 'withdrawn'))
                OR (OLD.status = 'active' AND NEW.status = 'withdrawn')
                OR (OLD.status = 'withdrawn' AND NEW.status = 'active')
            ) THEN
                RAISE EXCEPTION 'invalid Android release state transition'
                    USING ERRCODE = '23514';
            END IF;

            IF OLD.status = 'active' AND NEW.status = 'withdrawn'
               AND ROW(NEW.activated_at, NEW.activated_by)
                   IS DISTINCT FROM ROW(OLD.activated_at, OLD.activated_by) THEN
                RAISE EXCEPTION 'Android release activation evidence is immutable'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_android_releases_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON android_releases
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_android_release();
        """
    )


def downgrade() -> None:
    bind = op.get_bind()
    evidence_count = int(
        bind.execute(
            sa.text(
                """
                SELECT
                    (SELECT count(*) FROM client_installations)
                  + (SELECT count(*) FROM client_update_events)
                  + (SELECT count(*) FROM android_releases)
                """
            )
        ).scalar_one()
    )
    if evidence_count:
        raise RuntimeError(
            "Refusing unsafe downgrade from 0058 to 0057: client runtime or Android "
            "release evidence exists. Export and preserve it before any destructive downgrade."
        )

    op.execute("DROP TRIGGER IF EXISTS trg_android_releases_integrity ON android_releases")
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_android_release()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_client_update_events_immutable_scope ON client_update_events"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_client_update_event()")
    op.execute("DROP TRIGGER IF EXISTS trg_client_installations_scope ON client_installations")
    op.execute("DROP FUNCTION IF EXISTS dcompany_validate_client_installation_scope()")

    op.drop_index("uq_android_releases_one_active_direct", table_name="android_releases")
    op.drop_index("ix_android_releases_status_registered", table_name="android_releases")
    op.drop_table("android_releases")
    op.drop_index(
        "ix_client_update_events_company_actor", table_name="client_update_events"
    )
    op.drop_index(
        "ix_client_update_events_installation_occurred", table_name="client_update_events"
    )
    op.drop_index("ix_client_update_events_company_received", table_name="client_update_events")
    op.drop_index("ix_client_update_events_company_id", table_name="client_update_events")
    op.drop_table("client_update_events")
    op.drop_index("ix_client_installations_company_terminal", table_name="client_installations")
    op.drop_index(
        "ix_client_installations_company_registered_by", table_name="client_installations"
    )
    op.drop_index("ix_client_installations_company_version", table_name="client_installations")
    op.drop_index("ix_client_installations_company_last_seen", table_name="client_installations")
    op.drop_index("ix_client_installations_company_id", table_name="client_installations")
    op.drop_table("client_installations")

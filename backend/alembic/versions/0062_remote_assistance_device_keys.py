"""Cryptographic Android device identity for remote assistance.

Revision ID: 0062
Revises: 0061
Create Date: 2026-08-30

Only canonical P-256 public SPKI bytes are retained. Private keys and human
pairing codes never belong in PostgreSQL.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0062"
down_revision = "0061"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Existing 0061 databases admitted one command that Code 17 never shipped.
    # Abort on unexpected retained evidence rather than rewriting or allowing
    # the API and database contracts to disagree.
    op.drop_constraint(
        "ck_remote_assistance_commands_type",
        "remote_assistance_commands",
        type_="check",
    )
    op.drop_constraint(
        "ck_remote_assistance_commands_module",
        "remote_assistance_commands",
        type_="check",
    )
    op.execute(
        "ALTER TABLE remote_assistance_commands "
        "ADD CONSTRAINT ck_remote_assistance_commands_type "
        "CHECK (command_type IN ('navigate', 'refresh', 'collect_diagnostics')) NOT VALID"
    )
    op.execute(
        "ALTER TABLE remote_assistance_commands "
        "ADD CONSTRAINT ck_remote_assistance_commands_module "
        "CHECK (module IS NULL OR module = 'help') NOT VALID"
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM remote_assistance_commands
                 WHERE command_type NOT IN ('navigate', 'refresh', 'collect_diagnostics')
            ) THEN
                RAISE EXCEPTION '0062 found a command outside the closed semantic set'
                    USING ERRCODE = '23514',
                          HINT = 'Review retained command evidence before retrying.';
            END IF;
            IF EXISTS (
                SELECT 1 FROM remote_assistance_commands
                 WHERE module IS NOT NULL AND module <> 'help'
            ) THEN
                RAISE EXCEPTION '0062 found navigation outside the Help-only boundary'
                    USING ERRCODE = '23514',
                          HINT = 'Review retained command evidence before retrying.';
            END IF;
            ALTER TABLE remote_assistance_commands
                VALIDATE CONSTRAINT ck_remote_assistance_commands_type;
            ALTER TABLE remote_assistance_commands
                VALIDATE CONSTRAINT ck_remote_assistance_commands_module;
        END
        $$;
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM remote_assistance_commands
                 WHERE status = 'pending'
                 GROUP BY company_id, session_id
                HAVING count(*) > 1
            ) THEN
                RAISE EXCEPTION '0062 found multiple pending commands for one session'
                    USING ERRCODE = '23514',
                          HINT = 'Resolve retained command evidence before retrying.';
            END IF;
        END
        $$;
        """
    )
    op.create_index(
        "uq_remote_assistance_commands_session_pending",
        "remote_assistance_commands",
        ["company_id", "session_id"],
        unique=True,
        postgresql_where=sa.text("status = 'pending'"),
    )

    # Bind every grant request to the tablet user who was current when the
    # owner asked. Previously decided rows can be bound for retained history to
    # the recorded responder, but no pre-binding requested/active authority is
    # preserved: every open legacy session/grant is terminalized below.
    op.add_column(
        "remote_assistance_grants",
        sa.Column("requested_for_user_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.execute(
        """
        UPDATE remote_assistance_grants
           SET requested_for_user_id = responded_by_user_id
         WHERE responded_by_user_id IS NOT NULL;

        UPDATE remote_assistance_grants AS support_grant
           SET requested_for_user_id = installation.last_user_id
          FROM client_installations AS installation
         WHERE support_grant.requested_for_user_id IS NULL
           AND installation.id = support_grant.client_installation_id
           AND installation.company_id = support_grant.company_id;

        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM remote_assistance_grants
                 WHERE requested_for_user_id IS NULL
            ) THEN
                RAISE EXCEPTION '0062 cannot establish the tablet user for legacy grant evidence'
                    USING ERRCODE = '23514',
                          HINT = 'Review installations with no last_user_id before retrying.';
            END IF;
        END
        $$;

        UPDATE remote_assistance_commands AS remote_command
           SET status = 'rejected',
               resolved_at = CURRENT_TIMESTAMP,
               rejection_reason_code = 'session_ended'
          FROM remote_assistance_sessions AS support_session
         WHERE remote_command.session_id = support_session.id
           AND remote_command.company_id = support_session.company_id
           AND remote_command.status = 'pending'
           AND support_session.status IN ('requested', 'active');

        UPDATE remote_assistance_sessions AS support_session
           SET status = 'expired'
         WHERE support_session.status IN ('requested', 'active');

        UPDATE remote_assistance_grants
           SET status = 'expired'
         WHERE status IN ('requested', 'active');
        """
    )
    op.alter_column(
        "remote_assistance_grants",
        "requested_for_user_id",
        existing_type=postgresql.UUID(as_uuid=True),
        nullable=False,
    )
    op.create_foreign_key(
        "fk_remote_assistance_grants_requested_for_user_id",
        "remote_assistance_grants",
        "users",
        ["requested_for_user_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_check_constraint(
        "ck_remote_assistance_grants_consent_user_binding",
        "remote_assistance_grants",
        "responded_by_user_id IS NULL OR responded_by_user_id = requested_for_user_id",
    )
    op.execute(
        """
        CREATE FUNCTION dcompany_guard_remote_assistance_grant_target_user()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.requested_for_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'remote assistance grant target user crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF TG_OP = 'UPDATE' AND
               NEW.requested_for_user_id IS DISTINCT FROM OLD.requested_for_user_id THEN
                RAISE EXCEPTION 'remote assistance grant target user is immutable'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_remote_assistance_grants_target_user_guard
        BEFORE INSERT OR UPDATE ON remote_assistance_grants
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_remote_assistance_grant_target_user();
        """
    )

    op.create_table(
        "remote_assistance_device_keys",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("public_key_spki", sa.LargeBinary(), nullable=False),
        sa.Column("public_key_fingerprint_sha256", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("enrollment_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("enrolled_by_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("enrolled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("pending_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("approval_id", postgresql.UUID(as_uuid=True)),
        sa.Column("approved_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("approved_at", sa.DateTime(timezone=True)),
        sa.Column("revocation_id", postgresql.UUID(as_uuid=True)),
        sa.Column("revoked_by_user_id", postgresql.UUID(as_uuid=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
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
            "status IN ('pending', 'active', 'revoked', 'expired')",
            name="ck_remote_assistance_device_keys_status",
        ),
        sa.CheckConstraint(
            "octet_length(public_key_spki) BETWEEN 80 AND 160",
            name="ck_remote_assistance_device_keys_spki_length",
        ),
        sa.CheckConstraint(
            "public_key_fingerprint_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_remote_assistance_device_keys_fingerprint_length",
        ),
        sa.CheckConstraint(
            "pending_expires_at > enrolled_at AND "
            "pending_expires_at <= enrolled_at + interval '15 minutes'",
            name="ck_remote_assistance_device_keys_pending_expiry",
        ),
        sa.CheckConstraint(
            "(status IN ('pending', 'expired') AND approved_at IS NULL "
            "AND approved_by_user_id IS NULL AND approval_id IS NULL) OR "
            "(status IN ('active', 'revoked') AND approved_at IS NOT NULL "
            "AND approved_by_user_id IS NOT NULL AND approval_id IS NOT NULL) OR "
            "(status = 'revoked' AND approved_at IS NULL "
            "AND approved_by_user_id IS NULL AND approval_id IS NULL)",
            name="ck_remote_assistance_device_keys_approval_evidence",
        ),
        sa.CheckConstraint(
            "(status <> 'revoked' AND revoked_at IS NULL "
            "AND revoked_by_user_id IS NULL AND revocation_id IS NULL) OR "
            "(status = 'revoked' AND revoked_at IS NOT NULL "
            "AND revoked_by_user_id IS NOT NULL AND revocation_id IS NOT NULL)",
            name="ck_remote_assistance_device_keys_revocation_evidence",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_remote_assistance_device_keys_scoped_installation",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(["enrolled_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["approved_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["revoked_by_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id", "id", name="uq_remote_assistance_device_keys_company_id_id"
        ),
        sa.UniqueConstraint(
            "company_id",
            "enrollment_id",
            name="uq_remote_assistance_device_keys_company_enrollment_id",
        ),
        sa.UniqueConstraint(
            "company_id",
            "approval_id",
            name="uq_remote_assistance_device_keys_company_approval_id",
        ),
        sa.UniqueConstraint(
            "company_id",
            "revocation_id",
            name="uq_remote_assistance_device_keys_company_revocation_id",
        ),
        sa.UniqueConstraint(
            "company_id",
            "public_key_fingerprint_sha256",
            name="uq_remote_assistance_device_keys_company_fingerprint",
        ),
    )
    op.create_index(
        "ix_remote_assistance_device_keys_company_id",
        "remote_assistance_device_keys",
        ["company_id"],
    )
    op.create_index(
        "uq_remote_assistance_device_keys_installation_active",
        "remote_assistance_device_keys",
        ["company_id", "client_installation_id"],
        unique=True,
        postgresql_where=sa.text("status = 'active'"),
    )
    op.create_index(
        "uq_remote_assistance_device_keys_installation_pending",
        "remote_assistance_device_keys",
        ["company_id", "client_installation_id"],
        unique=True,
        postgresql_where=sa.text("status = 'pending'"),
    )
    op.create_index(
        "ix_remote_assistance_device_keys_pending_expiry",
        "remote_assistance_device_keys",
        ["pending_expires_at"],
        postgresql_where=sa.text("status = 'pending'"),
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_remote_assistance_device_key()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'remote assistance device keys are retained audit evidence'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM client_installations ci
                 WHERE ci.id = NEW.client_installation_id
                   AND ci.company_id = NEW.company_id
                   AND ci.platform = 'android'
            ) THEN
                RAISE EXCEPTION 'remote assistance device key crosses company or platform scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.enrolled_by_user_id AND u.company_id = NEW.company_id
            ) OR (NEW.approved_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.approved_by_user_id AND u.company_id = NEW.company_id
            )) OR (NEW.revoked_by_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.revoked_by_user_id AND u.company_id = NEW.company_id
            )) THEN
                RAISE EXCEPTION 'remote assistance device key actor crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF TG_OP = 'UPDATE' THEN
                IF NEW.id IS DISTINCT FROM OLD.id
                   OR NEW.company_id IS DISTINCT FROM OLD.company_id
                   OR NEW.client_installation_id IS DISTINCT FROM OLD.client_installation_id
                   OR NEW.public_key_spki IS DISTINCT FROM OLD.public_key_spki
                   OR NEW.public_key_fingerprint_sha256 IS DISTINCT FROM
                      OLD.public_key_fingerprint_sha256
                   OR NEW.enrollment_id IS DISTINCT FROM OLD.enrollment_id
                   OR NEW.enrolled_by_user_id IS DISTINCT FROM OLD.enrolled_by_user_id
                   OR NEW.enrolled_at IS DISTINCT FROM OLD.enrolled_at
                   OR NEW.pending_expires_at IS DISTINCT FROM OLD.pending_expires_at
                   OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                    RAISE EXCEPTION 'remote assistance device key identity is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF NOT (
                    NEW.status = OLD.status OR
                    (OLD.status = 'pending' AND NEW.status IN ('active','revoked','expired')) OR
                    (OLD.status = 'active' AND NEW.status = 'revoked')
                ) THEN
                    RAISE EXCEPTION 'invalid remote assistance device key transition'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    NEW.approved_at IS DISTINCT FROM OLD.approved_at OR
                    NEW.approved_by_user_id IS DISTINCT FROM OLD.approved_by_user_id OR
                    NEW.approval_id IS DISTINCT FROM OLD.approval_id
                ) AND NOT (OLD.status = 'pending' AND NEW.status = 'active') THEN
                    RAISE EXCEPTION 'remote assistance device key approval is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF (
                    NEW.revoked_at IS DISTINCT FROM OLD.revoked_at OR
                    NEW.revoked_by_user_id IS DISTINCT FROM OLD.revoked_by_user_id OR
                    NEW.revocation_id IS DISTINCT FROM OLD.revocation_id
                ) AND NOT (
                    OLD.status IN ('pending', 'active') AND NEW.status = 'revoked'
                ) THEN
                    RAISE EXCEPTION 'remote assistance device key revocation is immutable'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_remote_assistance_device_keys_guard
        BEFORE INSERT OR UPDATE OR DELETE ON remote_assistance_device_keys
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_remote_assistance_device_key();
        """
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS trg_remote_assistance_grants_target_user_guard "
        "ON remote_assistance_grants"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS dcompany_guard_remote_assistance_grant_target_user()"
    )
    op.drop_constraint(
        "ck_remote_assistance_grants_consent_user_binding",
        "remote_assistance_grants",
        type_="check",
    )
    op.drop_constraint(
        "fk_remote_assistance_grants_requested_for_user_id",
        "remote_assistance_grants",
        type_="foreignkey",
    )
    op.drop_column("remote_assistance_grants", "requested_for_user_id")
    op.drop_index(
        "uq_remote_assistance_commands_session_pending",
        table_name="remote_assistance_commands",
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_remote_assistance_device_keys_guard "
        "ON remote_assistance_device_keys"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_remote_assistance_device_key()")
    op.drop_index(
        "ix_remote_assistance_device_keys_pending_expiry",
        table_name="remote_assistance_device_keys",
    )
    op.drop_index(
        "uq_remote_assistance_device_keys_installation_pending",
        table_name="remote_assistance_device_keys",
    )
    op.drop_index(
        "uq_remote_assistance_device_keys_installation_active",
        table_name="remote_assistance_device_keys",
    )
    op.drop_index(
        "ix_remote_assistance_device_keys_company_id",
        table_name="remote_assistance_device_keys",
    )
    op.drop_table("remote_assistance_device_keys")

    # 0061 carries the same closed command set, so downgrade preserves it.
    op.drop_constraint(
        "ck_remote_assistance_commands_type",
        "remote_assistance_commands",
        type_="check",
    )
    op.drop_constraint(
        "ck_remote_assistance_commands_module",
        "remote_assistance_commands",
        type_="check",
    )
    op.create_check_constraint(
        "ck_remote_assistance_commands_type",
        "remote_assistance_commands",
        "command_type IN ('navigate', 'refresh', 'collect_diagnostics')",
    )
    op.create_check_constraint(
        "ck_remote_assistance_commands_module",
        "remote_assistance_commands",
        "module IS NULL OR module = 'help'",
    )

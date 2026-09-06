"""Single-use refresh-token families and revocable logout sessions.

Revision ID: 0060
Revises: 0059
Create Date: 2026-08-30

Only SHA-256 token digests are retained.  The ledger is tenant/user scoped,
supports atomic rotation and family revocation, and gives refresh JWTs issued
before this migration one bounded exchange into the new scheme.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0060"
down_revision = "0059"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "auth_refresh_sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("family_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("token_hash", sa.String(length=64), nullable=False),
        sa.Column("auth_version", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
        sa.Column("revocation_reason", sa.String(length=32)),
        sa.Column(
            "legacy_exchange",
            sa.Boolean(),
            server_default=sa.text("false"),
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
        sa.CheckConstraint(
            "token_hash ~ '^[0-9a-f]{64}$'",
            name="ck_auth_refresh_sessions_token_hash",
        ),
        sa.CheckConstraint(
            "auth_version >= 0",
            name="ck_auth_refresh_sessions_auth_version",
        ),
        sa.CheckConstraint(
            "(revoked_at IS NULL AND revocation_reason IS NULL) OR "
            "(revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)",
            name="ck_auth_refresh_sessions_revocation_pair",
        ),
        sa.CheckConstraint(
            "revocation_reason IS NULL OR revocation_reason IN ('logout', 'reuse_detected')",
            name="ck_auth_refresh_sessions_revocation_reason",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "token_hash",
            name="uq_auth_refresh_sessions_company_token_hash",
        ),
    )
    op.create_index(
        "ix_auth_refresh_sessions_company_id",
        "auth_refresh_sessions",
        ["company_id"],
    )
    op.create_index(
        "ix_auth_refresh_sessions_company_user_family",
        "auth_refresh_sessions",
        ["company_id", "user_id", "family_id"],
    )
    op.create_index(
        "ix_auth_refresh_sessions_expires_at",
        "auth_refresh_sessions",
        ["expires_at"],
    )
    op.create_index(
        "ix_auth_refresh_sessions_active_family",
        "auth_refresh_sessions",
        ["company_id", "user_id", "family_id"],
        postgresql_where=sa.text("revoked_at IS NULL"),
    )

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_auth_refresh_session()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                  FROM users u
                 WHERE u.id = NEW.user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'refresh session user crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF TG_OP = 'UPDATE' THEN
                IF NEW.company_id IS DISTINCT FROM OLD.company_id
                   OR NEW.user_id IS DISTINCT FROM OLD.user_id
                   OR NEW.family_id IS DISTINCT FROM OLD.family_id
                   OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
                   OR NEW.auth_version IS DISTINCT FROM OLD.auth_version
                   OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
                   OR NEW.legacy_exchange IS DISTINCT FROM OLD.legacy_exchange
                   OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
                    RAISE EXCEPTION 'refresh session identity is immutable'
                        USING ERRCODE = '23514';
                END IF;

                IF OLD.consumed_at IS NOT NULL
                   AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at THEN
                    RAISE EXCEPTION 'refresh session consumption is immutable'
                        USING ERRCODE = '23514';
                END IF;

                IF OLD.revoked_at IS NOT NULL
                   AND (
                       NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
                       OR NEW.revocation_reason IS DISTINCT FROM OLD.revocation_reason
                   ) THEN
                    RAISE EXCEPTION 'refresh session revocation is immutable'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_auth_refresh_sessions_scope_immutable
        BEFORE INSERT OR UPDATE ON auth_refresh_sessions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_auth_refresh_session();
        """
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS trg_auth_refresh_sessions_scope_immutable ON auth_refresh_sessions"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_auth_refresh_session()")
    op.drop_index(
        "ix_auth_refresh_sessions_active_family",
        table_name="auth_refresh_sessions",
    )
    op.drop_index(
        "ix_auth_refresh_sessions_expires_at",
        table_name="auth_refresh_sessions",
    )
    op.drop_index(
        "ix_auth_refresh_sessions_company_user_family",
        table_name="auth_refresh_sessions",
    )
    op.drop_index(
        "ix_auth_refresh_sessions_company_id",
        table_name="auth_refresh_sessions",
    )
    op.drop_table("auth_refresh_sessions")

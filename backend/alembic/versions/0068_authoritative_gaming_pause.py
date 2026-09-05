"""Exact pause clock and durable reasoned transition receipts.

Revision ID: 0068
Revises: 0067
"""

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql
from alembic import op

revision = "0068"
down_revision = "0067"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("paused_duration_ms", sa.BigInteger(), server_default="0", nullable=False),
    )
    op.add_column("gaming_sessions", sa.Column("paused_at", sa.DateTime(timezone=True)))
    op.add_column(
        "gaming_sessions",
        sa.Column("pause_version", sa.Integer(), server_default="0", nullable=False),
    )
    op.add_column(
        "gaming_sessions", sa.Column("last_pause_transition_at", sa.DateTime(timezone=True))
    )
    # Preserve the only known legacy duration. Do not invent the beginning of
    # an existing paused state or fabricate an employee/reason for it.
    op.execute(
        "UPDATE gaming_sessions SET paused_duration_ms = GREATEST(paused_minutes,0)::bigint * 60000"
    )
    op.create_check_constraint(
        "ck_gaming_pause_duration",
        "gaming_sessions",
        "paused_duration_ms >= 0 AND pause_version >= 0",
    )
    op.create_check_constraint(
        "ck_gaming_pause_active_time",
        "gaming_sessions",
        "paused_at IS NULL OR (status = 'paused' AND paused_at >= start_at)",
    )
    op.create_table(
        "gaming_pause_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "gaming_session_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("gaming_sessions.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "actor_user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "terminal_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("action", sa.String(10), nullable=False),
        sa.Column("reason", sa.String(500), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("pause_version", sa.Integer(), nullable=False),
        sa.Column("idempotency_key", sa.String(160), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("response", postgresql.JSONB(), nullable=False),
        sa.UniqueConstraint("company_id", "idempotency_key", name="uq_gaming_pause_event_key"),
        sa.UniqueConstraint(
            "gaming_session_id", "pause_version", name="uq_gaming_pause_event_version"
        ),
        sa.CheckConstraint(
            "action IN ('pause','resume') AND length(trim(reason)) BETWEEN 3 AND 500 AND pause_version > 0",
            name="ck_gaming_pause_event_payload",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0 AND length(request_hash) = 64",
            name="ck_gaming_pause_event_identity",
        ),
    )
    op.create_index(
        "ix_gaming_pause_events_company_session",
        "gaming_pause_events",
        ["company_id", "gaming_session_id"],
    )
    op.execute("""
        CREATE FUNCTION dcompany_guard_gaming_pause_event() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION 'gaming pause event is immutable' USING ERRCODE='23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM gaming_sessions gs
                JOIN shifts sh ON sh.id=gs.shift_id
                JOIN users u ON u.id=NEW.actor_user_id
                WHERE gs.id=NEW.gaming_session_id AND gs.company_id=NEW.company_id
                  AND u.company_id=NEW.company_id AND sh.terminal_id=NEW.terminal_id
                  AND gs.pause_version=NEW.pause_version
            ) THEN
                RAISE EXCEPTION 'gaming pause event scope or version is invalid' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END; $$;
        CREATE TRIGGER trg_gaming_pause_event_guard BEFORE INSERT OR UPDATE OR DELETE
        ON gaming_pause_events FOR EACH ROW EXECUTE FUNCTION dcompany_guard_gaming_pause_event();
    """)


def downgrade() -> None:
    # Once precise history exists, discarding it would change billing/recovery.
    op.execute("""
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM gaming_pause_events)
               OR EXISTS (SELECT 1 FROM gaming_sessions WHERE pause_version > 0 OR paused_at IS NOT NULL) THEN
                RAISE EXCEPTION '0068 rollback requires preserving precise gaming pause history';
            END IF;
        END $$;
    """)
    op.execute("DROP TRIGGER trg_gaming_pause_event_guard ON gaming_pause_events")
    op.execute("DROP FUNCTION dcompany_guard_gaming_pause_event()")
    op.drop_index("ix_gaming_pause_events_company_session", table_name="gaming_pause_events")
    op.drop_table("gaming_pause_events")
    op.drop_constraint("ck_gaming_pause_active_time", "gaming_sessions", type_="check")
    op.drop_constraint("ck_gaming_pause_duration", "gaming_sessions", type_="check")
    for column in ("last_pause_transition_at", "pause_version", "paused_at", "paused_duration_ms"):
        op.drop_column("gaming_sessions", column)

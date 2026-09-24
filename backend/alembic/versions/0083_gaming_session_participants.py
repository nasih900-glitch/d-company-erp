"""Record paid post-Start PS5 participant intervals and settlement.

Revision ID: 0083
Revises: 0082
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0083"
down_revision = "0082"
branch_labels = None
depends_on = None


def _immutable_trigger(table: str) -> None:
    function = f"reject_{table}_mutation"
    op.execute(
        f"""
        CREATE FUNCTION {function}() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION '{table} rows are immutable';
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_{table}_immutable
        BEFORE UPDATE OR DELETE ON {table}
        FOR EACH ROW EXECUTE FUNCTION {function}();
        """
    )


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("participant_revision", sa.Integer(), server_default="0", nullable=False),
    )
    op.create_check_constraint(
        "ck_gaming_sessions_participant_revision",
        "gaming_sessions",
        "participant_revision >= 0",
    )

    op.create_table(
        "gaming_session_participants",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("gaming_session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("customer_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("joined_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("joined_play_elapsed_ms", sa.BigInteger(), nullable=False),
        sa.Column("join_timing_source", sa.String(20), nullable=False),
        sa.Column("joined_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("joined_terminal_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("join_revision", sa.Integer(), nullable=False),
        sa.Column("join_idempotency_key", sa.String(160), nullable=False),
        sa.Column("join_request_hash", sa.String(64), nullable=False),
        sa.Column("join_response", postgresql.JSONB(), nullable=False),
        sa.Column("left_at", sa.DateTime(timezone=True)),
        sa.Column("left_play_elapsed_ms", sa.BigInteger()),
        sa.Column("leave_timing_source", sa.String(20)),
        sa.Column("left_by", postgresql.UUID(as_uuid=True)),
        sa.Column("left_terminal_id", postgresql.UUID(as_uuid=True)),
        sa.Column("leave_revision", sa.Integer()),
        sa.Column("leave_idempotency_key", sa.String(160)),
        sa.Column("leave_request_hash", sa.String(64)),
        sa.Column("leave_response", postgresql.JSONB()),
        sa.CheckConstraint("joined_play_elapsed_ms >= 0 AND (left_play_elapsed_ms IS NULL OR (left_play_elapsed_ms >= joined_play_elapsed_ms AND left_at >= joined_at))", name="ck_gaming_session_participant_meter"),
        sa.CheckConstraint("join_revision > 0 AND (leave_revision IS NULL OR leave_revision > join_revision)", name="ck_gaming_session_participant_revision"),
        sa.CheckConstraint("join_timing_source IN ('server','offline_capture') AND (leave_timing_source IS NULL OR leave_timing_source IN ('server','offline_capture'))", name="ck_gaming_session_participant_timing_source"),
        sa.CheckConstraint("length(trim(join_idempotency_key)) > 0 AND length(join_request_hash) = 64", name="ck_gaming_session_participant_join_receipt"),
        sa.CheckConstraint("(left_at IS NULL AND left_play_elapsed_ms IS NULL AND leave_revision IS NULL AND left_by IS NULL AND left_terminal_id IS NULL AND leave_timing_source IS NULL AND leave_idempotency_key IS NULL AND leave_request_hash IS NULL AND leave_response IS NULL) OR (left_at IS NOT NULL AND left_play_elapsed_ms IS NOT NULL AND leave_revision IS NOT NULL AND left_by IS NOT NULL AND left_terminal_id IS NOT NULL AND leave_timing_source IS NOT NULL AND length(trim(leave_idempotency_key)) > 0 AND length(leave_request_hash) = 64 AND leave_response IS NOT NULL)", name="ck_gaming_session_participant_leave_receipt"),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["gaming_session_id"], ["gaming_sessions.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["customer_id"], ["customers.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["joined_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["joined_terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["left_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["left_terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("company_id", "join_idempotency_key", name="uq_gaming_participant_join_key"),
    )
    op.create_index("ix_gaming_participant_session", "gaming_session_participants", ["company_id", "gaming_session_id"])
    op.create_index("ix_gaming_session_participants_customer_id", "gaming_session_participants", ["customer_id"])
    op.create_index("uq_gaming_participant_leave_key", "gaming_session_participants", ["company_id", "leave_idempotency_key"], unique=True, postgresql_where=sa.text("leave_idempotency_key IS NOT NULL"))
    op.create_index("uq_gaming_participant_open_customer", "gaming_session_participants", ["gaming_session_id", "customer_id"], unique=True, postgresql_where=sa.text("left_at IS NULL"))
    op.execute(
        """
        CREATE FUNCTION validate_gaming_session_participant_scope() RETURNS trigger AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM gaming_sessions s WHERE s.id = NEW.gaming_session_id AND s.company_id = NEW.company_id)
             OR NOT EXISTS (SELECT 1 FROM customers c WHERE c.id = NEW.customer_id AND c.company_id = NEW.company_id)
             OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = NEW.joined_by AND u.company_id = NEW.company_id)
             OR NOT EXISTS (SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id WHERE t.id = NEW.joined_terminal_id AND b.company_id = NEW.company_id)
             OR (NEW.left_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = NEW.left_by AND u.company_id = NEW.company_id))
             OR (NEW.left_terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id WHERE t.id = NEW.left_terminal_id AND b.company_id = NEW.company_id)) THEN
            RAISE EXCEPTION 'gaming participant tenant scope mismatch';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_session_participants_scope
        BEFORE INSERT OR UPDATE ON gaming_session_participants
        FOR EACH ROW EXECUTE FUNCTION validate_gaming_session_participant_scope();

        CREATE FUNCTION guard_gaming_session_participant_mutation() RETURNS trigger AS $$
        BEGIN
          IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'gaming participant evidence cannot be deleted';
          END IF;
          IF OLD.left_at IS NOT NULL
             OR NEW.company_id IS DISTINCT FROM OLD.company_id
             OR NEW.gaming_session_id IS DISTINCT FROM OLD.gaming_session_id
             OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
             OR NEW.joined_at IS DISTINCT FROM OLD.joined_at
             OR NEW.joined_play_elapsed_ms IS DISTINCT FROM OLD.joined_play_elapsed_ms
             OR NEW.join_timing_source IS DISTINCT FROM OLD.join_timing_source
             OR NEW.joined_by IS DISTINCT FROM OLD.joined_by
             OR NEW.joined_terminal_id IS DISTINCT FROM OLD.joined_terminal_id
             OR NEW.join_revision IS DISTINCT FROM OLD.join_revision
             OR NEW.join_idempotency_key IS DISTINCT FROM OLD.join_idempotency_key
             OR NEW.join_request_hash IS DISTINCT FROM OLD.join_request_hash
             OR NEW.join_response IS DISTINCT FROM OLD.join_response
             OR NEW.left_at IS NULL OR NEW.left_play_elapsed_ms IS NULL
             OR NEW.leave_timing_source IS NULL OR NEW.left_by IS NULL
             OR NEW.left_terminal_id IS NULL OR NEW.leave_revision IS NULL
             OR NEW.leave_idempotency_key IS NULL OR NEW.leave_request_hash IS NULL
             OR NEW.leave_response IS NULL THEN
            RAISE EXCEPTION 'gaming participant rows allow only one complete leave transition';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_session_participants_guard
        BEFORE UPDATE OR DELETE ON gaming_session_participants
        FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_participant_mutation();
        """
    )

    op.create_table(
        "gaming_participant_settlements",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("gaming_session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("base_amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("guest_charge_minor", sa.BigInteger(), nullable=False),
        sa.Column("amount_after_minor", sa.BigInteger(), nullable=False),
        sa.Column("final_play_elapsed_ms", sa.BigInteger(), nullable=False),
        sa.Column("participant_revision_before", sa.Integer(), nullable=False),
        sa.Column("participant_revision", sa.Integer(), nullable=False),
        sa.Column("settled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("settled_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("stop_idempotency_key", sa.String(160), nullable=False),
        sa.Column("stop_request_hash", sa.String(64), nullable=False),
        sa.CheckConstraint("base_amount_minor >= 0 AND guest_charge_minor >= 0 AND amount_after_minor = base_amount_minor + guest_charge_minor AND final_play_elapsed_ms >= 0 AND participant_revision_before > 0 AND participant_revision >= participant_revision_before", name="ck_gaming_participant_settlement_amounts"),
        sa.CheckConstraint("length(trim(stop_idempotency_key)) > 0 AND length(stop_request_hash) = 64", name="ck_gaming_participant_settlement_stop_receipt"),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["gaming_session_id"], ["gaming_sessions.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["settled_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("gaming_session_id", name="uq_gaming_participant_settlement_session"),
        sa.UniqueConstraint("company_id", "stop_idempotency_key", name="uq_gaming_participant_settlement_stop_key"),
    )
    op.create_index("ix_gaming_participant_settlements_gaming_session_id", "gaming_participant_settlements", ["gaming_session_id"])

    op.create_table(
        "gaming_participant_settlement_lines",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("settlement_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("gaming_session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("customer_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("play_elapsed_ms", sa.BigInteger(), nullable=False),
        sa.Column("played_minutes", sa.Integer(), nullable=False),
        sa.Column("started_hours", sa.Integer(), nullable=False),
        sa.Column("charge_minor", sa.BigInteger(), nullable=False),
        sa.Column("interval_count", sa.Integer(), nullable=False),
        sa.CheckConstraint(
            "play_elapsed_ms >= 0 AND interval_count >= 1 "
            "AND played_minutes = CASE WHEN play_elapsed_ms = 0 THEN 0 "
            "ELSE (play_elapsed_ms + 59999) / 60000 END "
            "AND started_hours = greatest(1, (play_elapsed_ms + 3599999) / 3600000) "
            "AND charge_minor = started_hours * 3000",
            name="ck_gaming_participant_settlement_line_charge",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["settlement_id"], ["gaming_participant_settlements.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["gaming_session_id"], ["gaming_sessions.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["customer_id"], ["customers.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("settlement_id", "customer_id", name="uq_gaming_participant_settlement_customer"),
    )
    op.create_index("ix_gaming_participant_settlement_lines_settlement_id", "gaming_participant_settlement_lines", ["settlement_id"])
    op.create_index("ix_gaming_participant_settlement_lines_gaming_session_id", "gaming_participant_settlement_lines", ["gaming_session_id"])
    op.create_index("ix_gaming_participant_settlement_lines_customer_id", "gaming_participant_settlement_lines", ["customer_id"])
    op.execute(
        """
        CREATE FUNCTION validate_gaming_participant_settlement_scope() RETURNS trigger AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM gaming_sessions s WHERE s.id=NEW.gaming_session_id AND s.company_id=NEW.company_id)
             OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id=NEW.settled_by AND u.company_id=NEW.company_id)
             OR NOT EXISTS (SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id WHERE t.id=NEW.terminal_id AND b.company_id=NEW.company_id) THEN
            RAISE EXCEPTION 'gaming participant settlement tenant scope mismatch';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_participant_settlements_scope
        BEFORE INSERT ON gaming_participant_settlements
        FOR EACH ROW EXECUTE FUNCTION validate_gaming_participant_settlement_scope();

        CREATE FUNCTION validate_gaming_participant_line_scope() RETURNS trigger AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM gaming_participant_settlements s WHERE s.id=NEW.settlement_id AND s.company_id=NEW.company_id AND s.gaming_session_id=NEW.gaming_session_id)
             OR NOT EXISTS (SELECT 1 FROM customers c WHERE c.id=NEW.customer_id AND c.company_id=NEW.company_id) THEN
            RAISE EXCEPTION 'gaming participant settlement line tenant scope mismatch';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE CONSTRAINT TRIGGER trg_gaming_participant_lines_scope
        AFTER INSERT ON gaming_participant_settlement_lines
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION validate_gaming_participant_line_scope();
        """
    )
    _immutable_trigger("gaming_participant_settlements")
    _immutable_trigger("gaming_participant_settlement_lines")


def downgrade() -> None:
    raise RuntimeError("0083 stores immutable participant billing evidence and cannot be downgraded safely")

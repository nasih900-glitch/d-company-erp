"""Anonymous gaming participants with stable billing identity.

Revision ID: 0088
Revises: 0087
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0088"
down_revision = "0087"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_session_participants",
        sa.Column("participant_identity_id", postgresql.UUID(as_uuid=True)),
    )
    op.add_column(
        "gaming_session_participants",
        sa.Column("guest_name", sa.String(200)),
    )
    op.execute(
        "DROP TRIGGER trg_gaming_session_participants_guard ON gaming_session_participants"
    )
    # Existing customer-backed rows retain their prior cumulative-billing key.
    op.execute(
        "UPDATE gaming_session_participants "
        "SET participant_identity_id = customer_id "
        "WHERE participant_identity_id IS NULL"
    )
    op.alter_column("gaming_session_participants", "participant_identity_id", nullable=False)
    op.alter_column("gaming_session_participants", "customer_id", nullable=True)
    op.drop_index("uq_gaming_participant_open_customer", table_name="gaming_session_participants")
    op.create_index(
        "uq_gaming_participant_open_customer",
        "gaming_session_participants",
        ["gaming_session_id", "customer_id"],
        unique=True,
        postgresql_where=sa.text("left_at IS NULL AND customer_id IS NOT NULL"),
    )
    op.create_index(
        "uq_gaming_participant_open_identity",
        "gaming_session_participants",
        ["gaming_session_id", "participant_identity_id"],
        unique=True,
        postgresql_where=sa.text("left_at IS NULL"),
    )
    op.create_index(
        "ix_gaming_session_participants_participant_identity_id",
        "gaming_session_participants",
        ["participant_identity_id"],
    )

    op.add_column(
        "gaming_participant_settlement_lines",
        sa.Column("participant_identity_id", postgresql.UUID(as_uuid=True)),
    )
    op.execute(
        "DROP TRIGGER trg_gaming_participant_settlement_lines_immutable "
        "ON gaming_participant_settlement_lines"
    )
    op.execute(
        "UPDATE gaming_participant_settlement_lines "
        "SET participant_identity_id = customer_id "
        "WHERE participant_identity_id IS NULL"
    )
    op.alter_column("gaming_participant_settlement_lines", "participant_identity_id", nullable=False)
    op.alter_column("gaming_participant_settlement_lines", "customer_id", nullable=True)
    op.drop_constraint(
        "uq_gaming_participant_settlement_customer",
        "gaming_participant_settlement_lines",
        type_="unique",
    )
    op.create_unique_constraint(
        "uq_gaming_participant_settlement_identity",
        "gaming_participant_settlement_lines",
        ["settlement_id", "participant_identity_id"],
    )
    op.create_index(
        "ix_gaming_participant_settlement_lines_participant_identity_id",
        "gaming_participant_settlement_lines",
        ["participant_identity_id"],
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_gaming_session_participant_scope()
        RETURNS trigger AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM gaming_sessions s WHERE s.id = NEW.gaming_session_id AND s.company_id = NEW.company_id)
             OR (NEW.customer_id IS NOT NULL AND NOT EXISTS (
                  SELECT 1 FROM customers c WHERE c.id = NEW.customer_id AND c.company_id = NEW.company_id))
             OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = NEW.joined_by AND u.company_id = NEW.company_id)
             OR NOT EXISTS (SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id WHERE t.id = NEW.joined_terminal_id AND b.company_id = NEW.company_id)
             OR (NEW.left_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = NEW.left_by AND u.company_id = NEW.company_id))
             OR (NEW.left_terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id WHERE t.id = NEW.left_terminal_id AND b.company_id = NEW.company_id)) THEN
            RAISE EXCEPTION 'gaming participant tenant scope mismatch';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;

        CREATE OR REPLACE FUNCTION guard_gaming_session_participant_mutation()
        RETURNS trigger AS $$
        BEGIN
          IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'gaming participant evidence cannot be deleted';
          END IF;
          IF OLD.left_at IS NOT NULL
             OR NEW.company_id IS DISTINCT FROM OLD.company_id
             OR NEW.gaming_session_id IS DISTINCT FROM OLD.gaming_session_id
             OR NEW.participant_identity_id IS DISTINCT FROM OLD.participant_identity_id
             OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
             OR NEW.guest_name IS DISTINCT FROM OLD.guest_name
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

        CREATE OR REPLACE FUNCTION validate_gaming_participant_line_scope()
        RETURNS trigger AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM gaming_participant_settlements s WHERE s.id=NEW.settlement_id AND s.company_id=NEW.company_id AND s.gaming_session_id=NEW.gaming_session_id)
             OR (NEW.customer_id IS NOT NULL AND NOT EXISTS (
                  SELECT 1 FROM customers c WHERE c.id=NEW.customer_id AND c.company_id=NEW.company_id)) THEN
            RAISE EXCEPTION 'gaming participant settlement line tenant scope mismatch';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_participant_settlement_lines_immutable
        BEFORE UPDATE OR DELETE ON gaming_participant_settlement_lines
        FOR EACH ROW EXECUTE FUNCTION reject_gaming_participant_settlement_lines_mutation();
        """
    )


def downgrade() -> None:
    raise RuntimeError(
        "0088 stores immutable anonymous participant identity and cannot be downgraded safely"
    )

"""Preserve captured shift openings and immutable durable replay identity.

Revision ID: 0069
Revises: 0068
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0069"
down_revision = "0068"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("shifts", sa.Column("opening_action_id", sa.String(160), nullable=True))
    op.add_column("shifts", sa.Column("opening_request_hash", sa.String(64), nullable=True))
    op.add_column(
        "shifts", sa.Column("opening_received_at", sa.DateTime(timezone=True), nullable=True)
    )
    op.add_column(
        "shifts",
        sa.Column(
            "opening_was_offline", sa.Boolean(), nullable=False, server_default=sa.text("false")
        ),
    )
    # Keep migrated rows NULL, then install defaults for every row created
    # after this migration.  Key presence cannot be used as the vintage:
    # ordinary web opens are intentionally unkeyed but still use the new
    # recovery protocol.
    op.add_column(
        "shifts", sa.Column("opening_protocol_revision", sa.SmallInteger(), nullable=True)
    )
    op.add_column(
        "shifts", sa.Column("opening_client_platform", sa.String(20), nullable=True)
    )
    op.add_column(
        "shifts",
        sa.Column(
            "opening_client_installation_id",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.alter_column(
        "shifts", "opening_protocol_revision", server_default=sa.text("1")
    )
    op.alter_column(
        "shifts", "opening_client_platform", server_default=sa.text("'web'")
    )
    op.create_unique_constraint(
        "uq_shift_opening_action", "shifts", ["company_id", "opening_action_id"]
    )
    op.create_check_constraint(
        "ck_shift_opening_receipt",
        "shifts",
        "(opening_action_id IS NULL AND opening_request_hash IS NULL AND NOT opening_was_offline) OR (opening_action_id IS NOT NULL AND opening_request_hash IS NOT NULL AND opening_received_at IS NOT NULL)",
    )
    op.create_check_constraint(
        "ck_shift_capture_not_future",
        "shifts",
        "opening_received_at IS NULL OR opened_at <= opening_received_at",
    )
    op.create_check_constraint(
        "ck_shift_opening_protocol_revision",
        "shifts",
        "opening_protocol_revision IS NULL OR opening_protocol_revision = 1",
    )
    op.create_check_constraint(
        "ck_shift_opening_client_identity",
        "shifts",
        "(opening_protocol_revision IS NULL AND opening_client_platform IS NULL) OR "
        "(opening_protocol_revision = 1 AND opening_client_platform IN ('web', 'android', 'ios') "
        "AND (opening_client_platform <> 'android' OR opening_action_id LIKE 'shift-open:%'))",
    )
    op.create_check_constraint(
        "ck_shift_opening_installation_identity",
        "shifts",
        "opening_client_installation_id IS NULL OR "
        "(opening_protocol_revision = 1 AND opening_client_platform = 'android')",
    )
    op.execute("""
        CREATE FUNCTION dcompany_guard_shift_opening_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF ROW(NEW.opening_action_id, NEW.opening_request_hash, NEW.opening_received_at, NEW.opening_was_offline,
                   NEW.opening_protocol_revision, NEW.opening_client_platform, NEW.opening_client_installation_id)
               IS DISTINCT FROM ROW(OLD.opening_action_id, OLD.opening_request_hash, OLD.opening_received_at, OLD.opening_was_offline,
                   OLD.opening_protocol_revision, OLD.opening_client_platform, OLD.opening_client_installation_id)
               OR (OLD.opening_protocol_revision IS NOT NULL AND ROW(NEW.opened_at, NEW.opened_by, NEW.company_id, NEW.branch_id, NEW.terminal_id, NEW.opening_float_minor)
                   IS DISTINCT FROM ROW(OLD.opened_at, OLD.opened_by, OLD.company_id, OLD.branch_id, OLD.terminal_id, OLD.opening_float_minor)) THEN
                RAISE EXCEPTION 'shift opening receipt is immutable' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END; $$;
        CREATE TRIGGER trg_shift_opening_receipt BEFORE UPDATE ON shifts
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_shift_opening_receipt();
    """)


def downgrade():
    op.execute("""DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM shifts WHERE opening_protocol_revision IS NOT NULL OR opening_action_id IS NOT NULL) THEN
            RAISE EXCEPTION 'new-protocol shift opening exists; downgrade would lose recovery identity';
        END IF;
    END $$;""")
    op.execute(
        "DROP TRIGGER trg_shift_opening_receipt ON shifts; DROP FUNCTION dcompany_guard_shift_opening_receipt();"
    )
    op.drop_constraint("ck_shift_capture_not_future", "shifts")
    op.drop_constraint("ck_shift_opening_installation_identity", "shifts")
    op.drop_constraint("ck_shift_opening_client_identity", "shifts")
    op.drop_constraint("ck_shift_opening_protocol_revision", "shifts")
    op.drop_constraint("ck_shift_opening_receipt", "shifts")
    op.drop_constraint("uq_shift_opening_action", "shifts")
    for column in (
        "opening_client_installation_id",
        "opening_client_platform",
        "opening_protocol_revision",
        "opening_was_offline",
        "opening_received_at",
        "opening_request_hash",
        "opening_action_id",
    ):
        op.drop_column("shifts", column)

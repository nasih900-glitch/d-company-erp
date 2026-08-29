"""Add immutable cashier and Gaming handoff actor attribution.

Revision ID: 0057
Revises: 0056
Create Date: 2026-08-29

Historical rows deliberately remain NULL.  The previous schema did not retain
these facts, so backfilling from the order opener or session starter would
invent audit evidence.  Forward application writes populate the columns from
the authenticated tenant context.  Payments are already append-only; Gaming
actor fields are written only on the corresponding one-way state transition.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0057"
down_revision = "0056"
branch_labels = None
depends_on = None


def upgrade() -> None:
    actor_type = postgresql.UUID(as_uuid=True)
    op.add_column(
        "payments",
        sa.Column("recorded_by", actor_type, nullable=True),
    )
    op.create_foreign_key(
        "fk_payments_recorded_by_users",
        "payments",
        "users",
        ["recorded_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index(
        "ix_payments_recorded_by",
        "payments",
        ["recorded_by"],
    )

    op.add_column(
        "gaming_sessions",
        sa.Column("stopped_by", actor_type, nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("sent_to_pos_by", actor_type, nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("sent_to_pos_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_foreign_key(
        "fk_gaming_sessions_stopped_by_users",
        "gaming_sessions",
        "users",
        ["stopped_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_gaming_sessions_sent_to_pos_by_users",
        "gaming_sessions",
        "users",
        ["sent_to_pos_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index(
        "ix_gaming_sessions_stopped_by",
        "gaming_sessions",
        ["stopped_by"],
    )
    op.create_index(
        "ix_gaming_sessions_sent_to_pos_by",
        "gaming_sessions",
        ["sent_to_pos_by"],
    )

    # Payments are already append-only at the database layer (0048), so only
    # their forward actor/company relationship needs validating here.  NULL is
    # intentionally accepted for pre-0057 rows and legacy import tooling; every
    # normal API payment now supplies recorded_by.
    op.execute(
        """
        CREATE FUNCTION dcompany_validate_payment_recorded_by_scope()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.recorded_by IS NOT NULL AND NOT EXISTS (
                SELECT 1
                FROM orders o
                JOIN users u ON u.id = NEW.recorded_by
                WHERE o.id = NEW.order_id
                  AND u.company_id = o.company_id
            ) THEN
                RAISE EXCEPTION
                    'payment recorded_by must belong to the order company'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_payments_recorded_by_scope
        BEFORE INSERT OR UPDATE OF recorded_by, order_id ON payments
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_validate_payment_recorded_by_scope()
        """
    )

    # A Gaming session is mutable while it progresses, but these two actor
    # facts are one-way provenance.  Enforce both company scope and immutability
    # below the ORM so a bulk SQL update cannot rewrite history.
    op.execute(
        """
        CREATE FUNCTION dcompany_guard_gaming_session_actor_attribution()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'UPDATE' THEN
                IF OLD.stopped_by IS NOT NULL
                   AND NEW.stopped_by IS DISTINCT FROM OLD.stopped_by THEN
                    RAISE EXCEPTION 'gaming session stopped_by is immutable'
                        USING ERRCODE = '23514';
                END IF;
                IF (OLD.sent_to_pos_by IS NOT NULL OR OLD.sent_to_pos_at IS NOT NULL)
                   AND (
                       NEW.sent_to_pos_by IS DISTINCT FROM OLD.sent_to_pos_by
                       OR NEW.sent_to_pos_at IS DISTINCT FROM OLD.sent_to_pos_at
                   ) THEN
                    RAISE EXCEPTION
                        'gaming session POS handoff attribution is immutable'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            IF (NEW.sent_to_pos_by IS NULL) <> (NEW.sent_to_pos_at IS NULL) THEN
                RAISE EXCEPTION
                    'gaming session POS handoff actor and timestamp must be recorded together'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.stopped_by IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                WHERE u.id = NEW.stopped_by
                  AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION
                    'gaming session stopped_by must belong to the session company'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.sent_to_pos_by IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                WHERE u.id = NEW.sent_to_pos_by
                  AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION
                    'gaming session sent_to_pos_by must belong to the session company'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_gaming_sessions_actor_attribution
        BEFORE INSERT OR UPDATE OF stopped_by, sent_to_pos_by, sent_to_pos_at, company_id
        ON gaming_sessions
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_gaming_session_actor_attribution()
        """
    )


def downgrade() -> None:
    bind = op.get_bind()
    attributed_count = int(
        bind.execute(
            sa.text(
                """
                SELECT
                    (SELECT count(*) FROM payments WHERE recorded_by IS NOT NULL)
                  + (SELECT count(*) FROM gaming_sessions WHERE stopped_by IS NOT NULL)
                  + (SELECT count(*) FROM gaming_sessions WHERE sent_to_pos_by IS NOT NULL)
                  + (SELECT count(*) FROM gaming_sessions WHERE sent_to_pos_at IS NOT NULL)
                """
            )
        ).scalar_one()
    )
    if attributed_count:
        raise RuntimeError(
            "Refusing unsafe downgrade from 0057 to 0056: immutable cashier or "
            "Gaming transition attribution exists. Preserve and export this audit "
            "evidence before considering a destructive downgrade."
        )

    op.execute(
        "DROP TRIGGER IF EXISTS trg_gaming_sessions_actor_attribution "
        "ON gaming_sessions"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "dcompany_guard_gaming_session_actor_attribution()"
    )
    op.execute("DROP TRIGGER IF EXISTS trg_payments_recorded_by_scope ON payments")
    op.execute("DROP FUNCTION IF EXISTS dcompany_validate_payment_recorded_by_scope()")

    op.drop_index("ix_gaming_sessions_sent_to_pos_by", table_name="gaming_sessions")
    op.drop_index("ix_gaming_sessions_stopped_by", table_name="gaming_sessions")
    op.drop_constraint(
        "fk_gaming_sessions_sent_to_pos_by_users",
        "gaming_sessions",
        type_="foreignkey",
    )
    op.drop_constraint(
        "fk_gaming_sessions_stopped_by_users",
        "gaming_sessions",
        type_="foreignkey",
    )
    op.drop_column("gaming_sessions", "sent_to_pos_by")
    op.drop_column("gaming_sessions", "sent_to_pos_at")
    op.drop_column("gaming_sessions", "stopped_by")

    op.drop_index("ix_payments_recorded_by", table_name="payments")
    op.drop_constraint(
        "fk_payments_recorded_by_users",
        "payments",
        type_="foreignkey",
    )
    op.drop_column("payments", "recorded_by")

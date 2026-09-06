"""Reject negative shift opening floats at the database boundary.

Revision ID: 0063
Revises: 0062
Create Date: 2026-09-03

The API and current clients reject negative drawer counts. This constraint
keeps direct, stale, or future write paths from creating an impossible cash
starting balance. Existing invalid evidence is never rewritten silently.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0063"
down_revision = "0062"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Official API paths serialize on the terminal row, while this invariant
    # also protects against stale clients, maintenance scripts, and future
    # writers that might otherwise create two accountable drawers.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                 FROM shifts
                 WHERE status = 'open'
                 GROUP BY terminal_id
                HAVING count(*) > 1
            ) THEN
                RAISE EXCEPTION '0063 found more than one open shift for a terminal'
                    USING ERRCODE = '23505',
                          HINT = 'Reconcile duplicate open shifts before retrying.';
            END IF;
        END
        $$;
        """
    )
    op.create_index(
        "uq_shifts_terminal_open",
        "shifts",
        ["terminal_id"],
        unique=True,
        postgresql_where=sa.text("status = 'open'"),
    )

    # NOT VALID takes the table lock once and immediately protects new writes.
    # Validate only after producing a clear operator-facing failure for any
    # retained invalid row; the migration transaction rolls back unchanged.
    op.execute(
        "ALTER TABLE shifts "
        "ADD CONSTRAINT ck_shifts_opening_float_nonnegative "
        "CHECK (opening_float_minor >= 0) NOT VALID"
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM shifts
                 WHERE opening_float_minor < 0
            ) THEN
                RAISE EXCEPTION '0063 found a shift with a negative opening float'
                    USING ERRCODE = '23514',
                          HINT = 'Reconcile invalid shift evidence before retrying.';
            END IF;

            ALTER TABLE shifts
                VALIDATE CONSTRAINT ck_shifts_opening_float_nonnegative;
        END
        $$;
        """
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_shifts_opening_float_nonnegative",
        "shifts",
        type_="check",
    )
    op.drop_index("uq_shifts_terminal_open", table_name="shifts")

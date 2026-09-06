"""Record the authenticated employee who closes each shift.

Revision ID: 0066
Revises: 0065
Create Date: 2026-09-04

Historical closed shifts deliberately remain NULL: the previous schema did
not retain this fact, so copying ``opened_by`` would invent audit evidence.
Every new open-to-closed transition must supply a same-company closer, and a
recorded closer can never be rewritten.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0066"
down_revision = "0065"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "shifts",
        sa.Column(
            "closed_by",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.create_foreign_key(
        "fk_shifts_closed_by_users",
        "shifts",
        "users",
        ["closed_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index("ix_shifts_closed_by", "shifts", ["closed_by"])

    op.execute(
        """
        CREATE FUNCTION dcompany_guard_shift_closer_attribution()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                IF NEW.status IS NOT DISTINCT FROM 'open'
                   AND NEW.closed_by IS NOT NULL THEN
                    RAISE EXCEPTION 'open shift cannot have closed_by attribution'
                        USING ERRCODE = '23514';
                END IF;

                IF NEW.status IS DISTINCT FROM 'open'
                   AND NEW.closed_by IS NULL THEN
                    RAISE EXCEPTION 'closing a shift requires closed_by attribution'
                        USING ERRCODE = '23514';
                END IF;
            ELSE
                IF OLD.status IS DISTINCT FROM 'open'
                   AND NEW.status IS NOT DISTINCT FROM 'open' THEN
                    RAISE EXCEPTION 'closed shift cannot be reopened'
                        USING ERRCODE = '23514';
                END IF;

                IF NEW.closed_by IS DISTINCT FROM OLD.closed_by THEN
                    -- The only legitimate write is NULL -> actor on the first
                    -- transition away from open. This also prevents inventing a
                    -- closer later for pre-0066 historical rows.
                    IF OLD.closed_by IS NOT NULL
                       OR OLD.status IS DISTINCT FROM 'open'
                       OR NEW.status IS NOT DISTINCT FROM 'open' THEN
                        RAISE EXCEPTION 'shift closed_by is immutable'
                            USING ERRCODE = '23514';
                    END IF;
                END IF;

                IF OLD.status = 'open'
                   AND NEW.status IS DISTINCT FROM 'open'
                   AND NEW.closed_by IS NULL THEN
                    RAISE EXCEPTION 'closing a shift requires closed_by attribution'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            IF NEW.closed_by IS NOT NULL AND NOT EXISTS (
                SELECT 1
                  FROM users u
                 WHERE u.id = NEW.closed_by
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'shift closed_by must belong to the shift company'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_shifts_closer_attribution
        BEFORE INSERT OR UPDATE OF closed_by, status, company_id ON shifts
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_shift_closer_attribution()
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS trg_shifts_closer_attribution ON shifts")
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_shift_closer_attribution()")
    op.drop_index("ix_shifts_closed_by", table_name="shifts")
    op.drop_constraint("fk_shifts_closed_by_users", "shifts", type_="foreignkey")
    op.drop_column("shifts", "closed_by")

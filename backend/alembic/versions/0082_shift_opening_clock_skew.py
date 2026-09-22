"""Allow bounded clock skew for captured shift openings.

Revision ID: 0082
Revises: 0081
"""

from __future__ import annotations

from alembic import op

revision = "0082"
down_revision = "0081"
branch_labels = None
depends_on = None


_BOUNDED_CAPTURE_CHECK = (
    "opening_received_at IS NULL OR "
    "opened_at <= opening_received_at + INTERVAL '1 second'"
)
_STRICT_CAPTURE_CHECK = "opening_received_at IS NULL OR opened_at <= opening_received_at"


def upgrade() -> None:
    op.drop_constraint(
        "ck_shift_capture_not_future",
        "shifts",
        type_="check",
    )
    op.create_check_constraint(
        "ck_shift_capture_not_future",
        "shifts",
        _BOUNDED_CAPTURE_CHECK,
    )


def downgrade() -> None:
    # A row accepted by 0082 cannot satisfy the earlier exact-clock contract.
    # Refuse to lie about the schema or rewrite immutable opening evidence.
    op.execute("LOCK TABLE shifts IN ACCESS EXCLUSIVE MODE")
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM shifts
                 WHERE opening_received_at IS NOT NULL
                   AND opened_at > opening_received_at
            ) THEN
                RAISE EXCEPTION
                    '0082 downgrade refused: a shift uses the bounded clock-skew allowance';
            END IF;
        END
        $$;
        """
    )
    op.drop_constraint(
        "ck_shift_capture_not_future",
        "shifts",
        type_="check",
    )
    op.create_check_constraint(
        "ck_shift_capture_not_future",
        "shifts",
        _STRICT_CAPTURE_CHECK,
    )

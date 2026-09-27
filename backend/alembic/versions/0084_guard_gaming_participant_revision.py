"""Prevent gaming participant revision rollback.

Revision ID: 0084
Revises: 0083
"""

from __future__ import annotations

from alembic import op

revision = "0084"
down_revision = "0083"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # CREATE OR REPLACE plus DROP IF EXISTS also repairs local pre-release
    # databases where this trigger was briefly added to the already-stamped
    # 0083 migration. Released 0083 databases do not have either object.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION guard_gaming_session_participant_revision()
        RETURNS trigger AS $$
        BEGIN
          IF NEW.participant_revision < OLD.participant_revision THEN
            RAISE EXCEPTION 'gaming session participant revision cannot decrease';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;

        DROP TRIGGER IF EXISTS trg_gaming_sessions_participant_revision
        ON gaming_sessions;
        CREATE TRIGGER trg_gaming_sessions_participant_revision
        BEFORE UPDATE OF participant_revision ON gaming_sessions
        FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_participant_revision();
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DROP TRIGGER IF EXISTS trg_gaming_sessions_participant_revision
        ON gaming_sessions;
        DROP FUNCTION IF EXISTS guard_gaming_session_participant_revision();
        """
    )

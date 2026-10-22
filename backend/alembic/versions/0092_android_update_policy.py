"""Protected global Android update policy; no business or tablet queue changes.

Revision ID: 0092
Revises: 0091
"""

from alembic import op

revision = "0092"
down_revision = "0091"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE android_update_policy (
            id integer PRIMARY KEY CHECK (id = 1),
            mode varchar(20) NOT NULL CHECK (mode IN ('optional', 'required')),
            revision integer NOT NULL CHECK (revision BETWEEN 1 AND 2147483647),
            release_id uuid REFERENCES android_releases(id) ON DELETE RESTRICT,
            release_fingerprint varchar(64),
            reason varchar(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 3 AND 500),
            updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT ck_android_update_policy_target CHECK (
                (mode = 'optional' AND release_id IS NULL AND release_fingerprint IS NULL) OR
                (mode = 'required' AND release_id IS NOT NULL AND
                    release_fingerprint IS NOT NULL AND release_fingerprint ~ '^[a-f0-9]{64}$')
            )
        )
    """)
    # Deliberately do not seed: absence preserves the deployed environment policy.


def downgrade() -> None:
    # A concurrent owner decision must finish before the emptiness check. Even
    # Optional rows retain a revision high-water mark; silently dropping one
    # could resurrect an older Required decision on installed clients.
    op.execute("LOCK TABLE android_update_policy IN ACCESS EXCLUSIVE MODE")
    op.execute("""
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM android_update_policy) THEN
                RAISE EXCEPTION 'cannot discard Android update policy; %',
                    'retain revision-aware readers';
            END IF;
        END $$
    """)
    op.drop_table("android_update_policy")

"""Expand the bounded per-user client installation capacity.

Revision ID: 0089
Revises: 0088
"""

from __future__ import annotations

from alembic import op

revision = "0089"
down_revision = "0088"
branch_labels = None
depends_on = None


def _replace_scope_guard(user_limit: int) -> None:
    if user_limit not in {8, 16}:
        raise ValueError("unsupported client installation user limit")
    scope_guard_sql = """
        CREATE OR REPLACE FUNCTION dcompany_validate_client_installation_scope()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                PERFORM pg_advisory_xact_lock(
                    hashtextextended('dcompany-client-telemetry:' || NEW.company_id::text, 0)
                );
                -- INSERT .. ON CONFLICT invokes BEFORE INSERT triggers even for
                -- a heartbeat that will become an UPDATE. Exact replays must
                -- remain usable after the admission ceiling is reached.
                IF NOT EXISTS (
                    SELECT 1 FROM client_installations ci
                     WHERE ci.company_id = NEW.company_id
                       AND ci.installation_id = NEW.installation_id
                ) THEN
                    IF (
                        SELECT count(*) FROM client_installations ci
                         WHERE ci.company_id = NEW.company_id
                    ) >= 32 THEN
                        RAISE EXCEPTION 'client installation company capacity reached'
                            USING ERRCODE = '23514';
                    END IF;
                    IF (
                        SELECT count(*) FROM client_installations ci
                         WHERE ci.company_id = NEW.company_id
                           AND ci.registered_by_user_id = NEW.registered_by_user_id
                    ) >= __USER_LIMIT__ THEN
                        RAISE EXCEPTION 'client installation user capacity reached'
                            USING ERRCODE = '23514';
                    END IF;
                END IF;
            END IF;

            IF TG_OP = 'UPDATE' AND (
                NEW.id IS DISTINCT FROM OLD.id
                OR NEW.company_id IS DISTINCT FROM OLD.company_id
                OR NEW.installation_id IS DISTINCT FROM OLD.installation_id
                OR NEW.registered_by_user_id IS DISTINCT FROM OLD.registered_by_user_id
                OR NEW.created_at IS DISTINCT FROM OLD.created_at
            ) THEN
                RAISE EXCEPTION 'client installation tenant identity is immutable'
                    USING ERRCODE = '23514';
            END IF;

            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.registered_by_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client installation registering user crosses company scope'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.last_user_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.last_user_id
                   AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client installation user crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.terminal_id IS NOT NULL AND NOT EXISTS (
                SELECT 1
                  FROM terminals t
                  JOIN branches b ON b.id = t.branch_id
                 WHERE t.id = NEW.terminal_id
                   AND b.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'client installation terminal crosses company scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.last_successful_sync_at > now() + interval '24 hours' THEN
                RAISE EXCEPTION 'client installation sync time is implausibly in the future'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$;
        """.replace("__USER_LIMIT__", str(user_limit))
    op.execute(scope_guard_sql)


def upgrade() -> None:
    _replace_scope_guard(16)


def downgrade() -> None:
    # Existing rows remain intact and exact installation heartbeats still update;
    # only admission of additional IDs returns to the prior bounded ceiling.
    _replace_scope_guard(8)

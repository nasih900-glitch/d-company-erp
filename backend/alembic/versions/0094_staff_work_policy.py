"""Add empty versioned staff work/draft-pay policies; preserve existing history.

Revision ID: 0094
Revises: 0093
"""

from alembic import op

revision = "0094"
down_revision = "0093"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE staff_work_policies (
            id uuid PRIMARY KEY,
            company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
            user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            revision integer NOT NULL,
            effective_from date NOT NULL,
            monthly_salary_minor bigint NOT NULL,
            daily_presence_minutes integer NOT NULL,
            daily_break_minutes integer NOT NULL,
            working_weekday_mask integer NOT NULL,
            overtime_multiplier_basis_points integer,
            pay_status varchar(16) NOT NULL DEFAULT 'draft',
            reason varchar(500) NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT uq_staff_work_policy_revision UNIQUE (company_id, user_id, revision),
            CONSTRAINT ck_staff_work_policy_revision CHECK (revision > 0),
            CONSTRAINT ck_staff_work_policy_salary CHECK (
                monthly_salary_minor BETWEEN 0 AND 1000000000000
            ),
            CONSTRAINT ck_staff_work_policy_hours CHECK (
                daily_presence_minutes BETWEEN 1 AND 1440
                AND daily_break_minutes >= 0 AND daily_break_minutes < daily_presence_minutes
            ),
            CONSTRAINT ck_staff_work_policy_weekdays CHECK (working_weekday_mask BETWEEN 1 AND 127),
            CONSTRAINT ck_staff_work_policy_draft_only CHECK (pay_status = 'draft'),
            CONSTRAINT ck_staff_work_policy_overtime CHECK (
                overtime_multiplier_basis_points IS NULL
                OR overtime_multiplier_basis_points BETWEEN 10000 AND 40000
            ),
            CONSTRAINT ck_staff_work_policy_reason CHECK (length(trim(reason)) BETWEEN 1 AND 500)
        );
        CREATE INDEX ix_staff_work_policies_company_id ON staff_work_policies(company_id);
        CREATE INDEX ix_staff_work_policies_user_id ON staff_work_policies(user_id);

        CREATE FUNCTION guard_staff_work_policy()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION 'staff work policy revisions are immutable' USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u WHERE u.id = NEW.user_id AND u.company_id = NEW.company_id
            ) OR NOT EXISTS (
                SELECT 1 FROM users u WHERE u.id = NEW.actor_user_id
                    AND u.company_id = NEW.company_id
            ) THEN
                RAISE EXCEPTION 'staff work policy tenant scope is invalid' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_staff_work_policy_guard
            BEFORE INSERT OR UPDATE OR DELETE ON staff_work_policies
            FOR EACH ROW EXECUTE FUNCTION guard_staff_work_policy();
        CREATE TRIGGER trg_staff_work_policy_no_truncate
            BEFORE TRUNCATE ON staff_work_policies
            FOR EACH STATEMENT EXECUTE FUNCTION guard_staff_work_policy();
    """)


def downgrade() -> None:
    op.execute("""
        LOCK TABLE staff_work_policies IN ACCESS EXCLUSIVE MODE;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM staff_work_policies) THEN
                RAISE EXCEPTION 'cannot drop retained staff work policy history';
            END IF;
        END $$;
        DROP TABLE staff_work_policies;
        DROP FUNCTION guard_staff_work_policy();
    """)

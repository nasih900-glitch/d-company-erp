"""Enforce staff tenant scope and one open attendance row.

Revision ID: 0047
Revises: 0046
Create Date: 2026-08-27

``user_roles`` historically referenced users, roles, branches, and grantors
independently.  Every foreign key could therefore be valid while the rows
belonged to different companies.  The application now validates the same
scope at login, and these triggers make the invariant unavoidable for scripts,
future endpoints, and direct maintenance SQL.

Attendance carries an explicit company alongside user and branch foreign keys;
that denormalized scope is now checked too.  A partial unique index is the
database backstop for the API's per-user row lock, so only one clock-in can be
open for a company/user pair.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0047"
down_revision = "0046"
branch_labels = None
depends_on = None


_USER_ROLE_CORRUPTION = """
SELECT 1
  FROM user_roles assignment
  LEFT JOIN users target_user ON target_user.id = assignment.user_id
  LEFT JOIN roles target_role ON target_role.id = assignment.role_id
  LEFT JOIN branches target_branch ON target_branch.id = assignment.branch_id
  LEFT JOIN users grantor ON grantor.id = assignment.granted_by
 WHERE target_user.id IS NULL
    OR target_role.id IS NULL
    OR target_user.company_id IS DISTINCT FROM target_role.company_id
    OR (
        assignment.branch_id IS NOT NULL
        AND (
            target_branch.id IS NULL
            OR target_branch.company_id IS DISTINCT FROM target_user.company_id
        )
    )
    OR (
        assignment.granted_by IS NOT NULL
        AND (
            grantor.id IS NULL
            OR grantor.company_id IS DISTINCT FROM target_user.company_id
        )
    )
"""

_ATTENDANCE_CORRUPTION = """
SELECT 1
  FROM attendance record
  LEFT JOIN users staff_user ON staff_user.id = record.user_id
  LEFT JOIN branches work_branch ON work_branch.id = record.branch_id
 WHERE staff_user.id IS NULL
    OR work_branch.id IS NULL
    OR record.company_id IS DISTINCT FROM staff_user.company_id
    OR record.company_id IS DISTINCT FROM work_branch.company_id
"""


def _assert_existing_rows_are_safe() -> None:
    op.execute(
        f"""
        DO $$
        BEGIN
            IF EXISTS ({_USER_ROLE_CORRUPTION}) THEN
                RAISE EXCEPTION
                    'Cannot enforce staff tenant integrity: user role scope is corrupt'
                    USING HINT =
                        'Reconcile the target user, role, optional branch, and grantor '
                        'to one company; do not auto-reassign historical access.';
            END IF;

            IF EXISTS ({_ATTENDANCE_CORRUPTION}) THEN
                RAISE EXCEPTION
                    'Cannot enforce staff tenant integrity: attendance scope is corrupt'
                    USING HINT =
                        'Reconcile attendance company, user, and branch without deleting history.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM attendance
                 WHERE clock_out_at IS NULL
                 GROUP BY company_id, user_id
                HAVING COUNT(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'Cannot enforce staff tenant integrity: duplicate open attendance exists'
                    USING HINT =
                        'Reconcile the duplicate clock-ins explicitly; '
                        'do not discard staff history.';
            END IF;
        END
        $$;
        """  # noqa: S608 -- interpolates only static migration SQL constants
    )


def upgrade() -> None:
    _assert_existing_rows_are_safe()

    # Moving any of these parent rows to another company would invalidate
    # child-scope guarantees without firing a child-table trigger. Tenant
    # ownership is identity, not editable profile data, so freeze it at the
    # database boundary.
    op.execute(
        """
        CREATE FUNCTION prevent_staff_scope_parent_company_change()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
                RAISE EXCEPTION '% company ownership is immutable', TG_TABLE_NAME
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_users_company_immutable
        BEFORE UPDATE OF company_id ON users
        FOR EACH ROW EXECUTE FUNCTION prevent_staff_scope_parent_company_change();

        CREATE TRIGGER trg_roles_company_immutable
        BEFORE UPDATE OF company_id ON roles
        FOR EACH ROW EXECUTE FUNCTION prevent_staff_scope_parent_company_change();

        CREATE TRIGGER trg_branches_company_immutable
        BEFORE UPDATE OF company_id ON branches
        FOR EACH ROW EXECUTE FUNCTION prevent_staff_scope_parent_company_change();
        """
    )

    op.execute(
        """
        CREATE FUNCTION enforce_user_role_tenant_scope()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            user_company uuid;
            role_company uuid;
            branch_company uuid;
            grantor_company uuid;
        BEGIN
            SELECT company_id INTO user_company FROM users WHERE id = NEW.user_id;
            SELECT company_id INTO role_company FROM roles WHERE id = NEW.role_id;

            IF user_company IS NULL
               OR role_company IS NULL
               OR user_company IS DISTINCT FROM role_company
            THEN
                RAISE EXCEPTION 'user role must belong to the target user company'
                    USING ERRCODE = '23514';
            END IF;

            IF NEW.branch_id IS NOT NULL THEN
                SELECT company_id
                  INTO branch_company
                  FROM branches
                 WHERE id = NEW.branch_id;
                IF branch_company IS NULL
                   OR branch_company IS DISTINCT FROM user_company
                THEN
                    RAISE EXCEPTION 'user role branch must belong to the target user company'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            IF NEW.granted_by IS NOT NULL THEN
                SELECT company_id
                  INTO grantor_company
                  FROM users
                 WHERE id = NEW.granted_by;
                IF grantor_company IS NULL
                   OR grantor_company IS DISTINCT FROM user_company
                THEN
                    RAISE EXCEPTION 'role grantor must belong to the target user company'
                        USING ERRCODE = '23514';
                END IF;
            END IF;

            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_enforce_user_role_tenant_scope
        BEFORE INSERT OR UPDATE OF user_id, role_id, branch_id, granted_by
        ON user_roles
        FOR EACH ROW
        EXECUTE FUNCTION enforce_user_role_tenant_scope();
        """
    )

    # Role membership is embedded in access and refresh tokens. Every mutation
    # invalidates the target user's claims for every writer, including direct
    # SQL. Initial assignment consumes a version too; ORM callers that retain a
    # new User instance must refresh its trigger-managed auth_version.
    op.execute(
        """
        CREATE FUNCTION invalidate_user_auth_on_role_change()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                UPDATE users
                   SET auth_version = auth_version + 1
                 WHERE id = NEW.user_id;
                RETURN NEW;
            ELSIF TG_OP = 'DELETE' THEN
                UPDATE users
                   SET auth_version = auth_version + 1
                 WHERE id = OLD.user_id;
                RETURN OLD;
            END IF;

            IF OLD.user_id IS DISTINCT FROM NEW.user_id THEN
                UPDATE users
                   SET auth_version = auth_version + 1
                 WHERE id IN (OLD.user_id, NEW.user_id);
            ELSIF OLD.role_id IS DISTINCT FROM NEW.role_id
               OR OLD.branch_id IS DISTINCT FROM NEW.branch_id
            THEN
                UPDATE users
                   SET auth_version = auth_version + 1
                 WHERE id = NEW.user_id;
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_invalidate_user_auth_on_role_change
        AFTER INSERT OR DELETE OR UPDATE OF user_id, role_id, branch_id
        ON user_roles
        FOR EACH ROW
        EXECUTE FUNCTION invalidate_user_auth_on_role_change();
        """
    )

    # A role code selects the code-defined permission set. Renaming an
    # assigned role in place would silently alter every user's authority
    # without touching user_roles, so fail closed and require explicit role
    # reassignment instead.
    op.execute(
        """
        CREATE FUNCTION prevent_assigned_role_code_change()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF NEW.code IS DISTINCT FROM OLD.code
               AND EXISTS (SELECT 1 FROM user_roles WHERE role_id = OLD.id)
            THEN
                RAISE EXCEPTION 'assigned role code is immutable'
                    USING ERRCODE = '23514',
                          HINT = 'Reassign users explicitly before changing role identity.';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_prevent_assigned_role_code_change
        BEFORE UPDATE OF code ON roles
        FOR EACH ROW
        EXECUTE FUNCTION prevent_assigned_role_code_change();
        """
    )

    op.execute(
        """
        CREATE FUNCTION enforce_attendance_tenant_scope()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            user_company uuid;
            branch_company uuid;
        BEGIN
            SELECT company_id INTO user_company FROM users WHERE id = NEW.user_id;
            SELECT company_id INTO branch_company FROM branches WHERE id = NEW.branch_id;

            IF user_company IS NULL
               OR branch_company IS NULL
               OR NEW.company_id IS DISTINCT FROM user_company
               OR NEW.company_id IS DISTINCT FROM branch_company
            THEN
                RAISE EXCEPTION
                    'attendance company, user, and branch must belong to one company'
                    USING ERRCODE = '23514';
            END IF;

            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_enforce_attendance_tenant_scope
        BEFORE INSERT OR UPDATE OF company_id, user_id, branch_id
        ON attendance
        FOR EACH ROW
        EXECUTE FUNCTION enforce_attendance_tenant_scope();
        """
    )

    op.create_index(
        "uq_attendance_one_open_per_company_user",
        "attendance",
        ["company_id", "user_id"],
        unique=True,
        postgresql_where=sa.text("clock_out_at IS NULL"),
    )


def downgrade() -> None:
    # No forward-only data is stored by this revision, so removing the guards
    # does not erase evidence. Recheck the invariants first and refuse a
    # downgrade if constraints were bypassed by maintenance tooling.
    _assert_existing_rows_are_safe()

    op.drop_index(
        "uq_attendance_one_open_per_company_user",
        table_name="attendance",
    )
    op.execute(
        "DROP TRIGGER trg_invalidate_user_auth_on_role_change ON user_roles"
    )
    op.execute("DROP FUNCTION invalidate_user_auth_on_role_change()")
    op.execute("DROP TRIGGER trg_prevent_assigned_role_code_change ON roles")
    op.execute("DROP FUNCTION prevent_assigned_role_code_change()")
    op.execute("DROP TRIGGER trg_enforce_attendance_tenant_scope ON attendance")
    op.execute("DROP FUNCTION enforce_attendance_tenant_scope()")
    op.execute("DROP TRIGGER trg_enforce_user_role_tenant_scope ON user_roles")
    op.execute("DROP FUNCTION enforce_user_role_tenant_scope()")
    op.execute("DROP TRIGGER trg_branches_company_immutable ON branches")
    op.execute("DROP TRIGGER trg_roles_company_immutable ON roles")
    op.execute("DROP TRIGGER trg_users_company_immutable ON users")
    op.execute("DROP FUNCTION prevent_staff_scope_parent_company_change()")

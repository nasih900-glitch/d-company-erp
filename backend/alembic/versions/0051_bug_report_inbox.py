"""Add the tenant-scoped bug-report support inbox.

Revision ID: 0051
Revises: 0050
Create Date: 2026-08-28

Reports retain their immutable reporter and client context while protected
administrators may move them through a constrained support lifecycle. A
database trigger duplicates the application invariants so native SQL and ORM
bulk updates cannot rewrite submission evidence or cross tenant boundaries.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0051"
down_revision = "0050"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "bug_reports",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("reporter_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("reporter_name", sa.String(length=200), nullable=False),
        sa.Column("reporter_email", sa.String(length=254), nullable=False),
        sa.Column("category", sa.String(length=30), nullable=False),
        sa.Column("severity", sa.String(length=20), nullable=False),
        sa.Column("title", sa.String(length=160), nullable=False),
        sa.Column("description", sa.String(length=4000), nullable=False),
        sa.Column("reproduction_steps", sa.String(length=4000), nullable=True),
        sa.Column("expected_behavior", sa.String(length=4000), nullable=True),
        sa.Column("actual_behavior", sa.String(length=4000), nullable=True),
        sa.Column("client_platform", sa.String(length=20), nullable=False),
        sa.Column("app_version", sa.String(length=40), nullable=True),
        sa.Column("version_code", sa.Integer(), nullable=True),
        sa.Column("device_model", sa.String(length=160), nullable=True),
        sa.Column("os_version", sa.String(length=100), nullable=True),
        sa.Column("current_screen", sa.String(length=100), nullable=True),
        sa.Column("branch_id", postgresql.UUID(as_uuid=True), nullable=True),
        # Matches branches.name; the server snapshots the canonical name when
        # a validated branch_id is present.
        sa.Column("branch_name", sa.String(length=200), nullable=True),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("terminal_name", sa.String(length=160), nullable=True),
        sa.Column(
            "connectivity",
            sa.String(length=20),
            server_default="unknown",
            nullable=False,
        ),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("status", sa.String(length=20), server_default="open", nullable=False),
        sa.Column("internal_resolution_note", sa.String(length=4000), nullable=True),
        sa.Column("status_changed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("status_changed_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("resolved_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "category IN ('crash', 'incorrect_data', 'payment', 'sync', "
            "'permission', 'performance', 'usability', 'other')",
            name="ck_bug_reports_category",
        ),
        sa.CheckConstraint(
            "severity IN ('low', 'medium', 'high', 'critical')",
            name="ck_bug_reports_severity",
        ),
        sa.CheckConstraint(
            "connectivity IN ('online', 'offline', 'unknown')",
            name="ck_bug_reports_connectivity",
        ),
        sa.CheckConstraint(
            "status IN ('open', 'acknowledged', 'in_progress', 'resolved', 'closed', 'rejected')",
            name="ck_bug_reports_status",
        ),
        sa.CheckConstraint("length(trim(title)) >= 5", name="ck_bug_reports_title_present"),
        sa.CheckConstraint(
            "length(trim(description)) >= 10",
            name="ck_bug_reports_description_present",
        ),
        sa.CheckConstraint(
            "length(trim(client_platform)) > 0",
            name="ck_bug_reports_platform_present",
        ),
        sa.CheckConstraint(
            "version_code IS NULL OR version_code >= 1",
            name="ck_bug_reports_version_code_positive",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["reporter_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["branch_id"], ["branches.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["status_changed_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["resolved_by"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_bug_reports_company_id", "bug_reports", ["company_id"])
    op.create_index("ix_bug_reports_reporter_user_id", "bug_reports", ["reporter_user_id"])
    op.create_index("ix_bug_reports_branch_id", "bug_reports", ["branch_id"])
    op.create_index("ix_bug_reports_terminal_id", "bug_reports", ["terminal_id"])
    op.create_index("ix_bug_reports_status", "bug_reports", ["status"])
    op.create_index(
        "ix_bug_reports_company_status_created",
        "bug_reports",
        ["company_id", "status", "created_at"],
    )
    op.create_index(
        "ix_bug_reports_company_reporter_created",
        "bug_reports",
        ["company_id", "reporter_user_id", "created_at"],
    )
    op.create_index(
        "ix_bug_reports_company_severity_created",
        "bug_reports",
        ["company_id", "severity", "created_at"],
    )

    op.execute(
        """
        CREATE FUNCTION enforce_bug_report_integrity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            reporter_company uuid;
            canonical_reporter_name text;
            canonical_reporter_email text;
            branch_company uuid;
            canonical_branch_name text;
            terminal_company uuid;
            terminal_branch uuid;
            canonical_terminal_name text;
            actor_company uuid;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION
                    'bug reports are durable support evidence and cannot be deleted';
            ELSIF TG_OP = 'INSERT' THEN
                SELECT company_id, name, email
                  INTO reporter_company, canonical_reporter_name, canonical_reporter_email
                  FROM users
                 WHERE id = NEW.reporter_user_id
                   AND status = 'active'
                   AND deleted_at IS NULL;
                IF reporter_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION
                        'bug report reporter must be an active user in the same company';
                END IF;
                IF NEW.reporter_name IS DISTINCT FROM canonical_reporter_name
                   OR NEW.reporter_email IS DISTINCT FROM canonical_reporter_email THEN
                    RAISE EXCEPTION
                        'bug report reporter snapshots must match the authenticated user';
                END IF;

                IF NEW.status IS DISTINCT FROM 'open'
                   OR NEW.internal_resolution_note IS NOT NULL
                   OR NEW.resolved_at IS NOT NULL
                   OR NEW.resolved_by IS NOT NULL THEN
                    RAISE EXCEPTION 'new bug reports must start open and unresolved';
                END IF;
                IF NEW.status_changed_by IS DISTINCT FROM NEW.reporter_user_id
                   OR NEW.status_changed_at IS NULL THEN
                    RAISE EXCEPTION
                        'new bug report status provenance must identify its reporter';
                END IF;

                -- Resolve and freeze canonical operational context only when the
                -- report is submitted. Branch and terminal labels may later be
                -- renamed (or the branch retired); the report must retain its
                -- immutable historical snapshots during lifecycle updates.
                IF NEW.branch_id IS NOT NULL THEN
                    SELECT company_id, name
                      INTO branch_company, canonical_branch_name
                      FROM branches
                     WHERE id = NEW.branch_id
                       AND deleted_at IS NULL;
                    IF branch_company IS DISTINCT FROM NEW.company_id THEN
                        RAISE EXCEPTION 'bug report branch must belong to the same company';
                    END IF;
                    IF NEW.branch_name IS DISTINCT FROM canonical_branch_name THEN
                        RAISE EXCEPTION
                            'bug report branch snapshot must match the canonical branch';
                    END IF;
                END IF;

                IF NEW.terminal_id IS NOT NULL THEN
                    SELECT branch.company_id, terminal.branch_id, terminal.name
                      INTO terminal_company, terminal_branch, canonical_terminal_name
                      FROM terminals terminal
                      JOIN branches branch ON branch.id = terminal.branch_id
                     WHERE terminal.id = NEW.terminal_id
                       AND branch.deleted_at IS NULL;
                    IF terminal_company IS DISTINCT FROM NEW.company_id
                       OR NEW.branch_id IS NULL
                       OR terminal_branch IS DISTINCT FROM NEW.branch_id THEN
                        RAISE EXCEPTION
                            'bug report terminal must belong to its company and branch';
                    END IF;
                    IF NEW.terminal_name IS DISTINCT FROM canonical_terminal_name THEN
                        RAISE EXCEPTION
                            'bug report terminal snapshot must match the canonical terminal';
                    END IF;
                END IF;
            ELSE
                IF ROW(
                    NEW.id, NEW.company_id, NEW.reporter_user_id,
                    NEW.reporter_name, NEW.reporter_email,
                    NEW.category, NEW.severity, NEW.title, NEW.description,
                    NEW.reproduction_steps, NEW.expected_behavior,
                    NEW.actual_behavior, NEW.client_platform, NEW.app_version,
                    NEW.version_code, NEW.device_model, NEW.os_version,
                    NEW.current_screen, NEW.branch_id, NEW.branch_name,
                    NEW.terminal_id, NEW.terminal_name, NEW.connectivity,
                    NEW.occurred_at, NEW.created_at
                ) IS DISTINCT FROM ROW(
                    OLD.id, OLD.company_id, OLD.reporter_user_id,
                    OLD.reporter_name, OLD.reporter_email,
                    OLD.category, OLD.severity, OLD.title, OLD.description,
                    OLD.reproduction_steps, OLD.expected_behavior,
                    OLD.actual_behavior, OLD.client_platform, OLD.app_version,
                    OLD.version_code, OLD.device_model, OLD.os_version,
                    OLD.current_screen, OLD.branch_id, OLD.branch_name,
                    OLD.terminal_id, OLD.terminal_name, OLD.connectivity,
                    OLD.occurred_at, OLD.created_at
                ) THEN
                    RAISE EXCEPTION 'bug report submission context is immutable';
                END IF;

                IF NEW.status IS DISTINCT FROM OLD.status THEN
                    IF NOT (CASE OLD.status
                        WHEN 'open' THEN NEW.status IN (
                            'acknowledged', 'in_progress', 'resolved', 'rejected'
                        )
                        WHEN 'acknowledged' THEN NEW.status IN (
                            'open', 'in_progress', 'resolved', 'rejected'
                        )
                        WHEN 'in_progress' THEN NEW.status IN (
                            'open', 'acknowledged', 'resolved', 'rejected'
                        )
                        WHEN 'resolved' THEN NEW.status IN ('in_progress', 'closed')
                        WHEN 'closed' THEN NEW.status = 'in_progress'
                        WHEN 'rejected' THEN NEW.status IN ('open', 'in_progress')
                        ELSE FALSE
                    END) THEN
                        RAISE EXCEPTION 'invalid bug report status transition: % -> %',
                            OLD.status, NEW.status;
                    END IF;
                    IF NEW.status_changed_at IS NULL
                       OR NEW.status_changed_at IS NOT DISTINCT FROM OLD.status_changed_at
                       OR NEW.status_changed_by IS NULL THEN
                        RAISE EXCEPTION 'bug report status changes require fresh actor provenance';
                    END IF;
                ELSIF ROW(NEW.status_changed_at, NEW.status_changed_by)
                      IS DISTINCT FROM ROW(OLD.status_changed_at, OLD.status_changed_by) THEN
                    RAISE EXCEPTION 'status provenance cannot change without a status transition';
                END IF;

                IF NEW.status = 'resolved' AND OLD.status IS DISTINCT FROM 'resolved' THEN
                    IF NEW.resolved_at IS NULL
                       OR NEW.resolved_by IS NULL
                       OR NEW.resolved_by IS DISTINCT FROM NEW.status_changed_by THEN
                        RAISE EXCEPTION 'resolved bug reports require resolution provenance';
                    END IF;
                ELSIF OLD.status IN ('resolved', 'closed')
                      AND NEW.status = 'in_progress' THEN
                    IF NEW.resolved_at IS NOT NULL OR NEW.resolved_by IS NOT NULL THEN
                        RAISE EXCEPTION
                            'reopened bug reports must clear stale resolution provenance';
                    END IF;
                ELSIF ROW(NEW.resolved_at, NEW.resolved_by)
                      IS DISTINCT FROM ROW(OLD.resolved_at, OLD.resolved_by) THEN
                    RAISE EXCEPTION
                        'resolution provenance can change only when resolving a report';
                END IF;
                NEW.updated_at := now();
            END IF;

            IF NEW.status IN ('resolved', 'closed', 'rejected')
               AND (
                    NEW.internal_resolution_note IS NULL
                    OR length(trim(NEW.internal_resolution_note)) < 3
               ) THEN
                RAISE EXCEPTION
                    'resolved, closed, or rejected reports require a resolution note';
            END IF;

            FOR actor_company IN
                SELECT company_id
                  FROM users
                 WHERE id IN (NEW.status_changed_by, NEW.resolved_by)
            LOOP
                IF actor_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION 'bug report lifecycle actor must belong to the same company';
                END IF;
            END LOOP;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_bug_reports_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON bug_reports
        FOR EACH ROW EXECUTE FUNCTION enforce_bug_report_integrity();
        """
    )


def downgrade() -> None:
    # A submitted report is durable operational evidence. Dropping this table
    # after forward activity would silently erase the reporter's evidence and
    # every administrator lifecycle decision. Roll back the application binary
    # without rolling back this schema once any report exists.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM bug_reports) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0051 after bug-report activity'
                    USING HINT =
                        'Preserve the durable support evidence and deploy the '
                        'application at revision 0051 or later.';
            END IF;
        END
        $$;
        """
    )
    op.execute("DROP TRIGGER IF EXISTS trg_bug_reports_integrity ON bug_reports")
    op.execute("DROP FUNCTION IF EXISTS enforce_bug_report_integrity()")
    op.drop_table("bug_reports")

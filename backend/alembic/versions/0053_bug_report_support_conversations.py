"""Add reporter-safe support conversations, inbox reads, and private screenshots.

Revision ID: 0053
Revises: 0052
Create Date: 2026-08-28

Public replies are immutable and visible to the report author; the existing
``internal_resolution_note`` remains protected support-only material. Inbox
read cursors are per protected owner. Screenshot bytes are private database
objects with a hard two-MiB limit and explicit expiry metadata, never public
URLs.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0053"
down_revision = "0052"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("bug_reports", sa.Column("last_action", sa.String(length=120)))
    op.add_column("bug_reports", sa.Column("error_code", sa.String(length=100)))

    op.create_table(
        "bug_report_public_replies",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("bug_report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("author_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("author_name", sa.String(length=200), nullable=False),
        sa.Column("message", sa.String(length=4000), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "length(trim(message)) >= 2",
            name="ck_bug_report_public_replies_message_present",
        ),
        sa.ForeignKeyConstraint(["bug_report_id"], ["bug_reports.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["author_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_bug_report_public_replies_bug_report_id",
        "bug_report_public_replies",
        ["bug_report_id"],
    )
    op.create_index(
        "ix_bug_report_public_replies_company_id",
        "bug_report_public_replies",
        ["company_id"],
    )
    op.create_index(
        "ix_bug_report_public_replies_company_report_created",
        "bug_report_public_replies",
        ["company_id", "bug_report_id", "created_at"],
    )

    op.create_table(
        "bug_report_attachments",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("bug_report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("uploader_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("original_filename", sa.String(length=160), nullable=False),
        sa.Column("content_type", sa.String(length=32), nullable=False),
        sa.Column("byte_size", sa.Integer(), nullable=False),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("payload", sa.LargeBinary(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("purged_at", sa.DateTime(timezone=True)),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint(
            "content_type IN ('image/png', 'image/jpeg', 'image/webp')",
            name="ck_bug_report_attachments_content_type",
        ),
        sa.CheckConstraint(
            "byte_size BETWEEN 1 AND 2097152",
            name="ck_bug_report_attachments_byte_size",
        ),
        sa.CheckConstraint(
            "sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_bug_report_attachments_sha256",
        ),
        sa.CheckConstraint(
            "(payload IS NULL AND purged_at IS NOT NULL) OR ("
            "payload IS NOT NULL AND purged_at IS NULL "
            "AND octet_length(payload) = byte_size "
            "AND octet_length(payload) <= 2097152 "
            "AND sha256 = encode(digest(payload, 'sha256'), 'hex'))",
            name="ck_bug_report_attachments_payload_integrity",
        ),
        sa.ForeignKeyConstraint(["bug_report_id"], ["bug_reports.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["uploader_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "bug_report_id",
            "sha256",
            name="uq_bug_report_attachments_report_sha256",
        ),
    )
    op.create_index(
        "ix_bug_report_attachments_bug_report_id",
        "bug_report_attachments",
        ["bug_report_id"],
    )
    op.create_index(
        "ix_bug_report_attachments_company_id",
        "bug_report_attachments",
        ["company_id"],
    )
    op.create_index(
        "ix_bug_report_attachments_company_report_created",
        "bug_report_attachments",
        ["company_id", "bug_report_id", "created_at"],
    )

    op.create_table(
        "bug_report_inbox_reads",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("bug_report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("last_read_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
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
        sa.ForeignKeyConstraint(["bug_report_id"], ["bug_reports.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "bug_report_id",
            "user_id",
            name="uq_bug_report_inbox_reads_report_user",
        ),
    )
    op.create_index(
        "ix_bug_report_inbox_reads_bug_report_id",
        "bug_report_inbox_reads",
        ["bug_report_id"],
    )
    op.create_index(
        "ix_bug_report_inbox_reads_company_id",
        "bug_report_inbox_reads",
        ["company_id"],
    )
    op.create_index(
        "ix_bug_report_inbox_reads_company_user",
        "bug_report_inbox_reads",
        ["company_id", "user_id"],
    )

    # The original 0051 trigger protects the original evidence columns. This
    # narrow companion trigger freezes the new allowlisted action/error fields.
    op.execute(
        """
        CREATE FUNCTION enforce_bug_report_support_context_integrity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF ROW(NEW.last_action, NEW.error_code)
               IS DISTINCT FROM ROW(OLD.last_action, OLD.error_code) THEN
                RAISE EXCEPTION 'bug report submission context is immutable';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_bug_report_support_context_integrity
        BEFORE UPDATE ON bug_reports
        FOR EACH ROW EXECUTE FUNCTION enforce_bug_report_support_context_integrity();
        """
    )

    op.execute(
        """
        CREATE FUNCTION enforce_bug_report_support_child_integrity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            report_company uuid;
            reporter_id uuid;
            actor_company uuid;
            actor_name text;
        BEGIN
            IF TG_TABLE_NAME = 'bug_report_public_replies' THEN
                IF TG_OP <> 'INSERT' THEN
                    RAISE EXCEPTION
                        'public support replies are durable and cannot be changed or deleted';
                END IF;
                SELECT company_id INTO report_company
                  FROM bug_reports WHERE id = NEW.bug_report_id;
                SELECT company_id, name INTO actor_company, actor_name
                  FROM users
                 WHERE id = NEW.author_user_id
                   AND status = 'active'
                   AND deleted_at IS NULL;
                IF report_company IS DISTINCT FROM NEW.company_id
                   OR actor_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION 'public support reply crosses company scope';
                END IF;
                IF actor_name IS DISTINCT FROM NEW.author_name THEN
                    RAISE EXCEPTION 'public support reply author snapshot is invalid';
                END IF;
                RETURN NEW;
            END IF;

            IF TG_TABLE_NAME = 'bug_report_attachments' THEN
                IF TG_OP = 'DELETE' THEN
                    RAISE EXCEPTION
                        'bug report attachment metadata is durable and cannot be deleted';
                ELSIF TG_OP = 'INSERT' THEN
                    SELECT company_id, reporter_user_id
                      INTO report_company, reporter_id
                      FROM bug_reports WHERE id = NEW.bug_report_id;
                    SELECT company_id INTO actor_company
                      FROM users
                     WHERE id = NEW.uploader_user_id
                       AND status = 'active'
                       AND deleted_at IS NULL;
                    IF report_company IS DISTINCT FROM NEW.company_id
                       OR actor_company IS DISTINCT FROM NEW.company_id
                       OR reporter_id IS DISTINCT FROM NEW.uploader_user_id THEN
                        RAISE EXCEPTION 'bug report attachment crosses reporter scope';
                    END IF;
                    IF NEW.payload IS NULL OR NEW.purged_at IS NOT NULL THEN
                        RAISE EXCEPTION 'new bug report attachment requires private bytes';
                    END IF;
                    RETURN NEW;
                END IF;

                IF ROW(
                    NEW.id, NEW.company_id, NEW.bug_report_id,
                    NEW.uploader_user_id, NEW.original_filename,
                    NEW.content_type, NEW.byte_size, NEW.sha256,
                    NEW.created_at, NEW.expires_at
                ) IS DISTINCT FROM ROW(
                    OLD.id, OLD.company_id, OLD.bug_report_id,
                    OLD.uploader_user_id, OLD.original_filename,
                    OLD.content_type, OLD.byte_size, OLD.sha256,
                    OLD.created_at, OLD.expires_at
                ) THEN
                    RAISE EXCEPTION 'bug report attachment metadata is immutable';
                END IF;
                IF OLD.payload IS NULL
                   OR NEW.payload IS NOT NULL
                   OR NEW.purged_at IS NULL
                   OR NEW.expires_at > now() THEN
                    RAISE EXCEPTION 'invalid bug report attachment purge';
                END IF;
                RETURN NEW;
            END IF;

            -- Read cursors are mutable but must remain inside the same tenant.
            SELECT company_id INTO report_company
              FROM bug_reports WHERE id = NEW.bug_report_id;
            SELECT company_id INTO actor_company
              FROM users
             WHERE id = NEW.user_id
               AND status = 'active'
               AND deleted_at IS NULL;
            IF report_company IS DISTINCT FROM NEW.company_id
               OR actor_company IS DISTINCT FROM NEW.company_id THEN
                RAISE EXCEPTION 'bug report inbox read crosses company scope';
            END IF;
            IF TG_OP = 'UPDATE' AND ROW(
                NEW.id, NEW.company_id, NEW.bug_report_id, NEW.user_id, NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.id, OLD.company_id, OLD.bug_report_id, OLD.user_id, OLD.created_at
            ) THEN
                RAISE EXCEPTION 'bug report inbox read identity is immutable';
            END IF;
            NEW.updated_at := now();
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_bug_report_public_replies_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON bug_report_public_replies
        FOR EACH ROW EXECUTE FUNCTION enforce_bug_report_support_child_integrity();

        CREATE TRIGGER trg_bug_report_attachments_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON bug_report_attachments
        FOR EACH ROW EXECUTE FUNCTION enforce_bug_report_support_child_integrity();

        CREATE TRIGGER trg_bug_report_inbox_reads_integrity
        BEFORE INSERT OR UPDATE ON bug_report_inbox_reads
        FOR EACH ROW EXECUTE FUNCTION enforce_bug_report_support_child_integrity();
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM bug_report_public_replies)
               OR EXISTS (SELECT 1 FROM bug_report_attachments)
               OR EXISTS (
                    SELECT 1 FROM bug_reports
                     WHERE last_action IS NOT NULL OR error_code IS NOT NULL
               ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0053 after support conversation activity'
                    USING HINT =
                        'Preserve support evidence and deploy revision 0053 or later.';
            END IF;
        END
        $$;
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_bug_report_inbox_reads_integrity "
        "ON bug_report_inbox_reads"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_bug_report_attachments_integrity "
        "ON bug_report_attachments"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_bug_report_public_replies_integrity "
        "ON bug_report_public_replies"
    )
    op.execute("DROP FUNCTION IF EXISTS enforce_bug_report_support_child_integrity()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_bug_report_support_context_integrity ON bug_reports"
    )
    op.execute("DROP FUNCTION IF EXISTS enforce_bug_report_support_context_integrity()")
    op.drop_table("bug_report_inbox_reads")
    op.drop_table("bug_report_attachments")
    op.drop_table("bug_report_public_replies")
    op.drop_column("bug_reports", "error_code")
    op.drop_column("bug_reports", "last_action")

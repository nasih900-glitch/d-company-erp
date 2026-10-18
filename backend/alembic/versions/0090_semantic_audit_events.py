"""Add typed business Audit evidence and database append-only guards.

Revision ID: 0090
Revises: 0089
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0090"
down_revision = "0089"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Immutable event identities must not be nulled by parent deletion or take
    # FK KEY SHARE locks in the reverse of operational Shift -> Terminal paths.
    # Keep the UUID evidence; reads still tenant-scope joined current labels.
    for constraint in sa.inspect(op.get_bind()).get_foreign_keys("audit_log"):
        if constraint["constrained_columns"] in (["actor_user_id"], ["terminal_id"]):
            op.drop_constraint(constraint["name"], "audit_log", type_="foreignkey")
    for name, length in (
        ("action_code", 24),
        ("category", 32),
        ("stage", 32),
        ("outcome", 32),
        ("operation_id", 100),
        ("source_reference", 120),
        ("actor_kind", 24),
    ):
        op.add_column("audit_log", sa.Column(name, sa.String(length), nullable=True))
    op.add_column("audit_log", sa.Column("target_user_id", postgresql.UUID(as_uuid=True)))
    op.add_column("audit_log", sa.Column("business_details", postgresql.JSONB()))
    op.add_column("audit_log", sa.Column("catalogue_version", sa.Integer()))
    for name, field in (
        ("ix_audit_company_code_id", "action_code"),
        ("ix_audit_company_category_id", "category"),
        ("ix_audit_company_actor_id", "actor_user_id"),
        ("ix_audit_company_reference_id", "source_reference"),
    ):
        op.create_index(name, "audit_log", ["company_id", field, "id"])
    # No historical UPDATE/backfill. Ordinary users/terminals remain soft
    # deleted/archived, and Audit retains original UUIDs regardless of lifecycle.
    # A DB owner/superuser can still disable triggers; this is not a WORM vault.
    op.execute("""
        CREATE FUNCTION guard_audit_log_append_only() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'audit log is append-only'
                USING ERRCODE = '23514';
        END;
        $$
    """)
    op.execute("""
        CREATE TRIGGER audit_log_append_only
        BEFORE UPDATE OR DELETE ON audit_log
        FOR EACH ROW EXECUTE FUNCTION guard_audit_log_append_only()
    """)
    op.execute("""
        CREATE TRIGGER audit_log_no_truncate
        BEFORE TRUNCATE ON audit_log
        FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only()
    """)


def downgrade() -> None:
    # An old application can run against these additive columns. Dropping them
    # after use would erase immutable actor/target/classification evidence.
    op.execute("""
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM audit_log WHERE catalogue_version IS NOT NULL
                       OR action_code IS NOT NULL OR business_details IS NOT NULL
                       OR target_user_id IS NOT NULL OR actor_kind IS NOT NULL
                       OR source_reference IS NOT NULL OR operation_id IS NOT NULL
                       OR outcome IS NOT NULL OR stage IS NOT NULL OR category IS NOT NULL) THEN
                RAISE EXCEPTION 'cannot downgrade semantic Audit after evidence is recorded';
            END IF;
        END $$
    """)
    op.execute("""
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM audit_log a WHERE
                (a.actor_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id=a.actor_user_id)) OR
                (a.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM terminals t WHERE t.id=a.terminal_id))) THEN
                RAISE EXCEPTION 'cannot restore Audit foreign keys over retained orphan identities';
            END IF;
        END $$
    """)
    op.create_foreign_key(
        "audit_log_actor_user_id_fkey",
        "audit_log",
        "users",
        ["actor_user_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_foreign_key(
        "audit_log_terminal_id_fkey",
        "audit_log",
        "terminals",
        ["terminal_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.execute("DROP TRIGGER IF EXISTS audit_log_no_truncate ON audit_log")
    op.execute("DROP TRIGGER IF EXISTS audit_log_append_only ON audit_log")
    op.execute("DROP FUNCTION IF EXISTS guard_audit_log_append_only()")
    for name in (
        "ix_audit_company_code_id",
        "ix_audit_company_category_id",
        "ix_audit_company_actor_id",
        "ix_audit_company_reference_id",
    ):
        op.drop_index(name, table_name="audit_log")
    for name in (
        "catalogue_version",
        "business_details",
        "target_user_id",
        "actor_kind",
        "source_reference",
        "operation_id",
        "outcome",
        "stage",
        "category",
        "action_code",
    ):
        op.drop_column("audit_log", name)

"""Add generation-bound daily collection summary configuration.

Revision ID: 0086
Revises: 0085
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0086"
down_revision = "0085"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_export_mode",
            sa.String(length=32),
            server_default="legacy_transactions",
            nullable=False,
        ),
    )
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_summary_branch_id",
            postgresql.UUID(as_uuid=True),
        ),
    )
    op.add_column(
        "companies",
        sa.Column("google_sheets_summary_start_date", sa.Date()),
    )
    op.add_column(
        "companies",
        sa.Column("google_sheets_summary_timezone", sa.String(length=64)),
    )
    op.add_column(
        "companies",
        sa.Column("google_sheets_summary_scan_cursor_date", sa.Date()),
    )
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_summary_last_scanned_at",
            sa.DateTime(timezone=True),
        ),
    )
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_summary_last_window_blockers",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )
    op.add_column(
        "companies",
        sa.Column("google_sheets_summary_last_error", sa.String(length=200)),
    )
    op.create_foreign_key(
        "fk_companies_google_sheets_summary_branch",
        "companies",
        "branches",
        ["google_sheets_summary_branch_id"],
        ["id"],
        ondelete="RESTRICT",
        use_alter=True,
    )
    op.create_check_constraint(
        "ck_company_google_sheets_export_mode",
        "companies",
        "google_sheets_export_mode IN ('legacy_transactions', 'daily_collections')",
    )
    op.create_check_constraint(
        "ck_company_google_sheets_summary_scope",
        "companies",
        "(google_sheets_export_mode = 'legacy_transactions' "
        "AND google_sheets_summary_branch_id IS NULL "
        "AND google_sheets_summary_start_date IS NULL "
        "AND google_sheets_summary_timezone IS NULL "
        "AND google_sheets_summary_scan_cursor_date IS NULL) OR "
        "(google_sheets_export_mode = 'daily_collections' "
        "AND google_sheets_summary_branch_id IS NOT NULL "
        "AND google_sheets_summary_start_date IS NOT NULL "
        "AND google_sheets_summary_timezone IS NOT NULL "
        "AND length(trim(google_sheets_summary_timezone)) > 0 "
        "AND (google_sheets_summary_scan_cursor_date IS NULL OR "
        "google_sheets_summary_scan_cursor_date >= "
        "google_sheets_summary_start_date))",
    )
    op.execute(
        """
        CREATE FUNCTION validate_google_sheets_summary_branch_scope()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.google_sheets_export_mode = 'daily_collections'
             AND NOT EXISTS (
               SELECT 1 FROM branches b
               WHERE b.id = NEW.google_sheets_summary_branch_id
                 AND b.company_id = NEW.id
                 AND b.deleted_at IS NULL
             ) THEN
            RAISE EXCEPTION 'Google Sheets summary branch crosses company scope'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_companies_google_sheets_summary_scope
        BEFORE INSERT OR UPDATE OF google_sheets_export_mode,
          google_sheets_summary_branch_id
        ON companies
        FOR EACH ROW EXECUTE FUNCTION validate_google_sheets_summary_branch_scope();
        """
    )
    op.create_index(
        "ix_shifts_company_branch_opened_at",
        "shifts",
        ["company_id", "branch_id", "opened_at"],
    )
    op.create_index(
        "ix_google_sheets_deliveries_daily_source_revision",
        "google_sheets_deliveries",
        ["company_id", "source_id", "source_revision"],
        postgresql_where=sa.text(
            "event_type = 'collections.daily_snapshot' AND source_type = 'daily_collections'"
        ),
    )


def downgrade() -> None:
    active_summary = bool(
        op.get_bind()
        .execute(
            sa.text(
                "SELECT EXISTS (SELECT 1 FROM companies "
                "WHERE google_sheets_export_mode = 'daily_collections')"
            )
        )
        .scalar_one()
    )
    if active_summary:
        raise RuntimeError(
            "0086 downgrade refused: return every Google Sheets export to legacy mode first"
        )
    op.drop_index(
        "ix_google_sheets_deliveries_daily_source_revision",
        table_name="google_sheets_deliveries",
    )
    op.drop_index("ix_shifts_company_branch_opened_at", table_name="shifts")
    op.execute("DROP TRIGGER trg_companies_google_sheets_summary_scope ON companies")
    op.execute("DROP FUNCTION validate_google_sheets_summary_branch_scope()")
    op.drop_constraint("ck_company_google_sheets_summary_scope", "companies", type_="check")
    op.drop_constraint("ck_company_google_sheets_export_mode", "companies", type_="check")
    op.drop_constraint(
        "fk_companies_google_sheets_summary_branch",
        "companies",
        type_="foreignkey",
    )
    op.drop_column("companies", "google_sheets_summary_last_error")
    op.drop_column("companies", "google_sheets_summary_last_window_blockers")
    op.drop_column("companies", "google_sheets_summary_last_scanned_at")
    op.drop_column("companies", "google_sheets_summary_scan_cursor_date")
    op.drop_column("companies", "google_sheets_summary_start_date")
    op.drop_column("companies", "google_sheets_summary_timezone")
    op.drop_column("companies", "google_sheets_summary_branch_id")
    op.drop_column("companies", "google_sheets_export_mode")

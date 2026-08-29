"""Add immutable itemisation for paid gaming package extensions.

Revision ID: 0038
Revises: 0037
Create Date: 2026-08-27
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0038"
down_revision = "0037"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("billing_mode", sa.String(length=20), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("package_price_minor_snapshot", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("package_duration_minutes_snapshot", sa.Integer(), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("package_variant_snapshot", sa.String(length=20), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("package_station_type_snapshot", sa.String(length=20), nullable=True),
    )
    # Existing package sessions predate explicit snapshots. The linked catalog
    # row is the strongest available provenance and packages have never had a
    # public mutation endpoint. Keep the columns nullable so a genuinely
    # unresolvable legacy row remains visible and fails closed.
    op.execute(
        """
        UPDATE gaming_sessions AS session
           SET package_price_minor_snapshot = package.price_minor,
               package_duration_minutes_snapshot = package.duration_minutes,
               package_variant_snapshot = package.variant,
               package_station_type_snapshot = package.station_type
          FROM gaming_packages AS package
         WHERE session.package_id = package.id
           AND session.package_id IS NOT NULL
        """
    )
    # package_id is ON DELETE SET NULL, so preserve a durable discriminator.
    # A running row with a precomputed amount cannot be an ordinary hourly
    # session in the existing implementation; classify it as a package row so
    # Stop preserves the locked amount and never silently reprices it. Rows
    # that still have their catalog FK are unambiguous. Any ended, unbilled row
    # with a locked amount is conservatively marked ambiguous: pre-0038 package
    # timers could be cleared, so nullable package_id/timer fields cannot prove
    # that granting hourly membership benefits is safe. Already-order-linked
    # history cannot be repriced and retains historical elapsed-hourly mode.
    op.execute(
        """
        UPDATE gaming_sessions
           SET billing_mode = CASE
               WHEN package_id IS NOT NULL THEN 'package'
               WHEN status IN ('active', 'paused') AND amount_minor IS NOT NULL
                   THEN 'package'
               WHEN status = 'ended'
                    AND order_id IS NULL
                    AND amount_minor IS NOT NULL
                   THEN 'legacy_ambiguous'
               ELSE 'hourly'
           END
        """
    )
    op.alter_column(
        "gaming_sessions",
        "billing_mode",
        nullable=False,
        server_default=sa.text("'hourly'"),
    )
    op.create_check_constraint(
        "ck_gaming_session_billing_mode",
        "gaming_sessions",
        "billing_mode IN ('hourly', 'package', 'legacy_ambiguous')",
    )

    op.create_table(
        "gaming_session_extensions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "gaming_session_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("gaming_sessions.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "package_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("gaming_packages.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("package_name", sa.String(length=100), nullable=False),
        sa.Column("package_variant", sa.String(length=20), nullable=False),
        sa.Column("station_type", sa.String(length=20), nullable=False),
        sa.Column("duration_minutes", sa.Integer(), nullable=False),
        sa.Column("package_price_minor", sa.BigInteger(), nullable=False),
        sa.Column("controller_surcharge_minor", sa.BigInteger(), nullable=False),
        sa.Column("total_minor", sa.BigInteger(), nullable=False),
        sa.Column("timer_before_minutes", sa.Integer(), nullable=False),
        sa.Column("timer_after_minutes", sa.Integer(), nullable=False),
        sa.Column("amount_before_minor", sa.BigInteger(), nullable=False),
        sa.Column("amount_after_minor", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column(
            "created_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "duration_minutes > 0",
            name="ck_gaming_session_extension_duration_positive",
        ),
        sa.CheckConstraint(
            "package_price_minor >= 0 AND controller_surcharge_minor >= 0 AND total_minor >= 0",
            name="ck_gaming_session_extension_amounts_non_negative",
        ),
        sa.CheckConstraint(
            "total_minor = package_price_minor + controller_surcharge_minor",
            name="ck_gaming_session_extension_total_matches_parts",
        ),
        sa.CheckConstraint(
            "timer_before_minutes >= 0 "
            "AND timer_after_minutes = timer_before_minutes + duration_minutes",
            name="ck_gaming_session_extension_timer_chain",
        ),
        sa.CheckConstraint(
            "amount_before_minor >= 0 AND amount_after_minor = amount_before_minor + total_minor",
            name="ck_gaming_session_extension_amount_chain",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_gaming_session_extension_idempotency_present",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_gaming_session_extension_company_idempotency",
        ),
    )
    op.create_index(
        "ix_gaming_session_extensions_company_id",
        "gaming_session_extensions",
        ["company_id"],
    )
    op.create_index(
        "ix_gaming_session_extensions_gaming_session_id",
        "gaming_session_extensions",
        ["gaming_session_id"],
    )
    op.create_index(
        "ix_gaming_session_extensions_package_id",
        "gaming_session_extensions",
        ["package_id"],
    )
    op.create_index(
        "ix_gaming_session_extensions_created_by",
        "gaming_session_extensions",
        ["created_by"],
    )
    op.execute(
        """
        CREATE FUNCTION dcompany_guard_gaming_session_extension_immutable()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION 'gaming_session_extensions is append-only; % is forbidden', TG_OP;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_gaming_session_extensions_immutable
        BEFORE UPDATE OR DELETE ON gaming_session_extensions
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_gaming_session_extension_immutable()
        """
    )


def downgrade() -> None:
    bind = op.get_bind()
    extension_receipts = int(
        bind.execute(sa.text("SELECT count(*) FROM gaming_session_extensions")).scalar_one()
    )
    unresolved_ambiguous = int(
        bind.execute(
            sa.text(
                "SELECT count(*) FROM gaming_sessions "
                "WHERE billing_mode = 'legacy_ambiguous' "
                "AND status = 'ended' AND order_id IS NULL"
            )
        ).scalar_one()
    )
    orphaned_package_modes = int(
        bind.execute(
            sa.text(
                "SELECT count(*) FROM gaming_sessions "
                "WHERE billing_mode = 'package' AND package_id IS NULL"
            )
        ).scalar_one()
    )
    if extension_receipts or unresolved_ambiguous or orphaned_package_modes:
        raise RuntimeError(
            "Refusing unsafe downgrade from 0038 to 0037: immutable gaming billing "
            "evidence would be destroyed "
            f"(extension_receipts={extension_receipts}, "
            f"unresolved_legacy_ambiguous={unresolved_ambiguous}, "
            f"package_rows_without_catalog={orphaned_package_modes}). Keep the additive "
            "0038 schema while rolling back application code, or reconcile and archive "
            "the affected records through an owner-audited procedure first."
        )

    op.execute(
        "DROP TRIGGER IF EXISTS trg_gaming_session_extensions_immutable "
        "ON gaming_session_extensions"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_gaming_session_extension_immutable()")
    op.drop_index(
        "ix_gaming_session_extensions_created_by",
        table_name="gaming_session_extensions",
    )
    op.drop_index(
        "ix_gaming_session_extensions_package_id",
        table_name="gaming_session_extensions",
    )
    op.drop_index(
        "ix_gaming_session_extensions_gaming_session_id",
        table_name="gaming_session_extensions",
    )
    op.drop_index(
        "ix_gaming_session_extensions_company_id",
        table_name="gaming_session_extensions",
    )
    op.drop_table("gaming_session_extensions")
    # IF EXISTS keeps local/staging databases recoverable if they briefly ran
    # the pre-release draft of 0038 before billing_mode was added to the same
    # unreleased revision.
    op.execute(
        "ALTER TABLE gaming_sessions DROP CONSTRAINT IF EXISTS ck_gaming_session_billing_mode"
    )
    op.execute("ALTER TABLE gaming_sessions DROP COLUMN IF EXISTS billing_mode")
    op.drop_column("gaming_sessions", "package_station_type_snapshot")
    op.drop_column("gaming_sessions", "package_variant_snapshot")
    op.drop_column("gaming_sessions", "package_duration_minutes_snapshot")
    op.drop_column("gaming_sessions", "package_price_minor_snapshot")

"""Record one immutable PS5 60-to-30-minute package amendment.

Revision ID: 0085
Revises: 0084
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0085"
down_revision = "0084"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("billing_revision", sa.Integer(), server_default="0", nullable=False),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("effective_package_id", postgresql.UUID(as_uuid=True)),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("effective_package_price_minor_snapshot", sa.BigInteger()),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("effective_package_duration_minutes_snapshot", sa.Integer()),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("effective_package_variant_snapshot", sa.String(20)),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("effective_package_station_type_snapshot", sa.String(20)),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("effective_package_pricing_tier_snapshot", sa.String(20)),
    )
    op.create_foreign_key(
        "fk_gaming_sessions_effective_package_id",
        "gaming_sessions",
        "gaming_packages",
        ["effective_package_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_check_constraint(
        "ck_gaming_sessions_billing_amendment_projection",
        "gaming_sessions",
        "(billing_revision = 0 "
        "AND effective_package_id IS NULL "
        "AND effective_package_price_minor_snapshot IS NULL "
        "AND effective_package_duration_minutes_snapshot IS NULL "
        "AND effective_package_variant_snapshot IS NULL "
        "AND effective_package_station_type_snapshot IS NULL "
        "AND effective_package_pricing_tier_snapshot IS NULL) "
        "OR (billing_revision = 1 "
        "AND effective_package_price_minor_snapshot >= 0 "
        "AND effective_package_duration_minutes_snapshot = 30 "
        "AND effective_package_variant_snapshot IN ('single','dual') "
        "AND effective_package_station_type_snapshot = 'ps5' "
        "AND effective_package_pricing_tier_snapshot = 'standard')",
    )
    op.execute(
        """
        CREATE FUNCTION guard_gaming_session_billing_amendment_projection()
        RETURNS trigger AS $$
        BEGIN
          IF NEW.billing_revision < OLD.billing_revision
             OR NEW.billing_revision > OLD.billing_revision + 1 THEN
            RAISE EXCEPTION 'gaming session billing revision must advance exactly once';
          END IF;
          IF OLD.billing_revision = 1 AND (
               NEW.billing_revision IS DISTINCT FROM OLD.billing_revision
               OR NEW.effective_package_price_minor_snapshot IS DISTINCT FROM OLD.effective_package_price_minor_snapshot
               OR NEW.effective_package_duration_minutes_snapshot IS DISTINCT FROM OLD.effective_package_duration_minutes_snapshot
               OR NEW.effective_package_variant_snapshot IS DISTINCT FROM OLD.effective_package_variant_snapshot
               OR NEW.effective_package_station_type_snapshot IS DISTINCT FROM OLD.effective_package_station_type_snapshot
               OR NEW.effective_package_pricing_tier_snapshot IS DISTINCT FROM OLD.effective_package_pricing_tier_snapshot
               OR (
                    NEW.effective_package_id IS DISTINCT FROM OLD.effective_package_id
                    AND NOT (OLD.effective_package_id IS NOT NULL AND NEW.effective_package_id IS NULL)
               )
          ) THEN
            RAISE EXCEPTION 'gaming session effective package projection is immutable';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_sessions_billing_amendment_projection
        BEFORE UPDATE OF billing_revision, effective_package_id,
          effective_package_price_minor_snapshot,
          effective_package_duration_minutes_snapshot,
          effective_package_variant_snapshot,
          effective_package_station_type_snapshot,
          effective_package_pricing_tier_snapshot
        ON gaming_sessions
        FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_billing_amendment_projection();
        """
    )

    op.add_column(
        "gaming_session_extensions",
        sa.Column("billing_revision", sa.Integer(), server_default="0", nullable=False),
    )
    op.create_check_constraint(
        "ck_gaming_session_extension_billing_revision",
        "gaming_session_extensions",
        "billing_revision IN (0,1)",
    )

    op.create_table(
        "gaming_session_package_amendments",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("gaming_session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("original_package_id", postgresql.UUID(as_uuid=True)),
        sa.Column("original_package_price_minor", sa.BigInteger(), nullable=False),
        sa.Column("original_package_duration_minutes", sa.Integer(), nullable=False),
        sa.Column("original_package_variant", sa.String(20), nullable=False),
        sa.Column("original_package_station_type", sa.String(20), nullable=False),
        sa.Column("original_package_pricing_tier", sa.String(20), nullable=False),
        sa.Column("target_package_id", postgresql.UUID(as_uuid=True)),
        sa.Column("target_package_code", sa.String(80), nullable=False),
        sa.Column("target_package_name", sa.String(100), nullable=False),
        sa.Column("target_package_price_minor", sa.BigInteger(), nullable=False),
        sa.Column("target_package_duration_minutes", sa.Integer(), nullable=False),
        sa.Column("target_package_variant", sa.String(20), nullable=False),
        sa.Column("target_package_station_type", sa.String(20), nullable=False),
        sa.Column("target_package_pricing_tier", sa.String(20), nullable=False),
        sa.Column("extra_controllers", sa.Integer(), nullable=False),
        sa.Column("controller_surcharge_minor", sa.BigInteger(), nullable=False),
        sa.Column("timer_before_minutes", sa.Integer(), nullable=False),
        sa.Column("timer_after_minutes", sa.Integer(), nullable=False),
        sa.Column("amount_before_minor", sa.BigInteger(), nullable=False),
        sa.Column("amount_after_minor", sa.BigInteger(), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("play_elapsed_ms", sa.BigInteger(), nullable=False),
        sa.Column("timing_source", sa.String(20), nullable=False),
        sa.Column("pause_version", sa.Integer(), nullable=False),
        sa.Column("participant_revision", sa.Integer(), nullable=False),
        sa.Column("billing_revision_before", sa.Integer(), nullable=False),
        sa.Column("billing_revision", sa.Integer(), nullable=False),
        sa.Column("idempotency_key", sa.String(160), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("amended_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint(
            "original_package_duration_minutes = 60 "
            "AND original_package_station_type = 'ps5' "
            "AND original_package_pricing_tier = 'standard' "
            "AND ((original_package_variant = 'single' "
            "AND original_package_price_minor = 12000) "
            "OR (original_package_variant = 'dual' "
            "AND original_package_price_minor = 15000))",
            name="ck_gaming_package_amendment_original",
        ),
        sa.CheckConstraint(
            "target_package_duration_minutes = 30 "
            "AND target_package_station_type = 'ps5' "
            "AND target_package_pricing_tier = 'standard' "
            "AND target_package_variant = original_package_variant "
            "AND ((target_package_variant = 'single' "
            "AND target_package_code = 'standard-single-session-30m' "
            "AND target_package_price_minor = 8000) "
            "OR (target_package_variant = 'dual' "
            "AND target_package_code = 'standard-dual-session-30m' "
            "AND target_package_price_minor = 10000))",
            name="ck_gaming_package_amendment_target",
        ),
        sa.CheckConstraint(
            "extra_controllers >= 0 "
            "AND (original_package_variant <> 'single' OR extra_controllers = 0) "
            "AND controller_surcharge_minor = extra_controllers * 3000 "
            "AND timer_before_minutes = 60 AND timer_after_minutes = 30 "
            "AND amount_before_minor = original_package_price_minor + controller_surcharge_minor "
            "AND amount_after_minor = target_package_price_minor + controller_surcharge_minor",
            name="ck_gaming_package_amendment_amounts",
        ),
        sa.CheckConstraint(
            "play_elapsed_ms >= 0 AND play_elapsed_ms < 1800000 "
            "AND timing_source IN ('server','offline_capture') "
            "AND pause_version >= 0 AND participant_revision = 0 "
            "AND billing_revision_before = 0 AND billing_revision = 1",
            name="ck_gaming_package_amendment_state",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0 AND length(request_hash) = 64",
            name="ck_gaming_package_amendment_receipt",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["gaming_session_id"], ["gaming_sessions.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["original_package_id"], ["gaming_packages.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["target_package_id"], ["gaming_packages.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["amended_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("gaming_session_id", name="uq_gaming_package_amendment_session"),
        sa.UniqueConstraint("company_id", "idempotency_key", name="uq_gaming_package_amendment_key"),
    )
    op.create_index(
        "ix_gaming_package_amendments_company_session",
        "gaming_session_package_amendments",
        ["company_id", "gaming_session_id"],
    )
    op.execute(
        """
        CREATE FUNCTION validate_gaming_package_amendment_scope() RETURNS trigger AS $$
        BEGIN
          IF NOT EXISTS (
               SELECT 1
               FROM gaming_sessions s
               JOIN stations st ON st.id = s.station_id
               JOIN shifts sh ON sh.id = s.shift_id
               JOIN gaming_packages original_package
                 ON original_package.id = NEW.original_package_id
               WHERE s.id = NEW.gaming_session_id
                 AND s.company_id = NEW.company_id
                 AND st.company_id = NEW.company_id
                 AND st.type = 'ps5'
                 AND sh.company_id = NEW.company_id
                 AND sh.branch_id = st.branch_id
                 AND sh.terminal_id = NEW.terminal_id
                 AND sh.status = 'open'
                 AND s.status IN ('active','paused')
                 AND s.end_at IS NULL
                 AND s.order_id IS NULL
                 AND s.billing_revision = NEW.billing_revision_before
                 AND s.participant_revision = NEW.participant_revision
                 AND s.pause_version = NEW.pause_version
                 AND s.timer_minutes = NEW.timer_before_minutes
                 AND s.amount_minor = NEW.amount_before_minor
                 AND s.extra_controllers = NEW.extra_controllers
                 AND s.package_id = NEW.original_package_id
                 AND s.package_price_minor_snapshot = NEW.original_package_price_minor
                 AND s.package_duration_minutes_snapshot = NEW.original_package_duration_minutes
                 AND s.package_variant_snapshot = NEW.original_package_variant
                 AND s.package_station_type_snapshot = NEW.original_package_station_type
                 AND s.package_pricing_tier_snapshot = NEW.original_package_pricing_tier
                 AND original_package.company_id = NEW.company_id
                 AND original_package.branch_id = st.branch_id
          ) OR NOT EXISTS (
               SELECT 1 FROM users u
               WHERE u.id = NEW.amended_by AND u.company_id = NEW.company_id
          ) OR NOT EXISTS (
               SELECT 1 FROM gaming_packages p
               JOIN gaming_sessions s ON s.id = NEW.gaming_session_id
               JOIN stations st ON st.id = s.station_id
               WHERE p.id = NEW.target_package_id
                 AND p.company_id = NEW.company_id
                 AND p.branch_id = st.branch_id
                 AND p.deleted_at IS NULL
                 AND p.is_active
                 AND p.code = NEW.target_package_code
                 AND p.name = NEW.target_package_name
                 AND p.price_minor = NEW.target_package_price_minor
                 AND p.duration_minutes = NEW.target_package_duration_minutes
                 AND p.variant = NEW.target_package_variant
                 AND p.station_type = NEW.target_package_station_type
                 AND p.pricing_tier = NEW.target_package_pricing_tier
          ) THEN
            RAISE EXCEPTION 'gaming package amendment tenant scope mismatch';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_package_amendments_scope
        BEFORE INSERT ON gaming_session_package_amendments
        FOR EACH ROW EXECUTE FUNCTION validate_gaming_package_amendment_scope();

        CREATE FUNCTION guard_gaming_package_amendment_mutation() RETURNS trigger AS $$
        BEGIN
          IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'gaming package amendment rows are immutable';
          END IF;
          IF (to_jsonb(NEW) - 'original_package_id' - 'target_package_id')
                 IS DISTINCT FROM
             (to_jsonb(OLD) - 'original_package_id' - 'target_package_id')
             OR (
                  NEW.original_package_id IS DISTINCT FROM OLD.original_package_id
                  AND NOT (OLD.original_package_id IS NOT NULL AND NEW.original_package_id IS NULL)
             )
             OR (
                  NEW.target_package_id IS DISTINCT FROM OLD.target_package_id
                  AND NOT (OLD.target_package_id IS NOT NULL AND NEW.target_package_id IS NULL)
             ) THEN
            RAISE EXCEPTION 'gaming package amendment rows are immutable';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_gaming_package_amendments_immutable
        BEFORE UPDATE OR DELETE ON gaming_session_package_amendments
        FOR EACH ROW EXECUTE FUNCTION guard_gaming_package_amendment_mutation();
        """
    )


def downgrade() -> None:
    raise RuntimeError(
        "0085 stores immutable package amendment evidence and cannot be downgraded safely"
    )

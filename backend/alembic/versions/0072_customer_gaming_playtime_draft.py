"""Add stable gaming customer identity and a disabled playtime draft.

Revision ID: 0072
Revises: 0071
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0072"
down_revision = "0071"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("customer_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column(
            "customer_identity_provenance",
            sa.String(length=20),
            server_default="historical",
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "ck_gaming_sessions_customer_identity_provenance",
        "gaming_sessions",
        "customer_identity_provenance = 'historical' OR "
        "(customer_identity_provenance = 'start_linked' AND customer_id IS NOT NULL) OR "
        "(customer_identity_provenance = 'start_unlinked' AND customer_id IS NULL)",
    )
    op.create_foreign_key(
        "fk_gaming_sessions_customer_id_customers",
        "gaming_sessions",
        "customers",
        ["customer_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index(
        "ix_gaming_sessions_customer_id", "gaming_sessions", ["customer_id"]
    )

    # Non-unique by design: old formatting variants can coexist. The Start
    # resolver treats multiple normalized matches as ambiguous and attaches
    # none, instead of migrating or merging identity without owner review.
    op.execute(
        """
        CREATE INDEX ix_customers_company_india_phone_live
        ON customers (
            company_id,
            (CASE
                WHEN phone ~ '^[+0-9[:space:]().-]+$'
                 AND regexp_replace(phone, '[^0-9]', '', 'g')
                     ~ '^(91)?[0-9]{10}$'
                THEN right(regexp_replace(phone, '[^0-9]', '', 'g'), 10)
                ELSE NULL
            END)
        )
        WHERE deleted_at IS NULL
        """
    )

    op.create_table(
        "gaming_playtime_program_settings",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=20), server_default="draft", nullable=False),
        sa.Column("rewards_enabled", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("messaging_enabled", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("threshold_paid_minutes", sa.Integer(), server_default="600", nullable=False),
        sa.Column("reward_minutes", sa.Integer(), server_default="60", nullable=False),
        sa.Column("company_whatsapp_phone", sa.String(length=20), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint("status = 'draft'", name="ck_gaming_playtime_program_draft"),
        sa.CheckConstraint(
            "rewards_enabled = false AND messaging_enabled = false",
            name="ck_gaming_playtime_program_disabled",
        ),
        sa.CheckConstraint(
            "threshold_paid_minutes BETWEEN 1 AND 525600",
            name="ck_gaming_playtime_program_threshold",
        ),
        sa.CheckConstraint(
            "reward_minutes BETWEEN 1 AND 10080",
            name="ck_gaming_playtime_program_reward",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("company_id", name="uq_gaming_playtime_program_company"),
    )
    op.create_index(
        "ix_gaming_playtime_program_settings_company_id",
        "gaming_playtime_program_settings",
        ["company_id"],
    )


def downgrade() -> None:
    # Refuse to erase either stable identity snapshots or reviewed proposal
    # values. Operators must export/restore them or apply a forward fix.
    op.execute(
        """
        DO $$ BEGIN
            IF EXISTS (
                SELECT 1 FROM gaming_sessions WHERE customer_id IS NOT NULL
            ) THEN
                RAISE EXCEPTION '%',
                    'Cannot downgrade 0072: linked customer identities exist; ' ||
                    'export data or apply a forward fix';
            END IF;
            IF EXISTS (
                SELECT 1 FROM gaming_sessions
                WHERE customer_identity_provenance <> 'historical'
            ) THEN
                RAISE EXCEPTION '%',
                    'Cannot downgrade 0072: new customer identity decisions exist; ' ||
                    'export data or apply a forward fix';
            END IF;
            IF EXISTS (
                SELECT 1 FROM gaming_playtime_program_settings
            ) THEN
                RAISE EXCEPTION '%',
                    'Cannot downgrade 0072: saved playtime settings exist; ' ||
                    'export data or apply a forward fix';
            END IF;
        END $$
        """
    )
    op.drop_index(
        "ix_gaming_playtime_program_settings_company_id",
        table_name="gaming_playtime_program_settings",
    )
    op.drop_table("gaming_playtime_program_settings")
    op.drop_index("ix_customers_company_india_phone_live", table_name="customers")
    op.drop_index("ix_gaming_sessions_customer_id", table_name="gaming_sessions")
    op.drop_constraint(
        "fk_gaming_sessions_customer_id_customers",
        "gaming_sessions",
        type_="foreignkey",
    )
    op.drop_constraint(
        "ck_gaming_sessions_customer_identity_provenance",
        "gaming_sessions",
        type_="check",
    )
    op.drop_column("gaming_sessions", "customer_identity_provenance")
    op.drop_column("gaming_sessions", "customer_id")

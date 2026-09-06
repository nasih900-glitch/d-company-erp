"""Add stable gaming tariff identity and player/tier contracts.

Revision ID: 0064
Revises: 0063
Create Date: 2026-09-03

Existing package rows receive an explicitly legacy code and conservative
player limits. Historical gaming sessions are not reclassified: the new tier
snapshot remains NULL until a Code 22+ package session is started.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0064"
down_revision = "0063"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_packages",
        sa.Column("code", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "gaming_packages",
        sa.Column(
            "pricing_tier",
            sa.String(length=20),
            server_default=sa.text("'standard'"),
            nullable=False,
        ),
    )
    op.add_column(
        "gaming_packages",
        sa.Column(
            "included_players",
            sa.Integer(),
            server_default=sa.text("1"),
            nullable=False,
        ),
    )
    op.add_column(
        "gaming_packages",
        sa.Column(
            "max_players",
            sa.Integer(),
            server_default=sa.text("1"),
            nullable=False,
        ),
    )

    # UUID-backed legacy identities are deterministic and collision-free. Do
    # not infer tariff codes from editable names/prices or rewrite old rows to
    # look like the new D Company catalog.
    op.execute("UPDATE gaming_packages SET code = 'legacy-' || id::text WHERE code IS NULL")
    # Preserve Code 21's additional-controller capability only for the mode it
    # was intended for. Other legacy products remain fixed-player packages.
    op.execute(
        "UPDATE gaming_packages "
        "SET included_players = 2, max_players = 10 "
        "WHERE station_type = 'ps5' AND variant = 'dual'"
    )
    op.alter_column(
        "gaming_packages",
        "code",
        existing_type=sa.String(length=64),
        nullable=False,
    )
    op.create_check_constraint(
        "ck_gaming_packages_code_present",
        "gaming_packages",
        "length(trim(code)) > 0",
    )
    op.create_check_constraint(
        "ck_gaming_packages_pricing_tier",
        "gaming_packages",
        "pricing_tier IN ('standard', 'premium')",
    )
    op.create_check_constraint(
        "ck_gaming_packages_player_limits",
        "gaming_packages",
        "included_players BETWEEN 1 AND 10 "
        "AND max_players BETWEEN included_players AND 10",
    )
    op.create_check_constraint(
        "ck_gaming_packages_multiplayer_eligibility",
        "gaming_packages",
        "max_players = included_players OR "
        "(station_type = 'ps5' AND variant = 'dual' AND included_players = 2)",
    )
    op.create_index(
        "uq_gaming_packages_company_branch_code_active",
        "gaming_packages",
        ["company_id", "branch_id", "code"],
        unique=True,
        postgresql_where=sa.text("deleted_at IS NULL"),
    )

    op.add_column(
        "gaming_sessions",
        sa.Column("package_pricing_tier_snapshot", sa.String(length=20)),
    )
    op.create_check_constraint(
        "ck_gaming_sessions_package_pricing_tier_snapshot",
        "gaming_sessions",
        "package_pricing_tier_snapshot IS NULL OR "
        "package_pricing_tier_snapshot IN ('standard', 'premium')",
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_gaming_sessions_package_pricing_tier_snapshot",
        "gaming_sessions",
        type_="check",
    )
    op.drop_column("gaming_sessions", "package_pricing_tier_snapshot")

    op.drop_index(
        "uq_gaming_packages_company_branch_code_active",
        table_name="gaming_packages",
    )
    op.drop_constraint(
        "ck_gaming_packages_multiplayer_eligibility",
        "gaming_packages",
        type_="check",
    )
    op.drop_constraint(
        "ck_gaming_packages_player_limits",
        "gaming_packages",
        type_="check",
    )
    op.drop_constraint(
        "ck_gaming_packages_pricing_tier",
        "gaming_packages",
        type_="check",
    )
    op.drop_constraint(
        "ck_gaming_packages_code_present",
        "gaming_packages",
        type_="check",
    )
    op.drop_column("gaming_packages", "max_players")
    op.drop_column("gaming_packages", "included_players")
    op.drop_column("gaming_packages", "pricing_tier")
    op.drop_column("gaming_packages", "code")

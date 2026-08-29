"""Enforce deterministic active recipe and positive yield semantics.

Revision ID: 0044
Revises: 0043
Create Date: 2026-08-27

Recipe quantities describe the ingredients required for ``yield_qty`` menu
units.  Deduction divides each line by that positive yield.  A partial unique
index makes the one-active-recipe assumption database-safe even for writes
outside the API's parent-row lock.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0044"
down_revision = "0043"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Do not guess which live recipe should win. A conflicting catalogue needs
    # explicit owner reconciliation before the constraint can be installed.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM recipes
                 WHERE is_active IS TRUE
                 GROUP BY menu_item_id
                HAVING COUNT(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'Cannot enforce one active recipe: duplicate active recipes exist'
                    USING HINT =
                        'Deactivate the incorrect recipe versions explicitly, then retry.';
            END IF;
            IF EXISTS (
                SELECT 1
                  FROM recipes
                 WHERE yield_qty IS NULL OR yield_qty <= 0
            ) THEN
                RAISE EXCEPTION
                    'Cannot enforce positive recipe yield: invalid recipe yields exist'
                    USING HINT =
                        'Correct each yield_qty to the number of menu units produced, then retry.';
            END IF;
            IF EXISTS (
                SELECT 1
                  FROM ingredients AS ingredient
                  JOIN batches AS batch
                    ON batch.ingredient_id = ingredient.id
                 WHERE ingredient.deleted_at IS NOT NULL
                   AND batch.qty_on_hand <> 0
            ) THEN
                RAISE EXCEPTION
                    'Cannot validate inventory valuation: deleted ingredients retain stock'
                    USING HINT =
                        'Restore the ingredient and reconcile its remaining batches with audited '
                        'stock movements before retrying.';
            END IF;
            IF EXISTS (
                SELECT 1
                  FROM recipe_lines AS line
                  JOIN recipes AS recipe
                    ON recipe.id = line.recipe_id
                  JOIN ingredients AS ingredient
                    ON ingredient.id = line.ingredient_id
                 WHERE recipe.is_active IS TRUE
                   AND ingredient.deleted_at IS NOT NULL
            ) THEN
                RAISE EXCEPTION
                    'Cannot validate recipes: an active recipe uses a deleted ingredient'
                    USING HINT =
                        'Restore or replace the ingredient in every active recipe, then retry.';
            END IF;
        END
        $$;
        """
    )
    op.create_check_constraint(
        "ck_recipe_positive_yield",
        "recipes",
        "yield_qty > 0",
    )
    op.alter_column(
        "recipes",
        "yield_qty",
        existing_type=sa.Numeric(14, 4),
        nullable=False,
    )
    op.create_index(
        "uq_recipe_one_active_per_menu_item",
        "recipes",
        ["menu_item_id"],
        unique=True,
        postgresql_where=sa.text("is_active IS TRUE"),
    )


def downgrade() -> None:
    op.drop_index(
        "uq_recipe_one_active_per_menu_item",
        table_name="recipes",
    )
    op.drop_constraint(
        "ck_recipe_positive_yield",
        "recipes",
        type_="check",
    )
    op.alter_column(
        "recipes",
        "yield_qty",
        existing_type=sa.Numeric(14, 4),
        nullable=True,
    )

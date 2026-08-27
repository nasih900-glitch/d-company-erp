"""Repair pre-release 0038 databases missing the gaming billing discriminator.

Revision ID: 0039
Revises: 0038
Create Date: 2026-08-27

An unreleased draft of 0038 was briefly applied to some environments before
``gaming_sessions.billing_mode`` was part of that revision. Those databases
are already stamped 0038, so changing 0038 cannot repair them. This additive
compatibility revision brings that schema up to the contract now promised by
0038 without disturbing databases that already have the complete schema.
"""

from __future__ import annotations

from typing import Any

import sqlalchemy as sa

from alembic import op

revision = "0039"
down_revision = "0038"
branch_labels = None
depends_on = None

_CHECK_NAME = "ck_gaming_session_billing_mode"
_CHECK_SQL = "billing_mode IN ('hourly', 'package', 'legacy_ambiguous')"


def _column_state(bind: Any) -> dict[str, Any] | None:
    row = bind.execute(
        sa.text(
            """
            SELECT attribute.attnotnull AS not_null,
                   (default_value.oid IS NOT NULL) AS has_default
              FROM pg_catalog.pg_attribute AS attribute
              LEFT JOIN pg_catalog.pg_attrdef AS default_value
                ON default_value.adrelid = attribute.attrelid
               AND default_value.adnum = attribute.attnum
             WHERE attribute.attrelid = to_regclass('gaming_sessions')
               AND attribute.attname = 'billing_mode'
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
            """
        )
    ).mappings().one_or_none()
    return dict(row) if row is not None else None


def _constraint_state(bind: Any) -> dict[str, Any] | None:
    row = bind.execute(
        sa.text(
            """
            SELECT constraint_row.convalidated AS validated
              FROM pg_catalog.pg_constraint AS constraint_row
             WHERE constraint_row.conrelid = to_regclass('gaming_sessions')
               AND constraint_row.conname = :constraint_name
               AND constraint_row.contype = 'c'
            """
        ),
        {"constraint_name": _CHECK_NAME},
    ).mappings().one_or_none()
    return dict(row) if row is not None else None


def upgrade() -> None:
    bind = op.get_bind()
    column_state = _column_state(bind)

    if column_state is None:
        op.add_column(
            "gaming_sessions",
            sa.Column("billing_mode", sa.String(length=20), nullable=True),
        )
        column_state = {"not_null": False, "has_default": False}

    # Install the write-time default before the data backfill. PostgreSQL keeps
    # the DDL lock until this migration commits, so no concurrent session can
    # slip a NULL between the backfill and the NOT NULL transition.
    if not column_state["has_default"]:
        op.alter_column(
            "gaming_sessions",
            "billing_mode",
            existing_type=sa.String(length=20),
            server_default=sa.text("'hourly'"),
        )

    has_null_billing_modes = bool(
        bind.execute(
            sa.text(
                "SELECT EXISTS ("
                "SELECT 1 FROM gaming_sessions WHERE billing_mode IS NULL"
                ")"
            )
        ).scalar_one()
    )
    if has_null_billing_modes:
        # This is intentionally identical to 0038's conservative classifier.
        # In particular, an ended unbilled row with a locked amount but no
        # surviving package FK is ambiguous, never silently treated as hourly.
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
             WHERE billing_mode IS NULL
            """
        )

    if not column_state["not_null"]:
        op.alter_column(
            "gaming_sessions",
            "billing_mode",
            existing_type=sa.String(length=20),
            nullable=False,
        )

    constraint_state = _constraint_state(bind)
    if constraint_state is None:
        op.create_check_constraint(
            _CHECK_NAME,
            "gaming_sessions",
            _CHECK_SQL,
        )
    elif not constraint_state["validated"]:
        op.execute(
            "ALTER TABLE gaming_sessions VALIDATE CONSTRAINT " + _CHECK_NAME
        )


def downgrade() -> None:
    # Forward-only compatibility repair. Revision 0038 now requires this
    # column, default, and constraint, so moving the version marker back to
    # 0038 must preserve the repaired schema rather than recreate the defect.
    pass

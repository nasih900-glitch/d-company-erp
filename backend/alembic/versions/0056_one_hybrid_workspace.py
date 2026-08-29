"""Enforce one active Hybrid workspace per shop without rewriting history.

Revision ID: 0056
Revises: 0055
Create Date: 2026-08-29

This migration deliberately performs a read-only preflight before creating
constraints.  It never chooses a keeper, archives a terminal, or rewrites a
historical foreign key.  Installations with legacy multi-terminal state must
first run ``scripts.merge_terminals_to_one`` in dry-run mode, take a verified
backup, resolve every reported operational blocker, and then explicitly apply
the reviewed plan.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0056"
down_revision = "0055"
branch_labels = None
depends_on = None


def _format_rows(rows, *, columns: tuple[str, ...]) -> str:
    return "; ".join(
        ", ".join(f"{column}={row[column]}" for column in columns)
        for row in rows
    )


def _assert_one_hybrid_workspace_ready(bind) -> None:
    active_count_violations = (
        bind.execute(
            sa.text(
                """
                SELECT b.id AS branch_id,
                       CASE WHEN b.deleted_at IS NULL
                            THEN 'active' ELSE 'archived' END AS branch_status,
                       COUNT(t.id) AS active_count
                  FROM branches AS b
                  LEFT JOIN terminals AS t
                    ON t.branch_id = b.id
                   AND t.is_active IS TRUE
                 GROUP BY b.id, b.deleted_at
                HAVING (b.deleted_at IS NULL AND COUNT(t.id) <> 1)
                    OR (b.deleted_at IS NOT NULL AND COUNT(t.id) > 1)
                 ORDER BY b.id
                 LIMIT 20
                """
            )
        )
        .mappings()
        .all()
    )
    non_hybrid_active = (
        bind.execute(
            sa.text(
                """
                SELECT id AS terminal_id, branch_id, purpose
                  FROM terminals
                 WHERE is_active IS TRUE
                   AND purpose <> 'hybrid'
                 ORDER BY branch_id, id
                 LIMIT 20
                """
            )
        )
        .mappings()
        .all()
    )
    if not active_count_violations and not non_hybrid_active:
        return

    details: list[str] = []
    if active_count_violations:
        details.append(
            "active workspace count violations: "
            + _format_rows(
                active_count_violations,
                columns=("branch_id", "branch_status", "active_count"),
            )
        )
    if non_hybrid_active:
        details.append(
            "active non-Hybrid workspaces: "
            + _format_rows(
                non_hybrid_active,
                columns=("terminal_id", "branch_id", "purpose"),
            )
        )
    raise RuntimeError(
        "Migration 0056 refused before changing the schema: each active shop "
        "must have exactly one active Hybrid workspace, and each archived shop "
        "may retain at most one active Hybrid workspace. No terminal was "
        "archived or repointed. Run scripts.merge_terminals_to_one as a dry "
        "run, take and verify a database backup, resolve every blocker, apply "
        "the reviewed consolidation, then retry the migration. "
        + " | ".join(details)
    )


def upgrade() -> None:
    _assert_one_hybrid_workspace_ready(op.get_bind())
    op.create_check_constraint(
        "ck_terminals_active_requires_hybrid",
        "terminals",
        "is_active IS FALSE OR purpose = 'hybrid'",
    )
    op.create_index(
        "uq_terminals_one_active_per_branch",
        "terminals",
        ["branch_id"],
        unique=True,
        postgresql_where=sa.text("is_active IS TRUE"),
        sqlite_where=sa.text("is_active = 1"),
    )


def downgrade() -> None:
    op.drop_index(
        "uq_terminals_one_active_per_branch",
        table_name="terminals",
    )
    op.drop_constraint(
        "ck_terminals_active_requires_hybrid",
        "terminals",
        type_="check",
    )

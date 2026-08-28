"""Consolidate one branch to one active hybrid workspace, without rewriting history.

This is an explicit, branch-scoped maintenance command. It never deletes a
terminal and never rewrites terminal foreign keys on historical records. The
default mode is a locked dry run; mutation requires ``--apply`` plus both an
operator reason and a database-backup reference.

Example dry run::

    python -m scripts.merge_terminals_to_one \
      --company-id 11111111-1111-1111-1111-111111111111 \
      --branch-id 22222222-2222-2222-2222-222222222222 \
      --keep-terminal-id 33333333-3333-3333-3333-333333333333

Apply only after reviewing and saving the dry-run JSON manifest::

    python -m scripts.merge_terminals_to_one \
      --company-id 11111111-1111-1111-1111-111111111111 \
      --branch-id 22222222-2222-2222-2222-222222222222 \
      --keep-terminal-id 33333333-3333-3333-3333-333333333333 \
      --keep-name "Main Workspace" \
      --apply \
      --reason "Approved one-workspace rollout" \
      --backup-reference "s3://erp-backups/pre-workspace-merge-2026-08-28.dump"

The single-line JSON output is deterministic for the same arguments and
database state, making it suitable for release evidence and diff review.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from typing import TYPE_CHECKING, Any
from uuid import UUID

from sqlalchemy import func, select

from app.core.db import AsyncSessionLocal
from app.models import (
    Branch,
    Company,
    GamingSession,
    MembershipPayment,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundAttemptResolution,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    Order,
    OrderLine,
    PosRefundRequest,
    PosRefundWithdrawal,
    Refund,
    Shift,
    Terminal,
)

if TYPE_CHECKING:
    from collections.abc import Sequence

    from sqlalchemy.ext.asyncio import AsyncSession

_FINAL_SHIFT_STATUSES = ("closed", "reconciled")
_BLOCKER_KEYS = (
    "unsettled_shifts",
    "unfinished_orders",
    "unacknowledged_kitchen_cancellations",
    "running_gaming_sessions",
    "unbilled_gaming_sessions",
    "unresolved_membership_payments",
    "unresolved_membership_refunds",
    "unresolved_membership_refund_recoveries",
    "unresolved_pos_refunds",
)


async def _count(session: AsyncSession, statement) -> int:
    return int((await session.execute(statement)).scalar_one() or 0)


async def _collect_operational_blockers(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    terminal_ids: tuple[UUID, ...],
) -> dict[str, int]:
    """Mirror shift-close safety rules, including legacy inconsistent rows."""

    common_shift_scope = (
        Shift.company_id == company_id,
        Shift.branch_id == branch_id,
        Shift.terminal_id.in_(terminal_ids),
    )
    blockers = {
        "unsettled_shifts": await _count(
            session,
            select(func.count(func.distinct(Shift.id))).where(
                *common_shift_scope,
                Shift.status.not_in(_FINAL_SHIFT_STATUSES),
            ),
        ),
        "unfinished_orders": await _count(
            session,
            select(func.count(func.distinct(Order.id))).where(
                Order.company_id == company_id,
                Order.branch_id == branch_id,
                Order.terminal_id.in_(terminal_ids),
                Order.status.in_(("open", "held")),
            ),
        ),
        "unacknowledged_kitchen_cancellations": await _count(
            session,
            select(func.count(func.distinct(OrderLine.id)))
            .join(Order, Order.id == OrderLine.order_id)
            .where(
                Order.company_id == company_id,
                Order.branch_id == branch_id,
                Order.terminal_id.in_(terminal_ids),
                OrderLine.kitchen_released_at.is_not(None),
                OrderLine.voided_at.is_not(None),
                OrderLine.kitchen_void_acknowledged_at.is_(None),
            ),
        ),
        "running_gaming_sessions": await _count(
            session,
            select(func.count(func.distinct(GamingSession.id)))
            .join(Shift, Shift.id == GamingSession.shift_id)
            .where(
                *common_shift_scope,
                GamingSession.company_id == company_id,
                GamingSession.status.in_(("active", "paused")),
            ),
        ),
        "unbilled_gaming_sessions": await _count(
            session,
            select(func.count(func.distinct(GamingSession.id)))
            .join(Shift, Shift.id == GamingSession.shift_id)
            .where(
                *common_shift_scope,
                GamingSession.company_id == company_id,
                GamingSession.status == "ended",
                GamingSession.order_id.is_(None),
            ),
        ),
        "unresolved_membership_payments": await _count(
            session,
            select(func.count(func.distinct(MembershipPaymentRequest.id)))
            .outerjoin(
                MembershipPayment,
                MembershipPayment.request_id == MembershipPaymentRequest.id,
            )
            .outerjoin(
                MembershipPaymentRequestResolution,
                MembershipPaymentRequestResolution.request_id
                == MembershipPaymentRequest.id,
            )
            .where(
                MembershipPaymentRequest.company_id == company_id,
                MembershipPaymentRequest.branch_id == branch_id,
                MembershipPaymentRequest.terminal_id.in_(terminal_ids),
                MembershipPayment.id.is_(None),
                MembershipPaymentRequestResolution.id.is_(None),
            ),
        ),
        "unresolved_membership_refunds": await _count(
            session,
            select(func.count(func.distinct(MembershipRefund.id)))
            .outerjoin(
                MembershipRefundSettlement,
                MembershipRefundSettlement.refund_id == MembershipRefund.id,
            )
            .outerjoin(
                MembershipRefundResolution,
                MembershipRefundResolution.refund_id == MembershipRefund.id,
            )
            .where(
                MembershipRefund.company_id == company_id,
                MembershipRefund.branch_id == branch_id,
                MembershipRefund.terminal_id.in_(terminal_ids),
                MembershipRefundSettlement.id.is_(None),
                MembershipRefundResolution.id.is_(None),
            ),
        ),
        "unresolved_membership_refund_recoveries": await _count(
            session,
            select(func.count(func.distinct(MembershipRefundAttemptRecovery.id)))
            .outerjoin(
                MembershipRefundAttemptResolution,
                MembershipRefundAttemptResolution.recovery_id
                == MembershipRefundAttemptRecovery.id,
            )
            .where(
                MembershipRefundAttemptRecovery.company_id == company_id,
                MembershipRefundAttemptRecovery.source_branch_id == branch_id,
                MembershipRefundAttemptRecovery.source_terminal_id.in_(terminal_ids),
                MembershipRefundAttemptResolution.id.is_(None),
            ),
        ),
        "unresolved_pos_refunds": await _count(
            session,
            select(func.count(func.distinct(PosRefundRequest.id)))
            .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
            .outerjoin(
                PosRefundWithdrawal,
                PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
            )
            .where(
                PosRefundRequest.company_id == company_id,
                PosRefundRequest.branch_id == branch_id,
                PosRefundRequest.terminal_id.in_(terminal_ids),
                Refund.id.is_(None),
                PosRefundWithdrawal.id.is_(None),
            ),
        ),
    }
    # Keep the output schema stable even if construction above is reordered.
    return {key: blockers[key] for key in _BLOCKER_KEYS}


def _base_manifest(
    *,
    company_id: UUID,
    branch_id: UUID,
    keep_terminal_id: UUID,
    apply: bool,
    reason: str | None,
    backup_reference: str | None,
) -> dict[str, Any]:
    return {
        "active_terminal_ids_after": [],
        "active_terminal_ids_before": [],
        "archive_terminals": [],
        "backup_reference": backup_reference,
        "blockers": dict.fromkeys(_BLOCKER_KEYS, 0),
        "branch_id": str(branch_id),
        "company_id": str(company_id),
        "errors": [],
        "keep_name_after": None,
        "keep_name_before": None,
        "keep_terminal_id": str(keep_terminal_id),
        "mode": "apply" if apply else "dry_run",
        "operation": "consolidate_terminals_to_one",
        "preserves_historical_references": True,
        "reason": reason,
        "result": "refused",
        "schema_version": 1,
    }


async def consolidate(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    keep_terminal_id: UUID,
    keep_name: str | None = None,
    apply: bool = False,
    reason: str | None = None,
    backup_reference: str | None = None,
) -> dict[str, Any]:
    """Plan or atomically apply one branch's terminal consolidation."""

    clean_reason = reason.strip() if reason else None
    clean_backup_reference = backup_reference.strip() if backup_reference else None
    manifest = _base_manifest(
        company_id=company_id,
        branch_id=branch_id,
        keep_terminal_id=keep_terminal_id,
        apply=apply,
        reason=clean_reason,
        backup_reference=clean_backup_reference,
    )
    if apply and not clean_reason:
        manifest["errors"].append("--reason is required with --apply")
    if apply and not clean_backup_reference:
        manifest["errors"].append("--backup-reference is required with --apply")
    if manifest["errors"]:
        return manifest

    company_exists = (
        await session.execute(
            select(Company.id).where(
                Company.id == company_id,
                Company.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if company_exists is None:
        manifest["errors"].append("company not found or archived")
        return manifest

    # Match settings and shift-open lock order: branch first, then every
    # terminal row in deterministic UUID order. This gives blocker inspection
    # and apply one stable transactional snapshot.
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if branch is None:
        manifest["errors"].append("branch not found, archived, or outside company")
        return manifest

    terminals = (
        (
            await session.execute(
                select(Terminal)
                .where(Terminal.branch_id == branch_id)
                .order_by(Terminal.id)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    keeper = next(
        (terminal for terminal in terminals if terminal.id == keep_terminal_id),
        None,
    )
    if keeper is None:
        manifest["errors"].append("keeper terminal not found in the selected branch")
        return manifest

    terminals = sorted(terminals, key=lambda terminal: str(terminal.id))
    active_before = [terminal for terminal in terminals if terminal.is_active]
    to_archive = [
        terminal
        for terminal in active_before
        if terminal.id != keep_terminal_id
    ]
    target_name = keep_name.strip() if keep_name is not None else keeper.name
    manifest["active_terminal_ids_before"] = [
        str(terminal.id) for terminal in active_before
    ]
    manifest["active_terminal_ids_after"] = [str(keep_terminal_id)]
    manifest["archive_terminals"] = [
        {"id": str(terminal.id), "name": terminal.name}
        for terminal in to_archive
    ]
    manifest["keep_name_before"] = keeper.name
    manifest["keep_name_after"] = target_name

    if not target_name:
        manifest["errors"].append("keeper name cannot be blank")
    elif len(target_name) > 100:
        manifest["errors"].append("keeper name cannot exceed 100 characters")
    else:
        collision = next(
            (
                terminal
                for terminal in terminals
                if terminal.id != keep_terminal_id
                and terminal.name.casefold() == target_name.casefold()
            ),
            None,
        )
        if collision is not None:
            manifest["errors"].append(
                "keeper rename collides with preserved terminal "
                f"{collision.id} ({collision.name!r})"
            )

    terminal_ids = tuple(terminal.id for terminal in terminals)
    blockers = await _collect_operational_blockers(
        session,
        company_id=company_id,
        branch_id=branch_id,
        terminal_ids=terminal_ids,
    )
    manifest["blockers"] = blockers
    blocking_total = sum(blockers.values())
    if blocking_total:
        manifest["errors"].append(
            f"{blocking_total} unsettled operational record(s) must be resolved"
        )
    if manifest["errors"]:
        return manifest

    changes_required = bool(
        to_archive
        or not keeper.is_active
        or keeper.purpose != "hybrid"
        or keeper.name != target_name
    )
    if not apply:
        manifest["result"] = "planned" if changes_required else "no_change"
        return manifest

    for terminal in to_archive:
        terminal.is_active = False
    keeper.is_active = True
    keeper.purpose = "hybrid"
    keeper.name = target_name
    await session.flush()
    manifest["result"] = "applied" if changes_required else "no_change"
    return manifest


async def run(
    *,
    company_id: UUID,
    branch_id: UUID,
    keep_terminal_id: UUID,
    keep_name: str | None = None,
    apply: bool = False,
    reason: str | None = None,
    backup_reference: str | None = None,
) -> dict[str, Any]:
    async with AsyncSessionLocal() as session, session.begin():
        return await consolidate(
            session,
            company_id=company_id,
            branch_id=branch_id,
            keep_terminal_id=keep_terminal_id,
            keep_name=keep_name,
            apply=apply,
            reason=reason,
            backup_reference=backup_reference,
        )


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Keep one active hybrid workspace in exactly one company branch; "
            "dry-run unless --apply is supplied."
        )
    )
    parser.add_argument("--company-id", required=True, type=UUID)
    parser.add_argument("--branch-id", required=True, type=UUID)
    parser.add_argument("--keep-terminal-id", required=True, type=UUID)
    parser.add_argument(
        "--keep-name",
        default=None,
        help="Optionally rename only the surviving terminal",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Commit the reviewed plan; without this flag no rows are changed",
    )
    parser.add_argument(
        "--reason",
        default=None,
        help="Required with --apply: approved operational reason",
    )
    parser.add_argument(
        "--backup-reference",
        default=None,
        help="Required with --apply: identifier or URI of the verified pre-change backup",
    )
    return parser.parse_args(argv)


def _manifest_json(manifest: dict[str, Any]) -> str:
    return json.dumps(manifest, sort_keys=True, separators=(",", ":"))


def main(argv: Sequence[str] | None = None) -> None:
    args = _parse_args(argv)
    manifest = asyncio.run(
        run(
            company_id=args.company_id,
            branch_id=args.branch_id,
            keep_terminal_id=args.keep_terminal_id,
            keep_name=args.keep_name,
            apply=args.apply,
            reason=args.reason,
            backup_reference=args.backup_reference,
        )
    )
    print(_manifest_json(manifest))
    if manifest["result"] == "refused":
        raise SystemExit(2)


if __name__ == "__main__":
    main()

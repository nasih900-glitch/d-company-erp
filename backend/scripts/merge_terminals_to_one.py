"""Consolidate one branch to one active hybrid workspace, without rewriting history.

This is an explicit, branch-scoped maintenance command. It never deletes a
terminal and never rewrites terminal foreign keys on historical records. The
default mode is a locked dry run; mutation requires ``--apply`` plus both an
operator identity, reason, database-backup reference, and the exact state
fingerprint printed by the reviewed dry run.

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
      --actor-user-id 44444444-4444-4444-4444-444444444444 \
      --reason "Approved one-workspace rollout" \
      --backup-reference "s3://erp-backups/pre-workspace-merge-2026-08-28.dump" \
      --expected-state-fingerprint <sha256-from-dry-run>

The single-line JSON output is deterministic for the same arguments and
database state, making it suitable for release evidence and diff review.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from typing import TYPE_CHECKING, Any
from uuid import UUID

from sqlalchemy import func, select

from app.core.db import AsyncSessionLocal
from app.models import (
    AuditLog,
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
    User,
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
        "schema_version": 2,
        "state_fingerprint": None,
        "terminal_state_before": [],
    }


def _state_fingerprint(manifest: dict[str, Any]) -> str:
    """Hash only reviewed database state and intended terminal outcome."""

    payload = {
        key: manifest[key]
        for key in (
            "active_terminal_ids_after",
            "active_terminal_ids_before",
            "archive_terminals",
            "blockers",
            "branch_id",
            "company_id",
            "keep_name_after",
            "keep_name_before",
            "keep_terminal_id",
            "operation",
            "terminal_state_before",
        )
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


async def consolidate(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    keep_terminal_id: UUID,
    keep_name: str | None = None,
    apply: bool = False,
    actor_user_id: UUID | None = None,
    reason: str | None = None,
    backup_reference: str | None = None,
    expected_state_fingerprint: str | None = None,
) -> dict[str, Any]:
    """Plan or atomically apply one branch's terminal consolidation."""

    clean_reason = reason.strip() if reason else None
    clean_backup_reference = backup_reference.strip() if backup_reference else None
    clean_expected_fingerprint = (
        expected_state_fingerprint.strip().lower()
        if expected_state_fingerprint
        else None
    )
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
    elif clean_reason and len(clean_reason) > 500:
        manifest["errors"].append("--reason cannot exceed 500 characters")
    if apply and not clean_backup_reference:
        manifest["errors"].append("--backup-reference is required with --apply")
    if apply and actor_user_id is None:
        manifest["errors"].append("--actor-user-id is required with --apply")
    if apply and (
        clean_expected_fingerprint is None
        or len(clean_expected_fingerprint) != 64
        or any(character not in "0123456789abcdef" for character in clean_expected_fingerprint)
    ):
        manifest["errors"].append(
            "--expected-state-fingerprint must be a 64-character SHA-256 with --apply"
        )
    if manifest["errors"]:
        return manifest

    # Migration 0056's terminal constraints also cover soft-deleted companies.
    # Keep this explicit, backup-gated repair path available for their retained
    # historical rows instead of leaving an otherwise unfixable preflight.
    company_exists = (
        await session.execute(
            select(Company.id).where(
                Company.id == company_id,
            )
        )
    ).scalar_one_or_none()
    if company_exists is None:
        manifest["errors"].append("company not found")
        return manifest
    if apply:
        actor_exists = (
            await session.execute(
                select(User.id).where(
                    User.id == actor_user_id,
                    User.company_id == company_id,
                )
            )
        ).scalar_one_or_none()
        if actor_exists is None:
            manifest["errors"].append(
                "actor user not found in the selected company"
            )
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
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if branch is None:
        manifest["errors"].append("branch not found or outside company")
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
    manifest["terminal_state_before"] = [
        {
            "id": str(terminal.id),
            "is_active": bool(terminal.is_active),
            "name": terminal.name,
            "purpose": terminal.purpose,
        }
        for terminal in terminals
    ]

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
    manifest["state_fingerprint"] = _state_fingerprint(manifest)
    if apply and clean_expected_fingerprint != manifest["state_fingerprint"]:
        manifest["errors"].append(
            "state fingerprint mismatch; run a new dry run and review it before applying"
        )
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

    if changes_required:
        for terminal in to_archive:
            terminal.is_active = False
        keeper.is_active = True
        keeper.purpose = "hybrid"
        keeper.name = target_name
        session.add(
            AuditLog(
                actor_user_id=actor_user_id,
                company_id=company_id,
                action="terminal_workspace_consolidation",
                entity_type="Branch",
                entity_id=str(branch_id),
                before={
                    "active_terminal_ids": manifest["active_terminal_ids_before"],
                    "state_fingerprint": manifest["state_fingerprint"],
                    "terminal_state": manifest["terminal_state_before"],
                },
                after={
                    "active_terminal_ids": manifest["active_terminal_ids_after"],
                    "archived_terminal_ids": [
                        terminal["id"] for terminal in manifest["archive_terminals"]
                    ],
                    "backup_reference": clean_backup_reference,
                    "keeper_name": target_name,
                    "keeper_purpose": "hybrid",
                    "preserved_historical_references": True,
                },
                terminal_id=keeper.id,
                reason=clean_reason,
                user_agent="script/merge_terminals_to_one:v2",
            )
        )
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
    actor_user_id: UUID | None = None,
    reason: str | None = None,
    backup_reference: str | None = None,
    expected_state_fingerprint: str | None = None,
) -> dict[str, Any]:
    async with AsyncSessionLocal() as session, session.begin():
        return await consolidate(
            session,
            company_id=company_id,
            branch_id=branch_id,
            keep_terminal_id=keep_terminal_id,
            keep_name=keep_name,
            apply=apply,
            actor_user_id=actor_user_id,
            reason=reason,
            backup_reference=backup_reference,
            expected_state_fingerprint=expected_state_fingerprint,
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
    parser.add_argument(
        "--actor-user-id",
        default=None,
        type=UUID,
        help="Required with --apply: owner/operator user UUID persisted in Audit Log",
    )
    parser.add_argument(
        "--expected-state-fingerprint",
        default=None,
        help="Required with --apply: exact SHA-256 printed by the reviewed dry run",
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
            actor_user_id=args.actor_user_id,
            reason=args.reason,
            backup_reference=args.backup_reference,
            expected_state_fingerprint=args.expected_state_fingerprint,
        )
    )
    print(_manifest_json(manifest))
    if manifest["result"] == "refused":
        raise SystemExit(2)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Guarded, idempotent D Company setup-transaction itemization.

reconcile_d_company_history.py parked all 503 rows of the owner's
Transactions sheet in one lump journal entry (debit 1490 "Historical Setup
Costs Pending Classification", credit 1185 "Historical Funds Pending
Reconciliation") because the sheet's receipt-matched status was incomplete.
The sheet's Category column is a separate field and is populated on every
real row — verified against a live query of the full sheet, not a sample.
This script replaces that one lump entry with 14 per-category entries built
from the audited breakdown in scripts/data/d_company_transaction_itemization.py,
so Trial Balance and expense reports show real spending by category instead
of one undifferentiated blob.

The lump entry is voided in place (JournalEntry.voided_at/voided_by/
void_reason), never deleted — see migration 0028. The derived ledger
(app.services.accounting.ledger._posted_journal_ledger_lines) excludes
voided entries by construction, so voiding it first and inserting the
itemized entries second, in the same transaction, never double-counts.

Refuses to run unless reconcile_d_company_history.py has already reached its
fully-applied state (the 15-category chart of accounts and the lump entry
must already exist exactly as that script created them).

The command is a dry run unless ``--apply`` is supplied. Applying requires
both a literal confirmation and the exact state fingerprint printed by a
fresh dry run, and takes the SAME PostgreSQL advisory lock key as
reconcile_d_company_history.py (both scripts touch the same journal rows and
must never run concurrently with each other or with themselves).

Run from ``backend`` after migration 0028 is installed::

    python -m scripts.itemize_d_company_setup_transactions
    python -m scripts.itemize_d_company_setup_transactions \
      --apply \
      --confirm APPLY_D_COMPANY_TRANSACTION_ITEMIZATION_V1 \
      --expected-state-fingerprint <sha256-from-dry-run>
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any
from uuid import UUID, uuid5

from sqlalchemy import or_, select, text

from app.core.db import AsyncSessionLocal
from app.models import (
    Account,
    AuditLog,
    Branch,
    Company,
    ExpenseCategory,
    JournalEntry,
    JournalLine,
    User,
)
from app.services.accounting.accounts import DEFAULT_EXPENSE_CATEGORY_ACCOUNTS
from app.services.audit.recorder import clear_actor, install_audit_listeners, set_actor
from scripts.data.d_company_historical_reconciliation import (
    EXPECTED_COMPANY_ID,
    IMPORT_CREATED_BY_ID,
    TRANSACTIONS_TOTAL_MINOR,
    validate_payload as validate_reconciliation_payload,
)
from scripts.data.d_company_transaction_itemization import (
    CATEGORY_TOTALS,
    ITEMIZATION_SOURCE_REF_PREFIX,
    ITEMIZATION_VERSION,
    SETUP_VOID_REASON,
    validate_payload as validate_itemization_payload,
)
from scripts.reconcile_d_company_history import (
    ACCOUNT_SPECS,
    EXPECTED_EXPENSE_CATEGORY_IDS,
    EXPECTED_LIVE_ACCOUNT_IDS,
    SETUP_CREDIT_LINE_ID,
    SETUP_DEBIT_LINE_ID,
    SETUP_ENTRY_ID,
    SETUP_MEMO,
    SETUP_POSTED_AT,
    SETUP_REF_TYPE,
    ComponentState,
    ReconciliationError,
    _as_utc,
    _fail,
    _lock_key,
    _maybe_lock,
    _resolve_context,
    classify_presence,
    state_fingerprint,
)

if TYPE_CHECKING:
    from collections.abc import Sequence

    from sqlalchemy.ext.asyncio import AsyncSession


CONFIRMATION = "APPLY_D_COMPANY_TRANSACTION_ITEMIZATION_V1"

# Accounts this script needs: 1185 (credit target), 1490 (the old entry's
# debit line, verified but never posted to again), and every category's own
# expense account.
_NEEDED_ACCOUNT_CODES: tuple[str, ...] = tuple(
    sorted(
        {DEFAULT_EXPENSE_CATEGORY_ACCOUNTS[row.category_name].code for row in CATEGORY_TOTALS}
        | {"1185", "1490"}
    )
)


def itemized_entry_id(category_name: str) -> UUID:
    return uuid5(EXPECTED_COMPANY_ID, f"{ITEMIZATION_SOURCE_REF_PREFIX}:{category_name}")


def itemized_debit_line_id(entry_id: UUID) -> UUID:
    return uuid5(entry_id, "debit")


def itemized_credit_line_id(entry_id: UUID) -> UUID:
    return uuid5(entry_id, "credit")


def itemized_memo(category_name: str, row_count: int, amount_minor: int) -> str:
    return (
        f"Itemized from Transactions sheet Category={category_name!r} "
        f"({row_count} rows, {amount_minor} minor); replaces lump entry "
        f"{SETUP_ENTRY_ID} ({ITEMIZATION_VERSION})."
    )


# Every JournalEntry/JournalLine ID this script ever reads or writes — used to
# scan broadly (by ref_type/ref_id/journal_entry_id, not just by known ID) so
# any stray row sharing this ref_type or pointing at a known ID is detected
# and refused rather than silently ignored. Mirrors the duplicate-detection
# posture of reconcile_d_company_history.py's own entry/line lookups.
_ALL_ITEMIZED_ENTRY_IDS: tuple[UUID, ...] = tuple(
    itemized_entry_id(row.category_name) for row in CATEGORY_TOTALS
)
_KNOWN_ENTRY_IDS: tuple[UUID, ...] = (SETUP_ENTRY_ID, *_ALL_ITEMIZED_ENTRY_IDS)
_KNOWN_LINE_IDS: tuple[UUID, ...] = (
    SETUP_DEBIT_LINE_ID,
    SETUP_CREDIT_LINE_ID,
    *(
        line_id
        for entry_id in _ALL_ITEMIZED_ENTRY_IDS
        for line_id in (itemized_debit_line_id(entry_id), itemized_credit_line_id(entry_id))
    ),
)


@dataclass(slots=True)
class Inspection:
    company: Company
    branch: Branch
    actor: User
    accounts: dict[str, Account]
    categories: dict[str, ExpenseCategory]
    setup_entry: JournalEntry
    setup_lines: list[JournalLine]
    itemized_entries: dict[str, JournalEntry]
    itemized_lines: dict[str, list[JournalLine]]
    state: ComponentState
    fingerprint: str


async def _inspect(
    session: AsyncSession,
    *,
    branch_id: UUID | None,
    lock: bool,
) -> Inspection:
    company, branch, actor = await _resolve_context(session, branch_id=branch_id, lock=lock)

    account_stmt = select(Account).where(
        Account.company_id == company.id,
        Account.code.in_(_NEEDED_ACCOUNT_CODES),
    )
    account_rows = (await session.execute(_maybe_lock(account_stmt, lock=lock))).scalars().all()
    accounts = {row.code: row for row in account_rows}
    missing_codes = sorted(set(_NEEDED_ACCOUNT_CODES) - set(accounts))
    if missing_codes:
        _fail(
            f"required GL accounts are missing: {missing_codes}; "
            "run reconcile_d_company_history.py --apply first"
        )
    for code, account in accounts.items():
        name, account_type, normal_side = ACCOUNT_SPECS[code]
        expected_id = EXPECTED_LIVE_ACCOUNT_IDS.get(code, uuid5(company.id, f"account:{code}"))
        if (
            account.id != expected_id
            or account.name != name
            or account.type != account_type
            or account.normal_side != normal_side
            or not account.is_active
        ):
            _fail(f"account {code} does not match the audited production snapshot")

    category_stmt = select(ExpenseCategory).where(
        ExpenseCategory.company_id == company.id,
        ExpenseCategory.name.in_([row.category_name for row in CATEGORY_TOTALS]),
    )
    category_rows = (await session.execute(_maybe_lock(category_stmt, lock=lock))).scalars().all()
    categories = {row.name: row for row in category_rows}
    if len(categories) != len(CATEGORY_TOTALS):
        _fail(
            "one or more required expense categories are missing; "
            "run reconcile_d_company_history.py --apply first"
        )
    for total in CATEGORY_TOTALS:
        category = categories[total.category_name]
        definition = DEFAULT_EXPENSE_CATEGORY_ACCOUNTS[total.category_name]
        if (
            category.id != EXPECTED_EXPENSE_CATEGORY_IDS[total.category_name]
            or category.code != definition.code
            or category.gl_account_id != accounts[definition.code].id
        ):
            _fail(
                f"expense category {total.category_name!r} is not fully wired to its GL "
                "account; run reconcile_d_company_history.py --apply first"
            )

    # Broad scan: anything sharing this ref_type, or whose ref_id points at a
    # known entry, not just the known IDs — so a stray row from elsewhere
    # (a bug, a manual insert, a future script reusing this ref_type) is
    # refused rather than silently ignored.
    entry_stmt = select(JournalEntry).where(
        JournalEntry.company_id == company.id,
        or_(
            JournalEntry.id.in_(_KNOWN_ENTRY_IDS),
            JournalEntry.ref_type == SETUP_REF_TYPE,
            JournalEntry.ref_id.in_(_KNOWN_ENTRY_IDS),
        ),
    )
    entry_rows = (await session.execute(_maybe_lock(entry_stmt, lock=lock))).scalars().all()
    entries_by_id = {row.id: row for row in entry_rows}
    unexpected_entries = sorted(str(entry_id) for entry_id in entries_by_id if entry_id not in _KNOWN_ENTRY_IDS)
    if unexpected_entries:
        _fail(
            f"unexpected journal entry(ies) sharing ref_type={SETUP_REF_TYPE!r} or "
            f"pointing at a known entry: {unexpected_entries}"
        )
    setup_entry = entries_by_id.get(SETUP_ENTRY_ID)
    if setup_entry is None:
        _fail(
            "the lump setup journal entry does not exist; "
            "run reconcile_d_company_history.py --apply first"
        )
    itemized_ids: dict[UUID, str] = {
        itemized_entry_id(row.category_name): row.category_name for row in CATEGORY_TOTALS
    }
    itemized_entries = {
        itemized_ids[entry_id]: entry
        for entry_id, entry in entries_by_id.items()
        if entry_id != SETUP_ENTRY_ID
    }

    line_id_to_category: dict[UUID, str] = {}
    for entry_id, category_name in itemized_ids.items():
        line_id_to_category[itemized_debit_line_id(entry_id)] = category_name
        line_id_to_category[itemized_credit_line_id(entry_id)] = category_name

    line_stmt = select(JournalLine).where(
        or_(
            JournalLine.journal_entry_id.in_(_KNOWN_ENTRY_IDS),
            JournalLine.id.in_(_KNOWN_LINE_IDS),
        )
    )
    line_rows = list((await session.execute(_maybe_lock(line_stmt, lock=lock))).scalars().all())
    unexpected_lines = sorted(
        str(row.id)
        for row in line_rows
        if row.id not in _KNOWN_LINE_IDS or row.journal_entry_id not in _KNOWN_ENTRY_IDS
    )
    if unexpected_lines:
        _fail(f"unexpected journal line(s) attached to a known entry: {unexpected_lines}")

    setup_lines = sorted(
        (row for row in line_rows if row.journal_entry_id == SETUP_ENTRY_ID),
        key=lambda row: str(row.id),
    )
    if len(setup_lines) != 2:
        _fail("lump setup journal does not have exactly its 2 audited lines")

    itemized_lines: dict[str, list[JournalLine]] = {}
    for row in line_rows:
        category_name = line_id_to_category.get(row.id)
        if category_name is None:
            continue
        itemized_lines.setdefault(category_name, []).append(row)

    entries_state = classify_presence(
        found=len(itemized_entries),
        expected=len(CATEGORY_TOTALS),
        label="itemized journal entries",
    )
    lines_found = sum(len(rows) for rows in itemized_lines.values())
    lines_state = classify_presence(
        found=lines_found,
        expected=len(CATEGORY_TOTALS) * 2,
        label="itemized journal lines",
    )
    if entries_state is not lines_state:
        _fail("itemized entries and lines disagree on fresh/applied state")
    state = entries_state

    setup_voided = setup_entry.voided_at is not None
    if state is ComponentState.FRESH and setup_voided:
        _fail("lump entry is voided but no itemized entries exist yet")
    if state is ComponentState.APPLIED and not setup_voided:
        _fail("itemized entries exist but the lump entry was never voided")

    if state is ComponentState.FRESH:
        if not (
            setup_entry.company_id == company.id
            and setup_entry.branch_id == branch.id
            and setup_entry.ref_type == SETUP_REF_TYPE
            and setup_entry.ref_id == SETUP_ENTRY_ID
            and _as_utc(setup_entry.posted_at) == SETUP_POSTED_AT
            and setup_entry.memo == SETUP_MEMO
            and int(setup_entry.total_minor) == TRANSACTIONS_TOTAL_MINOR
            and setup_entry.voided_at is None
            and setup_entry.voided_by is None
            and setup_entry.void_reason is None
        ):
            _fail("lump setup journal header fingerprint mismatch")
        by_id = {row.id: row for row in setup_lines}
        for line_id, code, side in ((SETUP_DEBIT_LINE_ID, "1490", "dr"), (SETUP_CREDIT_LINE_ID, "1185", "cr")):
            line = by_id.get(line_id)
            if line is None or not (
                line.journal_entry_id == SETUP_ENTRY_ID
                and line.account_id == accounts[code].id
                and line.side == side
                and int(line.amount_minor) == TRANSACTIONS_TOTAL_MINOR
                and line.memo == SETUP_MEMO
            ):
                _fail(f"lump setup journal line fingerprint mismatch: {line_id}")
    else:
        if not (
            setup_entry.voided_by == IMPORT_CREATED_BY_ID
            and setup_entry.void_reason == SETUP_VOID_REASON
        ):
            _fail("lump entry was voided by something other than this itemization")
        for total in CATEGORY_TOTALS:
            entry = itemized_entries.get(total.category_name)
            entry_id = itemized_entry_id(total.category_name)
            definition = DEFAULT_EXPENSE_CATEGORY_ACCOUNTS[total.category_name]
            memo = itemized_memo(total.category_name, total.row_count, total.amount_minor)
            if entry is None or not (
                entry.id == entry_id
                and entry.company_id == company.id
                and entry.branch_id == branch.id
                and entry.ref_type == SETUP_REF_TYPE
                and entry.ref_id == entry_id
                and _as_utc(entry.posted_at) == SETUP_POSTED_AT
                and entry.memo == memo
                and int(entry.total_minor) == total.amount_minor
                and entry.voided_at is None
            ):
                _fail(f"itemized entry fingerprint mismatch: {total.category_name!r}")
            rows = {row.id: row for row in itemized_lines.get(total.category_name, [])}
            expected_lines = (
                (itemized_debit_line_id(entry_id), definition.code, "dr"),
                (itemized_credit_line_id(entry_id), "1185", "cr"),
            )
            for line_id, code, side in expected_lines:
                line = rows.get(line_id)
                if line is None or not (
                    line.journal_entry_id == entry_id
                    and line.account_id == accounts[code].id
                    and line.side == side
                    and int(line.amount_minor) == total.amount_minor
                    and line.memo == memo
                ):
                    _fail(f"itemized line fingerprint mismatch: {total.category_name!r}/{line_id}")

    manifest = {
        "version": ITEMIZATION_VERSION,
        "company": {"id": company.id, "name": company.name},
        "branch": {"id": branch.id, "name": branch.name},
        "actor": {"id": actor.id, "status": actor.status},
        "accounts": [
            {"code": row.code, "id": row.id, "name": row.name, "is_active": row.is_active}
            for row in sorted(account_rows, key=lambda row: row.code)
        ],
        "categories": [
            {"name": row.name, "id": row.id, "code": row.code, "gl_account_id": row.gl_account_id}
            for row in sorted(category_rows, key=lambda row: row.name)
        ],
        "setup_entry": {
            "id": setup_entry.id,
            "total_minor": int(setup_entry.total_minor),
            "voided_at": setup_entry.voided_at,
            "voided_by": setup_entry.voided_by,
            "void_reason": setup_entry.void_reason,
        },
        "setup_lines": [
            {"id": row.id, "account_id": row.account_id, "side": row.side, "amount_minor": int(row.amount_minor)}
            for row in setup_lines
        ],
        "itemized_entries": [
            {
                "category": name,
                "id": entry.id,
                "total_minor": int(entry.total_minor),
                "posted_at": entry.posted_at,
            }
            for name, entry in sorted(itemized_entries.items())
        ],
        "itemized_lines": [
            {
                "category": name,
                "id": row.id,
                "account_id": row.account_id,
                "side": row.side,
                "amount_minor": int(row.amount_minor),
            }
            for name, rows in sorted(itemized_lines.items())
            for row in sorted(rows, key=lambda row: str(row.id))
        ],
    }
    return Inspection(
        company=company,
        branch=branch,
        actor=actor,
        accounts=accounts,
        categories=categories,
        setup_entry=setup_entry,
        setup_lines=setup_lines,
        itemized_entries=itemized_entries,
        itemized_lines=itemized_lines,
        state=state,
        fingerprint=state_fingerprint(manifest),
    )


def _plan(inspection: Inspection, *, applied: bool = False) -> dict[str, Any]:
    return {
        "mode": "applied" if applied else "dry-run",
        "itemization_version": ITEMIZATION_VERSION,
        "company_id": str(inspection.company.id),
        "branch_id": str(inspection.branch.id),
        "state_fingerprint": inspection.fingerprint,
        "component_state": inspection.state.value,
        "intended_result": {
            "void_lump_entry_id": str(SETUP_ENTRY_ID),
            "lump_amount_minor": TRANSACTIONS_TOTAL_MINOR,
            "insert_itemized_entries": len(CATEGORY_TOTALS),
            "itemized_breakdown": {
                row.category_name: {
                    "amount_minor": row.amount_minor,
                    "row_count": row.row_count,
                }
                for row in CATEGORY_TOTALS
            },
        },
        "warnings": [
            "The original lump entry is voided in place, never deleted.",
            "Every itemized entry keeps ref_type=historical_setup_reconciliation, "
            "already allowlisted by the derived ledger — no ledger.py change needed "
            "beyond excluding voided entries.",
        ],
    }


async def _apply(session: AsyncSession, inspection: Inspection) -> None:
    if inspection.state is ComponentState.APPLIED:
        return
    now = datetime.now(UTC)
    inspection.setup_entry.voided_at = now
    inspection.setup_entry.voided_by = IMPORT_CREATED_BY_ID
    inspection.setup_entry.void_reason = SETUP_VOID_REASON

    for total in CATEGORY_TOTALS:
        entry_id = itemized_entry_id(total.category_name)
        definition = DEFAULT_EXPENSE_CATEGORY_ACCOUNTS[total.category_name]
        memo = itemized_memo(total.category_name, total.row_count, total.amount_minor)
        entry = JournalEntry(
            id=entry_id,
            company_id=inspection.company.id,
            branch_id=inspection.branch.id,
            ref_type=SETUP_REF_TYPE,
            ref_id=entry_id,
            posted_at=SETUP_POSTED_AT,
            memo=memo,
            total_minor=total.amount_minor,
        )
        session.add(entry)
        session.add_all(
            [
                JournalLine(
                    id=itemized_debit_line_id(entry_id),
                    journal_entry_id=entry.id,
                    account_id=inspection.accounts[definition.code].id,
                    side="dr",
                    amount_minor=total.amount_minor,
                    memo=memo,
                ),
                JournalLine(
                    id=itemized_credit_line_id(entry_id),
                    journal_entry_id=entry.id,
                    account_id=inspection.accounts["1185"].id,
                    side="cr",
                    amount_minor=total.amount_minor,
                    memo=memo,
                ),
            ]
        )
    await session.flush()


async def _run(
    *,
    apply: bool,
    branch_id: UUID | None,
    expected_state_fingerprint: str | None,
) -> dict[str, Any]:
    validate_reconciliation_payload()
    validate_itemization_payload()
    install_audit_listeners()
    async with AsyncSessionLocal() as session:
        if apply:
            set_actor(
                user_id=IMPORT_CREATED_BY_ID,
                company_id=EXPECTED_COMPANY_ID,
                ip=None,
                user_agent=f"script/{ITEMIZATION_VERSION}",
            )
        try:
            async with session.begin():
                if apply:
                    await session.execute(text("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"))
                    await session.execute(
                        text("SELECT pg_advisory_xact_lock(:lock_key)"),
                        {"lock_key": _lock_key()},
                    )
                before = await _inspect(session, branch_id=branch_id, lock=apply)
                if not apply:
                    return _plan(before)
                if expected_state_fingerprint != before.fingerprint:
                    _fail(
                        "state fingerprint mismatch; run a new dry run and review it "
                        "before applying"
                    )
                if before.state is ComponentState.APPLIED:
                    result = _plan(before, applied=True)
                    result["no_op"] = True
                    return result

                await _apply(session, before)
                await session.flush()

                after = await _inspect(session, branch_id=before.branch.id, lock=True)
                if after.state is not ComponentState.APPLIED:
                    _fail("post-apply verification did not reach the complete state")
                session.add(
                    AuditLog(
                        actor_user_id=IMPORT_CREATED_BY_ID,
                        company_id=after.company.id,
                        action="transaction_itemization_applied",
                        entity_type="Company",
                        entity_id=str(after.company.id),
                        before={"state_fingerprint": before.fingerprint},
                        after={
                            "itemization_version": ITEMIZATION_VERSION,
                            "state_fingerprint": after.fingerprint,
                            "itemized_entries": len(CATEGORY_TOTALS),
                            "voided_lump_entry": str(SETUP_ENTRY_ID),
                        },
                        ip=None,
                        user_agent=f"script/{ITEMIZATION_VERSION}",
                    )
                )
                await session.flush()
                result = _plan(after, applied=True)
                result["no_op"] = False
                result["previous_state_fingerprint"] = before.fingerprint
                return result
        finally:
            if apply:
                clear_actor()


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="commit the itemization; default is a read-only dry run",
    )
    parser.add_argument(
        "--confirm",
        help=f"required with --apply; must equal {CONFIRMATION}",
    )
    parser.add_argument(
        "--expected-state-fingerprint",
        help="required with --apply; copy from the immediately preceding dry run",
    )
    parser.add_argument(
        "--branch-id",
        type=UUID,
        help="target branch UUID; optional only when D Company has one active branch",
    )
    args = parser.parse_args(argv)
    if args.apply:
        if args.confirm != CONFIRMATION:
            parser.error(f"--apply requires --confirm {CONFIRMATION}")
        if not args.expected_state_fingerprint:
            parser.error("--apply requires --expected-state-fingerprint")
        if len(args.expected_state_fingerprint) != 64:
            parser.error("--expected-state-fingerprint must be a 64-character SHA-256")
    elif args.confirm or args.expected_state_fingerprint:
        parser.error("--confirm and --expected-state-fingerprint only apply with --apply")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        result = asyncio.run(
            _run(
                apply=args.apply,
                branch_id=args.branch_id,
                expected_state_fingerprint=args.expected_state_fingerprint,
            )
        )
    except ReconciliationError as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

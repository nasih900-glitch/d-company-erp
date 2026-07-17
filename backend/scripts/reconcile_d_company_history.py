#!/usr/bin/env python3
"""Guarded, idempotent D Company historical accounting reconciliation.

The command is a dry run unless ``--apply`` is supplied.  Applying requires
both a literal confirmation and the exact state fingerprint printed by a
fresh dry run.  It then holds a PostgreSQL transaction advisory lock, locks
the target rows, re-checks every audited production fingerprint, and commits
all changes atomically.

Run from ``backend`` after migrations 0026 and 0027 are installed::

    python -m scripts.reconcile_d_company_history
    python -m scripts.reconcile_d_company_history \
      --apply \
      --confirm APPLY_D_COMPANY_HISTORICAL_RECONCILIATION_V1 \
      --expected-state-fingerprint <sha256-from-dry-run>

This script never deletes an invoice, payment, gaming session, or capital
entry.  Incorrect historical rows are voided in place.  The 503 setup
transactions remain explicitly pending classification because the available
receipt evidence is incomplete; the script does not invent expenses/assets.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import sys
from dataclasses import dataclass
from datetime import UTC, date, datetime, time
from decimal import Decimal
from enum import StrEnum
from typing import TYPE_CHECKING, Any
from uuid import UUID, uuid5
from zoneinfo import ZoneInfo

from sqlalchemy import or_, select, text

from app.core.db import AsyncSessionLocal
from app.models import (
    Account,
    AuditLog,
    Branch,
    CapitalEntry,
    Company,
    ExpenseCategory,
    GamingSession,
    JournalEntry,
    JournalLine,
    ManualCollection,
    Order,
    Partner,
    Payment,
    User,
)
from app.services.accounting.accounts import (
    DEFAULT_CHART_OF_ACCOUNTS,
    DEFAULT_EXPENSE_CATEGORY_ACCOUNTS,
)
from app.services.audit.recorder import (
    clear_actor,
    install_audit_listeners,
    set_actor,
)
from scripts.data.d_company_historical_reconciliation import (
    BAD_CAPITAL_ENTRIES,
    CAPITAL_IMPORT_ROWS,
    COLLECTION_IMPORT_ROWS,
    EXPECTED_COMPANY_ID,
    EXPECTED_COMPANY_NAME,
    IMPORT_CREATED_BY_ID,
    PARTNER_TARGETS,
    RECONCILIATION_VERSION,
    SYNTHETIC_ORDERS,
    TRANSACTIONS_MATCHED_MINOR,
    TRANSACTIONS_SHEET_ROW_COUNT,
    TRANSACTIONS_SOURCE_REF,
    TRANSACTIONS_TOTAL_MINOR,
    validate_payload,
)

if TYPE_CHECKING:
    from collections.abc import Sequence
    from typing import NoReturn

    from sqlalchemy.ext.asyncio import AsyncSession


CONFIRMATION = "APPLY_D_COMPANY_HISTORICAL_RECONCILIATION_V1"
SHEET_ID = "1gvsKfigUkrj8-wVvuOlbo7GNqjnJLnFZiGCkIodmRVk"
SETUP_POSTED_AT = datetime(2026, 6, 30, 6, 30, tzinfo=UTC)
SETUP_REF_TYPE = "historical_setup_reconciliation"
SETUP_ENTRY_ID = uuid5(EXPECTED_COMPANY_ID, TRANSACTIONS_SOURCE_REF)
SETUP_DEBIT_LINE_ID = uuid5(SETUP_ENTRY_ID, "debit-1490")
SETUP_CREDIT_LINE_ID = uuid5(SETUP_ENTRY_ID, "credit-1185")
CAPITAL_VOID_REASON = (
    "Erroneous equal-split capital import voided by the audited July 2026 "
    "historical reconciliation; original row retained for traceability."
)
ORDER_VOID_MARKER = f"[reconciliation={RECONCILIATION_VERSION};replaced_by=manual_collections]"
SESSION_CANCEL_REASON = (
    "Synthetic gaming backfill replaced by auditable aggregate manual "
    f"collections ({RECONCILIATION_VERSION}); invoice history retained."
)
SETUP_MEMO = (
    f"Google Sheet {SHEET_ID} Transactions: {TRANSACTIONS_SHEET_ROW_COUNT} rows; "
    f"total_minor={TRANSACTIONS_TOTAL_MINOR}; "
    f"matched_minor={TRANSACTIONS_MATCHED_MINOR}; "
    f"unmatched_minor={TRANSACTIONS_TOTAL_MINOR - TRANSACTIONS_MATCHED_MINOR}; "
    "pending receipt review and accountant classification."
)

ACCOUNT_SPECS: dict[str, tuple[str, str, str]] = {
    account.code: (account.name, account.type, account.normal_side)
    for account in DEFAULT_CHART_OF_ACCOUNTS
}
LEGACY_ACCOUNT_NAMES: dict[str, str] = {
    "1110": "UPI Clearing",
    "2110": "Output CGST Payable",
    "2120": "Output SGST Payable",
    "2130": "Output IGST Payable",
    "2140": "Output Cess Payable",
    "2400": "Tips Payable to Staff",
    "4000": "Revenue — Food",
    "4160": "Revenue - Streaming",
    "5000": "COGS",
    "5800": "Round-off (Income/Expense)",
}
EXPECTED_LIVE_ACCOUNT_IDS: dict[str, UUID] = {
    "1000": UUID("a0183c6c-c08b-45c4-b685-fbd5ef7ed94b"),
    "1010": UUID("49baafb1-d4b4-4f7a-bed4-a5a3697dc373"),
    "1100": UUID("f6828d1f-076b-46aa-bebd-2981326b42c1"),
    "1110": UUID("2dfe1c38-7b64-41db-bebf-6b938d95f79f"),
    "1200": UUID("2c403a0f-a59c-408b-93f8-2ff86f0fc6a1"),
    "1500": UUID("9deea2bd-8177-447c-82b5-71e0f87cf101"),
    "2000": UUID("9b3d1d57-5ab7-4dc8-8a00-84197364509f"),
    "2100": UUID("d0e2c4f1-3aa8-437d-9ae6-ba4b63b6ce62"),
    "2110": UUID("45dbbc29-c25f-4d69-a222-cabd676232a9"),
    "2120": UUID("47c04acd-0f21-4dcf-b6c8-963bc4ded4bd"),
    "2130": UUID("ff63b84c-d9c3-42df-9da2-bc8130d6581f"),
    "2140": UUID("140986b1-bdc8-493c-a333-6fa0b47ca094"),
    "2400": UUID("8f90efcb-4be2-4b47-a2a8-9d25eb12204c"),
    "3000": UUID("173f6064-30ff-414a-bd4a-69cc9ffdd5f6"),
    "4000": UUID("e19945dd-01b4-435b-92ed-242294c906f7"),
    "4010": UUID("c67e8297-2d01-4bfa-a314-072e47aa5617"),
    "4020": UUID("8b7cb223-7932-42bb-8c21-921feeba08ba"),
    "4100": UUID("59df42bd-8525-48c9-b112-4daad530e6ea"),
    "4150": UUID("f19ee13f-3dd6-44ae-a182-06e888618bb0"),
    "4160": UUID("33ad1371-5dde-4659-abed-f7ec85e3630a"),
    "4200": UUID("8d53f968-e2a9-496b-9dd9-fa46553c0561"),
    "5000": UUID("732b2f70-f580-4237-ba39-fe95c42bcd50"),
    "5100": UUID("f955262b-6e85-4890-bf3b-c8ee7ece0183"),
    "5200": UUID("0be9df53-6ec6-4e02-9556-d8af6b2aeb7c"),
    "5300": UUID("0194bb0f-4a81-4451-b923-587981a9b0d9"),
    "5400": UUID("adda0ac8-e4e9-469a-a6c2-305eef2a66c5"),
    "5500": UUID("3559a5f3-b80c-445b-8e70-ad9a243475a5"),
    "5800": UUID("61262a17-550c-4248-8156-9fd173a42665"),
    "5900": UUID("e75fa221-b0ac-4172-a21c-12f3276cb51c"),
}
EXPECTED_EXPENSE_CATEGORY_IDS: dict[str, UUID] = {
    "Building Materials": UUID("06285add-0053-436d-bfc2-193b4abddcf0"),
    "Cafe Equipments": UUID("d66f7301-f501-41f2-acb5-44db1e9d279d"),
    "Design & Architecture": UUID("c3ca25a4-27bb-42e3-b842-f731cd917e93"),
    "Food & Water": UUID("57977e1d-87d3-4fe6-a4dd-fe9cc2b2b7b2"),
    "Furnitures": UUID("dc389d3c-881e-4be2-8d41-6e52662429d1"),
    "Gaming Equipments": UUID("89c78526-5c25-4803-a3d6-2031054572f0"),
    "Kitchen Equipments": UUID("dcf2c52f-9f4d-4d52-aa7d-6b87417e52c6"),
    "Labour Charges": UUID("495e9638-e0ed-4bad-9cb9-6580e4c0b76d"),
    "Marketing": UUID("cfc04ff4-bac5-4717-9c67-b7afa2f7b7d6"),
    "Others": UUID("367a1b5c-632f-49a4-aa8c-3723f0dde49c"),
    "PaperWork Charges": UUID("3b7b3ca0-ccd4-4723-adfc-d6d52918e7a8"),
    "Rent": UUID("884579f5-14c2-4f52-82f3-1f04062e9b16"),
    "Transportation Cost": UUID("3798b948-43f6-4a7e-989e-066274fee3be"),
    "Utilities": UUID("3f710377-d72c-4a0e-abb7-841e009dbcb1"),
    "Wages": UUID("5a3aa3aa-d242-45e6-a8b5-6287faa9ecbb"),
}


class ReconciliationError(RuntimeError):
    """Raised when production does not exactly match an audited state."""


class ComponentState(StrEnum):
    FRESH = "fresh"
    APPLIED = "applied"


@dataclass(slots=True)
class Inspection:
    company: Company
    branch: Branch
    actor: User
    partners: dict[str, Partner]
    bad_capital: dict[UUID, CapitalEntry]
    imported_capital: dict[str, CapitalEntry]
    orders: dict[UUID, Order]
    sessions: dict[UUID, GamingSession]
    payments: dict[UUID, Payment]
    collections: dict[str, ManualCollection]
    accounts: dict[str, Account]
    expense_categories: dict[str, ExpenseCategory]
    setup_entry: JournalEntry | None
    setup_lines: list[JournalLine]
    capital_state: ComponentState
    collections_state: ComponentState
    setup_state: ComponentState
    missing_account_codes: tuple[str, ...]
    account_rename_codes: tuple[str, ...]
    expense_category_changes: tuple[str, ...]
    fingerprint: str

    @property
    def is_fully_applied(self) -> bool:
        return (
            self.capital_state is ComponentState.APPLIED
            and self.collections_state is ComponentState.APPLIED
            and self.setup_state is ComponentState.APPLIED
            and not self.missing_account_codes
            and not self.account_rename_codes
            and not self.expense_category_changes
        )


def _fail(message: str) -> NoReturn:
    raise ReconciliationError(message)


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def _json_safe(value: Any) -> Any:
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, datetime):
        return _as_utc(value).isoformat().replace("+00:00", "Z")
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_json_safe(item) for item in value]
    return value


def state_fingerprint(manifest: dict[str, Any]) -> str:
    """Return an order-stable SHA-256 fingerprint for an inspected state."""
    encoded = json.dumps(
        _json_safe(manifest),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def classify_presence(*, found: int, expected: int, label: str) -> ComponentState:
    """Classify an idempotency marker set, rejecting partial application."""
    if found == 0:
        return ComponentState.FRESH
    if found == expected:
        return ComponentState.APPLIED
    _fail(f"{label} is partially applied: found {found} of {expected} rows")


def _append_marker(note: str | None, marker: str) -> str:
    if marker in (note or ""):
        return note or marker
    combined = f"{note.rstrip()} {marker}" if note else marker
    if len(combined) > 500:
        _fail("synthetic order note cannot preserve its original text within 500 characters")
    return combined


def _capital_note(sheet_row: int, original_note: str | None) -> str:
    if original_note:
        return original_note
    return f"Imported from owner Partner Investments sheet row {sheet_row}."


def _collection_note(business_date: date, source_kind: str) -> str:
    if source_kind == "legacy_daily":
        return (
            "Legacy aggregate copied from the owner's Daily collection sheet; "
            "no itemized order or tax invoice exists."
        )
    return (
        f"Owner-attested manual collection for {business_date.isoformat()}; "
        "the POS was not used for these sales."
    )


def _local_noon_utc(business_date: date, timezone_name: str) -> datetime:
    local = datetime.combine(business_date, time(12), ZoneInfo(timezone_name))
    return local.astimezone(UTC)


def _lock_key() -> int:
    raw = hashlib.sha256(f"{RECONCILIATION_VERSION}:{EXPECTED_COMPANY_ID}".encode()).digest()[:8]
    return int.from_bytes(raw, byteorder="big", signed=True)


def _maybe_lock(statement: Any, *, lock: bool) -> Any:
    return statement.with_for_update() if lock else statement


async def _resolve_context(
    session: AsyncSession,
    *,
    branch_id: UUID | None,
    lock: bool,
) -> tuple[Company, Branch, User]:
    company = await session.get(Company, EXPECTED_COMPANY_ID, with_for_update=lock)
    if company is None or company.deleted_at is not None:
        _fail(f"active company {EXPECTED_COMPANY_ID} not found")
    if company.name != EXPECTED_COMPANY_NAME:
        _fail(f"company name mismatch: expected {EXPECTED_COMPANY_NAME!r}, got {company.name!r}")
    if company.timezone != "Asia/Kolkata":
        _fail(f"company timezone must be Asia/Kolkata, got {company.timezone!r}")
    if company.gst_registration_type != "unregistered":
        _fail("aggregate manual collections are only valid while D Company is GST-unregistered")

    branch_stmt = select(Branch).where(
        Branch.company_id == company.id,
        Branch.deleted_at.is_(None),
    )
    if branch_id is not None:
        branch_stmt = branch_stmt.where(Branch.id == branch_id)
    branches = (
        (await session.execute(_maybe_lock(branch_stmt.order_by(Branch.id), lock=lock)))
        .scalars()
        .all()
    )
    if len(branches) != 1:
        _fail(
            "branch selection must resolve to exactly one active D Company branch; "
            "pass --branch-id when more than one exists"
        )
    branch = branches[0]

    actor = await session.get(User, IMPORT_CREATED_BY_ID, with_for_update=lock)
    if (
        actor is None
        or actor.company_id != company.id
        or actor.deleted_at is not None
        or actor.status != "active"
    ):
        _fail(f"active reconciliation actor {IMPORT_CREATED_BY_ID} not found in D Company")
    return company, branch, actor


async def _inspect_partners_and_capital(
    session: AsyncSession,
    *,
    company: Company,
    lock: bool,
) -> tuple[
    dict[str, Partner],
    dict[UUID, CapitalEntry],
    dict[str, CapitalEntry],
    ComponentState,
    list[dict[str, Any]],
]:
    target_ids = [target.id for target in PARTNER_TARGETS]
    partner_stmt = select(Partner).where(Partner.id.in_(target_ids))
    partners_found = (await session.execute(_maybe_lock(partner_stmt, lock=lock))).scalars().all()
    partners = {partner.name: partner for partner in partners_found}
    if len(partners_found) != len(PARTNER_TARGETS):
        _fail("one or more audited partner rows are missing")
    for target in PARTNER_TARGETS:
        partner = next((row for row in partners_found if row.id == target.id), None)
        if partner is None or not (
            partner.company_id == company.id
            and partner.name == target.name
            and Decimal(str(partner.share_pct)) == target.ownership_share_pct
            and _as_utc(partner.joined_at) == target.joined_at
            and partner.notes == target.notes
        ):
            _fail(f"partner fingerprint mismatch for {target.name}")

    all_stmt = (
        select(CapitalEntry)
        .join(Partner, Partner.id == CapitalEntry.partner_id)
        .where(Partner.company_id == company.id)
        .order_by(CapitalEntry.id)
    )
    all_entries = (await session.execute(_maybe_lock(all_stmt, lock=lock))).scalars().all()
    by_id = {entry.id: entry for entry in all_entries}
    bad: dict[UUID, CapitalEntry] = {}
    target_by_name = {target.name: target for target in PARTNER_TARGETS}
    for expected in BAD_CAPITAL_ENTRIES:
        entry = by_id.get(expected.id)
        if entry is None:
            _fail(f"audited bad capital row {expected.id} is missing")
        target = target_by_name[expected.partner_name]
        exact = (
            entry.partner_id == target.id
            and entry.type == "invest"
            and int(entry.amount_minor) == expected.amount_minor
            and _as_utc(entry.effective_at) == expected.effective_at
            and entry.note == expected.note
            and entry.settlement_account == "bank"
            and entry.source_ref is None
            and entry.created_by is None
        )
        if not exact:
            _fail(f"bad capital row fingerprint changed: {expected.id}")
        bad[entry.id] = entry

    import_refs = {row.source_ref for row in CAPITAL_IMPORT_ROWS}
    imported_rows = [entry for entry in all_entries if entry.source_ref in import_refs]
    imported = {str(entry.source_ref): entry for entry in imported_rows}
    if len(imported) != len(imported_rows):
        _fail("duplicate imported capital source_ref detected")

    bad_void_count = sum(entry.voided_at is not None for entry in bad.values())
    if not imported and bad_void_count == 0:
        if set(by_id) != {row.id for row in BAD_CAPITAL_ENTRIES}:
            _fail(
                "fresh capital precondition requires exactly the 27 audited bad rows; "
                "unexpected capital movements exist"
            )
        state = ComponentState.FRESH
    elif len(imported) == len(CAPITAL_IMPORT_ROWS) and bad_void_count == len(bad):
        state = ComponentState.APPLIED
    else:
        _fail(
            "capital reconciliation is partial: expected either 27 active bad rows "
            "and no imports, or 27 voided bad rows plus all 64 source rows"
        )

    if state is ComponentState.FRESH:
        for entry in bad.values():
            if any((entry.voided_at, entry.voided_by, entry.void_reason)):
                _fail(f"bad capital row has an inconsistent void state: {entry.id}")
    else:
        for entry in bad.values():
            if (
                entry.voided_at is None
                or entry.voided_by != IMPORT_CREATED_BY_ID
                or entry.void_reason != CAPITAL_VOID_REASON
            ):
                _fail(f"bad capital row was not voided by this reconciliation: {entry.id}")

        for source in CAPITAL_IMPORT_ROWS:
            entry = imported.get(source.source_ref)
            target = target_by_name[source.partner_name]
            expected_effective = _local_noon_utc(source.business_date, company.timezone)
            if entry is None or not (
                entry.partner_id == target.id
                and entry.type == "invest"
                and int(entry.amount_minor) == source.amount_minor
                and _as_utc(entry.effective_at) == expected_effective
                and entry.note == _capital_note(source.sheet_row, source.original_note)
                and entry.settlement_account == "historical_funds"
                and entry.created_by == IMPORT_CREATED_BY_ID
                and entry.voided_at is None
                and entry.voided_by is None
                and entry.void_reason is None
            ):
                _fail(f"imported capital fingerprint mismatch: {source.source_ref}")

    manifest = [
        {
            "id": entry.id,
            "partner_id": entry.partner_id,
            "type": entry.type,
            "amount_minor": int(entry.amount_minor),
            "effective_at": entry.effective_at,
            "note": entry.note,
            "settlement_account": entry.settlement_account,
            "source_ref": entry.source_ref,
            "created_by": entry.created_by,
            "voided_at": entry.voided_at,
            "voided_by": entry.voided_by,
            "void_reason": entry.void_reason,
        }
        for entry in all_entries
    ]
    return partners, bad, imported, state, manifest


async def _inspect_collections_and_synthetic_history(
    session: AsyncSession,
    *,
    company: Company,
    branch: Branch,
    lock: bool,
) -> tuple[
    dict[UUID, Order],
    dict[UUID, GamingSession],
    dict[UUID, Payment],
    dict[str, ManualCollection],
    ComponentState,
    dict[str, Any],
]:
    expected_by_order = {row.order_id: row for row in SYNTHETIC_ORDERS}
    order_stmt = select(Order).where(Order.id.in_(expected_by_order)).order_by(Order.id)
    order_rows = (await session.execute(_maybe_lock(order_stmt, lock=lock))).scalars().all()
    orders = {row.id: row for row in order_rows}
    if len(orders) != len(SYNTHETIC_ORDERS):
        _fail("one or more audited synthetic orders are missing")

    session_ids = [row.session_id for row in SYNTHETIC_ORDERS]
    gaming_stmt = select(GamingSession).where(GamingSession.id.in_(session_ids))
    gaming_rows = list((await session.execute(_maybe_lock(gaming_stmt, lock=lock))).scalars().all())
    gaming_rows.sort(key=lambda row: str(row.id))
    sessions = {row.id: row for row in gaming_rows}
    if len(sessions) != len(SYNTHETIC_ORDERS):
        _fail("one or more audited synthetic gaming sessions are missing")

    payment_stmt = select(Payment).where(Payment.order_id.in_(expected_by_order))
    payment_rows = list(
        (await session.execute(_maybe_lock(payment_stmt, lock=lock))).scalars().all()
    )
    payment_rows.sort(key=lambda row: str(row.id))
    payments_by_order: dict[UUID, Payment] = {}
    for payment in payment_rows:
        if payment.order_id in payments_by_order:
            _fail(f"synthetic order has more than one payment: {payment.order_id}")
        payments_by_order[payment.order_id] = payment
    if len(payments_by_order) != len(SYNTHETIC_ORDERS):
        _fail("each synthetic order must retain exactly one audited payment")

    for expected in SYNTHETIC_ORDERS:
        order = orders[expected.order_id]
        payment = payments_by_order[expected.order_id]
        gaming = sessions[expected.session_id]
        invoice_business_date = (
            order.invoice_issued_at.astimezone(ZoneInfo(company.timezone)).date()
            if order.invoice_issued_at is not None
            else None
        )
        payment_business_date = payment.paid_at.astimezone(ZoneInfo(company.timezone)).date()
        if not (
            order.company_id == company.id
            and order.branch_id == branch.id
            and order.invoice_no == expected.invoice_no
            and int(order.total_minor) == expected.amount_minor
            and order.type == "session"
            and order.opened_by == IMPORT_CREATED_BY_ID
            and invoice_business_date == expected.business_date
            and int(order.subtotal_minor) == expected.amount_minor
            and int(order.tax_minor) == 0
            and "(backfilled from daily collection sheet)" in (order.notes or "")
        ):
            _fail(f"synthetic order fingerprint changed: {order.id}")
        if not (
            payment.method == expected.payment_method
            and int(payment.amount_minor) == expected.amount_minor
            and payment.shift_id == order.shift_id
            and payment_business_date == expected.business_date
        ):
            _fail(f"synthetic payment fingerprint changed for order {order.id}")
        if not (
            gaming.company_id == company.id
            and gaming.order_id == order.id
            and gaming.shift_id == order.shift_id
            and gaming.opened_by == IMPORT_CREATED_BY_ID
            and int(gaming.amount_minor or 0) == expected.amount_minor
        ):
            _fail(f"synthetic gaming session fingerprint changed: {gaming.id}")

    target_dates = sorted({row.business_date for row in COLLECTION_IMPORT_ROWS})
    collection_stmt = (
        select(ManualCollection)
        .where(
            ManualCollection.company_id == company.id,
            ManualCollection.branch_id == branch.id,
            ManualCollection.business_date.in_(target_dates),
        )
        .order_by(ManualCollection.id)
    )
    collection_rows = (
        (await session.execute(_maybe_lock(collection_stmt, lock=lock))).scalars().all()
    )
    collections = {row.idempotency_key: row for row in collection_rows}
    if len(collections) != len(collection_rows):
        _fail("duplicate manual collection idempotency key detected")

    expected_keys = {row.idempotency_key for row in COLLECTION_IMPORT_ROWS}
    matched = [row for row in collection_rows if row.idempotency_key in expected_keys]
    all_fresh = (
        not collection_rows
        and all(order.status == "paid" for order in orders.values())
        and all(gaming.status == "ended" for gaming in sessions.values())
    )
    all_applied = (
        len(matched) == len(COLLECTION_IMPORT_ROWS)
        and len(collection_rows) == len(COLLECTION_IMPORT_ROWS)
        and all(order.status == "void" for order in orders.values())
        and all(gaming.status == "cancelled" for gaming in sessions.values())
    )
    if all_fresh:
        state = ComponentState.FRESH
    elif all_applied:
        state = ComponentState.APPLIED
    else:
        _fail(
            "manual collection/synthetic history reconciliation is partial or "
            "conflicts with another collection on Jul 11-16"
        )

    if state is ComponentState.FRESH:
        for order in orders.values():
            if ORDER_VOID_MARKER in (order.notes or ""):
                _fail(f"fresh synthetic order already has reconciliation marker: {order.id}")
        for gaming in sessions.values():
            if any((gaming.cancelled_at, gaming.cancelled_by, gaming.cancel_reason)):
                _fail(f"fresh synthetic session already has cancellation data: {gaming.id}")
    else:
        for order in orders.values():
            if ORDER_VOID_MARKER not in (order.notes or ""):
                _fail(f"void synthetic order lacks reconciliation marker: {order.id}")
        for gaming in sessions.values():
            if (
                gaming.cancelled_at is None
                or gaming.cancelled_by != IMPORT_CREATED_BY_ID
                or gaming.cancel_reason != SESSION_CANCEL_REASON
            ):
                _fail(f"cancelled synthetic session lacks reconciliation marker: {gaming.id}")
        for source in COLLECTION_IMPORT_ROWS:
            row = collections.get(source.idempotency_key)
            if row is None or not (
                row.company_id == company.id
                and row.branch_id == branch.id
                and row.business_date == source.business_date
                and row.method == source.method
                and int(row.amount_minor) == source.amount_minor
                and row.source_kind == source.source_kind
                and row.source_ref == source.source_ref
                and row.note == _collection_note(source.business_date, source.source_kind)
                and row.created_by == IMPORT_CREATED_BY_ID
                and row.voided_at is None
                and row.voided_by is None
                and row.void_reason is None
            ):
                _fail(f"manual collection fingerprint mismatch: {source.idempotency_key}")

    manifest = {
        "orders": [
            {
                "id": row.id,
                "company_id": row.company_id,
                "branch_id": row.branch_id,
                "terminal_id": row.terminal_id,
                "shift_id": row.shift_id,
                "opened_by": row.opened_by,
                "invoice_no": row.invoice_no,
                "invoice_issued_at": row.invoice_issued_at,
                "closed_at": row.closed_at,
                "type": row.type,
                "status": row.status,
                "subtotal_minor": int(row.subtotal_minor),
                "discount_minor": int(row.discount_minor),
                "tax_minor": int(row.tax_minor),
                "total_minor": int(row.total_minor),
                "notes": row.notes,
            }
            for row in order_rows
        ],
        "sessions": [
            {
                "id": row.id,
                "company_id": row.company_id,
                "order_id": row.order_id,
                "station_id": row.station_id,
                "shift_id": row.shift_id,
                "opened_by": row.opened_by,
                "start_at": row.start_at,
                "end_at": row.end_at,
                "billable_minutes": row.billable_minutes,
                "rate_per_hour_minor": int(row.rate_per_hour_minor),
                "status": row.status,
                "amount_minor": int(row.amount_minor or 0),
                "cancelled_at": row.cancelled_at,
                "cancelled_by": row.cancelled_by,
                "cancel_reason": row.cancel_reason,
            }
            for row in gaming_rows
        ],
        "payments": [
            {
                "id": row.id,
                "order_id": row.order_id,
                "shift_id": row.shift_id,
                "method": row.method,
                "amount_minor": int(row.amount_minor),
                "tendered_minor": row.tendered_minor,
                "change_minor": row.change_minor,
                "ref_external": row.ref_external,
                "paid_at": row.paid_at,
            }
            for row in payment_rows
        ],
        "manual_collections": [
            {
                "id": row.id,
                "business_date": row.business_date,
                "method": row.method,
                "amount_minor": int(row.amount_minor),
                "source_kind": row.source_kind,
                "source_ref": row.source_ref,
                "idempotency_key": row.idempotency_key,
                "note": row.note,
                "created_by": row.created_by,
                "voided_at": row.voided_at,
                "voided_by": row.voided_by,
                "void_reason": row.void_reason,
            }
            for row in collection_rows
        ],
    }
    return orders, sessions, payments_by_order, collections, state, manifest


async def _inspect_accounts_and_setup_journal(
    session: AsyncSession,
    *,
    company: Company,
    branch: Branch,
    lock: bool,
) -> tuple[
    dict[str, Account],
    dict[str, ExpenseCategory],
    JournalEntry | None,
    list[JournalLine],
    ComponentState,
    tuple[str, ...],
    tuple[str, ...],
    tuple[str, ...],
    dict[str, Any],
]:
    account_stmt = select(Account).where(
        Account.company_id == company.id,
        Account.code.in_(ACCOUNT_SPECS),
    )
    account_rows = list(
        (await session.execute(_maybe_lock(account_stmt, lock=lock))).scalars().all()
    )
    account_rows.sort(key=lambda row: (row.code, str(row.id)))
    accounts = {row.code: row for row in account_rows}
    if len(accounts) != len(account_rows):
        _fail("duplicate chart-of-account code detected")
    rename_codes: list[str] = []
    for code, account in accounts.items():
        name, account_type, normal_side = ACCOUNT_SPECS[code]
        expected_id = EXPECTED_LIVE_ACCOUNT_IDS.get(
            code,
            uuid5(company.id, f"account:{code}"),
        )
        if account.id != expected_id:
            _fail(f"account {code} ID differs from the audited production snapshot")
        if (
            account.type != account_type
            or account.normal_side != normal_side
            or not account.is_active
        ):
            _fail(f"account {code} conflicts with the canonical type/normal-side contract")
        if account.name == name:
            continue
        if account.name == LEGACY_ACCOUNT_NAMES.get(code):
            rename_codes.append(code)
            continue
        _fail(f"account {code} has unexpected name {account.name!r}; refusing an unsafe rename")
    missing = tuple(sorted(set(ACCOUNT_SPECS) - set(accounts)))
    rename_codes_tuple = tuple(sorted(rename_codes))

    category_stmt = (
        select(ExpenseCategory)
        .where(ExpenseCategory.company_id == company.id)
        .order_by(ExpenseCategory.name, ExpenseCategory.id)
    )
    category_rows = (await session.execute(_maybe_lock(category_stmt, lock=lock))).scalars().all()
    categories = {row.name: row for row in category_rows}
    if len(categories) != len(category_rows):
        _fail("duplicate expense category name detected")
    expected_category_names = set(DEFAULT_EXPENSE_CATEGORY_ACCOUNTS)
    if set(categories) != expected_category_names:
        missing_names = sorted(expected_category_names - set(categories))
        unexpected_names = sorted(set(categories) - expected_category_names)
        _fail(
            "expense category snapshot changed; "
            f"missing={missing_names}, unexpected={unexpected_names}"
        )
    category_changes: list[str] = []
    for name, category in categories.items():
        if category.id != EXPECTED_EXPENSE_CATEGORY_IDS[name]:
            _fail(f"expense category {name!r} ID differs from the audited snapshot")
        definition = DEFAULT_EXPENSE_CATEGORY_ACCOUNTS[name]
        expected_account_id = (
            accounts[definition.code].id
            if definition.code in accounts
            else uuid5(company.id, f"account:{definition.code}")
        )
        if category.code not in (None, definition.code):
            _fail(f"expense category {name!r} has unexpected code {category.code!r}")
        if category.gl_account_id not in (None, expected_account_id):
            _fail(f"expense category {name!r} points at an unexpected GL account")
        if category.code != definition.code or category.gl_account_id != expected_account_id:
            category_changes.append(name)
    category_changes_tuple = tuple(sorted(category_changes))

    entry_stmt = select(JournalEntry).where(
        JournalEntry.company_id == company.id,
        or_(
            JournalEntry.id == SETUP_ENTRY_ID,
            JournalEntry.ref_type == SETUP_REF_TYPE,
            JournalEntry.ref_id == SETUP_ENTRY_ID,
        ),
    )
    entry_rows = (await session.execute(_maybe_lock(entry_stmt, lock=lock))).scalars().all()
    if len(entry_rows) > 1:
        _fail("duplicate historical setup reconciliation journals detected")
    entry = entry_rows[0] if entry_rows else None
    line_stmt = select(JournalLine).where(
        or_(
            JournalLine.journal_entry_id == SETUP_ENTRY_ID,
            JournalLine.id.in_((SETUP_DEBIT_LINE_ID, SETUP_CREDIT_LINE_ID)),
        )
    )
    line_rows = list((await session.execute(_maybe_lock(line_stmt, lock=lock))).scalars().all())
    line_rows.sort(key=lambda row: str(row.id))
    if entry is None and not line_rows:
        setup_state = ComponentState.FRESH
    elif entry is not None and len(line_rows) == 2:
        setup_state = ComponentState.APPLIED
    else:
        _fail("historical setup journal is partially applied")

    if setup_state is ComponentState.APPLIED:
        assert entry is not None
        if not (
            entry.company_id == company.id
            and entry.branch_id == branch.id
            and entry.ref_type == SETUP_REF_TYPE
            and entry.ref_id == SETUP_ENTRY_ID
            and _as_utc(entry.posted_at) == SETUP_POSTED_AT
            and entry.memo == SETUP_MEMO
            and int(entry.total_minor) == TRANSACTIONS_TOTAL_MINOR
        ):
            _fail("historical setup journal header fingerprint mismatch")
        if missing:
            _fail("historical setup journal exists but one or more required accounts are missing")
        lines_by_id = {row.id: row for row in line_rows}
        expected_lines = (
            (SETUP_DEBIT_LINE_ID, "1490", "dr"),
            (SETUP_CREDIT_LINE_ID, "1185", "cr"),
        )
        for line_id, code, side in expected_lines:
            line = lines_by_id.get(line_id)
            if line is None or not (
                line.journal_entry_id == SETUP_ENTRY_ID
                and line.account_id == accounts[code].id
                and line.side == side
                and int(line.amount_minor) == TRANSACTIONS_TOTAL_MINOR
                and line.memo == SETUP_MEMO
            ):
                _fail(f"historical setup journal line fingerprint mismatch: {line_id}")

    manifest = {
        "accounts": [
            {
                "id": row.id,
                "code": row.code,
                "name": row.name,
                "type": row.type,
                "normal_side": row.normal_side,
                "is_active": row.is_active,
            }
            for row in account_rows
        ],
        "expense_categories": [
            {
                "id": row.id,
                "name": row.name,
                "code": row.code,
                "gl_account_id": row.gl_account_id,
            }
            for row in category_rows
        ],
        "entry": (
            None
            if entry is None
            else {
                "id": entry.id,
                "company_id": entry.company_id,
                "branch_id": entry.branch_id,
                "ref_type": entry.ref_type,
                "ref_id": entry.ref_id,
                "posted_at": entry.posted_at,
                "memo": entry.memo,
                "total_minor": int(entry.total_minor),
            }
        ),
        "lines": [
            {
                "id": row.id,
                "journal_entry_id": row.journal_entry_id,
                "account_id": row.account_id,
                "side": row.side,
                "amount_minor": int(row.amount_minor),
                "memo": row.memo,
            }
            for row in line_rows
        ],
    }
    return (
        accounts,
        categories,
        entry,
        line_rows,
        setup_state,
        missing,
        rename_codes_tuple,
        category_changes_tuple,
        manifest,
    )


async def inspect_state(
    session: AsyncSession,
    *,
    branch_id: UUID | None,
    lock: bool,
) -> Inspection:
    company, branch, actor = await _resolve_context(
        session,
        branch_id=branch_id,
        lock=lock,
    )
    partners, bad, imported, capital_state, capital_manifest = await _inspect_partners_and_capital(
        session, company=company, lock=lock
    )
    (
        orders,
        sessions,
        payments,
        collections,
        collections_state,
        collection_manifest,
    ) = await _inspect_collections_and_synthetic_history(
        session,
        company=company,
        branch=branch,
        lock=lock,
    )
    (
        accounts,
        expense_categories,
        setup_entry,
        setup_lines,
        setup_state,
        missing,
        account_rename_codes,
        expense_category_changes,
        setup_manifest,
    ) = await _inspect_accounts_and_setup_journal(
        session,
        company=company,
        branch=branch,
        lock=lock,
    )
    manifest = {
        "version": RECONCILIATION_VERSION,
        "company": {
            "id": company.id,
            "name": company.name,
            "timezone": company.timezone,
            "gst_registration_type": company.gst_registration_type,
        },
        "branch": {"id": branch.id, "name": branch.name},
        "actor": {"id": actor.id, "name": actor.name, "status": actor.status},
        "partners": [
            {
                "id": partner.id,
                "name": partner.name,
                "share_pct": Decimal(str(partner.share_pct)),
            }
            for partner in sorted(partners.values(), key=lambda item: item.name)
        ],
        "capital": capital_manifest,
        "collections_and_synthetic_history": collection_manifest,
        "setup_journal": setup_manifest,
    }
    return Inspection(
        company=company,
        branch=branch,
        actor=actor,
        partners=partners,
        bad_capital=bad,
        imported_capital=imported,
        orders=orders,
        sessions=sessions,
        payments=payments,
        collections=collections,
        accounts=accounts,
        expense_categories=expense_categories,
        setup_entry=setup_entry,
        setup_lines=setup_lines,
        capital_state=capital_state,
        collections_state=collections_state,
        setup_state=setup_state,
        missing_account_codes=missing,
        account_rename_codes=account_rename_codes,
        expense_category_changes=expense_category_changes,
        fingerprint=state_fingerprint(manifest),
    )


def _plan(inspection: Inspection, *, applied: bool = False) -> dict[str, Any]:
    return {
        "mode": "applied" if applied else "dry-run",
        "reconciliation_version": RECONCILIATION_VERSION,
        "company_id": str(inspection.company.id),
        "branch_id": str(inspection.branch.id),
        "state_fingerprint": inspection.fingerprint,
        "component_state": {
            "capital": inspection.capital_state.value,
            "manual_collections_and_synthetic_history": (inspection.collections_state.value),
            "historical_setup_journal": inspection.setup_state.value,
            "missing_accounts": list(inspection.missing_account_codes),
            "accounts_to_rename": list(inspection.account_rename_codes),
            "expense_categories_to_map": list(inspection.expense_category_changes),
        },
        "intended_result": {
            "partner_capital_minor": {
                target.name: target.expected_capital_minor for target in PARTNER_TARGETS
            },
            "ownership_share_pct": {
                target.name: str(target.ownership_share_pct) for target in PARTNER_TARGETS
            },
            "void_bad_capital_rows": len(BAD_CAPITAL_ENTRIES),
            "insert_real_capital_rows": len(CAPITAL_IMPORT_ROWS),
            "manual_collection_rows": len(COLLECTION_IMPORT_ROWS),
            "manual_collections_minor": sum(row.amount_minor for row in COLLECTION_IMPORT_ROWS),
            "void_synthetic_orders": len(SYNTHETIC_ORDERS),
            "cancel_synthetic_sessions": len(SYNTHETIC_ORDERS),
            "setup_pending_classification_minor": TRANSACTIONS_TOTAL_MINOR,
            "canonical_account_count": len(DEFAULT_CHART_OF_ACCOUNTS),
            "mapped_expense_category_count": len(DEFAULT_EXPENSE_CATEGORY_ACCOUNTS),
            "unreconciled_funds_residual_minor": (
                sum(target.expected_capital_minor for target in PARTNER_TARGETS)
                - TRANSACTIONS_TOTAL_MINOR
            ),
        },
        "warnings": [
            "The 503 setup transactions remain pending classification.",
            "Only INR 1,146,842.05 is marked matched in the source sheet.",
            "No invoice, payment, session, or capital row will be deleted.",
        ],
    }


async def _ensure_chart_and_expense_categories(
    session: AsyncSession,
    inspection: Inspection,
) -> None:
    for code in inspection.account_rename_codes:
        inspection.accounts[code].name = ACCOUNT_SPECS[code][0]
    for code in inspection.missing_account_codes:
        name, account_type, normal_side = ACCOUNT_SPECS[code]
        account = Account(
            id=uuid5(inspection.company.id, f"account:{code}"),
            company_id=inspection.company.id,
            code=code,
            name=name,
            type=account_type,
            normal_side=normal_side,
            is_active=True,
        )
        session.add(account)
        inspection.accounts[code] = account
    if inspection.missing_account_codes or inspection.account_rename_codes:
        await session.flush()
    for name in inspection.expense_category_changes:
        category = inspection.expense_categories[name]
        definition = DEFAULT_EXPENSE_CATEGORY_ACCOUNTS[name]
        category.code = definition.code
        category.gl_account_id = inspection.accounts[definition.code].id
    if inspection.expense_category_changes:
        await session.flush()


async def _apply_capital(session: AsyncSession, inspection: Inspection) -> None:
    if inspection.capital_state is ComponentState.APPLIED:
        return
    now = datetime.now(UTC)
    target_by_name = {target.name: target for target in PARTNER_TARGETS}
    for entry in inspection.bad_capital.values():
        entry.voided_at = now
        entry.voided_by = IMPORT_CREATED_BY_ID
        entry.void_reason = CAPITAL_VOID_REASON
    for source in CAPITAL_IMPORT_ROWS:
        target = target_by_name[source.partner_name]
        session.add(
            CapitalEntry(
                id=uuid5(EXPECTED_COMPANY_ID, source.source_ref),
                partner_id=target.id,
                type="invest",
                amount_minor=source.amount_minor,
                effective_at=_local_noon_utc(
                    source.business_date,
                    inspection.company.timezone,
                ),
                note=_capital_note(source.sheet_row, source.original_note),
                settlement_account="historical_funds",
                source_ref=source.source_ref,
                created_by=IMPORT_CREATED_BY_ID,
            )
        )
    await session.flush()


async def _apply_collections_and_synthetic_history(
    session: AsyncSession,
    inspection: Inspection,
) -> None:
    if inspection.collections_state is ComponentState.APPLIED:
        return
    now = datetime.now(UTC)
    for source in COLLECTION_IMPORT_ROWS:
        session.add(
            ManualCollection(
                id=uuid5(EXPECTED_COMPANY_ID, source.idempotency_key),
                company_id=inspection.company.id,
                branch_id=inspection.branch.id,
                business_date=source.business_date,
                method=source.method,
                amount_minor=source.amount_minor,
                source_kind=source.source_kind,
                source_ref=source.source_ref,
                note=_collection_note(source.business_date, source.source_kind),
                idempotency_key=source.idempotency_key,
                created_by=IMPORT_CREATED_BY_ID,
            )
        )
    for expected in SYNTHETIC_ORDERS:
        order = inspection.orders[expected.order_id]
        order.status = "void"
        order.notes = _append_marker(order.notes, ORDER_VOID_MARKER)
        gaming = inspection.sessions[expected.session_id]
        gaming.status = "cancelled"
        gaming.cancelled_at = now
        gaming.cancelled_by = IMPORT_CREATED_BY_ID
        gaming.cancel_reason = SESSION_CANCEL_REASON
    await session.flush()


async def _apply_setup_journal(session: AsyncSession, inspection: Inspection) -> None:
    if inspection.setup_state is ComponentState.APPLIED:
        return
    entry = JournalEntry(
        id=SETUP_ENTRY_ID,
        company_id=inspection.company.id,
        branch_id=inspection.branch.id,
        ref_type=SETUP_REF_TYPE,
        ref_id=SETUP_ENTRY_ID,
        posted_at=SETUP_POSTED_AT,
        memo=SETUP_MEMO,
        total_minor=TRANSACTIONS_TOTAL_MINOR,
    )
    session.add(entry)
    session.add_all(
        [
            JournalLine(
                id=SETUP_DEBIT_LINE_ID,
                journal_entry_id=entry.id,
                account_id=inspection.accounts["1490"].id,
                side="dr",
                amount_minor=TRANSACTIONS_TOTAL_MINOR,
                memo=SETUP_MEMO,
            ),
            JournalLine(
                id=SETUP_CREDIT_LINE_ID,
                journal_entry_id=entry.id,
                account_id=inspection.accounts["1185"].id,
                side="cr",
                amount_minor=TRANSACTIONS_TOTAL_MINOR,
                memo=SETUP_MEMO,
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
    validate_payload()
    install_audit_listeners()
    async with AsyncSessionLocal() as session:
        if apply:
            set_actor(
                user_id=IMPORT_CREATED_BY_ID,
                company_id=EXPECTED_COMPANY_ID,
                ip=None,
                user_agent=f"script/{RECONCILIATION_VERSION}",
            )
        try:
            async with session.begin():
                if apply:
                    await session.execute(text("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"))
                    await session.execute(
                        text("SELECT pg_advisory_xact_lock(:lock_key)"),
                        {"lock_key": _lock_key()},
                    )
                before = await inspect_state(
                    session,
                    branch_id=branch_id,
                    lock=apply,
                )
                if not apply:
                    return _plan(before)
                if expected_state_fingerprint != before.fingerprint:
                    _fail(
                        "state fingerprint mismatch; run a new dry run and review it "
                        "before applying"
                    )
                if before.is_fully_applied:
                    result = _plan(before, applied=True)
                    result["no_op"] = True
                    return result

                await _ensure_chart_and_expense_categories(session, before)
                await _apply_capital(session, before)
                await _apply_collections_and_synthetic_history(session, before)
                await _apply_setup_journal(session, before)
                await session.flush()

                after = await inspect_state(
                    session,
                    branch_id=before.branch.id,
                    lock=True,
                )
                if not after.is_fully_applied:
                    _fail("post-apply verification did not reach the complete state")
                session.add(
                    AuditLog(
                        actor_user_id=IMPORT_CREATED_BY_ID,
                        company_id=after.company.id,
                        action="historical_reconciliation_applied",
                        entity_type="Company",
                        entity_id=str(after.company.id),
                        before={"state_fingerprint": before.fingerprint},
                        after={
                            "reconciliation_version": RECONCILIATION_VERSION,
                            "state_fingerprint": after.fingerprint,
                            "capital_rows_imported": len(CAPITAL_IMPORT_ROWS),
                            "manual_collection_rows_imported": len(COLLECTION_IMPORT_ROWS),
                            "synthetic_orders_voided": len(SYNTHETIC_ORDERS),
                            "setup_pending_classification_minor": (TRANSACTIONS_TOTAL_MINOR),
                        },
                        ip=None,
                        user_agent=f"script/{RECONCILIATION_VERSION}",
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
        help="commit the reconciliation; default is a read-only dry run",
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

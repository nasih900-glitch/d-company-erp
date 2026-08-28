"""Balanced double-entry view derived from immutable operational records.

The operational tables remain the source of truth. This service translates
their financial effects into one journal so Trial Balance, Balance Sheet and
General Ledger cannot disagree with each other.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, time
from decimal import ROUND_HALF_UP, Decimal
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessRuleError
from app.core.timezone import company_timezone
from app.models import (
    GRN,
    Account,
    Asset,
    CapitalEntry,
    Expense,
    ExpenseCategory,
    GRNLine,
    JournalEntry,
    JournalLine,
    ManualCollection,
    MembershipPayment,
    MembershipRefund,
    MembershipRefundSettlement,
    Order,
    Partner,
    Payment,
    PurchaseOrder,
    Refund,
    SupplierPayment,
    TipPayout,
)
from app.services.accounting.accounts import (
    ACCOUNT_BY_CODE,
    ACCOUNTS_PAYABLE,
    ACCUMULATED_DEPRECIATION,
    BANK,
    CARD_CLEARING,
    CASH,
    CESS_PAYABLE,
    CGST_PAYABLE,
    COST_OF_GOODS_SOLD,
    DEFAULT_EXPENSE_CATEGORY_ACCOUNTS,
    DEPRECIATION_EXPENSE,
    DISCOUNTS_GIVEN,
    HISTORICAL_FUNDS,
    IGST_PAYABLE,
    INVENTORY,
    LOYALTY_POINTS_REDEEMED,
    MANUAL_COLLECTION_REVENUE,
    MEMBERSHIP_REVENUE,
    OTHER_EXPENSES,
    PARTNER_CAPITAL,
    POS_SETTLEMENT_CLEARING,
    ROUNDING_EXPENSE,
    ROUNDING_INCOME,
    SALES_RETURNS,
    SALES_REVENUE,
    SGST_PAYABLE,
    STORE_CREDIT_LIABILITY,
    TIPS_PAYABLE,
    UNRECONCILED_SETTLEMENT,
    UPI_CLEARING,
    WALLET_CLEARING,
)
from app.services.accounting.depreciation import asset_depreciation_expense_minor
from app.services.accounting.purchases import (
    received_line_total_minor,
    require_invoice_matches_capitalised_total,
)
from app.services.accounting.refund_allocation import cumulative_refunded_tip_minor
from app.services.inventory.accounting import load_inventory_value_changes


@dataclass(frozen=True, slots=True)
class LedgerLine:
    occurred_at: datetime
    ref_type: str
    ref_id: UUID | None
    account_code: str
    account_name: str
    account_type: str
    debit_minor: int = 0
    credit_minor: int = 0
    memo: str | None = None


ACCOUNT_BY_METHOD: dict[str, tuple[str, str, str]] = {
    "cash": CASH.ledger_tuple,
    "bank": BANK.ledger_tuple,
    "card": CARD_CLEARING.ledger_tuple,
    "upi": UPI_CLEARING.ledger_tuple,
    "qr": UPI_CLEARING.ledger_tuple,
    "wallet": WALLET_CLEARING.ledger_tuple,
}

# Manual collections are explicitly not order Sales Revenue.  Keep this
# stable, distinct code aligned with the chart of accounts and report it as a
# separate source until historical item detail is available.
MANUAL_COLLECTION_REVENUE_ACCOUNT = MANUAL_COLLECTION_REVENUE.ledger_tuple

CAPITAL_SETTLEMENT_ACCOUNTS: dict[str, tuple[str, str, str]] = {
    "cash": CASH.ledger_tuple,
    "bank": BANK.ledger_tuple,
    "upi": UPI_CLEARING.ledger_tuple,
    # Internal-only reconciliation value; the public API cannot select it.
    "historical_funds": HISTORICAL_FUNDS.ledger_tuple,
}

POSTED_JOURNAL_REF_TYPES = frozenset(
    {
        "historical_setup_reconciliation",
        "grn_receipt",
        "supplier_payment",
    }
)


def _method_account(method: str | None) -> tuple[str, str, str]:
    return ACCOUNT_BY_METHOD.get(
        (method or "").lower(),
        UNRECONCILED_SETTLEMENT.ledger_tuple,
    )


def _expense_payment_account(paid_via: str) -> tuple[str, str, str]:
    """Map an outgoing expense to the asset that actually funded it.

    Card/UPI on this form mean a business debit card or a bank-account UPI
    transfer. Provider clearing accounts represent incoming customer funds
    awaiting settlement; crediting them for an outgoing vendor payment would
    fabricate a reduction in receivables and overstate spendable Bank cash.
    A future business credit-card workflow needs an explicit liability source,
    not reuse of the customer Card Clearing account.
    """

    if paid_via == "cash":
        return CASH.ledger_tuple
    if paid_via in {"bank", "card", "upi"}:
        return BANK.ledger_tuple
    raise BusinessRuleError(f"unsupported expense payment rail: {paid_via}")


def _capital_settlement_account(value: str) -> tuple[str, str, str]:
    try:
        return CAPITAL_SETTLEMENT_ACCOUNTS[value]
    except KeyError as exc:
        raise BusinessRuleError(f"unsupported capital settlement account: {value}") from exc


def _expense_account(category: ExpenseCategory) -> tuple[str, str, str]:
    """Resolve one stable account meaning for every expense category."""
    if category.code:
        canonical = ACCOUNT_BY_CODE.get(category.code)
        if canonical is not None:
            if canonical.type != "expense":
                raise BusinessRuleError(
                    f"expense category {category.name!r} uses non-expense "
                    f"account code {category.code}"
                )
            return canonical.ledger_tuple
        return category.code, category.name, "expense"

    mapped = DEFAULT_EXPENSE_CATEGORY_ACCOUNTS.get(category.name)
    return (mapped or OTHER_EXPENSES).ledger_tuple


def _proportional(component: int, cumulative: int, total: int) -> int:
    if component <= 0 or cumulative <= 0 or total <= 0:
        return 0
    return int(
        (Decimal(component) * Decimal(min(cumulative, total)) / Decimal(total)).quantize(
            Decimal("1"), rounding=ROUND_HALF_UP
        )
    )


def _line(
    *,
    occurred_at: datetime,
    ref_type: str,
    ref_id: UUID | None,
    account: tuple[str, str, str],
    debit: int = 0,
    credit: int = 0,
    memo: str | None = None,
) -> LedgerLine:
    return LedgerLine(
        occurred_at=occurred_at,
        ref_type=ref_type,
        ref_id=ref_id,
        account_code=account[0],
        account_name=account[1],
        account_type=account[2],
        debit_minor=debit,
        credit_minor=credit,
        memo=memo,
    )


def _manual_collection_ledger_lines(
    collections: list[ManualCollection],
    *,
    timezone_name: str,
    end_exclusive: datetime,
    start_at: datetime | None = None,
) -> list[LedgerLine]:
    """Post active date-only collections at company-local day start."""
    local_zone = ZoneInfo(timezone_name)
    lines: list[LedgerLine] = []
    for collection in collections:
        if collection.voided_at is not None:
            continue
        occurred_at = datetime.combine(
            collection.business_date,
            time.min,
            tzinfo=local_zone,
        ).astimezone(UTC)
        if occurred_at >= end_exclusive or (start_at is not None and occurred_at < start_at):
            continue
        memo = collection.note or (
            f"{collection.source_kind.replace('_', ' ').title()} {collection.source_ref}"
        )
        lines.extend(
            [
                _line(
                    occurred_at=occurred_at,
                    ref_type="manual_collection",
                    ref_id=collection.id,
                    account=_method_account(collection.method),
                    debit=collection.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=occurred_at,
                    ref_type="manual_collection",
                    ref_id=collection.id,
                    account=MANUAL_COLLECTION_REVENUE_ACCOUNT,
                    credit=collection.amount_minor,
                    memo=memo,
                ),
            ]
        )
    return lines


def _membership_payment_ledger_lines(
    payments: list[MembershipPayment],
) -> list[LedgerLine]:
    """Recognize only explicit settlement rows, never bare entitlements."""
    lines: list[LedgerLine] = []
    for payment in payments:
        memo = payment.note or f"Membership payment {payment.membership_id}"
        lines.extend(
            [
                _line(
                    occurred_at=payment.paid_at,
                    ref_type="membership_payment",
                    ref_id=payment.id,
                    account=_method_account(payment.method),
                    debit=payment.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=payment.paid_at,
                    ref_type="membership_payment",
                    ref_id=payment.id,
                    account=MEMBERSHIP_REVENUE.ledger_tuple,
                    credit=payment.amount_minor,
                    memo=memo,
                ),
            ]
        )
    return lines


def _membership_refund_ledger_lines(
    rows: list[tuple[MembershipRefundSettlement, MembershipRefund]],
) -> list[LedgerLine]:
    """Reverse membership revenue against the rail that paid the refund."""
    lines: list[LedgerLine] = []
    for settlement, refund in rows:
        memo = f"Membership refund: {refund.reason}"
        lines.extend(
            [
                _line(
                    occurred_at=settlement.settled_at,
                    ref_type="membership_refund",
                    ref_id=settlement.id,
                    account=MEMBERSHIP_REVENUE.ledger_tuple,
                    debit=settlement.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=settlement.settled_at,
                    ref_type="membership_refund",
                    ref_id=settlement.id,
                    account=_method_account(settlement.method),
                    credit=settlement.amount_minor,
                    memo=memo,
                ),
            ]
        )
    return lines


async def _posted_journal_ledger_lines(
    session: AsyncSession,
    *,
    company_id: UUID,
    end_exclusive: datetime,
    start_at: datetime | None,
) -> list[LedgerLine]:
    """Load only approved, already-posted setup journals.

    Operational journal types remain derived from their source tables.  This
    narrow allowlist prevents manually inserted journal rows from replacing or
    double-counting POS, expense, refund, or capital activity.
    """
    entry_stmt = select(JournalEntry).where(
        JournalEntry.company_id == company_id,
        JournalEntry.ref_type.in_(POSTED_JOURNAL_REF_TYPES),
        JournalEntry.posted_at < end_exclusive,
        JournalEntry.voided_at.is_(None),
    )
    if start_at is not None:
        entry_stmt = entry_stmt.where(JournalEntry.posted_at >= start_at)
    entries = (
        (await session.execute(entry_stmt.order_by(JournalEntry.posted_at, JournalEntry.id)))
        .scalars()
        .all()
    )
    if not entries:
        return []

    entries_by_id = {entry.id: entry for entry in entries}
    line_rows = (
        await session.execute(
            select(JournalLine, Account)
            .join(Account, Account.id == JournalLine.account_id)
            .where(JournalLine.journal_entry_id.in_(entries_by_id))
            .order_by(JournalLine.journal_entry_id, JournalLine.id)
        )
    ).all()
    grouped: dict[UUID, list[tuple[JournalLine, Account]]] = {
        entry_id: [] for entry_id in entries_by_id
    }
    for journal_line, account in line_rows:
        if account.company_id != company_id:
            raise BusinessRuleError("posted journal references an account from another company")
        grouped[journal_line.journal_entry_id].append((journal_line, account))

    await _validate_purchase_journal_sources(
        session,
        company_id=company_id,
        entries=entries,
        grouped=grouped,
    )

    output: list[LedgerLine] = []
    for entry in entries:
        if (
            entry.company_id != company_id
            or entry.ref_type not in POSTED_JOURNAL_REF_TYPES
            or entry.voided_at is not None
        ):
            raise BusinessRuleError("unapproved posted journal reached the ledger")
        rows = grouped[entry.id]
        debit_total = 0
        credit_total = 0
        for journal_line, account in rows:
            amount = int(journal_line.amount_minor)
            if amount <= 0 or journal_line.side not in {"dr", "cr"}:
                raise BusinessRuleError(f"posted journal {entry.id} contains an invalid line")
            if journal_line.side == "dr":
                debit_total += amount
            else:
                credit_total += amount
            output.append(
                _line(
                    occurred_at=entry.posted_at,
                    ref_type=entry.ref_type,
                    ref_id=entry.ref_id,
                    account=(account.code, account.name, account.type),
                    debit=amount if journal_line.side == "dr" else 0,
                    credit=amount if journal_line.side == "cr" else 0,
                    memo=journal_line.memo or entry.memo,
                )
            )
        expected = int(entry.total_minor)
        if expected <= 0 or debit_total != expected or credit_total != expected:
            raise BusinessRuleError(
                f"posted journal {entry.id} is not balanced to its declared total"
            )
    return output


def _require_exact_two_sided_shape(
    *,
    rows: list[tuple[JournalLine, Account]],
    debit_account: tuple[str, str, str],
    credit_account: tuple[str, str, str],
    amount_minor: int,
    label: str,
) -> None:
    actual = [
        (account.code, account.name, account.type, line.side, int(line.amount_minor))
        for line, account in rows
    ]
    expected = sorted(
        [
            (*debit_account, "dr", int(amount_minor)),
            (*credit_account, "cr", int(amount_minor)),
        ]
    )
    if sorted(actual) != expected:
        raise BusinessRuleError(f"{label} journal does not match its source contract")


async def _validate_purchase_journal_sources(
    session: AsyncSession,
    *,
    company_id: UUID,
    entries: list[JournalEntry],
    grouped: dict[UUID, list[tuple[JournalLine, Account]]],
) -> None:
    """Prove operational journals are exact mirrors of immutable sources.

    Merely using a reserved ``ref_type`` is not authority to post. A valid
    purchase journal must be linked back from its GRN/SupplierPayment source,
    belong to the same tenant and branch, and contain exactly the canonical
    two-sided entry. This keeps manually inserted look-alike journals out of
    Trial Balance and Balance Sheet.
    """

    grn_entries = [entry for entry in entries if entry.ref_type == "grn_receipt"]
    if grn_entries:
        entry_ids = [entry.id for entry in grn_entries]
        source_rows = (
            await session.execute(
                select(GRN, PurchaseOrder)
                .join(PurchaseOrder, PurchaseOrder.id == GRN.purchase_order_id)
                .where(GRN.journal_entry_id.in_(entry_ids))
            )
        ).all()
        by_journal = {grn.journal_entry_id: (grn, po) for grn, po in source_rows}
        grn_ids = [grn.id for grn, _po in source_rows]
        line_rows = (
            await session.execute(
                select(GRNLine).where(GRNLine.grn_id.in_(grn_ids))
            )
        ).scalars().all()
        lines_by_grn: dict[UUID, list[GRNLine]] = {}
        for line in line_rows:
            lines_by_grn.setdefault(line.grn_id, []).append(line)

        for entry in grn_entries:
            source = by_journal.get(entry.id)
            if source is None:
                raise BusinessRuleError("GRN receipt journal has no linked GRN source")
            grn, purchase_order = source
            source_lines = lines_by_grn.get(grn.id, [])
            if not source_lines:
                raise BusinessRuleError("GRN receipt journal source has no received lines")
            total_minor = sum(
                received_line_total_minor(line.qty_received, line.cost_per_unit_minor)
                for line in source_lines
            )
            require_invoice_matches_capitalised_total(
                supplier_invoice_amount_minor=grn.supplier_invoice_amount_minor,
                capitalised_total_minor=total_minor,
            )
            if (
                purchase_order.company_id != company_id
                or purchase_order.branch_id != entry.branch_id
                or entry.ref_id != grn.id
                or entry.posted_at != grn.received_at
                or int(entry.total_minor) != total_minor
            ):
                raise BusinessRuleError("GRN receipt journal provenance does not match its source")
            _require_exact_two_sided_shape(
                rows=grouped[entry.id],
                debit_account=INVENTORY.ledger_tuple,
                credit_account=ACCOUNTS_PAYABLE.ledger_tuple,
                amount_minor=total_minor,
                label="GRN receipt",
            )

    payment_entries = [
        entry for entry in entries if entry.ref_type == "supplier_payment"
    ]
    if payment_entries:
        entry_ids = [entry.id for entry in payment_entries]
        payments = (
            await session.execute(
                select(SupplierPayment).where(
                    SupplierPayment.journal_entry_id.in_(entry_ids)
                )
            )
        ).scalars().all()
        by_journal = {payment.journal_entry_id: payment for payment in payments}
        for entry in payment_entries:
            payment = by_journal.get(entry.id)
            if payment is None:
                raise BusinessRuleError(
                    "supplier payment journal has no linked settlement source"
                )
            if (
                payment.company_id != company_id
                or payment.branch_id != entry.branch_id
                or payment.voided_at is not None
                or entry.ref_id != payment.id
                or entry.posted_at != payment.paid_at
                or int(entry.total_minor) != int(payment.amount_minor)
            ):
                raise BusinessRuleError(
                    "supplier payment journal provenance does not match its source"
                )
            settlement_account = {
                "cash": CASH.ledger_tuple,
                "bank": BANK.ledger_tuple,
            }.get(payment.method)
            if settlement_account is None:
                raise BusinessRuleError("supplier payment uses an unsupported settlement rail")
            _require_exact_two_sided_shape(
                rows=grouped[entry.id],
                debit_account=ACCOUNTS_PAYABLE.ledger_tuple,
                credit_account=settlement_account,
                amount_minor=int(payment.amount_minor),
                label="supplier payment",
            )


def _validate_account_code_meanings(lines: list[LedgerLine]) -> None:
    """Fail if any emitted account code is assigned multiple meanings."""
    meanings: dict[str, tuple[str, str]] = {}
    for line in lines:
        meaning = (line.account_name, line.account_type)
        canonical = ACCOUNT_BY_CODE.get(line.account_code)
        if canonical is not None and meaning != (canonical.name, canonical.type):
            raise BusinessRuleError(
                f"account code {line.account_code} conflicts with the canonical chart"
            )
        previous = meanings.setdefault(line.account_code, meaning)
        if previous != meaning:
            raise BusinessRuleError(
                f"account code {line.account_code} has multiple ledger meanings"
            )


async def build_operational_ledger(
    session: AsyncSession,
    *,
    company_id: UUID,
    end_exclusive: datetime,
    start_at: datetime | None = None,
) -> list[LedgerLine]:
    """Return balanced journal lines in the requested half-open UTC range."""
    lines: list[LedgerLine] = []

    payment_stmt = (
        select(Payment, Order.invoice_no)
        .join(Order, Order.id == Payment.order_id)
        .where(
            Order.company_id == company_id,
            Payment.paid_at < end_exclusive,
            Order.status.in_(("paid", "refunded")),
        )
        .order_by(Payment.paid_at, Payment.id)
    )
    payment_rows = (await session.execute(payment_stmt)).all()
    payment_methods_by_order: dict[UUID, set[str]] = {}
    for payment, invoice_no in payment_rows:
        payment_methods_by_order.setdefault(payment.order_id, set()).add(payment.method)
        if start_at is not None and payment.paid_at < start_at:
            continue
        memo = f"Payment for {invoice_no or payment.order_id.hex[:8]}"
        lines.extend(
            [
                _line(
                    occurred_at=payment.paid_at,
                    ref_type="payment",
                    ref_id=payment.id,
                    account=_method_account(payment.method),
                    debit=payment.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=payment.paid_at,
                    ref_type="payment",
                    ref_id=payment.id,
                    account=POS_SETTLEMENT_CLEARING.ledger_tuple,
                    credit=payment.amount_minor,
                    memo=memo,
                ),
            ]
        )

    timezone_name = await company_timezone(session, company_id)
    local_zone = ZoneInfo(timezone_name)
    candidate_start_date = start_at.astimezone(local_zone).date() if start_at is not None else None
    candidate_end_date = end_exclusive.astimezone(local_zone).date()
    manual_stmt = (
        select(ManualCollection)
        .where(
            ManualCollection.company_id == company_id,
            ManualCollection.business_date <= candidate_end_date,
            ManualCollection.voided_at.is_(None),
        )
        .order_by(ManualCollection.business_date, ManualCollection.id)
    )
    if candidate_start_date is not None:
        manual_stmt = manual_stmt.where(ManualCollection.business_date >= candidate_start_date)
    manual_rows = (await session.execute(manual_stmt)).scalars().all()
    lines.extend(
        _manual_collection_ledger_lines(
            list(manual_rows),
            timezone_name=timezone_name,
            start_at=start_at,
            end_exclusive=end_exclusive,
        )
    )

    membership_stmt = (
        select(MembershipPayment)
        .where(
            MembershipPayment.company_id == company_id,
            MembershipPayment.paid_at < end_exclusive,
        )
        .order_by(MembershipPayment.paid_at, MembershipPayment.id)
    )
    if start_at is not None:
        membership_stmt = membership_stmt.where(MembershipPayment.paid_at >= start_at)
    membership_rows = (await session.execute(membership_stmt)).scalars().all()
    lines.extend(_membership_payment_ledger_lines(list(membership_rows)))

    membership_refund_stmt = (
        select(MembershipRefundSettlement, MembershipRefund)
        .join(MembershipRefund, MembershipRefund.id == MembershipRefundSettlement.refund_id)
        .where(
            MembershipRefundSettlement.company_id == company_id,
            MembershipRefundSettlement.settled_at < end_exclusive,
        )
        .order_by(
            MembershipRefundSettlement.settled_at,
            MembershipRefundSettlement.id,
        )
    )
    if start_at is not None:
        membership_refund_stmt = membership_refund_stmt.where(
            MembershipRefundSettlement.settled_at >= start_at
        )
    membership_refund_rows = (
        await session.execute(membership_refund_stmt)
    ).all()
    lines.extend(_membership_refund_ledger_lines(list(membership_refund_rows)))

    # Keep the sale cohort aligned with Reports/Insights for paid legacy rows
    # created before invoice_issued_at was reliably persisted. The report DTO
    # exposes these fallback rows as an explicit reconciliation warning.
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    order_stmt = select(Order).where(
        Order.company_id == company_id,
        sale_at.isnot(None),
        sale_at < end_exclusive,
        Order.status.in_(("paid", "refunded")),
    )
    if start_at is not None:
        order_stmt = order_stmt.where(sale_at >= start_at)
    orders = (await session.execute(order_stmt.order_by(sale_at, Order.id))).scalars().all()
    for order in orders:
        occurred_at = order.invoice_issued_at or order.closed_at or order.opened_at
        memo = f"Tax invoice {order.invoice_no or order.id.hex[:8]}"
        lines.append(
            _line(
                occurred_at=occurred_at,
                ref_type="order",
                ref_id=order.id,
                account=POS_SETTLEMENT_CLEARING.ledger_tuple,
                debit=order.total_minor,
                memo=memo,
            )
        )
        components = [
            (*SALES_REVENUE.ledger_tuple, order.subtotal_minor),
            (*CGST_PAYABLE.ledger_tuple, order.cgst_minor),
            (*SGST_PAYABLE.ledger_tuple, order.sgst_minor),
            (*IGST_PAYABLE.ledger_tuple, order.igst_minor),
            (*CESS_PAYABLE.ledger_tuple, order.cess_minor),
            (*TIPS_PAYABLE.ledger_tuple, order.tip_minor),
        ]
        for code, name, account_type, amount in components:
            if amount:
                lines.append(
                    _line(
                        occurred_at=occurred_at,
                        ref_type="order",
                        ref_id=order.id,
                        account=(code, name, account_type),
                        credit=int(amount),
                        memo=memo,
                    )
                )
        if order.round_off_minor > 0:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="order",
                    ref_id=order.id,
                    account=ROUNDING_INCOME.ledger_tuple,
                    credit=order.round_off_minor,
                    memo=memo,
                )
            )
        elif order.round_off_minor < 0:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="order",
                    ref_id=order.id,
                    account=ROUNDING_EXPENSE.ledger_tuple,
                    debit=-order.round_off_minor,
                    memo=memo,
                )
            )
        # Sales Revenue/GST above are booked at the pre-discount line total
        # (order.subtotal_minor + tax), but the clearing debit above is
        # order.total_minor, which is already net of these two. Without a
        # contra-revenue debit here the entry is short exactly this amount
        # on every discounted or points-redeemed order.
        manual_discount = int(order.manual_discount_minor or 0)
        if manual_discount:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="order",
                    ref_id=order.id,
                    account=DISCOUNTS_GIVEN.ledger_tuple,
                    debit=manual_discount,
                    memo=memo,
                )
            )
        points_redeemed = int(order.points_redeemed_minor or 0)
        if points_redeemed:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="order",
                    ref_id=order.id,
                    account=LOYALTY_POINTS_REDEEMED.ledger_tuple,
                    debit=points_redeemed,
                    memo=memo,
                )
            )

    # Replay each batch from its initial quantity instead of valuing each
    # fractional movement independently. Whole-paise deltas are the change
    # between HALF_UP-rounded before/after batch values, so sub-paise residuals
    # carry into the next movement and a fully consumed batch clears its exact
    # receipt value. Pre-range rows must remain in the replay even though they
    # do not emit lines, otherwise a date-range report could allocate the same
    # fractional residual differently from the all-time ledger.
    inventory_changes = await load_inventory_value_changes(
        session,
        company_id=company_id,
        start_at=start_at,
        end_exclusive=end_exclusive,
    )
    for change in inventory_changes:
        movement = change.movement
        inventory_delta = change.inventory_delta_minor
        if inventory_delta == 0:
            continue
        amount = abs(inventory_delta)
        is_sale_flow = movement.type in {"sale", "refund_restock"}
        offset_account = (
            COST_OF_GOODS_SOLD.ledger_tuple
            if is_sale_flow
            else OTHER_EXPENSES.ledger_tuple
        )
        ref_type = "stock_sale" if is_sale_flow else "stock_adjustment"
        default_memo = {
            "sale": "Inventory consumed by sale",
            "refund_restock": "Inventory restocked by refund",
            "waste": "Inventory written off as waste",
            "damage": "Damaged inventory written off",
            "adjustment": "Inventory count variance",
        }[movement.type]
        # A positive count correction credits Other Expenses as a
        # contra-expense; a negative correction/waste/damage debits it.
        # This keeps the balance sheet tied to physical batch value without
        # pretending that a count variance is supplier AP or sales COGS.
        offset_side = (
            {"credit": amount} if inventory_delta > 0 else {"debit": amount}
        )
        inventory_side = (
            {"debit": amount} if inventory_delta > 0 else {"credit": amount}
        )
        memo = movement.note or default_memo
        lines.extend(
            [
                _line(
                    occurred_at=movement.created_at,
                    ref_type=ref_type,
                    ref_id=movement.id,
                    account=offset_account,
                    memo=memo,
                    **offset_side,
                ),
                _line(
                    occurred_at=movement.created_at,
                    ref_type=ref_type,
                    ref_id=movement.id,
                    account=INVENTORY.ledger_tuple,
                    memo=memo,
                    **inventory_side,
                ),
            ]
        )

    refund_at = func.coalesce(Refund.settled_at, Refund.created_at)
    refund_stmt = (
        select(Refund, Order)
        .join(Order, Order.id == Refund.order_id)
        .where(
            Order.company_id == company_id,
            refund_at < end_exclusive,
        )
        .order_by(Refund.order_id, refund_at, Refund.id)
    )
    all_refunds = (await session.execute(refund_stmt)).all()
    cumulative_by_order: dict[UUID, int] = {}
    for refund, order in all_refunds:
        previous = cumulative_by_order.get(order.id, 0)
        cumulative = previous + refund.amount_minor
        cumulative_by_order[order.id] = cumulative
        occurred_at = getattr(refund, "settled_at", None) or refund.created_at
        if start_at is not None and occurred_at < start_at:
            continue

        # order.total_minor includes any tip folded on at payment time
        # (see PaymentCreateRequest.tip_minor / record_payment), but a tip
        # is never part of the taxable bill — proportioning tax against the
        # tip-inclusive total would understate every refund's tax reversal
        # whenever the order also had a tip.
        taxable_total = int(order.total_minor or 0) - int(order.tip_minor or 0)
        allocated_tax: list[tuple[tuple[str, str, str], int]] = []
        for account, component in (
            (CGST_PAYABLE.ledger_tuple, order.cgst_minor),
            (SGST_PAYABLE.ledger_tuple, order.sgst_minor),
            (IGST_PAYABLE.ledger_tuple, order.igst_minor),
            (CESS_PAYABLE.ledger_tuple, order.cess_minor),
        ):
            current = _proportional(component, cumulative, taxable_total)
            prior = _proportional(component, previous, taxable_total)
            amount = current - prior
            if amount:
                allocated_tax.append((account, amount))
        tax_total = sum(amount for _, amount in allocated_tax)

        # A single Payment bundles bill + tip (see TIPS_PAYABLE credit of the
        # full order.tip_minor at sale time, ~line 426 above), so a large
        # enough refund necessarily claws back some of that tip too. The tip
        # sits on top of taxable_total (subtotal + tax), so — mirroring the
        # tax proportion above, which treats taxable_total as the denominator
        # a refund is applied against first — nothing is attributed to the
        # tip until cumulative refunds-to-date exceed taxable_total; only the
        # excess beyond that (capped at order.tip_minor, since taxable_total +
        # tip_minor == total_minor) is tip.
        total_minor = int(order.total_minor or 0)
        current_tip = cumulative_refunded_tip_minor(
            total_minor=total_minor,
            tip_minor=int(order.tip_minor or 0),
            cumulative_refunded_minor=cumulative,
        )
        prior_tip = cumulative_refunded_tip_minor(
            total_minor=total_minor,
            tip_minor=int(order.tip_minor or 0),
            cumulative_refunded_minor=previous,
        )
        tip_amount = current_tip - prior_tip

        return_amount = max(0, refund.amount_minor - tax_total - tip_amount)
        memo = f"Refund for {order.invoice_no or order.id.hex[:8]}"
        if return_amount:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="refund",
                    ref_id=refund.id,
                    account=SALES_RETURNS.ledger_tuple,
                    debit=return_amount,
                    memo=memo,
                )
            )
        if tip_amount:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="refund",
                    ref_id=refund.id,
                    account=TIPS_PAYABLE.ledger_tuple,
                    debit=tip_amount,
                    memo=memo,
                )
            )
        for account, amount in allocated_tax:
            lines.append(
                _line(
                    occurred_at=occurred_at,
                    ref_type="refund",
                    ref_id=refund.id,
                    account=account,
                    debit=amount,
                    memo=memo,
                )
            )
        settlement_method = refund.settlement_method
        if not settlement_method and refund.mode == "credit_note":
            settlement_method = "store_credit"
        if not settlement_method and refund.mode == "original":
            methods = payment_methods_by_order.get(order.id, set())
            settlement_method = next(iter(methods)) if len(methods) == 1 else None
        settlement_account = (
            STORE_CREDIT_LIABILITY.ledger_tuple
            if settlement_method == "store_credit"
            else _method_account(settlement_method or refund.mode)
        )
        lines.append(
            _line(
                occurred_at=occurred_at,
                ref_type="refund",
                ref_id=refund.id,
                account=settlement_account,
                credit=refund.amount_minor,
                memo=memo,
            )
        )

    # Tip payouts clear TIPS_PAYABLE back out to staff. TIPS_PAYABLE is
    # credited at payment time and debited on refund (above); this is the
    # only entry that ever debits it outside a refund, mirroring the
    # Expense pattern (a discrete, timestamped cash movement) rather than
    # ManualCollection's date-only daily aggregate.
    tip_payout_stmt = select(TipPayout).where(
        TipPayout.company_id == company_id,
        TipPayout.voided_at.is_(None),
        TipPayout.paid_at < end_exclusive,
    )
    if start_at is not None:
        tip_payout_stmt = tip_payout_stmt.where(TipPayout.paid_at >= start_at)
    tip_payout_stmt = tip_payout_stmt.order_by(TipPayout.paid_at, TipPayout.id)
    for payout in (await session.execute(tip_payout_stmt)).scalars().all():
        memo = payout.note or "Tip payout"
        lines.extend(
            [
                _line(
                    occurred_at=payout.paid_at,
                    ref_type="tip_payout",
                    ref_id=payout.id,
                    account=TIPS_PAYABLE.ledger_tuple,
                    debit=payout.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=payout.paid_at,
                    ref_type="tip_payout",
                    ref_id=payout.id,
                    account=_method_account(payout.method),
                    credit=payout.amount_minor,
                    memo=memo,
                ),
            ]
        )

    expense_stmt = (
        select(Expense, ExpenseCategory)
        .join(ExpenseCategory, ExpenseCategory.id == Expense.category_id)
        .where(
            Expense.company_id == company_id,
            Expense.deleted_at.is_(None),
            Expense.voided_at.is_(None),
            Expense.paid_at < end_exclusive,
        )
        .order_by(Expense.paid_at, Expense.id)
    )
    if start_at is not None:
        expense_stmt = expense_stmt.where(Expense.paid_at >= start_at)
    for expense, category in (await session.execute(expense_stmt)).all():
        memo = expense.vendor_name or expense.note or category.name
        lines.extend(
            [
                _line(
                    occurred_at=expense.paid_at,
                    ref_type="expense",
                    ref_id=expense.id,
                    account=_expense_account(category),
                    debit=expense.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=expense.paid_at,
                    ref_type="expense",
                    ref_id=expense.id,
                    account=_expense_payment_account(expense.paid_via),
                    credit=expense.amount_minor,
                    memo=memo,
                ),
            ]
        )

    capital_stmt = (
        select(CapitalEntry, Partner)
        .join(Partner, Partner.id == CapitalEntry.partner_id)
        .where(
            Partner.company_id == company_id,
            CapitalEntry.effective_at < end_exclusive,
            CapitalEntry.type.in_(("invest", "withdraw")),
            CapitalEntry.voided_at.is_(None),
        )
        .order_by(CapitalEntry.effective_at, CapitalEntry.id)
    )
    if start_at is not None:
        capital_stmt = capital_stmt.where(CapitalEntry.effective_at >= start_at)
    for entry, partner in (await session.execute(capital_stmt)).all():
        if entry.voided_at is not None or entry.type not in {"invest", "withdraw"}:
            continue
        memo = entry.note or f"{entry.type.replace('_', ' ').title()} - {partner.name}"
        settlement_account = _capital_settlement_account(entry.settlement_account)
        if entry.type == "invest":
            debit_account, credit_account = (
                settlement_account,
                PARTNER_CAPITAL.ledger_tuple,
            )
        elif entry.type == "withdraw":
            debit_account, credit_account = (
                PARTNER_CAPITAL.ledger_tuple,
                settlement_account,
            )
        else:
            continue
        lines.extend(
            [
                _line(
                    occurred_at=entry.effective_at,
                    ref_type="capital",
                    ref_id=entry.id,
                    account=debit_account,
                    debit=entry.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=entry.effective_at,
                    ref_type="capital",
                    ref_id=entry.id,
                    account=credit_account,
                    credit=entry.amount_minor,
                    memo=memo,
                ),
            ]
        )

    # ------------------------------------------------------------------
    # Depreciation — never manually entered or posted by a cron job.
    # Computed analytically from the Asset register every time this ledger
    # is built, same "derived from source data" philosophy as every section
    # above (see module docstring and depreciation.py). Straight-line only;
    # depreciation.py documents why other Asset.depreciation_method values
    # are a no-op for now rather than a guess.
    # ------------------------------------------------------------------
    now = datetime.now(UTC)
    depreciation_end_as_of = min(end_exclusive, now)
    depreciation_start_at = min(start_at, now) if start_at is not None else None
    asset_stmt = select(Asset).where(
        Asset.company_id == company_id,
        Asset.deleted_at.is_(None),
    )
    for asset in (await session.execute(asset_stmt)).scalars().all():
        amount = asset_depreciation_expense_minor(
            asset,
            start_at=depreciation_start_at,
            end_as_of=depreciation_end_as_of,
        )
        if amount <= 0:
            continue
        memo = f"Depreciation — {asset.name}"
        lines.extend(
            [
                _line(
                    occurred_at=depreciation_end_as_of,
                    ref_type="depreciation",
                    ref_id=asset.id,
                    account=DEPRECIATION_EXPENSE.ledger_tuple,
                    debit=amount,
                    memo=memo,
                ),
                _line(
                    occurred_at=depreciation_end_as_of,
                    ref_type="depreciation",
                    ref_id=asset.id,
                    account=ACCUMULATED_DEPRECIATION.ledger_tuple,
                    credit=amount,
                    memo=memo,
                ),
            ]
        )

    lines.extend(
        await _posted_journal_ledger_lines(
            session,
            company_id=company_id,
            start_at=start_at,
            end_exclusive=end_exclusive,
        )
    )
    _validate_account_code_meanings(lines)
    lines.sort(key=lambda item: (item.occurred_at, item.ref_type, str(item.ref_id)))
    return lines

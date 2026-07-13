"""Balanced double-entry view derived from immutable operational records.

The operational tables remain the source of truth. This service translates
their financial effects into one journal so Trial Balance, Balance Sheet and
General Ledger cannot disagree with each other.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    Branch,
    CapitalEntry,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    Order,
    Partner,
    Payment,
    Refund,
    StockMovement,
)
from app.services.pos.pricing import split_tax_from_inclusive_minor


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
    "cash": ("1000", "Cash", "asset"),
    "bank": ("1010", "Bank", "asset"),
    "card": ("1100", "Card Clearing", "asset"),
    "upi": ("1110", "UPI / QR Clearing", "asset"),
    "qr": ("1110", "UPI / QR Clearing", "asset"),
    "wallet": ("1120", "Wallet Clearing", "asset"),
}


def _method_account(method: str | None) -> tuple[str, str, str]:
    return ACCOUNT_BY_METHOD.get(
        (method or "").lower(),
        ("1190", "Unreconciled Settlement", "asset"),
    )


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
                    account=("1200", "POS Settlement Clearing", "asset"),
                    credit=payment.amount_minor,
                    memo=memo,
                ),
            ]
        )

    sale_at = Order.invoice_issued_at
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
                account=("1200", "POS Settlement Clearing", "asset"),
                debit=order.total_minor,
                memo=memo,
            )
        )
        components = [
            ("4000", "Sales Revenue", "revenue", order.subtotal_minor),
            ("2101", "CGST Payable", "liability", order.cgst_minor),
            ("2102", "SGST Payable", "liability", order.sgst_minor),
            ("2103", "IGST Payable", "liability", order.igst_minor),
            ("2104", "Cess Payable", "liability", order.cess_minor),
            ("2200", "Tips Payable", "liability", order.tip_minor),
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
                    account=("4900", "Rounding Income", "revenue"),
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
                    account=("5900", "Rounding Expense", "expense"),
                    debit=-order.round_off_minor,
                    memo=memo,
                )
            )

    stock_stmt = (
        select(StockMovement)
        .join(Branch, Branch.id == StockMovement.branch_id)
        .where(
            Branch.company_id == company_id,
            StockMovement.type == "sale",
            StockMovement.created_at < end_exclusive,
        )
        .order_by(StockMovement.created_at, StockMovement.id)
    )
    if start_at is not None:
        stock_stmt = stock_stmt.where(StockMovement.created_at >= start_at)
    for movement in (await session.execute(stock_stmt)).scalars().all():
        quantity = Decimal(str(movement.qty_delta or 0))
        if quantity >= 0:
            continue
        amount = int(abs(quantity) * int(movement.cost_per_unit_minor or 0))
        if amount <= 0:
            continue
        memo = movement.note or "Inventory consumed by sale"
        lines.extend(
            [
                _line(
                    occurred_at=movement.created_at,
                    ref_type="stock_sale",
                    ref_id=movement.id,
                    account=("5000", "Cost of Goods Sold", "expense"),
                    debit=amount,
                    memo=memo,
                ),
                _line(
                    occurred_at=movement.created_at,
                    ref_type="stock_sale",
                    ref_id=movement.id,
                    account=("1300", "Inventory", "asset"),
                    credit=amount,
                    memo=memo,
                ),
            ]
        )

    refund_stmt = (
        select(Refund, Order)
        .join(Order, Order.id == Refund.order_id)
        .where(
            Order.company_id == company_id,
            Refund.created_at < end_exclusive,
        )
        .order_by(Refund.order_id, Refund.created_at, Refund.id)
    )
    all_refunds = (await session.execute(refund_stmt)).all()
    cumulative_by_order: dict[UUID, int] = {}
    for refund, order in all_refunds:
        previous = cumulative_by_order.get(order.id, 0)
        cumulative = previous + refund.amount_minor
        cumulative_by_order[order.id] = cumulative
        if start_at is not None and refund.created_at < start_at:
            continue

        allocated_tax: list[tuple[tuple[str, str, str], int]] = []
        for account, component in (
            (("2101", "CGST Payable", "liability"), order.cgst_minor),
            (("2102", "SGST Payable", "liability"), order.sgst_minor),
            (("2103", "IGST Payable", "liability"), order.igst_minor),
            (("2104", "Cess Payable", "liability"), order.cess_minor),
        ):
            current = _proportional(component, cumulative, order.total_minor)
            prior = _proportional(component, previous, order.total_minor)
            amount = current - prior
            if amount:
                allocated_tax.append((account, amount))
        tax_total = sum(amount for _, amount in allocated_tax)
        return_amount = max(0, refund.amount_minor - tax_total)
        memo = f"Refund for {order.invoice_no or order.id.hex[:8]}"
        if return_amount:
            lines.append(
                _line(
                    occurred_at=refund.created_at,
                    ref_type="refund",
                    ref_id=refund.id,
                    account=("4090", "Sales Returns", "revenue"),
                    debit=return_amount,
                    memo=memo,
                )
            )
        for account, amount in allocated_tax:
            lines.append(
                _line(
                    occurred_at=refund.created_at,
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
            ("2160", "Store Credit Liability", "liability")
            if settlement_method == "store_credit"
            else _method_account(settlement_method or refund.mode)
        )
        lines.append(
            _line(
                occurred_at=refund.created_at,
                ref_type="refund",
                ref_id=refund.id,
                account=settlement_account,
                credit=refund.amount_minor,
                memo=memo,
            )
        )

    expense_stmt = (
        select(Expense, ExpenseCategory)
        .join(ExpenseCategory, ExpenseCategory.id == Expense.category_id)
        .where(
            Expense.company_id == company_id,
            Expense.deleted_at.is_(None),
            Expense.paid_at < end_exclusive,
        )
        .order_by(Expense.paid_at, Expense.id)
    )
    if start_at is not None:
        expense_stmt = expense_stmt.where(Expense.paid_at >= start_at)
    for expense, category in (await session.execute(expense_stmt)).all():
        code = category.code or "5100"
        memo = expense.vendor_name or expense.note or category.name
        lines.extend(
            [
                _line(
                    occurred_at=expense.paid_at,
                    ref_type="expense",
                    ref_id=expense.id,
                    account=(code, category.name, "expense"),
                    debit=expense.amount_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=expense.paid_at,
                    ref_type="expense",
                    ref_id=expense.id,
                    account=_method_account(expense.paid_via),
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
        )
        .order_by(CapitalEntry.effective_at, CapitalEntry.id)
    )
    if start_at is not None:
        capital_stmt = capital_stmt.where(CapitalEntry.effective_at >= start_at)
    for entry, partner in (await session.execute(capital_stmt)).all():
        memo = entry.note or f"{entry.type.replace('_', ' ').title()} - {partner.name}"
        if entry.type == "invest":
            debit_account, credit_account = (
                ("1010", "Bank", "asset"),
                ("3000", "Partner Capital", "equity"),
            )
        elif entry.type in {"withdraw", "profit_share"}:
            debit_account, credit_account = (
                ("3000", "Partner Capital", "equity"),
                ("1010", "Bank", "asset"),
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

    ticket_stmt = (
        select(EventTicket, Event)
        .join(Event, Event.id == EventTicket.event_id)
        .where(
            Event.company_id == company_id,
            EventTicket.order_id.is_(None),
            EventTicket.created_at < end_exclusive,
            EventTicket.status.in_(("sold", "checked_in")),
        )
        .order_by(EventTicket.created_at, EventTicket.id)
    )
    if start_at is not None:
        ticket_stmt = ticket_stmt.where(EventTicket.created_at >= start_at)
    for ticket, event in (await session.execute(ticket_stmt)).all():
        taxable, cgst, sgst, igst = split_tax_from_inclusive_minor(
            ticket.price_paid_minor,
            Decimal(str(event.tax_rate or 0)),
            True,
        )
        memo = f"Direct event ticket {ticket.ticket_no}"
        lines.extend(
            [
                _line(
                    occurred_at=ticket.created_at,
                    ref_type="event_ticket",
                    ref_id=ticket.id,
                    account=("1190", "Unreconciled Settlement", "asset"),
                    debit=ticket.price_paid_minor,
                    memo=memo,
                ),
                _line(
                    occurred_at=ticket.created_at,
                    ref_type="event_ticket",
                    ref_id=ticket.id,
                    account=("4000", "Sales Revenue", "revenue"),
                    credit=taxable,
                    memo=memo,
                ),
            ]
        )
        for account, amount in (
            (("2101", "CGST Payable", "liability"), cgst),
            (("2102", "SGST Payable", "liability"), sgst),
            (("2103", "IGST Payable", "liability"), igst),
        ):
            if amount:
                lines.append(
                    _line(
                        occurred_at=ticket.created_at,
                        ref_type="event_ticket",
                        ref_id=ticket.id,
                        account=account,
                        credit=amount,
                        memo=memo,
                    )
                )

    lines.sort(key=lambda item: (item.occurred_at, item.ref_type, str(item.ref_id)))
    return lines

"""Exact purchase valuation and balanced operational journal posting.

Purchase quantities may be fractional while the ledger is denominated in
whole paise.  Every GRN therefore rounds each received line to the nearest
paise (half up) and sums those line amounts.  Rounding only once after summing
fractional lines would make the API response, batch evidence, and accounting
journal disagree.

``unit_cost_minor`` is the complete capitalised cost per inventory unit.  It
may include unavoidable freight or non-creditable tax only when the operator
has deliberately included those amounts in the unit cost.  Recoverable GST,
freight entered separately, invoice discounts, and unallocated invoice
variance are not modelled yet; callers must not hide them in
``supplier_invoice_amount_minor``.  A mismatched invoice total is rejected
rather than posted to an invented account.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import TYPE_CHECKING
from uuid import uuid4

from sqlalchemy import select

from app.core.errors import BusinessRuleError
from app.models import Account, JournalEntry, JournalLine

if TYPE_CHECKING:
    from datetime import datetime
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.services.accounting.accounts import AccountDefinition


def received_line_total_minor(quantity: object, unit_cost_minor: int) -> int:
    """Return one received line's capitalised value in whole paise."""

    try:
        qty = Decimal(str(quantity))
        unit_cost = Decimal(int(unit_cost_minor))
        value = (qty * unit_cost).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise BusinessRuleError(
            "GRN quantity and unit cost must be valid numbers"
        ) from exc
    if qty <= 0:
        raise BusinessRuleError("GRN line quantity must be greater than zero")
    if unit_cost < 0:
        raise BusinessRuleError("GRN line unit cost cannot be negative")
    return int(value)


def received_total_minor(lines: object) -> int:
    """Sum line-wise rounded capitalised values for GRN-like objects."""

    return sum(
        received_line_total_minor(line.qty, int(line.unit_cost_minor))
        for line in lines
    )


def require_invoice_matches_capitalised_total(
    *, supplier_invoice_amount_minor: int | None, capitalised_total_minor: int
) -> None:
    """Fail closed when invoice variance has no explicit accounting category."""

    if supplier_invoice_amount_minor is None:
        return
    if int(supplier_invoice_amount_minor) != int(capitalised_total_minor):
        raise BusinessRuleError(
            "Supplier invoice amount does not match the capitalised GRN line total. "
            "Allocate freight, tax, discounts, or other variance into the line unit costs "
            "before posting; separate purchase variance and input-tax accounts are not "
            "supported yet."
        )


async def post_two_sided_operational_journal(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    ref_type: str,
    ref_id: UUID,
    posted_at: datetime,
    amount_minor: int,
    debit_account: AccountDefinition,
    credit_account: AccountDefinition,
    memo: str,
) -> JournalEntry | None:
    """Create one immutable, balanced source-linked operational journal.

    A zero-value receipt is valid physical inventory evidence but has no
    financial entry.  Positive entries fail closed if the company's canonical
    chart is missing or has been repurposed; silently creating or remapping an
    account during a money write would conceal configuration corruption.
    """

    amount = int(amount_minor)
    if amount < 0:
        raise BusinessRuleError("operational journal amount cannot be negative")
    if amount == 0:
        return None

    requested = (debit_account, credit_account)
    rows = (
        await session.execute(
            select(Account).where(
                Account.company_id == company_id,
                Account.code.in_([definition.code for definition in requested]),
            )
        )
    ).scalars().all()
    by_code = {row.code: row for row in rows}
    for definition in requested:
        row = by_code.get(definition.code)
        if row is None:
            raise BusinessRuleError(
                f"Canonical account {definition.code} {definition.name} is missing. "
                "Run the guarded chart-of-accounts reconciliation before posting."
            )
        if (
            row.name != definition.name
            or row.type != definition.type
            or row.normal_side != definition.normal_side
            or not row.is_active
        ):
            raise BusinessRuleError(
                f"Canonical account {definition.code} has incompatible configuration; "
                "reconcile the chart of accounts before posting."
            )

    entry = JournalEntry(
        id=uuid4(),
        company_id=company_id,
        branch_id=branch_id,
        ref_type=ref_type,
        ref_id=ref_id,
        posted_at=posted_at,
        memo=memo,
        total_minor=amount,
    )
    session.add(entry)
    session.add_all(
        [
            JournalLine(
                id=uuid4(),
                journal_entry_id=entry.id,
                account_id=by_code[debit_account.code].id,
                side="dr",
                amount_minor=amount,
                memo=memo,
            ),
            JournalLine(
                id=uuid4(),
                journal_entry_id=entry.id,
                account_id=by_code[credit_account.code].id,
                side="cr",
                amount_minor=amount,
                memo=memo,
            ),
        ]
    )
    return entry


__all__ = [
    "post_two_sided_operational_journal",
    "received_line_total_minor",
    "received_total_minor",
    "require_invoice_matches_capitalised_total",
]

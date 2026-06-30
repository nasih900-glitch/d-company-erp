"""POS pricing engine — India / Kerala rules.

Given a list of (menu_item_id, qty, modifiers) tuples, this service:

  1. Loads the menu items snapshot (price, tax rate, HSN/SAC).
  2. For each line, treats the menu price as TAX-INCLUSIVE (Kerala café
     convention) and works backwards: taxable = price / (1 + rate).
  3. Splits tax into CGST + SGST (intra-state) or IGST (inter-state)
     depending on place_of_supply vs branch state.
  4. Aggregates order totals: subtotal, tax-by-bucket, round-off to nearest
     rupee.
  5. Allocates the next invoice number from in_invoice_counters using a
     row-level lock so concurrent cashiers cannot collide.

Every value returned is an int in minor units. No floats anywhere.

See docs/INDIA_TAX_COMPLIANCE.md for the rules being applied.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessRuleError, NotFoundError
from app.models import (
    Branch,
    Company,
    Customer,
    CustomerMembership,
    InvoiceCounter,
    MembershipTier,
    MenuItem,
)


# ---------------------------------------------------------------------------
# Data classes — the engine's input and output
# ---------------------------------------------------------------------------
@dataclass(frozen=True, slots=True)
class LineRequest:
    menu_item_id: UUID
    qty: int  # whole qty for now; Numeric(10,3) supports decimals later


@dataclass(frozen=True, slots=True)
class PricedLine:
    menu_item_id: UUID
    name: str
    sku: str
    hsn_or_sac: str
    qty: int
    unit_inclusive_minor: int
    line_inclusive_minor: int
    discount_minor: int
    taxable_value_minor: int
    tax_rate: Decimal
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    cess_minor: int


@dataclass(frozen=True, slots=True)
class PricedOrder:
    lines: list[PricedLine]
    subtotal_taxable_minor: int
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    cess_minor: int
    discount_minor: int
    round_off_minor: int
    total_minor: int


@dataclass(frozen=True, slots=True)
class MembershipDiscountRates:
    food: Decimal = Decimal("0")
    gaming: Decimal = Decimal("0")
    hookah: Decimal = Decimal("0")


# ---------------------------------------------------------------------------
# Helpers — money math without floats
# ---------------------------------------------------------------------------
def _split_tax_from_inclusive(
    inclusive_minor: int, rate: Decimal, intra_state: bool
) -> tuple[int, int, int, int]:
    """Return (taxable, cgst, sgst, igst). Cess always 0 for now (no luxury items)."""
    if rate <= 0:
        return (inclusive_minor, 0, 0, 0)
    one_plus_rate = Decimal(1) + rate
    taxable = (Decimal(inclusive_minor) / one_plus_rate).quantize(
        Decimal("1"), rounding=ROUND_HALF_UP
    )
    tax = inclusive_minor - int(taxable)
    if intra_state:
        cgst = tax // 2
        sgst = tax - cgst
        return (int(taxable), cgst, sgst, 0)
    return (int(taxable), 0, 0, tax)


def split_tax_from_inclusive_minor(
    inclusive_minor: int, rate: Decimal, intra_state: bool = True
) -> tuple[int, int, int, int]:
    """Public wrapper for India GST-inclusive price splitting.

    Returns (taxable, cgst, sgst, igst), all in minor units.
    """
    return _split_tax_from_inclusive(inclusive_minor, rate, intra_state)


def _round_to_rupee(minor: int) -> tuple[int, int]:
    """Round to nearest 100 minor units (₹1). Return (rounded, round_off_delta)."""
    rupees = (minor + 50) // 100  # round half up
    rounded = rupees * 100
    return rounded, rounded - minor


def _discount_for_item_type(item_type: str, rates: MembershipDiscountRates) -> Decimal:
    """Return the membership discount rate that applies to this menu item type."""
    if item_type in {"food", "drink", "dessert"}:
        return rates.food
    if item_type in {"gaming", "streaming"}:
        return rates.gaming
    if item_type == "hookah":
        return rates.hookah
    return Decimal("0")


def _discount_minor(inclusive_minor: int, rate: Decimal) -> int:
    """Calculate a line discount in paise using Decimal half-up rounding."""
    if inclusive_minor <= 0 or rate <= 0:
        return 0
    discount = (Decimal(inclusive_minor) * rate).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return min(inclusive_minor, max(0, int(discount)))


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------
class OrderPricingService:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def price_order(
        self,
        *,
        company_id: UUID,
        branch_id: UUID,
        line_requests: list[LineRequest],
        customer_phone: str | None = None,
        place_of_supply_state_code: str | None = None,
        delivery_via: str | None = None,
    ) -> PricedOrder:
        """Price an order under the India GST rules.

        delivery_via != None means Section 9(5) aggregator (Zomato/Swiggy);
        in that case OUR invoice carries zero tax — the aggregator's does.
        """
        if not line_requests:
            raise BusinessRuleError("order must have at least one line")

        # --- Tenant context ---
        company = await self.session.get(Company, company_id)
        if not company:
            raise NotFoundError("company not found")
        branch = await self.session.get(Branch, branch_id)
        if not branch:
            raise NotFoundError("branch not found")

        is_composition = bool(company.is_composition)
        is_aggregator = delivery_via and delivery_via.lower() not in {"inhouse", ""}
        # Place of supply: defaults to branch state. D Company is Kerala-first,
        # so missing legacy branch state falls back to GST state code 32.
        branch_state = branch.state_code or "32"
        pos_state = place_of_supply_state_code or branch_state
        intra_state = pos_state == branch_state
        membership_rates = await self._membership_discount_rates(
            company_id=company_id,
            customer_phone=customer_phone,
        )

        # --- Load all menu items in one query ---
        ids = list({lr.menu_item_id for lr in line_requests})
        rows = (
            await self.session.execute(
                select(MenuItem).where(
                    MenuItem.company_id == company_id,
                    MenuItem.id.in_(ids),
                    MenuItem.deleted_at.is_(None),
                )
            )
        ).scalars().all()
        items_by_id = {item.id: item for item in rows}
        for lr in line_requests:
            if lr.menu_item_id not in items_by_id:
                raise NotFoundError(f"menu item {lr.menu_item_id} not found")

        priced_lines: list[PricedLine] = []
        sub_taxable = sub_cgst = sub_sgst = sub_igst = sub_cess = sub_inclusive = sub_discount = 0

        for lr in line_requests:
            item = items_by_id[lr.menu_item_id]
            if not item.is_available:
                raise BusinessRuleError(f"menu item {item.sku} is not available")
            if lr.qty <= 0:
                raise BusinessRuleError(f"qty must be positive for {item.sku}")

            line_gross_inclusive = item.base_price_minor * lr.qty
            line_discount = _discount_minor(
                line_gross_inclusive,
                _discount_for_item_type(item.type, membership_rates),
            )
            line_inclusive = line_gross_inclusive - line_discount
            rate = Decimal(str(item.tax_rate or 0))

            # Composition and aggregator: zero tax on OUR bill.
            if is_composition or is_aggregator:
                taxable, cgst, sgst, igst = (line_inclusive, 0, 0, 0)
            else:
                taxable, cgst, sgst, igst = _split_tax_from_inclusive(
                    line_inclusive, rate, intra_state
                )

            priced_lines.append(
                PricedLine(
                    menu_item_id=item.id,
                    name=item.name,
                    sku=item.sku,
                    hsn_or_sac=item.hsn_code or "",
                    qty=lr.qty,
                    unit_inclusive_minor=item.base_price_minor,
                    line_inclusive_minor=line_inclusive,
                    discount_minor=line_discount,
                    taxable_value_minor=taxable,
                    tax_rate=rate,
                    cgst_minor=cgst,
                    sgst_minor=sgst,
                    igst_minor=igst,
                    cess_minor=0,
                )
            )
            sub_taxable += taxable
            sub_cgst += cgst
            sub_sgst += sgst
            sub_igst += igst
            sub_inclusive += line_inclusive
            sub_discount += line_discount

        # Round whole-order total to nearest rupee.
        rounded, round_off = _round_to_rupee(sub_inclusive)

        return PricedOrder(
            lines=priced_lines,
            subtotal_taxable_minor=sub_taxable,
            cgst_minor=sub_cgst,
            sgst_minor=sub_sgst,
            igst_minor=sub_igst,
            cess_minor=sub_cess,
            discount_minor=sub_discount,
            round_off_minor=round_off,
            total_minor=rounded,
        )

    async def _membership_discount_rates(
        self,
        *,
        company_id: UUID,
        customer_phone: str | None,
    ) -> MembershipDiscountRates:
        """Load active membership discounts for an existing customer phone."""
        if not customer_phone:
            return MembershipDiscountRates()

        now = datetime.now(timezone.utc)
        tier = (
            await self.session.execute(
                select(MembershipTier)
                .join(CustomerMembership, CustomerMembership.tier_id == MembershipTier.id)
                .join(Customer, Customer.id == CustomerMembership.customer_id)
                .where(
                    Customer.company_id == company_id,
                    Customer.phone == customer_phone,
                    Customer.deleted_at.is_(None),
                    CustomerMembership.cancelled_at.is_(None),
                    CustomerMembership.expires_at > now,
                    MembershipTier.company_id == company_id,
                    MembershipTier.deleted_at.is_(None),
                )
                .order_by(CustomerMembership.expires_at.desc())
                .limit(1)
            )
        ).scalar_one_or_none()
        if not tier:
            return MembershipDiscountRates()

        return MembershipDiscountRates(
            food=Decimal(str(tier.food_discount_pct or 0)),
            gaming=Decimal(str(tier.gaming_discount_pct or 0)),
            hookah=Decimal(str(tier.hookah_discount_pct or 0)),
        )


# ---------------------------------------------------------------------------
# Invoice number allocator — atomic per (branch, FY, series)
# ---------------------------------------------------------------------------
def fiscal_year_for(d: date) -> str:
    """Indian FY string for a date. April-March. e.g. 2026-04-01 → '2026-27'."""
    if d.month >= 4:
        return f"{d.year}-{str(d.year + 1)[-2:]}"
    return f"{d.year - 1}-{str(d.year)[-2:]}"


class InvoiceNumberService:
    """Allocates the next invoice number atomically using a row-level lock.

    Format: ``{prefix}/{branch_code}/{FY}/{seq}`` with seq zero-padded to 5
    digits. Total length kept ≤ 16 chars per Rule 46.
    """

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def allocate(
        self,
        *,
        branch_id: UUID,
        branch_code: str,
        prefix: str = "D",
        series: str = "invoice",
        at: datetime | None = None,
    ) -> tuple[str, str]:
        """Return (invoice_no, fiscal_year)."""
        now = at or datetime.now(timezone.utc)
        fy = fiscal_year_for(now.date())

        # Get-or-create the counter row, then SELECT … FOR UPDATE on it.
        existing = (
            await self.session.execute(
                select(InvoiceCounter)
                .where(
                    InvoiceCounter.branch_id == branch_id,
                    InvoiceCounter.fiscal_year == fy,
                    InvoiceCounter.series == series,
                )
                .with_for_update()
            )
        ).scalar_one_or_none()

        if existing is None:
            counter = InvoiceCounter(
                branch_id=branch_id, fiscal_year=fy, series=series, last_seq=1
            )
            self.session.add(counter)
            await self.session.flush()
            seq = 1
        else:
            existing.last_seq += 1
            seq = existing.last_seq

        invoice_no = f"{prefix}/{branch_code}/{fy}/{seq:05d}"
        if len(invoice_no) > 16:
            # Trim FY to two digits if needed, e.g. "D/MN/26-27/00001"
            fy_short = fy.replace("20", "", 1)
            invoice_no = f"{prefix}/{branch_code}/{fy_short}/{seq:05d}"
        return invoice_no, fy

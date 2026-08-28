"""POS pricing engine — India / Kerala rules.

Given a list of (menu_item_id, qty, modifiers) tuples, this service:

  1. Loads the menu items snapshot (price, tax rate, HSN/SAC).
  2. For each line, uses the item's price mode. Café menu prices usually include
     GST; editable service items can also be priced before GST.
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

import re
from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal, cast
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
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
    MenuModifier,
    MenuModifierGroup,
    MenuVariant,
)

_GSTIN_RE = re.compile(r"^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")
_STATE_CODE_RE = re.compile(r"^[0-9]{2}$")
BillingTaxMode = Literal["regular", "composition", "unregistered", "sez"]


def _billing_tax_mode(
    *,
    registration_type: str | None,
    is_composition: bool,
    supplier_gstin: str | None,
    branch_state: str | None,
) -> BillingTaxMode:
    """Validate the supplier identity before calculating or collecting GST."""
    mode = (registration_type or "regular").strip().lower()
    if mode not in {"regular", "composition", "unregistered", "sez"}:
        raise BusinessRuleError("unsupported GST registration type")

    if (mode == "composition") != bool(is_composition):
        raise BusinessRuleError(
            "GST composition settings disagree; correct Company Settings before billing"
        )

    state_code = (branch_state or "").strip()
    if not _STATE_CODE_RE.fullmatch(state_code):
        raise BusinessRuleError(
            "branch GST state code is missing or invalid; set the two-digit code in Settings"
        )

    if mode != "unregistered":
        gstin = (supplier_gstin or "").strip().upper()
        if not _GSTIN_RE.fullmatch(gstin):
            raise BusinessRuleError(
                "GSTIN is missing or invalid; correct it in Settings before issuing GST bills"
            )
        if not gstin.startswith(state_code):
            raise BusinessRuleError(
                "GSTIN state code does not match the billing branch"
            )

    return cast("BillingTaxMode", mode)


# ---------------------------------------------------------------------------
# Data classes — the engine's input and output
# ---------------------------------------------------------------------------
@dataclass(frozen=True, slots=True)
class ModifierSelection:
    modifier_id: UUID
    quantity: int = 1


@dataclass(frozen=True, slots=True)
class LineRequest:
    menu_item_id: UUID
    qty: int  # whole qty for now; Numeric(10,3) supports decimals later
    variant_id: UUID | None = None
    modifiers: tuple[ModifierSelection, ...] = ()


@dataclass(frozen=True, slots=True)
class PricedVariantSnapshot:
    id: UUID
    name: str
    price_delta_minor: int
    line_delta_minor: int

    def as_dict(self) -> dict[str, str | int]:
        return {
            "variant_id": str(self.id),
            "name": self.name,
            "price_delta_minor": self.price_delta_minor,
            "line_delta_minor": self.line_delta_minor,
        }


@dataclass(frozen=True, slots=True)
class PricedModifierSnapshot:
    id: UUID
    modifier_group_id: UUID
    group_name: str
    name: str
    quantity_per_item: int
    unit_price_delta_minor: int
    per_item_delta_minor: int
    line_delta_minor: int

    def as_dict(self) -> dict[str, str | int]:
        return {
            "modifier_id": str(self.id),
            "modifier_group_id": str(self.modifier_group_id),
            "group_name": self.group_name,
            "name": self.name,
            # ``qty`` and ``price_delta_minor`` are the established Android
            # receipt aliases. The richer totals make the same immutable row
            # useful to web receipts and audit/reconciliation tools.
            "qty": self.quantity_per_item,
            "price_delta_minor": self.unit_price_delta_minor,
            "per_item_delta_minor": self.per_item_delta_minor,
            "line_delta_minor": self.line_delta_minor,
        }


@dataclass(frozen=True, slots=True)
class ResolvedLineCustomization:
    variant: PricedVariantSnapshot | None
    modifiers: tuple[PricedModifierSnapshot, ...]
    unit_delta_minor: int


@dataclass(frozen=True, slots=True)
class PricedLine:
    menu_item_id: UUID
    name: str
    item_type: str
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
    base_unit_price_minor: int = 0
    customization_unit_delta_minor: int = 0
    variant_snapshot: PricedVariantSnapshot | None = None
    modifier_snapshots: tuple[PricedModifierSnapshot, ...] = ()


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
class TimeBasedPricing:
    """GST split for a single non-catalog, time-based service line
    (a gaming/hookah session amount instead of a MenuItem × qty)."""
    total_minor: int
    discount_minor: int
    taxable_minor: int
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    # Portion of discount_minor funded by a reserved free allowance.  The
    # remainder is the tier's percentage discount.
    allowance_discount_minor: int = 0


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


def _split_tax_from_exclusive(
    exclusive_minor: int, rate: Decimal, intra_state: bool
) -> tuple[int, int, int, int]:
    """Return (taxable, cgst, sgst, igst) when base price excludes GST."""
    taxable = max(0, exclusive_minor)
    if rate <= 0:
        return (taxable, 0, 0, 0)
    tax = (Decimal(taxable) * rate).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    tax_minor = int(tax)
    if intra_state:
        cgst = tax_minor // 2
        sgst = tax_minor - cgst
        return (taxable, cgst, sgst, 0)
    return (taxable, 0, 0, tax_minor)


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


def gaming_minutes_allowance_minor(
    *,
    gross_amount_minor: int,
    billable_minutes: int,
    reserved_minutes: int,
    rate_per_hour_minor: int,
) -> int:
    """Value reserved PS5 minutes against the original session snapshot.

    The payable remainder uses the same ceiling formula as session billing,
    then is subtracted from the stored gross amount.  This avoids inventing a
    new price and guarantees that reserving every minute waives the exact
    snapshotted session amount, even when per-minute division has a remainder.
    """
    gross = max(0, int(gross_amount_minor))
    minutes = max(0, int(billable_minutes))
    reserved = min(minutes, max(0, int(reserved_minutes)))
    hourly_rate = max(0, int(rate_per_hour_minor))
    if not gross or not minutes or not reserved or not hourly_rate:
        return 0
    remaining_minutes = minutes - reserved
    remaining_gross = (remaining_minutes * hourly_rate + 59) // 60
    return max(0, gross - min(gross, remaining_gross))


def apply_manual_discount(
    *, line_discount_total_minor: int, manual_discount_minor: int, rounded_total_minor: int
) -> tuple[int, int, int]:
    """Fold a cashier's custom discount into a line-based recompute.

    Returns ``(clamped_manual_discount_minor, combined_discount_minor,
    final_total_minor)``. The manual amount is clamped to the rounded total so
    it can never take an order negative (e.g. a membership discount or a
    removed line already ate most of the bill). Used identically whenever an
    order's totals are rebuilt from its lines — order creation never calls
    this since a manual discount is only ever applied to an existing order.
    """
    clamped = min(max(0, manual_discount_minor), max(0, rounded_total_minor))
    return (
        clamped,
        line_discount_total_minor + clamped,
        rounded_total_minor - clamped,
    )


def apply_points_redemption(
    *, discount_so_far_minor: int, points_redeemed_minor: int, remaining_total_minor: int
) -> tuple[int, int, int]:
    """Fold a customer's loyalty-points redemption into an already-discounted total.

    Returns ``(clamped_points_redeemed_minor, combined_discount_minor,
    final_total_minor)``. Call this AFTER apply_manual_discount, passing its
    combined_discount_minor and final_total_minor through — clamping against
    what's left post-discount means points can never push the bill negative
    on top of an existing discount.
    """
    clamped = min(max(0, points_redeemed_minor), max(0, remaining_total_minor))
    return (
        clamped,
        discount_so_far_minor + clamped,
        remaining_total_minor - clamped,
    )


def _unit_inclusive_minor(line_inclusive_minor: int, qty: int) -> int:
    if qty <= 0:
        return 0
    return int((Decimal(line_inclusive_minor) / Decimal(qty)).quantize(
        Decimal("1"), rounding=ROUND_HALF_UP
    ))


async def resolve_menu_customizations(
    session: AsyncSession,
    *,
    company_id: UUID,
    line_requests: list[LineRequest],
) -> list[ResolvedLineCustomization]:
    """Resolve client-selected IDs into immutable, server-priced snapshots.

    The client never supplies names or price deltas. Active group bounds are
    checked even when a request selects nothing, so an older client cannot
    bypass a newly required choice. The returned list preserves request order
    and can be written directly to the corresponding OrderLine snapshot.
    """
    if not line_requests:
        return []

    item_ids = list({line.menu_item_id for line in line_requests})
    variant_ids = list(
        {line.variant_id for line in line_requests if line.variant_id is not None}
    )
    modifier_ids = list(
        {
            selection.modifier_id
            for line in line_requests
            for selection in line.modifiers
        }
    )

    variants: list[MenuVariant] = []
    if variant_ids:
        variants = list(
            (
                await session.execute(
                    select(MenuVariant).where(
                        MenuVariant.company_id == company_id,
                        MenuVariant.id.in_(variant_ids),
                        MenuVariant.is_active.is_(True),
                    )
                )
            ).scalars().all()
        )
    groups = list(
        (
            await session.execute(
                select(MenuModifierGroup).where(
                    MenuModifierGroup.company_id == company_id,
                    MenuModifierGroup.menu_item_id.in_(item_ids),
                    MenuModifierGroup.is_active.is_(True),
                )
            )
        ).scalars().all()
    )
    options: list[MenuModifier] = []
    if modifier_ids:
        options = list(
            (
                await session.execute(
                    select(MenuModifier).where(
                        MenuModifier.company_id == company_id,
                        MenuModifier.id.in_(modifier_ids),
                        MenuModifier.is_active.is_(True),
                    )
                )
            ).scalars().all()
        )

    variants_by_id = {variant.id: variant for variant in variants}
    groups_by_id = {group.id: group for group in groups}
    groups_by_item: dict[UUID, list[MenuModifierGroup]] = {}
    for group in groups:
        groups_by_item.setdefault(group.menu_item_id, []).append(group)
    options_by_id = {option.id: option for option in options}

    resolved_lines: list[ResolvedLineCustomization] = []
    for line in line_requests:
        if line.qty <= 0:
            # Keep the same public validation rule as price_order. Resolving a
            # negative line first would create misleading snapshot totals.
            raise BusinessRuleError("line quantity must be positive")

        variant_snapshot = None
        unit_delta_minor = 0
        if line.variant_id is not None:
            variant = variants_by_id.get(line.variant_id)
            if variant is None or variant.menu_item_id != line.menu_item_id:
                raise BusinessRuleError(
                    "selected variant is not active for this menu item"
                )
            variant_snapshot = PricedVariantSnapshot(
                id=variant.id,
                name=variant.name,
                price_delta_minor=variant.price_delta_minor,
                line_delta_minor=variant.price_delta_minor * line.qty,
            )
            unit_delta_minor += variant.price_delta_minor

        selection_ids: set[UUID] = set()
        group_selection_counts: dict[UUID, int] = {}
        modifier_snapshots: list[PricedModifierSnapshot] = []
        for selection in line.modifiers:
            if selection.modifier_id in selection_ids:
                raise BusinessRuleError(
                    "a modifier option may appear only once per order line"
                )
            selection_ids.add(selection.modifier_id)
            if (
                isinstance(selection.quantity, bool)
                or not isinstance(selection.quantity, int)
                or selection.quantity <= 0
            ):
                raise BusinessRuleError("modifier quantity must be a positive integer")

            option = options_by_id.get(selection.modifier_id)
            if option is None or option.menu_item_id != line.menu_item_id:
                raise BusinessRuleError(
                    "selected modifier is not active for this menu item"
                )
            selected_group = groups_by_id.get(option.modifier_group_id)
            if (
                selected_group is None
                or selected_group.menu_item_id != line.menu_item_id
            ):
                raise BusinessRuleError(
                    "selected modifier group is not active for this menu item"
                )
            if selection.quantity > option.max_quantity:
                raise BusinessRuleError(
                    f"modifier '{option.name}' allows at most {option.max_quantity} per item"
                )

            group_selection_counts[selected_group.id] = (
                group_selection_counts.get(selected_group.id, 0) + selection.quantity
            )
            per_item_delta = option.price_delta_minor * selection.quantity
            line_delta = per_item_delta * line.qty
            unit_delta_minor += per_item_delta
            modifier_snapshots.append(
                PricedModifierSnapshot(
                    id=option.id,
                    modifier_group_id=selected_group.id,
                    group_name=selected_group.name,
                    name=option.name,
                    quantity_per_item=selection.quantity,
                    unit_price_delta_minor=option.price_delta_minor,
                    per_item_delta_minor=per_item_delta,
                    line_delta_minor=line_delta,
                )
            )

        for group in groups_by_item.get(line.menu_item_id, []):
            selected_count = group_selection_counts.get(group.id, 0)
            if selected_count < group.min_select:
                raise BusinessRuleError(
                    f"modifier group '{group.name}' requires at least "
                    f"{group.min_select} selection(s)"
                )
            if selected_count > group.max_select:
                raise BusinessRuleError(
                    f"modifier group '{group.name}' allows at most "
                    f"{group.max_select} selection(s)"
                )

        resolved_lines.append(
            ResolvedLineCustomization(
                variant=variant_snapshot,
                modifiers=tuple(modifier_snapshots),
                unit_delta_minor=unit_delta_minor,
            )
        )

    return resolved_lines


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
        if not branch or branch.company_id != company_id or branch.deleted_at:
            raise NotFoundError("branch not found")

        branch_state = (branch.state_code or "").strip()
        supplier_gstin = branch.branch_gstin or company.gstin
        tax_mode = _billing_tax_mode(
            registration_type=company.gst_registration_type,
            is_composition=bool(company.is_composition),
            supplier_gstin=supplier_gstin,
            branch_state=branch_state,
        )
        is_composition = tax_mode == "composition"
        is_unregistered = tax_mode == "unregistered"
        is_aggregator = delivery_via and delivery_via.lower() not in {"inhouse", ""}
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
        resolved_customizations = await resolve_menu_customizations(
            self.session,
            company_id=company_id,
            line_requests=line_requests,
        )

        priced_lines: list[PricedLine] = []
        sub_taxable = sub_cgst = sub_sgst = sub_igst = sub_cess = sub_inclusive = sub_discount = 0

        for lr, customization in zip(
            line_requests,
            resolved_customizations,
            strict=True,
        ):
            item = items_by_id[lr.menu_item_id]
            if not item.is_available:
                raise BusinessRuleError(f"menu item {item.sku} is not available")
            if lr.qty <= 0:
                raise BusinessRuleError(f"qty must be positive for {item.sku}")

            unit_catalog_price = item.base_price_minor + customization.unit_delta_minor
            if unit_catalog_price < 0:
                raise BusinessRuleError(
                    f"selected customizations make menu item {item.sku} negative"
                )
            line_base = unit_catalog_price * lr.qty
            line_discount = _discount_minor(
                line_base,
                _discount_for_item_type(item.type, membership_rates),
            )
            line_net_base = line_base - line_discount
            rate = Decimal(str(item.tax_rate or 0))

            # Composition, unregistered suppliers and aggregators do not collect
            # line-level GST on this customer document.
            if is_composition or is_unregistered or is_aggregator:
                taxable, cgst, sgst, igst = (line_net_base, 0, 0, 0)
                line_inclusive = line_net_base
            elif bool(item.price_includes_tax):
                line_inclusive = line_net_base
                taxable, cgst, sgst, igst = _split_tax_from_inclusive(
                    line_inclusive, rate, intra_state
                )
            else:
                taxable, cgst, sgst, igst = _split_tax_from_exclusive(
                    line_net_base, rate, intra_state
                )
                line_inclusive = taxable + cgst + sgst + igst

            priced_lines.append(
                PricedLine(
                    menu_item_id=item.id,
                    name=item.name,
                    item_type=item.type,
                    sku=item.sku,
                    hsn_or_sac=item.hsn_code or "",
                    qty=lr.qty,
                    unit_inclusive_minor=_unit_inclusive_minor(line_inclusive, lr.qty),
                    line_inclusive_minor=line_inclusive,
                    discount_minor=line_discount,
                    taxable_value_minor=taxable,
                    tax_rate=rate,
                    cgst_minor=cgst,
                    sgst_minor=sgst,
                    igst_minor=igst,
                    cess_minor=0,
                    base_unit_price_minor=item.base_price_minor,
                    customization_unit_delta_minor=customization.unit_delta_minor,
                    variant_snapshot=customization.variant,
                    modifier_snapshots=customization.modifiers,
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

    async def price_time_based_line(
        self,
        *,
        company_id: UUID,
        branch_id: UUID,
        amount_minor: int,
        tax_rate: Decimal,
        rate_includes_tax: bool,
        customer_phone: str | None = None,
        item_type: str | None = None,
        place_of_supply_state_code: str | None = None,
        delivery_via: str | None = None,
        allowance_minor: int = 0,
    ) -> TimeBasedPricing:
        """Discount and GST-split one stored gross line amount.

        ``amount_minor`` is the pre-membership amount in the item's configured
        price mode (GST-inclusive when ``rate_includes_tax`` is true, otherwise
        GST-exclusive). This supports both session billing and deterministic
        repricing of a held order when the cashier attaches a member at POS.
        """
        company = await self.session.get(Company, company_id)
        if not company:
            raise NotFoundError("company not found")
        branch = await self.session.get(Branch, branch_id)
        if not branch or branch.company_id != company_id or branch.deleted_at:
            raise NotFoundError("branch not found")

        branch_state = (branch.state_code or "").strip()
        supplier_gstin = branch.branch_gstin or company.gstin
        tax_mode = _billing_tax_mode(
            registration_type=company.gst_registration_type,
            is_composition=bool(company.is_composition),
            supplier_gstin=supplier_gstin,
            branch_state=branch_state,
        )

        membership_rates = await self._membership_discount_rates(
            company_id=company_id,
            customer_phone=customer_phone,
        )
        allowance_discount_minor = min(
            max(0, int(amount_minor)),
            max(0, int(allowance_minor)),
        )
        amount_after_allowance = max(0, amount_minor - allowance_discount_minor)
        percentage_discount_minor = _discount_minor(
            amount_after_allowance,
            _discount_for_item_type(item_type or "", membership_rates),
        )
        discount_minor = allowance_discount_minor + percentage_discount_minor
        net_amount_minor = max(0, amount_minor - discount_minor)
        is_aggregator = delivery_via and delivery_via.lower() not in {"inhouse", ""}
        intra_state = (place_of_supply_state_code or branch_state) == branch_state

        if tax_mode in ("composition", "unregistered") or is_aggregator:
            return TimeBasedPricing(
                total_minor=net_amount_minor,
                discount_minor=discount_minor,
                taxable_minor=net_amount_minor,
                cgst_minor=0, sgst_minor=0, igst_minor=0,
                allowance_discount_minor=allowance_discount_minor,
            )
        if rate_includes_tax:
            taxable, cgst, sgst, igst = _split_tax_from_inclusive(
                net_amount_minor, tax_rate, intra_state
            )
            return TimeBasedPricing(
                total_minor=net_amount_minor,
                discount_minor=discount_minor,
                taxable_minor=taxable,
                cgst_minor=cgst, sgst_minor=sgst, igst_minor=igst,
                allowance_discount_minor=allowance_discount_minor,
            )
        taxable, cgst, sgst, igst = _split_tax_from_exclusive(
            net_amount_minor, tax_rate, intra_state
        )
        return TimeBasedPricing(
            total_minor=taxable + cgst + sgst + igst,
            discount_minor=discount_minor,
            taxable_minor=taxable,
            cgst_minor=cgst, sgst_minor=sgst, igst_minor=igst,
            allowance_discount_minor=allowance_discount_minor,
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
                    CustomerMembership.starts_at <= now,
                    CustomerMembership.expires_at > now,
                    CustomerMembership.revoked_at.is_(None),
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


def _invoice_component(value: str, *, fallback: str, length: int) -> str:
    """Return a stable uppercase alphanumeric invoice component."""
    cleaned = "".join(char for char in value.upper() if char.isalnum())
    return (cleaned or fallback)[:length]


def format_invoice_number(
    *, prefix: str, branch_code: str, fiscal_year: str, sequence: int
) -> str:
    """Build a Rule 46 serial number that always fits the 16-character limit."""
    safe_prefix = _invoice_component(prefix, fallback="D", length=1)
    safe_branch = _invoice_component(branch_code, fallback="MN", length=2)
    fiscal_year_short = fiscal_year[-5:]
    if sequence < 1 or sequence > 99_999:
        raise BusinessRuleError("invoice sequence is outside the supported range")
    return f"{safe_prefix}/{safe_branch}/{fiscal_year_short}/{sequence:05d}"


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
        company_id: UUID,
        branch_id: UUID,
        prefix: str = "D",
        series: str = "invoice",
        at: datetime | None = None,
        timezone_name: str = "Asia/Kolkata",
    ) -> tuple[str, str]:
        """Return (invoice_no, fiscal_year)."""
        # Lock the branch identity before reading its explicit fiscal series.
        # Settings takes the same lock before a series edit, closing the race
        # where the first invoice and a configuration change could otherwise
        # commit under different assumptions.
        branch = (
            await self.session.execute(
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
            raise BusinessRuleError(
                "cannot issue a fiscal document because the branch identity is invalid"
            )
        invoice_series_code = (branch.invoice_series_code or "").strip().upper()
        if re.fullmatch(r"[A-Z0-9]{2}", invoice_series_code) is None:
            raise BusinessRuleError(
                "branch invoice series is missing or invalid; set a unique two-character "
                "invoice series in Settings before billing"
            )

        now = at or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        fy = fiscal_year_for(now.astimezone(ZoneInfo(timezone_name)).date())

        # One atomic upsert handles both the first invoice and concurrent
        # allocations on different orders. SELECT-then-INSERT has a race when
        # the counter row does not exist yet.
        seq = int(
            (
                await self.session.execute(
                    pg_insert(InvoiceCounter)
                    .values(
                        branch_id=branch_id,
                        fiscal_year=fy,
                        series=series,
                        last_seq=1,
                    )
                    .on_conflict_do_update(
                        constraint="uq_inv_counter_branch_fy_series",
                        set_={"last_seq": InvoiceCounter.last_seq + 1},
                    )
                    .returning(InvoiceCounter.last_seq)
                )
            ).scalar_one()
        )

        invoice_no = format_invoice_number(
            prefix=prefix,
            branch_code=invoice_series_code,
            fiscal_year=fy,
            sequence=seq,
        )
        return invoice_no, fy

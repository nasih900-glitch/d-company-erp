"""Financial helpers for retaining consumed add-ons during a Gaming Void.

Routes own authorization and lock ordering.  These helpers accept only locked,
validated rows and never query current catalogue pricing for an add-on.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING
from uuid import uuid4

if TYPE_CHECKING:
    from datetime import datetime
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessRuleError
from app.models import GamingSession, GamingSessionAddon, Order, OrderLine, Shift
from app.services.pos.pricing import (
    ImmutableLineSnapshot,
    aggregate_immutable_line_snapshots,
)


def immutable_addon_snapshot(addon: GamingSessionAddon) -> ImmutableLineSnapshot:
    return ImmutableLineSnapshot(
        line_total_minor=int(addon.line_total_minor),
        discount_minor=int(addon.discount_minor),
        taxable_value_minor=int(addon.taxable_value_minor),
        cgst_minor=int(addon.cgst_minor),
        sgst_minor=int(addon.sgst_minor),
        igst_minor=int(addon.igst_minor),
        cess_minor=int(addon.cess_minor),
    )


@dataclass(frozen=True, slots=True)
class ImmutableGamingChargeSnapshot:
    unit_price_minor: int
    line_total_minor: int
    discount_minor: int
    taxable_value_minor: int
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    cess_minor: int


def immutable_gaming_charge_snapshot(
    line: OrderLine,
    *,
    original_session_amount_minor: int,
) -> ImmutableGamingChargeSnapshot:
    """Validate and freeze the reviewed POS charge without repricing it.

    ``GamingSession.amount_minor`` is the pre-membership amount.  A legitimate
    percentage membership discount lowers the POS line while leaving that
    historical session amount intact, so equality between the two is not a
    valid integrity check.  The checkout version and reviewed order total bind
    the bill; this snapshot binds the exact discounted/taxed line that was
    reviewed and later voided.
    """

    snapshot = ImmutableGamingChargeSnapshot(
        unit_price_minor=int(line.unit_price_minor),
        line_total_minor=int(line.line_total_minor),
        discount_minor=int(line.discount_minor or 0),
        taxable_value_minor=int(line.taxable_value_minor or 0),
        cgst_minor=int(line.cgst_minor or 0),
        sgst_minor=int(line.sgst_minor or 0),
        igst_minor=int(line.igst_minor or 0),
        cess_minor=int(line.cess_minor or 0),
    )
    financial_values = (
        snapshot.unit_price_minor,
        snapshot.line_total_minor,
        snapshot.discount_minor,
        snapshot.taxable_value_minor,
        snapshot.cgst_minor,
        snapshot.sgst_minor,
        snapshot.igst_minor,
        snapshot.cess_minor,
    )
    minimum_discounted_base = (
        int(original_session_amount_minor) - snapshot.discount_minor
    )
    if (
        int(original_session_amount_minor) < 0
        or any(value < 0 for value in financial_values)
        or snapshot.discount_minor > int(original_session_amount_minor)
        or snapshot.unit_price_minor != snapshot.line_total_minor
        or snapshot.line_total_minor < minimum_discounted_base
        or snapshot.line_total_minor
        != snapshot.taxable_value_minor
        + snapshot.cgst_minor
        + snapshot.sgst_minor
        + snapshot.igst_minor
        + snapshot.cess_minor
    ):
        raise BusinessRuleError(
            "The reviewed Gaming charge has inconsistent immutable pricing. "
            "Nothing was voided."
        )
    return snapshot


def require_exact_reviewed_order_aggregate(
    *,
    order: Order,
    active_lines: list[OrderLine],
) -> None:
    """Prove that the locked reviewed bill is exactly its active line cohort."""

    if not active_lines:
        raise BusinessRuleError(
            "The reviewed Gaming bill has no active financial lines. Nothing was voided."
        )
    expected = {
        "subtotal_minor": sum(int(line.taxable_value_minor or 0) for line in active_lines),
        "cgst_minor": sum(int(line.cgst_minor or 0) for line in active_lines),
        "sgst_minor": sum(int(line.sgst_minor or 0) for line in active_lines),
        "igst_minor": sum(int(line.igst_minor or 0) for line in active_lines),
        "cess_minor": sum(int(line.cess_minor or 0) for line in active_lines),
        "discount_minor": sum(int(line.discount_minor or 0) for line in active_lines),
    }
    expected["tax_minor"] = (
        expected["cgst_minor"]
        + expected["sgst_minor"]
        + expected["igst_minor"]
        + expected["cess_minor"]
    )
    stored = {
        name: int(getattr(order, name) or 0)
        for name in expected
    }
    active_total = sum(int(line.line_total_minor or 0) for line in active_lines)
    if (
        stored != expected
        or active_total + int(order.round_off_minor or 0) != int(order.total_minor or 0)
    ):
        raise BusinessRuleError(
            "The reviewed Gaming bill totals no longer match its immutable line cohort. "
            "Refresh POS; nothing was voided."
        )


def require_exact_addon_line_mapping(
    *,
    addons: list[GamingSessionAddon],
    active_lines: list[OrderLine],
    expected_charge_item_type: str,
    original_session_amount_minor: int,
) -> tuple[
    OrderLine,
    list[tuple[GamingSessionAddon, OrderLine]],
    ImmutableGamingChargeSnapshot,
]:
    """Return the sole Gaming charge and the exact add-on line bijection."""

    by_client_line: dict[UUID, OrderLine] = {}
    for line in active_lines:
        client_line_id = getattr(line, "client_line_id", None)
        if client_line_id is None:
            continue
        if client_line_id in by_client_line:
            raise BusinessRuleError(
                "The held Gaming bill contains duplicate add-on line identity. Nothing was voided."
            )
        by_client_line[client_line_id] = line

    mapped: list[tuple[GamingSessionAddon, OrderLine]] = []
    mapped_line_ids: set[UUID] = set()
    for addon in addons:
        line = by_client_line.get(addon.client_line_id)
        if line is None or line.menu_item_id != addon.menu_item_id:
            raise BusinessRuleError(
                "The held Gaming bill no longer matches its immutable add-on ledger. "
                "Nothing was voided."
            )
        exact = (
            int(line.qty) == int(addon.qty)
            and int(line.unit_price_minor) == int(addon.unit_price_minor)
            and int(line.line_total_minor) == int(addon.line_total_minor)
            and int(line.discount_minor or 0) == int(addon.discount_minor)
            and int(line.taxable_value_minor or 0) == int(addon.taxable_value_minor)
            and int(line.cgst_minor or 0) == int(addon.cgst_minor)
            and int(line.sgst_minor or 0) == int(addon.sgst_minor)
            and int(line.igst_minor or 0) == int(addon.igst_minor)
            and int(line.cess_minor or 0) == int(addon.cess_minor)
            and line.hsn_or_sac == addon.hsn_or_sac
            and line.tax_rate == addon.tax_rate
            and line.menu_item_name_snapshot == addon.menu_item_name_snapshot
            and line.menu_item_type_snapshot == addon.menu_item_type_snapshot
            and line.variant_id == addon.variant_id
            and line.variant_snapshot == addon.variant_snapshot
            and line.modifiers == addon.modifiers
            and line.note == addon.note
            and line.kitchen_status == "served"
            and line.kitchen_released_at == addon.created_at
            and line.kitchen_served_at == addon.created_at
            and line.created_at == addon.created_at
        )
        if not exact or line.id in mapped_line_ids:
            raise BusinessRuleError(
                "The held Gaming add-on line changed after review. Nothing was voided."
            )
        mapped_line_ids.add(line.id)
        mapped.append((addon, line))

    charge_lines = [line for line in active_lines if line.id not in mapped_line_ids]
    if len(charge_lines) != 1 or len(active_lines) != len(addons) + 1:
        raise BusinessRuleError(
            "The held bill must contain exactly one Gaming charge plus its immutable add-ons. "
            "Nothing was voided."
        )
    charge_line = charge_lines[0]
    if (
        charge_line.client_line_id is not None
        or int(charge_line.qty) != 1
        or charge_line.menu_item_type_snapshot != expected_charge_item_type
    ):
        raise BusinessRuleError(
            "The reviewed bill does not contain the exact Gaming charge snapshot. "
            "Nothing was voided."
        )
    snapshot = immutable_gaming_charge_snapshot(
        charge_line,
        original_session_amount_minor=original_session_amount_minor,
    )
    return charge_line, mapped, snapshot


async def create_retained_addon_order(
    db: AsyncSession,
    *,
    gaming_session: GamingSession,
    target_shift: Shift,
    addons: list[GamingSessionAddon],
    actor_user_id: UUID,
    at: datetime,
) -> tuple[Order, list[tuple[GamingSessionAddon, OrderLine]]]:
    """Create an add-on-only held bill from immutable consumption snapshots."""

    totals = aggregate_immutable_line_snapshots(
        [immutable_addon_snapshot(addon) for addon in addons]
    )
    order = Order(
        id=uuid4(),
        company_id=gaming_session.company_id,
        branch_id=target_shift.branch_id,
        terminal_id=target_shift.terminal_id,
        shift_id=target_shift.id,
        opened_by=actor_user_id,
        table_id=None,
        type="session",
        status="held",
        opened_at=at,
        held_at=at,
        subtotal_minor=totals.subtotal_minor,
        discount_minor=totals.discount_minor,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        cgst_minor=totals.cgst_minor,
        sgst_minor=totals.sgst_minor,
        igst_minor=totals.igst_minor,
        cess_minor=totals.cess_minor,
        tax_minor=totals.tax_minor,
        round_off_minor=totals.round_off_minor,
        tip_minor=0,
        total_minor=totals.total_minor,
        customer_name=gaming_session.customer_name,
        customer_phone=gaming_session.customer_phone,
        customer_id=gaming_session.customer_id,
        customer_directory_revision=gaming_session.customer_directory_revision,
        notes="Consumed Gaming add-ons retained after the Gaming charge was voided",
    )
    db.add(order)
    await db.flush()

    # The 0093 snapshot trigger resolves immutable add-on names through this
    # link.  Set it before inserting lines, and never recycle it for another bill.
    gaming_session.retained_addon_order_id = order.id
    await db.flush()

    mappings: list[tuple[GamingSessionAddon, OrderLine]] = []
    for addon in addons:
        line = OrderLine(
            id=uuid4(),
            order_id=order.id,
            client_line_id=addon.client_line_id,
            menu_item_id=addon.menu_item_id,
            menu_item_name_snapshot=addon.menu_item_name_snapshot,
            menu_item_type_snapshot=addon.menu_item_type_snapshot,
            variant_id=addon.variant_id,
            variant_snapshot=addon.variant_snapshot,
            modifiers=addon.modifiers,
            qty=addon.qty,
            unit_price_minor=addon.unit_price_minor,
            line_total_minor=addon.line_total_minor,
            discount_minor=addon.discount_minor,
            hsn_or_sac=addon.hsn_or_sac,
            tax_rate=addon.tax_rate,
            taxable_value_minor=addon.taxable_value_minor,
            cgst_minor=addon.cgst_minor,
            sgst_minor=addon.sgst_minor,
            igst_minor=addon.igst_minor,
            cess_minor=addon.cess_minor,
            note=addon.note,
            kitchen_status="served",
            kitchen_released_at=addon.created_at,
            kitchen_round_no=1,
            kitchen_served_at=addon.created_at,
            created_at=addon.created_at,
        )
        db.add(line)
        mappings.append((addon, line))
    await db.flush()
    return order, mappings

"""Read-only, exact-source review for a whole unpaid-in-reality POS invoice."""
# Pydantic resolves UUID annotations when constructing the strict request schema.
# ruff: noqa: TC003

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator
from sqlalchemy import select, text

from app.core.errors import BusinessRuleError, ConflictError, IdempotencyConflict, NotFoundError
from app.models import Order, Payment, PosReceiptCorrection, Shift

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.core.tenant import TenantContext

CORRECTION_KIND = "payment_not_received_full_mistake"


class ReceiptCorrectionCommit(BaseModel):
    model_config = ConfigDict(extra="forbid")
    order_id: UUID
    source_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    no_funds_received: StrictBool
    all_items_not_supplied: StrictBool
    acknowledges_no_payout: StrictBool
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def nonblank_reason(cls, value: str) -> str:
        value = value.strip()
        if len(value) < 3:
            raise ValueError("reason must contain at least three nonblank characters")
        return value

    @field_validator("no_funds_received", "all_items_not_supplied", "acknowledges_no_payout")
    @classmethod
    def true_attestation(cls, value: bool) -> bool:
        if value is not True:
            raise ValueError("each no-value attestation must be confirmed")
        return value


class ReceiptStockCorrectionCommit(ReceiptCorrectionCommit):
    restore_stock: Literal[True]
    stock_never_consumed: StrictBool
    stock_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")

    @field_validator("restore_stock", "stock_never_consumed", mode="before")
    @classmethod
    def explicit_stock_consent(cls, value):
        if value is not True:
            raise ValueError("stock restoration must be explicitly confirmed")
        return value


# One statement takes a single MVCC snapshot. No preview row, lease, audit
# event, invoice update, or financial reservation is created.
_SOURCE_SQL = text("""
    WITH captured AS (
        SELECT dcompany_pos_receipt_source(:order_id,:company_id) AS source
    ) SELECT source,encode(sha256(convert_to(source::text,'UTF8')),'hex') AS fingerprint
    FROM captured
""")


@dataclass(frozen=True, slots=True)
class CorrectionReview:
    eligible: bool
    blockers: tuple[str, ...]
    source_fingerprint: str
    amount_minor: int


@dataclass(frozen=True, slots=True)
class ReceiptSource:
    snapshot: dict
    fingerprint: str


def source_fingerprint(source: dict) -> str:
    return hashlib.sha256(
        json.dumps(
            source,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
            allow_nan=False,
        ).encode("utf-8")
    ).hexdigest()


def _plain_line(line: dict, *, retained_materialization: bool = False) -> bool:
    if line.get("menu_item_type_snapshot") not in {"food", "drink", "dessert"}:
        return False
    if not str(line.get("menu_item_name_snapshot") or "").strip():
        return False
    if line.get("voided_at") is not None:
        return False
    if not retained_materialization and any(
        line.get(k) is not None for k in ("kitchen_released_at", "kitchen_served_at")
    ):
        return False
    if any(
        line.get(k) not in (None, 0)
        for k in (
            "discount_minor",
            "cgst_minor",
            "sgst_minor",
            "igst_minor",
            "cess_minor",
        )
    ):
        return False
    if line.get("variant_snapshot") is not None or line.get("modifiers") not in (None, []):
        return False
    try:
        qty = Decimal(str(line["qty"]))
        # Pricing retains the configured rate when GST is not collected.
        # Only immutable charged components and exact line math prove zero tax.
        rate = Decimal(str(line.get("tax_rate") or 0))
        price, total = line["unit_price_minor"], line["line_total_minor"]
        return (
            type(price) is int
            and price > 0
            and type(total) is int
            and total > 0
            and rate.is_finite()
            and 0 <= rate <= 1
            and qty.is_finite()
            and qty > 0
            and qty * price == total
            and line.get("taxable_value_minor") == total
        )
    except (KeyError, InvalidOperation, TypeError, ValueError):
        return False


def _cancelled_retained_provenance(source: dict) -> bool:
    order, lines = source["order"], source["lines"]
    sessions, voids, links = (
        source[key]
        for key in (
            "gaming_sessions",
            "gaming_voids",
            "gaming_links",
        )
    )
    if len(sessions) != 1 or len(voids) != 1 or len(links) != len(lines):
        return False
    session, void = sessions[0], voids[0]
    stations = source["gaming_stations"]
    if not (
        order["type"] == "session"
        and void["mode"] == "unlinked_addons"
        and void["company_id"] == order["company_id"]
        and void["retained_order_id"] == order["id"]
        and void.get("source_order_id") is None
        and void["gaming_session_id"] == session["id"]
        and session["company_id"] == order["company_id"]
        and session["status"] == "cancelled"
        and session.get("order_id") is None
        and session["retained_addon_order_id"] == order["id"]
        and session["amount_minor"] == 0
        and session["billable_minutes"] == 0
        and session.get("cancelled_at") == void.get("voided_at")
        and session.get("cancelled_by") == void.get("actor_user_id")
        and session.get("cancel_reason") == void.get("reason")
        and len(stations) == 1
        and stations[0]["id"] == session["station_id"]
        and stations[0]["company_id"] == order["company_id"]
        and stations[0]["branch_id"] == order["branch_id"]
        and void["retained_total_minor"] == order["total_minor"]
        and void["addon_count"] == len(lines)
    ):
        return False
    by_id = {line["id"]: line for line in lines}
    seen: set[str] = set()
    active_addons = {a["id"] for a in source["gaming_addons"] if a.get("voided_at") is None}
    mapped_addons: set[str] = set()
    for entry in links:
        link, addon = entry["link"], entry["addon"]
        line_id = link["order_line_id"]
        if line_id not in by_id or line_id in seen:
            return False
        seen.add(line_id)
        mapped_addons.add(addon["id"])
        if not (
            link["receipt_id"] == void["id"]
            and link["retained_order_id"] == order["id"]
            and link.get("source_order_id") is None
            and link["addon_id"] == addon["id"]
            and addon["company_id"] == order["company_id"]
            and addon["gaming_session_id"] == session["id"]
            and addon.get("voided_at") is None
        ):
            return False
        if any(
            str(addon.get(k)) != str(by_id[line_id].get(k))
            for k in (
                "menu_item_id",
                "client_line_id",
                "menu_item_name_snapshot",
                "menu_item_type_snapshot",
                "unit_price_minor",
                "line_total_minor",
                "discount_minor",
                "taxable_value_minor",
                "cgst_minor",
                "sgst_minor",
                "igst_minor",
                "cess_minor",
                "variant_snapshot",
                "modifiers",
                "variant_id",
                "hsn_or_sac",
                "tax_rate",
                "note",
            )
        ):
            return False
        try:
            if Decimal(str(addon["qty"])) != Decimal(str(by_id[line_id]["qty"])):
                return False
        except (KeyError, InvalidOperation, TypeError, ValueError):
            return False
        line = by_id[line_id]
        # create_retained_addon_order constructs these fields from immutable
        # add-time facts; unequal times remain unsupported delivery evidence.
        if not (
            addon.get("created_at") is not None
            and line.get("created_at") == addon["created_at"]
            and line.get("kitchen_released_at") == addon["created_at"]
            and line.get("kitchen_served_at") == addon["created_at"]
            and line.get("kitchen_status") == "served"
            and line.get("kitchen_round_no") == 1
        ):
            return False
    return len(seen) == len(lines) and mapped_addons == active_addons


def review_source(source: dict, *, fingerprint: str | None = None) -> CorrectionReview:
    """Fail closed on unsupported effects; never infer that money was received."""
    order, shift, lines, payments = (
        source[key]
        for key in (
            "order",
            "shift",
            "lines",
            "payments",
        )
    )
    blockers: list[str] = []
    total = order["total_minor"]
    if not (
        order["status"] == "paid"
        and order.get("invoice_no")
        and order.get("invoice_issued_at")
        and order.get("fiscal_year")
        and order.get("closed_at")
        and order.get("source_integrity_revision") == 48
        and type(total) is int
        and total > 0
    ):
        blockers.append("requires_complete_issued_paid_invoice")
    if not (
        shift["id"] == order["shift_id"]
        and shift["company_id"] == order["company_id"]
        and shift["branch_id"] == order["branch_id"]
        and shift["terminal_id"] == order["terminal_id"]
        and shift["status"] == "closed"
        and shift.get("closed_at")
        and source["branch"]["company_id"] == order["company_id"]
        and source["branch"].get("deleted_at") is None
        and source["terminal"]["branch_id"] == order["branch_id"]
    ):
        blockers.append("requires_matching_closed_original_shift")
    if len(payments) != 1 or not (
        payments[0]["method"] == "upi"
        and payments[0]["amount_minor"] == total
        and payments[0]["order_id"] == order["id"]
        and payments[0]["shift_id"] == shift["id"]
        and payments[0].get("source_integrity_revision") == 48
        and payments[0].get("ref_external") in (None, "")
        and payments[0].get("tendered_minor") is None
        and payments[0].get("change_minor") is None
    ):
        blockers.append("requires_single_full_manual_upi_payment")
    if any(
        order.get(k) not in (None, 0)
        for k in (
            "tax_minor",
            "cgst_minor",
            "sgst_minor",
            "igst_minor",
            "cess_minor",
            "tip_minor",
            "round_off_minor",
            "discount_minor",
            "manual_discount_minor",
            "points_redeemed_minor",
        )
    ) or any(order.get(k) for k in ("irn", "irn_ack_no", "irn_acknowledged_at", "e_invoice_qr")):
        blockers.append("unsupported_tax_tip_rounding_discount_or_einvoice")
    if (
        order.get("customer_id") is not None
        or order.get("customer_phone")
        or any(
            source[key]
            for key in ("benefits", "points_redemptions", "loyalty_settlements", "rank_awards")
        )
    ):
        blockers.append("unsupported_customer_or_loyalty_effects")
    retained_materialization = _cancelled_retained_provenance(source)
    if (
        not lines
        or not all(
            _plain_line(line, retained_materialization=retained_materialization) for line in lines
        )
        or (
            sum(line["line_total_minor"] for line in lines) != total
            or order.get("subtotal_minor") != total
        )
    ):
        blockers.append("requires_plain_unsupplied_product_lines")
    for key, message in (
        ("refunds", "recorded_refund"),
        ("refund_requests", "recorded_refund_request"),
        ("tender_corrections", "existing_tender_correction"),
        ("corrections", "already_corrected"),
        ("active_claims", "active_checkout_claim"),
        ("stock_movements", "recorded_stock_or_cogs_movement"),
    ):
        if source[key]:
            blockers.append(message)
    gaming_history = any(
        source[key]
        for key in (
            "gaming_sessions",
            "gaming_voids",
            "gaming_links",
        )
    )
    if (gaming_history or order["type"] == "session") and not retained_materialization:
        blockers.append("unsupported_gaming_value_or_provenance")
    return CorrectionReview(
        not blockers,
        tuple(blockers),
        fingerprint or source_fingerprint(source),
        total,
    )


async def load_review_source(
    session: AsyncSession,
    tenant: TenantContext,
    order_id: UUID,
) -> ReceiptSource:
    row = (
        await session.execute(
            _SOURCE_SQL,
            {
                "order_id": order_id,
                "company_id": tenant.company_id,
            },
        )
    ).one()
    source = row.source
    if source is None or (
        tenant.branch_id is not None and str(tenant.branch_id) != source["order"]["branch_id"]
    ):
        raise NotFoundError("receipt not found")
    if tenant.terminal_id is not None and str(tenant.terminal_id) != source["order"]["terminal_id"]:
        raise NotFoundError("receipt not found")
    return ReceiptSource(source, row.fingerprint)


async def _request_hash(session: AsyncSession, payload: dict) -> str:
    return await session.scalar(
        text("SELECT encode(sha256(convert_to(CAST(:payload AS jsonb)::text,'UTF8')),'hex')"),
        {"payload": json.dumps(payload, sort_keys=True, separators=(",", ":"))},
    )


async def lock_receipt_company(session: AsyncSession, company_id: UUID) -> None:
    """Match configuration writers before locking receipt workspace parents."""
    from app.models import Company

    await session.scalar(
        select(Company.id).where(Company.id == company_id).with_for_update(read=True)
    )


async def lock_receipt_workspace(session: AsyncSession, order: Order, company_id: UUID) -> None:
    """Take FK parent locks before the collection fence and inventory."""
    from app.models import Branch, Terminal

    branch = await session.scalar(
        select(Branch.id)
        .where(Branch.id == order.branch_id, Branch.company_id == company_id)
        .with_for_update(read=True, key_share=True)
    )
    if branch is None:
        raise NotFoundError("receipt workspace not found")
    if order.terminal_id is not None:
        terminal = await session.scalar(
            select(Terminal.id)
            .where(Terminal.id == order.terminal_id, Terminal.branch_id == order.branch_id)
            .with_for_update(read=True, key_share=True)
        )
        if terminal is None:
            raise NotFoundError("receipt workspace not found")


async def commit_receipt_correction(
    session: AsyncSession,
    tenant: TenantContext,
    *,
    payload: ReceiptCorrectionCommit,
    idempotency_key: str,
    confirmation_token: str | None = None,
) -> dict:
    """Private transaction primitive. The caller owns commit/rollback; no payout occurs."""
    from uuid import uuid4

    from app.api.v1.finance.tender_corrections import require_protected_owner
    from app.core.permissions import _has_permission
    from app.services.audit.events import record_operation
    from app.services.integrations.google_sheets_mirror import (
        enqueue_google_sheets_event_if_enabled,
    )

    if not await _has_permission(session, tenant, "finance.receipt.correct"):
        from app.core.errors import ForbiddenError

        raise ForbiddenError("Protected Owner receipt correction permission required.")
    await require_protected_owner(session, tenant)
    if (
        not idempotency_key
        or idempotency_key != idempotency_key.strip()
        or len(idempotency_key) > 160
    ):
        raise BusinessRuleError("A nonblank receipt correction action key is required.")
    body = payload.model_dump(mode="json")
    stock_mode = isinstance(payload, ReceiptStockCorrectionCommit)
    path = (
        "/api/v1/pos/receipt-corrections/stock/commit"
        if stock_mode
        else "/api/v1/pos/receipt-corrections/commit"
    )
    digest = await _request_hash(session, body)
    from app.models import User

    # Both correction guards take this actor lock. Start work/attendance take
    # it before workspace rows, so neither correction may defer it past the fence.
    await session.scalar(
        select(User.id)
        .where(User.id == tenant.user_id, User.company_id == tenant.company_id)
        .with_for_update(read=True)
    )
    if stock_mode:
        from app.api.v1.finance.tender_corrections import require_confirmation

        confirmation_order = await session.scalar(
            select(Order).where(Order.id == payload.order_id, Order.company_id == tenant.company_id)
        )
        if (
            confirmation_order is None
            or not tenant.in_branch(confirmation_order.branch_id)
            or (
                tenant.terminal_id is not None
                and tenant.terminal_id != confirmation_order.terminal_id
            )
        ):
            raise NotFoundError("receipt not found")
        await require_confirmation(
            session,
            tenant,
            confirmation_token,
            branch_id=confirmation_order.branch_id,
            scope="receipt_stock",
        )

    async def replay():
        previous = await session.scalar(
            select(PosReceiptCorrection).where(
                PosReceiptCorrection.company_id == tenant.company_id,
                PosReceiptCorrection.idempotency_key == idempotency_key,
            )
        )
        if previous is None:
            return None
        if (
            previous.recorded_by != tenant.user_id
            or previous.request_hash != digest
            or previous.request_path != path
            or previous.request_payload != body
        ):
            raise IdempotencyConflict("This correction action key has different retained evidence.")
        if not tenant.in_branch(previous.branch_id) or (
            tenant.terminal_id is not None and tenant.terminal_id != previous.terminal_id
        ):
            raise NotFoundError("receipt not found")
        return dict(previous.response_payload)

    previous = await replay()
    if previous is not None:
        return previous
    order = await session.scalar(
        select(Order)
        .where(
            Order.id == payload.order_id,
            Order.company_id == tenant.company_id,
        )
        .with_for_update()
        .execution_options(populate_existing=True)
    )
    if (
        order is None
        or not tenant.in_branch(order.branch_id)
        or (tenant.terminal_id is not None and tenant.terminal_id != order.terminal_id)
    ):
        raise NotFoundError("receipt not found")
    await session.scalar(
        select(Shift)
        .where(
            Shift.id == order.shift_id,
            Shift.company_id == tenant.company_id,
        )
        .with_for_update()
        .execution_options(populate_existing=True)
    )
    await session.scalars(
        select(Payment).where(Payment.order_id == order.id).order_by(Payment.id).with_for_update()
    )
    await lock_receipt_company(session, tenant.company_id)
    await lock_receipt_workspace(session, order, tenant.company_id)
    from app.services.finance.tender_corrections import lock_sources

    await lock_sources(session, tenant.company_id)
    if stock_mode:
        await require_confirmation(
            session, tenant, confirmation_token, branch_id=order.branch_id, scope="receipt_stock"
        )
    previous = await replay()
    if previous is not None:
        return previous
    captured = await load_review_source(session, tenant, payload.order_id)
    stock_snapshot = None
    stock_review = None
    if stock_mode:
        from app.services.pos.receipt_stock import (
            load_stock_source,
            lock_stock_source,
            review_stock_source,
        )

        await lock_stock_source(session, captured.snapshot)
        captured = await load_review_source(session, tenant, payload.order_id)
        stock_snapshot, stock_fingerprint = await load_stock_source(
            session, tenant, payload.order_id
        )
        if payload.stock_fingerprint != stock_fingerprint:
            raise ConflictError("Inventory evidence changed; review the exact stock source again.")
        await require_confirmation(
            session, tenant, confirmation_token, branch_id=order.branch_id, scope="receipt_stock"
        )
        stock_review = review_stock_source(stock_snapshot)
        review = review_source(
            {**captured.snapshot, "stock_movements": []}, fingerprint=captured.fingerprint
        )
        if stock_review.blockers:
            raise BusinessRuleError(
                "Stock correction requires reconciled historical evidence.",
                details={"blockers": list(stock_review.blockers)},
            )
    else:
        review = review_source(captured.snapshot, fingerprint=captured.fingerprint)
    if payload.source_fingerprint != review.source_fingerprint:
        raise ConflictError("Receipt evidence changed; review the exact source again.")
    if not review.eligible:
        raise BusinessRuleError(
            "Receipt correction is unsupported for this source.",
            details={"blockers": list(review.blockers)},
        )
    now = (await session.scalar(text("SELECT statement_timestamp()"))).astimezone(UTC)
    company = captured.snapshot["company"]
    zone = await session.scalar(
        text(
            "SELECT coalesce(google_sheets_summary_timezone,timezone) FROM companies WHERE id=:id"
        ),
        {"id": tenant.company_id},
    )
    from zoneinfo import ZoneInfo

    ident = uuid4()
    payment = captured.snapshot["payments"][0]
    result = {
        "id": str(ident),
        "order_id": str(order.id),
        "payment_id": payment["id"],
        "original_shift_id": str(order.shift_id),
        "invoice_no": order.invoice_no,
        "kind": CORRECTION_KIND,
        "amount_minor": review.amount_minor,
        "accounting_at": now.isoformat(),
        "payout_minor": 0,
        "original_records_unchanged": True,
    }
    if stock_mode:
        result.update(
            stock_restored=True,
            restored_movement_count=len(stock_review.movements),
            inventory_increase_minor=stock_review.inventory_increase_minor,
        )
    row = PosReceiptCorrection(
        id=ident,
        company_id=tenant.company_id,
        order_id=order.id,
        payment_id=payment["id"],
        branch_id=order.branch_id,
        terminal_id=order.terminal_id,
        original_shift_id=order.shift_id,
        recorded_by=tenant.user_id,
        kind=CORRECTION_KIND,
        original_method="upi",
        invoice_no=order.invoice_no,
        fiscal_year=order.fiscal_year,
        amount_minor=review.amount_minor,
        no_funds_received=payload.no_funds_received,
        all_items_not_supplied=payload.all_items_not_supplied,
        acknowledges_no_payout=payload.acknowledges_no_payout,
        reason=payload.reason,
        idempotency_key=idempotency_key,
        request_hash=digest,
        request_path=path,
        source_fingerprint=review.source_fingerprint,
        source_snapshot=captured.snapshot,
        stock_source_snapshot=stock_snapshot,
        request_payload=body,
        response_payload=result,
        recorded_at=now,
        accounting_at=now,
        business_timezone=zone,
        business_date=now.astimezone(ZoneInfo(zone)).date(),
    )
    session.add(row)
    await session.flush()
    if stock_mode:
        from app.services.pos.receipt_stock import restore_stock

        await restore_stock(
            session,
            snapshot=stock_snapshot,
            correction_id=ident,
            company_id=tenant.company_id,
            actor_id=tenant.user_id,
            at=now,
        )
    record_operation(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="receipt_corrected_no_value",
        entity_type="PosReceiptCorrection",
        entity_id=ident,
        before={"original_invoice_minor": review.amount_minor},
        after={
            "corrected_minor": review.amount_minor,
            "payout_minor": 0,
            "original_records_unchanged": True,
            **(
                {
                    "stock_restored": True,
                    "restored_movement_count": len(stock_review.movements),
                    "inventory_increase_minor": stock_review.inventory_increase_minor,
                }
                if stock_mode
                else {}
            ),
        },
        reason=payload.reason,
        operation_id=str(ident),
        source_reference="PC-" + str(ident),
    )
    await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=tenant.company_id,
        event_type="pos.receipt.corrected",
        source_type="pos_receipt_correction",
        source_id=str(ident),
        source_revision="1",
        occurred_at=now,
        payload={
            "kind": CORRECTION_KIND,
            "amount_minor": review.amount_minor,
            "payment_method": "upi",
            "payout_minor": 0,
            "revenue_delta_minor": -review.amount_minor,
            "upi_delta_minor": -review.amount_minor,
            "currency": company["currency"],
            "business_date": row.business_date.isoformat(),
            "original_records_unchanged": True,
            **(
                {
                    "stock_restored": True,
                    "inventory_delta_minor": stock_review.inventory_increase_minor,
                    "cogs_delta_minor": -stock_review.inventory_increase_minor,
                }
                if stock_mode
                else {}
            ),
        },
    )
    return result

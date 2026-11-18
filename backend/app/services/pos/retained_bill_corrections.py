"""Immutable review evidence for the narrow unsupplied retained-bill correction."""

from __future__ import annotations

import hashlib
import json
from uuid import UUID

from sqlalchemy import or_, select, text

from app.core.errors import BusinessRuleError
from app.models import GamingVoidReceipt, Order


async def gaming_void_provenance(session, order: Order) -> dict | None:
    receipt = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.company_id == order.company_id,
                or_(
                    GamingVoidReceipt.source_order_id == order.id,
                    GamingVoidReceipt.retained_order_id == order.id,
                ),
            )
        )
    ).scalar_one_or_none()
    if receipt is None:
        return None
    return {
        "original_receipt_id": str(receipt.id),
        "gaming_session_id": str(receipt.gaming_session_id),
        "financial_evidence_locked": True,
        "mistake_correction_available": (
            receipt.mode == "unlinked_addons"
            and receipt.retained_order_id == order.id
            and order.status == "held"
            and order.type == "session"
            and order.invoice_no is None
            and order.invoice_issued_at is None
        ),
    }


async def reject_gaming_void_financial_mutation(session, order: Order) -> None:
    if await gaming_void_provenance(session, order):
        raise BusinessRuleError(
            "Gaming Void bill amounts are immutable audit evidence. Discounts cannot "
            "change this bill. If every retained item was added by mistake and never "
            "supplied, an Owner can use Cancel mistaken items in POS."
        )


async def retained_bill_snapshot(session, order_id: UUID) -> tuple[dict, list, str]:
    row = (
        await session.execute(
            text("""
        SELECT to_jsonb(o) AS bill,
            (SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id), '[]'::jsonb)
             FROM order_lines l WHERE l.order_id=o.id) AS lines
        FROM orders o WHERE o.id=:order_id
    """),
            {"order_id": order_id},
        )
    ).one()
    snapshot = {"order": row.bill, "lines": row.lines}
    review_hash = hashlib.sha256(
        json.dumps(
            snapshot,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
        ).encode("utf-8")
    ).hexdigest()
    return row.bill, row.lines, review_hash

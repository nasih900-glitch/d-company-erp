"""One sanitized financial-state projection for receipt detail and batched history."""

from __future__ import annotations

from sqlalchemy import text

# All settlement and intent facts share one statement snapshot. No private
# command/source/reason or mutable current recipe is part of a receipt read.
_STATE_SQL = """
SELECT o.id,o.invoice_no,o.total_minor,
 coalesce((SELECT sum(p.amount_minor) FROM payments p WHERE p.order_id=o.id),0) AS paid,
 coalesce((SELECT sum(r.amount_minor) FROM refunds r WHERE r.order_id=o.id),0) AS refunded,
 coalesce((SELECT sum(c.amount_minor) FROM pos_receipt_corrections c WHERE c.order_id=o.id AND
 c.company_id=:company),0) AS corrected,
 dcompany_paid_bill_revision(dcompany_paid_bill_source(o.id,:company,NULL)) AS revision,
 coalesce((SELECT jsonb_agg(jsonb_build_object(
  'id',a.id,'revision',a.revision,'operation',a.operation,'target_minor',a.target_minor,
  'refund_request_id',a.refund_request_id,'actual_refund_id',f.id,
  'physical_completed_pending_accounting_minor',CASE WHEN (cc.id IS NOT NULL OR pc.id IS NOT
  NULL) AND f.id IS NULL THEN r.amount_minor ELSE 0 END,
  'payout_remaining_minor',CASE WHEN f.id IS NOT NULL OR w.id IS NOT NULL OR cc.id IS NOT NULL OR
  pc.id IS NOT NULL OR r.id IS NULL THEN 0
    WHEN ch.id IS NOT NULL OR ps.id IS NOT NULL THEN NULL ELSE r.amount_minor END,
  'status',CASE WHEN a.refund_request_id IS NULL THEN 'discarded'
    WHEN f.id IS NOT NULL THEN CASE WHEN a.operation='discard' THEN 'discarded' ELSE 'settled' END
    WHEN w.id IS NOT NULL THEN 'withdrawn'
    WHEN cc.id IS NOT NULL OR pc.id IS NOT NULL THEN 'physical_completed_accounting_pending'
    WHEN ch.id IS NOT NULL OR ps.id IS NOT NULL THEN 'payout_in_progress_outcome_unknown'
    ELSE 'pending_settlement' END) ORDER BY a.revision)
 FROM paid_bill_amendment_intents a
 LEFT JOIN pos_refund_requests r ON r.id=a.refund_request_id AND r.company_id=a.company_id
 LEFT JOIN refunds f ON f.request_id=r.id
 LEFT JOIN pos_refund_withdrawals w ON w.refund_request_id=r.id
 LEFT JOIN pos_refund_cash_handoffs ch ON ch.refund_request_id=r.id
 LEFT JOIN pos_refund_cash_handoff_completions cc ON cc.refund_request_id=r.id
 LEFT JOIN pos_refund_provider_payout_starts ps ON ps.refund_request_id=r.id
 LEFT JOIN pos_refund_provider_settlements pc ON pc.refund_request_id=r.id
 WHERE a.order_id=o.id AND a.company_id=:company),'[]'::jsonb) AS history
FROM orders o WHERE o.company_id=:company AND o.id=ANY(CAST(:ids AS uuid[]))
"""


async def paid_bill_states(session, *, company_id, order_ids):
    if not order_ids:
        return {}
    from app.schemas.paid_bill_amendment import PAID_BILL_AMENDMENT_PROTOCOL

    rows = (
        await session.execute(text(_STATE_SQL), {"company": company_id, "ids": list(order_ids)})
    ).mappings()
    result = {}
    for row in rows:
        history = row["history"]
        latest = history[-1] if history else None
        result[row["id"]] = {
            "protocol": PAID_BILL_AMENDMENT_PROTOCOL,
            "order_id": str(row["id"]),
            "invoice_no": row["invoice_no"],
            "original_total_minor": int(row["total_minor"]),
            "actual_refunded_minor": int(row["refunded"]),
            "no_value_corrected_minor": int(row["corrected"]),
            "effective_total_minor": int(row["paid"])
            - int(row["refunded"])
            - int(row["corrected"]),
            "source_revision": int(row["revision"]),
            "proposed_total_minor": latest["target_minor"]
            if latest and latest["status"] not in {"withdrawn", "settled", "discarded"}
            else None,
            "status": latest["status"]
            if latest
            else "corrected_no_payment"
            if row["corrected"]
            else "original",
            "refund_request_id": latest["refund_request_id"] if latest else None,
            "history": history,
            "physical_completed_pending_accounting_minor": sum(
                h["physical_completed_pending_accounting_minor"] for h in history
            ),
            "payout_remaining_minor": None
            if any(h["payout_remaining_minor"] is None for h in history)
            else sum(h["payout_remaining_minor"] for h in history),
            "stock_effect": False,
        }
    return result

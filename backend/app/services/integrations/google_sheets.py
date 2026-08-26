"""Google Sheets sink.

Server-side equivalent of frontend/src/lib/google-sheets.ts. Subscribes to
domain events; on each one, POSTs a JSON envelope to the company's
configured Apps Script web app URL.

Why server-side AND client-side?
  - Client-side (the React app) covers demo mode and standalone single-Mac
    deploys: no extra plumbing, instant gratification.
  - Server-side covers the production case: when cashier #2 on a different
    tablet sells something, we don't want their browser to push — the
    backend should, so the sheet stays in sync regardless of which device
    saw the sale.

Resilient by design:
  - Fire-and-forget: never blocks the order transaction. The event bus
    runs each handler in its own task; an exception logs and moves on.
  - Bounded retries with exponential backoff (tenacity).
  - Bounded timeout per attempt.
"""

from __future__ import annotations

from datetime import date
from typing import Any
from uuid import UUID

import httpx
from sqlalchemy import select
from tenacity import (
    AsyncRetrying,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from app.core.db import AsyncSessionLocal
from app.core.logging import get_logger
from app.events.events import OrderPaid
from app.models import Company, MenuItem, Order, OrderLine, User

log = get_logger(__name__)

REQUEST_TIMEOUT_S = 5.0
MAX_ATTEMPTS = 3


async def _post(url: str, kind: str, payload: dict[str, Any]) -> None:
    """POST to the Apps Script web app with retries."""
    body = {"kind": kind, "payload": payload}
    async for attempt in AsyncRetrying(
        stop=stop_after_attempt(MAX_ATTEMPTS),
        wait=wait_exponential(multiplier=1, min=1, max=10),
        retry=retry_if_exception_type((httpx.HTTPError, httpx.TimeoutException)),
        reraise=True,
    ):
        with attempt:
            async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT_S, follow_redirects=True) as c:
                # Apps Script accepts text/plain to avoid the CORS preflight,
                # but server-to-server we can send proper JSON.
                r = await c.post(url, json=body)
                r.raise_for_status()
                data = r.json()
                if not data.get("ok"):
                    raise httpx.HTTPError(
                        f"Apps Script returned not-ok: {data.get('error')}"
                    )


async def push_order_to_sheet(
    *,
    url: str,
    invoice_no: str,
    fiscal_year: str | None,
    company_id: UUID,
    gstin: str | None,
    place_of_supply: str | None,
    order_type: str,
    items_text: str,
    items_count: int,
    taxable_minor: int,
    cgst_minor: int,
    sgst_minor: int,
    igst_minor: int,
    cess_minor: int,
    round_off_minor: int,
    total_minor: int,
    method: str | None,
    at_iso: str,
    cashier: str | None = None,
) -> None:
    """Push a single order. Failures are logged but never raised."""
    payload = {
        "invoice_no": invoice_no,
        "fiscal_year": fiscal_year,
        "company_id": str(company_id),
        "date": at_iso[:10],
        "time": at_iso[11:16],
        "type": order_type,
        "items_text": items_text,
        "items_count": items_count,
        "cashier": cashier or "",
        "taxable_minor": taxable_minor,
        "cgst_minor": cgst_minor,
        "sgst_minor": sgst_minor,
        "igst_minor": igst_minor,
        "cess_minor": cess_minor,
        "round_off_minor": round_off_minor,
        "total_minor": total_minor,
        "method": method or "",
        "gstin": gstin or "",
        "place_of_supply": place_of_supply or "",
    }
    try:
        await _post(url, "order", payload)
        log.info("gsheets.push.ok", invoice_no=invoice_no)
    except Exception as exc:  # noqa: BLE001
        log.warning(
            "gsheets.push.failed",
            invoice_no=invoice_no,
            error=str(exc),
        )


async def push_manual_collection_to_sheet(
    *,
    url: str,
    row_id: UUID,
    company_id: UUID,
    branch_name: str | None,
    business_date: date,
    method: str,
    amount_minor: int,
    source_kind: str,
    source_ref: str,
    note: str | None,
) -> None:
    """Push a single manual (off-POS) collection. Failures are logged, never raised."""
    payload = {
        "id": str(row_id),
        "company_id": str(company_id),
        "branch": branch_name or "",
        "date": business_date.isoformat(),
        "method": method,
        "amount_minor": amount_minor,
        "source_kind": source_kind,
        "source_ref": source_ref,
        "note": note or "",
    }
    try:
        await _post(url, "manual_collection", payload)
        log.info("gsheets.push.ok", kind="manual_collection", row_id=str(row_id))
    except Exception as exc:  # noqa: BLE001
        log.warning(
            "gsheets.push.failed",
            kind="manual_collection",
            row_id=str(row_id),
            error=str(exc),
        )


# ---------------------------------------------------------------------------
# Event-bus wiring
# ---------------------------------------------------------------------------
async def on_order_paid(event: OrderPaid) -> None:
    """OrderPaid handler — resolves the full order and forwards it to the
    company's Google Sheets webhook, if one is configured.

    Runs outside the request's session lifecycle (the event is published
    from a FastAPI BackgroundTask, after the payment request's own session
    has already committed — see record_payment in
    app/api/v1/pos/router.py), so it opens its own short-lived session to
    read the now-committed order in its final "paid" state.
    """
    async with AsyncSessionLocal() as session:
        company = await session.get(Company, event.company_id)
        if not company or not company.google_sheets_webhook_url:
            return  # not every company has this configured — silent no-op

        order = await session.get(Order, event.order_id)
        if not order:
            log.warning("gsheets.on_order_paid.order_missing", order_id=str(event.order_id))
            return

        line_rows = (
            await session.execute(
                select(OrderLine, MenuItem.name)
                .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
                .where(
                    OrderLine.order_id == order.id,
                    OrderLine.voided_at.is_(None),
                )
            )
        ).all()

        def _fmt_qty(qty: float) -> str:
            q = float(qty)
            return f"{q:g}"

        items_text = ", ".join(f"{_fmt_qty(ol.qty)}x {name}" for ol, name in line_rows)
        items_count = int(sum(float(ol.qty or 0) for ol, _ in line_rows))
        taxable_minor = sum(int(ol.taxable_value_minor or 0) for ol, _ in line_rows)

        cashier_name: str | None = None
        if order.opened_by:
            opener = await session.get(User, order.opened_by)
            cashier_name = opener.name if opener else None

        at = order.invoice_issued_at or event.occurred_at

        await push_order_to_sheet(
            url=company.google_sheets_webhook_url,
            invoice_no=order.invoice_no or "",
            fiscal_year=order.fiscal_year,
            company_id=event.company_id,
            gstin=company.gstin,
            place_of_supply=order.place_of_supply_state_code,
            order_type=order.type,
            items_text=items_text,
            items_count=items_count,
            taxable_minor=taxable_minor,
            cgst_minor=int(order.cgst_minor or 0),
            sgst_minor=int(order.sgst_minor or 0),
            igst_minor=int(order.igst_minor or 0),
            cess_minor=int(order.cess_minor or 0),
            round_off_minor=int(order.round_off_minor or 0),
            total_minor=int(order.total_minor or 0),
            method=event.method,
            at_iso=at.isoformat(),
            cashier=cashier_name,
        )

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

from typing import Any
from uuid import UUID

import httpx
from tenacity import (
    AsyncRetrying,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from app.core.logging import get_logger
from app.events.events import DomainEvent, OrderPaid

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


# ---------------------------------------------------------------------------
# Event-bus wiring
# ---------------------------------------------------------------------------
async def on_order_paid(_event: DomainEvent) -> None:
    """OrderPaid handler — looks up the company's webhook URL and forwards.

    Stubbed in V1: the OrderPaid event carries only ids, and resolving
    company.google_sheets_webhook_url requires a DB session. The full wiring
    lands when the POS payment endpoint emits OrderPaid (currently emits
    nothing — see backend/app/api/v1/pos/router.py record_payment).

    To enable later:
      from app.events.bus import get_event_bus
      bus = get_event_bus()
      bus.subscribe(OrderPaid, on_order_paid)
    in the lifespan handler.
    """
    # See docstring — full implementation requires the OrderPaid event +
    # session injection pattern (next session).
    del _event

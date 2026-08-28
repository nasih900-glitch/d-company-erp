"""Real-time push over WebSocket.

Tells every connected client for a company the instant an operationally
shared resource changes (a shift opened/closed, a table's status changed,
an order was billed/held/voided, a gaming session started/stopped, a
kitchen ticket advanced, a staff member clocked in/out) — so screens
update themselves the moment it happens instead of polling on a timer.
This is what makes two logins agree on shift/table state immediately
rather than a login showing whatever it last happened to fetch.

Single-process deployment (see infra/docker/backend-entrypoint.sh — plain
`uvicorn app.main:app`, no --workers) so an in-memory connection registry
is enough. If this ever runs multiple worker processes, broadcast() needs
to go over Redis pub/sub instead so every worker's connections hear it.

Deliberately coarse-grained: a message only says *what kind* of resource
changed ("shifts", "tables", ...), never a payload. The client already has
a REST fetch for that resource (the same one that used to run on a
timer) — on a signal it just re-runs that fetch. No new state-merging
logic needed on either end, and no risk of the push payload drifting out
of sync with what a plain GET would return.
"""

from __future__ import annotations

from collections import defaultdict
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from uuid import UUID

    from fastapi import WebSocket

from app.core.logging import get_logger

log = get_logger(__name__)

RESOURCES = frozenset({
    "shifts", "tables", "orders", "gaming", "kitchen", "attendance",
    "menu", "customers", "inventory", "finance", "staff", "events",
    "memberships", "access_control", "ocr", "settings", "bug_reports",
})


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: dict[UUID, set[WebSocket]] = defaultdict(set)

    def connect(self, company_id: UUID, ws: WebSocket) -> None:
        self._connections[company_id].add(ws)

    def disconnect(self, company_id: UUID, ws: WebSocket) -> None:
        conns = self._connections.get(company_id)
        if not conns:
            return
        conns.discard(ws)
        if not conns:
            self._connections.pop(company_id, None)

    async def broadcast(self, company_id: UUID, resource: str) -> None:
        conns = list(self._connections.get(company_id, ()))
        if not conns:
            return
        message = {"type": "changed", "resource": resource}
        dead: list[WebSocket] = []
        for ws in conns:
            try:
                await ws.send_json(message)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(company_id, ws)

    def connection_count(self, company_id: UUID) -> int:
        return len(self._connections.get(company_id, ()))


manager = ConnectionManager()


# Route-substring -> resource. Matched against the full request path (which
# always includes the /api/v1 prefix) after any successful mutating
# request. Deliberately path-based rather than wired into each individual
# endpoint, so a new write endpoint added under one of these routers is
# covered automatically instead of silently missing the broadcast the way
# a manually-placed call site could.
_PATH_RESOURCE_MAP: tuple[tuple[str, str], ...] = (
    ("/pos/shifts", "shifts"),
    ("/pos/refund-requests", "orders"),
    ("/pos/customer-spend-reconciliations", "customers"),
    ("/tables", "tables"),
    ("/pos/orders", "orders"),
    ("/gaming/sessions", "gaming"),
    ("/gaming/bookings", "gaming"),
    ("/kitchen", "kitchen"),
    # Must precede the bare "/staff" row below — the substring match returns
    # on first hit, so attendance writes would otherwise resolve to "staff".
    ("/staff/attendance", "attendance"),
    ("/menu", "menu"),
    ("/customers", "customers"),
    ("/inventory", "inventory"),
    ("/finance", "finance"),
    ("/staff", "staff"),
    ("/events", "events"),
    ("/memberships", "memberships"),
    ("/settings", "settings"),
    ("/bug-reports", "bug_reports"),
    # Narrow on purpose — NOT a bare "/admin" row, which would also swallow
    # /admin/pricing/unlock and /admin/audit/*, neither of which is a
    # resource anything should be pull-refreshing on.
    ("/admin/access-control", "access_control"),
    ("/ocr", "ocr"),
)


def resource_for_path(path: str) -> str | None:
    for substring, resource in _PATH_RESOURCE_MAP:
        if substring in path:
            return resource
    return None


def resources_for_path(path: str) -> tuple[str, ...]:
    """All caches invalidated by a successful write on ``path``.

    Membership collection/refund mutates more than the entitlement table: it
    changes shift collection/drawer totals, customer lifetime spend, and
    finance/report aggregates. Broadcasting only ``memberships`` left every
    other terminal displaying stale money until its next poll.
    """
    primary = resource_for_path(path)
    if primary is None:
        return ()
    normalized_path = path.rstrip("/")
    if primary == "memberships":
        return ("memberships", "shifts", "customers", "finance")
    if "/pos/customer-spend-reconciliations" in path:
        # This owner correction writes Customer.total_spent_minor directly.
        # Customer screens and finance/LTV aggregates both read that value.
        return ("customers", "finance")
    if "/pos/refund-requests" in path:
        return ("orders", "shifts", "customers", "finance")
    if "/pos/orders/" in normalized_path and normalized_path.endswith("/payments"):
        # Every accepted payment changes shift collections and finance/report
        # aggregates. When it settles the balance, the same route also runs
        # _finalize_order, which updates customer visits, spend, and loyalty
        # and deducts any recipe ingredients from inventory. Path-only
        # invalidation therefore has to include both completion resources for
        # this route, while ordinary unpaid-order edits remain narrowly scoped.
        return (
            "orders",
            "tables",
            "kitchen",
            "shifts",
            "customers",
            "finance",
            "inventory",
        )
    if "/pos/orders/" in normalized_path and normalized_path.endswith("/finalize-zero"):
        # Zero-total settlement has no shift collection to refresh, but it does
        # run the shared finalizer: customer metrics, finance/report facts
        # (including the completed sale/COGS boundary), and recipe inventory
        # can change.
        return ("orders", "tables", "kitchen", "customers", "finance", "inventory")
    if "/pos/orders" in path:
        # Table rounds, line edits/cancellations, held handoff, checkout claims,
        # and whole-order void can change these three operational screens. The
        # financial completion routes are handled above rather than widening
        # every draft-order edit to unrelated customer and finance reads.
        return ("orders", "tables", "kitchen")
    if "/gaming/sessions" in path and (
        "/send-to-pos" in path or "/reconcile-to-pos" in path
    ):
        return ("gaming", "orders")
    return (primary,)

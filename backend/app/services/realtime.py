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
from uuid import UUID

from fastapi import WebSocket

from app.core.logging import get_logger

log = get_logger(__name__)

RESOURCES = frozenset({"shifts", "tables", "orders", "gaming", "kitchen", "attendance"})


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
    ("/tables", "tables"),
    ("/pos/orders", "orders"),
    ("/gaming/sessions", "gaming"),
    ("/gaming/bookings", "gaming"),
    ("/kitchen", "kitchen"),
    ("/staff/attendance", "attendance"),
)


def resource_for_path(path: str) -> str | None:
    for substring, resource in _PATH_RESOURCE_MAP:
        if substring in path:
            return resource
    return None

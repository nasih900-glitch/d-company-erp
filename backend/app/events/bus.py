"""In-process event bus.

Handlers are coroutines registered at startup. Failures of one handler
do NOT block other handlers (each runs in its own try/except).
"""

from __future__ import annotations

import asyncio
from collections import defaultdict
from collections.abc import Awaitable, Callable
from functools import lru_cache
from typing import TypeVar

from app.core.logging import get_logger
from app.events.events import DomainEvent

E = TypeVar("E", bound=DomainEvent)
Handler = Callable[[E], Awaitable[None]]

log = get_logger(__name__)


class EventBus:
    def __init__(self) -> None:
        self._handlers: dict[type[DomainEvent], list[Handler[DomainEvent]]] = defaultdict(list)

    def subscribe(self, event_type: type[E], handler: Handler[E]) -> None:
        self._handlers[event_type].append(handler)  # type: ignore[arg-type]

    async def publish(self, event: DomainEvent) -> None:
        handlers = self._handlers.get(type(event), [])
        if not handlers:
            return
        results = await asyncio.gather(
            *(self._safe(h, event) for h in handlers), return_exceptions=False
        )
        del results  # nothing to do with them; errors are logged

    @staticmethod
    async def _safe(handler: Handler[DomainEvent], event: DomainEvent) -> None:
        try:
            await handler(event)
        except Exception as exc:  # noqa: BLE001
            log.exception(
                "event.handler.failed",
                event_type=type(event).__name__,
                handler=getattr(handler, "__name__", repr(handler)),
                exc_info=exc,
            )


@lru_cache(maxsize=1)
def get_event_bus() -> EventBus:
    return EventBus()

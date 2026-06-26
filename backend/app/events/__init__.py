"""Domain event bus.

In-process by default; the same interface has a Redis-backed
implementation for cross-process delivery (workers).
"""

from app.events.bus import EventBus, get_event_bus
from app.events.events import (
    DomainEvent,
    ExpenseApproved,
    LowStock,
    OrderPaid,
    SessionEnded,
)

__all__ = [
    "DomainEvent",
    "EventBus",
    "ExpenseApproved",
    "LowStock",
    "OrderPaid",
    "SessionEnded",
    "get_event_bus",
]

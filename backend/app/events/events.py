"""Domain events — value objects emitted by services."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID


@dataclass(frozen=True, slots=True)
class DomainEvent:
    occurred_at: datetime
    company_id: UUID
    branch_id: UUID | None


@dataclass(frozen=True, slots=True)
class OrderPaid(DomainEvent):
    order_id: UUID
    total_minor: int
    method: str


@dataclass(frozen=True, slots=True)
class LowStock(DomainEvent):
    ingredient_id: UUID
    current_qty: float
    threshold: float


@dataclass(frozen=True, slots=True)
class SessionEnded(DomainEvent):
    session_id: UUID
    station_id: UUID
    billable_minutes: int
    amount_minor: int


@dataclass(frozen=True, slots=True)
class ExpenseApproved(DomainEvent):
    expense_id: UUID
    amount_minor: int
    category: str

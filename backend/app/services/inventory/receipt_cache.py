"""Exact quantity/weighted numerator for the existing per-line summary cache."""

from __future__ import annotations

from decimal import Decimal

from app.core.errors import BusinessRuleError

SCALE = 10000
MAX_SCALED = 99_999_999_999_999


def _scaled(value):
    number = Decimal(str(value))
    scaled = number * SCALE
    if not number.is_finite() or scaled != scaled.to_integral_value():
        raise BusinessRuleError("Inventory quantity must be finite with at most four decimals.")
    return int(scaled)


def receive_summary(current_quantity, current_average_minor, received_quantity, unit_cost_minor):
    """Preserve sequential truncation toward zero; batches/FIFO remain authoritative."""
    previous, received = _scaled(current_quantity), _scaled(received_quantity)
    quantity = previous + received
    if abs(quantity) > MAX_SCALED:
        raise BusinessRuleError("Resulting ingredient quantity exceeds NUMERIC(14,4).")
    numerator = previous * int(current_average_minor or 0) + received * int(unit_cost_minor)
    if quantity > 0:
        average = (abs(numerator) // quantity) * (-1 if numerator < 0 else 1)
    else:
        average = int(unit_cost_minor)
    return Decimal(quantity).scaleb(-4), average

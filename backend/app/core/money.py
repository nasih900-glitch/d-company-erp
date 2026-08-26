"""Money value object — integer minor units only.

Floats and Decimals are forbidden in money math; convert at the boundary.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import TypeAlias

Weight: TypeAlias = Decimal | int | float | str


@dataclass(frozen=True, slots=True)
class Money:
    """An amount of money in a single currency, stored as integer minor units."""

    minor: int
    currency: str = "INR"

    def __post_init__(self) -> None:
        if not isinstance(self.minor, int) or isinstance(self.minor, bool):
            raise TypeError(f"Money.minor must be int, got {type(self.minor).__name__}")
        if len(self.currency) != 3 or not self.currency.isalpha():
            raise ValueError(f"invalid currency code: {self.currency!r}")

    @classmethod
    def zero(cls, currency: str = "INR") -> "Money":
        return cls(0, currency)

    def __add__(self, other: "Money") -> "Money":
        self._check(other)
        return Money(self.minor + other.minor, self.currency)

    def __sub__(self, other: "Money") -> "Money":
        self._check(other)
        return Money(self.minor - other.minor, self.currency)

    def __mul__(self, factor: int) -> "Money":
        if not isinstance(factor, int) or isinstance(factor, bool):
            raise TypeError("Money can only be multiplied by int")
        return Money(self.minor * factor, self.currency)

    def __neg__(self) -> "Money":
        return Money(-self.minor, self.currency)

    def __lt__(self, other: "Money") -> bool:
        self._check(other)
        return self.minor < other.minor

    def __le__(self, other: "Money") -> bool:
        self._check(other)
        return self.minor <= other.minor

    def is_zero(self) -> bool:
        return self.minor == 0

    def is_positive(self) -> bool:
        return self.minor > 0

    def is_negative(self) -> bool:
        return self.minor < 0

    def _check(self, other: "Money") -> None:
        if self.currency != other.currency:
            raise ValueError(
                f"currency mismatch: {self.currency} vs {other.currency}"
            )

    def __str__(self) -> str:
        sign = "-" if self.minor < 0 else ""
        whole, fraction = divmod(abs(self.minor), 100)
        return f"{sign}{whole}.{fraction:02d} {self.currency}"


def apportion(total_minor: int, weights: list[Weight]) -> list[int]:
    """Split an integer amount by weights so shares sum EXACTLY to the total.

    Largest-remainder method: floor each share, then hand out the few minor
    units left over (at most len(weights) - 1 of them) to whichever weights
    lost the most to flooring. Used for splitting profit/capital across
    partners — a naive per-share round() can under- or over-shoot the total
    by a few paise, which must never happen with real owners' money.
    """
    if not weights:
        return []
    if total_minor == 0:
        return [0] * len(weights)
    try:
        exact_weights = [Decimal(str(weight)) for weight in weights]
    except Exception as exc:
        raise ValueError("weights must be finite non-negative numbers") from exc
    if any(not weight.is_finite() or weight < 0 for weight in exact_weights):
        raise ValueError("weights must be finite non-negative numbers")
    # Normalize every finite Decimal to a common integer scale, then do the
    # entire largest-remainder calculation with integers. Decimal division is
    # context-rounded; close ownership percentages must not let that context
    # decide which partner receives the final paise.
    scale = max(0, *(-weight.as_tuple().exponent for weight in exact_weights))
    integer_weights = [int(weight.scaleb(scale)) for weight in exact_weights]
    weight_total = sum(integer_weights)
    if weight_total <= 0:
        raise ValueError("at least one weight must be positive")
    sign = 1 if total_minor >= 0 else -1
    magnitude = abs(total_minor)
    divisions = [divmod(magnitude * weight, weight_total) for weight in integer_weights]
    floors = [quotient for quotient, _ in divisions]
    remainder = magnitude - sum(floors)
    order = sorted(range(len(weights)), key=lambda i: divisions[i][1], reverse=True)
    for i in order[:remainder]:
        floors[i] += 1
    return [sign * f for f in floors]

"""Money value object — integer minor units only.

Floats and Decimals are forbidden in money math; convert at the boundary.
"""

from __future__ import annotations

from dataclasses import dataclass


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
        return f"{self.minor / 100:.2f} {self.currency}"


def apportion(total_minor: int, weights: list[float]) -> list[int]:
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
    weight_total = sum(weights)
    sign = 1 if total_minor >= 0 else -1
    magnitude = abs(total_minor)
    raw = [magnitude * w / weight_total for w in weights]
    floors = [int(r) for r in raw]
    remainder = magnitude - sum(floors)
    order = sorted(range(len(weights)), key=lambda i: raw[i] - floors[i], reverse=True)
    for i in order[:remainder]:
        floors[i] += 1
    return [sign * f for f in floors]

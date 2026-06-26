"""Money is the single source of truth for all financial math."""

import pytest

from app.core.money import Money


def test_addition_same_currency() -> None:
    assert Money(100) + Money(50) == Money(150)


def test_addition_currency_mismatch_raises() -> None:
    with pytest.raises(ValueError, match="currency mismatch"):
        Money(100, "INR") + Money(50, "USD")


def test_no_floats() -> None:
    with pytest.raises(TypeError):
        Money(1.50)  # type: ignore[arg-type]


def test_no_bools() -> None:
    with pytest.raises(TypeError):
        Money(True)  # type: ignore[arg-type]


def test_multiplication_by_int() -> None:
    assert Money(250) * 4 == Money(1000)


def test_multiplication_rejects_float() -> None:
    with pytest.raises(TypeError):
        Money(250) * 1.5  # type: ignore[operator]


def test_currency_validation() -> None:
    with pytest.raises(ValueError):
        Money(100, "rupees")
    with pytest.raises(ValueError):
        Money(100, "IN")


def test_str() -> None:
    assert str(Money(12345, "INR")) == "123.45 INR"

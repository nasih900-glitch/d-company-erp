"""Money is the single source of truth for all financial math."""

from decimal import Decimal

import pytest

from app.core.money import Money, apportion


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
    assert str(Money(-5, "INR")) == "-0.05 INR"


class TestApportion:
    def test_equal_split_sums_exactly(self) -> None:
        shares = apportion(100, [33.3334, 33.3333, 33.3333])
        assert sum(shares) == 100
        assert shares == [34, 33, 33]

    def test_evenly_divisible_split(self) -> None:
        assert apportion(300, [1, 1, 1]) == [100, 100, 100]

    def test_uneven_weights(self) -> None:
        shares = apportion(1000, [29.27, 36.94, 33.79])
        assert sum(shares) == 1000

    def test_zero_total(self) -> None:
        assert apportion(0, [50, 50]) == [0, 0]

    def test_negative_total_preserves_sign_and_sum(self) -> None:
        shares = apportion(-100, [33.3334, 33.3333, 33.3333])
        assert sum(shares) == -100
        assert all(s <= 0 for s in shares)

    def test_empty_weights(self) -> None:
        assert apportion(500, []) == []

    def test_single_weight_gets_everything(self) -> None:
        assert apportion(777, [100]) == [777]

    def test_remainder_smaller_than_one_paisa_per_head(self) -> None:
        # 7 paise across 3 equal weights: two get 2, one gets 3 (or similar),
        # but the total must always reconcile exactly.
        shares = apportion(7, [1, 1, 1])
        assert sum(shares) == 7
        assert max(shares) - min(shares) <= 1

    def test_large_value_uses_exact_decimal_weights(self) -> None:
        total = 9_007_199_254_740_991
        shares = apportion(
            total,
            [
                Decimal("33.3333333334"),
                Decimal("33.3333333333"),
                Decimal("33.3333333333"),
            ],
        )
        assert sum(shares) == total
        assert shares[0] >= shares[1] == shares[2]

    @pytest.mark.parametrize("weights", [[0, 0], [-1, 2], ["NaN", 1]])
    def test_invalid_weights_fail_closed(self, weights) -> None:
        with pytest.raises(ValueError):
            apportion(100, weights)

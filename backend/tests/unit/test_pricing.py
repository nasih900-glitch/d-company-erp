"""OrderPricingService — India / Kerala tax math.

These are PURE tests on the helpers; the full service is tested in
integration tests because it needs a DB session.
"""

from datetime import date
from decimal import Decimal

from app.services.pos.pricing import (
    MembershipDiscountRates,
    _discount_for_item_type,
    _discount_minor,
    _round_to_rupee,
    _split_tax_from_inclusive,
    fiscal_year_for,
)


class TestSplitTaxFromInclusive:
    """Cappuccino ₹180 inclusive at 5% should split to ₹171.43 + ₹4.29 + ₹4.28."""

    def test_zero_rate_passes_through(self) -> None:
        taxable, cgst, sgst, igst = _split_tax_from_inclusive(18000, Decimal("0"), True)
        assert (taxable, cgst, sgst, igst) == (18000, 0, 0, 0)

    def test_5_percent_intra_state_balances(self) -> None:
        # ₹180 inclusive at 5%
        taxable, cgst, sgst, igst = _split_tax_from_inclusive(18000, Decimal("0.05"), True)
        assert taxable + cgst + sgst + igst == 18000
        assert igst == 0
        # Indian roundoff: 18000 / 1.05 = 17142.857... → 17143
        assert taxable == 17143
        assert cgst + sgst == 857
        # The half-up split: cgst should equal sgst within 1 paisa
        assert abs(cgst - sgst) <= 1

    def test_5_percent_inter_state_uses_igst(self) -> None:
        taxable, cgst, sgst, igst = _split_tax_from_inclusive(18000, Decimal("0.05"), False)
        assert (cgst, sgst) == (0, 0)
        assert taxable + igst == 18000
        assert igst == 857

    def test_18_percent_gaming(self) -> None:
        # 1 hour at ₹200/hr inclusive → taxable 169.49, cgst+sgst 30.51
        taxable, cgst, sgst, igst = _split_tax_from_inclusive(20000, Decimal("0.18"), True)
        assert taxable + cgst + sgst == 20000
        assert taxable == 16949
        assert cgst + sgst == 3051

    def test_no_remainder_at_round_amounts(self) -> None:
        # Exactly ₹100 at 5% → taxable 95.24, tax 4.76
        taxable, cgst, sgst, igst = _split_tax_from_inclusive(10000, Decimal("0.05"), True)
        assert taxable + cgst + sgst == 10000


class TestRoundToRupee:
    def test_exact_rupee_is_zero_round_off(self) -> None:
        rounded, delta = _round_to_rupee(76000)
        assert (rounded, delta) == (76000, 0)

    def test_rounds_up_when_paise_geq_50(self) -> None:
        rounded, delta = _round_to_rupee(76050)
        assert rounded == 76100
        assert delta == 50

    def test_rounds_down_when_paise_lt_50(self) -> None:
        rounded, delta = _round_to_rupee(76049)
        assert rounded == 76000
        assert delta == -49


class TestMembershipDiscounts:
    def test_discount_minor_uses_half_up_rounding(self) -> None:
        assert _discount_minor(999, Decimal("0.10")) == 100

    def test_discount_never_exceeds_line_total(self) -> None:
        assert _discount_minor(100, Decimal("2.00")) == 100

    def test_food_discount_applies_to_cafe_item_types(self) -> None:
        rates = MembershipDiscountRates(
            food=Decimal("0.15"),
            gaming=Decimal("0.20"),
            hookah=Decimal("0.10"),
        )
        assert _discount_for_item_type("food", rates) == Decimal("0.15")
        assert _discount_for_item_type("drink", rates) == Decimal("0.15")
        assert _discount_for_item_type("dessert", rates) == Decimal("0.15")

    def test_event_tickets_do_not_get_membership_discount(self) -> None:
        rates = MembershipDiscountRates(
            food=Decimal("0.15"),
            gaming=Decimal("0.20"),
            hookah=Decimal("0.10"),
        )
        assert _discount_for_item_type("event", rates) == Decimal("0")


class TestFiscalYear:
    def test_april_starts_new_fy(self) -> None:
        assert fiscal_year_for(date(2026, 4, 1)) == "2026-27"
        assert fiscal_year_for(date(2026, 4, 30)) == "2026-27"

    def test_march_is_previous_fy(self) -> None:
        assert fiscal_year_for(date(2026, 3, 31)) == "2025-26"
        assert fiscal_year_for(date(2026, 1, 5)) == "2025-26"

    def test_calendar_year_end(self) -> None:
        assert fiscal_year_for(date(2026, 12, 31)) == "2026-27"

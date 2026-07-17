"""Regression tests for the business-metrics math (AOV/MRR/ARR/CAC/LTV/burn)."""

from __future__ import annotations

from app.services.reports.business_metrics import (
    compute_business_metrics,
    compute_distributable_capacity,
)


def _base(**overrides):
    values = dict(
        net_revenue_minor=100_000,
        orders_count=10,
        net_profit_minor=60_000,
        active_membership_monthly_equivalent_minor=0,
        active_members_count=0,
        marketing_spend_minor=0,
        new_customers_count=0,
        total_customer_spend_minor=0,
        customers_count=0,
    )
    values.update(overrides)
    return values


class TestAOV:
    def test_average_order_value(self) -> None:
        m = compute_business_metrics(**_base(net_revenue_minor=100_000, orders_count=10))
        assert m.aov_minor == 10_000

    def test_zero_orders_is_zero_not_a_crash(self) -> None:
        m = compute_business_metrics(**_base(net_revenue_minor=0, orders_count=0))
        assert m.aov_minor == 0


class TestMRRandARR:
    def test_mrr_is_the_sum_of_active_membership_monthly_equivalents(self) -> None:
        m = compute_business_metrics(
            **_base(active_membership_monthly_equivalent_minor=299_800, active_members_count=3)
        )
        assert m.mrr_minor == 299_800

    def test_arr_is_mrr_times_twelve(self) -> None:
        m = compute_business_metrics(
            **_base(active_membership_monthly_equivalent_minor=100_000)
        )
        assert m.arr_minor == 1_200_000

    def test_no_members_means_zero_mrr_and_arr(self) -> None:
        m = compute_business_metrics(**_base())
        assert (m.mrr_minor, m.arr_minor) == (0, 0)


class TestCAC:
    def test_marketing_spend_split_across_new_customers(self) -> None:
        m = compute_business_metrics(
            **_base(marketing_spend_minor=60_000, new_customers_count=3)
        )
        assert m.cac_minor == 20_000

    def test_no_new_customers_is_none_not_a_division_error(self) -> None:
        m = compute_business_metrics(
            **_base(marketing_spend_minor=60_000, new_customers_count=0)
        )
        assert m.cac_minor is None


class TestLTV:
    def test_average_lifetime_spend_per_customer(self) -> None:
        m = compute_business_metrics(
            **_base(total_customer_spend_minor=500_000, customers_count=5)
        )
        assert m.ltv_minor == 100_000

    def test_no_customers_is_zero_not_a_crash(self) -> None:
        m = compute_business_metrics(**_base(total_customer_spend_minor=0, customers_count=0))
        assert m.ltv_minor == 0


class TestBurnRate:
    def test_negative_net_profit_is_the_burn(self) -> None:
        m = compute_business_metrics(**_base(net_profit_minor=-50_000))
        assert m.burn_rate_minor == 50_000

    def test_profitable_period_has_zero_burn(self) -> None:
        m = compute_business_metrics(**_base(net_profit_minor=50_000))
        assert m.burn_rate_minor == 0

    def test_exactly_breakeven_has_zero_burn(self) -> None:
        m = compute_business_metrics(**_base(net_profit_minor=0))
        assert m.burn_rate_minor == 0

    def test_cost_of_goods_sold_is_not_silently_ignored(self) -> None:
        # Net revenue covers running costs alone, but real cost of goods sold
        # (already netted into net_profit_minor upstream) tips the period into
        # a real loss — this is exactly the scenario the old
        # expenses-vs-revenue-only formula got wrong: net revenue 1,00,000,
        # cost of goods sold 60,000, running costs 45,000 -> a 5,000 loss,
        # not the "0, profitable" the old formula would have reported.
        m = compute_business_metrics(
            **_base(net_revenue_minor=100_000, net_profit_minor=-5_000)
        )
        assert m.burn_rate_minor == 5_000


def _capacity_base(**overrides):
    values = dict(
        lifetime_net_profit_minor=1_000_000,
        lifetime_withdrawn_minor=0,
        avg_monthly_cost_minor=50_000,
        reserve_months=6,
        liquid_cash_minor=1_000_000,
    )
    values.update(overrides)
    return values


class TestDistributableCapacity:
    def test_reserve_is_avg_monthly_cost_times_reserve_months(self) -> None:
        c = compute_distributable_capacity(
            **_capacity_base(avg_monthly_cost_minor=50_000, reserve_months=6)
        )
        assert c.reserve_minor == 300_000

    def test_profit_based_cap_nets_lifetime_profit_minus_withdrawals_minus_reserve(self) -> None:
        c = compute_distributable_capacity(
            **_capacity_base(
                lifetime_net_profit_minor=1_000_000,
                lifetime_withdrawn_minor=200_000,
                avg_monthly_cost_minor=50_000,
                reserve_months=6,
                liquid_cash_minor=10_000_000,  # cash is not the binding constraint here
            )
        )
        # 1,000,000 - 200,000 - 300,000
        assert c.profit_based_capacity_minor == 500_000
        assert c.safe_to_distribute_minor == 500_000

    def test_cash_cap_binds_when_profit_is_on_paper_but_not_in_the_bank(self) -> None:
        # Real profit looks healthy, but the cash is tied up in stock/gear —
        # the cash-based cap must be the one that wins.
        c = compute_distributable_capacity(
            **_capacity_base(
                lifetime_net_profit_minor=2_000_000,
                lifetime_withdrawn_minor=0,
                avg_monthly_cost_minor=50_000,
                reserve_months=6,
                liquid_cash_minor=100_000,  # far less than the paper profit
            )
        )
        assert c.cash_based_capacity_minor == 100_000 - 300_000  # negative
        assert c.safe_to_distribute_minor == 0

    def test_never_negative_even_when_both_caps_are_negative(self) -> None:
        c = compute_distributable_capacity(
            **_capacity_base(
                lifetime_net_profit_minor=100_000,
                lifetime_withdrawn_minor=500_000,
                avg_monthly_cost_minor=100_000,
                reserve_months=6,
                liquid_cash_minor=50_000,
            )
        )
        assert c.profit_based_capacity_minor < 0
        assert c.cash_based_capacity_minor < 0
        assert c.safe_to_distribute_minor == 0

    def test_already_withdrawn_more_than_lifetime_profit_is_zero_not_negative(self) -> None:
        c = compute_distributable_capacity(
            **_capacity_base(
                lifetime_net_profit_minor=500_000,
                lifetime_withdrawn_minor=600_000,
                avg_monthly_cost_minor=0,
                reserve_months=6,
                liquid_cash_minor=10_000_000,
            )
        )
        assert c.safe_to_distribute_minor == 0

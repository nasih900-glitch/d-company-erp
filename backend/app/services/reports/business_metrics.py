"""Business metrics genuinely relevant to a single-location gaming café.

Deliberately does NOT include SaaS/VC-fundraising metrics (MRR growth rate
targets, Rule of 40, North Star Metric, viral coefficient, CAC:LTV payback
curves, TAM/SAM/SOM, vesting, SAFEs) — those measure a scaling software
product with outside investors and a large funnel, neither of which apply to
3 self-funded owners running one physical location. Forcing those numbers
in would just be noise dressed up as data.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class BusinessMetrics:
    aov_minor: int
    orders_count: int
    mrr_minor: int
    arr_minor: int
    active_members_count: int
    cac_minor: int | None  # None when there were no new customers to divide by
    new_customers_count: int
    marketing_spend_minor: int
    ltv_minor: int
    customers_count: int
    burn_rate_minor: int


def compute_business_metrics(
    *,
    net_revenue_minor: int,
    orders_count: int,
    net_profit_minor: int,
    active_membership_monthly_equivalent_minor: int,
    active_members_count: int,
    marketing_spend_minor: int,
    new_customers_count: int,
    total_customer_spend_minor: int,
    customers_count: int,
) -> BusinessMetrics:
    """Pure math over pre-aggregated period totals — no DB access here.

    active_membership_monthly_equivalent_minor: sum, across every currently
    active membership, of its monthly-equivalent price (a monthly plan's own
    price, or an annual plan's price / 12) — the standard MRR definition.

    burn_rate_minor: how much the period's true bottom line (net_profit_minor
    — revenue minus cost of goods sold minus every running expense) fell
    short of break-even; 0 when the period was actually profitable. Deriving
    this from net_profit_minor rather than expenses-vs-revenue directly is
    deliberate: an earlier version compared expenses to revenue without
    subtracting cost of goods sold, so it could read "profitable" in a period
    that was really a loss once COGS was accounted for — exactly the number a
    food/drink business can least afford to get wrong. Never includes owner
    capital contributions; those are investment, not burn.
    """
    aov_minor = net_revenue_minor // orders_count if orders_count > 0 else 0
    mrr_minor = active_membership_monthly_equivalent_minor
    arr_minor = mrr_minor * 12
    cac_minor = (
        marketing_spend_minor // new_customers_count if new_customers_count > 0 else None
    )
    ltv_minor = total_customer_spend_minor // customers_count if customers_count > 0 else 0
    burn_rate_minor = max(0, -net_profit_minor)
    return BusinessMetrics(
        aov_minor=aov_minor,
        orders_count=orders_count,
        mrr_minor=mrr_minor,
        arr_minor=arr_minor,
        active_members_count=active_members_count,
        cac_minor=cac_minor,
        new_customers_count=new_customers_count,
        marketing_spend_minor=marketing_spend_minor,
        ltv_minor=ltv_minor,
        customers_count=customers_count,
        burn_rate_minor=burn_rate_minor,
    )


@dataclass(frozen=True, slots=True)
class DistributableCapacity:
    reserve_minor: int
    profit_based_capacity_minor: int
    cash_based_capacity_minor: int
    safe_to_distribute_minor: int


def compute_distributable_capacity(
    *,
    lifetime_net_profit_minor: int,
    lifetime_withdrawn_minor: int,
    avg_monthly_cost_minor: int,
    reserve_months: int,
    liquid_cash_minor: int,
) -> DistributableCapacity:
    """How much partners can safely take out right now, all-time — not just
    one period's paper profit.

    Two independent, deliberately separate caps:
      - profit-based: all-time real profit (already net of cost of goods sold
        and every running expense) minus everything ever withdrawn minus a
        reserve. Capital contributed is never subtracted here — it was never
        counted as available in the first place, since capital injections
        never enter net profit on either side of the ledger.
      - cash-based: actual liquid cash on hand minus the same reserve. Exists
        because profit on paper and cash in the bank are not the same thing
        once money is tied up in stock or equipment — a partner should never
        be told to withdraw money that doesn't actually exist as spendable
        cash right now.

    The safe number is whichever cap is smaller, floored at 0 — never a
    negative "you can take out less than nothing."
    """
    reserve_minor = avg_monthly_cost_minor * reserve_months
    profit_based_capacity_minor = (
        lifetime_net_profit_minor - lifetime_withdrawn_minor - reserve_minor
    )
    cash_based_capacity_minor = liquid_cash_minor - reserve_minor
    safe_to_distribute_minor = max(
        0, min(profit_based_capacity_minor, cash_based_capacity_minor)
    )
    return DistributableCapacity(
        reserve_minor=reserve_minor,
        profit_based_capacity_minor=profit_based_capacity_minor,
        cash_based_capacity_minor=cash_based_capacity_minor,
        safe_to_distribute_minor=safe_to_distribute_minor,
    )

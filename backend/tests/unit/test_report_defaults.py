from datetime import date

from app.api.v1.reports.router import _current_indian_fiscal_period
from app.services.reports.aggregator import PaymentBreakdown, PnLReport


def test_current_indian_fiscal_period_after_april() -> None:
    assert _current_indian_fiscal_period(date(2026, 7, 11)) == ("2026-27", 2)


def test_current_indian_fiscal_period_before_april() -> None:
    assert _current_indian_fiscal_period(date(2027, 2, 1)) == ("2026-27", 4)


def test_report_net_payment_movement_deducts_refunds() -> None:
    report = object.__new__(PnLReport)
    object.__setattr__(report, "payments_received", PaymentBreakdown(cash_minor=18_000))
    object.__setattr__(report, "refunds_issued_minor", 18_000)

    assert report.net_payments_received_minor == 0

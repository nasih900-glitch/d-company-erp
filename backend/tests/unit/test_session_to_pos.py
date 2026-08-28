"""Regression tests for gaming/shisha "Send to POS" GST-inclusive billing.

`price_time_based_line` only needs Company/Branch lookups. A tiny fake async
session keeps these billing-math tests deterministic and independent of a
developer's local PostgreSQL service.
"""

from __future__ import annotations

from decimal import Decimal
from uuid import uuid4

import pytest

from app.core.errors import BusinessRuleError
from app.models import Branch, Company
from app.services.pos.pricing import MembershipDiscountRates, OrderPricingService


class FakeSession:
    def __init__(self, *rows) -> None:
        self.rows = {(type(row), row.id): row for row in rows}

    async def get(self, model, row_id):
        return self.rows.get((model, row_id))


@pytest.fixture
def regular_branch() -> tuple[FakeSession, Branch]:
    company = Company(
        id=uuid4(), name="TestCo", gst_registration_type="regular",
        is_composition=False, gstin="32ABCDE1234F1Z5",
    )
    branch = Branch(
        id=uuid4(), company_id=company.id, name="Main",
        invoice_series_code="MN", state_code="32",
    )
    return FakeSession(company, branch), branch


@pytest.fixture
def unregistered_branch() -> tuple[FakeSession, Branch]:
    company = Company(
        id=uuid4(), name="TestCo", gst_registration_type="unregistered",
        is_composition=False,
    )
    branch = Branch(
        id=uuid4(), company_id=company.id, name="Main",
        invoice_series_code="MN", state_code="32",
    )
    return FakeSession(company, branch), branch


@pytest.fixture
def composition_branch() -> tuple[FakeSession, Branch]:
    company = Company(
        id=uuid4(), name="TestCo", gst_registration_type="composition",
        is_composition=True, gstin="32ABCDE1234F1Z5",
    )
    branch = Branch(
        id=uuid4(), company_id=company.id, name="Main",
        invoice_series_code="MN", state_code="32",
    )
    return FakeSession(company, branch), branch


class TestPriceTimeBasedLine:
    async def test_regular_supplier_splits_gst_inclusive(
        self, regular_branch: tuple[FakeSession, Branch]
    ) -> None:
        session, branch = regular_branch
        pricing = OrderPricingService(session)
        # 47 minutes @ ₹200/hr = ceil(47/60*20000) = 15667 paise, GST-inclusive at 18%.
        priced = await pricing.price_time_based_line(
            company_id=branch.company_id,
            branch_id=branch.id,
            amount_minor=15667,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
        )
        assert priced.total_minor == 15667
        assert priced.taxable_minor + priced.cgst_minor + priced.sgst_minor == 15667
        assert priced.igst_minor == 0
        assert abs(priced.cgst_minor - priced.sgst_minor) <= 1
        assert priced.discount_minor == 0

    async def test_active_member_discount_reduces_session_before_gst_split(
        self, regular_branch: tuple[FakeSession, Branch]
    ) -> None:
        session, branch = regular_branch

        class MemberPricing(OrderPricingService):
            async def _membership_discount_rates(self, **_kwargs):
                return MembershipDiscountRates(gaming=Decimal("0.20"))

        priced = await MemberPricing(session).price_time_based_line(
            company_id=branch.company_id,
            branch_id=branch.id,
            amount_minor=20_000,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
            customer_phone="9000000000",
            item_type="gaming",
        )

        assert priced.discount_minor == 4_000
        assert priced.total_minor == 16_000
        assert priced.taxable_minor + priced.cgst_minor + priced.sgst_minor == 16_000

    async def test_unregistered_supplier_collects_zero_gst(
        self, unregistered_branch: tuple[FakeSession, Branch]
    ) -> None:
        session, branch = unregistered_branch
        pricing = OrderPricingService(session)
        priced = await pricing.price_time_based_line(
            company_id=branch.company_id,
            branch_id=branch.id,
            amount_minor=15667,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
        )
        assert priced.total_minor == 15667
        assert priced.taxable_minor == 15667
        assert (priced.cgst_minor, priced.sgst_minor, priced.igst_minor) == (0, 0, 0)

    async def test_composition_supplier_collects_zero_gst(
        self, composition_branch: tuple[FakeSession, Branch]
    ) -> None:
        session, branch = composition_branch
        pricing = OrderPricingService(session)
        priced = await pricing.price_time_based_line(
            company_id=branch.company_id,
            branch_id=branch.id,
            amount_minor=15667,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
        )
        assert (priced.cgst_minor, priced.sgst_minor, priced.igst_minor) == (0, 0, 0)

    async def test_exclusive_rate_grosses_up_the_total(
        self, regular_branch: tuple[FakeSession, Branch]
    ) -> None:
        session, branch = regular_branch
        pricing = OrderPricingService(session)
        priced = await pricing.price_time_based_line(
            company_id=branch.company_id,
            branch_id=branch.id,
            amount_minor=10000,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=False,
        )
        # Exclusive base ₹100 + 18% = ₹118 charged, not ₹100.
        assert priced.taxable_minor == 10000
        assert priced.total_minor == 11800
        assert priced.cgst_minor + priced.sgst_minor == 1800

    async def test_unknown_company_raises_not_found(self) -> None:
        from app.core.errors import NotFoundError

        pricing = OrderPricingService(FakeSession())
        with pytest.raises(NotFoundError):
            await pricing.price_time_based_line(
                company_id=uuid4(),
                branch_id=uuid4(),
                amount_minor=1000,
                tax_rate=Decimal("0.18"),
                rate_includes_tax=True,
            )

    async def test_registered_supplier_without_gstin_is_blocked(
        self,
    ) -> None:
        company = Company(
            id=uuid4(), name="TestCo", gst_registration_type="regular", is_composition=False,
        )
        branch = Branch(
            id=uuid4(), company_id=company.id, name="Main",
            invoice_series_code="MN", state_code="32",
        )
        session = FakeSession(company, branch)
        pricing = OrderPricingService(session)
        with pytest.raises(BusinessRuleError, match="GSTIN is missing or invalid"):
            await pricing.price_time_based_line(
                company_id=company.id,
                branch_id=branch.id,
                amount_minor=1000,
                tax_rate=Decimal("0.18"),
                rate_includes_tax=True,
            )

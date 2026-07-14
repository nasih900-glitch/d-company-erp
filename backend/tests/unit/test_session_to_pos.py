"""Regression tests for gaming/shisha "Send to POS" GST-inclusive billing.

price_time_based_line needs Company/Branch rows, so these run against the
real test DB via the shared `session` fixture (same pattern as the other
DB-touching unit tests in this package).
"""

from __future__ import annotations

from decimal import Decimal
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessRuleError
from app.models import Branch, Company
from app.services.pos.pricing import OrderPricingService


@pytest_asyncio.fixture
async def regular_branch(session: AsyncSession) -> Branch:
    company = Company(
        id=uuid4(), name="TestCo", gst_registration_type="regular",
        is_composition=False, gstin="32ABCDE1234F1Z5",
    )
    branch = Branch(id=uuid4(), company_id=company.id, name="Main", state_code="32")
    session.add_all([company, branch])
    await session.flush()
    return branch


@pytest_asyncio.fixture
async def unregistered_branch(session: AsyncSession) -> Branch:
    company = Company(
        id=uuid4(), name="TestCo", gst_registration_type="unregistered",
        is_composition=False,
    )
    branch = Branch(id=uuid4(), company_id=company.id, name="Main", state_code="32")
    session.add_all([company, branch])
    await session.flush()
    return branch


@pytest_asyncio.fixture
async def composition_branch(session: AsyncSession) -> Branch:
    company = Company(
        id=uuid4(), name="TestCo", gst_registration_type="composition",
        is_composition=True, gstin="32ABCDE1234F1Z5",
    )
    branch = Branch(id=uuid4(), company_id=company.id, name="Main", state_code="32")
    session.add_all([company, branch])
    await session.flush()
    return branch


class TestPriceTimeBasedLine:
    async def test_regular_supplier_splits_gst_inclusive(
        self, session: AsyncSession, regular_branch: Branch
    ) -> None:
        pricing = OrderPricingService(session)
        # 47 minutes @ ₹200/hr = ceil(47/60*20000) = 15667 paise, GST-inclusive at 18%.
        priced = await pricing.price_time_based_line(
            company_id=regular_branch.company_id,
            branch_id=regular_branch.id,
            amount_minor=15667,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
        )
        assert priced.total_minor == 15667
        assert priced.taxable_minor + priced.cgst_minor + priced.sgst_minor == 15667
        assert priced.igst_minor == 0
        assert abs(priced.cgst_minor - priced.sgst_minor) <= 1

    async def test_unregistered_supplier_collects_zero_gst(
        self, session: AsyncSession, unregistered_branch: Branch
    ) -> None:
        pricing = OrderPricingService(session)
        priced = await pricing.price_time_based_line(
            company_id=unregistered_branch.company_id,
            branch_id=unregistered_branch.id,
            amount_minor=15667,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
        )
        assert priced.total_minor == 15667
        assert priced.taxable_minor == 15667
        assert (priced.cgst_minor, priced.sgst_minor, priced.igst_minor) == (0, 0, 0)

    async def test_composition_supplier_collects_zero_gst(
        self, session: AsyncSession, composition_branch: Branch
    ) -> None:
        pricing = OrderPricingService(session)
        priced = await pricing.price_time_based_line(
            company_id=composition_branch.company_id,
            branch_id=composition_branch.id,
            amount_minor=15667,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=True,
        )
        assert (priced.cgst_minor, priced.sgst_minor, priced.igst_minor) == (0, 0, 0)

    async def test_exclusive_rate_grosses_up_the_total(
        self, session: AsyncSession, regular_branch: Branch
    ) -> None:
        pricing = OrderPricingService(session)
        priced = await pricing.price_time_based_line(
            company_id=regular_branch.company_id,
            branch_id=regular_branch.id,
            amount_minor=10000,
            tax_rate=Decimal("0.18"),
            rate_includes_tax=False,
        )
        # Exclusive base ₹100 + 18% = ₹118 charged, not ₹100.
        assert priced.taxable_minor == 10000
        assert priced.total_minor == 11800
        assert priced.cgst_minor + priced.sgst_minor == 1800

    async def test_unknown_company_raises_not_found(self, session: AsyncSession) -> None:
        from app.core.errors import NotFoundError

        pricing = OrderPricingService(session)
        with pytest.raises(NotFoundError):
            await pricing.price_time_based_line(
                company_id=uuid4(),
                branch_id=uuid4(),
                amount_minor=1000,
                tax_rate=Decimal("0.18"),
                rate_includes_tax=True,
            )

    async def test_registered_supplier_without_gstin_is_blocked(
        self, session: AsyncSession
    ) -> None:
        company = Company(
            id=uuid4(), name="TestCo", gst_registration_type="regular", is_composition=False,
        )
        branch = Branch(id=uuid4(), company_id=company.id, name="Main", state_code="32")
        session.add_all([company, branch])
        await session.flush()

        pricing = OrderPricingService(session)
        with pytest.raises(BusinessRuleError, match="GSTIN is missing or invalid"):
            await pricing.price_time_based_line(
                company_id=company.id,
                branch_id=branch.id,
                amount_minor=1000,
                tax_rate=Decimal("0.18"),
                rate_includes_tax=True,
            )

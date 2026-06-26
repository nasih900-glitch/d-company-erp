"""India-specific reference tables.

These hold relatively static reference data: state codes, GST rate slabs,
HSN/SAC catalogue, professional-tax slabs. Loaded once via seed script;
the rest of the application reads from them.

Kept in a separate module to make it obvious which tables are India-coupled.
A future multi-country build replaces this module with a country/<code>/
sub-package.
"""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import (
    BigInteger,
    ForeignKey,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, _uuid_pk


class StateCode(Base, TimestampMixin):
    """The 36 Indian state + UT codes used in GSTIN and place-of-supply.

    Kerala = '32'. Codes are zero-padded 2-char strings to preserve the
    leading zero on states like '01' (Jammu & Kashmir).
    """

    __tablename__ = "in_state_codes"

    code: Mapped[str] = mapped_column(String(2), primary_key=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    is_union_territory: Mapped[bool] = mapped_column(default=False, nullable=False)
    # Special category state for GST (NE states + Himachal + Uttarakhand).
    # If true, GST registration threshold is ₹10 lakh instead of ₹20 lakh.
    is_special_category: Mapped[bool] = mapped_column(default=False, nullable=False)


class GstRateSlab(Base, TimestampMixin):
    """Canonical GST rate slabs. Headline rate; CGST + SGST = headline ÷ 2.

    Single source of truth for the UI dropdown and the tax engine.
    Inserted in seed and rarely changes (last change: October 2023 for gaming).
    """

    __tablename__ = "in_gst_rate_slabs"
    __table_args__ = (UniqueConstraint("rate", name="uq_gst_rate"),)

    id: Mapped[UUID] = _uuid_pk()
    # Stored as a decimal: 0.05 = 5%, 0.18 = 18%, 0.28 = 28%.
    rate: Mapped[float] = mapped_column(Numeric(6, 4), nullable=False)
    label: Mapped[str] = mapped_column(String(20), nullable=False)  # "5%", "18%", "Exempt"
    description: Mapped[str | None] = mapped_column(String(500))


class HsnCode(Base, TimestampMixin):
    """HSN catalogue subset relevant to a café + gaming lounge.

    Stored as the 6-digit subheading (e.g. '090121' for roasted coffee).
    Display rules: < ₹1.5 cr turnover = none on bill, 2-digit at ₹1.5-5 cr,
    4-digit > ₹5 cr, 6-digit if e-invoicing.
    """

    __tablename__ = "in_hsn_codes"

    code: Mapped[str] = mapped_column(String(8), primary_key=True)
    description: Mapped[str] = mapped_column(String(500), nullable=False)
    default_gst_rate: Mapped[float] = mapped_column(Numeric(6, 4), nullable=False)
    chapter: Mapped[str] = mapped_column(String(2), nullable=False)  # first two digits
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)


class SacCode(Base, TimestampMixin):
    """SAC catalogue subset relevant to a café + gaming lounge."""

    __tablename__ = "in_sac_codes"

    code: Mapped[str] = mapped_column(String(8), primary_key=True)
    description: Mapped[str] = mapped_column(String(500), nullable=False)
    default_gst_rate: Mapped[float] = mapped_column(Numeric(6, 4), nullable=False)
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)


class InvoiceCounter(Base, TimestampMixin):
    """Per-branch per-fiscal-year per-series invoice number sequence.

    Atomic increment with row-level lock guarantees no-gaps, no-duplicates.
    NEVER reset within a fiscal year, even for voided invoices (the void
    keeps its number).

    A separate row per (branch_id, fiscal_year, series). 'series' lets us
    issue different sequences for e.g. tax invoices vs delivery challans vs
    credit notes.
    """

    __tablename__ = "in_invoice_counters"
    __table_args__ = (
        UniqueConstraint(
            "branch_id", "fiscal_year", "series", name="uq_inv_counter_branch_fy_series"
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    fiscal_year: Mapped[str] = mapped_column(String(7), nullable=False)  # "2026-27"
    series: Mapped[str] = mapped_column(String(20), nullable=False, default="invoice")
    # Last allocated sequence; next allocation is +1.
    last_seq: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)


class KeralaPTSlab(Base, TimestampMixin):
    """Kerala professional-tax half-yearly slabs.

    Local-body specific (Panchayat / Municipality / Corporation), but
    slabs are uniform across most Kerala local bodies as of FY 2026-27.
    Stored in INR (not minor units) because the law itself is stated in
    whole rupees.
    """

    __tablename__ = "in_kerala_pt_slabs"

    id: Mapped[UUID] = _uuid_pk()
    min_half_year_salary: Mapped[int] = mapped_column(Integer, nullable=False)
    max_half_year_salary: Mapped[int | None] = mapped_column(Integer)  # NULL = no upper bound
    half_year_pt: Mapped[int] = mapped_column(Integer, nullable=False)  # rupees per half-year
    effective_from: Mapped[str] = mapped_column(String(10), nullable=False)  # YYYY-MM-DD
    effective_to: Mapped[str | None] = mapped_column(String(10))

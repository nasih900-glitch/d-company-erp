"""Tenant tables: companies, branches, terminals."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, SoftDeleteMixin, TimestampMixin, _uuid_pk


class Company(Base, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "companies"

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    legal_name: Mapped[str | None] = mapped_column(String(200))
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="INR")
    currency_minor_units: Mapped[int] = mapped_column(default=100, nullable=False)
    timezone: Mapped[str] = mapped_column(String(64), nullable=False, default="Asia/Kolkata")
    country: Mapped[str | None] = mapped_column(String(2))
    # ----- India / GST identity -----
    # GSTIN format: 15 chars = [state_code(2)][PAN(10)][entity(1)][Z][checksum(1)]
    gstin: Mapped[str | None] = mapped_column(String(15))
    pan: Mapped[str | None] = mapped_column(String(10))
    gst_registration_type: Mapped[str] = mapped_column(
        String(20), nullable=False, default="regular"
    )  # regular | composition | unregistered
    # When True, the tax engine emits a "Bill of Supply" and owner pays 5% on
    # turnover instead of CGST/SGST on each line. Mirrors gst_registration_type
    # but kept explicit for readability.
    is_composition: Mapped[bool] = mapped_column(default=False, nullable=False)
    # E-invoicing kicks in at ₹5 cr aggregate turnover (any FY since 2017-18).
    e_invoicing_enabled: Mapped[bool] = mapped_column(default=False, nullable=False)
    # Indian financial year starts 1 April. Stored as integer (4 = April).
    fiscal_year_start_month: Mapped[int] = mapped_column(default=4, nullable=False)
    # ----- Integrations -----
    # If set, every paid order pings this URL (Google Apps Script web app).
    # See docs/GOOGLE_SHEETS.md and integrations/google-sheets/Code.gs.
    google_sheets_webhook_url: Mapped[str | None] = mapped_column(String(500))

    branches: Mapped[list["Branch"]] = relationship(
        back_populates="company", cascade="all, delete-orphan"
    )


class Branch(Base, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "branches"

    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("companies.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    code: Mapped[str | None] = mapped_column(String(10))  # short branch code for invoice numbering (e.g. "MN")
    address: Mapped[str | None] = mapped_column(String(500))
    timezone: Mapped[str | None] = mapped_column(String(64))
    opens_at: Mapped[str | None] = mapped_column(String(8))   # "09:00"
    closes_at: Mapped[str | None] = mapped_column(String(8))  # "23:30"
    # ----- India / Kerala compliance -----
    # State code per GST classification. Kerala = "32".
    # Used to derive place-of-supply and decide CGST+SGST vs IGST.
    state_code: Mapped[str | None] = mapped_column(String(2))
    # FSSAI licence — 14 digits. MUST appear on every food bill.
    fssai_license_no: Mapped[str | None] = mapped_column(String(14))
    # Local body trade licence (municipality / corporation issued). Annual renewal.
    trade_license_no: Mapped[str | None] = mapped_column(String(50))
    # Branch-specific GSTIN if registered separately (multi-state setup).
    branch_gstin: Mapped[str | None] = mapped_column(String(15))

    company: Mapped[Company] = relationship(back_populates="branches")
    terminals: Mapped[list["Terminal"]] = relationship(
        back_populates="branch", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("company_id", "name", name="uq_branch_name_per_company"),
    )


class Terminal(Base, TimestampMixin):
    __tablename__ = "terminals"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    device_id: Mapped[str | None] = mapped_column(String(100), unique=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    offline_seq_high_water: Mapped[int] = mapped_column(BigInteger, default=0)

    branch: Mapped[Branch] = relationship(back_populates="terminals")

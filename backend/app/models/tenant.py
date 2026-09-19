"""Tenant tables: companies, branches, terminals."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
    text,
)
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
    # Destination used only by the authenticated server-side outbox dispatcher.
    # Clients cannot write this through the generic company settings endpoint.
    google_sheets_webhook_url: Mapped[str | None] = mapped_column(String(500))
    # The Apps Script secret is read only by the dispatcher and the one-time
    # owner configuration response. It is never included in ordinary company
    # settings or mirror event payloads.
    google_sheets_mirror_enabled: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default=text("false"),
    )
    google_sheets_signing_secret_ciphertext: Mapped[str | None] = mapped_column(
        String(256)
    )
    google_sheets_configured_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    # Identifies one immutable delivery generation. A verified, drained
    # generation gets a new value when its destination or secret changes.
    # Corrections made before first verification retain the value so already
    # held facts are not abandoned; configured_at invalidates earlier tests.
    google_sheets_configuration_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True)
    )

    # Merchant UPI VPA (e.g. "Q530001220@ybl") used to build the dynamic
    # UPI pay QR shown at checkout. Public payment address, not a secret.
    upi_vpa: Mapped[str | None] = mapped_column(String(255))

    # Reserved for a future verified gateway integration. The current release
    # does not expose an API write path for these fields: secrets must not be
    # accepted until encrypted storage and provider-side verification exist.
    payment_provider: Mapped[str | None] = mapped_column(String(50))
    payment_key_id: Mapped[str | None] = mapped_column(String(255))
    payment_key_secret: Mapped[str | None] = mapped_column(String(512))

    __table_args__ = (
        CheckConstraint(
            "(google_sheets_mirror_enabled = false) OR "
            "(google_sheets_webhook_url IS NOT NULL "
            "AND length(trim(google_sheets_webhook_url)) > 0 "
            "AND google_sheets_signing_secret_ciphertext IS NOT NULL "
            "AND length(google_sheets_signing_secret_ciphertext) > 0 "
            "AND google_sheets_configured_at IS NOT NULL "
            "AND google_sheets_configuration_id IS NOT NULL)",
            name="ck_company_google_sheets_mirror_config",
        ),
    )

    branches: Mapped[list[Branch]] = relationship(
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
    # Operational/display code. Invoice identity is deliberately separate:
    # this value may be long or edited without silently starting a new fiscal
    # receipt series.
    code: Mapped[str | None] = mapped_column(String(10))
    # Explicit two-character fiscal document namespace. It is unique only
    # inside a company because invoice numbers belong to that legal tenant,
    # not to the shared multi-tenant database as a whole.
    invoice_series_code: Mapped[str] = mapped_column(String(2), nullable=False)
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
    terminals: Mapped[list[Terminal]] = relationship(
        back_populates="branch", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("company_id", "name", name="uq_branch_name_per_company"),
        UniqueConstraint(
            "company_id",
            "invoice_series_code",
            name="uq_branch_invoice_series_per_company",
        ),
        CheckConstraint(
            "invoice_series_code ~ '^[A-Z0-9]{2}$'",
            name="ck_branch_invoice_series_code_format",
        ),
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
    # Explicit operational capability. The active one-shop workspace is
    # always ``hybrid`` so Gaming, POS, and Shift share one authoritative
    # identity. Legacy split-purpose rows remain valid only while inactive so
    # their historical shifts, orders, and audit references stay intact.
    purpose: Mapped[str] = mapped_column(
        String(20), nullable=False, default="hybrid", server_default="hybrid"
    )
    # Historical till identities are accounting/audit evidence and must not be
    # hard-deleted.  Inactive terminals remain referenceable by old shifts and
    # orders but are excluded from new device assignment and operational writes.
    is_active: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True, server_default="true"
    )
    device_id: Mapped[str | None] = mapped_column(String(100), unique=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    offline_seq_high_water: Mapped[int] = mapped_column(BigInteger, default=0)

    branch: Mapped[Branch] = relationship(back_populates="terminals")

    __table_args__ = (
        Index("ix_terminals_branch_active", "branch_id", "is_active"),
        Index(
            "uq_terminals_one_active_per_branch",
            "branch_id",
            unique=True,
            postgresql_where=text("is_active IS TRUE"),
            sqlite_where=text("is_active = 1"),
        ),
        CheckConstraint(
            "purpose IN ('hybrid', 'cafe_pos', 'gaming')",
            name="ck_terminals_purpose",
        ),
        CheckConstraint(
            "is_active IS FALSE OR purpose = 'hybrid'",
            name="ck_terminals_active_requires_hybrid",
        ),
    )

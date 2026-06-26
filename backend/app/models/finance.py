"""Finance module models: chart of accounts, journal, expenses, partners, assets."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Account(Base, TimestampMixin, TenantMixin):
    """Chart of accounts entry."""

    __tablename__ = "accounts"
    __table_args__ = (UniqueConstraint("company_id", "code", name="uq_account_code_per_company"),)

    id: Mapped[UUID] = _uuid_pk()
    parent_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("accounts.id", ondelete="SET NULL")
    )
    code: Mapped[str] = mapped_column(String(20), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # asset|liability|equity|revenue|expense
    normal_side: Mapped[str] = mapped_column(String(2), nullable=False)  # dr|cr
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)


class JournalEntry(Base, TimestampMixin, TenantMixin):
    __tablename__ = "journal_entries"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="SET NULL"), index=True
    )
    ref_type: Mapped[str] = mapped_column(String(50), nullable=False, index=True)  # order|payment|refund|expense|capital
    ref_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False, index=True)
    posted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    memo: Mapped[str | None] = mapped_column(String(500))
    total_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)


class JournalLine(Base, TimestampMixin):
    __tablename__ = "journal_lines"

    id: Mapped[UUID] = _uuid_pk()
    journal_entry_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("journal_entries.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    account_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("accounts.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    side: Mapped[str] = mapped_column(String(2), nullable=False)  # dr|cr
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    memo: Mapped[str | None] = mapped_column(String(500))


class Partner(Base, TimestampMixin, TenantMixin):
    __tablename__ = "partners"

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    share_pct: Mapped[float] = mapped_column(Numeric(7, 4), nullable=False)
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))


class CapitalEntry(Base, TimestampMixin):
    __tablename__ = "capital_entries"

    id: Mapped[UUID] = _uuid_pk()
    partner_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("partners.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # invest|withdraw|profit_share
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    effective_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))


class ExpenseCategory(Base, TimestampMixin, TenantMixin):
    __tablename__ = "expense_categories"

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    gl_account_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("accounts.id", ondelete="SET NULL")
    )


class Expense(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "expenses"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    category_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("expense_categories.id", ondelete="RESTRICT"), nullable=False
    )
    supplier_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("suppliers.id", ondelete="SET NULL")
    )
    ocr_extraction_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ocr_extractions.id", ondelete="SET NULL")
    )
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    paid_via: Mapped[str] = mapped_column(String(20), nullable=False)  # cash|card|bank|upi
    paid_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    vendor_name: Mapped[str | None] = mapped_column(String(200))
    invoice_no: Mapped[str | None] = mapped_column(String(100))
    note: Mapped[str | None] = mapped_column(String(500))


class Asset(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "assets"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    type: Mapped[str] = mapped_column(String(50), nullable=False)  # ps5|tv|coffee_machine|projector|furniture|...
    purchase_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    purchase_date: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    depreciation_method: Mapped[str] = mapped_column(String(20), default="straight_line")
    useful_life_months: Mapped[int] = mapped_column(default=60)
    salvage_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    notes: Mapped[str | None] = mapped_column(String(500))

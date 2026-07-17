"""Finance module models: chart of accounts, journal, expenses, partners, assets."""

from __future__ import annotations

from datetime import date, datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Numeric,
    String,
    UniqueConstraint,
    event,
    func,
    inspect,
)
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
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))


@event.listens_for(JournalEntry, "before_update")
def _guard_journal_entry_update(_mapper, _connection, row: JournalEntry) -> None:
    """A posted journal entry is append-only; only the initial void is mutable."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "branch_id",
        "ref_type",
        "ref_id",
        "posted_at",
        "memo",
        "total_minor",
        "created_at",
    }
    changed_immutable = sorted(
        field
        for field in immutable_fields
        if state.attrs[field].history.has_changes()
    )
    if changed_immutable:
        raise ValueError(
            "journal entry financial/provenance fields are immutable: "
            + ", ".join(changed_immutable)
        )

    for field in ("voided_at", "voided_by", "void_reason"):
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("a journal entry void cannot be changed or reversed")
    void_changed = any(
        state.attrs[field].history.has_changes()
        for field in ("voided_at", "voided_by", "void_reason")
    )
    if void_changed and (
        row.voided_at is None
        or row.voided_by is None
        or row.void_reason is None
        or len(row.void_reason.strip()) < 3
    ):
        raise ValueError("a journal entry void must be populated atomically")


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
    __table_args__ = (
        CheckConstraint(
            "amount_minor > 0",
            name="ck_capital_entry_positive_amount",
        ),
        CheckConstraint(
            "settlement_account IN ('cash', 'bank', 'upi', 'historical_funds')",
            name="ck_capital_entry_settlement_account",
        ),
        CheckConstraint(
            "source_ref IS NULL OR length(trim(source_ref)) > 0",
            name="ck_capital_entry_source_ref_present",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3)",
            name="ck_capital_entry_void_state",
        ),
        UniqueConstraint("source_ref", name="uq_capital_entry_source_ref"),
    )

    id: Mapped[UUID] = _uuid_pk()
    partner_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("partners.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # invest|withdraw
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    effective_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))
    settlement_account: Mapped[str] = mapped_column(
        String(30), nullable=False, default="bank", server_default="bank"
    )
    source_ref: Mapped[str | None] = mapped_column(String(160))
    created_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    voided_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), index=True
    )
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))


@event.listens_for(CapitalEntry, "before_update")
def _guard_capital_entry_update(_mapper, _connection, row: CapitalEntry) -> None:
    """Capital facts are append-only; only the initial void is mutable."""
    state = inspect(row)
    immutable_fields = {
        "partner_id",
        "type",
        "amount_minor",
        "effective_at",
        "note",
        "settlement_account",
        "source_ref",
        "created_by",
        "created_at",
    }
    changed_immutable = sorted(
        field
        for field in immutable_fields
        if state.attrs[field].history.has_changes()
    )
    if changed_immutable:
        raise ValueError(
            "capital entry financial/provenance fields are immutable: "
            + ", ".join(changed_immutable)
        )

    for field in ("voided_at", "voided_by", "void_reason"):
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("a capital entry void cannot be changed or reversed")
    void_changed = any(
        state.attrs[field].history.has_changes()
        for field in ("voided_at", "voided_by", "void_reason")
    )
    if void_changed and (
        row.voided_at is None
        or row.voided_by is None
        or row.void_reason is None
        or len(row.void_reason.strip()) < 3
    ):
        raise ValueError("a capital entry void must be populated atomically")


class ExpenseCategory(Base, TimestampMixin, TenantMixin):
    __tablename__ = "expense_categories"
    __table_args__ = (
        UniqueConstraint(
            "company_id", "code", name="uq_expense_category_code_per_company"
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    code: Mapped[str | None] = mapped_column(String(20))
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


class ManualCollection(Base, TenantMixin):
    """Auditable revenue received outside the itemized POS workflow.

    These rows deliberately do not reference a shift, order, invoice,
    customer, inventory movement, or loyalty record.  They exist for legacy
    daily totals and exceptional collections where only the payment-method
    total is known.  Corrections are represented by a void plus a replacement
    row; the original amount and provenance are never overwritten.
    """

    __tablename__ = "manual_collections"
    __table_args__ = (
        CheckConstraint(
            "method IN ('cash', 'upi', 'card', 'bank')",
            name="ck_manual_collection_method",
        ),
        CheckConstraint(
            "amount_minor > 0",
            name="ck_manual_collection_positive_amount",
        ),
        CheckConstraint(
            "source_kind IN ('manual_daily', 'legacy_daily')",
            name="ck_manual_collection_source_kind",
        ),
        CheckConstraint(
            "length(trim(source_ref)) > 0",
            name="ck_manual_collection_source_ref_present",
        ),
        CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_manual_collection_idempotency_key_present",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3)",
            name="ck_manual_collection_void_state",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_manual_collection_company_idempotency",
        ),
        UniqueConstraint(
            "company_id",
            "branch_id",
            "source_kind",
            "source_ref",
            "method",
            name="uq_manual_collection_source_method",
        ),
        Index(
            "ix_manual_collection_company_business_date",
            "company_id",
            "business_date",
        ),
        Index(
            "ix_manual_collection_branch_business_date",
            "branch_id",
            "business_date",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    business_date: Mapped[date] = mapped_column(Date, nullable=False)
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    source_kind: Mapped[str] = mapped_column(
        String(30), nullable=False, default="manual_daily", server_default="manual_daily"
    )
    source_ref: Mapped[str] = mapped_column(String(160), nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    created_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))


@event.listens_for(ManualCollection, "before_update")
def _guard_manual_collection_update(_mapper, _connection, row: ManualCollection) -> None:
    """Only permit the first one-way transition from active to voided."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "branch_id",
        "business_date",
        "method",
        "amount_minor",
        "source_kind",
        "source_ref",
        "note",
        "idempotency_key",
        "created_by",
        "created_at",
    }
    changed_immutable = sorted(
        field
        for field in immutable_fields
        if state.attrs[field].history.has_changes()
    )
    if changed_immutable:
        raise ValueError(
            "manual collection financial/provenance fields are immutable: "
            + ", ".join(changed_immutable)
        )

    for field in ("voided_at", "voided_by", "void_reason"):
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("a manual collection void cannot be changed or reversed")
    void_changed = any(
        state.attrs[field].history.has_changes()
        for field in ("voided_at", "voided_by", "void_reason")
    )
    if void_changed and (
        row.voided_at is None
        or row.voided_by is None
        or row.void_reason is None
        or len(row.void_reason.strip()) < 3
    ):
        raise ValueError("a manual collection void must be populated atomically")


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

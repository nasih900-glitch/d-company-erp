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
    SmallInteger,
    String,
    UniqueConstraint,
    event,
    func,
    inspect,
    text,
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


@event.listens_for(Account, "before_update")
def _guard_account_identity_update(_mapper, _connection, row: Account) -> None:
    """An account's canonical identity must not rewrite historical journals."""
    state = inspect(row)
    immutable_fields = {"company_id", "code", "name", "type", "normal_side", "created_at"}
    changed = sorted(
        field for field in immutable_fields if state.attrs[field].history.has_changes()
    )
    if changed:
        raise ValueError(
            "account financial identity is immutable: " + ", ".join(changed)
        )


@event.listens_for(Account, "before_delete")
def _guard_account_delete(_mapper, _connection, _row: Account) -> None:
    raise ValueError("accounts are financial reference data and cannot be deleted")


class JournalEntry(Base, TimestampMixin, TenantMixin):
    __tablename__ = "journal_entries"
    __table_args__ = (
        Index(
            "uq_journal_entries_purchase_source",
            "company_id",
            "ref_type",
            "ref_id",
            unique=True,
            postgresql_where=text(
                "ref_type IN ('grn_receipt', 'supplier_payment')"
            ),
        ),
    )

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


@event.listens_for(JournalEntry, "before_delete")
def _guard_journal_entry_delete(_mapper, _connection, _row: JournalEntry) -> None:
    raise ValueError("posted journal entries are immutable and cannot be deleted")


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


@event.listens_for(JournalLine, "before_update")
def _guard_journal_line_update(_mapper, _connection, _row: JournalLine) -> None:
    raise ValueError("posted journal lines are immutable")


@event.listens_for(JournalLine, "before_delete")
def _guard_journal_line_delete(_mapper, _connection, _row: JournalLine) -> None:
    raise ValueError("posted journal lines are immutable and cannot be deleted")


class Partner(Base, TimestampMixin, TenantMixin):
    __tablename__ = "partners"
    __table_args__ = (
        CheckConstraint(
            "share_pct IS NOT NULL AND share_pct > 0 AND share_pct <= 100",
            name="ck_partner_share_pct_range",
        ),
        CheckConstraint(
            "name IS NOT NULL AND length(trim(name)) > 0",
            name="ck_partner_name_present",
        ),
        CheckConstraint(
            "source_integrity_revision IS NULL OR source_integrity_revision = 50",
            name="ck_partner_source_integrity_revision",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    share_pct: Mapped[float] = mapped_column(Numeric(7, 4), nullable=False)
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))
    source_integrity_revision: Mapped[int | None] = mapped_column(
        SmallInteger,
        nullable=True,
        default=50,
        server_default="50",
    )


@event.listens_for(Partner, "before_update")
def _guard_partner_identity_update(_mapper, _connection, row: Partner) -> None:
    """Ownership facts are immutable until effective-dated agreements exist."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "user_id",
        "name",
        "share_pct",
        "joined_at",
        "created_at",
        "source_integrity_revision",
    }
    changed = sorted(
        field for field in immutable_fields if state.attrs[field].history.has_changes()
    )
    if changed:
        raise ValueError(
            "partner financial identity is immutable: " + ", ".join(changed)
        )


@event.listens_for(Partner, "before_delete")
def _guard_partner_delete(_mapper, _connection, _row: Partner) -> None:
    raise ValueError("partner ownership records are immutable and cannot be deleted")


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


@event.listens_for(CapitalEntry, "before_delete")
def _guard_capital_entry_delete(_mapper, _connection, _row: CapitalEntry) -> None:
    raise ValueError("capital entries are immutable and cannot be deleted")


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


@event.listens_for(ExpenseCategory, "before_update")
def _guard_expense_category_identity_update(
    _mapper, _connection, row: ExpenseCategory
) -> None:
    """Category identity is part of every referenced expense's accounting fact."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "name",
        "code",
        "gl_account_id",
        "created_at",
    }
    changed = sorted(
        field for field in immutable_fields if state.attrs[field].history.has_changes()
    )
    if changed:
        raise ValueError(
            "expense category accounting identity is immutable: "
            + ", ".join(changed)
        )


@event.listens_for(ExpenseCategory, "before_delete")
def _guard_expense_category_delete(
    _mapper, _connection, _row: ExpenseCategory
) -> None:
    raise ValueError("expense categories cannot be deleted")


class Expense(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "expenses"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_expense_positive_amount"),
        CheckConstraint(
            "paid_via IN ('cash', 'card', 'bank', 'upi')",
            name="ck_expense_payment_method",
        ),
        CheckConstraint(
            "source_integrity_revision IS NULL OR source_integrity_revision = 50",
            name="ck_expense_source_integrity_revision",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3)",
            name="ck_expense_void_state",
        ),
    )

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
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))
    source_integrity_revision: Mapped[int | None] = mapped_column(
        SmallInteger,
        nullable=True,
        default=50,
        server_default="50",
    )


@event.listens_for(Expense, "before_update")
def _guard_expense_update(_mapper, _connection, row: Expense) -> None:
    """Expense facts are append-only; only the first complete void is mutable."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "branch_id",
        "category_id",
        "supplier_id",
        "ocr_extraction_id",
        "amount_minor",
        "paid_via",
        "paid_at",
        "vendor_name",
        "invoice_no",
        "note",
        "created_at",
        "deleted_at",
    }
    changed = sorted(
        field for field in immutable_fields if state.attrs[field].history.has_changes()
    )
    if changed:
        raise ValueError(
            "expense financial/provenance fields are immutable: " + ", ".join(changed)
        )

    void_fields = ("voided_at", "voided_by", "void_reason")
    for field in void_fields:
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("an expense void cannot be changed or reversed")
    void_changed = any(state.attrs[field].history.has_changes() for field in void_fields)
    if void_changed and (
        row.voided_at is None
        or row.voided_by is None
        or row.void_reason is None
        or len(row.void_reason.strip()) < 3
    ):
        raise ValueError("an expense void must be populated atomically")


@event.listens_for(Expense, "before_delete")
def _guard_expense_delete(_mapper, _connection, _row: Expense) -> None:
    raise ValueError("expenses are immutable; void the expense instead")


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


@event.listens_for(ManualCollection, "before_delete")
def _guard_manual_collection_delete(
    _mapper, _connection, _row: ManualCollection
) -> None:
    raise ValueError("manual collections are immutable and cannot be deleted")


class TipPayout(Base, TenantMixin):
    """Money actually paid out to staff against the TIPS_PAYABLE liability.

    TIPS_PAYABLE (ledger.py) is credited whenever a tipped order is paid and
    debited on refund of a tipped order, but neither of those ever clears it
    back out — this is the only record that does.  Deliberately standalone:
    it does not model who on staff received what share, which shift they
    worked, or any roster/payroll link.  `note` is the only place that
    detail is captured (e.g. "split among staff on shift") until the full
    Payroll feature exists.  Corrections are represented by a void plus a
    replacement row; the original amount and provenance are never
    overwritten — same pattern as ManualCollection.
    """

    __tablename__ = "tip_payouts"
    __table_args__ = (
        CheckConstraint(
            "method IN ('cash', 'upi', 'card', 'bank')",
            name="ck_tip_payout_method",
        ),
        CheckConstraint(
            "amount_minor > 0",
            name="ck_tip_payout_positive_amount",
        ),
        CheckConstraint(
            "length(trim(note)) >= 3",
            name="ck_tip_payout_note_present",
        ),
        CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_tip_payout_idempotency_key_present",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3)",
            name="ck_tip_payout_void_state",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_tip_payout_company_idempotency",
        ),
        Index(
            "ix_tip_payout_company_paid_at",
            "company_id",
            "paid_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    method: Mapped[str] = mapped_column(String(20), nullable=False)  # cash|upi|card|bank
    paid_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    note: Mapped[str] = mapped_column(String(500), nullable=False)
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


@event.listens_for(TipPayout, "before_update")
def _guard_tip_payout_update(_mapper, _connection, row: TipPayout) -> None:
    """Only permit the first one-way transition from active to voided."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "branch_id",
        "amount_minor",
        "method",
        "paid_at",
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
            "tip payout financial/provenance fields are immutable: "
            + ", ".join(changed_immutable)
        )

    for field in ("voided_at", "voided_by", "void_reason"):
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("a tip payout void cannot be changed or reversed")
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
        raise ValueError("a tip payout void must be populated atomically")


@event.listens_for(TipPayout, "before_delete")
def _guard_tip_payout_delete(_mapper, _connection, _row: TipPayout) -> None:
    raise ValueError("tip payouts are immutable and cannot be deleted")


class SupplierPayment(Base, TenantMixin):
    """Immutable settlement of one GRN's Accounts Payable balance.

    The linked journal is created in the same transaction and is the only
    accepted supplier-payment journal source.  Corrections use a one-way void
    with a reason; original amount, rail, reference, actor, and timestamps are
    never overwritten.
    """

    __tablename__ = "supplier_payments"
    __table_args__ = (
        CheckConstraint(
            "method IN ('cash', 'bank')",
            name="ck_supplier_payment_method",
        ),
        CheckConstraint(
            "amount_minor > 0",
            name="ck_supplier_payment_positive_amount",
        ),
        CheckConstraint(
            "length(trim(payment_reference)) >= 1",
            name="ck_supplier_payment_reference_present",
        ),
        CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_supplier_payment_idempotency_key_present",
        ),
        CheckConstraint(
            "length(trim(request_hash)) = 64",
            name="ck_supplier_payment_request_hash_present",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND void_reason IS NOT NULL AND length(trim(void_reason)) >= 3)",
            name="ck_supplier_payment_void_state",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_supplier_payment_company_idempotency",
        ),
        UniqueConstraint(
            "journal_entry_id",
            name="uq_supplier_payment_journal_entry",
        ),
        Index(
            "ix_supplier_payment_company_paid_at",
            "company_id",
            "paid_at",
        ),
        Index(
            "ix_supplier_payment_grn_active",
            "grn_id",
            "voided_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    supplier_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("suppliers.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    grn_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("grns.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    journal_entry_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("journal_entries.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    method: Mapped[str] = mapped_column(String(20), nullable=False)  # cash|bank
    paid_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    payment_reference: Mapped[str] = mapped_column(String(160), nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
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


@event.listens_for(SupplierPayment, "before_update")
def _guard_supplier_payment_update(_mapper, _connection, row: SupplierPayment) -> None:
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "branch_id",
        "supplier_id",
        "grn_id",
        "journal_entry_id",
        "amount_minor",
        "method",
        "paid_at",
        "payment_reference",
        "note",
        "idempotency_key",
        "request_hash",
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
            "supplier payment financial/provenance fields are immutable: "
            + ", ".join(changed_immutable)
        )

    for field in ("voided_at", "voided_by", "void_reason"):
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("a supplier payment void cannot be changed or reversed")
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
        raise ValueError("a supplier payment void must be populated atomically")


@event.listens_for(SupplierPayment, "before_delete")
def _guard_supplier_payment_delete(_mapper, _connection, _row: SupplierPayment) -> None:
    raise ValueError("supplier payments are immutable and cannot be deleted")


class Asset(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "assets"
    __table_args__ = (
        CheckConstraint("purchase_minor > 0", name="ck_asset_positive_purchase"),
        CheckConstraint(
            "salvage_minor >= 0 AND salvage_minor <= purchase_minor",
            name="ck_asset_salvage_range",
        ),
        CheckConstraint("useful_life_months > 0", name="ck_asset_positive_useful_life"),
        CheckConstraint(
            "depreciation_method = 'straight_line'",
            name="ck_asset_supported_depreciation_method",
        ),
        CheckConstraint(
            "source_integrity_revision IS NULL OR source_integrity_revision = 50",
            name="ck_asset_source_integrity_revision",
        ),
    )

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
    source_integrity_revision: Mapped[int | None] = mapped_column(
        SmallInteger,
        nullable=True,
        default=50,
        server_default="50",
    )


@event.listens_for(Asset, "before_update")
def _guard_asset_update(_mapper, _connection, row: Asset) -> None:
    """The depreciation register is an immutable source, not an editable estimate."""
    state = inspect(row)
    immutable_fields = {
        "company_id",
        "branch_id",
        "name",
        "type",
        "purchase_minor",
        "purchase_date",
        "depreciation_method",
        "useful_life_months",
        "salvage_minor",
        "notes",
        "created_at",
        "deleted_at",
        "source_integrity_revision",
    }
    changed = sorted(
        field for field in immutable_fields if state.attrs[field].history.has_changes()
    )
    if changed:
        raise ValueError(
            "asset financial/provenance fields are immutable: " + ", ".join(changed)
        )


@event.listens_for(Asset, "before_delete")
def _guard_asset_delete(_mapper, _connection, _row: Asset) -> None:
    raise ValueError("assets are immutable and cannot be deleted")

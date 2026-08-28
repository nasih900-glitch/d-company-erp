"""ORM-level append-only guards for posted goods receipts."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy.orm.attributes import set_committed_value

from app.models.inventory import (
    GRN,
    GRNLine,
    _clear_grn_initial_insert_marker,
    _guard_grn_delete,
    _guard_grn_line_delete,
    _guard_grn_line_update,
    _guard_grn_update,
    _mark_grn_initial_insert,
)

NOW = datetime(2026, 8, 28, 12, tzinfo=UTC)


def _grn(*, posted: bool = True) -> GRN:
    return GRN(
        id=uuid4(),
        purchase_order_id=uuid4(),
        received_at=NOW,
        received_by=uuid4(),
        supplier_invoice_no="SUP-001",
        supplier_invoice_amount_minor=12_500,
        idempotency_key=f"grn:{uuid4()}",
        request_hash="a" * 64,
        journal_entry_id=uuid4() if posted else None,
        eway_bill_no="123456789012",
        notes="Initial note",
        created_at=NOW,
        updated_at=NOW,
    )


def _mark_header_committed(row: GRN) -> None:
    for field in (
        "purchase_order_id",
        "received_at",
        "received_by",
        "supplier_invoice_no",
        "supplier_invoice_amount_minor",
        "idempotency_key",
        "request_hash",
        "journal_entry_id",
        "eway_bill_no",
        "notes",
        "created_at",
        "updated_at",
    ):
        set_committed_value(row, field, getattr(row, field))


@pytest.mark.parametrize(
    ("field", "replacement"),
    (
        ("purchase_order_id", uuid4()),
        ("received_at", NOW + timedelta(minutes=1)),
        ("received_by", uuid4()),
        ("supplier_invoice_no", "SUP-002"),
        ("supplier_invoice_amount_minor", 12_501),
        ("idempotency_key", f"grn:{uuid4()}"),
        ("request_hash", "b" * 64),
        ("journal_entry_id", uuid4()),
        ("eway_bill_no", "210987654321"),
        ("notes", "Rewritten receipt evidence"),
        ("created_at", NOW + timedelta(seconds=1)),
    ),
)
def test_posted_grn_financial_and_provenance_fields_are_immutable(
    field: str, replacement: object
) -> None:
    row = _grn()
    _mark_header_committed(row)

    setattr(row, field, replacement)

    with pytest.raises(ValueError, match=field):
        _guard_grn_update(None, None, row)


def test_initial_receipt_journal_link_is_allowed_only_once_in_insert_transaction() -> None:
    row = _grn(posted=False)
    _mark_header_committed(row)
    _mark_grn_initial_insert(None, None, row)
    row.journal_entry_id = uuid4()

    _guard_grn_update(None, None, row)
    _clear_grn_initial_insert_marker(None, None, row)

    set_committed_value(row, "journal_entry_id", None)
    row.journal_entry_id = uuid4()
    with pytest.raises(ValueError, match="journal_entry_id"):
        _guard_grn_update(None, None, row)


def test_initial_journal_link_does_not_allow_other_provenance_edits() -> None:
    row = _grn(posted=False)
    _mark_header_committed(row)
    _mark_grn_initial_insert(None, None, row)
    row.journal_entry_id = uuid4()
    row.supplier_invoice_amount_minor = 12_501

    with pytest.raises(ValueError, match="supplier_invoice_amount_minor"):
        _guard_grn_update(None, None, row)


def test_only_unposted_grn_headers_may_be_deleted() -> None:
    _guard_grn_delete(None, None, _grn(posted=False))

    with pytest.raises(ValueError, match="cannot be deleted"):
        _guard_grn_delete(None, None, _grn())


def test_grn_lines_are_immutable_after_creation() -> None:
    row = GRNLine(
        id=uuid4(),
        grn_id=uuid4(),
        ingredient_id=uuid4(),
        batch_id=uuid4(),
        qty_received=1,
        cost_per_unit_minor=500,
        created_at=NOW,
        updated_at=NOW,
    )

    with pytest.raises(ValueError, match="immutable after creation"):
        _guard_grn_line_update(None, None, row)
    with pytest.raises(ValueError, match="cannot be deleted"):
        _guard_grn_line_delete(None, None, row)

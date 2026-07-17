"""Journal entry void-in-place guard and ledger-exclusion tests."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from sqlalchemy.orm.attributes import set_committed_value

from app.models import Account, JournalEntry, JournalLine
from app.models.finance import _guard_journal_entry_update
from app.services.accounting.ledger import _posted_journal_ledger_lines

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
USER_ID = UUID("33333333-3333-3333-3333-333333333333")
NOW = datetime(2026, 7, 17, 12, 0, tzinfo=UTC)


class _Result:
    def __init__(self, *, rows: list | None = None) -> None:
        self.rows = rows or []

    def all(self):
        return self.rows

    def scalars(self):
        return self


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.statements = []

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)


def _entry(*, total_minor: int = 1_000_00, voided: bool = False) -> JournalEntry:
    return JournalEntry(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=None,
        ref_type="historical_setup_reconciliation",
        ref_id=uuid4(),
        posted_at=NOW,
        memo="Setup reconciliation",
        total_minor=total_minor,
        created_at=NOW,
        voided_at=NOW if voided else None,
        voided_by=USER_ID if voided else None,
        void_reason="Superseded by itemized entries" if voided else None,
    )


def test_journal_entry_model_is_immutable_except_atomic_first_void() -> None:
    row = _entry()
    for field in (
        "company_id",
        "branch_id",
        "ref_type",
        "ref_id",
        "posted_at",
        "memo",
        "total_minor",
        "created_at",
    ):
        set_committed_value(row, field, getattr(row, field))

    row.total_minor += 1
    with pytest.raises(ValueError, match="total_minor"):
        _guard_journal_entry_update(None, None, row)

    set_committed_value(row, "total_minor", 1_000_00)
    for field in ("voided_at", "voided_by", "void_reason"):
        set_committed_value(row, field, None)
    row.voided_at = NOW
    row.voided_by = USER_ID
    row.void_reason = "Superseded by itemized entries"
    _guard_journal_entry_update(None, None, row)

    for field in ("voided_at", "voided_by", "void_reason"):
        set_committed_value(row, field, getattr(row, field))
    row.void_reason = "Changed reason"
    with pytest.raises(ValueError, match="cannot be changed"):
        _guard_journal_entry_update(None, None, row)


def test_journal_entry_void_must_be_populated_atomically() -> None:
    row = _entry()
    for field in (
        "company_id",
        "branch_id",
        "ref_type",
        "ref_id",
        "posted_at",
        "memo",
        "total_minor",
        "created_at",
        "voided_at",
        "voided_by",
        "void_reason",
    ):
        set_committed_value(row, field, getattr(row, field))
    row.voided_at = NOW
    # voided_by and void_reason left unset — a half-void is not allowed.
    with pytest.raises(ValueError, match="atomically"):
        _guard_journal_entry_update(None, None, row)


@pytest.mark.asyncio
async def test_voided_posted_journal_is_excluded_from_the_ledger() -> None:
    live = _entry(total_minor=2_022_102_74)
    voided = _entry(total_minor=2_022_102_74, voided=True)

    account = Account(
        id=uuid4(),
        company_id=COMPANY_ID,
        code="1185",
        name="Historical Funds Pending Reconciliation",
        type="asset",
        normal_side="dr",
    )
    debit = JournalLine(
        id=uuid4(),
        journal_entry_id=live.id,
        account_id=account.id,
        side="dr",
        amount_minor=live.total_minor,
    )
    credit = JournalLine(
        id=uuid4(),
        journal_entry_id=live.id,
        account_id=account.id,
        side="cr",
        amount_minor=live.total_minor,
    )

    # The query itself filters voided_at IS NULL, so only `live` would ever
    # come back from a real database — assert that filter is present, and
    # that whatever the query returns is trusted (the voided entry is a
    # negative-space check on the SQL, not on Python-side re-filtering).
    session = _QueuedSession(
        [
            _Result(rows=[live]),
            _Result(rows=[(debit, account), (credit, account)]),
        ]
    )
    lines = await _posted_journal_ledger_lines(
        session,
        company_id=COMPANY_ID,
        start_at=None,
        end_exclusive=datetime(2026, 7, 18, tzinfo=UTC),
    )
    assert {line.ref_id for line in lines} == {live.ref_id}

    sql = str(session.statements[0].compile(compile_kwargs={"literal_binds": True}))
    assert "journal_entries.voided_at IS NULL" in sql
    assert voided.voided_at is not None  # sanity: the excluded fixture really is voided

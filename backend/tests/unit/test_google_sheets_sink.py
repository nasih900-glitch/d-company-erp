"""Database-free tests for the server-side Google Sheets mirror.

Follows the session-double convention used across the unit suite (see
tests/unit/test_operational_route_integrity.py): queue up `_Result`s for
each `session.execute(...)` call in order, and use `entities` for
`session.get(Model, id)` lookups.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.events.events import OrderPaid
from app.services.integrations import google_sheets


class _Result:
    def __init__(self, *, rows: list | None = None) -> None:
        self.rows = [] if rows is None else rows

    def all(self):
        return self.rows


class _Session:
    """Minimal async session double: `.get` by (Model, id), queued `.execute`."""

    def __init__(self, *results: _Result, entities: dict | None = None) -> None:
        self.results = list(results)
        self.entities = {} if entities is None else entities
        self.executed = []

    async def get(self, model, entity_id):
        return self.entities.get((model, entity_id))

    async def execute(self, statement):
        self.executed.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)


class _SessionCM:
    """Fakes `async with AsyncSessionLocal() as session:`."""

    def __init__(self, session: _Session) -> None:
        self._session = session

    def __call__(self):
        return self

    async def __aenter__(self):
        return self._session

    async def __aexit__(self, *_exc):
        return False


def _event(*, company_id: UUID, order_id: UUID, method: str = "upi") -> OrderPaid:
    return OrderPaid(
        occurred_at=datetime(2026, 7, 15, 12, 30, tzinfo=UTC),
        company_id=company_id,
        branch_id=uuid4(),
        order_id=order_id,
        total_minor=11_500,
        method=method,
    )


@pytest.mark.asyncio
async def test_on_order_paid_noops_when_webhook_url_is_null(monkeypatch) -> None:
    company_id = uuid4()
    order_id = uuid4()
    company = SimpleNamespace(id=company_id, google_sheets_webhook_url=None, gstin=None)
    session = _Session(entities={(google_sheets.Company, company_id): company})

    monkeypatch.setattr(google_sheets, "AsyncSessionLocal", _SessionCM(session))
    called = False

    async def _fake_push(**_kwargs):
        nonlocal called
        called = True

    monkeypatch.setattr(google_sheets, "push_order_to_sheet", _fake_push)

    await google_sheets.on_order_paid(_event(company_id=company_id, order_id=order_id))

    assert called is False
    # No webhook configured — must return before ever looking the order up.
    assert (google_sheets.Order, order_id) not in session.entities


@pytest.mark.asyncio
async def test_on_order_paid_noops_when_company_missing(monkeypatch) -> None:
    company_id = uuid4()
    order_id = uuid4()
    session = _Session(entities={})  # session.get(Company, ...) -> None

    monkeypatch.setattr(google_sheets, "AsyncSessionLocal", _SessionCM(session))
    called = False

    async def _fake_push(**_kwargs):
        nonlocal called
        called = True

    monkeypatch.setattr(google_sheets, "push_order_to_sheet", _fake_push)

    await google_sheets.on_order_paid(_event(company_id=company_id, order_id=order_id))

    assert called is False


@pytest.mark.asyncio
async def test_on_order_paid_resolves_order_and_pushes_when_webhook_is_set(
    monkeypatch,
) -> None:
    company_id = uuid4()
    order_id = uuid4()
    opener_id = uuid4()
    item_id = uuid4()

    company = SimpleNamespace(
        id=company_id,
        google_sheets_webhook_url="https://script.google.com/macros/s/abc/exec",
        gstin="32AAAAA0000A1Z5",
    )
    order = SimpleNamespace(
        id=order_id,
        company_id=company_id,
        opened_by=opener_id,
        invoice_no="D/MN/2026-27/00231",
        fiscal_year="2026-27",
        type="dine_in",
        place_of_supply_state_code="32",
        cgst_minor=1_195,
        sgst_minor=1_195,
        igst_minor=0,
        cess_minor=0,
        round_off_minor=0,
        total_minor=11_500,
        invoice_issued_at=datetime(2026, 7, 15, 12, 35, tzinfo=UTC),
    )
    opener = SimpleNamespace(id=opener_id, name="Asha")
    order_line = SimpleNamespace(qty=2, taxable_value_minor=8_475)
    menu_item_name = "Cappuccino"

    session = _Session(
        _Result(rows=[(order_line, menu_item_name)]),  # order lines join
        entities={
            (google_sheets.Company, company_id): company,
            (google_sheets.Order, order_id): order,
            (google_sheets.User, opener_id): opener,
        },
    )

    monkeypatch.setattr(google_sheets, "AsyncSessionLocal", _SessionCM(session))

    captured: dict = {}

    async def _fake_push(**kwargs):
        captured.update(kwargs)

    monkeypatch.setattr(google_sheets, "push_order_to_sheet", _fake_push)

    await google_sheets.on_order_paid(
        _event(company_id=company_id, order_id=order_id, method="upi")
    )

    assert captured["url"] == company.google_sheets_webhook_url
    assert captured["invoice_no"] == "D/MN/2026-27/00231"
    assert captured["fiscal_year"] == "2026-27"
    assert captured["company_id"] == company_id
    assert captured["gstin"] == "32AAAAA0000A1Z5"
    assert captured["place_of_supply"] == "32"
    assert captured["order_type"] == "dine_in"
    assert captured["items_text"] == "2x Cappuccino"
    assert captured["items_count"] == 2
    assert captured["taxable_minor"] == 8_475
    assert captured["cgst_minor"] == 1_195
    assert captured["sgst_minor"] == 1_195
    assert captured["total_minor"] == 11_500
    assert captured["method"] == "upi"
    assert captured["cashier"] == "Asha"
    assert captured["at_iso"] == "2026-07-15T12:35:00+00:00"


@pytest.mark.asyncio
async def test_on_order_paid_noops_when_order_missing(monkeypatch) -> None:
    company_id = uuid4()
    order_id = uuid4()
    company = SimpleNamespace(
        id=company_id,
        google_sheets_webhook_url="https://script.google.com/macros/s/abc/exec",
        gstin=None,
    )
    session = _Session(entities={(google_sheets.Company, company_id): company})
    monkeypatch.setattr(google_sheets, "AsyncSessionLocal", _SessionCM(session))

    called = False

    async def _fake_push(**_kwargs):
        nonlocal called
        called = True

    monkeypatch.setattr(google_sheets, "push_order_to_sheet", _fake_push)

    await google_sheets.on_order_paid(_event(company_id=company_id, order_id=order_id))

    assert called is False

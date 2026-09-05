"""Signed Code 21 cash-expense recovery without double-moving a drawer."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import delete, func, select

from app.api.v1.finance import router as finance_router
from app.core.security import hash_password, issue_access_token
from app.models import (
    AuditLog,
    Branch,
    Expense,
    ExpenseCategory,
    IdempotencyKey,
    Shift,
    Terminal,
    User,
)
from app.services.audit.recorder import install_audit_listeners


def _token(seed, *, user=None, branch_id=None) -> str:
    user = user or seed["owner"]
    return issue_access_token(
        user_id=user.id,
        company_id=seed["company"].id,
        branch_id=branch_id or seed["branch"].id,
        roles=["owner"],
        auth_version=user.auth_version,
    )


def _headers(
    seed,
    *,
    captured_at: datetime,
    key: str | None = None,
    user=None,
    terminal_id=None,
    token_branch_id=None,
    version_code: int = 21,
) -> dict[str, str]:
    key = key or f"expense:{uuid4()}"
    return {
        "Authorization": f"Bearer {_token(seed, user=user, branch_id=token_branch_id)}",
        "X-Terminal-Id": str(terminal_id or seed["terminal"].id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": str(version_code),
        "X-Client-Distribution-Channel": "direct",
        "Idempotency-Key": key,
        "X-Client-Action-Id": key,
        "X-Offline-Captured": "true",
        "X-Client-Occurred-At": captured_at.isoformat(),
    }


def _payload(seed, category, *, paid_at: datetime, amount_minor: int = 1_500):
    return {
        "branch_id": str(seed["branch"].id),
        "category_id": str(category.id),
        "amount_minor": amount_minor,
        "paid_via": "cash",
        "paid_at": paid_at.isoformat(),
        "vendor_name": "Code 21 recovery vendor",
        "invoice_no": f"C21-{uuid4().hex[:8]}",
        "note": "Cash paid from the accountable drawer",
    }


async def _seed_open_shift(session, seed, *, expected_minor: int = 10_000):
    now = datetime.now(UTC)
    category = ExpenseCategory(
        id=uuid4(),
        company_id=seed["company"].id,
        name=f"Code 21 recovery {uuid4().hex[:8]}",
        code=f"C21{uuid4().hex[:10]}",
    )
    shift = Shift(
        id=uuid4(),
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        terminal_id=seed["terminal"].id,
        opened_by=seed["owner"].id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=expected_minor,
        expected_minor=expected_minor,
        status="open",
    )
    session.add_all([category, shift])
    await session.commit()
    return category, shift


@pytest.mark.integration
@pytest.mark.asyncio
async def test_code21_cash_expense_is_shift_linked_and_replays_after_cache_expiry(
    client, session, seed_owner,
) -> None:
    # ASGITransport does not run the application's lifespan hook in this test
    # harness, so install the same listener explicitly before proving the
    # production audit provenance written by this route.
    install_audit_listeners()
    category, shift = await _seed_open_shift(session, seed_owner)
    captured = datetime.now(UTC) - timedelta(minutes=2)
    key = f"expense:{uuid4()}"
    headers = _headers(seed_owner, captured_at=captured, key=key)
    payload = _payload(seed_owner, category, paid_at=captured)

    created = await client.post(
        "/api/v1/finance/expenses", json=payload, headers=headers
    )
    assert created.status_code == 201, created.text
    assert created.json()["shift_id"] == str(shift.id)
    assert created.json()["created_by"] == str(seed_owner["owner"].id)
    await session.refresh(shift)
    assert shift.expected_minor == 8_500

    row = (
        await session.execute(
            select(Expense).where(Expense.id == UUID(created.json()["id"]))
        )
    ).scalar_one()
    assert row.shift_id == shift.id
    assert row.idempotency_key == key
    assert row.created_by == seed_owner["owner"].id
    assert row.source_integrity_revision == 51
    assert len(row.request_hash or "") == 64
    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.entity_type == "Expense",
                AuditLog.entity_id == str(row.id),
                AuditLog.action == "create",
            )
        )
    ).scalar_one()
    assert audit.actor_user_id == seed_owner["owner"].id
    assert audit.terminal_id == seed_owner["terminal"].id
    assert audit.client_platform == "android"
    assert audit.client_version_code == 21
    assert audit.client_action_id == key
    assert audit.client_was_offline is True
    assert audit.client_reported_at == captured

    # Prove the durable expense receipt, not the expiring generic cache, owns
    # long-tail replay safety.
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == key))
    await session.commit()
    first, second = await asyncio.gather(
        client.post("/api/v1/finance/expenses", json=payload, headers=headers),
        client.post("/api/v1/finance/expenses", json=payload, headers=headers),
    )
    assert first.status_code == second.status_code == 201, (first.text, second.text)
    assert first.json() == second.json() == created.json()
    await session.refresh(shift)
    assert shift.expected_minor == 8_500
    assert (
        await session.execute(
            select(func.count(Expense.id)).where(Expense.idempotency_key == key)
        )
    ).scalar_one() == 1


@pytest.mark.integration
@pytest.mark.asyncio
async def test_code21_cash_receipt_rejects_changed_actor_terminal_and_provenance(
    client, session, seed_owner,
) -> None:
    category, shift = await _seed_open_shift(session, seed_owner)
    captured = datetime.now(UTC) - timedelta(minutes=2)
    key = f"expense:{uuid4()}"
    headers = _headers(seed_owner, captured_at=captured, key=key)
    payload = _payload(seed_owner, category, paid_at=captured)
    created = await client.post(
        "/api/v1/finance/expenses", json=payload, headers=headers
    )
    assert created.status_code == 201, created.text

    colleague = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"cash-replay-{uuid4().hex[:8]}@test.local",
        name="Cash replay colleague",
        password_hash=hash_password("password1234"),
        status="active",
    )
    other_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Other branch {uuid4().hex[:8]}",
        invoice_series_code=f"O{uuid4().hex[:1].upper()}",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name=f"Other-{uuid4().hex[:8]}",
        device_id=f"other-{uuid4()}",
    )
    session.add_all([colleague, other_branch, other_terminal])
    await session.commit()
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == key))
    await session.commit()

    wrong_actor = await client.post(
        "/api/v1/finance/expenses",
        json=payload,
        headers=_headers(
            seed_owner,
            captured_at=captured,
            key=key,
            user=colleague,
        ),
    )
    assert wrong_actor.status_code == 409, wrong_actor.text

    wrong_terminal = await client.post(
        "/api/v1/finance/expenses",
        json=payload,
        headers=_headers(
            seed_owner,
            captured_at=captured,
            key=key,
            terminal_id=other_terminal.id,
            token_branch_id=other_branch.id,
        ),
    )
    assert wrong_terminal.status_code == 409, wrong_terminal.text

    changed_capture_headers = dict(headers)
    changed_capture_headers["X-Client-Occurred-At"] = (
        captured + timedelta(seconds=1)
    ).isoformat()
    changed_capture = await client.post(
        "/api/v1/finance/expenses",
        json=payload,
        headers=changed_capture_headers,
    )
    assert changed_capture.status_code == 409, changed_capture.text
    await session.refresh(shift)
    assert shift.expected_minor == 8_500


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("case", "expected_text"),
    [
        ("code24", "Cash paid-outs"),
        ("no_terminal", "not linked to a workspace"),
        ("wrong_terminal", "terminal not found"),
        ("pre_open", "before the current shift opened"),
        ("future", "in the future"),
        ("wrong_branch", "branch not found"),
    ],
)
async def test_cash_expense_fail_closed_cases(
    client, session, seed_owner, case: str, expected_text: str,
) -> None:
    category, shift = await _seed_open_shift(session, seed_owner)
    captured = datetime.now(UTC) - timedelta(minutes=2)
    terminal = seed_owner["terminal"].id
    version = 21
    branch_id = seed_owner["branch"].id
    if case == "code24":
        version = 24
    elif case == "wrong_terminal":
        terminal = uuid4()
    elif case == "pre_open":
        captured = shift.opened_at - timedelta(seconds=1)
    elif case == "future":
        captured = datetime.now(UTC) + timedelta(minutes=1)
    elif case == "wrong_branch":
        branch_id = uuid4()

    headers = _headers(
        seed_owner,
        captured_at=captured,
        terminal_id=terminal,
        version_code=version,
    )
    if case == "no_terminal":
        headers.pop("X-Terminal-Id")
    payload = _payload(seed_owner, category, paid_at=captured)
    payload["branch_id"] = str(branch_id)
    response = await client.post(
        "/api/v1/finance/expenses", json=payload, headers=headers
    )
    assert response.status_code in {401, 404, 422}, response.text
    assert expected_text.lower() in response.text.lower()
    await session.refresh(shift)
    assert shift.expected_minor == 10_000
    assert (
        await session.execute(
            select(func.count(Expense.id)).where(Expense.company_id == shift.company_id)
        )
    ).scalar_one() == 0


@pytest.mark.integration
@pytest.mark.asyncio
async def test_code21_cash_expense_void_reverses_once_but_never_after_close(
    client, session, seed_owner,
) -> None:
    category, shift = await _seed_open_shift(session, seed_owner)
    captured = datetime.now(UTC) - timedelta(minutes=2)
    headers = _headers(seed_owner, captured_at=captured)
    created = await client.post(
        "/api/v1/finance/expenses",
        json=_payload(seed_owner, category, paid_at=captured, amount_minor=1_000),
        headers=headers,
    )
    assert created.status_code == 201, created.text
    auth_headers = {
        "Authorization": headers["Authorization"],
        "X-Terminal-Id": headers["X-Terminal-Id"],
    }
    url = f"/api/v1/finance/expenses/{created.json()['id']}/void"
    first = await client.post(
        url, json={"reason": "Duplicate cash purchase"}, headers=auth_headers
    )
    replay = await client.post(
        url, json={"reason": "Duplicate cash purchase"}, headers=auth_headers
    )
    assert first.status_code == replay.status_code == 200, (first.text, replay.text)
    await session.refresh(shift)
    assert shift.expected_minor == 10_000

    second_headers = _headers(seed_owner, captured_at=captured)
    second = await client.post(
        "/api/v1/finance/expenses",
        json=_payload(seed_owner, category, paid_at=captured, amount_minor=1_000),
        headers=second_headers,
    )
    assert second.status_code == 201, second.text
    closed = await client.post(
        f"/api/v1/pos/shifts/{shift.id}/close",
        json={"counted_minor": 9_000},
        headers=auth_headers,
    )
    assert closed.status_code == 200, closed.text
    rejected = await client.post(
        f"/api/v1/finance/expenses/{second.json()['id']}/void",
        json={"reason": "Late correction after close"},
        headers=auth_headers,
    )
    assert rejected.status_code == 422, rejected.text
    assert "closed shift" in rejected.text
    await session.refresh(shift)
    assert shift.expected_minor == 9_000


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_expense_and_shift_close_serialize_on_same_drawer(
    client, session, seed_owner, monkeypatch,
) -> None:
    category, shift = await _seed_open_shift(session, seed_owner)
    captured = datetime.now(UTC) - timedelta(minutes=2)
    headers = _headers(seed_owner, captured_at=captured)
    auth_headers = {
        "Authorization": headers["Authorization"],
        "X-Terminal-Id": headers["X-Terminal-Id"],
    }
    shift_locked = asyncio.Event()
    allow_expense = asyncio.Event()
    original_validate = finance_router._validate_expense_references

    async def pause_after_shift_lock(*args, **kwargs):
        shift_locked.set()
        await asyncio.wait_for(allow_expense.wait(), timeout=10)
        return await original_validate(*args, **kwargs)

    monkeypatch.setattr(
        finance_router, "_validate_expense_references", pause_after_shift_lock
    )
    expense_task = asyncio.create_task(
        client.post(
            "/api/v1/finance/expenses",
            json=_payload(
                seed_owner, category, paid_at=captured, amount_minor=1_000
            ),
            headers=headers,
        )
    )
    await asyncio.wait_for(shift_locked.wait(), timeout=10)
    close_task = asyncio.create_task(
        client.post(
            f"/api/v1/pos/shifts/{shift.id}/close",
            json={"counted_minor": 9_000},
            headers=auth_headers,
        )
    )
    await asyncio.sleep(0.2)
    assert not close_task.done(), "shift close did not wait for the drawer lock"
    allow_expense.set()
    expense, closed = await asyncio.wait_for(
        asyncio.gather(expense_task, close_task), timeout=15
    )
    assert expense.status_code == 201, expense.text
    assert closed.status_code == 200, closed.text
    assert closed.json()["variance_minor"] == 0
    await session.refresh(shift)
    assert shift.status == "closed"
    assert shift.expected_minor == shift.counted_minor == 9_000


@pytest.mark.integration
@pytest.mark.asyncio
async def test_code24_noncash_expense_contract_is_unchanged(
    client, session, seed_owner,
) -> None:
    category, shift = await _seed_open_shift(session, seed_owner)
    paid_at = datetime.now(UTC) - timedelta(minutes=1)
    payload = _payload(seed_owner, category, paid_at=paid_at)
    payload["paid_via"] = "upi"
    response = await client.post(
        "/api/v1/finance/expenses",
        json=payload,
        headers={
            "Authorization": f"Bearer {_token(seed_owner)}",
            "Idempotency-Key": f"expense-code24-noncash:{uuid4()}",
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "24",
        },
    )
    assert response.status_code == 201, response.text
    assert response.json()["shift_id"] is None
    assert response.json()["created_by"] is None
    await session.refresh(shift)
    assert shift.expected_minor == 10_000

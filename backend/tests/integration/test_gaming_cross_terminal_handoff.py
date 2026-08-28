"""Postgres/HTTP proof for explicit Gaming -> POS terminal handoff."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text

from app.models import (
    AuditLog,
    Branch,
    GamingSession,
    Order,
    Shift,
    Station,
    Terminal,
)


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _login(client, seed_owner) -> str:
    response = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _headers(token: str, terminal_id) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
    }


def _ended_session(seed_owner, station: Station, source_shift: Shift) -> GamingSession:
    now = datetime.now(UTC)
    return GamingSession(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        station_id=station.id,
        opened_by=seed_owner["owner"].id,
        shift_id=source_shift.id,
        start_at=now - timedelta(minutes=30),
        end_at=now,
        paused_minutes=0,
        rate_per_hour_minor=12_000,
        billing_mode="hourly",
        billable_minutes=30,
        amount_minor=6_000,
        status="ended",
        extra_controllers=0,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cross_terminal_handoff_routes_bill_to_selected_drawer_only(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    source_terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company.gstin = "32AAAAA0000A1Z5"
    branch.state_code = "32"
    source_terminal.purpose = "gaming"

    target_terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Cafe POS",
        purpose="cafe_pos",
        device_id=f"cafe-{uuid4()}",
    )
    closed_terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Closed POS",
        purpose="cafe_pos",
        device_id=f"closed-{uuid4()}",
    )
    gaming_target_terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Second Gaming Area",
        purpose="gaming",
        device_id=f"gaming-{uuid4()}",
    )
    other_branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name=f"Other {uuid4().hex[:8]}",
        invoice_series_code="OT",
        state_code="32",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name="Other POS",
        purpose="cafe_pos",
        device_id=f"other-{uuid4()}",
    )
    now = datetime.now(UTC)
    source_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=source_terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    target_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=target_terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(minutes=45),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    closed_target_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=closed_terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=2),
        closed_at=now - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        counted_minor=0,
        variance_minor=0,
        status="closed",
    )
    gaming_target_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=gaming_target_terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(minutes=40),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    wrong_branch_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=other_branch.id,
        terminal_id=other_terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(minutes=30),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code=f"XTERM-{uuid4().hex[:8]}",
        name="Cross-terminal PS5",
        type="ps5",
        rate_per_hour_minor=12_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    handed_off = _ended_session(seed_owner, station, source_shift)
    closed_rejection = _ended_session(seed_owner, station, source_shift)
    branch_rejection = _ended_session(seed_owner, station, source_shift)
    purpose_rejection = _ended_session(seed_owner, station, source_shift)
    # Flush the branch/terminal topology first. These fixtures intentionally
    # use scalar foreign-key IDs (rather than ORM relationships), so make the
    # dependency order explicit for PostgreSQL's immediate FK checks.
    session.add_all(
        [
            target_terminal,
            closed_terminal,
            gaming_target_terminal,
            other_branch,
            other_terminal,
        ]
    )
    await session.flush()
    session.add_all(
        [
            source_shift,
            target_shift,
            closed_target_shift,
            gaming_target_shift,
            wrong_branch_shift,
            station,
        ]
    )
    await session.flush()
    session.add_all(
        [handed_off, closed_rejection, branch_rejection, purpose_rejection]
    )
    await session.commit()

    token = await _login(client, seed_owner)
    source_headers = _headers(token, source_terminal.id)
    target_headers = _headers(token, target_terminal.id)
    base_path = f"/api/v1/gaming/sessions/{handed_off.id}"

    eligible = await client.get(
        f"{base_path}/pos-target-shifts",
        headers=source_headers,
    )
    assert eligible.status_code == 200, eligible.text
    assert eligible.json() == [
        {
            "shift_id": str(target_shift.id),
            "terminal_id": str(target_terminal.id),
            "terminal_name": "Cafe POS",
            "opened_by": str(owner.id),
            "opened_by_name": owner.name,
            "opened_at": target_shift.opened_at.isoformat().replace("+00:00", "Z"),
        }
    ]

    handoff_payload = {"target_shift_id": str(target_shift.id)}
    first = await client.post(
        f"{base_path}/handoff-to-pos",
        json=handoff_payload,
        headers=source_headers,
    )
    assert first.status_code == 201, first.text
    first_body = first.json()
    assert first_body["source_shift_id"] == str(source_shift.id)
    assert first_body["source_terminal_id"] == str(source_terminal.id)
    assert first_body["target_shift_id"] == str(target_shift.id)
    assert first_body["target_terminal_id"] == str(target_terminal.id)
    assert first_body["already_linked"] is False

    await session.refresh(handed_off)
    order = await session.get(Order, handed_off.order_id)
    assert order is not None
    assert handed_off.shift_id == source_shift.id
    assert order.shift_id == target_shift.id
    assert order.terminal_id == target_terminal.id
    assert order.branch_id == branch.id
    assert order.status == "held"

    source_queue = await client.get(
        "/api/v1/pos/orders",
        params={"status": "held"},
        headers=source_headers,
    )
    target_queue = await client.get(
        "/api/v1/pos/orders",
        params={"status": "held"},
        headers=target_headers,
    )
    assert source_queue.status_code == 200, source_queue.text
    assert target_queue.status_code == 200, target_queue.text
    assert str(order.id) not in {row["id"] for row in source_queue.json()}
    assert str(order.id) in {row["id"] for row in target_queue.json()}

    retry = await client.post(
        f"{base_path}/handoff-to-pos",
        json=handoff_payload,
        headers=source_headers,
    )
    assert retry.status_code == 201, retry.text
    assert retry.json()["order_id"] == str(order.id)
    assert retry.json()["already_linked"] is True
    order_count = await session.scalar(
        select(func.count(Order.id)).where(Order.company_id == company.id)
    )
    assert order_count == 1

    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == company.id,
                AuditLog.action == "gaming_session_handoff_to_pos",
                AuditLog.entity_id == str(handed_off.id),
            )
        )
    ).scalar_one()
    assert audit.before == {
        "source_shift_id": str(source_shift.id),
        "source_terminal_id": str(source_terminal.id),
        "order_id": None,
    }
    assert audit.after == {
        "source_shift_id": str(source_shift.id),
        "source_terminal_id": str(source_terminal.id),
        "target_shift_id": str(target_shift.id),
        "target_terminal_id": str(target_terminal.id),
        "order_id": str(order.id),
    }

    closed_target = await client.post(
        f"/api/v1/gaming/sessions/{closed_rejection.id}/handoff-to-pos",
        json={"target_shift_id": str(closed_target_shift.id)},
        headers=source_headers,
    )
    wrong_branch = await client.post(
        f"/api/v1/gaming/sessions/{branch_rejection.id}/handoff-to-pos",
        json={"target_shift_id": str(wrong_branch_shift.id)},
        headers=source_headers,
    )
    wrong_purpose = await client.post(
        f"/api/v1/gaming/sessions/{purpose_rejection.id}/handoff-to-pos",
        json={"target_shift_id": str(gaming_target_shift.id)},
        headers=source_headers,
    )
    assert closed_target.status_code == 422, closed_target.text
    assert "not open" in closed_target.json()["error"]["message"]
    assert wrong_branch.status_code == 422, wrong_branch.text
    assert "different branch" in wrong_branch.json()["error"]["message"]
    assert wrong_purpose.status_code == 422, wrong_purpose.text
    assert "cannot receive POS bills" in wrong_purpose.json()["error"]["message"]
    await session.refresh(closed_rejection)
    await session.refresh(branch_rejection)
    await session.refresh(purpose_rejection)
    assert closed_rejection.order_id is None
    assert branch_rejection.order_id is None
    assert purpose_rejection.order_id is None

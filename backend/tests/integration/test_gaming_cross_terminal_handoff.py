"""Postgres/HTTP proof that archived legacy tills cannot receive new bills."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.models import GamingSession, Order, Shift, Station, Terminal


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


def _ended_session(seed_owner, station: Station, shift: Shift) -> GamingSession:
    now = datetime.now(UTC)
    return GamingSession(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        station_id=station.id,
        opened_by=seed_owner["owner"].id,
        shift_id=shift.id,
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
async def test_archived_legacy_till_is_excluded_and_hybrid_workspace_bills_locally(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    workspace = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company.gstin = "32AAAAA0000A1Z5"
    branch.state_code = "32"
    assert workspace.purpose == "hybrid"
    assert workspace.is_active is True

    # Historical terminal rows remain immutable and addressable for reports,
    # but they must never reappear as a destination for a new financial write.
    archived_till = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Historical Cafe POS",
        purpose="cafe_pos",
        is_active=False,
        device_id=f"archived-cafe-{uuid4()}",
    )
    now = datetime.now(UTC)
    workspace_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=workspace.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    stale_archived_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=archived_till.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=2),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code=f"HYBRID-{uuid4().hex[:8]}",
        name="Hybrid workspace PS5",
        type="ps5",
        rate_per_hour_minor=12_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    gaming_session = _ended_session(seed_owner, station, workspace_shift)
    session.add(archived_till)
    await session.flush()
    session.add_all([workspace_shift, stale_archived_shift, station])
    await session.flush()
    session.add(gaming_session)
    await session.commit()

    token = await _login(client, seed_owner)
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(workspace.id),
    }
    base_path = f"/api/v1/gaming/sessions/{gaming_session.id}"

    eligible = await client.get(
        f"{base_path}/pos-target-shifts",
        headers=headers,
    )
    assert eligible.status_code == 200, eligible.text
    assert eligible.json() == []

    archived_handoff = await client.post(
        f"{base_path}/handoff-to-pos",
        json={"target_shift_id": str(stale_archived_shift.id)},
        headers=headers,
    )
    assert archived_handoff.status_code == 422, archived_handoff.text
    assert "archived" in archived_handoff.json()["error"]["message"]
    await session.refresh(gaming_session)
    assert gaming_session.order_id is None

    sent = await client.post(
        f"{base_path}/send-to-pos",
        headers={**headers, "Idempotency-Key": f"hybrid-send:{uuid4()}"},
    )
    assert sent.status_code == 201, sent.text
    await session.refresh(gaming_session)
    order = await session.get(Order, gaming_session.order_id)
    assert order is not None
    assert order.status == "held"
    assert order.shift_id == workspace_shift.id
    assert order.terminal_id == workspace.id
    assert order.branch_id == branch.id

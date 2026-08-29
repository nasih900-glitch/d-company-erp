"""HTTP proof that ordinary Gaming Stop is operational, not audit-only."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.security import hash_password
from app.models import GamingSession, Role, Shift, Station, User, UserRole


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.integration
@pytest.mark.asyncio
async def test_co_owner_can_start_and_stop_same_terminal_session_without_audit_access(
    client,
    session,
    seed_owner,
) -> None:
    """Exercise the real login/token/permission/terminal boundary.

    ``co_owner`` is intentionally hidden as the public ``owner`` title and has
    broad operational access, but it must never gain Audit Log or legacy
    evidence-reconciliation authority.  A normal current-shift Stop therefore
    succeeds with ``gaming.write`` while the protected recovery endpoint stays
    forbidden for the same token.
    """
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]

    co_owner_role = Role(
        id=uuid4(),
        company_id=company.id,
        code="co_owner",
        name="Co-owner",
        permissions=[],
    )
    password = "co-owner-gaming-password"
    co_owner = User(
        id=uuid4(),
        company_id=company.id,
        email=f"co-owner-gaming-{uuid4().hex[:8]}@test.local",
        name="Gaming Co-owner",
        password_hash=hash_password(password),
        status="active",
    )
    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code=f"CO-STOP-{uuid4().hex[:6]}",
        name="Co-owner Stop PS5",
        type="ps5",
        rate_per_hour_minor=12_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    session.add_all([co_owner_role, co_owner, station])
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=co_owner.id,
            role_id=co_owner_role.id,
            branch_id=branch.id,
            granted_by=seed_owner["owner"].id,
        )
    )
    # Deliberately opened by another owner: ordinary Gaming Stop is scoped to
    # the shift's terminal, not restricted to the employee who opened it.
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=seed_owner["owner"].id,
        opened_at=datetime.now(UTC) - timedelta(minutes=10),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add(shift)
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": co_owner.email, "password": password},
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": f"Bearer {login.json()['access_token']}",
        "X-Terminal-Id": str(terminal.id),
    }

    me = await client.get("/api/v1/auth/me", headers=headers)
    assert me.status_code == 200, me.text
    identity = me.json()
    assert identity["roles"] == ["owner"]
    assert identity["protected_access"] is True
    assert identity["audit_access"] is False
    assert "gaming.write" in identity["effective_permissions"]
    assert "admin.audit.read" not in identity["effective_permissions"]
    assert "admin.system" not in identity["effective_permissions"]

    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "timer_minutes": 30,
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        },
        headers={**headers, "Idempotency-Key": f"co-owner-start:{uuid4()}"},
    )
    assert started.status_code == 201, started.text
    assert started.json()["status"] == "active"
    assert started.json()["shift_id"] == str(shift.id)

    stopped = await client.post(
        f"/api/v1/gaming/sessions/{started.json()['id']}/stop",
        json={},
        headers={**headers, "Idempotency-Key": f"co-owner-stop:{uuid4()}"},
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["status"] == "ended"
    assert stopped.json()["shift_id"] == str(shift.id)
    assert stopped.json()["billable_minutes"] == 1
    assert stopped.json()["amount_minor"] == 200

    stored = (
        await session.execute(select(GamingSession).where(GamingSession.id == started.json()["id"]))
    ).scalar_one()
    assert stored.opened_by == co_owner.id
    assert stored.stopped_by == co_owner.id
    assert stored.sent_to_pos_by is None
    assert stored.status == "ended"

    local_action_id = uuid4()
    legacy_recovery = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": started.json()["start_at"],
            "captured_stopped_at": stopped.json()["end_at"],
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "server_session_recovered",
            "reason": "Verify audit-only recovery remains protected",
        },
        headers={
            **headers,
            "Idempotency-Key": f"gaming-legacy-outbox-resolution:{local_action_id}",
        },
    )
    assert legacy_recovery.status_code == 403, legacy_recovery.text
    assert legacy_recovery.json()["error"]["code"] == "forbidden"
    assert "admin.audit.read" in legacy_recovery.json()["error"]["message"]

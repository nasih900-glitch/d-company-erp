from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import issue_access_token
from app.models import (
    Branch,
    Company,
    Customer,
    GamingSession,
    Order,
    Payment,
    RolePermissionOverride,
    Shift,
    Station,
    Terminal,
)
from app.services.reports.overview import load_pos_local_hour_profile


def _token(seed_owner: dict, *, roles: list[str]) -> str:
    owner = seed_owner["owner"]
    return issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=roles,
        auth_version=owner.auth_version,
    )


def _headers(seed_owner: dict, *, roles: list[str], terminal: bool = True) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {_token(seed_owner, roles=roles)}"}
    if terminal:
        headers["X-Terminal-Id"] = str(seed_owner["terminal"].id)
    return headers


@pytest.mark.integration
@pytest.mark.asyncio
async def test_home_is_branch_scoped_pii_minimal_and_bounded(
    client,
    session,
    seed_owner,
    monkeypatch,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC).replace(microsecond=0)
    foreign_branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name=f"Foreign Home branch {uuid4().hex[:8]}",
        invoice_series_code="HB",
    )
    foreign_terminal = Terminal(
        id=uuid4(),
        branch_id=foreign_branch.id,
        name="Foreign Home terminal",
        device_id=f"home-foreign-{uuid4()}",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Historical secondary terminal",
        purpose="cafe_pos",
        is_active=False,
        device_id=f"home-other-{uuid4()}",
    )
    foreign_company = Company(id=uuid4(), name="Foreign Home company")
    session.add_all([foreign_branch, foreign_company])
    await session.flush()
    session.add_all([foreign_terminal, other_terminal])
    await session.flush()
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(minutes=40),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    other_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=other_terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(minutes=35),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    foreign_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=foreign_branch.id,
        terminal_id=foreign_terminal.id,
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
        code=f"HOME-{uuid4().hex[:6]}",
        name="Home station",
        type="ps5",
        rate_per_hour_minor=10_000,
        is_active=True,
    )
    foreign_station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=foreign_branch.id,
        code=f"FOREIGN-{uuid4().hex[:6]}",
        name="FOREIGN HOME STATION",
        type="ps5",
        rate_per_hour_minor=10_000,
        is_active=True,
    )
    session.add_all([shift, other_shift, foreign_shift, station, foreign_station])
    await session.flush()
    gaming_session = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        shift_id=shift.id,
        opened_by=owner.id,
        start_at=now - timedelta(minutes=20),
        rate_per_hour_minor=10_000,
        status="active",
        customer_name="PRIVATE CUSTOMER NAME",
        customer_phone="+971500000001",
    )
    foreign_gaming_session = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=foreign_station.id,
        shift_id=foreign_shift.id,
        opened_by=owner.id,
        start_at=now - timedelta(minutes=5),
        rate_per_hour_minor=10_000,
        status="active",
        customer_name="FOREIGN PRIVATE CUSTOMER",
        customer_phone="+971500000009",
    )
    paid_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="paid",
        subtotal_minor=2_000,
        total_minor=2_100,
        tip_minor=100,
        opened_at=now - timedelta(minutes=15),
        closed_at=now - timedelta(minutes=10),
        invoice_issued_at=now - timedelta(minutes=10),
        invoice_no=f"D/{branch.invoice_series_code}/26-27/{uuid4().int % 100000:05d}",
        fiscal_year="2026-27",
        customer_name="PRIVATE ORDER CUSTOMER",
        customer_phone="+971500000002",
        notes="PRIVATE ORDER NOTE",
    )
    other_terminal_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=other_terminal.id,
        shift_id=other_shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="paid",
        subtotal_minor=3_000,
        total_minor=3_000,
        opened_at=now - timedelta(minutes=12),
        closed_at=now - timedelta(minutes=8),
        invoice_issued_at=now - timedelta(minutes=8),
        invoice_no="FT/26-27/00001",
        fiscal_year="2026-27",
    )
    foreign_branch_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=foreign_branch.id,
        terminal_id=foreign_terminal.id,
        shift_id=foreign_shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="paid",
        subtotal_minor=7_000,
        total_minor=7_000,
        opened_at=now - timedelta(minutes=7),
        closed_at=now - timedelta(minutes=3),
        invoice_issued_at=now - timedelta(minutes=3),
        invoice_no="FB/26-27/00001",
        fiscal_year="2026-27",
    )
    customers = [
        Customer(
            id=uuid4(),
            company_id=company.id,
            phone=f"+9715{index:08d}",
            name=f"PRIVATE DIRECTORY CUSTOMER {index}",
        )
        for index in range(501)
    ]
    deleted_customer = Customer(
        id=uuid4(),
        company_id=company.id,
        phone="+971599999999",
        name="PRIVATE DELETED CUSTOMER",
        deleted_at=now,
    )
    foreign_company_customer = Customer(
        id=uuid4(),
        company_id=foreign_company.id,
        phone="+971588888888",
        name="FOREIGN COMPANY CUSTOMER",
    )
    session.add_all(
        [
            gaming_session,
            foreign_gaming_session,
            paid_order,
            other_terminal_order,
            foreign_branch_order,
            Payment(
                id=uuid4(),
                order_id=paid_order.id,
                shift_id=shift.id,
                method="upi",
                amount_minor=paid_order.total_minor,
                paid_at=now - timedelta(minutes=10),
            ),
            Payment(
                id=uuid4(),
                order_id=other_terminal_order.id,
                shift_id=other_shift.id,
                method="upi",
                amount_minor=other_terminal_order.total_minor,
                paid_at=now - timedelta(minutes=8),
            ),
            Payment(
                id=uuid4(),
                order_id=foreign_branch_order.id,
                shift_id=foreign_shift.id,
                method="upi",
                amount_minor=foreign_branch_order.total_minor,
                paid_at=now - timedelta(minutes=3),
            ),
            *customers,
            deleted_customer,
            foreign_company_customer,
        ]
    )
    await session.commit()

    statements: list[str] = []
    original_execute = AsyncSession.execute

    async def tracked_execute(active_session, statement, *args, **kwargs):
        statements.append(str(statement))
        return await original_execute(active_session, statement, *args, **kwargs)

    monkeypatch.setattr(AsyncSession, "execute", tracked_execute)

    response = await client.get(
        "/api/v1/analytics/home",
        headers=_headers(seed_owner, roles=["owner"]),
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["schema_version"] == 1
    assert payload["branch_id"] == str(branch.id)
    assert payload["terminal_id"] == str(terminal.id)
    assert payload["customers"] == {
        "current": 501,
        "scope": "company_live_directory",
        "comparison_status": "unavailable_no_immutable_history",
    }
    assert payload["gaming"] == {"active_station_capacity": 1, "open_sessions": 1}
    profile = payload["local_hour_profile"]
    assert profile["basis"] == "pos_sales_tip_exclusive_local_hour_profile"
    assert profile["reconciles_to_daily_gross"] is False
    assert profile["day_length_minutes"] == 24 * 60
    assert [point["hour"] for point in profile["points"]] == list(range(24))
    # Aggregates are selected-branch scoped, but intentionally span terminals.
    assert sum(point["revenue_minor"] for point in profile["points"]) == 5_000
    assert sum(point["orders_count"] for point in profile["points"]) == 2
    assert len(payload["recent_activity"]) <= 8
    assert {row["kind"] for row in payload["recent_activity"]} == {
        "pos_order_paid",
        "pos_shift_opened",
        "gaming_session_started",
    }
    assert all(
        set(row)
        == {
            "kind",
            "occurred_at",
            "display_reference",
            "title",
            "amount_minor",
            "scope",
        }
        for row in payload["recent_activity"]
    )
    assert payload["orders"]["comparison_status"] == "no_baseline"
    assert payload["activity_capabilities"] == {"pos": "available", "gaming": "available"}
    assert len(statements) <= 64, f"Home query bound exceeded: {len(statements)}"
    assert not any("audit_log" in statement.lower() for statement in statements)
    serialized = json.dumps(payload)
    for forbidden in (
        "PRIVATE CUSTOMER NAME",
        "+971500000001",
        "PRIVATE ORDER CUSTOMER",
        "+971500000002",
        "PRIVATE ORDER NOTE",
        str(gaming_session.id),
        str(paid_order.id),
        str(owner.id),
        "FT/26-27/00001",
        "FB/26-27/00001",
        "FOREIGN HOME STATION",
        "FOREIGN COMPANY CUSTOMER",
    ):
        assert forbidden not in serialized

    cross_branch_terminal = await client.get(
        "/api/v1/analytics/home",
        headers={
            "Authorization": f"Bearer {_token(seed_owner, roles=['owner'])}",
            "X-Terminal-Id": str(foreign_terminal.id),
        },
    )
    assert cross_branch_terminal.status_code == 403


@pytest.mark.integration
@pytest.mark.asyncio
async def test_home_suppresses_activity_per_permission_and_terminal(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    session.add_all(
        [
            RolePermissionOverride(
                id=uuid4(),
                company_id=company_id,
                role_code="partner",
                module="pos",
                allowed=False,
            ),
            RolePermissionOverride(
                id=uuid4(),
                company_id=company_id,
                role_code="partner",
                module="gaming",
                allowed=False,
            ),
        ]
    )
    await session.commit()

    denied = await client.get(
        "/api/v1/analytics/home",
        headers=_headers(seed_owner, roles=["partner"]),
    )
    assert denied.status_code == 200, denied.text
    assert denied.json()["recent_activity"] == []
    assert denied.json()["activity_capabilities"] == {
        "pos": "missing_permission",
        "gaming": "missing_permission",
    }
    # Aggregates remain available even when both raw activity lanes are absent.
    assert len(denied.json()["local_hour_profile"]["points"]) == 24

    overrides = (
        await session.execute(
            select(RolePermissionOverride).where(
                RolePermissionOverride.company_id == company_id
            )
        )
    ).scalars().all()
    for row in overrides:
        await session.delete(row)
    await session.commit()

    no_terminal = await client.get(
        "/api/v1/analytics/home",
        headers=_headers(seed_owner, roles=["partner"], terminal=False),
    )
    assert no_terminal.status_code == 200, no_terminal.text
    assert no_terminal.json()["activity_capabilities"] == {
        "pos": "missing_terminal",
        "gaming": "available",
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_home_requires_a_selected_branch(client, seed_owner) -> None:
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        branch_id=None,
        roles=["partner"],
        auth_version=owner.auth_version,
    )
    response = await client.get(
        "/api/v1/analytics/home",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 400
    assert response.json()["detail"] == "a selected branch is required"

    missing_permission = await client.get(
        "/api/v1/analytics/home",
        headers=_headers(seed_owner, roles=["staff"]),
    )
    assert missing_permission.status_code == 403


@pytest.mark.integration
@pytest.mark.asyncio
async def test_local_hour_profile_combines_repeated_dst_hour_and_zero_fills(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime(2026, 10, 24, tzinfo=UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add(shift)
    await session.flush()
    # Europe/Berlin repeats local 02:xx on 25 October 2026.
    for index, sale_at in enumerate(
        (
            datetime(2026, 10, 25, 0, 30, tzinfo=UTC),
            datetime(2026, 10, 25, 1, 30, tzinfo=UTC),
        )
    ):
        order = Order(
            id=uuid4(),
            company_id=company.id,
            branch_id=branch.id,
            terminal_id=terminal.id,
            shift_id=shift.id,
            opened_by=owner.id,
            type="takeaway",
            status="paid",
            subtotal_minor=1_000 + index * 100,
            total_minor=1_050 + index * 100,
            tip_minor=50,
            opened_at=sale_at - timedelta(minutes=10),
            closed_at=sale_at,
            invoice_issued_at=sale_at,
            invoice_no=f"D/{branch.invoice_series_code}/26-27/{uuid4().int % 100000:05d}",
            fiscal_year="2026-27",
        )
        session.add_all(
            [
                order,
                Payment(
                    id=uuid4(),
                    order_id=order.id,
                    shift_id=shift.id,
                    method="upi",
                    amount_minor=order.total_minor,
                    paid_at=sale_at,
                ),
            ]
        )
    await session.commit()

    repeated = await load_pos_local_hour_profile(
        session,
        company_id=company.id,
        branch_id=branch.id,
        business_date=datetime(2026, 10, 25, tzinfo=UTC).date(),
        timezone_name="Europe/Berlin",
    )
    assert repeated.day_length_minutes == 25 * 60
    assert len(repeated.points) == 24
    assert repeated.points[2].orders_count == 2
    assert repeated.points[2].revenue_minor == 2_100
    assert repeated.total_orders_count == 2
    assert repeated.total_revenue_minor == 2_100
    assert all(point.orders_count == 0 for point in repeated.points if point.hour != 2)

    short = await load_pos_local_hour_profile(
        session,
        company_id=company.id,
        branch_id=branch.id,
        business_date=datetime(2026, 3, 29, tzinfo=UTC).date(),
        timezone_name="Europe/Berlin",
    )
    assert short.day_length_minutes == 23 * 60
    assert [point.hour for point in short.points] == list(range(24))
    assert short.points[2].orders_count == 0

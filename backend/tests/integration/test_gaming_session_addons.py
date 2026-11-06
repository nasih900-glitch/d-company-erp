"""Postgres contracts for server-priced Gaming session add-ons."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import hash_password, issue_access_token
from app.core.timezone import local_today
from app.models import (
    AuditLog,
    Batch,
    Branch,
    Company,
    Customer,
    CustomerMembership,
    GamingSession,
    GamingSessionAddon,
    GamingVoidAddonLine,
    GamingVoidReceipt,
    Ingredient,
    MenuCategory,
    MenuItem,
    MenuVariant,
    MembershipTier,
    MembershipBenefitReservation,
    Order,
    OrderLine,
    Payment,
    Recipe,
    RecipeLine,
    Role,
    Shift,
    Station,
    StockMovement,
    Terminal,
    User,
    UserRole,
)
from app.models.shift_operator import ShiftOperatorHandover
from app.services.reports import ReportsAggregator


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


def _headers(seed_owner, token: str, key: str | None = None) -> dict[str, str]:
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    if key is not None:
        headers["Idempotency-Key"] = key
    return headers


async def _seed_live_session(
    session,
    seed_owner,
    *,
    gaming_amount_minor: int = 5_050,
    drink_price_minor: int = 1_249,
    opener: User | None = None,
):
    company = seed_owner["company"]
    company.gst_registration_type = "unregistered"
    company.is_composition = False
    branch = seed_owner["branch"]
    branch.state_code = "32"
    seed_owner["terminal"].purpose = "hybrid"

    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=seed_owner["terminal"].id,
        opened_by=(opener or seed_owner["owner"]).id,
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code=f"PS5-{uuid4().hex[:8]}",
        name="Gaming add-on contract station",
        type="ps5",
        rate_per_hour_minor=12_000,
        is_active=True,
        tax_rate=0,
        sac_code="999692",
        rate_includes_tax=True,
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Gaming snacks {uuid4().hex[:8]}",
        sort_order=0,
    )
    drink = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"CAN-{uuid4().hex[:8]}",
        name="Cold can",
        type="drink",
        base_price_minor=drink_price_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    gaming_session = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        opened_by=(opener or seed_owner["owner"]).id,
        shift_id=shift.id,
        start_at=datetime.now(UTC) - timedelta(minutes=15),
        paused_minutes=0,
        rate_per_hour_minor=station.rate_per_hour_minor,
        billing_mode="package",
        package_price_minor_snapshot=gaming_amount_minor,
        package_duration_minutes_snapshot=60,
        package_variant_snapshot="single",
        package_station_type_snapshot="ps5",
        timer_minutes=60,
        amount_minor=gaming_amount_minor,
        status="active",
        extra_controllers=0,
        tax_rate=0,
        sac_code="999692",
        rate_includes_tax=True,
    )
    session.add_all([shift, station, category])
    await session.flush()
    session.add(drink)
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    return shift, station, drink, gaming_session


async def _attach_percentage_gaming_member(
    session,
    seed_owner,
    gaming_session: GamingSession,
    *,
    gaming_discount_pct: float = 0.10,
    free_gaming_minutes_per_week: int = 0,
) -> Customer:
    now = datetime.now(UTC)
    customer = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Percentage Gaming member",
        phone=f"7{uuid4().int % 10**9:09d}",
    )
    tier = MembershipTier(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code=f"gaming-{uuid4().hex[:8]}",
        name="Gaming percentage proof",
        monthly_price_minor=1_000,
        gaming_discount_pct=gaming_discount_pct,
        free_gaming_minutes_per_week=free_gaming_minutes_per_week,
    )
    session.add_all([customer, tier])
    await session.flush()
    session.add(
        CustomerMembership(
            id=uuid4(),
            customer_id=customer.id,
            tier_id=tier.id,
            billing_cycle="monthly",
            starts_at=now - timedelta(days=1),
            expires_at=now + timedelta(days=30),
            amount_paid_minor=1_000,
        )
    )
    gaming_session.customer_id = customer.id
    gaming_session.customer_name = customer.name
    gaming_session.customer_phone = customer.phone
    await session.commit()
    return customer


async def _seed_linked_void_candidate(client, session, seed_owner) -> dict[str, object]:
    """Create one claimed held Gaming+add-on bill for Owner Void tests."""

    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    token = await _login(client, seed_owner)
    client_line_id = uuid4()
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(client_line_id),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-race-stage:{uuid4()}"),
    )
    assert added.status_code == 201, added.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-race-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    order_id = sent.json()["order_id"]
    order_read = await client.get(
        f"/api/v1/pos/orders/{order_id}",
        headers=_headers(seed_owner, token),
    )
    assert order_read.status_code == 200, order_read.text
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    assert claim.status_code == 201, claim.text
    return {
        "token": token,
        "gaming_session_id": gaming_session.id,
        "client_line_id": client_line_id,
        "stopped": stopped.json(),
        "order": order_read.json(),
        "claim_token": claim.json()["claim_token"],
    }


@pytest.mark.asyncio
async def test_addon_role_and_tenant_boundaries(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )

    anonymous = await client.get(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons"
    )
    assert anonymous.status_code == 401, anonymous.text

    gaming_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="gaming_supervisor",
        name="Gaming Supervisor",
        permissions=[],
    )
    gaming_user = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"gaming-{uuid4().hex[:8]}@test.local",
        name="Gaming Supervisor",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add_all([gaming_role, gaming_user])
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=gaming_user.id,
            role_id=gaming_role.id,
            branch_id=seed_owner["branch"].id,
        )
    )
    await session.commit()
    await session.refresh(gaming_user)
    gaming_token = issue_access_token(
        user_id=gaming_user.id,
        company_id=gaming_user.company_id,
        roles=["gaming_supervisor"],
        branch_id=seed_owner["branch"].id,
        auth_version=gaming_user.auth_version,
    )
    gaming_headers = {
        "Authorization": f"Bearer {gaming_token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers={
            **gaming_headers,
            "Idempotency-Key": f"gaming-addon-role:{uuid4()}",
        },
    )
    assert added.status_code == 201, added.text

    other_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Other branch",
        invoice_series_code="OB",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name="Other Gaming",
        device_id=f"other-{uuid4()}",
        purpose="hybrid",
    )
    session.add(other_branch)
    await session.flush()
    session.add(other_terminal)
    await session.commit()
    other_branch_token = issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        roles=["owner"],
        branch_id=other_branch.id,
        auth_version=seed_owner["owner"].auth_version,
    )
    other_branch_read = await client.get(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        headers={
            "Authorization": f"Bearer {other_branch_token}",
            "X-Terminal-Id": str(other_terminal.id),
        },
    )
    assert other_branch_read.status_code == 404, other_branch_read.text

    foreign_company = Company(id=uuid4(), name="ForeignCo")
    foreign_branch = Branch(
        id=uuid4(),
        company_id=foreign_company.id,
        name="Foreign branch",
        invoice_series_code="FB",
    )
    foreign_terminal = Terminal(
        id=uuid4(),
        branch_id=foreign_branch.id,
        name="Foreign Gaming",
        device_id=f"foreign-{uuid4()}",
        purpose="hybrid",
    )
    foreign_role = Role(
        id=uuid4(),
        company_id=foreign_company.id,
        code="owner",
        name="Owner",
        permissions=[],
    )
    foreign_user = User(
        id=uuid4(),
        company_id=foreign_company.id,
        email=f"foreign-{uuid4().hex[:8]}@test.local",
        name="Foreign owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add(foreign_company)
    await session.flush()
    session.add_all([foreign_branch, foreign_role, foreign_user])
    await session.flush()
    session.add(foreign_terminal)
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=foreign_user.id,
            role_id=foreign_role.id,
            branch_id=foreign_branch.id,
        )
    )
    await session.commit()
    await session.refresh(foreign_user)
    foreign_token = issue_access_token(
        user_id=foreign_user.id,
        company_id=foreign_company.id,
        roles=["owner"],
        branch_id=foreign_branch.id,
        auth_version=foreign_user.auth_version,
    )
    foreign_read = await client.get(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        headers={
            "Authorization": f"Bearer {foreign_token}",
            "X-Terminal-Id": str(foreign_terminal.id),
        },
    )
    assert foreign_read.status_code == 404, foreign_read.text


@pytest.mark.asyncio
async def test_add_retry_void_after_stop_and_combined_handoff(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    token = await _login(client, seed_owner)
    first_line_id = uuid4()
    first_key = f"gaming-addon-add:{uuid4()}"
    first_payload = {
        "client_line_id": str(first_line_id),
        "menu_item_id": str(drink.id),
        "qty": 1,
        "expected_unit_price_minor": 1_249,
        "note": "Handed over at station",
    }

    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json=first_payload,
        headers=_headers(seed_owner, token, first_key),
    )
    replay = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json=first_payload,
        headers=_headers(seed_owner, token, first_key),
    )

    assert added.status_code == 201, added.text
    assert replay.status_code == 201, replay.text
    assert replay.json() == added.json()
    assert added.json()["catalog_unit_price_minor"] == 1_249
    assert added.json()["line_total_minor"] == 1_249
    assert added.json()["menu_item_type"] == "drink"

    # The immutable add-on row remains the receipt after the generic
    # idempotency cache reaches its retention horizon. It must still bind the
    # key to the original operation/payload before rebuilding the cache row.
    await session.execute(
        text("DELETE FROM idempotency_keys WHERE key = :key"),
        {"key": first_key},
    )
    await session.commit()
    changed_after_cache_expiry = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={**first_payload, "note": "Changed after response-cache expiry"},
        headers=_headers(seed_owner, token, first_key),
    )
    durable_replay = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json=first_payload,
        headers=_headers(seed_owner, token, first_key),
    )
    assert changed_after_cache_expiry.status_code == 409
    assert durable_replay.status_code == 201, durable_replay.text
    assert durable_replay.json() == added.json()

    duplicate_line = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json=first_payload,
        headers=_headers(seed_owner, token, f"gaming-addon-other:{uuid4()}"),
    )
    assert duplicate_line.status_code == 409, duplicate_line.text

    stale_price = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            **first_payload,
            "client_line_id": str(uuid4()),
            "expected_unit_price_minor": 1_250,
        },
        headers=_headers(seed_owner, token, f"gaming-addon-stale:{uuid4()}"),
    )
    assert stale_price.status_code == 409, stale_price.text
    assert stale_price.json()["error"]["details"]["current_unit_price_minor"] == 1_249

    second_key = f"gaming-addon-add:{uuid4()}"
    second = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={**first_payload, "client_line_id": str(uuid4()), "note": None},
        headers=_headers(seed_owner, token, second_key),
    )
    assert second.status_code == 201, second.text

    blocked_cancel = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/cancel",
        json={"reason": "Opened in error"},
        headers=_headers(
            seed_owner,
            token,
            f"gaming-session-void:{gaming_session.id}:addons-blocked",
        ),
    )
    assert blocked_cancel.status_code == 422, blocked_cancel.text
    assert blocked_cancel.json()["error"]["message"] == (
        "Choose the open POS shift that will retain the consumed add-ons. "
        "Nothing was voided."
    )

    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["amount_minor"] == 5_050

    void_key = f"gaming-addon-void:{uuid4()}"
    void_path = (
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons/"
        f"{second.json()['id']}/void"
    )
    voided = await client.post(
        void_path,
        json={"reason": "Wrong can selected"},
        headers=_headers(seed_owner, token, void_key),
    )
    void_replay = await client.post(
        void_path,
        json={"reason": "Wrong can selected"},
        headers=_headers(seed_owner, token, void_key),
    )
    assert voided.status_code == 200, voided.text
    assert void_replay.status_code == 200, void_replay.text
    assert void_replay.json() == voided.json()
    assert voided.json()["void_reason"] == "Wrong can selected"

    await session.execute(
        text("DELETE FROM idempotency_keys WHERE key = :key"),
        {"key": void_key},
    )
    await session.commit()
    changed_void_after_cache_expiry = await client.post(
        void_path,
        json={"reason": "Different correction reason"},
        headers=_headers(seed_owner, token, void_key),
    )
    durable_void_replay = await client.post(
        void_path,
        json={"reason": "Wrong can selected"},
        headers=_headers(seed_owner, token, void_key),
    )
    assert changed_void_after_cache_expiry.status_code == 409
    assert durable_void_replay.status_code == 200, durable_void_replay.text
    assert durable_void_replay.json() == voided.json()

    # Catalog edits after consumption must not rewrite the sold snapshot.
    await session.rollback()
    persisted_drink = await session.get(MenuItem, drink.id)
    assert persisted_drink is not None
    persisted_drink.name = "Renamed after consumption"
    await session.commit()

    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    assert sent.json()["amount_minor"] == 6_300

    await session.rollback()
    order = await session.get(Order, sent.json()["order_id"])
    assert order is not None
    assert int(order.total_minor) == 6_300
    assert int(order.round_off_minor) == 1
    assert order.kitchen_state is None
    lines = (
        await session.execute(
            select(OrderLine)
            .where(OrderLine.order_id == order.id)
            .order_by(OrderLine.created_at, OrderLine.id)
        )
    ).scalars().all()
    assert len(lines) == 2
    addon_line = next(line for line in lines if line.client_line_id == first_line_id)
    assert addon_line.menu_item_name_snapshot == "Cold can"
    assert addon_line.menu_item_type_snapshot == "drink"
    assert int(addon_line.line_total_minor) == 1_249
    assert addon_line.kitchen_status == "served"
    assert addon_line.kitchen_released_at is not None
    assert addon_line.kitchen_round_no == 1
    assert addon_line.kitchen_served_at == addon_line.kitchen_released_at

    void_after_handoff = await client.post(
        (
            f"/api/v1/gaming/sessions/{gaming_session.id}/addons/"
            f"{added.json()['id']}/void"
        ),
        json={"reason": "Too late correction"},
        headers=_headers(seed_owner, token, f"gaming-addon-void:{uuid4()}"),
    )
    assert void_after_handoff.status_code == 422, void_after_handoff.text

    listed = await client.get(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        headers=_headers(seed_owner, token),
    )
    assert listed.status_code == 200, listed.text
    assert len(listed.json()) == 2
    assert sum(row["voided_at"] is None for row in listed.json()) == 1

    audit_actions = set(
        (
            await session.execute(
                select(AuditLog.action).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_type == "GamingSessionAddon",
                )
            )
        ).scalars()
    )
    assert {"gaming_session_addon_added", "gaming_session_addon_voided"} <= audit_actions


@pytest.mark.asyncio
async def test_pos_linked_charge_requires_owner_and_dedicated_pos_review(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, _drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    owner_token = await _login(client, seed_owner)
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, owner_token, f"gaming-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, owner_token),
    )
    assert sent.status_code == 201, sent.text
    order_id = sent.json()["order_id"]

    supervisor_token = issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        roles=["gaming_supervisor"],
        branch_id=seed_owner["branch"].id,
        auth_version=seed_owner["owner"].auth_version,
    )
    denied = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/cancel",
        json={
            "reason": "Mistaken station booking",
            "expected_billing_revision": stopped.json()["billing_revision"],
            "expected_order_id": order_id,
        },
        headers=_headers(
            seed_owner,
            supervisor_token,
            f"gaming-session-void:{gaming_session.id}:supervisor",
        ),
    )
    assert denied.status_code == 403, denied.text
    assert "Only an Owner" in denied.json()["error"]["message"]

    voided = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/cancel",
        json={
            "reason": "Mistaken station booking",
            "expected_billing_revision": stopped.json()["billing_revision"],
            "expected_order_id": order_id,
        },
        headers=_headers(
            seed_owner,
            owner_token,
            f"gaming-session-void:{gaming_session.id}",
        ),
    )
    assert voided.status_code == 422, voided.text
    assert "Owner POS review" in voided.json()["error"]["message"]

    await session.rollback()
    order = await session.get(Order, order_id)
    assert order is not None and order.status == "held"
    lines = (
        await session.execute(select(OrderLine).where(OrderLine.order_id == order.id))
    ).scalars().all()
    assert len(lines) == 1
    assert lines[0].void_reason is None
    assert lines[0].voided_by is None


@pytest.mark.asyncio
async def test_complimentary_session_with_consumed_item_can_reach_pos(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
        gaming_amount_minor=0,
    )
    token = await _login(client, seed_owner)
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-addon-free-play:{uuid4()}"),
    )
    assert added.status_code == 201, added.text

    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-stop-free-play:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["amount_minor"] == 0

    # Target discovery uses the same eligibility rule as every write path.
    # A hybrid single-terminal setup has no cross-terminal targets, but this
    # must be a successful empty list rather than a zero-session rejection.
    targets = await client.get(
        f"/api/v1/gaming/sessions/{gaming_session.id}/pos-target-shifts",
        headers=_headers(seed_owner, token),
    )
    assert targets.status_code == 200, targets.text
    assert targets.json() == []

    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    assert sent.json()["amount_minor"] == 1_200

    await session.rollback()
    order = await session.get(Order, sent.json()["order_id"])
    assert order is not None
    assert int(order.total_minor) == 1_200
    lines = (
        await session.execute(select(OrderLine).where(OrderLine.order_id == order.id))
    ).scalars().all()
    assert len(lines) == 2
    assert any(
        line.menu_item_id == drink.id and int(line.line_total_minor) == 1_249
        for line in lines
    )


@pytest.mark.asyncio
async def test_zero_price_consumed_item_reaches_zero_finalization_and_stock(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
        gaming_amount_minor=0,
        drink_price_minor=0,
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        sku=f"FREE-CAN-STOCK-{uuid4().hex[:8]}",
        name="Complimentary can stock",
        base_unit="unit",
        reorder_threshold=2,
        reorder_qty=10,
        avg_cost_minor=300,
        current_qty=10,
    )
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=drink.id,
        name="One complimentary retail can",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=300,
    )
    session.add(ingredient)
    await session.flush()
    session.add(recipe)
    await session.flush()
    session.add_all(
        [
            RecipeLine(
                id=uuid4(),
                recipe_id=recipe.id,
                ingredient_id=ingredient.id,
                qty=1,
                wastage_pct=0,
            ),
            Batch(
                id=uuid4(),
                ingredient_id=ingredient.id,
                branch_id=seed_owner["branch"].id,
                received_at=datetime.now(UTC) - timedelta(days=1),
                expires_at=None,
                qty_initial=10,
                qty_on_hand=10,
                cost_per_unit_minor=300,
                lot_code=f"FREE-LOT-{uuid4().hex[:8]}",
            ),
        ]
    )
    await session.commit()

    token = await _login(client, seed_owner)
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 0,
        },
        headers=_headers(seed_owner, token, f"gaming-addon-zero-total:{uuid4()}"),
    )
    assert added.status_code == 201, added.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-stop-zero-total:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    assert sent.json()["amount_minor"] == 0

    order_id = sent.json()["order_id"]
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    assert claim.status_code == 201, claim.text
    finalized = await client.post(
        f"/api/v1/pos/orders/{order_id}/finalize-zero",
        headers={
            **_headers(seed_owner, token, f"gaming-zero-finalize:{uuid4()}"),
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert finalized.status_code == 200, finalized.text
    assert finalized.json()["order_status"] == "paid"
    assert finalized.json()["amount_minor"] == 0

    await session.rollback()
    persisted_ingredient = (
        await session.execute(
            select(Ingredient)
            .where(Ingredient.id == ingredient.id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    assert Decimal(str(persisted_ingredient.current_qty)) == Decimal("9.0000")
    movements = (
        await session.execute(
            select(StockMovement).where(
                StockMovement.ref_type == "order",
                StockMovement.ref_id == order_id,
            )
        )
    ).scalars().all()
    assert len(movements) == 1
    assert Decimal(str(movements[0].qty_delta)) == Decimal("-1.0000")


@pytest.mark.asyncio
async def test_addon_ledger_rejects_direct_financial_mutation_and_delete(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    token = await _login(client, seed_owner)
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-addon-sql:{uuid4()}"),
    )
    assert added.status_code == 201, added.text
    gaming_session_id = gaming_session.id

    other_item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=drink.category_id,
        sku=f"OTHER-{uuid4().hex[:8]}",
        name="Other same-company item",
        type="drink",
        base_price_minor=500,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    wrong_item_variant = MenuVariant(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        menu_item_id=other_item.id,
        name="Wrong item variant",
        price_delta_minor=0,
        is_active=True,
    )
    inactive_variant = MenuVariant(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        menu_item_id=drink.id,
        name="Inactive variant",
        price_delta_minor=0,
        is_active=False,
    )
    foreign_company = Company(id=uuid4(), name="Foreign variant company")
    foreign_category = MenuCategory(
        id=uuid4(),
        company_id=foreign_company.id,
        name="Foreign category",
        sort_order=0,
    )
    foreign_item = MenuItem(
        id=uuid4(),
        company_id=foreign_company.id,
        category_id=foreign_category.id,
        sku=f"FOREIGN-{uuid4().hex[:8]}",
        name="Foreign item",
        type="drink",
        base_price_minor=500,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    foreign_variant = MenuVariant(
        id=uuid4(),
        company_id=foreign_company.id,
        menu_item_id=foreign_item.id,
        name="Foreign variant",
        price_delta_minor=0,
        is_active=True,
    )
    session.add_all([other_item, foreign_company])
    await session.flush()
    session.add_all([wrong_item_variant, inactive_variant, foreign_category])
    await session.flush()
    session.add(foreign_item)
    await session.flush()
    session.add(foreign_variant)
    invalid_variant_ids = (
        wrong_item_variant.id,
        inactive_variant.id,
        foreign_variant.id,
    )
    await session.commit()

    copied_addon_insert = text(
        """
        INSERT INTO gaming_session_addons (
            id, company_id, gaming_session_id, client_line_id, menu_item_id,
            menu_item_name_snapshot, menu_item_type_snapshot, variant_id,
            variant_snapshot, modifiers, qty, catalog_unit_price_minor,
            unit_price_minor, line_total_minor, discount_minor, hsn_or_sac,
            tax_rate, taxable_value_minor, cgst_minor, sgst_minor, igst_minor,
            cess_minor, note, idempotency_key, request_hash, created_by,
            created_terminal_id, created_at
        )
        SELECT
            :id, company_id, gaming_session_id, :client_line_id, menu_item_id,
            menu_item_name_snapshot, menu_item_type_snapshot, :variant_id,
            variant_snapshot, modifiers, qty, catalog_unit_price_minor,
            unit_price_minor, line_total_minor, discount_minor, hsn_or_sac,
            tax_rate, taxable_value_minor, cgst_minor, sgst_minor, igst_minor,
            cess_minor, note, :idempotency_key, request_hash, created_by,
            created_terminal_id, created_at
          FROM gaming_session_addons
         WHERE id = :source_id
        """
    )
    for invalid_variant_id in invalid_variant_ids:
        with pytest.raises(
            DBAPIError,
            match="variant must be active and match item/company provenance",
        ):
            await session.execute(
                copied_addon_insert,
                {
                    "id": str(uuid4()),
                    "client_line_id": str(uuid4()),
                    "variant_id": str(invalid_variant_id),
                    "idempotency_key": f"gaming-addon-invalid-variant:{uuid4()}",
                    "source_id": added.json()["id"],
                },
            )
        await session.rollback()

    with pytest.raises(DBAPIError, match="immutable Void receipt"):
        await session.execute(
            text(
                "UPDATE gaming_sessions SET status = 'cancelled' "
                "WHERE id = :session_id"
            ),
            {"session_id": str(gaming_session_id)},
        )
        await session.commit()
    await session.rollback()

    with pytest.raises(DBAPIError, match="financial/provenance fields are immutable"):
        await session.execute(
            text(
                "UPDATE gaming_session_addons "
                "SET line_total_minor = line_total_minor + 1 WHERE id = :addon_id"
            ),
            {"addon_id": added.json()["id"]},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="immutable; void the add-on instead"):
        await session.execute(
            text("DELETE FROM gaming_session_addons WHERE id = :addon_id"),
            {"addon_id": added.json()["id"]},
        )
    await session.rollback()

    addon = (
        await session.execute(
            select(GamingSessionAddon).where(
                GamingSessionAddon.id == added.json()["id"]
            )
        )
    ).scalar_one_or_none()
    assert addon is not None


@pytest.mark.asyncio
async def test_combined_payment_deducts_addon_stock_once_and_reconciles_finance(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        sku=f"CAN-STOCK-{uuid4().hex[:8]}",
        name="Cold can stock",
        base_unit="unit",
        reorder_threshold=2,
        reorder_qty=10,
        avg_cost_minor=300,
        current_qty=10,
    )
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=drink.id,
        name="One retail can",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=300,
    )
    session.add(ingredient)
    await session.flush()
    session.add(recipe)
    await session.flush()
    session.add_all(
        [
            RecipeLine(
                id=uuid4(),
                recipe_id=recipe.id,
                ingredient_id=ingredient.id,
                qty=1,
                wastage_pct=0,
            ),
            Batch(
                id=uuid4(),
                ingredient_id=ingredient.id,
                branch_id=seed_owner["branch"].id,
                received_at=datetime.now(UTC) - timedelta(days=1),
                expires_at=None,
                qty_initial=10,
                qty_on_hand=10,
                cost_per_unit_minor=300,
                lot_code=f"LOT-{uuid4().hex[:8]}",
            ),
        ]
    )
    await session.commit()

    token = await _login(client, seed_owner)
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-addon-pay:{uuid4()}"),
    )
    assert added.status_code == 201, added.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-stop-pay:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    order_id = sent.json()["order_id"]
    assert sent.json()["amount_minor"] == 6_300

    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    assert claim.status_code == 201, claim.text
    payment_key = f"gaming-combined-upi:{uuid4()}"
    payment_payload = {
        "method": "upi",
        "amount_minor": 6_300,
        "ref_external": f"upi-{uuid4()}",
        "expected_order_total_minor": 6_300,
        "expected_due_minor": 6_300,
    }
    payment_headers = {
        **_headers(seed_owner, token, payment_key),
        "X-Checkout-Claim": claim.json()["claim_token"],
    }
    paid = await client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        json=payment_payload,
        headers=payment_headers,
    )
    replay = await client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        json=payment_payload,
        headers=payment_headers,
    )
    assert paid.status_code == 201, paid.text
    assert replay.status_code == 201, replay.text
    assert replay.json() == paid.json()
    assert paid.json()["method"] == "upi"
    assert paid.json()["order_status"] == "paid"

    await session.rollback()
    persisted_ingredient = (
        await session.execute(
            select(Ingredient)
            .where(Ingredient.id == ingredient.id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    assert persisted_ingredient is not None
    assert Decimal(str(persisted_ingredient.current_qty)) == Decimal("9.0000")
    payment_rows = (
        await session.execute(select(Payment).where(Payment.order_id == order_id))
    ).scalars().all()
    assert len(payment_rows) == 1
    movements = (
        await session.execute(
            select(StockMovement).where(
                StockMovement.ref_type == "order",
                StockMovement.ref_id == order_id,
            )
        )
    ).scalars().all()
    assert len(movements) == 1
    assert Decimal(str(movements[0].qty_delta)) == Decimal("-1.0000")

    kitchen_work = (
        await session.execute(
            select(OrderLine)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id == order_id,
                MenuItem.type.in_(("food", "drink", "dessert")),
                OrderLine.voided_at.is_(None),
                OrderLine.kitchen_status.in_(("queued", "cooking", "ready")),
            )
        )
    ).scalars().all()
    assert kitchen_work == []

    report = await ReportsAggregator(session).aggregate_daily(
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        d=local_today(seed_owner["company"].timezone),
    )
    assert report.revenue.food_minor == 1_249
    assert report.revenue.gaming_minor == 5_050
    assert report.revenue.round_off_minor == 1
    assert report.gross_revenue_minor == 6_300
    assert report.payments_received.upi_minor == 6_300
    assert report.payments_received.total_minor == 6_300
    assert report.cogs_minor == 300


@pytest.mark.asyncio
async def test_concurrent_add_and_stop_serialize_without_losing_an_accepted_line(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    token = await _login(client, seed_owner)
    client_line_id = uuid4()
    add_request, stop_request = await asyncio.gather(
        client.post(
            f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
            json={
                "client_line_id": str(client_line_id),
                "menu_item_id": str(drink.id),
                "qty": 1,
                "expected_unit_price_minor": 1_249,
            },
            headers=_headers(seed_owner, token, f"gaming-race-add:{uuid4()}"),
        ),
        client.post(
            f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
            json={},
            headers=_headers(seed_owner, token, f"gaming-race-stop:{uuid4()}"),
        ),
    )

    assert stop_request.status_code == 200, stop_request.text
    assert add_request.status_code in {201, 422}, add_request.text
    if add_request.status_code == 422:
        assert "active or paused" in add_request.json()["error"]["message"]

    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    await session.rollback()
    copied_line_ids = set(
        (
            await session.execute(
                select(OrderLine.client_line_id).where(
                    OrderLine.order_id == sent.json()["order_id"],
                    OrderLine.client_line_id.is_not(None),
                )
            )
        ).scalars()
    )
    if add_request.status_code == 201:
        assert copied_line_ids == {client_line_id}
        assert sent.json()["amount_minor"] == 6_300
    else:
        assert copied_line_ids == set()
        assert sent.json()["amount_minor"] == 5_100


@pytest.mark.asyncio
async def test_concurrent_void_and_send_choose_one_consistent_financial_outcome(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    token = await _login(client, seed_owner)
    client_line_id = uuid4()
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(client_line_id),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-race-stage:{uuid4()}"),
    )
    assert added.status_code == 201, added.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-race-ended:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text

    void_request, send_request = await asyncio.gather(
        client.post(
            (
                f"/api/v1/gaming/sessions/{gaming_session.id}/addons/"
                f"{added.json()['id']}/void"
            ),
            json={"reason": "Concurrent stop-review correction"},
            headers=_headers(seed_owner, token, f"gaming-race-void:{uuid4()}"),
        ),
        client.post(
            f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
            headers=_headers(seed_owner, token),
        ),
    )
    assert send_request.status_code == 201, send_request.text
    assert void_request.status_code in {200, 422}, void_request.text

    await session.rollback()
    copied_line_ids = set(
        (
            await session.execute(
                select(OrderLine.client_line_id).where(
                    OrderLine.order_id == send_request.json()["order_id"],
                    OrderLine.client_line_id.is_not(None),
                )
            )
        ).scalars()
    )
    if void_request.status_code == 200:
        assert copied_line_ids == set()
        assert send_request.json()["amount_minor"] == 5_100
    else:
        assert "after POS handoff" in void_request.json()["error"]["message"]
        assert copied_line_ids == {client_line_id}
        assert send_request.json()["amount_minor"] == 6_300


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("reason", [None, "   ", "Mistaken session reviewed by opener"])
async def test_unlinked_void_retains_consumed_addons_on_explicit_pos_shift(
    client,
    session,
    seed_owner,
    reason,
) -> None:
    source_shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    gaming_session_id = gaming_session.id
    # The reviewed source shift is already the branch's single active hybrid
    # terminal shift.  Reuse it as the explicit retained-bill target rather
    # than constructing a second active terminal solely for this fixture.
    target_shift = source_shift
    target_shift_id = target_shift.id
    target_terminal_id = target_shift.terminal_id
    ingredient = Ingredient(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        sku=f"RETAINED-CAN-{uuid4().hex[:8]}",
        name="Retained can stock",
        base_unit="unit",
        reorder_threshold=2,
        reorder_qty=10,
        avg_cost_minor=300,
        current_qty=10,
    )
    ingredient_id = ingredient.id
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=drink.id,
        name="One retained can",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=300,
    )
    session.add(ingredient)
    await session.flush()
    session.add(recipe)
    await session.flush()
    session.add_all(
        [
            RecipeLine(
                id=uuid4(),
                recipe_id=recipe.id,
                ingredient_id=ingredient.id,
                qty=1,
                wastage_pct=0,
            ),
            Batch(
                id=uuid4(),
                ingredient_id=ingredient.id,
                branch_id=seed_owner["branch"].id,
                received_at=datetime.now(UTC) - timedelta(days=1),
                expires_at=None,
                qty_initial=10,
                qty_on_hand=10,
                cost_per_unit_minor=300,
                lot_code=f"RETAINED-LOT-{uuid4().hex[:8]}",
            ),
        ]
    )
    await session.commit()
    token = await _login(client, seed_owner)
    owner_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(target_terminal_id),
    }
    client_line_id = uuid4()
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(client_line_id),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-retain-stage:{uuid4()}"),
    )
    assert added.status_code == 201, added.text

    void_key = f"gaming-retain-void:{uuid4()}"
    void_payload = {
        "reason": reason,
        "expected_order_id": None,
        "retained_target_shift_id": str(target_shift_id),
        "expected_billing_revision": 0,
    }
    void_path = f"/api/v1/gaming/sessions/{gaming_session.id}/cancel"
    voided = await client.post(void_path, json=void_payload,
        headers=_headers(seed_owner, token, void_key))
    assert voided.status_code == 200, voided.text
    assert voided.json()["status"] == "cancelled"
    assert voided.json()["order_id"] is None
    retained_order_id = voided.json()["retained_addon_order_id"]
    assert retained_order_id is not None
    expected_reason = reason.strip() if reason and reason.strip() else "Voided before 30 minutes; no user reason supplied"
    assert voided.json()["cancel_reason"] == expected_reason
    current = await client.get(f"/api/v1/gaming/sessions/{gaming_session_id}", headers=owner_headers)
    assert current.status_code == 200, current.text
    assert current.json() == voided.json()
    warm_replay = await client.post(void_path, json=void_payload,
        headers=_headers(seed_owner, token, void_key))
    assert warm_replay.status_code == 200 and warm_replay.json() == voided.json(), warm_replay.text
    # A technical replay-cache eviction must preserve the immutable command identity.
    await session.execute(text("DELETE FROM idempotency_keys WHERE key=:key"), {"key": void_key})
    await session.commit()
    cold_replay = await client.post(void_path, json=void_payload,
        headers=_headers(seed_owner, token, void_key))
    assert cold_replay.status_code == 200 and cold_replay.json() == voided.json(), cold_replay.text
    reread = await client.get(f"/api/v1/gaming/sessions/{gaming_session_id}", headers=owner_headers)
    assert reread.status_code == 200 and reread.json() == voided.json(), reread.text

    await session.rollback()
    saved_session = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == gaming_session_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    retained_order = (
        await session.execute(
            select(Order)
            .where(Order.id == retained_order_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    receipt = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.gaming_session_id == gaming_session_id
            )
        )
    ).scalar_one()
    mapping = (
        await session.execute(
            select(GamingVoidAddonLine).where(
                GamingVoidAddonLine.receipt_id == receipt.id
            )
        )
    ).scalar_one()
    retained_line = await session.get(OrderLine, mapping.order_line_id)

    assert saved_session is not None
    assert retained_order is not None
    assert retained_line is not None
    assert saved_session.order_id is None
    assert saved_session.retained_addon_order_id == retained_order.id
    assert retained_order.status == "held"
    assert retained_order.shift_id == target_shift_id
    assert retained_order.total_minor == 1_200
    retained_order_uuid = retained_order.id
    retained_total_minor = int(retained_order.total_minor)
    target_headers = {
        **owner_headers,
    }
    assert receipt.source_order_id is None
    assert receipt.retained_order_id == retained_order.id
    assert receipt.addon_count == 1
    assert receipt.idempotency_key == void_key
    assert retained_line.id == mapping.order_line_id
    assert retained_line.client_line_id == client_line_id
    assert retained_line.line_total_minor == 1_249
    assert retained_line.kitchen_status == "served"
    assert retained_line.voided_at is None
    stored_ingredient = await session.get(Ingredient, ingredient_id)
    assert stored_ingredient is not None
    assert Decimal(str(stored_ingredient.current_qty)) == Decimal("10.0000")
    assert (
        await session.execute(
            select(StockMovement).where(
                StockMovement.ref_type == "order",
                StockMovement.ref_id == retained_order_uuid,
            )
        )
    ).scalars().all() == []

    generic_order_void = await client.request(
        "DELETE",
        f"/api/v1/pos/orders/{retained_order.id}",
        json={"reason": "Must use Gaming review"},
        headers=target_headers,
    )
    assert generic_order_void.status_code == 422, generic_order_void.text
    assert "Gaming Void review" in generic_order_void.json()["error"]["message"]

    generic_line_void = await client.post(
        f"/api/v1/pos/orders/{retained_order.id}/lines/{retained_line.id}/void",
        json={
            "expected_checkout_version": retained_order.checkout_version,
            "reason": "Must use Gaming review",
        },
        headers={
            **target_headers,
            "Idempotency-Key": f"generic-line-reject:{uuid4()}",
        },
    )
    assert generic_line_void.status_code == 422, generic_line_void.text
    assert "Gaming Void review" in generic_line_void.json()["error"]["message"]

    claim = await client.post(
        f"/api/v1/pos/orders/{retained_order_uuid}/checkout-claim",
        headers=target_headers,
    )
    assert claim.status_code == 201, claim.text
    payment_key = f"retained-addon-payment:{uuid4()}"
    payment_payload = {
        "method": "upi",
        "amount_minor": retained_total_minor,
        "ref_external": f"upi-retained-{uuid4()}",
        "expected_order_total_minor": retained_total_minor,
        "expected_due_minor": retained_total_minor,
    }
    paid = await client.post(
        f"/api/v1/pos/orders/{retained_order_uuid}/payments",
        json=payment_payload,
        headers={
            **target_headers,
            "Idempotency-Key": payment_key,
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert paid.status_code == 201, paid.text
    replay = await client.post(
        f"/api/v1/pos/orders/{retained_order_uuid}/payments",
        json=payment_payload,
        headers={
            **target_headers,
            "Idempotency-Key": payment_key,
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == paid.json()

    await session.rollback()
    stored_ingredient = (
        await session.execute(
            select(Ingredient)
            .where(Ingredient.id == ingredient_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    assert Decimal(str(stored_ingredient.current_qty)) == Decimal("9.0000")
    movements = (
        await session.execute(
            select(StockMovement).where(
                StockMovement.ref_type == "order",
                StockMovement.ref_id == retained_order_uuid,
            )
        )
    ).scalars().all()
    assert len(movements) == 1
    assert Decimal(str(movements[0].qty_delta)) == Decimal("-1.0000")


@pytest.mark.integration
@pytest.mark.asyncio
async def test_owner_linked_void_preserves_served_addon_line_and_same_held_order(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    gaming_session_id = gaming_session.id
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id
    owner_id = seed_owner["owner"].id
    owner_auth_version = seed_owner["owner"].auth_version
    terminal_id = seed_owner["terminal"].id
    token = await _login(client, seed_owner)
    owner_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
    }
    client_line_id = uuid4()
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
        json={
            "client_line_id": str(client_line_id),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1_249,
        },
        headers=_headers(seed_owner, token, f"gaming-linked-stage:{uuid4()}"),
    )
    assert added.status_code == 201, added.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-linked-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    order_id = sent.json()["order_id"]

    await session.rollback()
    source_order = await session.get(Order, order_id)
    assert source_order is not None
    addon_line_before = (
        await session.execute(
            select(OrderLine).where(
                OrderLine.order_id == source_order.id,
                OrderLine.client_line_id == client_line_id,
            )
        )
    ).scalar_one()
    addon_line_id = addon_line_before.id
    addon_line_served_at = addon_line_before.kitchen_served_at
    before_checkout_version = source_order.checkout_version
    before_total_minor = source_order.total_minor
    generic_void = await client.request(
        "DELETE",
        f"/api/v1/pos/orders/{order_id}",
        json={"reason": "Owner must use dedicated Gaming review"},
        headers=owner_headers,
    )
    assert generic_void.status_code == 422, generic_void.text
    assert "Gaming Void review" in generic_void.json()["error"]["message"]
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=owner_headers,
    )
    assert claim.status_code == 201, claim.text

    void_key = f"gaming-linked-owner-void:{uuid4()}"
    reviewed_payload = {
        "session_id": str(gaming_session_id),
        "reason": "Owner reviewed incorrect Gaming charge",
        "expected_order_status": "held",
        "expected_checkout_version": before_checkout_version,
        "expected_order_total_minor": before_total_minor,
        "expected_session_status": "ended",
        "expected_billing_revision": stopped.json()["billing_revision"],
        "expected_participant_revision": stopped.json()["participant_revision"],
        "expected_amount_minor": stopped.json()["amount_minor"],
    }
    manager_token = issue_access_token(
        user_id=owner_id,
        company_id=company_id,
        roles=["manager"],
        branch_id=branch_id,
        auth_version=owner_auth_version,
    )
    manager_denied = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=reviewed_payload,
        headers={
            "Authorization": f"Bearer {manager_token}",
            "X-Terminal-Id": str(terminal_id),
            "Idempotency-Key": f"gaming-linked-manager-denied:{uuid4()}",
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert manager_denied.status_code == 403, manager_denied.text
    assert "Only an Owner" in manager_denied.json()["error"]["message"]

    stale_review = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json={**reviewed_payload, "expected_checkout_version": before_checkout_version + 1},
        headers={
            **owner_headers,
            "Idempotency-Key": f"gaming-linked-stale-review:{uuid4()}",
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert stale_review.status_code == 409, stale_review.text

    wrong_claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=reviewed_payload,
        headers={
            **owner_headers,
            "Idempotency-Key": f"gaming-linked-wrong-claim:{uuid4()}",
            "X-Checkout-Claim": "not-the-reviewed-claim",
        },
    )
    assert wrong_claim.status_code == 409, wrong_claim.text

    reviewed = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=reviewed_payload,
        headers={
            **owner_headers,
            "Idempotency-Key": void_key,
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["source_order_id"] == order_id
    assert reviewed.json()["retained_order_id"] == order_id
    assert reviewed.json()["source_order_status"] == "held"
    assert reviewed.json()["retained_addon_count"] == 1
    assert reviewed.json()["retained_total_minor"] == 1_200

    changed_key = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=reviewed_payload,
        headers={
            **owner_headers,
            "Idempotency-Key": f"gaming-linked-new-key:{uuid4()}",
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert changed_key.status_code == 409, changed_key.text

    changed_body = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json={**reviewed_payload, "reason": "Different reviewed reason"},
        headers={
            **owner_headers,
            "Idempotency-Key": void_key,
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert changed_body.status_code == 409, changed_body.text

    for owner_role in ("owner", "co_owner", "super_owner"):
        replay_token = issue_access_token(
            user_id=owner_id,
            company_id=company_id,
            roles=[owner_role],
            branch_id=branch_id,
            auth_version=owner_auth_version,
        )
        replay = await client.post(
            f"/api/v1/pos/orders/{order_id}/gaming-void",
            json=reviewed_payload,
            headers={
                "Authorization": f"Bearer {replay_token}",
                "X-Terminal-Id": str(terminal_id),
                "Idempotency-Key": void_key,
                # The durable receipt must replay after the claim was consumed.
                "X-Checkout-Claim": claim.json()["claim_token"],
            },
        )
        assert replay.status_code == 200, replay.text
        assert replay.json() == reviewed.json()

    await session.rollback()
    owner_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == company_id,
                Role.code == "owner",
            )
        )
    ).scalar_one()
    other_owner = User(
        id=uuid4(),
        company_id=company_id,
        email=f"other-owner-{uuid4().hex[:8]}@test.local",
        name="Other Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    existing_invoice_series = set(
        (
            await session.execute(
                select(Branch.invoice_series_code).where(Branch.company_id == company_id)
            )
        ).scalars()
    )
    invoice_alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    other_invoice_series = next(
        first + second
        for first in invoice_alphabet
        for second in invoice_alphabet
        if first + second not in existing_invoice_series
    )
    other_branch = Branch(
        id=uuid4(),
        company_id=company_id,
        name=f"Other branch {uuid4().hex[:8]}",
        code=f"B{uuid4().hex[:6].upper()}",
        invoice_series_code=other_invoice_series,
        state_code="32",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name=f"Other Hybrid {uuid4().hex[:8]}",
        purpose="hybrid",
        is_active=True,
        device_id=f"other-hybrid-{uuid4()}",
    )
    session.add_all([other_owner, other_branch, other_terminal])
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=other_owner.id,
            role_id=owner_role.id,
            branch_id=branch_id,
        )
    )
    await session.commit()
    await session.refresh(other_owner)
    other_owner_token = issue_access_token(
        user_id=other_owner.id,
        company_id=company_id,
        roles=["owner"],
        branch_id=branch_id,
        auth_version=other_owner.auth_version,
    )
    wrong_actor = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=reviewed_payload,
        headers={
            "Authorization": f"Bearer {other_owner_token}",
            "X-Terminal-Id": str(terminal_id),
            "Idempotency-Key": void_key,
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert wrong_actor.status_code == 409, wrong_actor.text
    wrong_terminal = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=reviewed_payload,
        headers={
            **owner_headers,
            "Idempotency-Key": void_key,
            "X-Terminal-Id": str(other_terminal.id),
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert wrong_terminal.status_code == 403, wrong_terminal.text
    assert wrong_terminal.json()["error"]["code"] == "tenant_violation"
    assert (
        wrong_terminal.json()["error"]["message"]
        == "terminal belongs to a different branch"
    )

    await session.rollback()
    saved_session = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == gaming_session_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    retained_order = (
        await session.execute(
            select(Order)
            .where(Order.id == order_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    addon_line_after = (
        await session.execute(
            select(OrderLine).where(OrderLine.id == addon_line_id)
        )
    ).scalar_one()
    receipt = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.gaming_session_id == gaming_session_id
            )
        )
    ).scalar_one()
    assert saved_session.status == "cancelled"
    assert saved_session.order_id is None
    assert saved_session.retained_addon_order_id == retained_order.id
    assert retained_order.status == "held"
    assert addon_line_after.id == addon_line_id
    assert addon_line_after.order_id == retained_order.id
    assert addon_line_after.kitchen_served_at == addon_line_served_at
    assert addon_line_after.voided_at is None
    assert receipt.source_order_id == retained_order.id
    assert receipt.retained_order_id == retained_order.id
    assert receipt.gaming_charge_line_id is not None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_owner_linked_void_without_addons_voids_only_reviewed_unpaid_order(
    client,
    session,
    seed_owner,
) -> None:
    _shift, _station, _drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
    )
    gaming_session_id = gaming_session.id
    token = await _login(client, seed_owner)
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"gaming-linked-empty-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    order_id = sent.json()["order_id"]
    order_read = await client.get(
        f"/api/v1/pos/orders/{order_id}",
        headers=_headers(seed_owner, token),
    )
    assert order_read.status_code == 200, order_read.text
    reviewed_order = order_read.json()
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    assert claim.status_code == 201, claim.text

    void_key = f"gaming-linked-empty-void:{uuid4()}"
    payload = {
        "session_id": str(gaming_session.id),
        "reason": "Owner reviewed duplicate Gaming charge",
        "expected_order_status": "held",
        "expected_checkout_version": reviewed_order["checkout_version"],
        "expected_order_total_minor": reviewed_order["total_minor"],
        "expected_session_status": "ended",
        "expected_billing_revision": stopped.json()["billing_revision"],
        "expected_participant_revision": stopped.json()["participant_revision"],
        "expected_amount_minor": stopped.json()["amount_minor"],
    }
    result = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json=payload,
        headers={
            **_headers(seed_owner, token, void_key),
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert result.status_code == 200, result.text
    assert result.json()["source_order_status"] == "void"
    assert result.json()["retained_order_id"] is None
    assert result.json()["retained_addon_count"] == 0
    assert result.json()["retained_total_minor"] == 0

    await session.rollback()
    saved_session = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == gaming_session_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    source_order = (
        await session.execute(
            select(Order)
            .where(Order.id == order_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    receipt = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.gaming_session_id == gaming_session_id
            )
        )
    ).scalar_one()
    charge_line = await session.get(OrderLine, receipt.gaming_charge_line_id)
    assert saved_session is not None and saved_session.status == "cancelled"
    assert saved_session.order_id is None
    assert saved_session.retained_addon_order_id is None
    assert source_order is not None and source_order.status == "void"
    assert receipt.source_order_id == source_order.id
    assert receipt.retained_order_id is None
    assert receipt.addon_count == 0
    assert charge_line is not None and charge_line.void_reason == payload["reason"]


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("with_addon", [False, True], ids=["charge-only", "with-addon"])
async def test_owner_linked_void_binds_percentage_discounted_charge_snapshot(
    client,
    session,
    seed_owner,
    with_addon: bool,
) -> None:
    _shift, _station, drink, gaming_session = await _seed_live_session(
        session,
        seed_owner,
        gaming_amount_minor=5_000,
    )
    gaming_session_id = gaming_session.id
    await _attach_percentage_gaming_member(session, seed_owner, gaming_session)
    token = await _login(client, seed_owner)
    terminal_id = seed_owner["terminal"].id
    owner_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
    }
    if with_addon:
        added = await client.post(
            f"/api/v1/gaming/sessions/{gaming_session.id}/addons",
            json={
                "client_line_id": str(uuid4()),
                "menu_item_id": str(drink.id),
                "qty": 1,
                "expected_unit_price_minor": 1_249,
            },
            headers=_headers(seed_owner, token, f"member-addon:{uuid4()}"),
        )
        assert added.status_code == 201, added.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={},
        headers=_headers(seed_owner, token, f"member-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/send-to-pos",
        headers=_headers(seed_owner, token),
    )
    assert sent.status_code == 201, sent.text
    order_id = UUID(sent.json()["order_id"])

    await session.rollback()
    active_lines = (
        await session.execute(
            select(OrderLine)
            .where(OrderLine.order_id == order_id, OrderLine.voided_at.is_(None))
            .order_by(OrderLine.id)
            .execution_options(populate_existing=True)
        )
    ).scalars().all()
    charge_line = next(line for line in active_lines if line.client_line_id is None)
    charge_line_id = charge_line.id
    assert int(charge_line.discount_minor) == 500
    assert int(charge_line.line_total_minor) == 4_500
    assert int(charge_line.line_total_minor) < int(stopped.json()["amount_minor"])
    assert (
        await session.execute(
            select(MembershipBenefitReservation).where(
                MembershipBenefitReservation.order_id == order_id
            )
        )
    ).scalars().all() == []

    order_read = await client.get(
        f"/api/v1/pos/orders/{order_id}",
        headers=owner_headers,
    )
    assert order_read.status_code == 200, order_read.text
    reviewed_order = order_read.json()
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=owner_headers,
    )
    assert claim.status_code == 201, claim.text
    voided = await client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json={
            "session_id": str(gaming_session_id),
            "reason": "Owner reviewed member Gaming charge",
            "expected_order_status": "held",
            "expected_checkout_version": reviewed_order["checkout_version"],
            "expected_order_total_minor": reviewed_order["total_minor"],
            "expected_session_status": "ended",
            "expected_billing_revision": stopped.json()["billing_revision"],
            "expected_participant_revision": stopped.json()["participant_revision"],
            "expected_amount_minor": stopped.json()["amount_minor"],
        },
        headers={
            **owner_headers,
            "Idempotency-Key": f"member-owner-void:{uuid4()}",
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert voided.status_code == 200, voided.text
    assert (voided.json()["retained_addon_count"] == 1) is with_addon

    await session.rollback()
    receipt = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.gaming_session_id == gaming_session_id
            )
        )
    ).scalar_one()
    assert receipt.gaming_charge_line_id == charge_line_id
    assert receipt.original_amount_minor == 5_000
    assert receipt.charge_unit_price_minor == 4_500
    assert receipt.charge_line_total_minor == 4_500
    assert receipt.charge_discount_minor == 500
    assert receipt.charge_taxable_value_minor == 4_500


@pytest.mark.integration
@pytest.mark.asyncio
async def test_linked_gaming_void_and_payment_have_exactly_one_financial_winner(
    client,
    session,
    seed_owner,
) -> None:
    candidate = await _seed_linked_void_candidate(client, session, seed_owner)
    token = candidate["token"]
    order = candidate["order"]
    stopped = candidate["stopped"]
    order_id = order["id"]
    gaming_session_id = candidate["gaming_session_id"]
    claim_token = candidate["claim_token"]
    owner_void = client.post(
        f"/api/v1/pos/orders/{order_id}/gaming-void",
        json={
            "session_id": str(gaming_session_id),
            "reason": "Owner race review",
            "expected_order_status": "held",
            "expected_checkout_version": order["checkout_version"],
            "expected_order_total_minor": order["total_minor"],
            "expected_session_status": "ended",
            "expected_billing_revision": stopped["billing_revision"],
            "expected_participant_revision": stopped["participant_revision"],
            "expected_amount_minor": stopped["amount_minor"],
        },
        headers={
            **_headers(seed_owner, token, f"gaming-race-void:{uuid4()}"),
            "X-Checkout-Claim": claim_token,
        },
    )
    payment = client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        json={
            "method": "upi",
            "amount_minor": order["total_minor"],
            "ref_external": f"upi-gaming-race-{uuid4()}",
            "expected_order_total_minor": order["total_minor"],
            "expected_due_minor": order["total_minor"],
        },
        headers={
            **_headers(seed_owner, token, f"gaming-race-payment:{uuid4()}"),
            "X-Checkout-Claim": claim_token,
        },
    )
    void_result, payment_result = await asyncio.wait_for(
        asyncio.gather(owner_void, payment),
        timeout=15,
    )
    assert sum(
        (
            void_result.status_code == 200,
            payment_result.status_code == 201,
        )
    ) == 1, (void_result.text, payment_result.text)
    loser = payment_result if void_result.status_code == 200 else void_result
    assert loser.status_code in {409, 422}, loser.text

    await session.rollback()
    saved_order = (
        await session.execute(
            select(Order)
            .where(Order.id == order_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    saved_session = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == gaming_session_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    payments = (
        await session.execute(select(Payment).where(Payment.order_id == order_id))
    ).scalars().all()
    receipts = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.gaming_session_id == gaming_session_id
            )
        )
    ).scalars().all()
    assert saved_order is not None and saved_session is not None
    if payment_result.status_code == 201:
        assert saved_order.status == "paid"
        assert saved_session.status == "ended"
        assert saved_session.order_id == saved_order.id
        assert saved_session.retained_addon_order_id is None
        assert len(payments) == 1
        assert receipts == []
    else:
        assert saved_order.status == "held"
        assert saved_session.status == "cancelled"
        assert saved_session.order_id is None
        assert saved_session.retained_addon_order_id == saved_order.id
        assert payments == []
        assert len(receipts) == 1


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("failure_stage", ["after_charge", "after_mappings"])
async def test_linked_gaming_void_rolls_back_every_staged_mutation_on_failure(
    client,
    session,
    seed_owner,
    monkeypatch,
    failure_stage: str,
) -> None:
    candidate = await _seed_linked_void_candidate(client, session, seed_owner)
    token = candidate["token"]
    order = candidate["order"]
    stopped = candidate["stopped"]
    order_id = order["id"]
    gaming_session_id = candidate["gaming_session_id"]

    expected_failure: str
    if failure_stage == "after_charge":

        async def fail_after_charge_void(*_args, **_kwargs):
            raise RuntimeError("injected retained-order aggregation failure")

        monkeypatch.setattr(
            "app.api.v1.pos.router._reaggregate_active_order_lines",
            fail_after_charge_void,
        )
        expected_failure = "injected retained-order aggregation failure"
    else:

        def fail_after_mapping_flush(*_args, **_kwargs):
            raise RuntimeError("injected post-mapping audit failure")

        monkeypatch.setattr(
            "app.api.v1.pos.router.record_operation",
            fail_after_mapping_flush,
        )
        expected_failure = "injected post-mapping audit failure"

    with pytest.raises(RuntimeError, match=expected_failure):
        await client.post(
            f"/api/v1/pos/orders/{order_id}/gaming-void",
            json={
                "session_id": str(gaming_session_id),
                "reason": "Owner rollback review",
                "expected_order_status": "held",
                "expected_checkout_version": order["checkout_version"],
                "expected_order_total_minor": order["total_minor"],
                "expected_session_status": "ended",
                "expected_billing_revision": stopped["billing_revision"],
                "expected_participant_revision": stopped["participant_revision"],
                "expected_amount_minor": stopped["amount_minor"],
            },
            headers={
                **_headers(seed_owner, token, f"gaming-rollback-void:{uuid4()}"),
                "X-Checkout-Claim": candidate["claim_token"],
            },
        )

    await session.rollback()
    saved_order = (
        await session.execute(
            select(Order)
            .where(Order.id == order_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    saved_session = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == gaming_session_id)
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    active_lines = (
        await session.execute(
            select(OrderLine).where(
                OrderLine.order_id == order_id,
                OrderLine.voided_at.is_(None),
            )
        )
    ).scalars().all()
    receipts = (
        await session.execute(
            select(GamingVoidReceipt).where(
                GamingVoidReceipt.gaming_session_id == gaming_session_id
            )
        )
    ).scalars().all()
    mappings = (
        await session.execute(
            select(GamingVoidAddonLine).where(
                GamingVoidAddonLine.retained_order_id == order_id
            )
        )
    ).scalars().all()
    void_audits = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.entity_id == str(gaming_session_id),
                AuditLog.action == "gaming_pos_charge_voided",
            )
        )
    ).scalars().all()
    assert saved_order is not None and saved_order.status == "held"
    assert saved_session is not None and saved_session.status == "ended"
    assert saved_session.order_id == saved_order.id
    assert saved_session.retained_addon_order_id is None
    assert len(active_lines) == 2
    assert receipts == []
    assert mappings == []
    assert void_audits == []


async def _void_retention_staff(session, seed_owner):
    role = await session.scalar(select(Role).where(
        Role.company_id == seed_owner["company"].id, Role.code == "gaming_supervisor",
    ))
    if role is None:
        role = Role(id=uuid4(), company_id=seed_owner["company"].id,
                    code="gaming_supervisor", name="Gaming Supervisor", permissions=[])
        session.add(role)
    actor = User(id=uuid4(), company_id=seed_owner["company"].id,
                 email=f"retention-{uuid4().hex}@test.local", name="Retention staff",
                 password_hash=hash_password("password1234"), status="active")
    session.add(actor)
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=actor.id, role_id=role.id,
                         branch_id=seed_owner["branch"].id))
    await session.commit()
    await session.refresh(actor)
    return actor


def _void_retention_headers(seed_owner, actor, *, owner=False, terminal_id=None, branch_id=None):
    token = issue_access_token(
        user_id=actor.id, company_id=actor.company_id,
        roles=["owner" if owner else "gaming_supervisor"],
        branch_id=branch_id or seed_owner["branch"].id,
        auth_version=actor.auth_version,
    )
    return {"Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(terminal_id or seed_owner["terminal"].id)}


async def _void_retention_replacement(session, seed_owner):
    # Obey one-active-Hybrid-per-branch; do not weaken the production invariant.
    seed_owner["terminal"].is_active = False
    await session.flush()
    terminal = Terminal(id=uuid4(), branch_id=seed_owner["branch"].id,
                        name="Replacement workspace", purpose="hybrid", is_active=True,
                        device_id=f"retention-replacement-{uuid4()}")
    session.add(terminal)
    await session.flush()
    target = Shift(id=uuid4(), company_id=seed_owner["company"].id,
                   branch_id=seed_owner["branch"].id, terminal_id=terminal.id,
                   opened_by=seed_owner["owner"].id, opened_at=datetime.now(UTC),
                   opening_float_minor=0, expected_minor=0, status="open")
    session.add(target)
    await session.commit()
    return terminal, target


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("session_status,legacy_pause", [
    ("active", False), ("paused", False), ("paused", True), ("ended", False),
])
async def test_void_retention_targets_staff_opener_includes_same_hybrid_without_writes(
    client, session, seed_owner, session_status, legacy_pause,
):
    staff = await _void_retention_staff(session, seed_owner)
    shift, _station, _drink, gs = await _seed_live_session(session, seed_owner, opener=staff)
    gs.status = session_status
    gs.paused_at = datetime.now(UTC) if session_status == "paused" and not legacy_pause else None
    gs.end_at = datetime.now(UTC) if session_status == "ended" else None
    await session.commit()
    headers = _void_retention_headers(seed_owner, staff)
    before = tuple([await session.scalar(select(func.count()).select_from(model))
                    for model in (Order, GamingSessionAddon, GamingVoidReceipt, AuditLog, StockMovement, Payment)])
    response = await client.get(f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets", headers=headers)
    assert response.status_code == 200, response.text
    assert len(response.json()) == 1
    target = response.json()[0]
    assert target["shift_id"] == str(shift.id)
    assert target["terminal_id"] == str(shift.terminal_id)
    assert target["opened_by"] == str(staff.id)
    assert target["opened_by_name"] == staff.name
    assert target["terminal_name"] == seed_owner["terminal"].name
    assert target["opened_at"]
    after = tuple([await session.scalar(select(func.count()).select_from(model))
                   for model in (Order, GamingSessionAddon, GamingVoidReceipt, AuditLog, StockMovement, Payment)])
    assert after == before
    await session.refresh(gs)
    assert gs.status == session_status and gs.order_id is None
    assert gs.retained_addon_order_id is None
    # Handoff remains ended-only and still excludes the source Hybrid till.
    handoff = await client.get(f"/api/v1/gaming/sessions/{gs.id}/pos-target-shifts", headers=headers)
    assert handoff.status_code == (200 if session_status == "ended" else 422), handoff.text
    if session_status == "ended":
        assert handoff.json() == []


@pytest.mark.integration
@pytest.mark.asyncio
async def test_void_retention_targets_public_owner_override_and_recorded_other_denial(client, session, seed_owner):
    opener = await _void_retention_staff(session, seed_owner)
    shift, _station, _drink, gs = await _seed_live_session(session, seed_owner, opener=opener)
    other_staff = await _void_retention_staff(session, seed_owner)
    session.add(ShiftOperatorHandover(
        company_id=shift.company_id, shift_id=shift.id,
        branch_id=shift.branch_id, terminal_id=shift.terminal_id,
        initiated_by_user_id=seed_owner["owner"].id,
        previous_operator_user_id=None, incoming_user_id=opener.id,
        from_revision=0, to_revision=1,
        action_key=f"recorded-void-fixture:{uuid4()}", request_hash="0" * 64,
    ))
    await session.commit()
    await session.refresh(shift)
    assert shift.operator_user_id == opener.id and shift.operator_revision == 1
    denied = await client.get(f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets",
                              headers=_void_retention_headers(seed_owner, other_staff))
    assert denied.status_code == 422, denied.text
    owner_headers = _void_retention_headers(seed_owner, seed_owner["owner"], owner=True)
    me = await client.get("/api/v1/auth/me", headers=owner_headers)
    assert me.status_code == 200, me.text
    assert me.json()["protected_access"] is False
    assert me.json()["audit_access"] is False
    accepted = await client.get(f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets", headers=owner_headers)
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()[0]["shift_id"] == str(shift.id)


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("session_status", ["active", "paused", "ended"])
@pytest.mark.parametrize("is_owner", [False, True])
async def test_void_retention_targets_closed_source_only_owner_ended_cross_terminal(
    client, session, seed_owner, session_status, is_owner,
):
    staff = await _void_retention_staff(session, seed_owner)
    shift, _station, _drink, gs = await _seed_live_session(session, seed_owner, opener=staff)
    shift.status = "closed"
    shift.closed_by = seed_owner["owner"].id
    shift.closed_at = datetime.now(UTC)
    shift.counted_minor = shift.expected_minor
    shift.variance_minor = 0
    gs.status = session_status
    gs.paused_at = None  # Legacy unknown pause remains ineligible after close.
    gs.end_at = datetime.now(UTC) if session_status == "ended" else None
    await session.commit()
    terminal, target = await _void_retention_replacement(session, seed_owner)
    actor = seed_owner["owner"] if is_owner else staff
    headers = _void_retention_headers(seed_owner, actor, owner=is_owner, terminal_id=terminal.id)
    result = await client.get(f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets", headers=headers)
    if is_owner and session_status == "ended":
        assert result.status_code == 200, result.text
        assert [row["shift_id"] for row in result.json()] == [str(target.id)]
    else:
        assert result.status_code == 422, result.text


@pytest.mark.integration
@pytest.mark.asyncio
async def test_void_retention_targets_open_owner_still_requires_exact_source_terminal(client, session, seed_owner):
    shift, _station, _drink, gs = await _seed_live_session(session, seed_owner)
    terminal, _target = await _void_retention_replacement(session, seed_owner)
    response = await client.get(f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets",
        headers=_void_retention_headers(seed_owner, seed_owner["owner"], owner=True, terminal_id=terminal.id))
    assert response.status_code == 422, response.text
    assert "different terminal" in response.json()["error"]["message"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_void_retention_targets_permission_and_scope_denials(client, session, seed_owner):
    from app.models.access_control import RolePermissionOverride

    staff = await _void_retention_staff(session, seed_owner)
    shift, _station, _drink, gs = await _seed_live_session(session, seed_owner, opener=staff)
    await session.commit()
    path = f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets"
    headers = _void_retention_headers(seed_owner, staff)
    no_terminal = await client.get(path, headers={"Authorization": headers["Authorization"]})
    assert no_terminal.status_code == 422, no_terminal.text
    no_branch_token = issue_access_token(user_id=staff.id, company_id=staff.company_id,
        roles=["gaming_supervisor"], branch_id=None, auth_version=staff.auth_version)
    no_branch = await client.get(path, headers={"Authorization": f"Bearer {no_branch_token}"})
    assert no_branch.status_code == 422, no_branch.text
    anonymous = await client.get(path)
    assert anonymous.status_code == 401, anonymous.text
    for module in ("pos", "gaming"):
        override = RolePermissionOverride(id=uuid4(), company_id=staff.company_id,
            role_code="gaming_supervisor", module=module, allowed=False)
        session.add(override)
        await session.commit()
        await session.refresh(staff)
        denied = await client.get(path, headers=_void_retention_headers(seed_owner, staff))
        assert denied.status_code == 403, denied.text
        await session.delete(override)
        await session.commit()
    other_branch = Branch(id=uuid4(), company_id=staff.company_id, name="Other", invoice_series_code="OT")
    session.add(other_branch)
    await session.commit()
    await session.refresh(seed_owner["owner"])
    other_headers = _void_retention_headers(seed_owner, seed_owner["owner"], owner=True, branch_id=other_branch.id)
    cross_branch = await client.get(path, headers={"Authorization": other_headers["Authorization"]})
    assert cross_branch.status_code == 422, cross_branch.text
    foreign_company = Company(id=uuid4(), name="Foreign retention company")
    session.add(foreign_company)
    await session.flush()
    foreign_branch = Branch(id=uuid4(), company_id=foreign_company.id, name="Foreign", invoice_series_code="FR")
    session.add(foreign_branch)
    await session.commit()
    foreign_seed = {"company": foreign_company, "branch": foreign_branch}
    foreign_actor = await _void_retention_staff(session, foreign_seed)
    foreign_token = issue_access_token(user_id=foreign_actor.id, company_id=foreign_company.id,
        roles=["gaming_supervisor"], branch_id=foreign_branch.id, auth_version=foreign_actor.auth_version)
    cross_company = await client.get(path, headers={"Authorization": f"Bearer {foreign_token}"})
    assert cross_company.status_code == 404, cross_company.text


@pytest.mark.integration
@pytest.mark.asyncio
async def test_void_retention_targets_excludes_archived_closed_and_other_branch_destinations(client, session, seed_owner):
    shift, _station, _drink, gs = await _seed_live_session(session, seed_owner)
    for purpose in ("hybrid", "cafe_pos", "gaming"):
        archived = Terminal(id=uuid4(), branch_id=shift.branch_id, name=f"Archived {purpose}",
            purpose=purpose, is_active=False, device_id=f"retention-archived-{uuid4()}")
        session.add(archived)
        await session.flush()
        session.add(Shift(id=uuid4(), company_id=shift.company_id, branch_id=shift.branch_id,
            terminal_id=archived.id, opened_by=seed_owner["owner"].id, opened_at=datetime.now(UTC),
            opening_float_minor=0, expected_minor=0, status="open"))
    # A closed shift on the currently active terminal is also not a destination.
    session.add(Shift(id=uuid4(), company_id=shift.company_id, branch_id=shift.branch_id,
        terminal_id=shift.terminal_id, opened_by=seed_owner["owner"].id, opened_at=datetime.now(UTC),
        opening_float_minor=0, expected_minor=0, status="closed",
        closed_by=seed_owner["owner"].id, closed_at=datetime.now(UTC),
        counted_minor=0, variance_minor=0))
    other = Branch(id=uuid4(), company_id=shift.company_id, name="Other target branch", invoice_series_code="TB")
    session.add(other)
    await session.flush()
    other_terminal = Terminal(id=uuid4(), branch_id=other.id, name="Other active Hybrid", purpose="hybrid",
        is_active=True, device_id=f"retention-other-{uuid4()}")
    session.add(other_terminal)
    await session.flush()
    session.add(Shift(id=uuid4(), company_id=shift.company_id, branch_id=other.id,
        terminal_id=other_terminal.id, opened_by=seed_owner["owner"].id, opened_at=datetime.now(UTC),
        opening_float_minor=0, expected_minor=0, status="open"))
    await session.commit()
    result = await client.get(f"/api/v1/gaming/sessions/{gs.id}/void-retention-targets",
        headers=_void_retention_headers(seed_owner, seed_owner["owner"], owner=True))
    assert result.status_code == 200, result.text
    assert [row["shift_id"] for row in result.json()] == [str(shift.id)]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_void_retention_targets_rejects_cancelled_and_pos_linked_sessions(client, session, seed_owner):
    _shift, _station, _drink, gs = await _seed_live_session(session, seed_owner)
    headers = _void_retention_headers(seed_owner, seed_owner["owner"], owner=True)
    path = f"/api/v1/gaming/sessions/{gs.id}"
    # Reach both states through real commands so immutable receipt invariants stay intact.
    cancelled = await client.post(path + "/cancel",
        json={"reason": None, "expected_order_id": None, "expected_billing_revision": 0},
        headers={**headers, "Idempotency-Key": f"discovery-cancel:{uuid4()}"})
    assert cancelled.status_code == 200, cancelled.text
    rejected = await client.get(path + "/void-retention-targets", headers=headers)
    assert rejected.status_code == 422, rejected.text
    # Reuse the source shift; only the new station/session is required.
    station = Station(id=uuid4(), company_id=gs.company_id, branch_id=seed_owner["branch"].id,
        code=f"RET-LINK-{uuid4().hex[:8]}", name="Linked station", type="ps5",
        rate_per_hour_minor=12000, is_active=True, tax_rate=0, sac_code="999692", rate_includes_tax=True)
    session.add(station)
    await session.flush()
    linked = GamingSession(id=uuid4(), company_id=gs.company_id, station_id=station.id,
        opened_by=seed_owner["owner"].id, shift_id=gs.shift_id,
        start_at=datetime.now(UTC)-timedelta(minutes=10), end_at=datetime.now(UTC),
        paused_minutes=0, rate_per_hour_minor=12000, billing_mode="hourly",
        billable_minutes=10, amount_minor=2000, status="ended", extra_controllers=0,
        tax_rate=0, sac_code="999692", rate_includes_tax=True)
    session.add(linked)
    await session.commit()
    sent = await client.post(f"/api/v1/gaming/sessions/{linked.id}/send-to-pos",
        headers={**headers, "Idempotency-Key": f"discovery-link:{uuid4()}"})
    assert sent.status_code == 201, sent.text
    linked_path = f"/api/v1/gaming/sessions/{linked.id}/void-retention-targets"
    owner_rejected = await client.get(linked_path, headers=headers)
    assert owner_rejected.status_code == 422, owner_rejected.text
    staff = await _void_retention_staff(session, seed_owner)
    staff_rejected = await client.get(linked_path, headers=_void_retention_headers(seed_owner, staff))
    assert staff_rejected.status_code == 403, staff_rejected.text


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("reason", [None, "   "])
async def test_retained_addon_void_after_30_requires_reason_without_side_effects(client, session, seed_owner, reason):
    shift, _station, drink, gs = await _seed_live_session(session, seed_owner)
    gs.start_at = datetime.now(UTC) - timedelta(minutes=31)
    await session.commit()
    token = await _login(client, seed_owner)
    headers = _headers(seed_owner, token)
    added = await client.post(f"/api/v1/gaming/sessions/{gs.id}/addons", json={
        "client_line_id": str(uuid4()), "menu_item_id": str(drink.id),
        "qty": 1, "expected_unit_price_minor": 1_249,
    }, headers={**headers, "Idempotency-Key": f"after30-addon:{uuid4()}"})
    assert added.status_code == 201, added.text
    key = f"after30-void:{uuid4()}"
    response = await client.post(f"/api/v1/gaming/sessions/{gs.id}/cancel", json={
        "reason": reason, "expected_order_id": None,
        "expected_billing_revision": 0, "retained_target_shift_id": str(shift.id),
    }, headers={**headers, "Idempotency-Key": key})
    assert response.status_code == 422, response.text
    assert response.json()["error"]["details"]["issue"] == "gaming_void_reason_required"
    current = await client.get(f"/api/v1/gaming/sessions/{gs.id}", headers=headers)
    assert current.status_code == 200 and current.json()["status"] == "active", current.text
    assert current.json()["retained_addon_order_id"] is None
    assert await session.scalar(select(func.count(GamingVoidReceipt.id)).where(
        GamingVoidReceipt.gaming_session_id == gs.id)) == 0
    assert await session.scalar(select(func.count(Order.id)).where(Order.company_id == gs.company_id)) == 0
    assert await session.scalar(select(func.count(AuditLog.id)).where(
        AuditLog.entity_id == str(gs.id), AuditLog.action.in_(["gaming_session_stopped", "gaming_session_voided"]))) == 0

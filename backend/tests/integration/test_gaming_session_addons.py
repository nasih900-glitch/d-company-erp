"""Postgres contracts for server-priced Gaming session add-ons."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import hash_password, issue_access_token
from app.core.timezone import local_today
from app.models import (
    AuditLog,
    Batch,
    Branch,
    Company,
    GamingSession,
    GamingSessionAddon,
    Ingredient,
    MenuCategory,
    MenuItem,
    MenuVariant,
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
        opened_by=seed_owner["owner"].id,
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
        opened_by=seed_owner["owner"].id,
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
        headers=_headers(seed_owner, token),
    )
    assert blocked_cancel.status_code == 422, blocked_cancel.text
    assert "Void every active Gaming add-on" in blocked_cancel.json()["error"]["message"]

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

    with pytest.raises(DBAPIError, match="must be voided before session cancellation"):
        await session.execute(
            text(
                "UPDATE gaming_sessions SET status = 'cancelled' "
                "WHERE id = :session_id"
            ),
            {"session_id": str(gaming_session_id)},
        )
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

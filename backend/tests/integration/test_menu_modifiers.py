"""PostgreSQL/API proof for modifier configuration and authoritative pricing."""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.errors import BusinessRuleError
from app.core.security import issue_access_token
from app.models import (
    Company,
    Floor,
    MenuCategory,
    MenuItem,
    MenuModifier,
    MenuModifierGroup,
    MenuVariant,
    Order,
    OrderLine,
    Role,
    Shift,
    Table,
    User,
    UserRole,
)
from app.services.pos.pricing import (
    LineRequest,
    MembershipDiscountRates,
    ModifierSelection,
    OrderPricingService,
)


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _menu_item(
    session,
    *,
    company_id,
    name: str = "Coffee",
    base_price_minor: int = 10_000,
    tax_rate: Decimal = Decimal("0.05"),
) -> MenuItem:
    category = MenuCategory(
        id=uuid4(),
        company_id=company_id,
        name=f"Category-{uuid4()}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company_id,
        category_id=category.id,
        sku=f"SKU-{uuid4()}",
        name=name,
        type="drink",
        base_price_minor=base_price_minor,
        tax_rate=tax_rate,
        hsn_code="996331",
        price_includes_tax=True,
        is_available=True,
    )
    session.add_all([category, item])
    await session.flush()
    return item


async def _owner_headers(client, session, seed_owner) -> dict[str, str]:
    owner = seed_owner["owner"]
    protected_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="super_owner",
        name="Protected owner",
        permissions=[],
    )
    session.add(protected_role)
    await session.flush()
    user_role = (
        await session.execute(select(UserRole).where(UserRole.user_id == owner.id))
    ).scalar_one()
    user_role.role_id = protected_role.id
    user_role.branch_id = seed_owner["branch"].id
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    authorization = f"Bearer {login.json()['access_token']}"
    unlock = await client.post(
        "/api/v1/admin/pricing/unlock",
        headers={"Authorization": authorization},
        json={"password": seed_owner["password"]},
    )
    assert unlock.status_code == 200, unlock.text
    return {
        "Authorization": authorization,
        "X-Pricing-Token": unlock.json()["pricing_token"],
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_modifier_crud_is_nested_tenant_scoped_permissioned_and_unlocked(
    client,
    session,
    seed_owner,
) -> None:
    item = await _menu_item(session, company_id=seed_owner["company"].id)
    await session.commit()
    headers = await _owner_headers(client, session, seed_owner)

    missing_unlock = await client.post(
        f"/api/v1/menu/items/{item.id}/modifier-groups",
        headers={"Authorization": headers["Authorization"]},
        json={"name": "Milk", "min_select": 0, "max_select": 2},
    )
    assert missing_unlock.status_code == 401

    staff = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"staff-{uuid4()}@test.local",
        name="Staff",
        password_hash="not-used",
        status="active",
    )
    staff_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "staff",
            )
        )
    ).scalar_one()
    session.add_all(
        [
            staff,
            UserRole(
                id=uuid4(),
                user_id=staff.id,
                role_id=staff_role.id,
                branch_id=seed_owner["branch"].id,
            ),
        ]
    )
    await session.commit()
    await session.refresh(staff)
    staff_token = issue_access_token(
        user_id=staff.id,
        company_id=staff.company_id,
        roles=["staff"],
        branch_id=seed_owner["branch"].id,
        auth_version=staff.auth_version,
    )
    forbidden = await client.post(
        f"/api/v1/menu/items/{item.id}/modifier-groups",
        headers={
            "Authorization": f"Bearer {staff_token}",
            "X-Pricing-Token": headers["X-Pricing-Token"],
        },
        json={"name": "Milk", "min_select": 0, "max_select": 2},
    )
    assert forbidden.status_code == 403

    create_group = await client.post(
        f"/api/v1/menu/items/{item.id}/modifier-groups",
        headers={**headers, "Idempotency-Key": f"group-{uuid4()}"},
        json={
            "name": " Milk ",
            "min_select": 0,
            "max_select": 2,
            "sort_order": 1,
        },
    )
    assert create_group.status_code == 201, create_group.text
    group_id = create_group.json()["id"]

    create_option = await client.post(
        f"/api/v1/menu/modifier-groups/{group_id}/options",
        headers={**headers, "Idempotency-Key": f"option-{uuid4()}"},
        json={
            "name": " Oat milk ",
            "price_delta_minor": 250,
            "max_quantity": 2,
        },
    )
    assert create_option.status_code == 201, create_option.text
    option_id = create_option.json()["id"]

    activate_required = await client.patch(
        f"/api/v1/menu/modifier-groups/{group_id}",
        headers=headers,
        json={"min_select": 1},
    )
    assert activate_required.status_code == 200, activate_required.text

    create_variant = await client.post(
        f"/api/v1/menu/items/{item.id}/variants",
        headers={**headers, "Idempotency-Key": f"variant-{uuid4()}"},
        json={"name": "Large", "price_delta_minor": 1000},
    )
    assert create_variant.status_code == 201, create_variant.text

    menu = await client.get("/api/v1/menu/items", headers=headers)
    assert menu.status_code == 200, menu.text
    read = next(row for row in menu.json() if row["id"] == str(item.id))
    assert read["variants"] == [
        {
            "id": create_variant.json()["id"],
            "name": "Large",
            "price_delta_minor": 1000,
            "sort_order": 0,
            "is_active": True,
        }
    ]
    assert read["modifier_groups"][0]["name"] == "Milk"
    assert read["modifier_groups"][0]["min_select"] == 1
    assert read["modifier_groups"][0]["options"][0]["id"] == option_id
    assert read["modifier_groups"][0]["options"][0]["name"] == "Oat milk"

    duplicate = await client.post(
        f"/api/v1/menu/modifier-groups/{group_id}/options",
        headers={**headers, "Idempotency-Key": f"option-{uuid4()}"},
        json={"name": "oAT MILK", "price_delta_minor": 300},
    )
    assert duplicate.status_code == 409

    impossible_deactivation = await client.delete(
        f"/api/v1/menu/modifiers/{option_id}",
        headers=headers,
    )
    assert impossible_deactivation.status_code == 422

    other_company = Company(id=uuid4(), name=f"Other-{uuid4()}")
    session.add(other_company)
    await session.flush()
    other_item = await _menu_item(session, company_id=other_company.id)
    await session.commit()
    cross_tenant = await client.post(
        f"/api/v1/menu/items/{other_item.id}/modifier-groups",
        headers={**headers, "Idempotency-Key": f"group-{uuid4()}"},
        json={"name": "Must not leak", "min_select": 0, "max_select": 1},
    )
    assert cross_tenant.status_code == 404


@pytest.mark.integration
@pytest.mark.asyncio
async def test_modifier_and_variant_deltas_precede_discount_tax_and_rounding(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    company.gst_registration_type = "regular"
    company.is_composition = False
    company.gstin = "32ABCDE1234F1Z5"
    branch.state_code = "32"
    item = await _menu_item(session, company_id=company.id)
    variant = MenuVariant(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        name="Large",
        price_delta_minor=1000,
        sort_order=0,
        is_active=True,
    )
    group = MenuModifierGroup(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        name="Extras",
        min_select=0,
        max_select=2,
        sort_order=0,
        is_active=True,
    )
    option = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        modifier_group_id=group.id,
        name="Extra shot",
        price_delta_minor=2500,
        max_quantity=2,
        sort_order=0,
        is_active=True,
    )
    session.add_all([variant, group])
    await session.flush()
    session.add(option)
    await session.flush()

    pricing = OrderPricingService(session)

    async def membership_rates(**_kwargs) -> MembershipDiscountRates:
        return MembershipDiscountRates(food=Decimal("0.10"))

    pricing._membership_discount_rates = membership_rates  # type: ignore[method-assign]
    request = LineRequest(
        menu_item_id=item.id,
        qty=2,
        variant_id=variant.id,
        modifiers=(ModifierSelection(modifier_id=option.id, quantity=2),),
    )
    priced = await pricing.price_order(
        company_id=company.id,
        branch_id=branch.id,
        line_requests=[request],
    )
    line = priced.lines[0]

    assert line.base_unit_price_minor == 10_000
    assert line.customization_unit_delta_minor == 6000
    assert line.discount_minor == 3200
    assert line.line_inclusive_minor == 28_800
    assert line.unit_inclusive_minor == 14_400
    assert line.taxable_value_minor == 27_429
    assert line.cgst_minor + line.sgst_minor == 1371
    assert priced.round_off_minor == 0
    assert priced.total_minor == 28_800
    assert line.variant_snapshot is not None
    assert line.variant_snapshot.as_dict()["line_delta_minor"] == 2000
    assert line.modifier_snapshots[0].as_dict()["line_delta_minor"] == 10_000

    # The first result is an immutable historical snapshot, not a live ORM row.
    option.name = "Double shot"
    option.price_delta_minor = 3000
    variant.name = "Extra large"
    variant.price_delta_minor = 2000
    await session.flush()
    assert line.variant_snapshot.name == "Large"
    assert line.modifier_snapshots[0].name == "Extra shot"
    assert line.modifier_snapshots[0].unit_price_delta_minor == 2500

    repriced = await pricing.price_order(
        company_id=company.id,
        branch_id=branch.id,
        line_requests=[request],
    )
    assert repriced.lines[0].customization_unit_delta_minor == 8000
    assert repriced.lines[0].line_inclusive_minor == 32_400


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pricing_rejects_required_duplicate_over_limit_inactive_and_wrong_scope(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    company.gst_registration_type = "unregistered"
    company.is_composition = False
    branch.state_code = "32"
    item = await _menu_item(session, company_id=company.id)
    other_item = await _menu_item(session, company_id=company.id, name="Tea")
    group = MenuModifierGroup(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        name="Milk",
        min_select=1,
        max_select=2,
        sort_order=0,
        is_active=True,
    )
    option_a = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        modifier_group_id=group.id,
        name="Oat",
        price_delta_minor=100,
        max_quantity=2,
        sort_order=0,
        is_active=True,
    )
    option_b = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        modifier_group_id=group.id,
        name="Soy",
        price_delta_minor=200,
        max_quantity=1,
        sort_order=1,
        is_active=True,
    )
    other_group = MenuModifierGroup(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=other_item.id,
        name="Tea extras",
        min_select=0,
        max_select=1,
        sort_order=0,
        is_active=True,
    )
    wrong_item_option = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=other_item.id,
        modifier_group_id=other_group.id,
        name="Lemon",
        price_delta_minor=50,
        max_quantity=1,
        sort_order=0,
        is_active=True,
    )
    wrong_item_variant = MenuVariant(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=other_item.id,
        name="Pot",
        price_delta_minor=500,
        sort_order=0,
        is_active=True,
    )
    session.add_all([group, other_group, wrong_item_variant])
    await session.flush()
    session.add_all([option_a, option_b, wrong_item_option])
    await session.flush()
    pricing = OrderPricingService(session)

    async def price(*, modifiers=(), variant_id=None):
        return await pricing.price_order(
            company_id=company.id,
            branch_id=branch.id,
            line_requests=[
                LineRequest(
                    menu_item_id=item.id,
                    qty=1,
                    variant_id=variant_id,
                    modifiers=modifiers,
                )
            ],
        )

    with pytest.raises(BusinessRuleError, match="requires at least 1"):
        await price()
    duplicate = ModifierSelection(option_a.id, 1)
    with pytest.raises(BusinessRuleError, match="only once"):
        await price(modifiers=(duplicate, duplicate))
    with pytest.raises(BusinessRuleError, match="at most 2 per item"):
        await price(modifiers=(ModifierSelection(option_a.id, 3),))
    with pytest.raises(BusinessRuleError, match="allows at most 2 selection"):
        await price(
            modifiers=(
                ModifierSelection(option_a.id, 2),
                ModifierSelection(option_b.id, 1),
            )
        )
    option_b.is_active = False
    await session.flush()
    with pytest.raises(BusinessRuleError, match="not active for this menu item"):
        await price(modifiers=(ModifierSelection(option_b.id, 1),))
    with pytest.raises(BusinessRuleError, match="not active for this menu item"):
        await price(modifiers=(ModifierSelection(wrong_item_option.id, 1),))
    with pytest.raises(BusinessRuleError, match="variant is not active"):
        await price(
            modifiers=(ModifierSelection(option_a.id, 1),),
            variant_id=wrong_item_variant.id,
        )

    other_company = Company(id=uuid4(), name=f"Other-{uuid4()}")
    session.add(other_company)
    await session.flush()
    foreign_item = await _menu_item(session, company_id=other_company.id, name="Foreign")
    foreign_group = MenuModifierGroup(
        id=uuid4(),
        company_id=other_company.id,
        menu_item_id=foreign_item.id,
        name="Foreign choices",
        min_select=0,
        max_select=1,
        sort_order=0,
        is_active=True,
    )
    foreign_option = MenuModifier(
        id=uuid4(),
        company_id=other_company.id,
        menu_item_id=foreign_item.id,
        modifier_group_id=foreign_group.id,
        name="Foreign option",
        price_delta_minor=1,
        max_quantity=1,
        sort_order=0,
        is_active=True,
    )
    session.add(foreign_group)
    await session.flush()
    session.add(foreign_option)
    await session.flush()
    with pytest.raises(BusinessRuleError, match="not active for this menu item"):
        await price(modifiers=(ModifierSelection(foreign_option.id, 1),))


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pos_create_without_optional_customizations_uses_sql_null_snapshots(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company.gst_registration_type = "unregistered"
    branch.state_code = "32"
    item = await _menu_item(
        session,
        company_id=company.id,
        name="Plain cappuccino",
        base_price_minor=18_000,
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add(shift)
    await session.commit()

    headers = await _owner_headers(client, session, seed_owner)
    headers["X-Terminal-Id"] = str(terminal.id)
    created = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": f"plain-{uuid4()}"},
        json={
            "type": "dine_in",
            "shift_id": str(shift.id),
            "lines": [
                {
                    "client_line_id": str(uuid4()),
                    "menu_item_id": str(item.id),
                    "qty": 2,
                }
            ],
        },
    )

    assert created.status_code == 201, created.text
    response_line = created.json()["lines"][0]
    assert response_line["variant_snapshot"] is None
    assert response_line["modifiers"] is None
    persisted = (
        await session.execute(
            select(OrderLine).where(OrderLine.order_id == created.json()["id"])
        )
    ).scalar_one()
    assert persisted.variant_snapshot is None
    assert persisted.modifiers is None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pos_create_append_receipt_and_kds_use_authoritative_snapshots(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company.gst_registration_type = "regular"
    company.is_composition = False
    company.gstin = "32ABCDE1234F1Z5"
    branch.state_code = "32"

    item = await _menu_item(
        session,
        company_id=company.id,
        name="Configured coffee",
        base_price_minor=10_003,
    )
    other_item = await _menu_item(
        session,
        company_id=company.id,
        name="Other drink",
    )
    variant = MenuVariant(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        name="Large",
        price_delta_minor=101,
        sort_order=0,
        is_active=True,
    )
    inactive_variant = MenuVariant(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        name="Retired",
        price_delta_minor=1,
        sort_order=1,
        is_active=False,
    )
    group = MenuModifierGroup(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        name="Shots",
        min_select=1,
        max_select=2,
        sort_order=0,
        is_active=True,
    )
    other_group = MenuModifierGroup(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=other_item.id,
        name="Other extras",
        min_select=0,
        max_select=1,
        sort_order=0,
        is_active=True,
    )
    floor = Floor(id=uuid4(), branch_id=branch.id, name="Main")
    table = Table(
        id=uuid4(),
        floor_id=floor.id,
        code="M1",
        seats=2,
        status="available",
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add_all(
        [
            variant,
            inactive_variant,
            group,
            other_group,
            floor,
            table,
            shift,
        ]
    )
    await session.flush()
    option = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        modifier_group_id=group.id,
        name="Extra shot",
        price_delta_minor=49,
        max_quantity=2,
        sort_order=0,
        is_active=True,
    )
    inactive_option = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=item.id,
        modifier_group_id=group.id,
        name="Unavailable shot",
        price_delta_minor=1,
        max_quantity=1,
        sort_order=1,
        is_active=False,
    )
    wrong_item_option = MenuModifier(
        id=uuid4(),
        company_id=company.id,
        menu_item_id=other_item.id,
        modifier_group_id=other_group.id,
        name="Lemon",
        price_delta_minor=50,
        max_quantity=1,
        sort_order=0,
        is_active=True,
    )
    session.add_all([option, inactive_option, wrong_item_option])
    await session.commit()

    headers = await _owner_headers(client, session, seed_owner)
    headers["X-Terminal-Id"] = str(terminal.id)

    def order_payload(*, variant_id=None, modifiers=None, qty: int = 2):
        return {
            "type": "dine_in",
            "table_id": str(table.id),
            "shift_id": str(shift.id),
            "lines": [
                {
                    "client_line_id": str(uuid4()),
                    "menu_item_id": str(item.id),
                    "variant_id": str(variant_id) if variant_id else None,
                    "qty": qty,
                    "modifiers": modifiers or [],
                    "note": "Half sugar",
                }
            ],
        }

    invalid_lines = [
        # A required group cannot be bypassed by an old/default client.
        order_payload(variant_id=variant.id, modifiers=[]),
        # The same option cannot be split into duplicate entries.
        order_payload(
            variant_id=variant.id,
            modifiers=[
                {"modifier_id": str(option.id), "qty": 1},
                {"modifier_id": str(option.id), "qty": 1},
            ],
        ),
        order_payload(
            variant_id=variant.id,
            modifiers=[{"modifier_id": str(option.id), "qty": 3}],
        ),
        order_payload(
            variant_id=variant.id,
            modifiers=[{"modifier_id": str(inactive_option.id), "qty": 1}],
        ),
        order_payload(
            variant_id=variant.id,
            modifiers=[{"modifier_id": str(wrong_item_option.id), "qty": 1}],
        ),
        order_payload(
            variant_id=inactive_variant.id,
            modifiers=[{"modifier_id": str(option.id), "qty": 1}],
        ),
    ]
    for payload in invalid_lines:
        rejected = await client.post(
            "/api/v1/pos/orders",
            headers={**headers, "Idempotency-Key": f"invalid-{uuid4()}"},
            json=payload,
        )
        assert rejected.status_code == 422, rejected.text

    client_price = order_payload(
        variant_id=variant.id,
        modifiers=[
            {
                "modifier_id": str(option.id),
                "qty": 1,
                "price_delta_minor": 1,
            }
        ],
    )
    rejected_client_price = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": f"invalid-price-{uuid4()}"},
        json=client_price,
    )
    assert rejected_client_price.status_code == 422
    assert (
        await session.execute(
            select(Order).where(Order.table_id == table.id)
        )
    ).scalar_one_or_none() is None

    created = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": f"configured-{uuid4()}"},
        json=order_payload(
            variant_id=variant.id,
            modifiers=[{"modifier_id": str(option.id), "qty": 2}],
        ),
    )
    assert created.status_code == 201, created.text
    first_receipt = created.json()
    first_line = first_receipt["lines"][0]
    assert first_line["unit_price_minor"] == 10_202
    assert first_line["line_total_minor"] == 20_404
    assert first_line["variant_snapshot"] == {
        "variant_id": str(variant.id),
        "name": "Large",
        "price_delta_minor": 101,
        "line_delta_minor": 202,
    }
    assert first_line["modifiers"] == [
        {
            "modifier_id": str(option.id),
            "modifier_group_id": str(group.id),
            "group_name": "Shots",
            "name": "Extra shot",
            "qty": 2,
            "price_delta_minor": 49,
            "per_item_delta_minor": 98,
            "line_delta_minor": 196,
        }
    ]
    assert first_receipt["subtotal_minor"] == 19_432
    assert first_receipt["tax_minor"] == 972
    assert first_receipt["round_off_minor"] == -4
    assert first_receipt["total_minor"] == 20_400

    queue = await client.get("/api/v1/kitchen/queue", headers=headers)
    assert queue.status_code == 200, queue.text
    kitchen_line = next(
        line
        for ticket in queue.json()
        if ticket["id"] == first_receipt["id"]
        for line in ticket["lines"]
    )
    assert kitchen_line["variant_snapshot"] == first_line["variant_snapshot"]
    assert kitchen_line["modifiers"] == first_line["modifiers"]
    assert kitchen_line["notes"] == "Half sugar"

    # Catalog edits affect only the next round; the first receipt snapshot is
    # immutable and remains exactly what the customer originally approved.
    variant.name = "Extra large"
    variant.price_delta_minor = 201
    option.name = "Double shot"
    option.price_delta_minor = 59
    await session.commit()
    appended = await client.post(
        f"/api/v1/pos/orders/{first_receipt['id']}/lines",
        headers={**headers, "Idempotency-Key": f"append-configured-{uuid4()}"},
        json={
            "expected_checkout_version": first_receipt["checkout_version"],
            "lines": order_payload(
                variant_id=variant.id,
                modifiers=[{"modifier_id": str(option.id), "qty": 2}],
                qty=1,
            )["lines"],
        },
    )
    assert appended.status_code == 200, appended.text
    appended_receipt = appended.json()
    assert appended_receipt["lines"][0]["variant_snapshot"]["name"] == "Large"
    assert appended_receipt["lines"][0]["modifiers"][0]["name"] == "Extra shot"
    second_line = appended_receipt["lines"][1]
    assert second_line["variant_snapshot"]["name"] == "Extra large"
    assert second_line["modifiers"][0]["name"] == "Double shot"
    assert second_line["unit_price_minor"] == 10_322
    assert second_line["line_total_minor"] == 10_322
    assert (
        appended_receipt["subtotal_minor"]
        + appended_receipt["tax_minor"]
        + appended_receipt["round_off_minor"]
        == appended_receipt["total_minor"]
    )

    receipt = await client.get(
        f"/api/v1/pos/orders/{first_receipt['id']}",
        headers=headers,
    )
    assert receipt.status_code == 200, receipt.text
    assert receipt.json()["lines"] == appended_receipt["lines"]
    queue = await client.get("/api/v1/kitchen/queue", headers=headers)
    ticket = next(row for row in queue.json() if row["id"] == first_receipt["id"])
    assert [line["variant_snapshot"]["name"] for line in ticket["lines"]] == [
        "Large",
        "Extra large",
    ]
    assert [line["modifiers"][0]["name"] for line in ticket["lines"]] == [
        "Extra shot",
        "Double shot",
    ]

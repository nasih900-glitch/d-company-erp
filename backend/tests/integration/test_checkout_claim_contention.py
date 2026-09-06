"""Real PostgreSQL proof that two checkout requests serialize by order row."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from fastapi import Response
from sqlalchemy import delete, select, text

from app.api.v1.pos.router import claim_order_for_checkout
from app.core.db import AsyncSessionLocal
from app.core.errors import CheckoutClaimConflictError
from app.core.security import hash_password, issue_access_token
from app.core.tenant import TenantContext
from app.models import (
    AuditLog,
    Floor,
    IdempotencyKey,
    InvoiceCounter,
    MenuCategory,
    MenuItem,
    Order,
    OrderCheckoutClaim,
    OrderLine,
    Payment,
    Shift,
    Table,
    User,
)


async def _set_paid_source_cleanup_triggers(session, *, enabled: bool) -> None:
    """Narrow test-only cleanup bypass; production exposes no bypass path."""
    verb = "ENABLE" if enabled else "DISABLE"
    for table_name, trigger_name in (
        ("payments", "trg_payments_immutable"),
        ("payments", "trg_payments_final_order_balance"),
        ("orders", "trg_orders_paid_source_integrity"),
        ("orders", "trg_orders_final_payment_balance"),
        ("order_lines", "trg_order_lines_paid_source_integrity"),
    ):
        await session.execute(
            text(f"ALTER TABLE {table_name} {verb} TRIGGER {trigger_name}")
        )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_held_order_list_version_matches_claim_after_send_to_pos(
    client,
    session,
    seed_owner,
) -> None:
    """The queue and claim endpoints must describe the same bill version.

    ``send-to-pos`` changes checkout-relevant order fields, so migration 0031
    increments ``checkout_version``. Android selects a held-order list row and
    then acquires a claim; if the list omits or defaults that version, the
    client correctly rejects its fresh claim as stale forever.
    """
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Checkout contract {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"CLAIM-{uuid4().hex[:10]}",
        name="Checkout contract item",
        type="food",
        base_price_minor=2_500,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
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
    floor = Floor(
        id=uuid4(),
        branch_id=branch.id,
        name=f"Checkout floor {uuid4().hex[:8]}",
    )
    table = Table(
        id=uuid4(),
        floor_id=floor.id,
        code=f"C-{uuid4().hex[:6]}",
        seats=2,
        status="occupied",
    )
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        table_id=table.id,
        type="dine_in",
        status="open",
        subtotal_minor=2_500,
        tax_minor=0,
        total_minor=2_500,
        opened_at=datetime.now(UTC),
    )
    line = OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=2_500,
        line_total_minor=2_500,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=2_500,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="ready",
    )
    # These models expose foreign-key ids rather than ORM relationships, so
    # flush each dependency layer explicitly instead of relying on unit-of-work
    # relationship ordering.
    session.add_all([category, shift, floor])
    await session.flush()
    session.add_all([item, table])
    await session.flush()
    session.add(order)
    await session.flush()
    session.add(line)
    await session.commit()
    await session.refresh(order)
    open_version = order.checkout_version

    access_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    headers = {
        "Authorization": f"Bearer {access_token}",
        "X-Terminal-Id": str(terminal.id),
    }

    send_key = f"send-contract-{uuid4()}"
    try:
        sent = await client.patch(
            f"/api/v1/pos/orders/{order.id}/send-to-pos",
            headers={**headers, "Idempotency-Key": send_key},
            json={"expected_checkout_version": open_version},
        )
        assert sent.status_code == 200, sent.text
        sent_version = sent.json()["checkout_version"]
        assert sent_version > open_version

        listed = await client.get(
            "/api/v1/pos/orders",
            headers=headers,
            params={"status": "held"},
        )
        assert listed.status_code == 200, listed.text
        list_row = next(row for row in listed.json() if row["id"] == str(order.id))
        assert list_row["checkout_version"] == sent_version

        claimed = await client.post(
            f"/api/v1/pos/orders/{order.id}/checkout-claim",
            headers=headers,
        )
        assert claimed.status_code == 201, claimed.text
        assert claimed.json()["order_version"] == list_row["checkout_version"]
    finally:
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(OrderCheckoutClaim).where(OrderCheckoutClaim.order_id == order.id)
            )
            await cleanup.execute(delete(OrderLine).where(OrderLine.order_id == order.id))
            await cleanup.execute(delete(Order).where(Order.id == order.id))
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(delete(MenuCategory).where(MenuCategory.id == category.id))
            await cleanup.execute(delete(Table).where(Table.id == table.id))
            await cleanup.execute(delete(Floor).where(Floor.id == floor.id))
            await cleanup.execute(
                delete(IdempotencyKey).where(IdempotencyKey.key == send_key)
            )
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_direct_publish_claim_and_legacy_protected_recovery(
    client,
    session,
    seed_owner,
) -> None:
    """New direct checkout is atomic; orphaned old drafts have owner recovery."""
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company.gst_registration_type = "unregistered"
    company.is_composition = False
    branch.state_code = "32"
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Direct checkout {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"DIRECT-{uuid4().hex[:10]}",
        name="Direct checkout item",
        type="food",
        base_price_minor=2_500,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
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
    abandoned_opener = User(
        id=uuid4(),
        company_id=company.id,
        email=f"abandoned-{uuid4().hex[:8]}@test.local",
        name="Abandoned checkout cashier",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add_all([category, shift, abandoned_opener])
    await session.flush()
    session.add(item)
    await session.commit()

    access_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    headers = {
        "Authorization": f"Bearer {access_token}",
        "X-Terminal-Id": str(terminal.id),
    }
    publish_create_key = f"direct-publish-source-{uuid4()}"
    publish_key = f"direct-publish-{uuid4()}"
    legacy_create_key = f"direct-open-{uuid4()}"
    foreign_guard_keys = {
        action: f"direct-foreign-{action}-{uuid4()}"
        for action in (
            "append",
            "customer",
            "discount",
            "points",
            "reward",
            "publish",
            "finalize-zero",
            "payment",
        )
    }
    recovery_key = f"direct-recovery-{uuid4()}"
    published_order_id = None
    legacy_order_id = None
    try:
        payload = {
            "type": "takeaway",
            "shift_id": str(shift.id),
            "lines": [{"menu_item_id": str(item.id), "qty": 1}],
        }
        publish_source = await client.post(
            "/api/v1/pos/orders",
            headers={**headers, "Idempotency-Key": publish_create_key},
            json=payload,
        )
        assert publish_source.status_code == 201, publish_source.text
        publish_source_order = publish_source.json()
        published_order_id = publish_source_order["id"]
        assert publish_source_order["status"] == "open"

        client_a = uuid4()
        client_b = uuid4()
        publish_headers = {
            **headers,
            "Idempotency-Key": publish_key,
            "X-Checkout-Client-Instance": str(client_a),
        }
        published = await client.post(
            f"/api/v1/pos/orders/{published_order_id}/publish-checkout-claim",
            headers=publish_headers,
            json={
                "expected_checkout_version": publish_source_order["checkout_version"],
            },
        )
        assert published.status_code == 201, published.text
        assert published.headers["cache-control"] == "no-store"
        assert published.json()["order_version"] == (
            publish_source_order["checkout_version"] + 1
        )
        assert published.json()["reused"] is False

        # A dropped response is recovered without persisting the bearer: the
        # same installation renews/rotates it against the one expected status
        # version bump, while another physical client cannot take it over.
        publish_replay = await client.post(
            f"/api/v1/pos/orders/{published_order_id}/publish-checkout-claim",
            headers=publish_headers,
            json={
                "expected_checkout_version": publish_source_order["checkout_version"],
            },
        )
        assert publish_replay.status_code == 201, publish_replay.text
        assert publish_replay.json()["reused"] is True
        assert publish_replay.json()["claim_token"] != published.json()["claim_token"]

        competing_publish = await client.post(
            f"/api/v1/pos/orders/{published_order_id}/publish-checkout-claim",
            headers={
                **headers,
                "Idempotency-Key": f"competing-publish-{uuid4()}",
                "X-Checkout-Client-Instance": str(client_b),
            },
            json={
                "expected_checkout_version": publish_source_order["checkout_version"],
            },
        )
        assert competing_publish.status_code == 409, competing_publish.text
        assert competing_publish.json()["error"]["code"] == "checkout_claim_conflict"

        legacy_create = await client.post(
            "/api/v1/pos/orders",
            headers={**headers, "Idempotency-Key": legacy_create_key},
            json=payload,
        )
        assert legacy_create.status_code == 201, legacy_create.text
        legacy_order = legacy_create.json()
        legacy_order_id = legacy_order["id"]
        assert legacy_order["status"] == "open"
        assert legacy_order["held_at"] is None

        # Simulate a legacy private draft left by another cashier. Protected
        # discovery is intentional, but it must not become a silent edit or
        # publish bypass around the explicit reasoned recovery action.
        legacy_record = await session.get(Order, UUID(legacy_order_id))
        assert legacy_record is not None
        legacy_record.opened_by = abandoned_opener.id
        await session.commit()
        await session.refresh(legacy_record)
        legacy_version = int(legacy_record.checkout_version)

        def assert_private_draft_denied(result) -> None:
            assert result.status_code == 422, result.text
            assert result.json()["error"]["code"] == "business_rule"
            assert "Recover to POS" in result.json()["error"]["message"]

        guarded_attempts = [
            await client.post(
                f"/api/v1/pos/orders/{legacy_order_id}/lines",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["append"],
                },
                json={
                    "expected_checkout_version": legacy_version,
                    "lines": [{"menu_item_id": str(item.id), "qty": 1}],
                },
            ),
            await client.patch(
                f"/api/v1/pos/orders/{legacy_order_id}/customer",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["customer"],
                },
                json={
                    "customer_name": "Must not be attached",
                    "expected_checkout_version": legacy_version,
                },
            ),
            await client.patch(
                f"/api/v1/pos/orders/{legacy_order_id}/discount",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["discount"],
                },
                json={
                    "manual_discount_minor": 0,
                    "expected_checkout_version": legacy_version,
                },
            ),
            await client.patch(
                f"/api/v1/pos/orders/{legacy_order_id}/points",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["points"],
                },
                json={
                    "points": 0,
                    "expected_checkout_version": legacy_version,
                },
            ),
            await client.patch(
                f"/api/v1/pos/orders/{legacy_order_id}/reward",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["reward"],
                },
                json={
                    "reward_key": "snack",
                    "expected_checkout_version": legacy_version,
                },
            ),
            await client.post(
                f"/api/v1/pos/orders/{legacy_order_id}/publish-checkout-claim",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["publish"],
                    "X-Checkout-Client-Instance": str(uuid4()),
                },
                json={"expected_checkout_version": legacy_version},
            ),
            await client.post(
                f"/api/v1/pos/orders/{legacy_order_id}/finalize-zero",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["finalize-zero"],
                },
            ),
            await client.post(
                f"/api/v1/pos/orders/{legacy_order_id}/payments",
                headers={
                    **headers,
                    "Idempotency-Key": foreign_guard_keys["payment"],
                },
                json={
                    "method": "upi",
                    "amount_minor": 2_500,
                    "expected_order_total_minor": 2_500,
                    "expected_due_minor": 2_500,
                },
            ),
        ]
        for attempt in guarded_attempts:
            assert_private_draft_denied(attempt)

        recovery_body = {
            "expected_checkout_version": legacy_version,
            "reason": "Recovered after the legacy POS client closed",
        }
        recovered = await client.patch(
            f"/api/v1/pos/orders/{legacy_order_id}/hold-for-checkout",
            headers={**headers, "Idempotency-Key": recovery_key},
            json=recovery_body,
        )
        assert recovered.status_code == 200, recovered.text
        assert recovered.json()["status"] == "held"
        assert recovered.json()["held_at"] is not None

        replay = await client.patch(
            f"/api/v1/pos/orders/{legacy_order_id}/hold-for-checkout",
            headers={**headers, "Idempotency-Key": recovery_key},
            json=recovery_body,
        )
        assert replay.status_code == 200, replay.text
        assert replay.json() == recovered.json()

        async with AsyncSessionLocal() as verify:
            audits = (
                await verify.execute(
                    select(AuditLog).where(
                        AuditLog.action == "pos_direct_order_hold_for_checkout",
                        AuditLog.entity_id == legacy_order_id,
                    )
                )
            ).scalars().all()
            assert len(audits) == 1
            assert audits[0].actor_user_id == owner.id
            assert audits[0].reason == recovery_body["reason"]
            assert await verify.get(IdempotencyKey, publish_key) is None
            recovered_record = await verify.get(Order, UUID(legacy_order_id))
            assert recovered_record is not None
            assert recovered_record.opened_by == abandoned_opener.id
            assert recovered_record.customer_name is None
    finally:
        async with AsyncSessionLocal() as cleanup:
            order_ids = [
                UUID(order_id)
                for order_id in (published_order_id, legacy_order_id)
                if order_id is not None
            ]
            if order_ids:
                await cleanup.execute(
                    delete(AuditLog).where(
                        AuditLog.entity_type == "Order",
                        AuditLog.entity_id.in_([str(order_id) for order_id in order_ids]),
                    )
                )
                await cleanup.execute(
                    delete(OrderCheckoutClaim).where(
                        OrderCheckoutClaim.order_id.in_(order_ids)
                    )
                )
                await cleanup.execute(
                    delete(OrderLine).where(OrderLine.order_id.in_(order_ids))
                )
                await cleanup.execute(delete(Order).where(Order.id.in_(order_ids)))
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(delete(MenuCategory).where(MenuCategory.id == category.id))
            await cleanup.execute(delete(User).where(User.id == abandoned_opener.id))
            await cleanup.execute(
                delete(IdempotencyKey).where(
                    IdempotencyKey.key.in_(
                        [
                            publish_create_key,
                            legacy_create_key,
                            *foreign_guard_keys.values(),
                            recovery_key,
                        ]
                    )
                )
            )
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_two_database_sessions_cannot_claim_the_same_held_bill(
    session,
    seed_owner,
) -> None:
    """B blocks on A's row lock, then sees A's committed lease and loses."""
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    cashier_b = User(
        id=uuid4(),
        company_id=company.id,
        email=f"checkout-b-{uuid4().hex[:10]}@test.local",
        name="Checkout B",
        password_hash=hash_password("not-used-password"),
        status="active",
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
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="dine_in",
        status="held",
        subtotal_minor=10_000,
        tax_minor=0,
        total_minor=10_000,
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
    )
    session.add_all([cashier_b, shift])
    await session.flush()
    session.add(order)
    await session.commit()

    tenant_a = TenantContext(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        roles=("owner",),
        protected_access=True,
    )
    tenant_b = TenantContext(
        user_id=cashier_b.id,
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        roles=("cashier",),
        protected_access=True,
    )
    client_instance_a = uuid4()
    client_instance_b = uuid4()

    try:
        # The version is enforced by PostgreSQL, not by remembering to bump it
        # in each endpoint.  Prove the trigger and ORM refresh contract before
        # taking the lease used by the contention test.
        async with AsyncSessionLocal() as version_session:
            versioned_order = await version_session.get(Order, order.id)
            assert versioned_order is not None
            version_before = versioned_order.checkout_version
            versioned_order.customer_name = "Versioned guest"
            await version_session.flush()
            await version_session.refresh(versioned_order)
            assert versioned_order.checkout_version == version_before + 1
            await version_session.commit()

        async with AsyncSessionLocal() as session_a, AsyncSessionLocal() as session_b:
            grant_a = await claim_order_for_checkout(
                order.id,
                session_a,
                Response(),
                tenant_a,
                client_instance_a,
            )
            claim_b_task = asyncio.create_task(
                claim_order_for_checkout(
                    order.id,
                    session_b,
                    Response(),
                    tenant_b,
                    client_instance_b,
                )
            )
            await asyncio.sleep(0.05)
            assert not claim_b_task.done(), "B must wait for A's order-row transaction"

            await session_a.commit()
            with pytest.raises(CheckoutClaimConflictError):
                await asyncio.wait_for(claim_b_task, timeout=2)
            await session_b.rollback()

            # Sharing the cashier account and terminal no longer makes a
            # second physical client the same lease holder. It cannot rotate
            # A's bearer token while A's installation-bound lease is active.
            async with AsyncSessionLocal() as same_cashier_other_client:
                with pytest.raises(CheckoutClaimConflictError):
                    await claim_order_for_checkout(
                        order.id,
                        same_cashier_other_client,
                        Response(),
                        tenant_a,
                        client_instance_b,
                    )
                await same_cashier_other_client.rollback()

            persisted = (
                await session_a.execute(
                    select(OrderCheckoutClaim).where(
                        OrderCheckoutClaim.order_id == order.id
                    )
                )
            ).scalar_one()
            assert persisted.claimed_by_user_id == owner.id
            assert persisted.token_hash != grant_a.claim_token
            assert persisted.client_instance_hash != str(client_instance_a)
            assert len(persisted.client_instance_hash) == 64
            assert persisted.order_total_minor == 10_000
            assert persisted.due_minor == 10_000
            assert persisted.order_version >= 1
    finally:
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(OrderCheckoutClaim).where(OrderCheckoutClaim.order_id == order.id)
            )
            await cleanup.execute(delete(Order).where(Order.id == order.id))
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(User).where(User.id == cashier_b.id))
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_http_claim_is_required_and_consumed_by_held_order_payment(
    client,
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
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Claim payment {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"CLAIM-PAY-{uuid4().hex[:8]}",
        name="Claim payment item",
        type="food",
        base_price_minor=2_500,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    session.add_all([shift, category])
    await session.flush()
    session.add(item)
    await session.flush()
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="dine_in",
        status="held",
        subtotal_minor=2_500,
        tax_minor=0,
        total_minor=2_500,
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
    )
    session.add(order)
    await session.flush()
    session.add(
        OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=item.id,
            qty=1,
            unit_price_minor=2_500,
            line_total_minor=2_500,
            discount_minor=0,
            tax_rate=0,
            taxable_value_minor=2_500,
            cgst_minor=0,
            sgst_minor=0,
            igst_minor=0,
            cess_minor=0,
        )
    )
    await session.commit()

    access_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    base_headers = {
        "Authorization": f"Bearer {access_token}",
        "X-Terminal-Id": str(terminal.id),
    }
    payment_body = {
        "method": "upi",
        "amount_minor": 2_500,
        "expected_order_total_minor": 2_500,
        "expected_due_minor": 2_500,
    }
    missing_cash_tender = await client.post(
        f"/api/v1/pos/orders/{order.id}/payments",
        headers={**base_headers, "Idempotency-Key": f"missing-cash-tender-{uuid4()}"},
        json={**payment_body, "method": "cash"},
    )
    assert missing_cash_tender.status_code == 422
    assert missing_cash_tender.json()["error"]["code"] == "validation_error"
    assert "cash tendered amount is required" in missing_cash_tender.json()["error"]["message"]

    non_cash_tender = await client.post(
        f"/api/v1/pos/orders/{order.id}/payments",
        headers={**base_headers, "Idempotency-Key": f"non-cash-tender-{uuid4()}"},
        json={**payment_body, "tendered_minor": 2_500},
    )
    assert non_cash_tender.status_code == 422
    assert non_cash_tender.json()["error"]["code"] == "validation_error"
    assert "only valid for cash payments" in non_cash_tender.json()["error"]["message"]

    missing_claim = await client.post(
        f"/api/v1/pos/orders/{order.id}/payments",
        headers={**base_headers, "Idempotency-Key": f"missing-{uuid4()}"},
        json=payment_body,
    )
    assert missing_claim.status_code == 409
    assert missing_claim.json()["error"]["code"] == "checkout_claim_required"

    claimed = await client.post(
        f"/api/v1/pos/orders/{order.id}/checkout-claim",
        headers=base_headers,
    )
    assert claimed.status_code == 201
    assert claimed.headers["cache-control"] == "no-store"
    claim_body = claimed.json()
    assert claim_body["order_total_minor"] == 2_500
    assert claim_body["due_minor"] == 2_500

    payment_key = f"claimed-payment-{uuid4()}"
    paid = await client.post(
        f"/api/v1/pos/orders/{order.id}/payments",
        headers={
            **base_headers,
            "Idempotency-Key": payment_key,
            "X-Checkout-Claim": claim_body["claim_token"],
        },
        json=payment_body,
    )
    assert paid.status_code == 201, paid.text
    receipt = paid.json()
    assert receipt["order_id"] == str(order.id)
    assert receipt["shift_id"] == str(shift.id)
    assert receipt["method"] == "upi"
    assert receipt["amount_minor"] == 2_500
    assert receipt["bill_amount_minor"] == 2_500
    assert receipt["tip_minor"] == 0
    assert receipt["tendered_minor"] is None
    assert receipt["change_minor"] is None
    assert receipt["ref_external"] is None
    assert receipt["paid_at"]
    assert receipt["order_status"] == "paid"
    assert receipt["invoice_no"]
    assert receipt["invoice_issued_at"]

    # A dropped 201 response is recovered by replaying the exact payment key.
    # The checkout claim has already been consumed, so only the stored receipt
    # can make this safe; a second Payment row must never be attempted.
    replayed = await client.post(
        f"/api/v1/pos/orders/{order.id}/payments",
        headers={
            **base_headers,
            "Idempotency-Key": payment_key,
            "X-Checkout-Claim": claim_body["claim_token"],
        },
        json=payment_body,
    )
    assert replayed.status_code == 201, replayed.text
    assert replayed.json() == receipt

    # Upgrade compatibility: releases before the typed receipt contract kept
    # a shorter idempotency response. The exact same retry must reconstruct
    # the receipt from the immutable Payment row and still avoid a new write.
    async with AsyncSessionLocal() as downgrade_response:
        stored = await downgrade_response.get(IdempotencyKey, payment_key)
        assert stored is not None
        stored.response_body = {
            field: receipt[field]
            for field in (
                "id",
                "amount_minor",
                "tip_minor",
                "order_status",
                "invoice_no",
                "fiscal_year",
                "invoice_issued_at",
            )
        }
        await downgrade_response.commit()

    legacy_replayed = await client.post(
        f"/api/v1/pos/orders/{order.id}/payments",
        headers={
            **base_headers,
            "Idempotency-Key": payment_key,
            "X-Checkout-Claim": claim_body["claim_token"],
        },
        json=payment_body,
    )
    assert legacy_replayed.status_code == 201, legacy_replayed.text
    assert legacy_replayed.json() == receipt

    try:
        async with AsyncSessionLocal() as verify:
            assert await verify.get(OrderCheckoutClaim, claim_body["claim_id"]) is None
            paid_order = await verify.get(Order, order.id)
            assert paid_order is not None
            assert paid_order.status == "paid"
            payments = (
                await verify.execute(select(Payment).where(Payment.order_id == order.id))
            ).scalars().all()
            assert len(payments) == 1
    finally:
        async with AsyncSessionLocal() as cleanup:
            await _set_paid_source_cleanup_triggers(cleanup, enabled=False)
            await cleanup.execute(delete(Payment).where(Payment.order_id == order.id))
            await cleanup.execute(
                delete(OrderCheckoutClaim).where(OrderCheckoutClaim.order_id == order.id)
            )
            await cleanup.execute(delete(OrderLine).where(OrderLine.order_id == order.id))
            await cleanup.execute(delete(Order).where(Order.id == order.id))
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(delete(MenuCategory).where(MenuCategory.id == category.id))
            await cleanup.execute(
                delete(InvoiceCounter).where(InvoiceCounter.branch_id == branch.id)
            )
            await cleanup.execute(
                delete(IdempotencyKey).where(IdempotencyKey.key == payment_key)
            )
            await _set_paid_source_cleanup_triggers(cleanup, enabled=True)
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_http_zero_total_finalization_requires_and_consumes_claim(
    client,
    session,
    seed_owner,
) -> None:
    """Membership-covered shared bills retain the two-device exclusion."""
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
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Claim zero {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"CLAIM-ZERO-{uuid4().hex[:8]}",
        name="Membership-covered item",
        type="gaming",
        base_price_minor=0,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    session.add_all([shift, category])
    await session.flush()
    session.add(item)
    await session.flush()
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="session",
        status="held",
        subtotal_minor=0,
        tax_minor=0,
        total_minor=0,
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
    )
    session.add(order)
    await session.flush()
    session.add(
        OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=item.id,
            qty=1,
            unit_price_minor=0,
            line_total_minor=0,
            discount_minor=0,
            tax_rate=0,
            taxable_value_minor=0,
            cgst_minor=0,
            sgst_minor=0,
            igst_minor=0,
            cess_minor=0,
        )
    )
    await session.commit()

    access_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    base_headers = {
        "Authorization": f"Bearer {access_token}",
        "X-Terminal-Id": str(terminal.id),
    }
    missing_key = f"missing-zero-{uuid4()}"
    missing_claim = await client.post(
        f"/api/v1/pos/orders/{order.id}/finalize-zero",
        headers={**base_headers, "Idempotency-Key": missing_key},
    )
    assert missing_claim.status_code == 409
    assert missing_claim.json()["error"]["code"] == "checkout_claim_required"

    claimed = await client.post(
        f"/api/v1/pos/orders/{order.id}/checkout-claim",
        headers=base_headers,
    )
    assert claimed.status_code == 201, claimed.text
    claim_body = claimed.json()
    assert claim_body["order_total_minor"] == 0
    assert claim_body["paid_minor"] == 0
    assert claim_body["due_minor"] == 0

    finalize_key = f"claimed-zero-{uuid4()}"
    finalized = await client.post(
        f"/api/v1/pos/orders/{order.id}/finalize-zero",
        headers={
            **base_headers,
            "Idempotency-Key": finalize_key,
            "X-Checkout-Claim": claim_body["claim_token"],
        },
    )
    assert finalized.status_code == 200, finalized.text
    assert finalized.json()["order_status"] == "paid"
    assert finalized.json()["amount_minor"] == 0

    try:
        async with AsyncSessionLocal() as verify:
            assert await verify.get(OrderCheckoutClaim, claim_body["claim_id"]) is None
            paid_order = await verify.get(Order, order.id)
            assert paid_order is not None
            assert paid_order.status == "paid"
            assert paid_order.invoice_no
    finally:
        async with AsyncSessionLocal() as cleanup:
            await _set_paid_source_cleanup_triggers(cleanup, enabled=False)
            await cleanup.execute(
                delete(OrderCheckoutClaim).where(OrderCheckoutClaim.order_id == order.id)
            )
            await cleanup.execute(delete(OrderLine).where(OrderLine.order_id == order.id))
            await cleanup.execute(delete(Order).where(Order.id == order.id))
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(delete(MenuCategory).where(MenuCategory.id == category.id))
            await cleanup.execute(
                delete(InvoiceCounter).where(InvoiceCounter.branch_id == branch.id)
            )
            await cleanup.execute(
                delete(IdempotencyKey).where(
                    IdempotencyKey.key.in_([missing_key, finalize_key])
                )
            )
            await _set_paid_source_cleanup_triggers(cleanup, enabled=True)
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_http_void_requires_live_claim_bearer_and_serializes_with_payment(
    client,
    session,
    seed_owner,
) -> None:
    """Reasoned void shares checkout ownership and the order-row mutex."""
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company.gst_registration_type = "unregistered"
    company.is_composition = False
    branch.state_code = "32"
    foreign_owner = User(
        id=uuid4(),
        company_id=company.id,
        email=f"void-foreign-{uuid4().hex[:8]}@test.local",
        name="Other checkout owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Claim void {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"CLAIM-VOID-{uuid4().hex[:8]}",
        name="Claim void item",
        type="food",
        base_price_minor=2_500,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
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
    void_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="held",
        subtotal_minor=2_500,
        tax_minor=0,
        total_minor=2_500,
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
    )
    race_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="held",
        subtotal_minor=2_500,
        tax_minor=0,
        total_minor=2_500,
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
    )
    session.add_all([foreign_owner, category, shift])
    await session.flush()
    session.add(item)
    await session.flush()
    session.add_all([void_order, race_order])
    await session.flush()
    session.add_all(
        [
            OrderLine(
                id=uuid4(),
                order_id=order.id,
                menu_item_id=item.id,
                qty=1,
                unit_price_minor=2_500,
                line_total_minor=2_500,
                discount_minor=0,
                tax_rate=0,
                taxable_value_minor=2_500,
                cgst_minor=0,
                sgst_minor=0,
                igst_minor=0,
                cess_minor=0,
            )
            for order in (void_order, race_order)
        ]
    )
    await session.commit()
    await session.refresh(foreign_owner)

    owner_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True},
    )
    foreign_token = issue_access_token(
        user_id=foreign_owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=foreign_owner.auth_version,
        extra={"protected_access": True},
    )
    owner_headers = {
        "Authorization": f"Bearer {owner_token}",
        "X-Terminal-Id": str(terminal.id),
    }
    foreign_headers = {
        "Authorization": f"Bearer {foreign_token}",
        "X-Terminal-Id": str(terminal.id),
    }
    void_reason = "Duplicate bill confirmed by the cashier"
    race_reason = "Customer cancelled before collection"
    race_payment_key = f"claim-void-race-{uuid4()}"

    try:
        claimed = await client.post(
            f"/api/v1/pos/orders/{void_order.id}/checkout-claim",
            headers=owner_headers,
        )
        assert claimed.status_code == 201, claimed.text
        claim = claimed.json()

        missing = await client.request(
            "DELETE",
            f"/api/v1/pos/orders/{void_order.id}",
            headers=owner_headers,
            json={"reason": void_reason},
        )
        assert missing.status_code == 409, missing.text
        assert missing.json()["error"]["code"] == "checkout_claim_required"

        foreign = await client.request(
            "DELETE",
            f"/api/v1/pos/orders/{void_order.id}",
            headers={**foreign_headers, "X-Checkout-Claim": claim["claim_token"]},
            json={"reason": void_reason},
        )
        assert foreign.status_code == 409, foreign.text
        assert foreign.json()["error"]["code"] == "checkout_claim_invalid"

        # Simulate a checkout-relevant canonical change after claim issuance.
        async with AsyncSessionLocal() as mutate:
            changed = await mutate.get(Order, void_order.id)
            assert changed is not None
            changed.customer_name = "Updated after initial claim"
            await mutate.commit()

        stale = await client.request(
            "DELETE",
            f"/api/v1/pos/orders/{void_order.id}",
            headers={**owner_headers, "X-Checkout-Claim": claim["claim_token"]},
            json={"reason": void_reason},
        )
        assert stale.status_code == 409, stale.text
        assert stale.json()["error"]["code"] == "checkout_claim_stale"

        refreshed_claim = await client.post(
            f"/api/v1/pos/orders/{void_order.id}/checkout-claim",
            headers=owner_headers,
        )
        assert refreshed_claim.status_code == 201, refreshed_claim.text
        fresh = refreshed_claim.json()
        assert fresh["claim_token"] != claim["claim_token"]

        voided = await client.request(
            "DELETE",
            f"/api/v1/pos/orders/{void_order.id}",
            headers={**owner_headers, "X-Checkout-Claim": fresh["claim_token"]},
            json={"reason": void_reason},
        )
        assert voided.status_code == 204, voided.text

        # Response-loss retry is keyed by immutable business outcome, not by
        # storing the bearer: same order + same reason is an idempotent 204.
        replay = await client.request(
            "DELETE",
            f"/api/v1/pos/orders/{void_order.id}",
            headers={**owner_headers, "X-Checkout-Claim": fresh["claim_token"]},
            json={"reason": void_reason},
        )
        assert replay.status_code == 204, replay.text

        race_claimed = await client.post(
            f"/api/v1/pos/orders/{race_order.id}/checkout-claim",
            headers=owner_headers,
        )
        assert race_claimed.status_code == 201, race_claimed.text
        race_claim = race_claimed.json()["claim_token"]
        payment_request = client.post(
            f"/api/v1/pos/orders/{race_order.id}/payments",
            headers={
                **owner_headers,
                "Idempotency-Key": race_payment_key,
                "X-Checkout-Claim": race_claim,
            },
            json={
                "method": "upi",
                "amount_minor": 2_500,
                "expected_order_total_minor": 2_500,
                "expected_due_minor": 2_500,
            },
        )
        void_request = client.request(
            "DELETE",
            f"/api/v1/pos/orders/{race_order.id}",
            headers={**owner_headers, "X-Checkout-Claim": race_claim},
            json={"reason": race_reason},
        )
        paid_result, void_result = await asyncio.gather(payment_request, void_request)
        assert sum(
            result.status_code in {201, 204}
            for result in (paid_result, void_result)
        ) == 1, (paid_result.text, void_result.text)
        assert sum(
            result.status_code == 422
            for result in (paid_result, void_result)
        ) == 1, (paid_result.text, void_result.text)

        async with AsyncSessionLocal() as verify:
            assert (
                await verify.execute(
                    select(OrderCheckoutClaim).where(
                        OrderCheckoutClaim.order_id.in_([void_order.id, race_order.id])
                    )
                )
            ).scalars().all() == []
            stored_void = await verify.get(Order, void_order.id)
            stored_race = await verify.get(Order, race_order.id)
            assert stored_void is not None and stored_void.status == "void"
            assert stored_race is not None and stored_race.status in {"paid", "void"}
            race_payments = (
                await verify.execute(
                    select(Payment).where(Payment.order_id == race_order.id)
                )
            ).scalars().all()
            assert len(race_payments) == (1 if stored_race.status == "paid" else 0)
    finally:
        async with AsyncSessionLocal() as cleanup:
            await _set_paid_source_cleanup_triggers(cleanup, enabled=False)
            order_ids = [void_order.id, race_order.id]
            await cleanup.execute(
                delete(Payment).where(Payment.order_id.in_(order_ids))
            )
            await cleanup.execute(
                delete(OrderCheckoutClaim).where(
                    OrderCheckoutClaim.order_id.in_(order_ids)
                )
            )
            await cleanup.execute(delete(OrderLine).where(OrderLine.order_id.in_(order_ids)))
            await cleanup.execute(delete(Order).where(Order.id.in_(order_ids)))
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(delete(MenuCategory).where(MenuCategory.id == category.id))
            await cleanup.execute(delete(User).where(User.id == foreign_owner.id))
            await cleanup.execute(
                delete(InvoiceCounter).where(InvoiceCounter.branch_id == branch.id)
            )
            await cleanup.execute(
                delete(IdempotencyKey).where(IdempotencyKey.key == race_payment_key)
            )
            await _set_paid_source_cleanup_triggers(cleanup, enabled=True)
            await cleanup.commit()

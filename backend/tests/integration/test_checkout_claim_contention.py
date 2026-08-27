"""Real PostgreSQL proof that two checkout requests serialize by order row."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import pytest
from fastapi import Response
from sqlalchemy import delete, select

from app.api.v1.pos.router import claim_order_for_checkout
from app.core.db import AsyncSessionLocal
from app.core.errors import CheckoutClaimConflictError
from app.core.security import hash_password, issue_access_token
from app.core.tenant import TenantContext
from app.models import (
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
            )
            claim_b_task = asyncio.create_task(
                claim_order_for_checkout(
                    order.id,
                    session_b,
                    Response(),
                    tenant_b,
                )
            )
            await asyncio.sleep(0.05)
            assert not claim_b_task.done(), "B must wait for A's order-row transaction"

            await session_a.commit()
            with pytest.raises(CheckoutClaimConflictError):
                await asyncio.wait_for(claim_b_task, timeout=2)
            await session_b.rollback()

            persisted = (
                await session_a.execute(
                    select(OrderCheckoutClaim).where(
                        OrderCheckoutClaim.order_id == order.id
                    )
                )
            ).scalar_one()
            assert persisted.claimed_by_user_id == owner.id
            assert persisted.token_hash != grant_a.claim_token
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
            await cleanup.commit()

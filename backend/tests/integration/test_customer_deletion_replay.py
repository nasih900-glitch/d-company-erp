"""Regression coverage for customer deletion versus delayed identity writes.

These tests deliberately use PostgreSQL transaction locks.  A fake session
cannot prove that a request which read a live customer before DELETE will
re-check the tombstone after the deleting transaction commits.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.db import AsyncSessionLocal
from app.models import (
    Customer,
    CustomerDirectoryState,
    GamingSession,
    MenuCategory,
    MenuItem,
    Order,
    Shift,
    Station,
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
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _headers(
    token: str,
    *,
    action_id: str | None = None,
) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {token}"}
    if action_id is not None:
        headers.update(
            {
                "X-Client-Platform": "android",
                "X-Client-Version-Code": "8",
                "X-Client-Action-Id": action_id,
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": "2026-09-14T09:00:00Z",
            }
        )
    return headers


@pytest.mark.asyncio
async def test_lost_create_ack_replay_after_delete_cannot_restore_customer_pii(
    client,
    session,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-6{uuid4().int % 10**9:09d}"
    action_id = f"customer-upsert:{uuid4()}"
    original_headers = _headers(token, action_id=action_id)
    original_body = {
        "phone": phone,
        "name": "Must Stay Erased",
        "customer_directory_revision": 0,
        "customer_directory_company_id": str(seed_owner["company"].id),
    }

    # Treat the successful response as lost: the tablet retains the exact
    # original outbox row and therefore the exact original action/revision.
    first = await client.post(
        "/api/v1/customers",
        json=original_body,
        headers=original_headers,
    )
    assert first.status_code == 201, first.text
    customer_id = first.json()["id"]

    deleted = await client.delete(
        f"/api/v1/customers/{customer_id}", headers=_headers(token)
    )
    assert deleted.status_code == 204, deleted.text

    replay = await client.post(
        "/api/v1/customers",
        json=original_body,
        headers=original_headers,
    )
    assert replay.status_code == 409, replay.text

    rows = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == seed_owner["company"].id,
                Customer.phone == phone,
            )
        )
    ).scalars().all()
    assert rows == []

    # A new operator intent is valid once it has observed the server-issued
    # post-delete revision.  It may deliberately reuse the freed phone.
    listing = await client.get("/api/v1/customers", headers=_headers(token))
    assert listing.status_code == 200, listing.text
    current_revision = int(listing.headers["X-Customer-Directory-Revision"])
    assert current_revision == 1
    recreated = await client.post(
        "/api/v1/customers",
        json={
            "phone": phone,
            "name": "Fresh Intent",
            "customer_directory_revision": current_revision,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers=_headers(token, action_id=f"customer-upsert:{uuid4()}"),
    )
    assert recreated.status_code == 201, recreated.text
    assert recreated.json()["id"] != customer_id
    assert recreated.json()["name"] == "Fresh Intent"


@pytest.mark.asyncio
async def test_patch_waiting_behind_delete_rechecks_tombstone_before_writing_pii(
    client,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-5{uuid4().int % 10**9:09d}"
    created = await client.post(
        "/api/v1/customers",
        json={"phone": phone, "name": "Erase Me"},
        headers=_headers(token),
    )
    assert created.status_code == 201, created.text
    customer_id = created.json()["id"]

    # Hold the customer row exactly as DELETE will.  PostgreSQL tells us when
    # the PATCH transaction is waiting on this backend pid, so the interleave
    # is explicit rather than inferred from a sleep.
    async with AsyncSessionLocal() as deleting:
        deleting_pid = int(
            (await deleting.execute(text("select pg_backend_pid()"))).scalar_one()
        )
        customer = (
            await deleting.execute(
                select(Customer)
                .where(Customer.id == customer_id)
                .with_for_update()
            )
        ).scalar_one()

        patch_task = asyncio.create_task(
            client.patch(
                f"/api/v1/customers/{customer_id}",
                json={"name": "Restored By Stale Patch"},
                headers=_headers(token),
            )
        )

        async def patch_is_waiting_on_delete() -> bool:
            async with AsyncSessionLocal() as observer:
                return bool(
                    (
                        await observer.execute(
                            text(
                                "SELECT EXISTS ("
                                "SELECT 1 FROM pg_stat_activity "
                                "WHERE :blocker = ANY(pg_blocking_pids(pid))"
                                ")"
                            ),
                            {"blocker": deleting_pid},
                        )
                    ).scalar_one()
                )

        deadline = asyncio.get_running_loop().time() + 3
        while not await patch_is_waiting_on_delete():
            if patch_task.done():
                raise AssertionError(
                    "PATCH completed without waiting for the deleting row lock"
                )
            if asyncio.get_running_loop().time() >= deadline:
                patch_task.cancel()
                raise AssertionError("PATCH did not reach the deleting row lock")
            await asyncio.sleep(0.01)

        customer.deleted_at = datetime.now(UTC)
        customer.name = None
        customer.email = None
        customer.birthday = None
        customer.notes = None
        customer.phone = f"deleted-{uuid4().hex[:12]}"
        await deleting.commit()

    patched = await asyncio.wait_for(patch_task, timeout=3)
    assert patched.status_code == 404, patched.text

    async with AsyncSessionLocal() as verification:
        tombstone = await verification.get(Customer, customer_id)
        assert tombstone is not None
        assert tombstone.deleted_at is not None
        assert tombstone.name is None
        assert tombstone.email is None
        assert tombstone.birthday is None
        assert tombstone.notes is None
        assert tombstone.phone != phone


async def _open_shift_and_item(session, seed_owner):
    seed_owner["branch"].state_code = "32"
    seed_owner["company"].gst_registration_type = "unregistered"
    seed_owner["company"].is_composition = False
    shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id,
        opened_at=datetime.now(UTC) - timedelta(minutes=5),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Deletion fence {uuid4().hex[:6]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"FENCE-{uuid4().hex[:8]}",
        name="Fence test item",
        type="drink",
        base_price_minor=1_000,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    session.add_all([shift, category])
    await session.flush()
    session.add(item)
    await session.commit()
    return shift, item


@pytest.mark.asyncio
async def test_stale_phone_only_gaming_start_keeps_play_without_recreating_customer(
    client,
    session,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-4{uuid4().int % 10**9:09d}"
    created = await client.post(
        "/api/v1/customers",
        json={
            "phone": phone,
            "name": "Deleted Gamer",
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers=_headers(token),
    )
    assert created.status_code == 201, created.text
    assert (
        await client.delete(
            f"/api/v1/customers/{created.json()['id']}", headers=_headers(token)
        )
    ).status_code == 204

    shift, _ = await _open_shift_and_item(session, seed_owner)
    station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        code=f"FENCE-{uuid4().hex[:6]}",
        name="Deletion fence station",
        type="projector",
        rate_per_hour_minor=6_000,
        is_active=True,
    )
    session.add(station)
    await session.commit()
    for invalid_evidence in (
        {
            "customer_directory_revision": 2,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        {
            "customer_directory_revision": 1,
            "customer_directory_company_id": str(uuid4()),
        },
    ):
        invalid_selected = await client.post(
            "/api/v1/gaming/sessions/start",
            json={
                "station_id": str(station.id),
                "shift_id": str(shift.id),
                "customer_id": created.json()["id"],
                "expected_rate_per_hour_minor": 6_000,
                **invalid_evidence,
            },
            headers={
                **_headers(token),
                "X-Terminal-Id": str(seed_owner["terminal"].id),
                "Idempotency-Key": f"gaming-invalid-fence:{uuid4()}",
            },
        )
        assert invalid_selected.status_code == 409, invalid_selected.text
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "customer_name": "Deleted Gamer",
            "customer_phone": phone,
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
            "expected_rate_per_hour_minor": 6_000,
        },
        headers={
            **_headers(token),
            "X-Terminal-Id": str(seed_owner["terminal"].id),
            "Idempotency-Key": f"gaming-fence:{uuid4()}",
        },
    )
    assert started.status_code == 201, started.text
    assert started.json()["customer_id"] is None
    assert started.json()["customer_phone"] == phone
    stored = await session.get(GamingSession, started.json()["id"])
    await session.refresh(stored)
    assert stored.customer_directory_revision == 0
    live = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == seed_owner["company"].id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    assert live is None


@pytest.mark.asyncio
async def test_stale_phone_only_checkout_settles_without_recreating_customer(
    client,
    session,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-3{uuid4().int % 10**9:09d}"
    created = await client.post(
        "/api/v1/customers",
        json={
            "phone": phone,
            "name": "Deleted Buyer",
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers=_headers(token),
    )
    assert created.status_code == 201, created.text
    assert (
        await client.delete(
            f"/api/v1/customers/{created.json()['id']}", headers=_headers(token)
        )
    ).status_code == 204
    shift, item = await _open_shift_and_item(session, seed_owner)
    operational_headers = {
        **_headers(token),
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    order_response = await client.post(
        "/api/v1/pos/orders",
        json={
            "type": "takeaway",
            "shift_id": str(shift.id),
            "lines": [
                {"client_line_id": str(uuid4()), "menu_item_id": str(item.id), "qty": 1}
            ],
            "customer_name": "Deleted Buyer",
            "customer_phone": phone,
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers={**operational_headers, "Idempotency-Key": f"order-fence:{uuid4()}"},
    )
    assert order_response.status_code == 201, order_response.text
    order_body = order_response.json()
    payment = await client.post(
        f"/api/v1/pos/orders/{order_body['id']}/payments",
        json={
            "method": "cash",
            "amount_minor": order_body["total_minor"],
            "tendered_minor": order_body["total_minor"],
            "expected_order_total_minor": order_body["total_minor"],
            "expected_due_minor": order_body["total_minor"],
        },
        headers={**operational_headers, "Idempotency-Key": f"payment-fence:{uuid4()}"},
    )
    assert payment.status_code == 201, payment.text
    stored_order = await session.get(Order, order_body["id"])
    await session.refresh(stored_order)
    assert stored_order.status == "paid"
    assert stored_order.customer_id is None
    assert stored_order.customer_directory_revision == 0
    live = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == seed_owner["company"].id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    assert live is None


@pytest.mark.asyncio
async def test_customer_directory_revision_is_strict_scoped_and_monotonic(
    client,
    session,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-2{uuid4().int % 10**9:09d}"
    created = await client.post(
        "/api/v1/customers",
        json={"phone": phone, "name": "Erase Counter"},
        headers=_headers(token),
    )
    assert created.status_code == 201, created.text
    assert (
        await client.delete(
            f"/api/v1/customers/{created.json()['id']}", headers=_headers(token)
        )
    ).status_code == 204

    by_phone = await client.get(
        f"/api/v1/customers/by-phone/{phone}", headers=_headers(token)
    )
    assert by_phone.status_code == 200, by_phone.text
    assert by_phone.json() is None
    assert by_phone.headers["X-Customer-Directory-Revision"] == "1"
    assert by_phone.headers["X-Customer-Directory-Company-Id"] == str(
        seed_owner["company"].id
    )

    invalid_bodies = [
        {"customer_directory_revision": -1},
        {"customer_directory_revision": 2**63},
        {"customer_directory_revision": "not-a-revision"},
        {"customer_directory_revision": "1"},
        {"customer_directory_revision": True},
        {"customer_directory_revision": 1.0},
    ]
    for invalid in invalid_bodies:
        response = await client.post(
            "/api/v1/customers",
            json={"phone": f"+91-1{uuid4().int % 10**9:09d}", **invalid},
            headers=_headers(token),
        )
        assert response.status_code == 422, response.text

    conflicts = [
        {},
        {
            "customer_directory_revision": 2,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        {
            "customer_directory_revision": 1,
            "customer_directory_company_id": str(uuid4()),
        },
    ]
    for conflict in conflicts:
        response = await client.post(
            "/api/v1/customers",
            json={"phone": f"+91-1{uuid4().int % 10**9:09d}", **conflict},
            headers=_headers(token),
        )
        assert response.status_code == 409, response.text

    # Removing an old tombstone during authorized cleanup must never rewind
    # the durable generation or make an old/legacy action fresh again.
    tombstone = await session.get(Customer, created.json()["id"])
    await session.delete(tombstone)
    await session.commit()
    listing = await client.get("/api/v1/customers", headers=_headers(token))
    assert listing.status_code == 200, listing.text
    assert listing.headers["X-Customer-Directory-Revision"] == "1"
    state = await session.get(CustomerDirectoryState, seed_owner["company"].id)
    assert state is not None
    assert state.deletion_revision == 1


@pytest.mark.asyncio
async def test_current_phone_only_checkout_links_customer_after_prior_delete(
    client,
    session,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    erased_phone = f"+91-1{uuid4().int % 10**9:09d}"
    erased = await client.post(
        "/api/v1/customers",
        json={"phone": erased_phone, "name": "Prior Tombstone"},
        headers=_headers(token),
    )
    assert erased.status_code == 201, erased.text
    assert (
        await client.delete(
            f"/api/v1/customers/{erased.json()['id']}", headers=_headers(token)
        )
    ).status_code == 204
    listing = await client.get("/api/v1/customers", headers=_headers(token))
    revision = int(listing.headers["X-Customer-Directory-Revision"])

    shift, item = await _open_shift_and_item(session, seed_owner)
    phone = f"+91-1{uuid4().int % 10**9:09d}"
    headers = {
        **_headers(token),
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    order_response = await client.post(
        "/api/v1/pos/orders",
        json={
            "type": "takeaway",
            "shift_id": str(shift.id),
            "lines": [
                {"client_line_id": str(uuid4()), "menu_item_id": str(item.id), "qty": 1}
            ],
            "customer_name": "Current Buyer",
            "customer_phone": phone,
            "customer_directory_revision": revision,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers={**headers, "Idempotency-Key": f"current-order:{uuid4()}"},
    )
    assert order_response.status_code == 201, order_response.text
    order_body = order_response.json()
    paid = await client.post(
        f"/api/v1/pos/orders/{order_body['id']}/payments",
        json={
            "method": "cash",
            "amount_minor": order_body["total_minor"],
            "tendered_minor": order_body["total_minor"],
            "expected_order_total_minor": order_body["total_minor"],
            "expected_due_minor": order_body["total_minor"],
        },
        headers={**headers, "Idempotency-Key": f"current-payment:{uuid4()}"},
    )
    assert paid.status_code == 201, paid.text
    stored_order = await session.get(Order, order_body["id"])
    await session.refresh(stored_order)
    assert stored_order.status == "paid"
    assert stored_order.customer_id is not None
    linked = await session.get(Customer, stored_order.customer_id)
    assert linked is not None
    assert linked.phone == phone
    assert linked.name == "Current Buyer"


@pytest.mark.asyncio
async def test_redeemed_phone_only_checkout_waits_on_delete_fence_before_customer_lock(
    client,
    session,
    seed_owner,
) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-7{uuid4().int % 10**9:09d}"
    created = await client.post(
        "/api/v1/customers",
        json={"phone": phone, "name": "Concurrent Buyer"},
        headers=_headers(token),
    )
    assert created.status_code == 201, created.text
    customer_id = created.json()["id"]
    customer = await session.get(Customer, customer_id)
    customer.loyalty_points = 100
    await session.commit()

    listing = await client.get("/api/v1/customers", headers=_headers(token))
    revision = int(listing.headers["X-Customer-Directory-Revision"])
    shift, item = await _open_shift_and_item(session, seed_owner)
    operational_headers = {
        **_headers(token),
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    order_response = await client.post(
        "/api/v1/pos/orders",
        json={
            "type": "takeaway",
            "shift_id": str(shift.id),
            "lines": [
                {"client_line_id": str(uuid4()), "menu_item_id": str(item.id), "qty": 1}
            ],
            "customer_phone": phone,
            "customer_directory_revision": revision,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers={**operational_headers, "Idempotency-Key": f"lock-order:{uuid4()}"},
    )
    assert order_response.status_code == 201, order_response.text
    redeemed = await client.patch(
        f"/api/v1/pos/orders/{order_response.json()['id']}/points",
        json={
            "points": 1,
            "expected_checkout_version": order_response.json()["checkout_version"],
        },
        headers={**operational_headers, "Idempotency-Key": f"lock-points:{uuid4()}"},
    )
    assert redeemed.status_code == 200, redeemed.text
    payable = redeemed.json()

    control = AsyncSessionLocal()
    deleting_started = asyncio.Event()
    deleting_backend_pid: int | None = None
    try:
        await control.execute(
            select(Customer).where(Customer.id == customer_id).with_for_update()
        )

        async def delete_under_production_lock_order() -> int:
            nonlocal deleting_backend_pid
            async with AsyncSessionLocal() as deleting:
                deleting_pid = int(
                    (await deleting.execute(text("select pg_backend_pid()"))).scalar_one()
                )
                deleting_backend_pid = deleting_pid
                state = (
                    await deleting.execute(
                        select(CustomerDirectoryState)
                        .where(CustomerDirectoryState.company_id == seed_owner["company"].id)
                        .with_for_update()
                    )
                ).scalar_one()
                deleting_started.set()
                row = (
                    await deleting.execute(
                        select(Customer).where(Customer.id == customer_id).with_for_update()
                    )
                ).scalar_one()
                row.deleted_at = datetime.now(UTC)
                row.name = row.email = row.notes = None
                row.birthday = None
                row.phone = f"deleted-{uuid4().hex[:12]}"
                state.deletion_revision += 1
                await deleting.commit()
                return deleting_pid

        deletion_task = asyncio.create_task(delete_under_production_lock_order())
        await asyncio.wait_for(deleting_started.wait(), timeout=3)
        payment_task = asyncio.create_task(
            client.post(
                f"/api/v1/pos/orders/{payable['id']}/payments",
                json={
                    "method": "cash",
                    "amount_minor": payable["total_minor"],
                    "tendered_minor": payable["total_minor"],
                    "expected_order_total_minor": payable["total_minor"],
                    "expected_due_minor": payable["due_minor"],
                },
                headers={
                    **operational_headers,
                    "Idempotency-Key": f"lock-payment:{uuid4()}",
                },
            )
        )

        deadline = asyncio.get_running_loop().time() + 3
        while True:
            async with AsyncSessionLocal() as observer:
                waits_on_directory_owner = bool(
                    (
                        await observer.execute(
                            text(
                                "SELECT EXISTS ("
                                "SELECT 1 FROM pg_stat_activity "
                                "WHERE :blocker = ANY(pg_blocking_pids(pid))"
                                ")"
                            ),
                            {"blocker": deleting_backend_pid},
                        )
                    ).scalar_one()
                )
            if waits_on_directory_owner:
                break
            if payment_task.done():
                raise AssertionError("payment did not wait on the deletion directory lock")
            if asyncio.get_running_loop().time() >= deadline:
                raise AssertionError("payment never reached the deletion directory lock")
            await asyncio.sleep(0.01)

        await control.commit()
        await asyncio.wait_for(deletion_task, timeout=3)
        paid = await asyncio.wait_for(payment_task, timeout=3)
        assert paid.status_code == 201, paid.text
        assert paid.json()["order_status"] == "paid"
    finally:
        await control.close()

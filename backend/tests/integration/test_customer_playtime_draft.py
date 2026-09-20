"""DB/API coverage for stable gaming identity and the draft projection."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select

from app.api.v1.pos.router import _upsert_and_attach_customer
from app.core.db import AsyncSessionLocal
from app.models import Company, Customer, GamingSession, Order, Payment, Shift, Station
from app.services.customers.deletion_fence import CustomerDirectoryFence
from app.services.customers.identity import resolve_gaming_customer


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


def _headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_resolver_reuses_formatted_phone_and_never_reuses_deleted_identity(
    session, seed_owner
) -> None:
    original = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        phone="9876543210",
        name=None,
    )
    session.add(original)
    await session.flush()
    resolved = await resolve_gaming_customer(
        session,
        company_id=seed_owner["company"].id,
        phone="+91 98765-43210",
        name="Booking guest",
    )
    assert resolved is not None
    assert resolved.id == original.id
    assert resolved.name == "Booking guest"

    original.phone = "9123456780"
    await session.flush()
    assert resolved.id == original.id

    original.deleted_at = datetime.now(UTC)
    original.phone = f"deleted-{uuid4().hex[:12]}"
    await session.flush()
    replacement = await resolve_gaming_customer(
        session,
        company_id=seed_owner["company"].id,
        phone="9876543210",
        name="New owner of phone",
    )
    assert replacement is not None
    assert replacement.id != original.id
    settled = await _upsert_and_attach_customer(
        session,
        company_id=seed_owner["company"].id,
        phone="9876543210",
        name="New owner of phone",
        order=SimpleNamespace(customer_id=original.id),
        at=datetime.now(UTC),
        directory_fence=CustomerDirectoryFence(
            current_revision=0,
            captured_revision=0,
        ),
    )
    assert settled is None
    assert replacement.visit_count == 0
    await session.rollback()


@pytest.mark.asyncio
async def test_malformed_stored_phone_cannot_capture_valid_numeric_identity(
    session, seed_owner
) -> None:
    malformed = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        phone="98765ABC43210", name="Malformed legacy record",
    )
    session.add(malformed)
    await session.flush()
    resolved = await resolve_gaming_customer(
        session,
        company_id=seed_owner["company"].id,
        phone="9876543210",
        name="Valid booking",
    )
    assert resolved is not None
    assert resolved.id != malformed.id
    assert resolved.phone == "9876543210"
    await session.rollback()


@pytest.mark.asyncio
async def test_concurrent_formatted_starts_resolve_one_customer(seed_owner) -> None:
    phone = f"8{uuid4().int % 1_000_000_000:09d}"

    async def resolve(raw: str):
        async with AsyncSessionLocal() as worker, worker.begin():
            customer = await resolve_gaming_customer(
                worker,
                company_id=seed_owner["company"].id,
                phone=raw,
                name="Concurrent booking",
            )
            assert customer is not None
            return customer.id

    first_id, second_id = await asyncio.gather(
        resolve(phone),
        resolve(f"+91 {phone[:5]} {phone[5:]}"),
    )
    assert first_id == second_id


@pytest.mark.asyncio
async def test_playtime_leaderboard_is_tenant_scoped_and_conservative(
    client, session, seed_owner
) -> None:
    now = datetime.now(UTC)
    first = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        phone="9000000001", name="First", created_at=now - timedelta(days=2),
    )
    second = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        phone="9000000002", name="Second", created_at=now - timedelta(days=1),
    )
    zero = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        phone="9000000003", name="Zero",
    )
    other_company = Company(id=uuid4(), name="Other tenant")
    other_customer = Customer(
        id=uuid4(), company_id=other_company.id,
        phone="9000000001", name="Other tenant customer",
    )
    shift = Shift(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id,
        opened_at=now - timedelta(hours=12), opening_float_minor=0,
        expected_minor=0, status="open",
    )
    station = Station(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, code=f"P-{uuid4().hex[:6]}",
        name="Play Station", type="ps5", rate_per_hour_minor=10_000,
        is_active=True,
    )
    session.add(other_company)
    await session.flush()
    session.add_all([first, second, zero, other_customer, shift, station])
    await session.flush()

    def order_and_game(customer: Customer, *, minutes: int, discount: int = 0):
        order = Order(
            id=uuid4(), company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            terminal_id=seed_owner["terminal"].id, shift_id=shift.id,
            opened_by=seed_owner["owner"].id, customer_id=customer.id,
            customer_phone=customer.phone, type="session", status="paid",
            subtotal_minor=10_000, discount_minor=discount,
            manual_discount_minor=0, points_redeemed_minor=0,
            total_minor=10_000 - discount, opened_at=now - timedelta(hours=2),
            closed_at=now - timedelta(hours=1), invoice_issued_at=now - timedelta(hours=1),
            invoice_no=f"PT/{uuid4().hex[:10]}", fiscal_year="2026-27",
        )
        game = GamingSession(
            id=uuid4(), company_id=seed_owner["company"].id,
            station_id=station.id, order_id=order.id, customer_id=customer.id,
            opened_by=seed_owner["owner"].id, shift_id=shift.id,
            start_at=now - timedelta(hours=2), end_at=now - timedelta(hours=1),
            rate_per_hour_minor=10_000, billing_mode="hourly",
            billable_minutes=minutes, amount_minor=10_000, status="ended",
            customer_name=customer.name, customer_phone=customer.phone,
        )
        payment = Payment(
            id=uuid4(), order_id=order.id, shift_id=shift.id,
            method="upi", amount_minor=10_000 - discount,
            paid_at=now - timedelta(hours=1),
        )
        return order, game, payment

    seeded = [
        order_and_game(first, minutes=600),
        order_and_game(second, minutes=600),
        order_and_game(first, minutes=60, discount=100),
    ]
    negative_legacy_row = GamingSession(
        id=uuid4(), company_id=seed_owner["company"].id,
        station_id=station.id, customer_id=first.id,
        opened_by=seed_owner["owner"].id, shift_id=shift.id,
        start_at=now - timedelta(hours=3), end_at=now - timedelta(hours=2),
        rate_per_hour_minor=10_000, billing_mode="hourly",
        billable_minutes=-15, amount_minor=0, status="ended",
        customer_name=first.name, customer_phone=first.phone,
    )
    session.add_all([row[0] for row in seeded])
    await session.flush()
    session.add_all([item for row in seeded for item in row[1:]] + [negative_legacy_row])
    await session.commit()

    token = await _login(client, seed_owner)
    hidden_other_tenant = await client.get(
        f"/api/v1/customers/{other_customer.id}/playtime", headers=_headers(token)
    )
    assert hidden_other_tenant.status_code == 404
    defaults = await client.get(
        "/api/v1/customers/playtime/program-draft", headers=_headers(token)
    )
    assert defaults.status_code == 200
    assert defaults.json()["rewards_enabled"] is False
    assert defaults.json()["messaging_enabled"] is False

    forbidden = await client.put(
        "/api/v1/customers/playtime/program-draft",
        headers=_headers(token),
        json={
            "threshold_paid_minutes": 600,
            "reward_minutes": 60,
            "rewards_enabled": True,
        },
    )
    assert forbidden.status_code == 422

    board = await client.get(
        "/api/v1/customers/playtime/leaderboard?limit=2&page=1",
        headers=_headers(token),
    )
    assert board.status_code == 200, board.text
    payload = board.json()
    assert payload["total"] == 3
    assert [row["name"] for row in payload["items"]] == ["First", "Second"]
    assert payload["items"][0]["total_played_minutes"] == 660
    assert payload["items"][0]["qualifying_paid_minutes"] == 600
    assert payload["items"][0]["draft_estimated_reward_minutes"] == 60
    assert payload["items"][0]["masked_phone"].endswith("0001")

    detail = await client.get(
        f"/api/v1/customers/{first.id}/playtime", headers=_headers(token)
    )
    assert detail.status_code == 200, detail.text
    statuses = {row["qualification_status"] for row in detail.json()["history"]}
    assert statuses == {"eligible", "discounted_or_free", "unpaid"}

    second_page = await client.get(
        "/api/v1/customers/playtime/leaderboard?limit=2&page=2",
        headers=_headers(token),
    )
    assert second_page.status_code == 200
    assert [row["name"] for row in second_page.json()["items"]] == ["Zero"]


@pytest.mark.asyncio
async def test_new_rejected_start_identity_is_not_reattributed_at_payment(
    client, session, seed_owner
) -> None:
    """Legacy POS billing may attach an exact phone; playtime must not guess it."""
    now = datetime.now(UTC)
    seed_owner["branch"].state_code = "32"
    seed_owner["company"].gst_registration_type = "unregistered"
    seed_owner["company"].is_composition = False
    shift = Shift(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id, opened_at=now - timedelta(hours=1),
        opening_float_minor=0, status="open",
    )
    invalid_phone = "invalid-phone"
    ambiguous_phone = "+91 98765 43210"
    exact_ambiguous = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        phone=ambiguous_phone, name="Ambiguous exact customer",
    )
    normalized_ambiguous = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        phone="9876543210", name="Ambiguous formatted customer",
    )
    stations = [
        Station(
            id=uuid4(), company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id, code=f"IDENT-{index}-{uuid4().hex[:5]}",
            name=f"Identity station {index}", type="projector",
            rate_per_hour_minor=6_000, is_active=True,
        )
        for index in range(2)
    ]
    session.add_all([shift, exact_ambiguous, normalized_ambiguous, *stations])
    await session.commit()
    token = await _login(client, seed_owner)

    async def complete(phone: str, name: str, station: Station) -> tuple[str, str]:
        base_headers = {
            **_headers(token),
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        }
        started = await client.post(
            "/api/v1/gaming/sessions/start",
            json={
                "station_id": str(station.id),
                "shift_id": str(shift.id),
                "customer_name": name,
                "customer_phone": phone,
                "expected_rate_per_hour_minor": 6_000,
            },
            headers={**base_headers, "Idempotency-Key": f"identity-start:{uuid4()}"},
        )
        assert started.status_code == 201, started.text
        session_id = started.json()["id"]
        stopped = await client.post(
            f"/api/v1/gaming/sessions/{session_id}/stop",
            json={},
            headers={**base_headers, "Idempotency-Key": f"identity-stop:{uuid4()}"},
        )
        assert stopped.status_code == 200, stopped.text
        sent = await client.post(
            f"/api/v1/gaming/sessions/{session_id}/send-to-pos",
            headers=base_headers,
        )
        assert sent.status_code == 201, sent.text
        order_id = sent.json()["order_id"]
        claim = await client.post(
            f"/api/v1/pos/orders/{order_id}/checkout-claim",
            headers=base_headers,
        )
        assert claim.status_code == 201, claim.text
        claim_body = claim.json()
        payment_key = f"identity-payment:{uuid4()}"
        paid = await client.post(
            f"/api/v1/pos/orders/{order_id}/payments",
            json={
                "method": "upi",
                "amount_minor": claim_body["due_minor"],
                "expected_order_total_minor": claim_body["order_total_minor"],
                "expected_due_minor": claim_body["due_minor"],
                "ref_external": f"IDENT-{uuid4().hex[:8]}",
            },
            headers={
                **base_headers,
                "Idempotency-Key": payment_key,
                "X-Checkout-Claim": claim_body["claim_token"],
            },
        )
        assert paid.status_code == 201, paid.text
        return session_id, order_id

    invalid_ids = await complete(invalid_phone, "Invalid identity", stations[0])
    ambiguous_ids = await complete(ambiguous_phone, "Ambiguous identity", stations[1])

    session.expire_all()
    rows = (
        await session.execute(
            select(GamingSession, Order)
            .join(Order, Order.id == GamingSession.order_id)
            .where(
                GamingSession.id.in_(
                    [UUID(invalid_ids[0]), UUID(ambiguous_ids[0])]
                )
            )
        )
    ).all()
    assert len(rows) == 2
    assert all(game.customer_id is None for game, _order in rows)
    assert all(game.customer_identity_provenance == "start_unlinked" for game, _order in rows)
    assert all(order.customer_id is not None for _game, order in rows)

    for search_name in ("Invalid identity", "Ambiguous exact customer"):
        board = await client.get(
            "/api/v1/customers/playtime/leaderboard",
            params={"q": search_name},
            headers=_headers(token),
        )
        assert board.status_code == 200, board.text
        row = board.json()["items"][0]
        assert row["total_played_minutes"] == 0
        assert row["qualifying_paid_minutes"] == 0

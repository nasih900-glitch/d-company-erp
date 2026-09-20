"""Fresh-database proof for sanitized, idempotent finance mirror events."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import delete, select

from app.core.security import issue_access_token
from app.models import (
    Account,
    IdempotencyKey,
    Ingredient,
    Order,
    Partner,
    Payment,
    Shift,
    Supplier,
)
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.accounting.accounts import ACCOUNTS_PAYABLE, CASH, INVENTORY
from app.services.integrations.google_sheets_mirror import (
    CONNECTION_TEST_EVENT_TYPE,
    CONNECTION_TEST_SOURCE_TYPE,
    enqueue_google_sheets_event,
)


def _headers(seed, *, key: str | None = None) -> dict[str, str]:
    owner = seed["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        roles=["super_owner"],
        auth_version=owner.auth_version,
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed["terminal"].id),
    }
    if key is not None:
        headers["Idempotency-Key"] = key
    return headers


async def _enable_mirror(session, seed) -> None:
    company = seed["company"]
    configured_at = datetime.now(UTC)
    configuration_id = uuid4()
    company.google_sheets_webhook_url = (
        "https://script.google.com/macros/s/finance-event-proof/exec"
    )
    company.google_sheets_mirror_enabled = True
    company.google_sheets_signing_secret_ciphertext = "test-encrypted-secret"
    company.google_sheets_configured_at = configured_at
    company.google_sheets_configuration_id = configuration_id
    await session.flush()
    connection_test = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=configuration_id,
        event_type=CONNECTION_TEST_EVENT_TYPE,
        source_type=CONNECTION_TEST_SOURCE_TYPE,
        source_id=str(uuid4()),
        source_revision=str(configuration_id),
        occurred_at=configured_at,
        payload={"status": "test"},
    )
    connection_test.status = "delivered"
    connection_test.delivered_at = configured_at
    await session.commit()


async def _mirror_events(
    session,
    *,
    company_id: UUID,
    source_types: tuple[str, ...],
) -> list[GoogleSheetsDelivery]:
    session.expire_all()
    return list(
        (
            await session.execute(
                select(GoogleSheetsDelivery)
                .where(
                    GoogleSheetsDelivery.company_id == company_id,
                    GoogleSheetsDelivery.source_type.in_(source_types),
                )
                .order_by(
                    GoogleSheetsDelivery.occurred_at,
                    GoogleSheetsDelivery.event_type,
                )
            )
        )
        .scalars()
        .all()
    )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_tip_capital_and_asset_events_are_signed_sanitized_and_idempotent(
    client,
    session,
    seed_owner,
) -> None:
    await _enable_mirror(session, seed_owner)
    now = datetime.now(UTC)
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company_currency = company.currency
    branch_name = branch.name
    owner_name = owner.name

    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=2_000,
        status="open",
    )
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="paid",
        subtotal_minor=1_000,
        tip_minor=1_000,
        total_minor=2_000,
        opened_at=now - timedelta(minutes=30),
        closed_at=now - timedelta(minutes=20),
        invoice_issued_at=now - timedelta(minutes=20),
        invoice_no=f"D/MIRROR/26-27/{uuid4().int % 100000:05d}",
        fiscal_year="2026-27",
    )
    partner = Partner(
        id=uuid4(),
        company_id=company.id,
        name="Mirror proof partner",
        share_pct=100,
        joined_at=now - timedelta(days=1),
    )
    session.add_all([shift, partner])
    await session.flush()
    session.add(order)
    await session.flush()
    session.add(
        Payment(
            id=uuid4(),
            order_id=order.id,
            shift_id=shift.id,
            method="cash",
            amount_minor=2_000,
            tendered_minor=2_000,
            change_minor=0,
            paid_at=now - timedelta(minutes=20),
        )
    )
    await session.commit()

    tip_note = "PRIVATE TIP DISTRIBUTION NOTE"
    tip_key = f"tip-payout:{uuid4()}"
    tip_payload = {
        "branch_id": str(branch.id),
        "shift_id": str(shift.id),
        "amount_minor": 500,
        "method": "cash",
        "paid_at": now.isoformat(),
        "note": tip_note,
    }
    tip = await client.post(
        "/api/v1/finance/tip-payouts",
        headers=_headers(seed_owner, key=tip_key),
        json=tip_payload,
    )
    assert tip.status_code == 201, tip.text
    tip_replay = await client.post(
        "/api/v1/finance/tip-payouts",
        headers=_headers(seed_owner, key=tip_key),
        json=tip_payload,
    )
    assert tip_replay.status_code == 201, tip_replay.text
    assert tip_replay.json() == tip.json()
    tip_void_reason = "PRIVATE TIP VOID REASON"
    for _ in range(2):
        voided = await client.post(
            f"/api/v1/finance/tip-payouts/{tip.json()['id']}/void",
            headers=_headers(seed_owner),
            json={"reason": tip_void_reason},
        )
        assert voided.status_code == 200, voided.text

    capital_rows: dict[str, dict[str, object]] = {}
    capital_secrets: list[str] = []
    for capital_type, amount in (("invest", 10_000), ("withdraw", 3_000)):
        source_ref = f"PRIVATE-CAPITAL-REFERENCE-{uuid4()}"
        note = f"PRIVATE {capital_type.upper()} NOTE"
        capital_secrets.extend((source_ref, note))
        key = f"capital-mirror-{uuid4()}"
        payload = {
            "partner_id": str(partner.id),
            "type": capital_type,
            "amount_minor": amount,
            "effective_at": now.isoformat(),
            "settlement_account": "bank",
            "source_ref": source_ref,
            "note": note,
        }
        response = await client.post(
            "/api/v1/finance/capital-entries",
            headers=_headers(seed_owner, key=key),
            json=payload,
        )
        assert response.status_code == 201, response.text
        capital_rows[capital_type] = response.json()
        if capital_type == "invest":
            replay = await client.post(
                "/api/v1/finance/capital-entries",
                headers=_headers(seed_owner, key=key),
                json=payload,
            )
            assert replay.status_code == 201, replay.text
            assert replay.json() == response.json()

    capital_void_reason = "PRIVATE CAPITAL VOID REASON"
    for capital_type, row in capital_rows.items():
        attempts = 2 if capital_type == "invest" else 1
        for _ in range(attempts):
            response = await client.post(
                f"/api/v1/finance/capital-entries/{row['id']}/void",
                headers=_headers(seed_owner),
                json={"reason": capital_void_reason},
            )
            assert response.status_code == 200, response.text

    asset_note = "PRIVATE ASSET PROCUREMENT NOTE"
    asset_key = f"asset-mirror-{uuid4()}"
    asset_payload = {
        "branch_id": str(branch.id),
        "name": "Mirror proof racing simulator",
        "type": "racing_simulator",
        "purchase_minor": 250_000,
        "purchase_date": (now - timedelta(days=1)).isoformat(),
        "useful_life_months": 60,
        "salvage_minor": 10_000,
        "notes": asset_note,
    }
    asset = await client.post(
        "/api/v1/finance/assets",
        headers=_headers(seed_owner, key=asset_key),
        json=asset_payload,
    )
    assert asset.status_code == 201, asset.text
    asset_replay = await client.post(
        "/api/v1/finance/assets",
        headers=_headers(seed_owner, key=asset_key),
        json=asset_payload,
    )
    assert asset_replay.status_code == 201, asset_replay.text
    assert asset_replay.json() == asset.json()

    events = await _mirror_events(
        session,
        company_id=company.id,
        source_types=("tip_payout", "capital_entry", "asset"),
    )
    assert len(events) == 7
    assert len({event.event_key for event in events}) == 7
    assert all(event.status == "pending" for event in events)
    assert all(event.payload["currency"] == company_currency for event in events)
    assert all(event.payload["actor"] == owner_name for event in events)

    tip_events = {event.event_type: event for event in events if event.source_type == "tip_payout"}
    assert tip_events["finance.tip_payout.recorded"].payload["amount_minor"] == -500
    assert tip_events["finance.tip_payout.voided"].payload["amount_minor"] == 500
    assert all(event.payload["branch"] == branch_name for event in tip_events.values())

    capital_events = [event for event in events if event.source_type == "capital_entry"]
    recorded_amounts = sorted(
        int(event.payload["amount_minor"])
        for event in capital_events
        if event.event_type == "finance.capital_entry.recorded"
    )
    voided_amounts = sorted(
        int(event.payload["amount_minor"])
        for event in capital_events
        if event.event_type == "finance.capital_entry.voided"
    )
    assert recorded_amounts == [-3_000, 10_000]
    assert voided_amounts == [-10_000, 3_000]
    assert all(event.payload["branch"] == "Company-wide" for event in capital_events)

    asset_events = [event for event in events if event.source_type == "asset"]
    assert len(asset_events) == 1
    assert asset_events[0].event_type == "finance.asset.registered"
    assert asset_events[0].source_revision == "registered-v1"
    assert asset_events[0].payload["amount_minor"] == -250_000
    assert asset_events[0].payload["branch"] == branch_name

    exported = json.dumps([event.payload for event in events], sort_keys=True)
    for forbidden in (
        tip_note,
        tip_void_reason,
        capital_void_reason,
        asset_note,
        *capital_secrets,
    ):
        assert forbidden not in exported


@pytest.mark.integration
@pytest.mark.asyncio
async def test_supplier_payment_events_use_safe_reference_and_survive_replays(
    client,
    session,
    seed_owner,
) -> None:
    await _enable_mirror(session, seed_owner)
    now = datetime.now(UTC)
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    company_currency = company.currency
    branch_name = branch.name
    owner_name = owner.name
    company.gst_registration_type = "unregistered"
    branch.state_code = "32"
    branch.code = f"M{uuid4().hex[:8].upper()}"

    for definition in (INVENTORY, ACCOUNTS_PAYABLE, CASH):
        session.add(
            Account(
                id=uuid4(),
                company_id=company.id,
                **definition.seed_dict(),
            )
        )
    supplier = Supplier(
        id=uuid4(),
        company_id=company.id,
        name="Mirror proof supplier",
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=company.id,
        sku=f"MIRROR-{uuid4().hex[:10]}",
        name="Mirror proof inventory",
        base_unit="unit",
        current_qty=0,
        avg_cost_minor=0,
        reorder_threshold=0,
        reorder_qty=0,
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=5_000,
        expected_minor=5_000,
        status="open",
    )
    session.add_all([supplier, ingredient, shift])
    await session.commit()
    supplier_id = supplier.id

    grn = await client.post(
        "/api/v1/inventory/grn",
        headers=_headers(seed_owner, key=f"grn-mirror-{uuid4()}"),
        json={
            "branch_id": str(branch.id),
            "supplier_id": str(supplier.id),
            "supplier_invoice_no": f"MIRROR-INVOICE-{uuid4().hex[:8]}",
            "supplier_invoice_amount_minor": 2_000,
            "received_at": (now - timedelta(minutes=5)).isoformat(),
            "lines": [
                {
                    "ingredient_id": str(ingredient.id),
                    "qty": 2,
                    "unit_cost_minor": 1_000,
                    "lot_code": f"MIRROR-{uuid4().hex[:6]}",
                }
            ],
        },
    )
    assert grn.status_code == 201, grn.text

    payment_reference = f"PRIVATE-BANK-REFERENCE-{uuid4()}"
    payment_note = "PRIVATE SUPPLIER PAYMENT NOTE"
    payment_key = f"supplier-payment:{uuid4()}"
    payment_payload = {
        "branch_id": str(branch.id),
        "shift_id": str(shift.id),
        "supplier_id": str(supplier.id),
        "grn_id": grn.json()["id"],
        "amount_minor": 2_000,
        "method": "cash",
        "paid_at": now.isoformat(),
        "payment_reference": payment_reference,
        "note": payment_note,
    }
    payment = await client.post(
        "/api/v1/finance/supplier-payments",
        headers=_headers(seed_owner, key=payment_key),
        json=payment_payload,
    )
    assert payment.status_code == 201, payment.text
    replay = await client.post(
        "/api/v1/finance/supplier-payments",
        headers=_headers(seed_owner, key=payment_key),
        json=payment_payload,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == payment.json()

    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == payment_key))
    await session.commit()
    durable_replay = await client.post(
        "/api/v1/finance/supplier-payments",
        headers=_headers(seed_owner, key=payment_key),
        json=payment_payload,
    )
    assert durable_replay.status_code == 201, durable_replay.text
    assert durable_replay.json() == payment.json()

    void_reason = "PRIVATE SUPPLIER VOID REASON"
    for _ in range(2):
        voided = await client.post(
            f"/api/v1/finance/supplier-payments/{payment.json()['id']}/void",
            headers=_headers(seed_owner),
            json={"reason": void_reason},
        )
        assert voided.status_code == 200, voided.text

    events = await _mirror_events(
        session,
        company_id=company.id,
        source_types=("supplier_payment",),
    )
    assert len(events) == 2
    by_type = {event.event_type: event for event in events}
    recorded = by_type["finance.supplier_payment.recorded"]
    reversed_event = by_type["finance.supplier_payment.voided"]
    assert recorded.payload["amount_minor"] == -2_000
    assert reversed_event.payload["amount_minor"] == 2_000
    assert recorded.payload["reference"] == reversed_event.payload["reference"]
    assert str(recorded.payload["reference"]).startswith("GRN-")
    assert recorded.payload["branch"] == branch_name
    assert recorded.payload["actor"] == owner_name
    assert recorded.payload["currency"] == company_currency
    assert recorded.payload["payment_method"] == "cash"
    assert recorded.payload["supplier_id"] == str(supplier_id)
    assert recorded.payload["grn_id"] == grn.json()["id"]
    assert len({event.event_key for event in events}) == 2

    exported = json.dumps([event.payload for event in events], sort_keys=True)
    assert payment_reference not in exported
    assert payment_note not in exported
    assert void_reason not in exported

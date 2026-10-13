"""Canonical receipt history, pagination, scope, and actor provenance."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest

from app.core.security import issue_access_token
from app.models import (
    Branch,
    GamingSession,
    GamingSessionExtension,
    GamingParticipantSettlement,
    MenuCategory,
    MenuItem,
    Order,
    Shift,
    Station,
    Terminal,
)


def _token(seed_owner: dict, *, branch_id) -> str:
    owner = seed_owner["owner"]
    return issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        roles=["owner"],
        branch_id=branch_id,
        auth_version=owner.auth_version,
    )


def _headers(seed_owner: dict, *, branch_id, terminal_id) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {_token(seed_owner, branch_id=branch_id)}",
        "X-Terminal-Id": str(terminal_id),
    }


async def _create_and_pay(
    client,
    *,
    headers: dict[str, str],
    shift_id,
    menu_item_id,
    tendered_minor: int = 2_000,
) -> dict:
    created = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": f"receipt-order-{uuid4()}"},
        json={
            "type": "takeaway",
            "shift_id": str(shift_id),
            "lines": [
                {
                    "client_line_id": str(uuid4()),
                    "menu_item_id": str(menu_item_id),
                    "qty": 1,
                    "note": "Chilled",
                }
            ],
        },
    )
    assert created.status_code == 201, created.text
    order = created.json()
    paid = await client.post(
        f"/api/v1/pos/orders/{order['id']}/payments",
        headers={**headers, "Idempotency-Key": f"receipt-payment-{uuid4()}"},
        json={
            "method": "cash",
            "amount_minor": order["total_minor"],
            "tendered_minor": tendered_minor,
            "expected_order_total_minor": order["total_minor"],
            "expected_due_minor": order["total_minor"],
            "ref_external": "counter-cash",
        },
    )
    assert paid.status_code == 201, paid.text
    return {"order": order, "payment": paid.json()}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_receipt_history_is_cursor_paged_branch_scoped_and_actor_complete(
    client,
    session,
    seed_owner,
) -> None:
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
        name=f"Receipt history {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"RH-{uuid4().hex[:10]}",
        name="Premium canned drink",
        type="drink",
        base_price_minor=1_250,
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
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code="PS5-RH",
        name="PS5 Receipt History",
        type="ps5",
        rate_per_hour_minor=20_000,
        is_active=True,
        tax_rate=0,
        rate_includes_tax=True,
    )
    session.add_all([category, shift, station])
    await session.flush()
    session.add(item)
    await session.commit()

    headers = _headers(
        seed_owner,
        branch_id=branch.id,
        terminal_id=terminal.id,
    )
    older = await _create_and_pay(
        client,
        headers=headers,
        shift_id=shift.id,
        menu_item_id=item.id,
    )

    # Attach the exact Gaming source before finalizing the second bill. The
    # labels remain display metadata; actor ids and timestamps are immutable.
    created_second = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": f"receipt-order-{uuid4()}"},
        json={
            "type": "takeaway",
            "shift_id": str(shift.id),
            "lines": [
                {
                    "client_line_id": str(uuid4()),
                    "menu_item_id": str(item.id),
                    "qty": 1,
                    "note": "Gaming add-on",
                }
            ],
        },
    )
    assert created_second.status_code == 201, created_second.text
    second = created_second.json()
    stopped_at = datetime.now(UTC) - timedelta(minutes=1)
    gaming_session = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        order_id=UUID(second["id"]),
        opened_by=owner.id,
        stopped_by=owner.id,
        sent_to_pos_by=owner.id,
        sent_to_pos_at=stopped_at,
        shift_id=shift.id,
        start_at=stopped_at - timedelta(minutes=30),
        end_at=stopped_at,
        paused_minutes=0,
        rate_per_hour_minor=20_000,
        billing_mode="package",
        package_price_minor_snapshot=15_000,
        package_duration_minutes_snapshot=60,
        package_variant_snapshot="dual",
        package_station_type_snapshot="ps5",
        package_pricing_tier_snapshot="standard",
        timer_minutes=90,
        extra_controllers=1,
        participant_revision=1,
        billable_minutes=90,
        amount_minor=24_000,
        status="ended",
        tax_rate=0,
        rate_includes_tax=True,
    )
    session.add(gaming_session)
    await session.flush()
    session.add(GamingSessionExtension(
        id=uuid4(),
        company_id=company.id,
        gaming_session_id=gaming_session.id,
        package_id=None,
        package_name="Dual +30 minutes",
        package_variant="dual",
        station_type="ps5",
        duration_minutes=30,
        package_price_minor=3_000,
        controller_surcharge_minor=0,
        total_minor=3_000,
        timer_before_minutes=60,
        timer_after_minutes=90,
        amount_before_minor=18_000,
        amount_after_minor=21_000,
        idempotency_key=f"receipt-extension-{uuid4()}",
        billing_revision=0,
        created_by=owner.id,
    ))
    session.add(GamingParticipantSettlement(
        id=uuid4(),
        company_id=company.id,
        gaming_session_id=gaming_session.id,
        base_amount_minor=21_000,
        guest_charge_minor=3_000,
        amount_after_minor=24_000,
        final_play_elapsed_ms=5_400_000,
        participant_revision_before=1,
        participant_revision=1,
        settled_at=stopped_at,
        settled_by=owner.id,
        terminal_id=terminal.id,
        stop_idempotency_key=f"receipt-stop-{uuid4()}",
        stop_request_hash="a" * 64,
    ))
    await session.commit()
    paid_second = await client.post(
        f"/api/v1/pos/orders/{second['id']}/payments",
        headers={**headers, "Idempotency-Key": f"receipt-payment-{uuid4()}"},
        json={
            "method": "cash",
            "amount_minor": second["total_minor"],
            "tendered_minor": 2_000,
            "expected_order_total_minor": second["total_minor"],
            "expected_due_minor": second["total_minor"],
            "ref_external": "gaming-counter",
        },
    )
    assert paid_second.status_code == 201, paid_second.text

    # Complete the real three-step cash-refund workflow for the older receipt.
    # Receipt history must show net collection and immutable refund provenance,
    # not merely label the order "refunded" while repeating gross payment.
    refund_amount = int(older["payment"]["amount_minor"])
    request_action_id = f"receipt-refund-request-{uuid4()}"
    requested = await client.post(
        "/api/v1/pos/refund-requests",
        headers={
            **headers,
            "Idempotency-Key": request_action_id,
            "X-Client-Action-Id": request_action_id,
        },
        json={
            "order_id": older["order"]["id"],
            "shift_id": str(shift.id),
            "reason_code": "CUSTOMER_REQUEST",
            "amount_minor": refund_amount,
            "expected_paid_minor": refund_amount,
            "expected_refundable_minor": refund_amount,
            "mode": "cash",
            "client_action_id": request_action_id,
            "note": "Canonical receipt refund proof",
        },
    )
    assert requested.status_code == 201, requested.text
    refund_request_id = requested.json()["id"]

    reserved_detail = await client.get(
        f"/api/v1/pos/receipts/{older['order']['id']}",
        headers=headers,
    )
    assert reserved_detail.status_code == 200, reserved_detail.text
    assert reserved_detail.json()["pending_refund_minor"] == refund_amount
    assert reserved_detail.json()["refundable_minor"] == 0

    begin_action_id = f"receipt-refund-begin-{uuid4()}"
    begun = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/begin-cash-handoff",
        headers={
            **headers,
            "Idempotency-Key": begin_action_id,
            "X-Client-Action-Id": begin_action_id,
        },
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": refund_amount,
            "ready_to_handover": True,
        },
    )
    assert begun.status_code == 201, begun.text

    settle_action_id = f"receipt-refund-settle-{uuid4()}"
    settled = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/settle-cash",
        headers={
            **headers,
            "Idempotency-Key": settle_action_id,
            "X-Client-Action-Id": settle_action_id,
        },
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": refund_amount,
            "cash_handed_over": True,
            "settled_at": begun.json()["handoff_started_at"],
        },
    )
    assert settled.status_code == 201, settled.text

    finalize_action_id = f"receipt-refund-finalize-{uuid4()}"
    finalized = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/finalize-cash",
        headers={
            **headers,
            "Idempotency-Key": finalize_action_id,
            "X-Client-Action-Id": finalize_action_id,
        },
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": refund_amount,
        },
    )
    assert finalized.status_code == 201, finalized.text
    assert finalized.json()["status"] == "settled"

    # A receipt in another branch of the same company must not appear even
    # though the authenticated user is the same owner.
    other_branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name=f"Other {uuid4().hex[:8]}",
        invoice_series_code="Z9",
        state_code="32",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name="Other Hybrid",
        purpose="hybrid",
        is_active=True,
        device_id=f"receipt-other-{uuid4()}",
    )
    other_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=other_branch.id,
        terminal_id=other_terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC) - timedelta(minutes=30),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add(other_branch)
    await session.flush()
    session.add(other_terminal)
    await session.flush()
    session.add(other_shift)
    await session.commit()
    other_headers = _headers(
        seed_owner,
        branch_id=other_branch.id,
        terminal_id=other_terminal.id,
    )
    other = await _create_and_pay(
        client,
        headers=other_headers,
        shift_id=other_shift.id,
        menu_item_id=item.id,
    )

    first_page = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"limit": 1},
    )
    assert first_page.status_code == 200, first_page.text
    first_body = first_page.json()
    assert first_body["has_more"] is True
    assert first_body["next_cursor"]
    assert len(first_body["items"]) == 1

    second_page = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"limit": 1, "cursor": first_body["next_cursor"]},
    )
    assert second_page.status_code == 200, second_page.text
    second_body = second_page.json()
    assert second_body["has_more"] is False
    assert second_body["next_cursor"] is None
    own_ids = {
        first_body["items"][0]["order_id"],
        second_body["items"][0]["order_id"],
    }
    assert own_ids == {older["order"]["id"], second["id"]}
    assert other["order"]["id"] not in own_ids

    # Ordinary booked controllers do not create a participant settlement:
    # revision zero means nobody joined late. The finalized package amount and
    # captured controller count must still explain the immutable surcharge.
    regular_booking = await _create_and_pay(
        client,
        headers=headers,
        shift_id=shift.id,
        menu_item_id=item.id,
    )
    regular_booking_session = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        order_id=UUID(regular_booking["order"]["id"]),
        opened_by=owner.id,
        stopped_by=owner.id,
        sent_to_pos_by=owner.id,
        sent_to_pos_at=stopped_at,
        shift_id=shift.id,
        start_at=stopped_at - timedelta(minutes=60),
        end_at=stopped_at,
        paused_minutes=0,
        rate_per_hour_minor=20_000,
        billing_mode="package",
        package_price_minor_snapshot=15_000,
        package_duration_minutes_snapshot=60,
        package_variant_snapshot="dual",
        package_station_type_snapshot="ps5",
        package_pricing_tier_snapshot="standard",
        timer_minutes=60,
        extra_controllers=1,
        participant_revision=0,
        billable_minutes=60,
        amount_minor=18_000,
        status="ended",
        tax_rate=0,
        rate_includes_tax=True,
    )
    session.add(regular_booking_session)
    await session.commit()
    regular_detail = await client.get(
        f"/api/v1/pos/receipts/{regular_booking['order']['id']}",
        headers=headers,
    )
    assert regular_detail.status_code == 200, regular_detail.text
    regular_source = regular_detail.json()["gaming_sessions"][0]
    assert regular_source["booked_controller_surcharge_minor"] == 3_000
    assert regular_source["late_friend_charge_minor"] is None

    # Exact receipt search must query retained history, not the operational
    # list's newest-500 window. These finalized rows are deliberately newer
    # than the target and need no line expansion because exact search excludes
    # them before the receipt projection is built.
    issued_base = datetime.now(UTC) + timedelta(minutes=1)
    filler_orders: list[Order] = []
    for index in range(501):
        filler_order_id = uuid4()
        issued_at = issued_base + timedelta(seconds=index)
        filler_orders.append(Order(
            id=filler_order_id,
            company_id=company.id,
            branch_id=branch.id,
            terminal_id=terminal.id,
            shift_id=shift.id,
            opened_by=owner.id,
            type="takeaway",
            status="paid",
            subtotal_minor=0,
            total_minor=0,
            opened_at=issued_at,
            closed_at=issued_at,
            invoice_issued_at=issued_at,
            invoice_no=f"RH/26-27/{index + 1000:06d}",
            fiscal_year="2026-27",
            source_integrity_revision=48,
        ))
    session.add_all(filler_orders)
    scoped_cursor_orders = []
    for label, seconds in (("A", 600), ("B", 601)):
        issued_at = issued_base + timedelta(seconds=seconds)
        scoped_cursor_orders.append(Order(
            id=uuid4(),
            company_id=company.id,
            branch_id=branch.id,
            terminal_id=terminal.id,
            shift_id=shift.id,
            opened_by=owner.id,
            type="takeaway",
            status="paid",
            subtotal_minor=0,
            total_minor=0,
            opened_at=issued_at,
            closed_at=issued_at,
            invoice_issued_at=issued_at,
            invoice_no=f"SCOPE/{label}/000001",
            fiscal_year="2026-27",
            source_integrity_revision=48,
        ))
    session.add_all(scoped_cursor_orders)
    await session.commit()

    second_invoice = paid_second.json()["invoice_no"]
    invoice_suffix = second_invoice.rsplit("/", 1)[-1]
    for query in (
        second_invoice,
        invoice_suffix,
        second["id"],
        str(gaming_session.id),
    ):
        searched = await client.get(
            "/api/v1/pos/receipts",
            headers=headers,
            params={"q": query, "limit": 1},
        )
        assert searched.status_code == 200, searched.text
        assert [row["order_id"] for row in searched.json()["items"]] == [second["id"]]

    older_search = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"q": older["order"]["id"]},
    )
    assert older_search.status_code == 200, older_search.text
    assert [row["order_id"] for row in older_search.json()["items"]] == [older["order"]["id"]]

    cross_branch_search = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"q": other["order"]["id"]},
    )
    assert cross_branch_search.status_code == 200, cross_branch_search.text
    assert cross_branch_search.json()["items"] == []

    literal_wildcards = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"q": "%_"},
    )
    assert literal_wildcards.status_code == 200, literal_wildcards.text
    assert literal_wildcards.json()["items"] == []

    mismatched_cursor = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"q": second_invoice, "cursor": first_body["next_cursor"]},
    )
    assert mismatched_cursor.status_code == 422
    assert "cursor is invalid" in mismatched_cursor.json()["error"]["message"]

    scoped_first = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"q": "000001", "limit": 1},
    )
    assert scoped_first.status_code == 200, scoped_first.text
    assert scoped_first.json()["has_more"] is True
    scoped_cursor = scoped_first.json()["next_cursor"]
    assert scoped_cursor
    scoped_second = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"q": "000001", "limit": 1, "cursor": scoped_cursor},
    )
    assert scoped_second.status_code == 200, scoped_second.text
    assert len(scoped_second.json()["items"]) == 1
    foreign_scoped_cursor = await client.get(
        "/api/v1/pos/receipts",
        headers=other_headers,
        params={"q": "000001", "limit": 1, "cursor": scoped_cursor},
    )
    assert foreign_scoped_cursor.status_code == 422
    assert "cursor is invalid" in foreign_scoped_cursor.json()["error"]["message"]

    # The legacy no-query synchronization cursor intentionally remains the
    # original two-field shape and is not scope-bound.
    legacy_cursor_other_scope = await client.get(
        "/api/v1/pos/receipts",
        headers=other_headers,
        params={"limit": 1, "cursor": first_body["next_cursor"]},
    )
    assert legacy_cursor_other_scope.status_code == 200, legacy_cursor_other_scope.text

    detail = await client.get(
        f"/api/v1/pos/receipts/{second['id']}",
        headers=headers,
    )
    assert detail.status_code == 200, detail.text
    receipt = detail.json()
    assert receipt["invoice_no"] == paid_second.json()["invoice_no"]
    assert receipt["subtotal_minor"] == second["subtotal_minor"]
    assert receipt["tax_minor"] == second["tax_minor"]
    assert receipt["total_minor"] == second["total_minor"]
    assert receipt["paid_minor"] == second["total_minor"]
    assert receipt["refunded_minor"] == 0
    assert receipt["pending_refund_minor"] == 0
    assert receipt["refundable_minor"] == second["total_minor"]
    assert receipt["net_collected_minor"] == second["total_minor"]
    assert receipt["refunds"] == []
    assert receipt["opened_by"] == str(owner.id)
    assert receipt["opened_by_name"] == owner.name
    assert receipt["lines"][0]["menu_item_name"] == item.name
    assert receipt["lines"][0]["qty"] == "1.000"
    assert receipt["payments"][0]["recorded_by"] == str(owner.id)
    assert receipt["payments"][0]["recorded_by_name"] == owner.name
    assert receipt["payments"][0]["tendered_minor"] == 2_000
    assert receipt["payments"][0]["change_minor"] == 2_000 - second["total_minor"]
    assert receipt["payments"][0]["reference"] == "gaming-counter"
    source = receipt["gaming_sessions"][0]
    assert source["id"] == str(gaming_session.id)
    assert source["station_id"] == str(station.id)
    assert source["started_by_name"] == owner.name
    assert source["stopped_by_name"] == owner.name
    assert source["sent_to_pos_by_name"] == owner.name
    assert source["billing_mode"] == "package"
    assert source["rate_per_hour_minor"] == 20_000
    assert source["effective_package_price_minor_snapshot"] == 15_000
    assert source["effective_package_duration_minutes_snapshot"] == 60
    assert source["effective_package_variant_snapshot"] == "dual"
    assert source["extension_package_minor"] == 3_000
    assert source["extension_controller_surcharge_minor"] == 0
    assert source["booked_controller_surcharge_minor"] == 3_000
    assert source["late_friend_charge_minor"] == 3_000
    assert datetime.fromisoformat(
        source["sent_to_pos_at"].replace("Z", "+00:00")
    ) == stopped_at

    refunded_detail = await client.get(
        f"/api/v1/pos/receipts/{older['order']['id']}",
        headers=headers,
    )
    assert refunded_detail.status_code == 200, refunded_detail.text
    refunded_receipt = refunded_detail.json()
    assert refunded_receipt["status"] == "refunded"
    assert refunded_receipt["paid_minor"] == refund_amount
    assert refunded_receipt["refunded_minor"] == refund_amount
    assert refunded_receipt["pending_refund_minor"] == 0
    assert refunded_receipt["refundable_minor"] == 0
    assert refunded_receipt["net_collected_minor"] == 0
    refund = refunded_receipt["refunds"][0]
    assert refund["request_id"] == refund_request_id
    assert refund["settlement_shift_id"] == str(shift.id)
    assert refund["amount_minor"] == refund_amount
    assert refund["mode"] == "cash"
    assert refund["settlement_method"] == "cash"
    assert refund["external_reference"] is None
    assert refund["approved_by"] == str(owner.id)
    assert refund["approved_by_name"] == owner.name
    assert refund["settled_by"] == str(owner.id)
    assert refund["settled_by_name"] == owner.name
    assert refund["receipt_no"]
    assert refund["receipt_fiscal_year"]
    assert refund["receipt_issued_at"]

    hidden = await client.get(
        f"/api/v1/pos/receipts/{other['order']['id']}",
        headers=headers,
    )
    assert hidden.status_code == 404

    bad_cursor = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={"cursor": "not-a-valid-cursor"},
    )
    assert bad_cursor.status_code == 422
    assert "cursor is invalid" in bad_cursor.json()["error"]["message"]

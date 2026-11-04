"""Network and cohort contracts for the reduced Reports Overview projection."""

from __future__ import annotations

import csv
import io
import json
from datetime import UTC, datetime, time, timedelta
from decimal import Decimal
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import issue_access_token
from app.core.timezone import local_date_bounds_utc, local_today
from app.models import (
    Branch,
    Company,
    Customer,
    GamingSession,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    RolePermissionOverride,
    Shift,
    Station,
    Terminal,
    User,
)
from app.services.reports.aggregator import ReportsAggregator


def _token(seed_owner: dict, *, roles: list[str], branch_id=None) -> str:
    owner = seed_owner["owner"]
    return issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id if branch_id is None else branch_id,
        roles=roles,
        auth_version=owner.auth_version,
    )


def _headers(seed_owner: dict, *, roles: list[str]) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {_token(seed_owner, roles=roles)}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }


def _mutation_headers(token: str, terminal_id, action_id: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
        "Idempotency-Key": action_id,
        "X-Client-Action-Id": action_id,
    }


def _assert_business_rule(response, message: str) -> None:
    assert response.status_code == 422, response.text
    assert response.json() == {
        "error": {
            "code": "business_rule",
            "message": message,
            "details": {},
        }
    }


async def _persist_paid_order(
    session,
    *,
    order: Order,
    line: OrderLine,
    payment: Payment,
    additional_lines: tuple[OrderLine, ...] = (),
) -> None:
    """Insert through the real open -> issued/paid -> payment lifecycle."""

    final_closed_at = order.closed_at
    final_invoice_issued_at = order.invoice_issued_at
    order.status = "open"
    order.closed_at = None
    order.invoice_issued_at = None
    order.invoice_no = None
    order.fiscal_year = None
    session.add(order)
    await session.flush()
    session.add_all([line, *additional_lines])
    await session.flush()
    order.status = "paid"
    order.closed_at = final_closed_at
    order.invoice_issued_at = final_invoice_issued_at
    order.invoice_no = f"OV/{uuid4().int % 100000000:08d}"
    order.fiscal_year = "2026-27"
    await session.flush()
    payment.paid_at = final_invoice_issued_at
    session.add(payment)
    await session.flush()


def _csv_rows(response) -> list[dict[str, str]]:
    assert response.status_code == 200, response.text
    assert response.headers["content-type"] == "text/csv; charset=utf-8"
    assert response.headers["cache-control"] == "no-store"
    reader = csv.DictReader(io.StringIO(response.content.decode("utf-8"), newline=""))
    assert reader.fieldnames == [
        "csv_schema_version", "schema_version", "branch_id", "period_start", "period_end",
        "timezone", "snapshot_at", "accounting_basis", "section", "row_key", "label",
        "metric", "value", "unit", "availability", "basis",
    ]
    rows = list(reader)
    assert rows and all(None not in row and None not in row.values() for row in rows)
    return rows


def _assert_csv_matches_overview(response, payload) -> list[dict[str, str]]:
    rows = _csv_rows(response)
    assert response.headers["content-disposition"] == (
        'attachment; filename="reports-overview-'
        f'{payload["period_start"]}-{payload["period_end"]}.csv"'
    )
    snapshots = {row["snapshot_at"] for row in rows}
    assert len(snapshots) == 1
    assert datetime.fromisoformat(next(iter(snapshots))).tzinfo is not None
    for row in rows:
        assert row["csv_schema_version"] == "reports.overview.csv.v1"
        for field in ("schema_version", "branch_id", "period_start", "period_end", "accounting_basis"):
            assert row[field] == payload[field]
        assert row["timezone"] == "'" + payload["timezone"]
    actual = {(row["section"], row["row_key"], row["metric"]): row for row in rows}
    assert len(actual) == len(rows), "Export row identities must be unique"

    def expect(section, key, metric, value, unit, basis, *, label=None, availability="available"):
        row = actual.pop((section, key, metric))
        rendered = "" if value is None else str(value).lower() if isinstance(value, bool) else str(value)
        assert row["value"] == rendered
        assert row["unit"] == unit and row["availability"] == availability
        assert row["basis"] == basis
        if label is not None:
            assert row["label"] == label

    for metric, unit, basis in (
        ("gross_revenue_minor", "paise", payload["accounting_basis"]),
        ("orders_count", "count", payload["accounting_basis"]),
        ("sessions_started_count", "count", payload["sessions_basis"]),
    ):
        expect("overview", "summary", metric, payload[metric], unit, basis)
    for section in ("revenue_by_source", "payments_received", "linked_customers"):
        for metric, value in payload[section].items():
            if metric == "basis":
                continue
            unit = "boolean" if isinstance(value, bool) else "paise" if metric.endswith("_minor") else "count"
            expect(section, "summary", metric, value, unit, payload[section]["basis"])
    hours = payload["local_hour_profile"]
    hour_basis = "pos_sales_tip_exclusive_local_hour_profile"
    if hours["availability"] == "available":
        for metric, unit in (
            ("timezone", "text"), ("business_date", "date"), ("day_length_minutes", "minutes"),
            ("total_revenue_minor", "paise"), ("total_orders_count", "count"),
            ("reconciles_to_daily_gross", "boolean"),
        ):
            value = "'" + hours[metric] if metric == "timezone" else hours[metric]
            expect("local_hour_profile", "summary", metric, value, unit, hour_basis)
        for point in hours["points"]:
            for metric, unit in (("hour", "local_hour"), ("revenue_minor", "paise"), ("orders_count", "count")):
                expect("local_hour_profile", f'{point["hour"]:02d}', metric, point[metric], unit, hour_basis)
    else:
        expect("local_hour_profile", "summary", "reason", hours["reason"], "text", hour_basis, availability="unavailable")
        for metric, unit in (("total_revenue_minor", "paise"), ("total_orders_count", "count")):
            expect("local_hour_profile", "summary", metric, None, unit, hour_basis, availability="unavailable")
    products = payload["top_products"]
    for metric, value, unit in (
        ("limit", products["limit"], "count"), ("rows_count", len(products["rows"]), "count"),
        ("refunds_basis", products["refunds_basis"], "text"),
        ("reconciles_to_gross", products["reconciles_to_gross"], "boolean"),
    ):
        expect("top_products", "summary", metric, value, unit, products["basis"], label="Top 5 products by gross settled POS line sales")
    for index, product in enumerate(products["rows"], 1):
        for metric, unit in (("menu_item_id", "uuid"), ("product_type", "text"), ("quantity_sold", "quantity"), ("gross_line_sales_minor", "paise")):
            value = "'" + product[metric] if metric == "product_type" else product[metric]
            expect("top_products", str(index), metric, value, unit, products["basis"], label="'" + product["name"])
    usage = payload["device_usage"]
    for metric, value, unit in (("grouping", usage["grouping"], "text"), ("time_basis", usage["time_basis"], "text"), ("rows_count", len(usage["rows"]), "count")):
        expect("device_usage", "summary", metric, value, unit, usage["basis"])
    for index, device in enumerate(usage["rows"], 1):
        for metric in ("completed_sessions_count", "unknown_play_sessions_count"):
            expect("device_usage", str(index), metric, device[metric], "count", usage["basis"], label="'" + device["device_type"])
        expect("device_usage", str(index), "play_elapsed_ms", device["play_elapsed_ms"], "milliseconds", usage["time_basis"],
               label="'" + device["device_type"], availability="unknown" if device["play_elapsed_ms"] is None else "available")
    assert not actual, f"Unexpected/unmatched CSV fields: {set(actual)}"
    return rows


@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_is_reduced_branch_scoped_and_uses_one_aggregate(
    client,
    session,
    seed_owner,
    monkeypatch,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    # Pin the cohort to a fully elapsed company-local day. Wall-clock-relative
    # fixtures can cross the local date boundary when this test runs near
    # midnight and silently move otherwise valid sales outside the request.
    business_date = local_today(company.timezone) - timedelta(days=2)
    from_dt, to_dt = local_date_bounds_utc(
        business_date,
        business_date,
        company.timezone,
    )
    cohort_noon = datetime.combine(
        business_date,
        time(hour=12),
        tzinfo=ZoneInfo(company.timezone),
    ).astimezone(UTC)

    foreign_branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name=f"Foreign reports branch {uuid4().hex[:8]}",
        invoice_series_code="RO",
    )
    session.add(foreign_branch)
    await session.flush()
    foreign_terminal = Terminal(
        id=uuid4(),
        branch_id=foreign_branch.id,
        name="Foreign reports terminal",
        device_id=f"reports-{uuid4()}",
    )
    session.add(foreign_terminal)
    await session.flush()
    main_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=from_dt - timedelta(hours=2),
        opening_float_minor=100_000,
        expected_minor=100_000,
        status="open",
    )
    foreign_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=foreign_branch.id,
        terminal_id=foreign_terminal.id,
        opened_by=owner.id,
        opened_at=from_dt - timedelta(hours=2),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(), company_id=company.id, name=f"Overview {uuid4().hex[:8]}"
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"OV-{uuid4().hex[:12]}",
        name="Synthetic overview item",
        type="food",
        base_price_minor=1_000,
        tax_rate=0,
        is_available=True,
    )
    customer = Customer(
        id=uuid4(),
        company_id=company.id,
        phone=f"+9715{uuid4().int % 10**8:08d}",
        name="PRIVATE OVERVIEW CUSTOMER",
    )
    deleted_customer = Customer(
        id=uuid4(),
        company_id=company.id,
        phone=f"+9715{uuid4().int % 10**8:08d}",
        name="PRIVATE DELETED OVERVIEW CUSTOMER",
    )
    main_station = Station(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        code=f"OV-{uuid4().hex[:6]}", name="PRIVATE MAIN STATION", type="ps5",
        rate_per_hour_minor=10_000, is_active=True,
    )
    foreign_station = Station(
        id=uuid4(), company_id=company.id, branch_id=foreign_branch.id,
        code=f"FV-{uuid4().hex[:6]}", name="PRIVATE FOREIGN STATION", type="ps5",
        rate_per_hour_minor=10_000, is_active=True,
    )
    session.add_all(
        [
            main_shift,
            foreign_shift,
            category,
            item,
            customer,
            deleted_customer,
            main_station,
            foreign_station,
        ]
    )
    await session.flush()

    settled = (
        (1_000, customer.id, "cash", terminal, main_shift),
        (2_000, deleted_customer.id, "card", terminal, main_shift),
        (3_000, None, "upi", terminal, main_shift),
        (4_000, None, "cash", terminal, main_shift),
        (90_000, None, "cash", foreign_terminal, foreign_shift),
    )
    settled_ids: list[str] = []
    refunded_order: Order | None = None
    for index, (amount, customer_id, method, order_terminal, shift) in enumerate(settled):
        sale_at = cohort_noon + timedelta(minutes=index)
        target_branch = branch if order_terminal.id == terminal.id else foreign_branch
        order = Order(
            id=uuid4(), company_id=company.id, branch_id=target_branch.id,
            terminal_id=order_terminal.id, shift_id=shift.id, opened_by=owner.id,
            customer_id=customer_id, type="takeaway", status="paid",
            subtotal_minor=amount, total_minor=amount,
            opened_at=sale_at - timedelta(minutes=1), closed_at=sale_at,
            invoice_issued_at=sale_at, invoice_no="placeholder", fiscal_year="2026-27",
            customer_name="PRIVATE ORDER NAME", customer_phone="+971500000000",
        )
        line = OrderLine(
            id=uuid4(), order_id=order.id, menu_item_id=item.id,
            menu_item_name_snapshot=item.name,
            menu_item_type_snapshot=item.type,
            qty=Decimal("1.250") if amount == 1_000 else Decimal("1.000"),
            unit_price_minor=800 if amount == 1_000 else amount,
            line_total_minor=amount, discount_minor=0, tax_rate=0,
            taxable_value_minor=amount, cgst_minor=0, sgst_minor=0,
            igst_minor=0, cess_minor=0,
        )
        payment = Payment(
            id=uuid4(), order_id=order.id, shift_id=shift.id,
            method=method, amount_minor=amount, tendered_minor=amount,
            change_minor=0, paid_at=sale_at,
        )
        if method != "cash":
            payment.tendered_minor = None
            payment.change_minor = None
        await _persist_paid_order(session, order=order, line=line, payment=payment)
        settled_ids.append(str(order.id))
        if amount == 4_000 and order_terminal.id == terminal.id:
            refunded_order = order

    assert refunded_order is not None

    # These valid rows are deliberately outside the settled in-range cohort:
    # one is still open, and the other two sit immediately before/at the
    # half-open UTC bounds. Their conspicuous values make leakage obvious.
    open_order = Order(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, shift_id=main_shift.id, opened_by=owner.id,
        type="takeaway", status="open", subtotal_minor=60_000,
        total_minor=60_000, opened_at=cohort_noon + timedelta(hours=1),
    )
    open_line = OrderLine(
        id=uuid4(), order_id=open_order.id, menu_item_id=item.id,
        menu_item_name_snapshot=item.name,
        menu_item_type_snapshot=item.type, qty=1, unit_price_minor=60_000,
        line_total_minor=60_000, discount_minor=0, tax_rate=0,
        taxable_value_minor=60_000, cgst_minor=0, sgst_minor=0,
        igst_minor=0, cess_minor=0,
    )
    session.add(open_order)
    await session.flush()
    session.add(open_line)
    await session.flush()
    settled_ids.append(str(open_order.id))

    for index, outside_sale_at in enumerate(
        (from_dt - timedelta(seconds=1), to_dt)
    ):
        outside_amount = 70_000 + index * 10_000
        outside_order = Order(
            id=uuid4(), company_id=company.id, branch_id=branch.id,
            terminal_id=terminal.id, shift_id=main_shift.id, opened_by=owner.id,
            type="takeaway", status="paid", subtotal_minor=outside_amount,
            total_minor=outside_amount,
            opened_at=outside_sale_at - timedelta(minutes=1),
            closed_at=outside_sale_at, invoice_issued_at=outside_sale_at,
            invoice_no="placeholder", fiscal_year="2026-27",
        )
        outside_line = OrderLine(
            id=uuid4(), order_id=outside_order.id, menu_item_id=item.id,
            menu_item_name_snapshot=item.name,
            menu_item_type_snapshot=item.type, qty=1,
            unit_price_minor=outside_amount, line_total_minor=outside_amount,
            discount_minor=0, tax_rate=0, taxable_value_minor=outside_amount,
            cgst_minor=0, sgst_minor=0, igst_minor=0, cess_minor=0,
        )
        outside_payment = Payment(
            id=uuid4(), order_id=outside_order.id, shift_id=main_shift.id,
            method="cash", amount_minor=outside_amount,
            tendered_minor=outside_amount, change_minor=0,
            paid_at=outside_sale_at,
        )
        await _persist_paid_order(
            session,
            order=outside_order,
            line=outside_line,
            payment=outside_payment,
        )
        settled_ids.append(str(outside_order.id))

    deleted_customer.deleted_at = cohort_noon
    item.name = "Renamed after sale"
    item.type = "drink"
    item.is_available = False

    session.add_all(
        [
            GamingSession(
                id=uuid4(), company_id=company.id, station_id=main_station.id,
                shift_id=main_shift.id, opened_by=owner.id,
                start_at=cohort_noon - timedelta(minutes=20),
                rate_per_hour_minor=10_000,
                status="active", customer_name="PRIVATE SESSION CUSTOMER",
            ),
            GamingSession(
                id=uuid4(), company_id=company.id, station_id=main_station.id,
                shift_id=main_shift.id, opened_by=owner.id,
                start_at=cohort_noon - timedelta(minutes=18),
                end_at=cohort_noon - timedelta(minutes=17),
                rate_per_hour_minor=10_000, amount_minor=0,
                status="cancelled",
                cancelled_at=cohort_noon - timedelta(minutes=17),
                cancelled_by=owner.id,
                cancel_reason="Synthetic cancelled Overview exclusion",
            ),
            GamingSession(
                id=uuid4(), company_id=company.id, station_id=foreign_station.id,
                shift_id=foreign_shift.id, opened_by=owner.id,
                start_at=cohort_noon - timedelta(minutes=10),
                rate_per_hour_minor=10_000,
                status="active", customer_name="PRIVATE FOREIGN SESSION",
            ),
        ]
    )
    await session.commit()

    # Drive one cohort member through the real refund workflow. A refunded
    # invoice remains in the original gross/order/hour denominator; the
    # reduced Overview intentionally does not serialize refund/P&L facts.
    refund_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    refund_request_key = f"overview-refund-request:{uuid4()}"
    accepted_refund = await client.post(
        "/api/v1/pos/refund-requests",
        json={
            "order_id": str(refunded_order.id),
            "shift_id": str(main_shift.id),
            "reason_code": "CUSTOMER_REQUEST",
            "amount_minor": 4_000,
            "expected_paid_minor": 4_000,
            "expected_refundable_minor": 4_000,
            "mode": "cash",
            "client_action_id": refund_request_key,
            "note": "Reports Overview refunded-order cohort proof",
        },
        headers=_mutation_headers(refund_token, terminal.id, refund_request_key),
    )
    assert accepted_refund.status_code == 201, accepted_refund.text
    refund_request_id = accepted_refund.json()["id"]

    begin_key = f"overview-refund-begin:{uuid4()}"
    begun_refund = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/begin-cash-handoff",
        json={
            "shift_id": str(main_shift.id),
            "expected_amount_minor": 4_000,
            "ready_to_handover": True,
        },
        headers=_mutation_headers(refund_token, terminal.id, begin_key),
    )
    assert begun_refund.status_code == 201, begun_refund.text

    settle_key = f"overview-refund-settle:{uuid4()}"
    settled_refund = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/settle-cash",
        json={
            "shift_id": str(main_shift.id),
            "expected_amount_minor": 4_000,
            "cash_handed_over": True,
            "settled_at": begun_refund.json()["handoff_started_at"],
        },
        headers=_mutation_headers(refund_token, terminal.id, settle_key),
    )
    assert settled_refund.status_code == 201, settled_refund.text

    finalize_key = f"overview-refund-finalize:{uuid4()}"
    finalized_refund = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/finalize-cash",
        json={
            "shift_id": str(main_shift.id),
            "expected_amount_minor": 4_000,
        },
        headers=_mutation_headers(refund_token, terminal.id, finalize_key),
    )
    assert finalized_refund.status_code == 201, finalized_refund.text
    assert finalized_refund.json()["status"] == "settled"
    await session.refresh(refunded_order)
    assert refunded_order.status == "refunded"

    statements: list[str] = []
    aggregate_calls = 0
    original_execute = AsyncSession.execute
    original_aggregate = ReportsAggregator.aggregate

    async def tracked_execute(active_session, statement, *args, **kwargs):
        statements.append(str(statement))
        return await original_execute(active_session, statement, *args, **kwargs)

    async def counted_aggregate(active_aggregator, *args, **kwargs):
        nonlocal aggregate_calls
        aggregate_calls += 1
        return await original_aggregate(active_aggregator, *args, **kwargs)

    monkeypatch.setattr(AsyncSession, "execute", tracked_execute)
    monkeypatch.setattr(ReportsAggregator, "aggregate", counted_aggregate)
    response = await client.get(
        "/api/v1/reports/overview",
        params={"from_date": business_date.isoformat(), "to_date": business_date.isoformat()},
        headers=_headers(seed_owner, roles=["owner"]),
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert aggregate_calls == 1
    assert len(statements) <= 64, f"Overview query bound exceeded: {len(statements)}"
    assert set(payload) == {
        "schema_version", "branch_id", "period_start", "period_end", "timezone",
        "snapshot_at", "accounting_basis", "gross_revenue_minor", "orders_count",
        "sessions_started_count", "sessions_basis", "revenue_by_source",
        "payments_received", "linked_customers", "local_hour_profile",
    }
    assert payload["schema_version"] == "reports.overview.v1"
    assert payload["branch_id"] == str(branch.id)
    assert payload["gross_revenue_minor"] == 10_000
    assert payload["orders_count"] == 4
    assert payload["sessions_started_count"] == 1
    assert payload["linked_customers"] == {
        "basis": "current_order_linkage",
        "distinct_linked_customers_count": 2,
        "linked_orders_count": 2,
        "unlinked_orders_count": 2,
    }
    sources = payload["revenue_by_source"]
    assert sources["food_minor"] == 10_000
    assert sources["positive_source_denominator_minor"] == 10_000
    assert sources["adjustments_minor"] == 0
    assert sources["total_minor"] == 10_000
    assert sources["reconciles_to_gross"] is True
    assert payload["payments_received"]["total_minor"] == 10_000
    profile = payload["local_hour_profile"]
    assert profile["availability"] == "available"
    assert profile["reconciles_to_daily_gross"] is False
    assert profile["basis"] == "pos_sales_tip_exclusive_local_hour_profile"
    assert [point["hour"] for point in profile["points"]] == list(range(24))
    assert profile["total_revenue_minor"] == 10_000
    assert profile["total_orders_count"] == 4

    serialized = json.dumps(payload)
    for forbidden in (
        "PRIVATE OVERVIEW CUSTOMER",
        "PRIVATE DELETED OVERVIEW CUSTOMER",
        "PRIVATE ORDER NAME",
        "PRIVATE SESSION CUSTOMER",
        "PRIVATE FOREIGN SESSION",
        *settled_ids,
        str(owner.id),
        "expenses",
        "cogs_minor",
        "depreciation_minor",
        "net_profit_minor",
        "tax_collected",
        "tender_reclassifications",
    ):
        assert forbidden not in serialized

    v1_query_count = len(statements)
    statements.clear()
    aggregate_calls = 0
    expanded = await client.get(
        "/api/v1/reports/overview",
        params={
            "from_date": business_date.isoformat(),
            "to_date": business_date.isoformat(),
            "schema_version": "reports.overview.v2",
        },
        headers=_headers(seed_owner, roles=["owner"]),
    )
    assert expanded.status_code == 200, expanded.text
    v2 = expanded.json()
    assert aggregate_calls == 1
    assert len(statements) == v1_query_count + 2
    assert any(
        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY" in statement
        for statement in statements
    )
    assert set(v2) == set(payload) | {"top_products", "device_usage"}
    assert v2["schema_version"] == "reports.overview.v2"
    assert {key: value for key, value in v2.items() if key in payload and key not in {
        "schema_version", "snapshot_at",
    }} == {key: value for key, value in payload.items() if key not in {
        "schema_version", "snapshot_at",
    }}
    assert v2["top_products"] == {
        "basis": "gross_settled_pos_line_sales",
        "refunds_basis": "included_original_sales_no_refund_deduction",
        "reconciles_to_gross": False,
        "limit": 5,
        "rows": [{
            "menu_item_id": str(item.id),
            "name": "Synthetic overview item",
            "product_type": "food",
            "quantity_sold": "4.250",
            "gross_line_sales_minor": 10_000,
        }],
    }
    assert v2["device_usage"] == {
        "basis": "non_cancelled_sessions_ended_in_period",
        "grouping": "device_type",
        "time_basis": "whole_session_pause_adjusted_elapsed_ms",
        "rows": [],
    }
    v2_query_count = len(statements)
    statements.clear()
    aggregate_calls = 0
    from app.services.reports.overview import ReportsOverviewService
    original_load = ReportsOverviewService.load
    csv_snapshots = []

    async def capture_csv_snapshot(service, **kwargs):
        projection = await original_load(service, **kwargs)
        csv_snapshots.append(projection.snapshot_at)
        return projection

    monkeypatch.setattr(ReportsOverviewService, "load", capture_csv_snapshot)
    exported = await client.get(
        "/api/v1/reports/overview.csv",
        params={"from_date": business_date.isoformat(), "to_date": business_date.isoformat()},
        headers=_headers(seed_owner, roles=["owner"]),
    )
    csv_rows = _assert_csv_matches_overview(exported, v2)
    assert aggregate_calls == 1 and len(csv_snapshots) == 1
    assert datetime.fromisoformat(csv_rows[0]["snapshot_at"]) == csv_snapshots[0]
    # Ordinary actors incur one extra bounded permission lookup for analytics.export;
    # the projection itself executes exactly the same queries as JSON v2.
    assert len(statements) == v2_query_count + 1
    assert sum("role_permission_overrides" in statement for statement in statements) >= 2
    assert any("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY" in statement for statement in statements)
    for forbidden in ("PRIVATE", *settled_ids, str(owner.id), str(main_station.id),
                      "expenses", "cogs_minor", "depreciation_minor", "net_profit_minor", "tax_collected"):
        assert forbidden not in exported.text

    expanded_serialized = json.dumps(v2)
    for forbidden in (
        "PRIVATE", *settled_ids, str(owner.id), str(main_station.id),
        "expenses", "cogs_minor", "depreciation_minor", "net_profit_minor",
        "tax_collected", "tender_reclassifications", "Renamed after sale",
    ):
        assert forbidden not in expanded_serialized


@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_range_branch_and_permission_contracts(
    client,
    session,
    seed_owner,
    monkeypatch,
) -> None:
    company_id = seed_owner["company"].id
    session.add_all(
        [
            RolePermissionOverride(
                id=uuid4(), company_id=company_id, role_code="analytics_only",
                module="insights_reports", allowed=True,
            ),
            RolePermissionOverride(
                id=uuid4(), company_id=company_id, role_code="analytics_only",
                module="finance", allowed=False,
            ),
            RolePermissionOverride(
                id=uuid4(), company_id=company_id, role_code="finance_only",
                module="insights_reports", allowed=False,
            ),
            RolePermissionOverride(
                id=uuid4(), company_id=company_id, role_code="finance_only",
                module="finance", allowed=True,
            ),
        ]
    )
    await session.commit()
    analytics_headers = _headers(seed_owner, roles=["analytics_only"])
    finance_headers = _headers(seed_owner, roles=["finance_only"])
    today = local_today(seed_owner["company"].timezone)

    overview = await client.get("/api/v1/reports/overview", headers=analytics_headers)
    assert overview.status_code == 200, overview.text
    default_payload = overview.json()
    assert default_payload["period_start"] == today.isoformat()
    assert default_payload["period_end"] == today.isoformat()
    assert default_payload["gross_revenue_minor"] == 0
    assert default_payload["orders_count"] == 0
    assert default_payload["sessions_started_count"] == 0
    assert default_payload["linked_customers"] == {
        "basis": "current_order_linkage",
        "distinct_linked_customers_count": 0,
        "linked_orders_count": 0,
        "unlinked_orders_count": 0,
    }
    assert default_payload["local_hour_profile"]["total_revenue_minor"] == 0
    assert default_payload["local_hour_profile"]["total_orders_count"] == 0
    finance_only_overview = await client.get(
        "/api/v1/reports/overview", headers=finance_headers
    )
    assert finance_only_overview.status_code == 403, finance_only_overview.text
    v2_params = {"schema_version": "reports.overview.v2"}
    v2_empty = await client.get(
        "/api/v1/reports/overview", params=v2_params, headers=analytics_headers,
    )
    assert v2_empty.status_code == 200, v2_empty.text
    assert v2_empty.json()["top_products"]["rows"] == []
    assert v2_empty.json()["device_usage"]["rows"] == []
    v2_denied = await client.get(
        "/api/v1/reports/overview", params=v2_params, headers=finance_headers,
    )
    assert v2_denied.status_code == 403, v2_denied.text
    explicit_v1 = await client.get(
        "/api/v1/reports/overview", params={"schema_version": "reports.overview.v1"},
        headers=analytics_headers,
    )
    assert explicit_v1.status_code == 200, explicit_v1.text
    assert {key: value for key, value in explicit_v1.json().items() if key != "snapshot_at"} == {
        key: value for key, value in default_payload.items() if key != "snapshot_at"
    }
    unsupported_version = await client.get(
        "/api/v1/reports/overview",
        params={"schema_version": "reports.overview.v3"},
        headers=analytics_headers,
    )
    assert unsupported_version.status_code == 422, unsupported_version.text
    for path, params in (
        ("daily", {"on_date": today.isoformat()}),
        ("monthly", {"yyyy_mm": today.strftime("%Y-%m")}),
        ("quarterly", {"fy": "2026-27", "q": 3}),
        ("yearly", {"fy": "2026-27"}),
        ("range", {"from_date": today.isoformat(), "to_date": today.isoformat()}),
    ):
        denied = await client.get(
            f"/api/v1/reports/{path}", params=params, headers=analytics_headers
        )
        assert denied.status_code == 403, f"{path}: {denied.text}"
        allowed = await client.get(
            f"/api/v1/reports/{path}",
            params=params,
            headers=finance_headers,
        )
        assert allowed.status_code == 200, f"{path}: {allowed.text}"

    owner = seed_owner["owner"]
    no_branch_token = issue_access_token(
        user_id=owner.id, company_id=company_id, branch_id=None,
        roles=["analytics_only"], auth_version=owner.auth_version,
    )
    no_branch = await client.get(
        "/api/v1/reports/overview",
        headers={"Authorization": f"Bearer {no_branch_token}"},
    )
    _assert_business_rule(
        no_branch,
        "a selected branch is required for this report",
    )
    v2_no_branch = await client.get(
        "/api/v1/reports/overview", params=v2_params,
        headers={"Authorization": f"Bearer {no_branch_token}"},
    )
    _assert_business_rule(v2_no_branch, "a selected branch is required for this report")

    missing_permission = await client.get(
        "/api/v1/reports/overview", headers=_headers(seed_owner, roles=["staff"])
    )
    assert missing_permission.status_code == 403
    partial = await client.get(
        "/api/v1/reports/overview",
        params={"from_date": today.isoformat()},
        headers=analytics_headers,
    )
    _assert_business_rule(
        partial,
        "from_date and to_date must either both be provided or both be omitted",
    )
    reversed_range = await client.get(
        "/api/v1/reports/overview",
        params={"from_date": today.isoformat(), "to_date": (today - timedelta(days=1)).isoformat()},
        headers=analytics_headers,
    )
    _assert_business_rule(
        reversed_range,
        "to_date must be on or after from_date",
    )
    too_wide = await client.get(
        "/api/v1/reports/overview",
        params={"from_date": today.isoformat(), "to_date": (today + timedelta(days=366)).isoformat()},
        headers=analytics_headers,
    )
    _assert_business_rule(
        too_wide,
        "report overview range cannot exceed 366 calendar dates",
    )
    v2_too_wide = await client.get(
        "/api/v1/reports/overview",
        params={**v2_params, "from_date": today.isoformat(),
                "to_date": (today + timedelta(days=366)).isoformat()},
        headers=analytics_headers,
    )
    _assert_business_rule(v2_too_wide, "report overview range cannot exceed 366 calendar dates")

    import app.services.reports.overview as overview_service

    async def forbidden_hour_profile(*args, **kwargs):
        raise AssertionError("multi-day Overview must not run the local-hour helper")

    monkeypatch.setattr(
        overview_service,
        "load_pos_local_hour_profile",
        forbidden_hour_profile,
    )
    multi_day = await client.get(
        "/api/v1/reports/overview",
        params={"from_date": today.isoformat(), "to_date": (today + timedelta(days=1)).isoformat()},
        headers=analytics_headers,
    )
    assert multi_day.status_code == 200, multi_day.text
    assert multi_day.json()["local_hour_profile"] == {
        "availability": "unavailable",
        "reason": "multi_day_range",
    }
    v2_multi_day = await client.get(
        "/api/v1/reports/overview",
        params={**v2_params, "from_date": today.isoformat(),
                "to_date": (today + timedelta(days=365)).isoformat()},
        headers=analytics_headers,
    )
    assert v2_multi_day.status_code == 200, v2_multi_day.text
    assert v2_multi_day.json()["local_hour_profile"]["availability"] == "unavailable"
    assert v2_multi_day.json()["top_products"]["rows"] == []
    assert v2_multi_day.json()["device_usage"]["rows"] == []


async def _foreign_reports_scope(
    session, opened_at: datetime, *, company=None, owner=None,
) -> dict:
    """A valid other branch/tenant, not malformed cross-tenant FK fixtures."""

    if company is None:
        company = Company(id=uuid4(), name="Foreign Reports tenant")
        session.add(company)
        await session.flush()
    branch = Branch(
        id=uuid4(), company_id=company.id, name="Foreign Reports branch",
        invoice_series_code="FR",
    )
    if owner is None:
        owner = User(
            id=uuid4(), company_id=company.id, email=f"reports-{uuid4()}@test.local",
            name="Foreign Reports actor", password_hash="unused-test-credential", status="active",
        )
    session.add_all([branch, owner])
    await session.flush()
    terminal = Terminal(
        id=uuid4(), branch_id=branch.id, name="Foreign Reports till",
        device_id=f"foreign-reports-{uuid4()}",
    )
    session.add(terminal)
    await session.flush()
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id, opened_at=opened_at,
        opening_float_minor=0, expected_minor=0, status="open",
    )
    session.add(shift)
    await session.flush()
    return {"company": company, "branch": branch, "owner": owner,
            "terminal": terminal, "shift": shift}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_v2_top_products_rank_snapshots_and_exclude_voids_and_foreign_tenant(
    client, session, seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    day = local_today(company.timezone) - timedelta(days=2)
    start_at, _ = local_date_bounds_utc(day, day, company.timezone)
    sale_at = start_at + timedelta(hours=12)
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id, opened_at=start_at - timedelta(hours=1),
        opening_float_minor=0, expected_minor=0, status="open",
    )
    category = MenuCategory(id=uuid4(), company_id=company.id, name="Reports ranking")
    session.add_all([shift, category])
    await session.flush()

    async def sell(item, amount, *, qty=Decimal("1.000"), voided_item=None):
        order = Order(
            id=uuid4(), company_id=company.id, branch_id=branch.id,
            terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
            type="takeaway", status="paid", subtotal_minor=amount, total_minor=amount,
            opened_at=sale_at - timedelta(minutes=1), closed_at=sale_at,
            invoice_issued_at=sale_at,
        )
        line = OrderLine(
            id=uuid4(), order_id=order.id, menu_item_id=item.id,
            menu_item_name_snapshot=item.name, menu_item_type_snapshot=item.type,
            qty=qty, unit_price_minor=int(Decimal(amount) / qty), line_total_minor=amount,
            discount_minor=0, tax_rate=0, taxable_value_minor=amount,
            cgst_minor=0, sgst_minor=0, igst_minor=0, cess_minor=0,
        )
        extras = ()
        if voided_item is not None:
            extras = (OrderLine(
                id=uuid4(), order_id=order.id, menu_item_id=voided_item.id,
                menu_item_name_snapshot=voided_item.name,
                menu_item_type_snapshot=voided_item.type, qty=1,
                unit_price_minor=1_000_000, line_total_minor=1_000_000,
                discount_minor=0, tax_rate=0, taxable_value_minor=1_000_000,
                cgst_minor=0, sgst_minor=0, igst_minor=0, cess_minor=0,
                voided_at=sale_at, voided_by=owner.id, void_reason="Mistaken test line",
            ),)
        await _persist_paid_order(
            session, order=order, line=line, additional_lines=extras,
            payment=Payment(
                id=uuid4(), order_id=order.id, shift_id=shift.id, method="cash",
                amount_minor=amount, tendered_minor=amount, change_minor=0, paid_at=sale_at,
            ),
        )

    items = [MenuItem(
        id=uuid4(), company_id=company.id, category_id=category.id,
        sku=f"RANK-{uuid4().hex[:12]}", name=name, type="food", base_price_minor=1_000,
        tax_rate=0, is_available=True,
    ) for name in ("Alpha", "Alpha", "Beta", "Delta", "Epsilon", "Zeta", "Voided only")]
    session.add_all(items)
    await session.flush()
    for index, amount in enumerate((1_000, 1_000, 1_000, 900, 800, 700)):
        await sell(
            items[index], amount,
            qty=Decimal("1.250") if index == 0 else Decimal("1.000"),
            voided_item=items[-1] if index == 3 else None,
        )
    # The same product can have two sold snapshots. Grouping only by its ID
    # would erase history; joining the live catalogue would rename both rows.
    items[0].name = "Renamed"
    items[0].type = "drink"
    await session.flush()
    await sell(items[0], 1_100)
    items[0].name = "Retired current name"
    items[0].is_available = False

    foreign = await _foreign_reports_scope(session, start_at - timedelta(hours=1))
    foreign_category = MenuCategory(
        id=uuid4(), company_id=foreign["company"].id, name="Foreign catalogue",
    )
    session.add(foreign_category)
    await session.flush()
    foreign_item = MenuItem(
        id=uuid4(), company_id=foreign["company"].id, category_id=foreign_category.id,
        sku=f"FOREIGN-{uuid4().hex[:12]}", name="Foreign million sale", type="food",
        base_price_minor=1_000_000, tax_rate=0, is_available=True,
    )
    session.add(foreign_item)
    await session.flush()
    foreign_order = Order(
        id=uuid4(), company_id=foreign["company"].id, branch_id=foreign["branch"].id,
        terminal_id=foreign["terminal"].id, shift_id=foreign["shift"].id,
        opened_by=foreign["owner"].id, type="takeaway", status="paid",
        subtotal_minor=1_000_000, total_minor=1_000_000,
        opened_at=sale_at - timedelta(minutes=1), closed_at=sale_at,
        invoice_issued_at=sale_at,
    )
    await _persist_paid_order(
        session, order=foreign_order,
        line=OrderLine(
            id=uuid4(), order_id=foreign_order.id, menu_item_id=foreign_item.id,
            menu_item_name_snapshot=foreign_item.name, menu_item_type_snapshot=foreign_item.type,
            qty=1, unit_price_minor=1_000_000, line_total_minor=1_000_000,
            discount_minor=0, tax_rate=0, taxable_value_minor=1_000_000,
            cgst_minor=0, sgst_minor=0, igst_minor=0, cess_minor=0,
        ),
        payment=Payment(
            id=uuid4(), order_id=foreign_order.id, shift_id=foreign["shift"].id,
            method="cash", amount_minor=1_000_000, tendered_minor=1_000_000,
            change_minor=0, paid_at=sale_at,
        ),
    )
    await session.commit()

    params = {"schema_version": "reports.overview.v2", "from_date": day.isoformat(),
              "to_date": day.isoformat()}
    response = await client.get(
        "/api/v1/reports/overview", params=params, headers=_headers(seed_owner, roles=["owner"]),
    )
    assert response.status_code == 200, response.text
    rows = response.json()["top_products"]["rows"]
    assert [row["name"] for row in rows] == ["Renamed", "Alpha", "Alpha", "Beta", "Delta"]
    assert [row["gross_line_sales_minor"] for row in rows] == [1_100, 1_000, 1_000, 1_000, 900]
    assert [row["menu_item_id"] for row in rows[1:3]] == sorted(str(item.id) for item in items[:2])
    assert rows[0]["menu_item_id"] == str(items[0].id)
    assert rows[0]["product_type"] == "drink"
    historical = next(row for row in rows[1:3] if row["menu_item_id"] == str(items[0].id))
    assert historical["product_type"] == "food"
    assert historical["quantity_sold"] == "1.250"
    assert response.json()["gross_revenue_minor"] == 6_500
    repeated = await client.get(
        "/api/v1/reports/overview", params=params, headers=_headers(seed_owner, roles=["owner"]),
    )
    assert repeated.status_code == 200, repeated.text
    assert repeated.json()["top_products"]["rows"] == rows
    exported = await client.get(
        "/api/v1/reports/overview.csv",
        params={"from_date": day.isoformat(), "to_date": day.isoformat()},
        headers=_headers(seed_owner, roles=["owner"]),
    )
    _assert_csv_matches_overview(exported, response.json())
    for excluded in ("Epsilon", "Zeta", "Voided only", "Foreign million sale", "Retired current name"):
        assert excluded not in exported.text


@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_v2_device_usage_exact_play_unknowns_transfers_and_scope(
    client, session, seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    day = local_today(company.timezone) - timedelta(days=2)
    start_at, end_at = local_date_bounds_utc(day, day, company.timezone)
    noon = start_at + timedelta(hours=12)
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id, opened_at=start_at - timedelta(days=1),
        opening_float_minor=0, expected_minor=0, status="open",
    )
    stations = [Station(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        code=f"DEV-{uuid4().hex[:6]}", name="PRIVATE DEVICE NAME", type=kind,
        rate_per_hour_minor=987_654, is_active=True,
    ) for kind in ("ps5", "ps5", "vr", "hookah", "streaming")]
    session.add_all([shift, *stations])
    await session.flush()

    def ended(station, started, ended_at, *, pause_ms=0, pause_minutes=0, status="ended"):
        return GamingSession(
            id=uuid4(), company_id=company.id, station_id=station.id,
            shift_id=shift.id, opened_by=owner.id, start_at=started, end_at=ended_at,
            rate_per_hour_minor=987_654, status=status,
            paused_duration_ms=pause_ms, paused_minutes=pause_minutes,
            amount_minor=9_876_543 if status == "ended" else None,
            # Billing duration intentionally differs from exact elapsed play.
            billable_minutes=99 if status == "ended" else None,
        )

    crossing_midnight = ended(
        stations[0], start_at - timedelta(milliseconds=500), start_at, pause_ms=123,
    )
    transferred = ended(
        stations[0], noon, noon + timedelta(seconds=90, microseconds=123_456),
        pause_ms=60_001, pause_minutes=1,
    )
    session.add_all([crossing_midnight, transferred])
    await session.flush()
    # Same-type station transfer preserves type totals. No per-station play
    # attribution can be reconstructed from this final station_id.
    transferred.station_id = stations[1].id
    stations[0].is_active = False
    stations[1].is_active = False
    rows = [
        # A type with both valid and unknown history has no trustworthy total.
        ended(stations[2], noon, noon + timedelta(seconds=1), pause_ms=1_001),
        ended(stations[2], noon, noon),
        ended(stations[3], noon + timedelta(seconds=1), noon),
        ended(stations[4], noon, noon),
        # None of these rows belongs to the ended-in-range cohort.
        ended(stations[0], start_at - timedelta(seconds=2), start_at - timedelta(microseconds=1)),
        ended(stations[0], end_at - timedelta(seconds=1), end_at),
        ended(stations[0], noon, None),
        ended(stations[0], noon, None, status="active"),
        ended(stations[0], noon, noon + timedelta(hours=1), status="cancelled"),
    ]
    rows.extend(ended(stations[0], noon, noon + timedelta(seconds=1)) for _ in range(20))
    session.add_all(rows)
    for foreign in (
        await _foreign_reports_scope(
            session, start_at - timedelta(days=1), company=company, owner=owner,
        ),
        await _foreign_reports_scope(session, start_at - timedelta(days=1)),
    ):
        station = Station(
            id=uuid4(), company_id=foreign["company"].id, branch_id=foreign["branch"].id,
            code=f"EX-{uuid4().hex[:6]}", name="PRIVATE FOREIGN DEVICE", type="ps5",
            rate_per_hour_minor=100, is_active=True,
        )
        session.add(station)
        await session.flush()
        session.add(GamingSession(
            id=uuid4(), company_id=foreign["company"].id, station_id=station.id,
            shift_id=foreign["shift"].id, opened_by=foreign["owner"].id,
            start_at=noon - timedelta(hours=10), end_at=noon,
            rate_per_hour_minor=100, status="ended", amount_minor=100,
            billable_minutes=600,
        ))
    await session.commit()

    response = await client.get(
        "/api/v1/reports/overview",
        params={"schema_version": "reports.overview.v2", "from_date": day.isoformat(),
                "to_date": day.isoformat()},
        headers=_headers(seed_owner, roles=["owner"]),
    )
    assert response.status_code == 200, response.text
    usage = response.json()["device_usage"]
    assert usage == {
        "basis": "non_cancelled_sessions_ended_in_period",
        "grouping": "device_type",
        "time_basis": "whole_session_pause_adjusted_elapsed_ms",
        "rows": [
            {"device_type": "ps5", "completed_sessions_count": 22,
             "play_elapsed_ms": 50_499, "unknown_play_sessions_count": 0},
            {"device_type": "vr", "completed_sessions_count": 2,
             "play_elapsed_ms": None, "unknown_play_sessions_count": 1},
            {"device_type": "hookah", "completed_sessions_count": 1,
             "play_elapsed_ms": None, "unknown_play_sessions_count": 1},
            {"device_type": "streaming", "completed_sessions_count": 1,
             "play_elapsed_ms": 0, "unknown_play_sessions_count": 0},
        ],
    }
    exported = await client.get(
        "/api/v1/reports/overview.csv",
        params={"from_date": day.isoformat(), "to_date": day.isoformat()},
        headers=_headers(seed_owner, roles=["owner"]),
    )
    csv_rows = _assert_csv_matches_overview(exported, response.json())
    elapsed_rows = {row["label"]: row for row in csv_rows if row["metric"] == "play_elapsed_ms"}
    assert elapsed_rows["'vr"]["value"] == "" and elapsed_rows["'vr"]["availability"] == "unknown"
    assert elapsed_rows["'streaming"]["value"] == "0" and elapsed_rows["'streaming"]["availability"] == "available"
    assert "PRIVATE" not in exported.text and str(transferred.id) not in exported.text

    serialized = json.dumps(response.json())
    for forbidden in ("PRIVATE", "station_id", "amount_minor", "billable_minutes",
                      str(transferred.id), str(stations[1].id)):
        assert forbidden not in serialized


@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_csv_requires_read_and_export_before_loading(
    client, seed_owner, monkeypatch,
) -> None:
    from app.core.permissions import ROLE_PERMISSIONS
    from app.services.reports.overview import ReportsOverviewService

    grants = {
        "csv_read_only": {"analytics.read"},
        "csv_export_only": {"analytics.export"},
        "csv_finance_only": {"finance.read"},
        "csv_both": {"analytics.read", "analytics.export"},
    }
    for role, permissions in grants.items():
        monkeypatch.setitem(ROLE_PERMISSIONS, role, permissions)

    async def forbidden_load(*args, **kwargs):
        raise AssertionError("Denied CSV requests must not load any Overview data")

    with monkeypatch.context() as denied_scope:
        denied_scope.setattr(ReportsOverviewService, "load", forbidden_load)
        for role in ("csv_read_only", "csv_export_only", "csv_finance_only", "staff"):
            denied = await client.get(
                "/api/v1/reports/overview.csv", headers=_headers(seed_owner, roles=[role]),
            )
            assert denied.status_code == 403, (role, denied.text)
        unauthenticated = await client.get("/api/v1/reports/overview.csv")
        assert unauthenticated.status_code == 401

    headers = _headers(seed_owner, roles=["csv_both"])
    exported = await client.get("/api/v1/reports/overview.csv", headers=headers)
    overview = await client.get(
        "/api/v1/reports/overview", params={"schema_version": "reports.overview.v2"},
        headers=headers,
    )
    assert overview.status_code == 200, overview.text
    rows = _assert_csv_matches_overview(exported, overview.json())
    assert rows[0]["period_start"] == local_today(seed_owner["company"].timezone).isoformat()
    assert rows[0]["period_end"] == rows[0]["period_start"]
    # A successful export grants no P&L authority.
    assert (await client.get(
        "/api/v1/reports/daily", params={"on_date": rows[0]["period_start"]}, headers=headers,
    )).status_code == 403
    read_only = await client.get(
        "/api/v1/reports/overview", headers=_headers(seed_owner, roles=["csv_read_only"]),
    )
    assert read_only.status_code == 200 and read_only.json()["schema_version"] == "reports.overview.v1"

@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_csv_preserves_branch_and_date_admission(
    client, seed_owner, monkeypatch,
) -> None:
    from app.services.reports.overview import ReportsOverviewService

    headers = _headers(seed_owner, roles=["owner"])
    today = local_today(seed_owner["company"].timezone)

    async def forbidden_load(*args, **kwargs):
        raise AssertionError("Invalid branch/date requests must not load Overview data")

    with monkeypatch.context() as invalid_scope:
        invalid_scope.setattr(ReportsOverviewService, "load", forbidden_load)
        for params, message in (
            ({"from_date": today.isoformat()}, "from_date and to_date must either both be provided or both be omitted"),
            ({"to_date": today.isoformat()}, "from_date and to_date must either both be provided or both be omitted"),
            ({"from_date": today.isoformat(), "to_date": (today - timedelta(days=1)).isoformat()}, "to_date must be on or after from_date"),
            ({"from_date": today.isoformat(), "to_date": (today + timedelta(days=366)).isoformat()}, "report overview range cannot exceed 366 calendar dates"),
        ):
            response = await client.get("/api/v1/reports/overview.csv", params=params, headers=headers)
            _assert_business_rule(response, message)
        malformed = await client.get(
            "/api/v1/reports/overview.csv", params={"from_date": "not-a-date"}, headers=headers,
        )
        assert malformed.status_code == 422
        owner = seed_owner["owner"]
        token = issue_access_token(
            user_id=owner.id, company_id=seed_owner["company"].id, branch_id=None,
            roles=["owner"], auth_version=owner.auth_version,
        )
        no_branch = await client.get(
            "/api/v1/reports/overview.csv", headers={"Authorization": f"Bearer {token}"},
        )
        _assert_business_rule(no_branch, "a selected branch is required for this report")

    params = {"from_date": today.isoformat(), "to_date": (today + timedelta(days=365)).isoformat()}
    exported = await client.get("/api/v1/reports/overview.csv", params=params, headers=headers)
    overview = await client.get(
        "/api/v1/reports/overview", params={**params, "schema_version": "reports.overview.v2"},
        headers=headers,
    )
    assert overview.status_code == 200, overview.text
    rows = _assert_csv_matches_overview(exported, overview.json())
    hours = [row for row in rows if row["section"] == "local_hour_profile"]
    assert {row["availability"] for row in hours} == {"unavailable"}
    assert {row["row_key"] for row in hours} == {"summary"}
    assert next(row for row in hours if row["metric"] == "total_revenue_minor")["value"] == ""

@pytest.mark.integration
@pytest.mark.asyncio
async def test_overview_csv_escapes_editable_text_without_changing_numeric_values(
    client, session, seed_owner,
) -> None:
    company, branch = seed_owner["company"], seed_owner["branch"]
    terminal, owner = seed_owner["terminal"], seed_owner["owner"]
    day = local_today(company.timezone) - timedelta(days=2)
    start_at, _ = local_date_bounds_utc(day, day, company.timezone)
    sale_at = start_at + timedelta(hours=12)
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id, terminal_id=terminal.id,
        opened_by=owner.id, opened_at=start_at, opening_float_minor=0,
        expected_minor=0, status="open",
    )
    category = MenuCategory(id=uuid4(), company_id=company.id, name="CSV escaping")
    session.add_all([shift, category])
    await session.flush()
    names = ('=SUM(1,2)\r\n"quoted" café', '+SUM(1,2)', '-2+3', '@SUM(A1:A2)', '\t=1+1')
    for name in names:
        item = MenuItem(
            id=uuid4(), company_id=company.id, category_id=category.id,
            sku=f"CSV-{uuid4().hex[:12]}", name=name, type="food",
            base_price_minor=10_000, tax_rate=0, is_available=True,
        )
        session.add(item)
        await session.flush()
        order = Order(
            id=uuid4(), company_id=company.id, branch_id=branch.id, terminal_id=terminal.id,
            shift_id=shift.id, opened_by=owner.id, type="takeaway", status="paid",
            subtotal_minor=1_250, total_minor=1_250, opened_at=sale_at - timedelta(minutes=1),
            closed_at=sale_at, invoice_issued_at=sale_at,
        )
        await _persist_paid_order(
            session, order=order,
            line=OrderLine(
                id=uuid4(), order_id=order.id, menu_item_id=item.id,
                menu_item_name_snapshot=name, menu_item_type_snapshot="food",
                qty=Decimal("0.125"), unit_price_minor=10_000, line_total_minor=1_250,
                discount_minor=0, tax_rate=0, taxable_value_minor=1_250,
                cgst_minor=0, sgst_minor=0, igst_minor=0, cess_minor=0,
            ),
            payment=Payment(
                id=uuid4(), order_id=order.id, shift_id=shift.id, method="cash",
                amount_minor=1_250, tendered_minor=1_250, change_minor=0, paid_at=sale_at,
            ),
        )
    await session.commit()
    params = {"from_date": day.isoformat(), "to_date": day.isoformat()}
    headers = _headers(seed_owner, roles=["owner"])
    overview = await client.get(
        "/api/v1/reports/overview", params={**params, "schema_version": "reports.overview.v2"},
        headers=headers,
    )
    assert overview.status_code == 200, overview.text
    exported = await client.get("/api/v1/reports/overview.csv", params=params, headers=headers)
    rows = _assert_csv_matches_overview(exported, overview.json())
    products = [row for row in rows if row["section"] == "top_products" and row["row_key"] != "summary"]
    assert {row["label"] for row in products} == {"'" + name for name in names}
    assert {row["value"] for row in products if row["metric"] == "quantity_sold"} == {"0.125"}
    assert {row["value"] for row in products if row["metric"] == "gross_line_sales_minor"} == {"1250"}
    assert {row["value"] for row in products if row["metric"] == "product_type"} == {"'food"}

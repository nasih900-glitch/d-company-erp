"""PostgreSQL proof for GRN -> FIFO sale -> P&L -> AP settlement.

This test deliberately crosses the real HTTP write paths. It proves the
classic valuation case of 10 units @ 200 paise plus 10 @ 500, followed by a
12-unit sale: FIFO COGS is 3,000 and remaining Inventory is 4,000. Paying the
7,000 supplier liability then clears Accounts Payable exactly once.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
import pytest_asyncio
from sqlalchemy import delete, func, select, text
from sqlalchemy.exc import DBAPIError

from app.models import (
    GRN,
    Account,
    Batch,
    IdempotencyKey,
    Ingredient,
    JournalEntry,
    MenuCategory,
    MenuItem,
    Recipe,
    RecipeLine,
    Shift,
    Supplier,
    SupplierPayment,
)
from app.services.accounting.accounts import (
    ACCOUNTS_PAYABLE,
    BANK,
    CASH,
    INVENTORY,
)


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.integration
@pytest.mark.asyncio
async def test_grn_fifo_sale_finance_and_supplier_settlement_are_exact_and_idempotent(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    owner_id = owner.id
    company.gst_registration_type = "unregistered"
    branch.state_code = "32"
    branch.code = f"F{uuid4().hex[:8].upper()}"

    for definition in (CASH, BANK, INVENTORY, ACCOUNTS_PAYABLE):
        session.add(
            Account(
                id=uuid4(),
                company_id=company.id,
                **definition.seed_dict(),
            )
        )
    category = MenuCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Purchase accounting {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=company.id,
        category_id=category.id,
        sku=f"FIFO-{uuid4().hex[:10]}",
        name="FIFO proof item",
        type="drink",
        base_price_minor=1_000,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=company.id,
        sku=f"ING-{uuid4().hex[:10]}",
        name="FIFO proof ingredient",
        base_unit="unit",
        current_qty=0,
        avg_cost_minor=0,
        reorder_threshold=0,
        reorder_qty=0,
    )
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="One ingredient per sold item",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=0,
    )
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=1,
        wastage_pct=0,
    )
    supplier = Supplier(
        id=uuid4(),
        company_id=company.id,
        name=f"FIFO supplier {uuid4().hex[:8]}",
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC) - timedelta(minutes=10),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    # These legacy mappers intentionally expose few ORM relationships, so
    # make FK insertion order explicit instead of relying on unit-of-work
    # dependency discovery from bare UUID columns.
    session.add_all([category, ingredient, supplier, shift])
    await session.flush()
    session.add(item)
    await session.flush()
    session.add(recipe)
    await session.flush()
    session.add(recipe_line)
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": owner.email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": f"Bearer {login.json()['access_token']}",
        "X-Terminal-Id": str(terminal.id),
    }

    now = datetime.now(UTC)
    overprecision_key = f"grn-overprecision-{uuid4()}"
    rejected_overprecision = await client.post(
        "/api/v1/inventory/grn",
        headers={**headers, "Idempotency-Key": overprecision_key},
        json={
            "branch_id": str(branch.id),
            "supplier_id": str(supplier.id),
            "received_at": (now - timedelta(minutes=7)).isoformat(),
            "lines": [
                {
                    "ingredient_id": str(ingredient.id),
                    "qty": "1.00001",
                    "unit_cost_minor": 1_000,
                    "lot_code": "MUST-NOT-ROUND",
                }
            ],
        },
    )
    assert rejected_overprecision.status_code == 422
    assert "decimal places" in rejected_overprecision.text
    assert (
        await session.execute(
            select(func.count(GRN.id)).where(
                GRN.idempotency_key == overprecision_key
            )
        )
    ).scalar_one() == 0

    rejected_variance_key = f"grn-variance-{uuid4()}"
    rejected_variance = await client.post(
        "/api/v1/inventory/grn",
        headers={**headers, "Idempotency-Key": rejected_variance_key},
        json={
            "branch_id": str(branch.id),
            "supplier_id": str(supplier.id),
            "supplier_invoice_no": "UNALLOCATED-VARIANCE",
            "supplier_invoice_amount_minor": 999,
            "received_at": (now - timedelta(minutes=6)).isoformat(),
            "lines": [
                {
                    "ingredient_id": str(ingredient.id),
                    "qty": 1,
                    "unit_cost_minor": 1_000,
                    "lot_code": "MUST-NOT-POST",
                }
            ],
        },
    )
    assert rejected_variance.status_code == 422
    assert "does not match the capitalised GRN line total" in rejected_variance.text
    assert (
        await session.execute(
            select(func.count(GRN.id)).where(
                GRN.idempotency_key == rejected_variance_key
            )
        )
    ).scalar_one() == 0

    grn_responses: list[dict] = []
    grn_specs = (
        ("A", 200, 2_000, now - timedelta(minutes=5)),
        ("B", 500, 5_000, now - timedelta(minutes=4)),
    )
    first_grn_key = f"grn-fifo-{uuid4()}"
    first_grn_payload: dict | None = None
    for index, (lot, unit_cost, invoice_total, received_at) in enumerate(grn_specs):
        payload = {
            "branch_id": str(branch.id),
            "supplier_id": str(supplier.id),
            "supplier_invoice_no": f"FIFO-{lot}-{uuid4().hex[:6]}",
            "supplier_invoice_amount_minor": invoice_total,
            "received_at": received_at.isoformat(),
            "lines": [
                {
                    "ingredient_id": str(ingredient.id),
                    "qty": 10,
                    "unit_cost_minor": unit_cost,
                    "lot_code": f"FIFO-{lot}",
                }
            ],
        }
        key = first_grn_key if index == 0 else f"grn-fifo-{uuid4()}"
        response = await client.post(
            "/api/v1/inventory/grn",
            headers={**headers, "Idempotency-Key": key},
            json=payload,
        )
        assert response.status_code == 201, response.text
        body = response.json()
        assert body["accounting_status"] == "posted"
        assert body["journal_entry_id"]
        assert body["total_minor"] == invoice_total
        grn_responses.append(body)
        if index == 0:
            first_grn_payload = payload

    assert first_grn_payload is not None
    replay = await client.post(
        "/api/v1/inventory/grn",
        headers={**headers, "Idempotency-Key": first_grn_key},
        json=first_grn_payload,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == grn_responses[0]
    await session.execute(
        delete(IdempotencyKey).where(IdempotencyKey.key == first_grn_key)
    )
    await session.commit()
    durable_replay = await client.post(
        "/api/v1/inventory/grn",
        headers={**headers, "Idempotency-Key": first_grn_key},
        json=first_grn_payload,
    )
    assert durable_replay.status_code == 201, durable_replay.text
    assert durable_replay.json() == grn_responses[0]

    order_key = f"order-fifo-{uuid4()}"
    created = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": order_key},
        json={
            "type": "takeaway",
            "shift_id": str(shift.id),
            "lines": [
                {
                    "client_line_id": str(uuid4()),
                    "menu_item_id": str(item.id),
                    "qty": 12,
                }
            ],
        },
    )
    assert created.status_code == 201, created.text
    order = created.json()
    assert order["total_minor"] == 12_000

    paid = await client.post(
        f"/api/v1/pos/orders/{order['id']}/payments",
        headers={
            **headers,
            "Idempotency-Key": f"payment-fifo-{uuid4()}",
        },
        json={
            "method": "cash",
            "amount_minor": 12_000,
            "tendered_minor": 12_000,
            "expected_order_total_minor": 12_000,
            "expected_due_minor": 12_000,
        },
    )
    assert paid.status_code == 201, paid.text

    batches = (
        await session.execute(
            select(Batch)
            .where(
                Batch.ingredient_id == ingredient.id,
                Batch.branch_id == branch.id,
                Batch.lot_code.in_(("FIFO-A", "FIFO-B")),
            )
            .order_by(Batch.received_at, Batch.id)
        )
    ).scalars().all()
    assert [float(batch.qty_on_hand) for batch in batches] == [0.0, 8.0]

    supplier_payment_key = f"supplier-payment-{uuid4()}"
    supplier_payment_payload = {
        "branch_id": str(branch.id),
        "supplier_id": str(supplier.id),
        "grn_id": grn_responses[0]["id"],
        "amount_minor": 2_000,
        "method": "cash",
        "paid_at": now.isoformat(),
        "payment_reference": f"CASH-{uuid4().hex[:10]}",
        "note": "FIFO integration settlement one",
    }
    settled_one = await client.post(
        "/api/v1/finance/supplier-payments",
        headers={**headers, "Idempotency-Key": supplier_payment_key},
        json=supplier_payment_payload,
    )
    assert settled_one.status_code == 201, settled_one.text
    replay_settlement = await client.post(
        "/api/v1/finance/supplier-payments",
        headers={**headers, "Idempotency-Key": supplier_payment_key},
        json=supplier_payment_payload,
    )
    assert replay_settlement.status_code == 201, replay_settlement.text
    assert replay_settlement.json() == settled_one.json()
    await session.execute(
        delete(IdempotencyKey).where(IdempotencyKey.key == supplier_payment_key)
    )
    await session.commit()
    durable_settlement_replay = await client.post(
        "/api/v1/finance/supplier-payments",
        headers={**headers, "Idempotency-Key": supplier_payment_key},
        json=supplier_payment_payload,
    )
    assert durable_settlement_replay.status_code == 201
    assert durable_settlement_replay.json() == settled_one.json()

    settled_two = await client.post(
        "/api/v1/finance/supplier-payments",
        headers={**headers, "Idempotency-Key": f"supplier-payment-{uuid4()}"},
        json={
            **supplier_payment_payload,
            "grn_id": grn_responses[1]["id"],
            "amount_minor": 5_000,
            "payment_reference": f"CASH-{uuid4().hex[:10]}",
        },
    )
    assert settled_two.status_code == 201, settled_two.text

    assert (
        await session.execute(
            select(func.count(SupplierPayment.id)).where(
                SupplierPayment.company_id == company.id
            )
        )
    ).scalar_one() == 2
    assert (
        await session.execute(
            select(func.count(JournalEntry.id)).where(
                JournalEntry.company_id == company.id,
                JournalEntry.ref_type.in_(("grn_receipt", "supplier_payment")),
            )
        )
    ).scalar_one() == 4

    day = now.astimezone(ZoneInfo(company.timezone)).date().isoformat()
    ledger_response = await client.get(
        "/api/v1/accounting/general-ledger",
        headers=headers,
        params={"from_date": day, "to_date": day, "limit": 2000},
    )
    assert ledger_response.status_code == 200, ledger_response.text
    ledger = ledger_response.json()

    def balance(code: str) -> int:
        return sum(
            int(line["debit_minor"]) - int(line["credit_minor"])
            for line in ledger
            if line["account_code"] == code
        )

    assert balance("1200") == 4_000  # Inventory: 7,000 receipts - 3,000 FIFO COGS
    assert balance("2000") == 0  # AP: 7,000 receipts - 7,000 settlements
    assert balance("1000") == 5_000  # Cash: 12,000 sale - 7,000 supplier payment
    assert balance("5000") == 3_000
    assert balance("4000") == -12_000

    trial = await client.get(
        "/api/v1/accounting/trial-balance",
        headers=headers,
        params={"as_of": day},
    )
    assert trial.status_code == 200, trial.text
    assert trial.json()["is_balanced"] is True

    balance_sheet = await client.get(
        "/api/v1/accounting/balance-sheet",
        headers=headers,
        params={"as_of": day},
    )
    assert balance_sheet.status_code == 200, balance_sheet.text
    sheet = balance_sheet.json()
    assert sheet["is_balanced"] is True
    assert sheet["assets"]["total_minor"] == 9_000
    assert sheet["liabilities"]["total_minor"] == 0
    assert sheet["equity"]["total_minor"] == 9_000

    pnl = await client.get(
        "/api/v1/finance/pnl",
        headers=headers,
        params={"period_start": day, "period_end": day},
    )
    assert pnl.status_code == 200, pnl.text
    assert pnl.json()["revenue_minor"] == 12_000
    assert pnl.json()["cogs_minor"] == 3_000
    assert pnl.json()["net_profit_minor"] == 9_000

    report = await client.get(
        "/api/v1/reports/range",
        headers=headers,
        params={"from_date": day, "to_date": day},
    )
    assert report.status_code == 200, report.text
    assert report.json()["cogs_minor"] == 3_000
    assert report.json()["net_profit_minor"] == 9_000

    # A physical write-off must also reduce the Inventory asset and recognise
    # an expense. A later positive count correction restores the batch asset
    # and credits the same expense account; neither action touches supplier AP.
    wasted = await client.post(
        "/api/v1/inventory/adjustments",
        headers={**headers, "Idempotency-Key": f"waste-fifo-{uuid4()}"},
        json={
            "ingredient_id": str(ingredient.id),
            "branch_id": str(branch.id),
            "qty_delta": -1,
            "type": "waste",
            "note": "Integration write-off proof",
        },
    )
    assert wasted.status_code == 201, wasted.text
    after_waste = await client.get(
        "/api/v1/accounting/general-ledger",
        headers=headers,
        params={"from_date": day, "to_date": day, "limit": 2000},
    )
    assert after_waste.status_code == 200, after_waste.text
    waste_ledger = after_waste.json()

    def waste_balance(code: str) -> int:
        return sum(
            int(line["debit_minor"]) - int(line["credit_minor"])
            for line in waste_ledger
            if line["account_code"] == code
        )

    assert waste_balance("1200") == 3_500
    assert waste_balance("5900") == 500
    assert waste_balance("2000") == 0

    corrected = await client.post(
        "/api/v1/inventory/adjustments",
        headers={**headers, "Idempotency-Key": f"count-fifo-{uuid4()}"},
        json={
            "ingredient_id": str(ingredient.id),
            "branch_id": str(branch.id),
            "qty_delta": 1,
            "type": "adjustment",
            "note": "Integration count correction proof",
        },
    )
    assert corrected.status_code == 201, corrected.text
    after_correction = await client.get(
        "/api/v1/accounting/general-ledger",
        headers=headers,
        params={"from_date": day, "to_date": day, "limit": 2000},
    )
    assert after_correction.status_code == 200, after_correction.text
    corrected_ledger = after_correction.json()

    def corrected_balance(code: str) -> int:
        return sum(
            int(line["debit_minor"]) - int(line["credit_minor"])
            for line in corrected_ledger
            if line["account_code"] == code
        )

    assert corrected_balance("1200") == 4_000
    assert corrected_balance("5900") == 0
    assert corrected_balance("2000") == 0

    # Two different actions race against one ₹10.00 AP balance. The GRN lock
    # serializes the outstanding-balance read so only one ₹7.00 payment lands.
    concurrent_grn = await client.post(
        "/api/v1/inventory/grn",
        headers={**headers, "Idempotency-Key": f"grn-race-{uuid4()}"},
        json={
            "branch_id": str(branch.id),
            "supplier_id": str(supplier.id),
            "supplier_invoice_no": f"RACE-{uuid4().hex[:8]}",
            "supplier_invoice_amount_minor": 1_000,
            "received_at": (now - timedelta(minutes=1)).isoformat(),
            "lines": [
                {
                    "ingredient_id": str(ingredient.id),
                    "qty": 1,
                    "unit_cost_minor": 1_000,
                    "lot_code": f"RACE-{uuid4().hex[:6]}",
                }
            ],
        },
    )
    assert concurrent_grn.status_code == 201, concurrent_grn.text

    async def pay_racing_action(label: str):
        return await client.post(
            "/api/v1/finance/supplier-payments",
            headers={**headers, "Idempotency-Key": f"supplier-race-{uuid4()}"},
            json={
                "branch_id": str(branch.id),
                "supplier_id": str(supplier.id),
                "grn_id": concurrent_grn.json()["id"],
                "amount_minor": 700,
                "method": "cash",
                "paid_at": now.isoformat(),
                "payment_reference": f"RACE-{label}-{uuid4().hex[:6]}",
            },
        )

    race_results = await asyncio.gather(
        pay_racing_action("A"),
        pay_racing_action("B"),
    )
    assert sorted(response.status_code for response in race_results) == [201, 422]
    rejected = next(response for response in race_results if response.status_code == 422)
    assert "exceeds the outstanding Accounts Payable balance" in rejected.text
    raced_paid_minor = (
        await session.execute(
            select(func.coalesce(func.sum(SupplierPayment.amount_minor), 0)).where(
                SupplierPayment.company_id == company.id,
                SupplierPayment.grn_id == concurrent_grn.json()["id"],
                SupplierPayment.voided_at.is_(None),
            )
        )
    ).scalar_one()
    assert int(raced_paid_minor) == 700

    successful_race = next(
        response for response in race_results if response.status_code == 201
    )
    raced_payment = successful_race.json()
    void_payload = {"reason": "Duplicate supplier settlement test"}
    voided = await client.post(
        f"/api/v1/finance/supplier-payments/{raced_payment['id']}/void",
        headers=headers,
        json=void_payload,
    )
    assert voided.status_code == 200, voided.text
    assert voided.json()["is_voided"] is True
    assert voided.json()["void_reason"] == void_payload["reason"]

    void_replay = await client.post(
        f"/api/v1/finance/supplier-payments/{raced_payment['id']}/void",
        headers=headers,
        json=void_payload,
    )
    assert void_replay.status_code == 200, void_replay.text
    assert void_replay.json() == voided.json()

    conflicting_void = await client.post(
        f"/api/v1/finance/supplier-payments/{raced_payment['id']}/void",
        headers=headers,
        json={"reason": "A different owner correction"},
    )
    assert conflicting_void.status_code == 422
    assert "already voided with a different reason" in conflicting_void.text

    sources = (
        await session.execute(
            select(SupplierPayment, JournalEntry)
            .join(
                JournalEntry,
                JournalEntry.id == SupplierPayment.journal_entry_id,
            )
            .where(SupplierPayment.id == raced_payment["id"])
            .execution_options(populate_existing=True)
        )
    ).one()
    source, journal = sources
    assert source.voided_at is not None
    assert source.voided_by == owner.id
    assert source.void_reason == void_payload["reason"]
    assert journal.voided_at == source.voided_at
    assert journal.voided_by == source.voided_by
    assert journal.void_reason == source.void_reason

    # A posted inventory receipt is one immutable financial source. Native SQL
    # cannot alter/delete its header or lines, append a later line, or void its
    # Accounts-Payable journal independently.
    posted_grn_id = grn_responses[0]["id"]
    posted_grn_journal_id = grn_responses[0]["journal_entry_id"]
    grn_line = (
        await session.execute(
            text(
                "SELECT id, ingredient_id, batch_id FROM grn_lines "
                "WHERE grn_id = :grn_id ORDER BY id LIMIT 1"
            ),
            {"grn_id": posted_grn_id},
        )
    ).one()
    with pytest.raises(DBAPIError, match="posted GRN financial/provenance"):
        await session.execute(
            text(
                "UPDATE grns SET supplier_invoice_no = 'NATIVE-TAMPER' "
                "WHERE id = :grn_id"
            ),
            {"grn_id": posted_grn_id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="posted GRNs are immutable"):
        await session.execute(
            text("DELETE FROM grns WHERE id = :grn_id"),
            {"grn_id": posted_grn_id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="posted GRN lines are immutable"):
        await session.execute(
            text(
                "UPDATE grn_lines SET cost_per_unit_minor = "
                "cost_per_unit_minor + 1 WHERE id = :line_id"
            ),
            {"line_id": grn_line.id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="posted GRN lines are immutable"):
        await session.execute(
            text("DELETE FROM grn_lines WHERE id = :line_id"),
            {"line_id": grn_line.id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="posted GRN lines are immutable"):
        await session.execute(
            text(
                "INSERT INTO grn_lines "
                "(id, grn_id, ingredient_id, batch_id, qty_received, "
                "cost_per_unit_minor) VALUES "
                "(:id, :grn_id, :ingredient_id, :batch_id, 1, 0)"
            ),
            {
                "id": uuid4(),
                "grn_id": posted_grn_id,
                "ingredient_id": grn_line.ingredient_id,
                "batch_id": grn_line.batch_id,
            },
        )
    await session.rollback()

    await session.execute(
        text(
            "UPDATE journal_entries SET voided_at = :voided_at, "
            "voided_by = :voided_by, void_reason = :reason "
            "WHERE id = :journal_id"
        ),
        {
            "voided_at": datetime.now(UTC),
            "voided_by": owner_id,
            "reason": "Independent receipt-journal void proof",
            "journal_id": posted_grn_journal_id,
        },
    )
    with pytest.raises(DBAPIError, match="must remain an exact, active"):
        await session.commit()
    await session.rollback()

    # 0050 makes the source contract authoritative below the ORM/API layer.
    # Native SQL cannot rewrite/delete a settlement or void only one side of
    # the exact SupplierPayment <-> JournalEntry pair.
    active_payment_id = settled_one.json()["id"]
    with pytest.raises(DBAPIError, match="financial/provenance fields are immutable"):
        await session.execute(
            text(
                "UPDATE supplier_payments SET amount_minor = amount_minor + 1 "
                "WHERE id = :payment_id"
            ),
            {"payment_id": active_payment_id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="immutable and cannot be deleted"):
        await session.execute(
            text("DELETE FROM supplier_payments WHERE id = :payment_id"),
            {"payment_id": active_payment_id},
        )
    await session.rollback()

    await session.execute(
        text(
            "UPDATE supplier_payments SET voided_at = :voided_at, "
            "voided_by = :voided_by, void_reason = :reason "
            "WHERE id = :payment_id"
        ),
        {
            "voided_at": datetime.now(UTC),
            "voided_by": owner_id,
            "reason": "Unpaired native-SQL void proof",
            "payment_id": active_payment_id,
        },
    )
    with pytest.raises(DBAPIError, match="must remain an exact paired source"):
        await session.commit()
    await session.rollback()

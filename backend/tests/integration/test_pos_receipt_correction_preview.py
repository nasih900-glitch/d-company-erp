"""Owned PostgreSQL proof: source review never changes a paid receipt or its shift."""

from __future__ import annotations

from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import hash_password, issue_access_token
from app.models import Branch, Company, Role, User, UserRole
from app.models.access_control import RolePermissionOverride
from tests.integration.test_gaming_session_addons import _seed_live_session

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]


def token(seed, role):
    return {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=seed["owner"].id,
            company_id=seed["company"].id,
            branch_id=seed["branch"].id,
            roles=[role],
            auth_version=seed["owner"].auth_version,
        ),
        "X-Terminal-Id": str(seed["terminal"].id),
    }


async def set_role(session, seed, code):
    role = await session.scalar(
        select(Role).where(
            Role.company_id == seed["company"].id,
            Role.code == code,
        )
    )
    if role is None:
        role = Role(
            id=uuid4(), company_id=seed["company"].id, code=code, name="Test role", permissions=[]
        )
        session.add(role)
        await session.flush()
    assignment = await session.scalar(
        select(UserRole).where(
            UserRole.user_id == seed["owner"].id,
        )
    )
    assignment.role_id = role.id
    await session.commit()
    await session.refresh(seed["owner"])
    return token(seed, code)


@pytest_asyncio.fixture
async def paid_retained(client, session, seed_owner, request):
    shift, _station, drink, gaming = await _seed_live_session(
        session,
        seed_owner,
        drink_price_minor=2300,
    )
    params = getattr(request, "param", [1, 1])
    if isinstance(params, dict):
        drink.tax_rate = params["tax_rate"]
        await session.commit()
        quantities = params["quantities"]
    else:
        quantities = params
    headers = await set_role(session, seed_owner, "super_owner")
    for quantity in quantities:
        result = await client.post(
            f"/api/v1/gaming/sessions/{gaming.id}/addons",
            headers={**headers, "Idempotency-Key": str(uuid4())},
            json={
                "client_line_id": str(uuid4()),
                "menu_item_id": str(drink.id),
                "qty": quantity,
                "expected_unit_price_minor": 2300,
            },
        )
        assert result.status_code == 201, result.text
    cancelled = await client.post(
        f"/api/v1/gaming/sessions/{gaming.id}/cancel",
        headers={**headers, "Idempotency-Key": str(uuid4())},
        json={
            "reason": "Synthetic no-value mistake scenario",
            "expected_order_id": None,
            "retained_target_shift_id": str(shift.id),
            "expected_billing_revision": 0,
        },
    )
    assert cancelled.status_code == 200, cancelled.text
    order_id = cancelled.json()["retained_addon_order_id"]
    claim = await client.post(f"/api/v1/pos/orders/{order_id}/checkout-claim", headers=headers)
    assert claim.status_code == 201, claim.text
    paid = await client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        headers={
            **headers,
            "Idempotency-Key": str(uuid4()),
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
        json={
            "method": "upi",
            "amount_minor": 4600,
            "expected_order_total_minor": 4600,
            "expected_due_minor": 4600,
        },
    )
    assert paid.status_code == 201, paid.text
    closed = await client.post(
        f"/api/v1/pos/shifts/{shift.id}/close",
        headers=headers,
        json={"counted_minor": 0},
    )
    assert closed.status_code == 200, closed.text
    return {"order_id": order_id, "shift_id": str(shift.id), "headers": headers}


async def snapshot(session):
    await session.rollback()
    tables = (
        "orders",
        "order_lines",
        "payments",
        "shifts",
        "gaming_sessions",
        "gaming_void_receipts",
        "gaming_void_addon_lines",
        "gaming_session_addons",
        "stock_movements",
        "refunds",
        "pos_refund_requests",
        "audit_log",
        "pos_receipt_corrections",
        "google_sheets_deliveries",
    )
    # This test owns its database. Capture whole tables, including append-only
    # outbox/audit rows, so an accidental preview write cannot hide in a join.
    result = {}
    for table in tables:
        result[table] = await session.scalar(
            text(
                "SELECT md5(coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id)::text,'[]')) "  # noqa: S608
                f"FROM {table} x"
            )
        )
    await session.rollback()
    return result


async def test_paid_retained_preview_is_exact_readonly_and_does_not_refund(
    client,
    session,
    seed_owner,
    paid_retained,
):
    before = await snapshot(session)
    responses = []
    for _ in range(2):
        response = await client.post(
            "/api/v1/pos/receipt-corrections/preview",
            headers=paid_retained["headers"],
            json={"order_id": paid_retained["order_id"]},
        )
        assert response.status_code == 200, response.text
        responses.append(response.json())
    body = responses[0]
    assert body == responses[1]
    assert body["eligible"], body["blockers"]
    assert body["blockers"] == []
    assert body["amount_minor"] == 4600
    assert body["payout_minor"] == 0
    assert body["financial_effect"] is False
    assert body["commit_available"] is True
    assert len(body["lines"]) == 2
    assert body["payment_id"]
    assert len(body["source_fingerprint"]) == 64
    assert await snapshot(session) == before
    state = await session.execute(
        text(
            "SELECT o.status,s.status FROM orders o JOIN shifts s ON s.id=o.shift_id WHERE o.id=:id"
        ),
        {"id": UUID(paid_retained["order_id"])},
    )
    assert state.one() == ("paid", "closed")


@pytest.mark.parametrize("role", ["owner", "co_owner", "manager", "store_staff", "cashier"])
async def test_nonprotected_roles_cannot_preview_even_with_finance_module_access(
    client,
    session,
    seed_owner,
    paid_retained,
    role,
):
    headers = await set_role(session, seed_owner, role)
    session.add(
        RolePermissionOverride(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            role_code=role,
            module="finance",
            allowed=True,
        )
    )
    await session.commit()
    await session.refresh(seed_owner["owner"])
    headers = token(seed_owner, role)
    result = await client.post(
        "/api/v1/pos/receipt-corrections/preview",
        headers=headers,
        json={"order_id": paid_retained["order_id"]},
    )
    assert result.status_code == 403, result.text


async def test_forged_protected_claim_without_current_protected_assignment_is_denied(
    client,
    session,
    seed_owner,
    paid_retained,
):
    await set_role(session, seed_owner, "owner")
    result = await client.post(
        "/api/v1/pos/receipt-corrections/preview",
        headers=token(seed_owner, "super_owner"),
        json={"order_id": paid_retained["order_id"]},
    )
    assert result.status_code == 403, result.text


async def test_preview_denies_wrong_tenant_and_unknown_source_without_disclosure(
    client,
    session,
    seed_owner,
    paid_retained,
):
    unknown = await client.post(
        "/api/v1/pos/receipt-corrections/preview",
        headers=paid_retained["headers"],
        json={"order_id": str(uuid4())},
    )
    assert unknown.status_code == 404, unknown.text
    company = Company(id=uuid4(), name="Other test tenant")
    session.add(company)
    await session.flush()
    branch = Branch(id=uuid4(), company_id=company.id, name="Other", invoice_series_code="OT")
    owner = User(
        id=uuid4(),
        company_id=company.id,
        name="Other protected Owner",
        email=f"other-{uuid4().hex}@example.invalid",
        status="active",
        password_hash=hash_password("test-only-other-owner"),
    )
    role = Role(
        id=uuid4(),
        company_id=company.id,
        code="super_owner",
        name="Other protected Owner",
        permissions=[],
    )
    session.add_all([branch, owner, role])
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=owner.id, role_id=role.id))
    await session.commit()
    await session.refresh(owner)
    headers = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=owner.id,
            company_id=company.id,
            branch_id=branch.id,
            roles=["super_owner"],
            auth_version=owner.auth_version,
        )
    }
    hidden = await client.post(
        "/api/v1/pos/receipt-corrections/preview",
        headers=headers,
        json={"order_id": paid_retained["order_id"]},
    )
    assert hidden.status_code == 404, hidden.text
    assert hidden.json() == unknown.json()


async def test_public_commit_requires_strict_payload_and_invalid_database_evidence_is_rejected(
    client,
    session,
    seed_owner,
    paid_retained,
):
    result = await client.post(
        "/api/v1/pos/receipt-corrections/commit",
        headers=paid_retained["headers"],
        json={"order_id": paid_retained["order_id"]},
    )
    assert result.status_code == 422
    with pytest.raises(DBAPIError, match="invalid receipt correction source"):
        async with session.begin_nested():
            await session.execute(
                text("INSERT INTO pos_receipt_corrections(id) VALUES(:id)"), {"id": uuid4()}
            )
    assert (
        await session.scalar(
            text("SELECT count(*) FROM pos_receipt_corrections WHERE order_id=:id"),
            {"id": UUID(paid_retained["order_id"])},
        )
        == 0
    )
    retained = await session.scalar(text("SELECT count(*) FROM pos_receipt_corrections"))
    for command in ("TRUNCATE pos_receipt_corrections", "TRUNCATE pos_receipt_corrections CASCADE"):
        # The restoration FK can reject plain TRUNCATE before row triggers;
        # CASCADE must still reach the append-only protection, never erase it.
        expected = "append-only" if command.endswith("CASCADE") else "append-only|cannot truncate a table referenced in a foreign key constraint"
        with pytest.raises(DBAPIError, match=expected) as refused:
            async with session.begin_nested():
                await session.execute(text(command))
        assert refused.value.orig.sqlstate in {"23514", "0A000"}
        assert await session.scalar(text("SELECT count(*) FROM pos_receipt_corrections")) == retained

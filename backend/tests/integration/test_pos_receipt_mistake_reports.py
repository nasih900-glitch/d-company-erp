"""Scoped report creation, exact replay, and protected review of paid receipts."""

from __future__ import annotations

import asyncio
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.api.v1.pos import receipt_mistake_reports
from app.core.security import issue_access_token
from app.models import Branch, PosReceiptMistakeReport, Role, Terminal, User, UserRole
from app.services.audit.recorder import install_audit_listeners
from tests.integration.test_build71_candidate_migration import _run_feature
from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic
from tests.integration.test_pos_receipt_correction_preview import paid_retained  # noqa: F401
from tests.integration.test_pos_receipt_corrections import business_table_hashes, original_facts

pytestmark = pytest.mark.integration


async def _actor_headers(session, seed, code, branch, terminal):
    company = seed["company"]
    role = await session.scalar(
        select(Role).where(Role.company_id == company.id, Role.code == code)
    )
    if role is None:
        role = Role(id=uuid4(), company_id=company.id, code=code, name=code, permissions=[])
        session.add(role)
    actor = User(
        id=uuid4(),
        company_id=company.id,
        name=f"Report test {code}",
        email=f"report-{uuid4().hex}@example.test",
        password_hash=seed["owner"].password_hash,
        status="active",
    )
    session.add(actor)
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=actor.id, role_id=role.id, branch_id=branch.id))
    await session.commit()
    await session.refresh(actor)
    token = issue_access_token(
        user_id=actor.id,
        company_id=company.id,
        roles=[code],
        branch_id=branch.id,
        auth_version=actor.auth_version,
    )
    return actor.id, {"Authorization": f"Bearer {token}", "X-Terminal-Id": str(terminal.id)}


@pytest.mark.asyncio
async def test_staff_report_is_exact_and_private_without_changing_receipt(
    client,
    session,
    seed_owner,
    paid_retained,  # noqa: F811 - imported pytest fixture
    monkeypatch,
):
    company, branch = seed_owner["company"], seed_owner["branch"]
    seed_owner["terminal"].is_active = False
    await session.flush()
    alternate = Terminal(
        id=uuid4(), branch_id=branch.id, name="Report terminal", device_id=f"test-{uuid4()}"
    )
    other_branch = Branch(
        id=uuid4(), company_id=company.id, name="Private branch", invoice_series_code="PR"
    )
    other_terminal = Terminal(
        id=uuid4(), branch_id=other_branch.id, name="Other terminal", device_id=f"test-{uuid4()}"
    )
    session.add_all([alternate, other_branch, other_terminal])
    await session.commit()
    reporter_id, staff = await _actor_headers(session, seed_owner, "store_staff", branch, alternate)
    _, reader = await _actor_headers(session, seed_owner, "gaming_supervisor", branch, alternate)
    _, ordinary_owner = await _actor_headers(session, seed_owner, "owner", branch, alternate)
    owner = seed_owner["owner"]
    branch_token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        roles=["super_owner"],
        branch_id=other_branch.id,
        auth_version=owner.auth_version,
    )
    another_branch = {
        "Authorization": f"Bearer {branch_token}",
        "X-Terminal-Id": str(other_terminal.id),
    }
    install_audit_listeners()
    before = await business_table_hashes(session)
    original = await original_facts(session, paid_retained)
    order_id = paid_retained["order_id"]
    submit_url = f"/api/v1/pos/receipts/{order_id}/mistake-reports"
    review_url = "/api/v1/pos/receipt-mistake-reports"
    key = str(uuid4())
    action = {**staff, "Idempotency-Key": key}
    body = {
        "reason": "no_funds_received",
        "note": '  Receipt \u202emistake\n   note. password="synthetic credential words"  ',
    }
    monkeypatch.setattr(receipt_mistake_reports, "_MAX_REPORTS_PER_HOUR", 1)

    first, replay = await asyncio.gather(
        client.post(submit_url, headers=action, json=body),
        client.post(submit_url, headers=action, json=body),
    )
    assert first.status_code == replay.status_code == 201, (first.text, replay.text)
    assert first.json() == replay.json()
    assert set(first.json()) == {"id", "order_id", "created_at"}
    assert first.json()["order_id"] == order_id
    divergent = await client.post(
        submit_url, headers=action, json={**body, "note": body["note"].strip()}
    )
    assert divergent.status_code == 409, divergent.text
    assert (
        await client.post(
            submit_url, headers={**action, "Idempotency-Key": str(uuid4())}, json=body
        )
    ).status_code == 429
    assert (
        await client.post(submit_url, headers={**reader, "Idempotency-Key": key}, json=body)
    ).status_code == 403
    assert (
        await client.post(submit_url, headers={**another_branch, "Idempotency-Key": key}, json=body)
    ).status_code == 404
    assert (
        await client.post(submit_url, headers=action, json={**body, "payment_id": str(uuid4())})
    ).status_code == 422
    assert (await client.get(review_url, headers=staff)).status_code == 403
    assert (await client.get(review_url, headers=ordinary_owner)).status_code == 403
    assert (await client.get(review_url, headers=another_branch)).json() == {
        "items": [],
        "next_before": None,
    }

    listed = await client.get(
        review_url, headers={**paid_retained["headers"], "X-Terminal-Id": str(alternate.id)}
    )
    assert listed.status_code == 200, listed.text
    item = listed.json()["items"][0]
    assert item["order_id"] == order_id
    assert item["reporter_user_id"] == str(reporter_id)
    assert item["reporter_name"] == "Report test store_staff"
    assert item["reason"] == "no_funds_received"
    assert item["note"] == "Receipt mistake note. password=[REDACTED]"

    row = await session.get(PosReceiptMistakeReport, UUID(first.json()["id"]))
    assert row.company_id == company.id
    assert row.branch_id == branch.id
    assert str(row.original_shift_id) == paid_retained["shift_id"]
    assert row.original_terminal_id == seed_owner["terminal"].id != alternate.id
    audit = (
        await session.execute(
            text("""
        SELECT actor_user_id, after FROM audit_log WHERE entity_id = :id AND action = 'create'
    """),
            {"id": str(row.id)},
        )
    ).one()
    assert audit.actor_user_id == reporter_id
    assert {audit.after[field] for field in ("note", "idempotency_key", "request_hash")} == {
        "***REDACTED***"
    }
    assert await original_facts(session, paid_retained) == original
    after = await business_table_hashes(session)
    assert {table for table in before if before[table] != after[table]} == {
        "pos_receipt_mistake_reports",
        "audit_log",
    }

    for statement in (
        "UPDATE pos_receipt_mistake_reports SET reason = 'other' WHERE id = :id",
        "DELETE FROM pos_receipt_mistake_reports WHERE id = :id",
        "TRUNCATE pos_receipt_mistake_reports",
    ):
        with pytest.raises(DBAPIError, match="Receipt mistake reports are append-only"):
            async with session.begin_nested():
                await session.execute(text(statement), {"id": row.id})


def test_0105_empty_roundtrip_and_retained_report_blocks_downgrade():
    with _disposable_database("erp_receipt_mistake_reports") as database_url:
        for operation, revision in (
            ("upgrade", "0105"),
            ("downgrade", "0104"),
            ("upgrade", "0105"),
        ):
            result = _run_alembic(database_url, operation, revision)
            assert result.returncode == 0, result.stdout + result.stderr
        _run_feature(
            database_url,
            "tests/integration/test_pos_receipt_mistake_reports.py",
            "test_staff_report_is_exact",
        )
        downgrade = _run_alembic(database_url, "downgrade", "0104")
        assert downgrade.returncode != 0
        assert "Retained receipt mistake reports prevent downgrade" in downgrade.stderr

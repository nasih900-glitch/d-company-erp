from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import hash_password, issue_access_token
from app.models import AuditLog, Branch, BugReport, Company, Role, Terminal, User, UserRole

if TYPE_CHECKING:
    from httpx import Response


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1 from bug_reports limit 1"))
    except Exception as exc:
        pytest.skip(f"bug-report migration/local Postgres unavailable: {exc}")


async def _staff_headers(session, seed_owner) -> tuple[User, dict[str, str]]:
    staff = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"reporter-{uuid4().hex[:8]}@test.local",
        name="Cafe Reporter",
        password_hash=hash_password("password1234"),
        status="active",
    )
    role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "staff",
            )
        )
    ).scalar_one()
    session.add_all(
        [
            staff,
            UserRole(
                id=uuid4(),
                user_id=staff.id,
                role_id=role.id,
                branch_id=seed_owner["branch"].id,
            ),
        ]
    )
    await session.commit()
    await session.refresh(staff)
    token = issue_access_token(
        user_id=staff.id,
        company_id=staff.company_id,
        roles=["staff"],
        branch_id=seed_owner["branch"].id,
        auth_version=staff.auth_version,
    )
    return staff, {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "6",
    }


def _admin_headers(seed_owner) -> dict[str, str]:
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=owner.company_id,
        roles=["super_owner"],
        branch_id=seed_owner["branch"].id,
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }


def _co_owner_headers(seed_owner) -> dict[str, str]:
    """Operational bypass is intentionally not protected-system access."""
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=owner.company_id,
        roles=["co_owner"],
        branch_id=seed_owner["branch"].id,
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": False},
    )
    return {"Authorization": f"Bearer {token}"}


def _report_payload(seed_owner, **overrides) -> dict:
    payload = {
        "category": "crash",
        "severity": "high",
        "title": "Payment dialog closes unexpectedly",
        "description": "The payment dialog closes immediately after tapping the Pay button.",
        "reproduction_steps": "Open POS, add an item, tap Pay, then tap Cash.",
        "expected_behavior": "The cash tender dialog remains visible.",
        "actual_behavior": "The dialog closes and the order remains unpaid.",
        "client_context": {
            "platform": "android",
            "app_version": "3.0.5",
            "version_code": 6,
            "device_model": "Android SDK built for arm64",
            "os_version": "Android 15",
            "current_screen": "POS/Payment",
            "branch_id": str(seed_owner["branch"].id),
            "branch_name": "untrusted client branch label",
            "terminal_id": str(seed_owner["terminal"].id),
            "terminal_name": "untrusted client terminal label",
            "connectivity": "online",
            "occurred_at": datetime.now(UTC).isoformat(),
        },
    }
    payload.update(overrides)
    return payload


@pytest.mark.integration
@pytest.mark.asyncio
async def test_staff_can_submit_idempotently_but_cannot_open_admin_inbox(
    client,
    session,
    seed_owner,
) -> None:
    staff, headers = await _staff_headers(session, seed_owner)
    key = f"bug-report-{uuid4()}"
    payload = _report_payload(
        seed_owner,
        description=(
            "Payment failed with password=not-safe and Authorization: Bearer secret-token-value"
        ),
    )
    create = await client.post(
        "/api/v1/bug-reports",
        headers={**headers, "Idempotency-Key": key},
        json=payload,
    )
    assert create.status_code == 201, create.text
    body = create.json()
    assert body["status"] == "open"
    assert body["reporter"] == {
        "user_id": str(staff.id),
        "name": staff.name,
        "email": staff.email,
    }
    assert body["client_context"]["branch_name"] == seed_owner["branch"].name
    assert body["client_context"]["terminal_name"] == seed_owner["terminal"].name
    assert "not-safe" not in body["description"]
    assert "secret-token-value" not in body["description"]

    replay = await client.post(
        "/api/v1/bug-reports",
        headers={**headers, "Idempotency-Key": key},
        json=payload,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == body

    conflicting_payload = {**payload, "title": "A different valid report title"}
    conflict = await client.post(
        "/api/v1/bug-reports",
        headers={**headers, "Idempotency-Key": key},
        json=conflicting_payload,
    )
    assert conflict.status_code == 409, conflict.text

    missing_key = await client.post("/api/v1/bug-reports", headers=headers, json=payload)
    assert missing_key.status_code == 422, missing_key.text
    assert "Idempotency-Key" in missing_key.json()["error"]["message"]

    denied_list = await client.get("/api/v1/bug-reports", headers=headers)
    denied_detail = await client.get(f"/api/v1/bug-reports/{body['id']}", headers=headers)
    denied_update = await client.patch(
        f"/api/v1/bug-reports/{body['id']}",
        headers=headers,
        json={"status": "acknowledged"},
    )
    assert denied_list.status_code == 403
    assert denied_detail.status_code == 403
    assert denied_update.status_code == 403

    # A co-owner's broad operational bypass must not become admin.system.
    # Only the protected system owner (audit_access) receives the inbox.
    co_owner_headers = _co_owner_headers(seed_owner)
    co_owner_list = await client.get("/api/v1/bug-reports", headers=co_owner_headers)
    co_owner_detail = await client.get(
        f"/api/v1/bug-reports/{body['id']}", headers=co_owner_headers
    )
    co_owner_update = await client.patch(
        f"/api/v1/bug-reports/{body['id']}",
        headers=co_owner_headers,
        json={"status": "acknowledged"},
    )
    assert co_owner_list.status_code == 403
    assert co_owner_detail.status_code == 403
    assert co_owner_update.status_code == 403

    rows = (
        (
            await session.execute(
                select(BugReport).where(
                    BugReport.company_id == seed_owner["company"].id,
                    BugReport.reporter_user_id == staff.id,
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == 1


@pytest.mark.integration
@pytest.mark.asyncio
async def test_admin_inbox_is_paginated_filtered_company_scoped_and_safely_audited(
    client,
    session,
    seed_owner,
) -> None:
    _, staff_headers = await _staff_headers(session, seed_owner)
    admin_headers = _admin_headers(seed_owner)
    first = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"bug-{uuid4()}"},
        json=_report_payload(seed_owner),
    )
    second = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"bug-{uuid4()}"},
        json=_report_payload(
            seed_owner,
            category="performance",
            severity="low",
            title="Menu search takes too long",
            description="Menu search needs several seconds to display matching products.",
        ),
    )
    assert first.status_code == second.status_code == 201
    company_id = seed_owner["company"].id

    page = await client.get(
        "/api/v1/bug-reports",
        headers=admin_headers,
        params={"limit": 1, "offset": 0, "severity": "high"},
    )
    assert page.status_code == 200, page.text
    payload = page.json()
    assert payload["limit"] == 1
    assert payload["offset"] == 0
    assert payload["total"] == 1
    assert len(payload["items"]) == 1
    assert payload["summary"]["counts_by_status"]["open"] == 1
    # Severity facets ignore their own filter while retaining the remaining filters.
    assert payload["summary"]["counts_by_severity"]["high"] == 1
    assert payload["summary"]["counts_by_severity"]["low"] == 1

    report_id = first.json()["id"]
    resolved = await client.patch(
        f"/api/v1/bug-reports/{report_id}",
        headers=admin_headers,
        json={
            "status": "resolved",
            "internal_resolution_note": "Corrected the payment-dialog state ownership.",
        },
    )
    assert resolved.status_code == 200, resolved.text
    assert resolved.json()["resolved_at"] is not None
    assert resolved.json()["resolved_by"] == str(seed_owner["owner"].id)

    retried_resolution = await client.patch(
        f"/api/v1/bug-reports/{report_id}",
        headers=admin_headers,
        json={
            "status": "resolved",
            "internal_resolution_note": "Corrected the payment-dialog state ownership.",
        },
    )
    assert retried_resolution.status_code == 200, retried_resolution.text
    assert retried_resolution.json() == resolved.json()

    reopened = await client.patch(
        f"/api/v1/bug-reports/{report_id}",
        headers=admin_headers,
        json={"status": "in_progress"},
    )
    assert reopened.status_code == 200, reopened.text
    assert reopened.json()["resolved_at"] is None
    assert reopened.json()["resolved_by"] is None

    invalid_transition = await client.patch(
        f"/api/v1/bug-reports/{report_id}",
        headers=admin_headers,
        json={"status": "closed"},
    )
    assert invalid_transition.status_code == 422

    session.expire_all()
    audit_rows = (
        (
            await session.execute(
                select(AuditLog)
                .where(
                    AuditLog.company_id == company_id,
                    AuditLog.entity_type == "BugReport",
                    AuditLog.entity_id == report_id,
                )
                .order_by(AuditLog.id)
            )
        )
        .scalars()
        .all()
    )
    assert [row.action for row in audit_rows] == [
        "bug_report_create",
        "bug_report_status_change",
        "bug_report_resolution_note_change",
        "bug_report_status_change",
    ]
    serialized_audit = " ".join(
        f"{row.before!r} {row.after!r} {row.reason!r}" for row in audit_rows
    )
    assert "Payment dialog closes unexpectedly" not in serialized_audit
    assert "Corrected the payment-dialog" not in serialized_audit
    assert audit_rows[2].before == {"note_present": False}
    assert audit_rows[2].after == {"note_present": True}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_lifecycle_patch_preserves_location_snapshots_after_canonical_rename(
    client,
    session,
    seed_owner,
) -> None:
    _, staff_headers = await _staff_headers(session, seed_owner)
    original_branch_name = seed_owner["branch"].name
    original_terminal_name = seed_owner["terminal"].name
    created = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"rename-{uuid4()}"},
        json=_report_payload(seed_owner),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]

    seed_owner["branch"].name = "Renamed Main Branch"
    seed_owner["terminal"].name = "Renamed Main Terminal"
    await session.commit()

    updated = await client.patch(
        f"/api/v1/bug-reports/{report_id}",
        headers=_admin_headers(seed_owner),
        json={"status": "acknowledged"},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["status"] == "acknowledged"
    assert updated.json()["client_context"]["branch_name"] == original_branch_name
    assert updated.json()["client_context"]["terminal_name"] == original_terminal_name

    session.expire_all()
    stored = await session.get(BugReport, UUID(report_id))
    assert stored is not None
    assert stored.branch_name == original_branch_name
    assert stored.terminal_name == original_terminal_name


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cross_company_context_and_records_are_not_disclosed(
    client,
    session,
    seed_owner,
) -> None:
    _, staff_headers = await _staff_headers(session, seed_owner)
    admin_headers = _admin_headers(seed_owner)
    other_company = Company(id=uuid4(), name="Other support tenant")
    other_branch = Branch(
        id=uuid4(),
        company_id=other_company.id,
        name="Other branch",
        invoice_series_code="OT",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name="Other terminal",
        device_id=f"other-{uuid4()}",
    )
    other_user = User(
        id=uuid4(),
        company_id=other_company.id,
        email=f"other-{uuid4().hex[:8]}@test.local",
        name="Other user",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add_all([other_company, other_branch, other_terminal, other_user])
    await session.commit()

    same_company_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Another same-company branch",
        invoice_series_code="SC",
    )
    same_company_terminal = Terminal(
        id=uuid4(),
        branch_id=same_company_branch.id,
        name="Another branch terminal",
        device_id=f"same-company-{uuid4()}",
    )
    same_branch_terminal = Terminal(
        id=uuid4(),
        branch_id=seed_owner["branch"].id,
        name="Another terminal in the bound branch",
        device_id=f"same-branch-{uuid4()}",
    )
    session.add_all([same_company_branch, same_company_terminal, same_branch_terminal])
    await session.commit()

    # The reporter's token and request header are bound to the seed branch and
    # terminal. Even valid IDs elsewhere in the same tenant are not acceptable
    # evidence for this request.
    wrong_same_company_branch = _report_payload(seed_owner)
    wrong_same_company_branch["client_context"]["branch_id"] = str(
        same_company_branch.id
    )
    wrong_same_company_branch["client_context"]["terminal_id"] = str(
        same_company_terminal.id
    )
    rejected_same_company_branch = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"bug-{uuid4()}"},
        json=wrong_same_company_branch,
    )
    assert rejected_same_company_branch.status_code == 404

    wrong_bound_terminal = _report_payload(seed_owner)
    wrong_bound_terminal["client_context"]["terminal_id"] = str(same_branch_terminal.id)
    rejected_bound_terminal = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"bug-{uuid4()}"},
        json=wrong_bound_terminal,
    )
    assert rejected_bound_terminal.status_code == 404

    foreign_context = _report_payload(seed_owner)
    foreign_context["client_context"]["branch_id"] = str(other_branch.id)
    foreign_context["client_context"]["terminal_id"] = str(other_terminal.id)
    rejected = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"bug-{uuid4()}"},
        json=foreign_context,
    )
    assert rejected.status_code == 404

    foreign_report = BugReport(
        id=uuid4(),
        company_id=other_company.id,
        reporter_user_id=other_user.id,
        reporter_name=other_user.name,
        reporter_email=other_user.email,
        category="crash",
        severity="critical",
        title="Foreign tenant report",
        description="This report must never be visible to another tenant.",
        client_platform="android",
        connectivity="unknown",
        status="open",
        status_changed_at=datetime.now(UTC),
        status_changed_by=other_user.id,
    )
    session.add(foreign_report)
    await session.commit()

    hidden = await client.get(f"/api/v1/bug-reports/{foreign_report.id}", headers=admin_headers)
    assert hidden.status_code == 404
    page = await client.get("/api/v1/bug-reports", headers=admin_headers)
    assert page.status_code == 200
    assert str(foreign_report.id) not in {item["id"] for item in page.json()["items"]}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_canonical_two_hundred_character_branch_name_is_snapshotted(
    client,
    session,
    seed_owner,
) -> None:
    canonical_name = "B" * 200
    seed_owner["branch"].name = canonical_name
    await session.commit()
    _, staff_headers = await _staff_headers(session, seed_owner)

    created = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"long-branch-{uuid4()}"},
        json=_report_payload(seed_owner),
    )

    assert created.status_code == 201, created.text
    assert created.json()["client_context"]["branch_name"] == canonical_name


@pytest.mark.integration
@pytest.mark.asyncio
async def test_submission_rate_limit_is_durable_per_company_reporter(
    client,
    session,
    seed_owner,
) -> None:
    _, headers = await _staff_headers(session, seed_owner)
    payload = _report_payload(seed_owner)
    release_requests = asyncio.Event()

    async def submit() -> Response:
        await release_requests.wait()
        return await client.post(
            "/api/v1/bug-reports",
            headers={**headers, "Idempotency-Key": f"rate-{uuid4()}"},
            json=payload,
        )

    tasks = [asyncio.create_task(submit()) for _ in range(11)]
    # Let every task reach the same start gate before releasing the burst. This
    # exercises separate request transactions contending for one reporter row.
    await asyncio.sleep(0)
    release_requests.set()
    outcomes = await asyncio.wait_for(
        asyncio.gather(*tasks, return_exceptions=True),
        timeout=20,
    )

    failures = [outcome for outcome in outcomes if isinstance(outcome, BaseException)]
    assert failures == []
    responses = [outcome for outcome in outcomes if not isinstance(outcome, BaseException)]
    assert sorted(response.status_code for response in responses) == [201] * 10 + [429]

    limited = next(response for response in responses if response.status_code == 429)
    assert limited.json()["error"]["details"] == {
        "limit": 10,
        "window_seconds": 3600,
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_guard_blocks_native_submission_rewrites_and_false_provenance(
    client,
    session,
    seed_owner,
) -> None:
    staff, headers = await _staff_headers(session, seed_owner)
    created = await client.post(
        "/api/v1/bug-reports",
        headers={**headers, "Idempotency-Key": f"guard-{uuid4()}"},
        json=_report_payload(seed_owner),
    )
    assert created.status_code == 201, created.text
    report_id = UUID(created.json()["id"])
    company_id = seed_owner["company"].id
    staff_id = staff.id
    staff_email = staff.email

    with pytest.raises(DBAPIError, match="submission context is immutable"):
        await session.execute(
            text("UPDATE bug_reports SET description = :value WHERE id = :id"),
            {"id": report_id, "value": "A silently rewritten support description."},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="invalid bug report status transition"):
        await session.execute(
            text(
                "UPDATE bug_reports "
                "SET status = 'closed', internal_resolution_note = 'Hidden close', "
                "status_changed_at = now(), status_changed_by = :actor "
                "WHERE id = :id"
            ),
            {"id": report_id, "actor": staff_id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="durable support evidence"):
        await session.execute(
            text("DELETE FROM bug_reports WHERE id = :id"),
            {"id": report_id},
        )
    await session.rollback()

    forged = BugReport(
        id=uuid4(),
        company_id=company_id,
        reporter_user_id=staff_id,
        reporter_name="Forged Reporter Name",
        reporter_email=staff_email,
        category="crash",
        severity="critical",
        title="Forged reporter snapshot",
        description="This direct insert must fail before it becomes inbox evidence.",
        client_platform="android",
        connectivity="unknown",
        status="open",
        status_changed_at=datetime.now(UTC),
        status_changed_by=staff_id,
    )
    session.add(forged)
    with pytest.raises(DBAPIError, match="reporter snapshots must match"):
        await session.commit()
    await session.rollback()

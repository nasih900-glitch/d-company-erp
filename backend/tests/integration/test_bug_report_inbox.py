from __future__ import annotations

import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from io import BytesIO
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from PIL import Image
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import hash_password, issue_access_token
from app.models import (
    AuditLog,
    Branch,
    BugReport,
    BugReportAttachment,
    Company,
    Role,
    Terminal,
    User,
    UserRole,
)
from app.services.bug_reports.attachments import purge_expired_bug_report_attachments

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
        "X-Client-Version-Code": "8",
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
            "app_version": "3.0.7",
            "version_code": 8,
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


def _image_bytes(
    image_format: str,
    *,
    color: tuple[int, int, int, int] = (25, 75, 125, 180),
) -> bytes:
    mode = "RGBA" if image_format in {"PNG", "WEBP"} else "RGB"
    image = Image.new(mode, (8, 6), color if mode == "RGBA" else color[:3])
    exif = Image.Exif()
    exif[0x010E] = "private integration-test metadata"
    output = BytesIO()
    image.save(output, format=image_format, exif=exif)
    image.close()
    return output.getvalue()


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
    # Submission returns the same least-privilege shape used by My requests.
    # Private triage notes and reporter-email snapshots are admin-inbox data.
    assert "reporter" not in body
    assert "internal_resolution_note" not in body
    assert body["public_replies"] == []
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


@pytest.mark.integration
@pytest.mark.asyncio
async def test_reporter_can_follow_public_replies_without_private_inbox_data(
    client,
    session,
    seed_owner,
) -> None:
    staff, staff_headers = await _staff_headers(session, seed_owner)
    _, other_staff_headers = await _staff_headers(session, seed_owner)
    admin_headers = _admin_headers(seed_owner)
    created = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"conversation-{uuid4()}"},
        json=_report_payload(
            seed_owner,
            client_context={
                **_report_payload(seed_owner)["client_context"],
                "last_action": "POST /api/v1/pos/orders/:id/payments",
                "error_code": "payment_unavailable",
            },
        ),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]

    initial_summary = await client.get("/api/v1/bug-reports/inbox-summary", headers=admin_headers)
    assert initial_summary.status_code == 200, initial_summary.text
    assert initial_summary.json() == {
        "active": 1,
        "unread": 1,
        "urgent_unread": 1,
        "critical_active": 0,
        "last_activity_at": initial_summary.json()["last_activity_at"],
    }
    denied_summary = await client.get(
        "/api/v1/bug-reports/inbox-summary",
        headers=_co_owner_headers(seed_owner),
    )
    assert denied_summary.status_code == 403

    marked = await client.post(f"/api/v1/bug-reports/{report_id}/read", headers=admin_headers)
    assert marked.status_code == 204, marked.text
    after_read = await client.get("/api/v1/bug-reports/inbox-summary", headers=admin_headers)
    assert after_read.json()["unread"] == 0

    reply_key = f"support-reply-{uuid4()}"
    reply = await client.post(
        f"/api/v1/bug-reports/{report_id}/public-replies",
        headers={**admin_headers, "Idempotency-Key": reply_key},
        json={"message": "Restart the payment screen. Your order is still safely held."},
    )
    assert reply.status_code == 201, reply.text
    replay = await client.post(
        f"/api/v1/bug-reports/{report_id}/public-replies",
        headers={**admin_headers, "Idempotency-Key": reply_key},
        json={"message": "Restart the payment screen. Your order is still safely held."},
    )
    assert replay.status_code == 201
    assert replay.json() == reply.json()
    conflict = await client.post(
        f"/api/v1/bug-reports/{report_id}/public-replies",
        headers={**admin_headers, "Idempotency-Key": reply_key},
        json={"message": "A different response under the same operation key."},
    )
    assert conflict.status_code == 409

    private_note = await client.patch(
        f"/api/v1/bug-reports/{report_id}",
        headers=admin_headers,
        json={"internal_resolution_note": "Private diagnosis: cash state reducer race."},
    )
    assert private_note.status_code == 200, private_note.text
    assert private_note.json()["internal_resolution_note"].startswith("Private diagnosis")

    mine = await client.get("/api/v1/bug-reports/mine", headers=staff_headers)
    assert mine.status_code == 200, mine.text
    reporter_view = next(item for item in mine.json()["items"] if item["id"] == report_id)
    assert reporter_view["client_context"]["last_action"].endswith("/payments")
    assert reporter_view["client_context"]["error_code"] == "payment_unavailable"
    assert reporter_view["public_replies"] == [reply.json()]
    assert "internal_resolution_note" not in reporter_view
    assert "reporter" not in reporter_view
    assert "status_changed_by" not in reporter_view
    assert "resolved_by" not in reporter_view

    hidden_from_other_staff = await client.get(
        f"/api/v1/bug-reports/mine/{report_id}",
        headers=other_staff_headers,
    )
    assert hidden_from_other_staff.status_code == 404
    denied_reply = await client.post(
        f"/api/v1/bug-reports/{report_id}/public-replies",
        headers={**staff_headers, "Idempotency-Key": f"denied-{uuid4()}"},
        json={"message": "A reporter cannot impersonate an owner response."},
    )
    assert denied_reply.status_code == 403

    # Reply insertion and private-note update are separately auditable, without
    # copying either message body into the audit JSON.
    audit_rows = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_id == report_id,
                )
            )
        )
        .scalars()
        .all()
    )
    reply_audit = next(row for row in audit_rows if row.action == "bug_report_public_reply_add")
    assert "Restart" not in str(reply_audit.after)
    assert staff.email not in str(reporter_view)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_private_screenshot_is_bounded_idempotent_tenant_scoped_and_purgeable(
    client,
    session,
    seed_owner,
) -> None:
    staff, staff_headers = await _staff_headers(session, seed_owner)
    _, other_staff_headers = await _staff_headers(session, seed_owner)
    admin_headers = _admin_headers(seed_owner)
    created = await client.post(
        "/api/v1/bug-reports",
        headers={**staff_headers, "Idempotency-Key": f"attachment-report-{uuid4()}"},
        json=_report_payload(seed_owner),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]
    png = _image_bytes("PNG")
    key = f"attachment-{uuid4()}"
    uploaded = await client.post(
        f"/api/v1/bug-reports/mine/{report_id}/attachments",
        headers={**staff_headers, "Idempotency-Key": key},
        files={"file": ("payment-screen.png", png, "image/png")},
    )
    assert uploaded.status_code == 201, uploaded.text
    attachment = uploaded.json()
    assert attachment["available"] is True
    assert 0 < attachment["byte_size"] <= 2 * 1024 * 1024
    assert len(attachment["sha256"]) == 64

    # Multipart boundaries differ on every request; canonical file hashing
    # still makes a genuine network retry return exactly the first result.
    replay = await client.post(
        f"/api/v1/bug-reports/mine/{report_id}/attachments",
        headers={**staff_headers, "Idempotency-Key": key},
        files={"file": ("payment-screen.png", png, "image/png")},
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == attachment
    conflict = await client.post(
        f"/api/v1/bug-reports/mine/{report_id}/attachments",
        headers={**staff_headers, "Idempotency-Key": key},
        files={"file": ("different.jpg", _image_bytes("JPEG"), "image/jpeg")},
    )
    assert conflict.status_code == 409
    malformed_images = (
        ("not-a-jpeg.jpg", b"\xff\xd8\xffnot-a-jpeg", "image/jpeg"),
        ("not-a-png.png", b"\x89PNG\r\n\x1a\nnot-a-png", "image/png"),
        ("not-a-webp.webp", b"RIFFxxxxWEBPnot-a-webp", "image/webp"),
    )
    for filename, malformed, content_type in malformed_images:
        rejected = await client.post(
            f"/api/v1/bug-reports/mine/{report_id}/attachments",
            headers={**staff_headers, "Idempotency-Key": f"malformed-{uuid4()}"},
            files={"file": (filename, malformed, content_type)},
        )
        assert rejected.status_code == 422, rejected.text

    downloaded = await client.get(
        f"/api/v1/bug-reports/{report_id}/attachments/{attachment['id']}",
        headers=admin_headers,
    )
    assert downloaded.status_code == 200
    assert len(downloaded.content) == attachment["byte_size"]
    assert hashlib.sha256(downloaded.content).hexdigest() == attachment["sha256"]
    with Image.open(BytesIO(downloaded.content)) as canonical:
        canonical.load()
        assert canonical.format == "PNG"
        assert canonical.size == (8, 6)
        assert not canonical.getexif()
        assert "exif" not in canonical.info
    assert downloaded.headers["cache-control"] == "private, no-store"
    assert downloaded.headers["x-content-type-options"] == "nosniff"
    denied_other_reporter = await client.get(
        f"/api/v1/bug-reports/mine/{report_id}/attachments/{attachment['id']}",
        headers=other_staff_headers,
    )
    assert denied_other_reporter.status_code == 404
    denied_unprotected_owner = await client.get(
        f"/api/v1/bug-reports/{report_id}/attachments/{attachment['id']}",
        headers=_co_owner_headers(seed_owner),
    )
    assert denied_unprotected_owner.status_code == 403

    report = await session.get(BugReport, UUID(report_id))
    assert report is not None
    oversized = b"x" * (2 * 1024 * 1024 + 1)
    invalid_rows = (
        ("oversized.png", 1, hashlib.sha256(oversized).hexdigest(), oversized),
        ("wrong-size.png", len(png) + 1, hashlib.sha256(png).hexdigest(), png),
        ("wrong-digest.png", len(png), "0" * 64, png),
        ("uppercase-digest.png", len(png), hashlib.sha256(png).hexdigest().upper(), png),
    )
    insert_attachment = text(
        """
        INSERT INTO bug_report_attachments (
            id, company_id, bug_report_id, uploader_user_id, original_filename,
            content_type, byte_size, sha256, payload, created_at, expires_at, purged_at
        ) VALUES (
            :id, :company_id, :report_id, :uploader_user_id, :filename,
            'image/png', :byte_size, :sha256, :payload, now(),
            now() + interval '90 days', NULL
        )
        """
    )
    for filename, declared_size, digest, payload in invalid_rows:
        with pytest.raises(DBAPIError, match="ck_bug_report_attachments"):
            async with session.begin_nested():
                await session.execute(
                    insert_attachment,
                    {
                        "id": uuid4(),
                        "company_id": seed_owner["company"].id,
                        "report_id": report.id,
                        "uploader_user_id": staff.id,
                        "filename": filename,
                        "byte_size": declared_size,
                        "sha256": digest,
                        "payload": payload,
                    },
                )

    expired_payload = _image_bytes("PNG", color=(120, 30, 60, 255))
    expired_older_payload = _image_bytes("PNG", color=(20, 140, 80, 255))
    expired_sha256 = hashlib.sha256(expired_payload).hexdigest()
    expired_older_sha256 = hashlib.sha256(expired_older_payload).hexdigest()
    expired = BugReportAttachment(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        bug_report_id=report.id,
        uploader_user_id=staff.id,
        original_filename="expired.png",
        content_type="image/png",
        byte_size=len(expired_payload),
        sha256=expired_sha256,
        payload=expired_payload,
        created_at=datetime.now(UTC) - timedelta(days=91),
        expires_at=datetime.now(UTC) - timedelta(days=1),
    )
    expired_older = BugReportAttachment(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        bug_report_id=report.id,
        uploader_user_id=staff.id,
        original_filename="expired-older.png",
        content_type="image/png",
        byte_size=len(expired_older_payload),
        sha256=expired_older_sha256,
        payload=expired_older_payload,
        created_at=datetime.now(UTC) - timedelta(days=92),
        expires_at=datetime.now(UTC) - timedelta(days=2),
    )
    session.add_all([expired, expired_older])
    await session.flush()
    first_batch = await purge_expired_bug_report_attachments(
        session,
        now=datetime.now(UTC),
        batch_size=1,
        company_id=seed_owner["company"].id,
    )
    assert first_batch.rows == 1
    assert first_batch.bytes_released == len(expired_older_payload)
    assert expired_older.payload is None
    assert expired_older.purged_at is not None
    assert expired_older.byte_size == len(expired_older_payload)
    assert expired_older.sha256 == expired_older_sha256
    assert expired.payload == expired_payload

    second_batch = await purge_expired_bug_report_attachments(
        session,
        now=datetime.now(UTC),
        batch_size=1,
        company_id=seed_owner["company"].id,
    )
    assert second_batch.rows == 1
    assert second_batch.bytes_released == len(expired_payload)
    assert expired.payload is None
    assert expired.purged_at is not None
    assert expired.byte_size == len(expired_payload)
    assert expired.sha256 == expired_sha256

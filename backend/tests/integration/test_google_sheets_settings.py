"""Owner configuration and encrypted storage for the Sheets mirror."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from time import monotonic
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select, text

from app.api.v1.settings.google_sheets import (
    GoogleSheetsMirrorConfigure,
    configure_google_sheets_mirror,
)
from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.core.tenant import TenantContext
from app.models import AuditLog, Company
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.audit.recorder import install_audit_listeners
from app.services.integrations.google_sheets_mirror import (
    _delivery_dispatchable_clause,
    enqueue_google_sheets_event_if_enabled,
)
from app.services.integrations.google_sheets_runtime import (
    resolve_google_sheets_destination,
)


def _headers(seed, *, roles: list[str] | None = None) -> dict[str, str]:
    owner = seed["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        roles=roles or ["owner"],
        auth_version=owner.auth_version,
    )
    return {"Authorization": f"Bearer {token}"}


async def _fresh_company(session, company_id: UUID) -> Company:
    session.expire_all()
    company = await session.get(Company, company_id)
    assert company is not None
    return company


async def _company_audit_rows(session, company_id: UUID) -> list[AuditLog]:
    session.expire_all()
    return list(
        (
            await session.execute(
                select(AuditLog)
                .where(
                    AuditLog.company_id == company_id,
                    AuditLog.entity_type == "Company",
                    AuditLog.entity_id == str(company_id),
                    AuditLog.action == "update",
                )
                .order_by(AuditLog.id)
            )
        )
        .scalars()
        .all()
    )


def _assert_sheets_secret_absent_from_audit(
    row: AuditLog,
    *,
    forbidden_values: tuple[str, ...],
) -> None:
    snapshots = (row.before or {}, row.after or {})
    for snapshot in snapshots:
        assert snapshot["google_sheets_signing_secret_ciphertext"] == "***REDACTED***"
        rendered = repr(snapshot)
        for forbidden in forbidden_values:
            assert forbidden not in rendered


async def _wait_for_company_lock_wait(
    observer,
    *,
    blocker_pid: int,
    timeout_seconds: float = 5.0,
) -> None:
    deadline = monotonic() + timeout_seconds
    while monotonic() < deadline:
        blocked = await observer.scalar(
            text(
                "SELECT EXISTS ("
                "SELECT 1 FROM pg_stat_activity "
                "WHERE :blocker_pid = ANY(pg_blocking_pids(pid))"
                ")"
            ),
            {"blocker_pid": blocker_pid},
        )
        if blocked:
            return
        await asyncio.sleep(0.01)
    pytest.fail("configuration change never reached the company row-lock boundary")


@pytest.mark.integration
@pytest.mark.asyncio
async def test_owner_configures_rotates_tests_and_disconnects_encrypted_mirror(
    client,
    session,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    url = "https://script.google.com/macros/s/test-deployment/exec"

    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "rotate_secret": False},
    )
    assert configured.status_code == 200, configured.text
    first_secret = configured.json()["signing_secret"]
    assert isinstance(first_secret, str)
    assert len(first_secret) >= 32
    assert configured.json()["secret_configured"] is True
    assert configured.json()["connection_verified"] is False

    company = await _fresh_company(session, company_id)
    ciphertext = company.google_sheets_signing_secret_ciphertext
    assert ciphertext
    assert first_secret not in ciphertext
    assert company.google_sheets_mirror_enabled is True

    status = await client.get("/api/v1/settings/google-sheets", headers=headers)
    assert status.status_code == 200, status.text
    assert "signing_secret" not in status.json()
    assert status.json()["webhook_url"] == url

    saved = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "rotate_secret": False},
    )
    assert saved.status_code == 200, saved.text
    assert saved.json()["signing_secret"] is None
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_signing_secret_ciphertext == ciphertext

    rotated = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "rotate_secret": True},
    )
    assert rotated.status_code == 200, rotated.text
    assert rotated.json()["signing_secret"] != first_secret
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_signing_secret_ciphertext != ciphertext

    queued = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued.status_code == 200, queued.text
    event = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company_id,
                GoogleSheetsDelivery.event_id == UUID(queued.json()["event_id"]),
            )
        )
    ).scalar_one()
    assert event.event_type == "mirror.connection_test"
    assert event.status == "pending"
    assert event.occurred_at <= datetime.now(UTC)

    event.status = "delivered"
    event.delivered_at = datetime.now(UTC)
    await session.commit()
    verified = await client.get("/api/v1/settings/google-sheets", headers=headers)
    assert verified.status_code == 200, verified.text
    assert verified.json()["connection_verified"] is True

    rotated_again = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "rotate_secret": True},
    )
    assert rotated_again.status_code == 200, rotated_again.text
    assert rotated_again.json()["connection_verified"] is False

    disconnected = await client.delete("/api/v1/settings/google-sheets", headers=headers)
    assert disconnected.status_code == 200, disconnected.text
    assert disconnected.json()["enabled"] is False
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_signing_secret_ciphertext is None
    assert company.google_sheets_webhook_url is None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_business_events_wait_for_current_connection_test_delivery(
    client,
    session,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={
            "webhook_url": "https://script.google.com/macros/s/verified-gate/exec",
            "rotate_secret": False,
        },
    )
    assert configured.status_code == 200, configured.text
    assert configured.json()["connection_verified"] is False

    original_source_id = str(uuid4())
    original_occurred_at = datetime.now(UTC)
    original_payload = {"amount_minor": -100, "status": "recorded"}
    before_verification = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=original_source_id,
        source_revision="recorded-v1",
        occurred_at=original_occurred_at,
        payload=original_payload,
    )
    assert before_verification is not None
    assert before_verification.status == "pending"
    original_delivery_id = before_verification.id
    original_configuration_id = before_verification.configuration_id
    await session.commit()
    business_events = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company_id,
                GoogleSheetsDelivery.event_type == "finance.expense.recorded",
            )
        )
    ).scalars().all()
    assert [row.id for row in business_events] == [original_delivery_id]
    assert (
        await session.scalar(
            select(GoogleSheetsDelivery.id).where(
                GoogleSheetsDelivery.id == original_delivery_id,
                _delivery_dispatchable_clause(),
            )
        )
        is None
    )
    pending_status = await client.get("/api/v1/settings/google-sheets", headers=headers)
    assert pending_status.status_code == 200, pending_status.text
    assert pending_status.json()["pending_count"] == 1
    assert pending_status.json()["held_count"] == 1

    # Rotating an unverified same-URL secret keeps the held business fact in
    # the same generation while resetting verification for the new secret.
    company = await _fresh_company(session, company_id)
    initial_ciphertext = company.google_sheets_signing_secret_ciphertext
    same_url_rotation = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={
            "webhook_url": "https://script.google.com/macros/s/verified-gate/exec",
            "rotate_secret": True,
        },
    )
    assert same_url_rotation.status_code == 200, same_url_rotation.text
    assert isinstance(same_url_rotation.json()["signing_secret"], str)
    assert same_url_rotation.json()["connection_verified"] is False
    assert same_url_rotation.json()["held_count"] == 1
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_configuration_id == original_configuration_id
    assert company.google_sheets_signing_secret_ciphertext != initial_ciphertext
    await session.refresh(before_verification)
    assert before_verification.configuration_id == original_configuration_id

    queued = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued.status_code == 200, queued.text
    assert queued.json()["status"] == "pending"
    connection_test = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company_id,
                GoogleSheetsDelivery.event_id == UUID(queued.json()["event_id"]),
            )
        )
    ).scalar_one()
    connection_test.status = "delivered"
    connection_test.delivered_at = datetime.now(UTC)
    await session.commit()
    verified_status = await client.get("/api/v1/settings/google-sheets", headers=headers)
    assert verified_status.status_code == 200, verified_status.text
    assert verified_status.json()["connection_verified"] is True
    assert verified_status.json()["held_count"] == 0
    assert await session.scalar(
        select(GoogleSheetsDelivery.id).where(
            GoogleSheetsDelivery.id == original_delivery_id,
            _delivery_dispatchable_clause(),
        )
    ) == original_delivery_id

    # Drain the generation before rotating its secret.
    before_verification.status = "delivered"
    before_verification.delivered_at = datetime.now(UTC)
    await session.commit()

    # A destination/secret rotation starts a new configuration generation.
    # Its old delivered test must not authorize facts recorded afterwards.
    await session.commit()
    rotated = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={
            "webhook_url": "https://script.google.com/macros/s/verified-gate/exec",
            "rotate_secret": True,
        },
    )
    assert rotated.status_code == 200, rotated.text
    assert rotated.json()["connection_verified"] is False
    company = await _fresh_company(session, company_id)
    rotated_configuration_id = company.google_sheets_configuration_id
    assert rotated_configuration_id is not None
    assert rotated_configuration_id != original_configuration_id

    after_rotation_before_test = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=str(uuid4()),
        source_revision="recorded-v1",
        occurred_at=datetime.now(UTC),
        payload={"amount_minor": -300, "status": "recorded"},
    )
    assert after_rotation_before_test is not None
    assert after_rotation_before_test.configuration_id == rotated_configuration_id
    await session.commit()
    assert (
        await session.scalar(
            select(GoogleSheetsDelivery.id).where(
                GoogleSheetsDelivery.id == after_rotation_before_test.id,
                _delivery_dispatchable_clause(),
            )
        )
        is None
    )

    current_test = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert current_test.status_code == 200, current_test.text
    current_connection_test = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company_id,
                GoogleSheetsDelivery.event_id == UUID(current_test.json()["event_id"]),
            )
        )
    ).scalar_one()
    current_connection_test.status = "delivered"
    current_connection_test.delivered_at = datetime.now(UTC)
    await session.commit()
    assert await session.scalar(
        select(GoogleSheetsDelivery.id).where(
            GoogleSheetsDelivery.id == after_rotation_before_test.id,
            _delivery_dispatchable_clause(),
        )
    ) == after_rotation_before_test.id

    replayed_original = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=original_source_id,
        source_revision="recorded-v1",
        occurred_at=original_occurred_at,
        payload=original_payload,
    )
    assert replayed_original is not None
    assert replayed_original.id == original_delivery_id
    assert replayed_original.configuration_id == original_configuration_id

    after_current_verification = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=str(uuid4()),
        source_revision="recorded-v1",
        occurred_at=datetime.now(UTC),
        payload={"amount_minor": -400, "status": "recorded"},
    )
    assert after_current_verification is not None
    await session.flush()
    business_event_ids = set(
        (
            await session.scalars(
                select(GoogleSheetsDelivery.id).where(
                    GoogleSheetsDelivery.company_id == company_id,
                    GoogleSheetsDelivery.event_type == "finance.expense.recorded",
                )
            )
        ).all()
    )
    assert business_event_ids == {
        original_delivery_id,
        after_rotation_before_test.id,
        after_current_verification.id,
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_unverified_correction_preserves_held_facts_and_requires_new_test(
    client,
    session,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    configured_a = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={
            "webhook_url": "https://script.google.com/macros/s/config-a/exec",
            "rotate_secret": False,
        },
    )
    assert configured_a.status_code == 200, configured_a.text
    company = await _fresh_company(session, company_id)
    generation_a = company.google_sheets_configuration_id
    ciphertext_a = company.google_sheets_signing_secret_ciphertext
    assert generation_a is not None
    assert ciphertext_a is not None

    queued_a = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued_a.status_code == 200, queued_a.text
    queued_a_event_id = UUID(queued_a.json()["event_id"])
    failed_a = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert failed_a.status_code == 200, failed_a.text
    failed_a_event_id = UUID(failed_a.json()["event_id"])
    test_a = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.event_id == queued_a_event_id
            )
        )
    ).scalar_one()
    test_a_id = test_a.id
    assert test_a.configuration_id == generation_a
    quarantined_a = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.event_id == failed_a_event_id
            )
        )
    ).scalar_one()
    quarantined_a.status = "quarantined"
    quarantined_a.quarantined_at = datetime.now(UTC)
    quarantined_a.quarantine_reason = "http_400: old configuration rejected"
    quarantined_a.last_attempt_at = datetime.now(UTC)
    quarantined_a.last_error_code = "http_400"
    quarantined_a.last_error_detail = "old configuration rejected"
    held_business = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=str(uuid4()),
        source_revision="recorded-v1",
        occurred_at=datetime.now(UTC),
        payload={"amount_minor": -100, "status": "recorded"},
    )
    assert held_business is not None
    assert held_business.configuration_id == generation_a
    held_business_id = held_business.id
    await session.commit()

    configured_b = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={
            "webhook_url": "https://script.google.com/macros/s/config-b/exec",
            "rotate_secret": True,
        },
    )
    assert configured_b.status_code == 200, configured_b.text
    assert isinstance(configured_b.json()["signing_secret"], str)
    company = await _fresh_company(session, company_id)
    generation_b = company.google_sheets_configuration_id
    assert generation_b is not None
    assert generation_b == generation_a
    assert company.google_sheets_signing_secret_ciphertext != ciphertext_a
    held_business = await session.get(GoogleSheetsDelivery, held_business_id)
    assert held_business is not None
    assert held_business.configuration_id == generation_b

    # Simulate A completing after the correction. Its pre-correction occurrence
    # time must not verify the corrected URL and fresh secret.
    test_a = await session.get(GoogleSheetsDelivery, test_a_id)
    assert test_a is not None
    test_a.status = "delivered"
    test_a.delivered_at = datetime.now(UTC)
    await session.commit()
    current_destination = await resolve_google_sheets_destination(
        session,
        company_id,
        generation_a,
    )
    assert current_destination is not None
    assert current_destination.webhook_url.endswith("/config-b/exec")
    # The resolver deliberately holds a shared company lock through a send.
    # Release this read-only probe before exercising the exclusive retry route.
    await session.rollback()
    status_b = await client.get("/api/v1/settings/google-sheets", headers=headers)
    assert status_b.status_code == 200, status_b.text
    assert status_b.json()["connection_verified"] is False
    assert status_b.json()["pending_count"] == 1
    assert status_b.json()["held_count"] == 1
    assert status_b.json()["quarantined_count"] == 1
    assert status_b.json()["last_error"] == "old configuration rejected"
    assert status_b.json()["delivered_count"] == 1

    retried = await client.post(
        "/api/v1/settings/google-sheets/retry-quarantined",
        headers=headers,
    )
    assert retried.status_code == 200, retried.text
    await session.refresh(quarantined_a)
    assert quarantined_a.status == "pending"
    assert quarantined_a.configuration_id == generation_a
    assert retried.json()["held_count"] == 1
    assert (
        await session.scalar(
            select(GoogleSheetsDelivery.id).where(
                GoogleSheetsDelivery.id == held_business_id,
                _delivery_dispatchable_clause(),
            )
        )
        is None
    )

    queued_b = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued_b.status_code == 200, queued_b.text
    test_b = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.event_id == UUID(queued_b.json()["event_id"])
            )
        )
    ).scalar_one()
    assert test_b.configuration_id == generation_b
    assert test_b.source_revision == str(generation_b)
    test_b.status = "delivered"
    test_b.delivered_at = datetime.now(UTC)
    await session.commit()

    verified_b = await client.get("/api/v1/settings/google-sheets", headers=headers)
    assert verified_b.status_code == 200, verified_b.text
    assert verified_b.json()["connection_verified"] is True
    assert verified_b.json()["pending_count"] == 2
    assert verified_b.json()["held_count"] == 0
    assert verified_b.json()["quarantined_count"] == 0
    assert verified_b.json()["last_error"] is None
    assert verified_b.json()["delivered_count"] == 2
    assert await session.scalar(
        select(GoogleSheetsDelivery.id).where(
            GoogleSheetsDelivery.id == held_business_id,
            _delivery_dispatchable_clause(),
        )
    ) == held_business_id


@pytest.mark.integration
@pytest.mark.asyncio
async def test_verified_configuration_change_waits_for_every_business_row_to_drain(
    client,
    session,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    original_url = "https://script.google.com/macros/s/drain-a/exec"
    replacement_url = "https://script.google.com/macros/s/drain-b/exec"
    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": original_url, "rotate_secret": False},
    )
    assert configured.status_code == 200, configured.text
    original_secret = configured.json()["signing_secret"]

    queued_test = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued_test.status_code == 200, queued_test.text
    test_row = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.event_id == UUID(queued_test.json()["event_id"])
            )
        )
    ).scalar_one()
    test_row.status = "delivered"
    test_row.delivered_at = datetime.now(UTC)
    await session.commit()

    rows: list[GoogleSheetsDelivery] = []
    for amount in (-100, -200, -300):
        row = await enqueue_google_sheets_event_if_enabled(
            session,
            company_id=company_id,
            event_type="finance.expense.recorded",
            source_type="expense",
            source_id=str(uuid4()),
            source_revision="recorded-v1",
            occurred_at=datetime.now(UTC),
            payload={"amount_minor": amount, "status": "recorded"},
        )
        assert row is not None
        rows.append(row)
    now = datetime.now(UTC)
    rows[1].status = "leased"
    rows[1].lease_owner = "drain-test-worker"
    rows[1].lease_expires_at = now + timedelta(minutes=5)
    rows[1].attempt_count = 1
    rows[1].last_attempt_at = now
    rows[2].status = "quarantined"
    rows[2].quarantined_at = now
    rows[2].quarantine_reason = "http_400: test rejection"
    rows[2].last_error_code = "http_400"
    rows[2].last_error_detail = "test rejection"
    await session.commit()

    company = await _fresh_company(session, company_id)
    original_generation = company.google_sheets_configuration_id
    original_ciphertext = company.google_sheets_signing_secret_ciphertext
    changed = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": replacement_url, "rotate_secret": False},
    )
    assert changed.status_code == 409, changed.text
    assert "Let waiting entries finish and retry failed entries" in changed.text
    assert changed.json()["error"]["details"]["undelivered_business_entries"] == {
        "pending": 1,
        "leased": 1,
        "quarantined": 1,
    }
    rotated = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": original_url, "rotate_secret": True},
    )
    assert rotated.status_code == 409, rotated.text
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_configuration_id == original_generation
    assert company.google_sheets_signing_secret_ciphertext == original_ciphertext
    assert company.google_sheets_webhook_url == original_url

    for row in rows:
        row.status = "delivered"
        row.lease_owner = None
        row.lease_expires_at = None
        row.quarantined_at = None
        row.quarantine_reason = None
        row.delivered_at = datetime.now(UTC)
    await session.commit()

    changed = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": replacement_url, "rotate_secret": False},
    )
    assert changed.status_code == 200, changed.text
    assert changed.json()["connection_verified"] is False
    assert isinstance(changed.json()["signing_secret"], str)
    assert changed.json()["signing_secret"] != original_secret
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_configuration_id != original_generation
    assert company.google_sheets_signing_secret_ciphertext != original_ciphertext
    assert company.google_sheets_webhook_url == replacement_url


@pytest.mark.integration
@pytest.mark.asyncio
async def test_enqueue_and_unverified_correction_serialize_on_company_configuration(
    client,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    tenant = TenantContext(
        user_id=seed_owner["owner"].id,
        company_id=company_id,
        branch_id=seed_owner["branch"].id,
        terminal_id=None,
        roles=("owner",),
        protected_access=True,
        audit_access=True,
    )
    first_url = "https://script.google.com/macros/s/concurrent-a/exec"
    second_url = "https://script.google.com/macros/s/concurrent-b/exec"
    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": first_url, "rotate_secret": False},
    )
    assert configured.status_code == 200, configured.text

    source_id = str(uuid4())
    configure_task: asyncio.Task | None = None
    async with AsyncSessionLocal() as enqueuer, AsyncSessionLocal() as observer:
        blocker_pid = await enqueuer.scalar(text("SELECT pg_backend_pid()"))
        assert isinstance(blocker_pid, int)
        delivery = await enqueue_google_sheets_event_if_enabled(
            enqueuer,
            company_id=company_id,
            event_type="finance.expense.recorded",
            source_type="expense",
            source_id=source_id,
            source_revision="recorded-v1",
            occurred_at=datetime.now(UTC),
            payload={"amount_minor": -500, "status": "recorded"},
        )
        assert delivery is not None
        original_generation = delivery.configuration_id

        async def configure_in_separate_transaction():
            async with AsyncSessionLocal() as configurer:
                result = await configure_google_sheets_mirror(
                    GoogleSheetsMirrorConfigure(
                        webhook_url=second_url,
                        rotate_secret=False,
                    ),
                    configurer,
                    tenant,
                )
                await configurer.commit()
                return result

        configure_task = asyncio.create_task(configure_in_separate_transaction())
        try:
            await _wait_for_company_lock_wait(observer, blocker_pid=blocker_pid)
            assert not configure_task.done()
            await enqueuer.commit()
            changed = await asyncio.wait_for(configure_task, timeout=5)
        finally:
            if configure_task is not None and not configure_task.done():
                configure_task.cancel()
                await asyncio.gather(configure_task, return_exceptions=True)
            await enqueuer.rollback()
            await observer.rollback()

    assert isinstance(changed.signing_secret, str)
    assert changed.connection_verified is False
    assert changed.held_count == 1
    async with AsyncSessionLocal() as verify:
        company = await verify.get(Company, company_id)
        assert company is not None
        assert company.google_sheets_configuration_id == original_generation
        assert company.google_sheets_webhook_url == second_url
        rows = (
            await verify.execute(
                select(GoogleSheetsDelivery).where(
                    GoogleSheetsDelivery.company_id == company_id,
                    GoogleSheetsDelivery.source_id == source_id,
                )
            )
        ).scalars().all()
        assert len(rows) == 1
        assert rows[0].configuration_id == original_generation
        assert rows[0].status == "pending"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_sheets_configure_rotate_and_disconnect_never_retain_secret_in_audit(
    client,
    session,
    seed_owner,
) -> None:
    # ASGITransport does not run the application lifespan hook. Production
    # installs this listener during startup; this test needs the same listener
    # to inspect the persisted append-only audit rows.
    install_audit_listeners()
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    url = "https://script.google.com/macros/s/audit-secret-redaction/exec"

    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "rotate_secret": False},
    )
    assert configured.status_code == 200, configured.text
    first_plaintext = configured.json()["signing_secret"]
    assert isinstance(first_plaintext, str)
    company = await _fresh_company(session, company_id)
    first_ciphertext = company.google_sheets_signing_secret_ciphertext
    assert isinstance(first_ciphertext, str)

    audit_rows = await _company_audit_rows(session, company_id)
    configured_audit = audit_rows[-1]
    configured_audit_id = configured_audit.id
    _assert_sheets_secret_absent_from_audit(
        configured_audit,
        forbidden_values=(first_plaintext, first_ciphertext),
    )

    rotated = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "rotate_secret": True},
    )
    assert rotated.status_code == 200, rotated.text
    rotated_plaintext = rotated.json()["signing_secret"]
    assert isinstance(rotated_plaintext, str)
    company = await _fresh_company(session, company_id)
    rotated_ciphertext = company.google_sheets_signing_secret_ciphertext
    assert isinstance(rotated_ciphertext, str)
    assert rotated_ciphertext != first_ciphertext

    audit_rows = await _company_audit_rows(session, company_id)
    rotated_audit = audit_rows[-1]
    rotated_audit_id = rotated_audit.id
    assert rotated_audit_id > configured_audit_id
    _assert_sheets_secret_absent_from_audit(
        rotated_audit,
        forbidden_values=(
            first_plaintext,
            first_ciphertext,
            rotated_plaintext,
            rotated_ciphertext,
        ),
    )

    disconnected = await client.delete(
        "/api/v1/settings/google-sheets",
        headers=headers,
    )
    assert disconnected.status_code == 200, disconnected.text
    company = await _fresh_company(session, company_id)
    assert company.google_sheets_signing_secret_ciphertext is None

    audit_rows = await _company_audit_rows(session, company_id)
    disconnected_audit = audit_rows[-1]
    assert disconnected_audit.id > rotated_audit_id
    _assert_sheets_secret_absent_from_audit(
        disconnected_audit,
        forbidden_values=(
            first_plaintext,
            first_ciphertext,
            rotated_plaintext,
            rotated_ciphertext,
        ),
    )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_sheets_configuration_rejects_unsafe_destinations_and_non_owner(
    client,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    for unsafe in (
        "http://script.google.com/macros/s/test/exec",
        "https://example.com/macros/s/test/exec",
        "https://script.google.com:444/macros/s/test/exec",
        "https://user@script.google.com/macros/s/test/exec",
    ):
        response = await client.post(
            "/api/v1/settings/google-sheets/configure",
            headers=headers,
            json={"webhook_url": unsafe},
        )
        assert response.status_code == 422, (unsafe, response.text)

    denied = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=_headers(seed_owner, roles=["cashier"]),
        json={
            "webhook_url": "https://script.google.com/macros/s/test/exec",
        },
    )
    assert denied.status_code == 403, denied.text

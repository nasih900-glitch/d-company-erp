"""Owner controls for the authenticated, durable Google Sheets mirror."""

from __future__ import annotations

import secrets
from datetime import UTC, datetime
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import func, select, update

from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves this at runtime
from app.core.errors import ConflictError, NotFoundError, ValidationError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Company
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations.google_sheets_mirror import (
    CONNECTION_TEST_EVENT_TYPE,
    CONNECTION_TEST_SOURCE_TYPE,
    DestinationValidationError,
    DispatcherConfig,
    enqueue_google_sheets_event,
    validate_destination_url,
)
from app.services.integrations.google_sheets_secrets import (
    encrypt_google_sheets_signing_secret,
)

router = APIRouter()
SettingsManager = Annotated[
    TenantContext,
    Depends(requires("settings.manage")),
]


class GoogleSheetsMirrorConfigure(BaseModel):
    webhook_url: str = Field(min_length=1, max_length=500)
    rotate_secret: bool = False


class GoogleSheetsMirrorStatus(BaseModel):
    enabled: bool
    connection_verified: bool
    webhook_url: str | None
    configured_at: datetime | None
    secret_configured: bool
    pending_count: int
    held_count: int
    quarantined_count: int
    delivered_count: int
    last_delivered_at: datetime | None
    last_error: str | None


class GoogleSheetsMirrorConfigureResult(GoogleSheetsMirrorStatus):
    # Returned only when first configured, explicitly rotated, or moved to a
    # different URL. The ordinary status route never exposes it.
    signing_secret: str | None = None


class GoogleSheetsMirrorTestResult(BaseModel):
    event_id: UUID
    status: str
    message: str


async def _company_or_404(
    session: SessionDep,
    company_id: UUID,
    *,
    lock: bool = True,
) -> Company:
    company = await session.get(Company, company_id, with_for_update=lock)
    if company is None:
        raise NotFoundError("company not found")
    return company


async def _mirror_status(
    session: SessionDep,
    company: Company,
) -> GoogleSheetsMirrorStatus:
    current_configuration_id = company.google_sheets_configuration_id
    count_rows = []
    if current_configuration_id is not None:
        count_rows = (
            await session.execute(
                select(
                    GoogleSheetsDelivery.status,
                    func.count(GoogleSheetsDelivery.id),
                )
                .where(
                    GoogleSheetsDelivery.company_id == company.id,
                    GoogleSheetsDelivery.configuration_id
                    == current_configuration_id,
                )
                .group_by(GoogleSheetsDelivery.status)
            )
        ).all()
    counts: dict[str, int] = {
        status: int(count) for status, count in count_rows
    }
    delivered_count = await session.scalar(
        select(func.count(GoogleSheetsDelivery.id)).where(
            GoogleSheetsDelivery.company_id == company.id,
            GoogleSheetsDelivery.status == "delivered",
        )
    )
    last_delivered_at = None
    if current_configuration_id is not None:
        last_delivered_at = await session.scalar(
            select(func.max(GoogleSheetsDelivery.delivered_at)).where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.configuration_id
                == current_configuration_id,
                GoogleSheetsDelivery.status == "delivered",
            )
        )
    last_connection_test_at = None
    if (
        current_configuration_id is not None
        and company.google_sheets_configured_at is not None
    ):
        last_connection_test_at = await session.scalar(
            select(func.max(GoogleSheetsDelivery.delivered_at)).where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.configuration_id
                == current_configuration_id,
                GoogleSheetsDelivery.event_type == CONNECTION_TEST_EVENT_TYPE,
                GoogleSheetsDelivery.source_type == CONNECTION_TEST_SOURCE_TYPE,
                GoogleSheetsDelivery.status == "delivered",
                GoogleSheetsDelivery.occurred_at
                >= company.google_sheets_configured_at,
                GoogleSheetsDelivery.delivered_at
                >= company.google_sheets_configured_at,
            )
        )
    last_failure = None
    if current_configuration_id is not None:
        last_failure = (
            await session.execute(
                select(GoogleSheetsDelivery.last_error_detail)
                .where(
                    GoogleSheetsDelivery.company_id == company.id,
                    GoogleSheetsDelivery.configuration_id
                    == current_configuration_id,
                    GoogleSheetsDelivery.last_error_detail.is_not(None),
                )
                .order_by(GoogleSheetsDelivery.last_attempt_at.desc().nullslast())
                .limit(1)
            )
        ).scalar_one_or_none()
    connection_verified = bool(
        company.google_sheets_mirror_enabled
        and company.google_sheets_configuration_id is not None
        and company.google_sheets_configured_at is not None
        and last_connection_test_at is not None
    )
    held_count = 0
    if current_configuration_id is not None and not connection_verified:
        held_count = int(
            await session.scalar(
                select(func.count(GoogleSheetsDelivery.id)).where(
                    GoogleSheetsDelivery.company_id == company.id,
                    GoogleSheetsDelivery.configuration_id
                    == current_configuration_id,
                    GoogleSheetsDelivery.status.in_(("pending", "leased")),
                    ~(
                        (
                            GoogleSheetsDelivery.event_type
                            == CONNECTION_TEST_EVENT_TYPE
                        )
                        & (
                            GoogleSheetsDelivery.source_type
                            == CONNECTION_TEST_SOURCE_TYPE
                        )
                    ),
                )
            )
            or 0
        )
    return GoogleSheetsMirrorStatus(
        enabled=company.google_sheets_mirror_enabled,
        connection_verified=connection_verified,
        webhook_url=company.google_sheets_webhook_url,
        configured_at=company.google_sheets_configured_at,
        secret_configured=bool(company.google_sheets_signing_secret_ciphertext),
        pending_count=int(counts.get("pending", 0)) + int(counts.get("leased", 0)),
        held_count=held_count,
        quarantined_count=int(counts.get("quarantined", 0)),
        delivered_count=int(delivered_count or 0),
        last_delivered_at=last_delivered_at,
        last_error=last_failure,
    )


async def _configuration_is_verified(
    session: SessionDep,
    company: Company,
) -> bool:
    if (
        not company.google_sheets_mirror_enabled
        or company.google_sheets_configuration_id is None
        or company.google_sheets_configured_at is None
    ):
        return False
    verified_id = await session.scalar(
        select(GoogleSheetsDelivery.id)
        .where(
            GoogleSheetsDelivery.company_id == company.id,
            GoogleSheetsDelivery.configuration_id
            == company.google_sheets_configuration_id,
            GoogleSheetsDelivery.event_type == CONNECTION_TEST_EVENT_TYPE,
            GoogleSheetsDelivery.source_type == CONNECTION_TEST_SOURCE_TYPE,
            GoogleSheetsDelivery.status == "delivered",
            GoogleSheetsDelivery.occurred_at >= company.google_sheets_configured_at,
            GoogleSheetsDelivery.delivered_at >= company.google_sheets_configured_at,
        )
        .limit(1)
    )
    return verified_id is not None


async def _undelivered_business_counts(
    session: SessionDep,
    company: Company,
) -> dict[str, int]:
    if company.google_sheets_configuration_id is None:
        return {"pending": 0, "leased": 0, "quarantined": 0}
    rows = (
        await session.execute(
            select(
                GoogleSheetsDelivery.status,
                func.count(GoogleSheetsDelivery.id),
            )
            .where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.configuration_id
                == company.google_sheets_configuration_id,
                GoogleSheetsDelivery.status.in_(("pending", "leased", "quarantined")),
                ~(
                    (
                        GoogleSheetsDelivery.event_type
                        == CONNECTION_TEST_EVENT_TYPE
                    )
                    & (
                        GoogleSheetsDelivery.source_type
                        == CONNECTION_TEST_SOURCE_TYPE
                    )
                ),
            )
            .group_by(GoogleSheetsDelivery.status)
        )
    ).all()
    counts = {"pending": 0, "leased": 0, "quarantined": 0}
    counts.update({status: int(count) for status, count in rows})
    return counts


def _configuration_change_blocked_message(counts: dict[str, int]) -> str:
    total = sum(counts.values())
    return (
        f"Google Sheets has {total} undelivered business "
        f"{'entry' if total == 1 else 'entries'} in the current verified connection. "
        "Let waiting entries finish and retry failed entries before changing or "
        "disconnecting this connection."
    )


@router.get("/google-sheets", response_model=GoogleSheetsMirrorStatus)
async def get_google_sheets_mirror(
    session: SessionDep,
    tenant: SettingsManager,
) -> GoogleSheetsMirrorStatus:
    company = await _company_or_404(session, tenant.company_id, lock=False)
    return await _mirror_status(session, company)


@router.post("/google-sheets/configure", response_model=GoogleSheetsMirrorConfigureResult)
async def configure_google_sheets_mirror(
    payload: GoogleSheetsMirrorConfigure,
    session: SessionDep,
    tenant: SettingsManager,
) -> GoogleSheetsMirrorConfigureResult:
    try:
        url = validate_destination_url(
            payload.webhook_url.strip(),
            allowed_hosts=DispatcherConfig().allowed_hosts,
        )
    except DestinationValidationError as exc:
        raise ValidationError(str(exc)) from exc
    company = await _company_or_404(session, tenant.company_id)
    generated_secret: str | None = None
    destination_changed = company.google_sheets_webhook_url != url
    secret_changed = (
        destination_changed
        or payload.rotate_secret
        or not company.google_sheets_signing_secret_ciphertext
    )
    material_change = (
        destination_changed
        or secret_changed
        or not company.google_sheets_mirror_enabled
        or company.google_sheets_configured_at is None
        or company.google_sheets_configuration_id is None
    )
    was_verified = await _configuration_is_verified(session, company)
    if material_change and was_verified:
        outstanding = await _undelivered_business_counts(session, company)
        if sum(outstanding.values()):
            raise ConflictError(
                _configuration_change_blocked_message(outstanding),
                details={"undelivered_business_entries": outstanding},
            )
    if secret_changed:
        generated_secret = secrets.token_urlsafe(48)
        company.google_sheets_signing_secret_ciphertext = (
            encrypt_google_sheets_signing_secret(
                company_id=company.id,
                secret=generated_secret,
            )
        )
    company.google_sheets_webhook_url = url
    company.google_sheets_mirror_enabled = True
    if material_change:
        # A successful test verifies one exact destination/secret generation.
        # Before the first successful test, corrections keep the same generation
        # so already-held business facts remain durable. Once verified, a
        # drained generation is closed and the replacement gets a fresh ID.
        if company.google_sheets_configuration_id is None or was_verified:
            company.google_sheets_configuration_id = uuid4()
        company.google_sheets_configured_at = datetime.now(UTC)
    await session.flush()
    status = await _mirror_status(session, company)
    return GoogleSheetsMirrorConfigureResult(
        **status.model_dump(),
        signing_secret=generated_secret,
    )


@router.post("/google-sheets/test", response_model=GoogleSheetsMirrorTestResult)
async def queue_google_sheets_connection_check(
    session: SessionDep,
    tenant: SettingsManager,
) -> GoogleSheetsMirrorTestResult:
    company = await _company_or_404(session, tenant.company_id)
    if (
        not company.google_sheets_mirror_enabled
        or company.google_sheets_configuration_id is None
        or company.google_sheets_configured_at is None
    ):
        return GoogleSheetsMirrorTestResult(
            event_id=uuid4(),
            status="disabled",
            message="Configure and enable the Google Sheets mirror first.",
        )
    source_id = str(uuid4())
    delivery = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=company.google_sheets_configuration_id,
        event_type=CONNECTION_TEST_EVENT_TYPE,
        source_type=CONNECTION_TEST_SOURCE_TYPE,
        source_id=source_id,
        source_revision=str(company.google_sheets_configuration_id),
        occurred_at=datetime.now(UTC),
        payload={
            "currency": company.currency,
            "description": "D Company ERP mirror connection test",
            "status": "test",
        },
    )
    return GoogleSheetsMirrorTestResult(
        event_id=delivery.event_id,
        status="pending",
        message="Test queued. The status will update after the server contacts Google Sheets.",
    )


@router.post("/google-sheets/retry-quarantined", response_model=GoogleSheetsMirrorStatus)
async def retry_quarantined_google_sheets_events(
    session: SessionDep,
    tenant: SettingsManager,
) -> GoogleSheetsMirrorStatus:
    company = await _company_or_404(session, tenant.company_id)
    if company.google_sheets_configuration_id is not None:
        await session.execute(
            update(GoogleSheetsDelivery)
            .where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.configuration_id
                == company.google_sheets_configuration_id,
                GoogleSheetsDelivery.status == "quarantined",
            )
            .values(
                status="pending",
                available_at=datetime.now(UTC),
                attempt_count=0,
                quarantined_at=None,
                quarantine_reason=None,
                last_error_code=None,
                last_error_detail=None,
            )
        )
    await session.flush()
    return await _mirror_status(session, company)


@router.delete("/google-sheets", response_model=GoogleSheetsMirrorStatus)
async def disconnect_google_sheets_mirror(
    session: SessionDep,
    tenant: SettingsManager,
) -> GoogleSheetsMirrorStatus:
    company = await _company_or_404(session, tenant.company_id)
    outstanding = await _undelivered_business_counts(session, company)
    if sum(outstanding.values()):
        raise ConflictError(
            _configuration_change_blocked_message(outstanding),
            details={"undelivered_business_entries": outstanding},
        )
    company.google_sheets_mirror_enabled = False
    company.google_sheets_webhook_url = None
    company.google_sheets_signing_secret_ciphertext = None
    company.google_sheets_configuration_id = None
    company.google_sheets_configured_at = None
    await session.flush()
    return await _mirror_status(session, company)


__all__ = ["router"]

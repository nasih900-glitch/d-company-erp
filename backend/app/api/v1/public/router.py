"""Public (unauthenticated) endpoints.

These power the public-facing pages — QR-coded menu at the table,
event detail pages, future bookings flow. Read-only and scoped to a
single hard-coded company because the URL is the only identifier.

If you ever run multi-company, swap the company lookup to use a path
prefix like /public/{company_slug}/menu.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Literal, TypedDict
from uuid import UUID  # noqa: TC003 - Pydantic resolves this model type at runtime.
from weakref import WeakKeyDictionary

from fastapi import APIRouter, Query, Request, Response
from pydantic import BaseModel
from sqlalchemy import select

from app.core.config import get_settings
from app.core.db import AsyncSessionLocal, SessionDep  # noqa: TC001 - FastAPI dependency.
from app.core.logging import get_logger
from app.models import AndroidRelease, Company, MenuCategory, MenuItem
from app.services.client_updates.runtime_parity import (
    RuntimeParityError,
    verify_runtime_parity_for_public_offer,
)

router = APIRouter()
logger = get_logger(__name__)

if TYPE_CHECKING:
    from app.core.config import Settings

# Code 21's immutable client deadline is three seconds for the complete public
# request.  Keep optional release lookup + runtime verification inside this
# smaller server budget, reserving proxy, TLS, middleware and serialization
# headroom.  Timeout fails closed to "supported"; it never advertises an
# unverified artifact or blocks normal offline-capable operation.
PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS = 1.75


# ---------------------------------------------------------------- DTOs
class PublicItemDTO(BaseModel):
    id: UUID
    sku: str
    name: str
    type: str
    base_price_minor: int
    tax_rate: float
    description: str | None
    category_id: UUID
    category_name: str
    category_sort: int


class PublicMenuDTO(BaseModel):
    company_name: str
    company_gstin: str | None
    categories: list[dict]
    items: list[PublicItemDTO]


class ClientCompatibilityDTO(BaseModel):
    platform: Literal["android", "ios"]
    current_version_code: int
    policy_revision: int
    minimum_supported_version_code: int
    latest_version_code: int
    status: Literal["supported", "update_available", "update_required"]
    update_url: str | None
    latest_version_name: str | None = None
    release_notes: str | None = None
    apk_sha256: str | None = None
    apk_size_bytes: int | None = None
    apk_signing_cert_sha256: str | None = None
    message: str
    checked_at: datetime


class _AndroidOffer(TypedDict):
    version_code: int
    version_name: str
    source_git_sha: str
    update_url: str
    release_notes: str
    apk_sha256: str
    apk_size_bytes: int
    apk_signing_cert_sha256: str


@dataclass(slots=True)
class _PublicOfferLookupState:
    """One shared optional-offer lookup per worker event loop.

    The endpoint is unauthenticated and polled by every direct-install tablet.
    Sharing one task prevents a slow database or identity endpoint from turning
    those polls into unbounded database work.
    """

    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    in_flight: asyncio.Task[_AndroidOffer | None] | None = None


_public_offer_lookup_states: WeakKeyDictionary[
    asyncio.AbstractEventLoop, _PublicOfferLookupState
] = WeakKeyDictionary()


def _public_offer_lookup_state() -> _PublicOfferLookupState:
    loop = asyncio.get_running_loop()
    state = _public_offer_lookup_states.get(loop)
    if state is None:
        state = _PublicOfferLookupState()
        _public_offer_lookup_states[loop] = state
    return state


async def _load_verified_active_direct_offer(settings: Settings) -> _AndroidOffer | None:
    """Read, verify, and recheck an offer outside the client request session.

    Database cleanup is deliberately owned by a detached, deduplicated task.
    A broken pool or connection can therefore suppress an optional offer, but
    it cannot hold an immutable Code 21 compatibility request past its three-
    second client deadline.
    """
    async with AsyncSessionLocal() as session:
        active_release = (
            await session.execute(
                select(AndroidRelease).where(
                    AndroidRelease.channel == "direct",
                    AndroidRelease.status == "active",
                )
            )
        ).scalar_one_or_none()
        if (
            active_release is None
            or active_release.version_code < settings.android_min_supported_version_code
        ):
            await session.rollback()
            return None

        # An active DB row survives deployment rollbacks. It is not enough to
        # prove the currently served web/backend can support this APK. Copy the
        # immutable offer, then close the read session before network I/O.
        offer = _AndroidOffer(
            version_code=active_release.version_code,
            version_name=active_release.version_name,
            source_git_sha=active_release.source_git_sha,
            update_url=active_release.update_url,
            release_notes=active_release.release_notes,
            apk_sha256=active_release.apk_sha256,
            apk_size_bytes=active_release.apk_size_bytes,
            apk_signing_cert_sha256=active_release.apk_signing_cert_sha256,
        )
        offer_id = active_release.id
        await session.rollback()

    try:
        await verify_runtime_parity_for_public_offer(
            version_name=offer["version_name"],
            source_git_sha=offer["source_git_sha"],
            settings=settings,
        )
    except RuntimeParityError:
        return None

    # Withdrawal/promotion may have happened during identity I/O. Do not
    # resurrect an offer whose owner just withdrew it.
    async with AsyncSessionLocal() as session:
        still_active = (
            await session.execute(
                select(AndroidRelease.id).where(
                    AndroidRelease.id == offer_id,
                    AndroidRelease.status == "active",
                )
            )
        ).scalar_one_or_none()
        await session.rollback()
    return offer if still_active is not None else None


async def _run_public_offer_lookup(
    state: _PublicOfferLookupState,
    settings: Settings,
) -> _AndroidOffer | None:
    try:
        return await _load_verified_active_direct_offer(settings)
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001 - an optional offer must fail closed
        logger.warning(
            "android_update.public_offer_lookup_failed",
            error_type=type(exc).__name__,
        )
        return None
    finally:
        async with state.lock:
            if state.in_flight is asyncio.current_task():
                state.in_flight = None


async def _shared_verified_active_direct_offer(settings: Settings) -> _AndroidOffer | None:
    state = _public_offer_lookup_state()
    async with state.lock:
        task = state.in_flight
        if task is None:
            task = asyncio.create_task(
                _run_public_offer_lookup(state, settings),
                name="public-android-offer-lookup",
            )
            state.in_flight = task

    # A timed-out/disconnected legacy caller must never cancel the shared work
    # or make another poll open a second database lookup.
    return await asyncio.shield(task)


def _reset_public_offer_lookup_state_for_tests() -> None:
    """Cancel and discard process-local lookup state between isolated tests."""
    for state in tuple(_public_offer_lookup_states.values()):
        task = state.in_flight
        if task is not None and not task.done():
            task.cancel()
    _public_offer_lookup_states.clear()


# ---------------------------------------------------------------- endpoints
@router.get("/client-compatibility", response_model=ClientCompatibilityDTO)
async def client_compatibility(
    request: Request,
    response: Response,
    platform: Literal["android", "ios"],
    version_code: int = Query(ge=1),
) -> ClientCompatibilityDTO:
    """Return required policy plus the independently promoted optional offer.

    The environment minimum remains the required-update authority used by the
    426 middleware.  For supported Android clients, only a verified active DB
    release is an optional offer; staging a row never advertises it.
    """
    response.headers["Cache-Control"] = "no-store"
    settings = get_settings()
    response.headers["X-Client-Compatibility-Policy-Revision"] = str(
        settings.client_compatibility_policy_revision
    )
    latest: int
    update_url: str | None
    latest_version_name: str | None
    release_notes: str | None
    apk_sha256: str | None
    apk_size_bytes: int | None
    apk_signing_cert_sha256: str | None
    if platform == "android":
        minimum = settings.android_min_supported_version_code
        # Missing means the existing direct-APK client/monitor contract. Play
        # and managed builds must never be handed our self-hosted APK, even if
        # they happen to share the same version code.
        distribution_channel = (
            request.headers.get("X-Client-Distribution-Channel", "direct").strip().lower()
            or "direct"
        )
        offer = None
        if distribution_channel == "direct":
            try:
                async with asyncio.timeout(PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS):
                    offer = await _shared_verified_active_direct_offer(settings)
            except TimeoutError:
                offer = None
        if offer is not None:
            latest = offer["version_code"]
            update_url = offer["update_url"]
            latest_version_name = offer["version_name"]
            release_notes = offer["release_notes"]
            apk_sha256 = offer["apk_sha256"]
            apk_size_bytes = offer["apk_size_bytes"]
            apk_signing_cert_sha256 = offer["apk_signing_cert_sha256"]
        elif version_code < minimum and distribution_channel == "direct":
            # Required-update recovery remains deploy-time policy so an owner
            # cannot accidentally strand an already-blocked client by
            # withdrawing the optional offer.
            latest = max(minimum, settings.android_latest_version_code)
            update_url = str(settings.android_update_url) if settings.android_update_url else None
            latest_version_name = settings.android_latest_version_name
            release_notes = settings.android_update_release_notes
            apk_sha256 = settings.android_update_apk_sha256
            apk_size_bytes = settings.android_update_apk_size_bytes
            apk_signing_cert_sha256 = settings.android_update_signing_cert_sha256
        else:
            latest = minimum
            update_url = None
            latest_version_name = None
            release_notes = None
            apk_sha256 = None
            apk_size_bytes = None
            apk_signing_cert_sha256 = None
    else:
        minimum = settings.ios_min_supported_version_code
        latest = settings.ios_latest_version_code
        update_url = str(settings.ios_update_url) if settings.ios_update_url else None
        latest_version_name = None
        release_notes = None
        apk_sha256 = None
        apk_size_bytes = None
        apk_signing_cert_sha256 = None

    if version_code < minimum:
        compatibility_status = "update_required"
        default_message = (
            "This app version is no longer compatible with the ERP server. "
            "Update before continuing; saved offline work will remain on this device."
        )
    elif (
        platform == "android" and update_url is not None and version_code < latest
    ) or (platform == "ios" and version_code < latest):
        compatibility_status = "update_available"
        default_message = "A newer app version is available. You can continue for now."
    else:
        compatibility_status = "supported"
        default_message = "This app version is supported."

    return ClientCompatibilityDTO(
        platform=platform,
        current_version_code=version_code,
        policy_revision=settings.client_compatibility_policy_revision,
        minimum_supported_version_code=minimum,
        latest_version_code=latest,
        status=compatibility_status,
        update_url=update_url,
        latest_version_name=latest_version_name,
        release_notes=release_notes,
        apk_sha256=apk_sha256,
        apk_size_bytes=apk_size_bytes,
        apk_signing_cert_sha256=apk_signing_cert_sha256,
        message=(
            default_message
            if compatibility_status == "supported"
            else settings.client_update_message or default_message
        ),
        checked_at=datetime.now(UTC),
    )


@router.get("/menu", response_model=PublicMenuDTO)
async def public_menu(session: SessionDep) -> PublicMenuDTO:
    """Public read-only menu — what QR-at-the-table customers see.

    Filters to one company (the only one in this deployment). Hides
    items with is_available=False and items in deleted categories.
    """
    company = (
        await session.execute(select(Company).where(Company.deleted_at.is_(None)).limit(1))
    ).scalar_one_or_none()
    if not company:
        return PublicMenuDTO(company_name="D Company", company_gstin=None, categories=[], items=[])

    cats = (
        (
            await session.execute(
                select(MenuCategory)
                .where(
                    MenuCategory.company_id == company.id,
                    MenuCategory.deleted_at.is_(None),
                )
                .order_by(MenuCategory.sort_order)
            )
        )
        .scalars()
        .all()
    )
    cat_meta = {c.id: (c.name, c.sort_order) for c in cats}

    items = (
        (
            await session.execute(
                select(MenuItem)
                .where(
                    MenuItem.company_id == company.id,
                    MenuItem.deleted_at.is_(None),
                    MenuItem.is_available.is_(True),
                )
                .order_by(MenuItem.name)
            )
        )
        .scalars()
        .all()
    )

    out_items: list[PublicItemDTO] = []
    for it in items:
        cm = cat_meta.get(it.category_id)
        if not cm:
            continue
        out_items.append(
            PublicItemDTO(
                id=it.id,
                sku=it.sku,
                name=it.name,
                type=it.type,
                base_price_minor=it.base_price_minor,
                tax_rate=float(it.tax_rate),
                description=it.description,
                category_id=it.category_id,
                category_name=cm[0],
                category_sort=cm[1],
            )
        )

    return PublicMenuDTO(
        company_name=company.name,
        company_gstin=company.gstin,
        categories=[{"id": str(c.id), "name": c.name, "sort_order": c.sort_order} for c in cats],
        items=out_items,
    )

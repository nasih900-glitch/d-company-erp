"""Public (unauthenticated) endpoints.

These power the public-facing pages — QR-coded menu at the table,
event detail pages, future bookings flow. Read-only and scoped to a
single hard-coded company because the URL is the only identifier.

If you ever run multi-company, swap the company lookup to use a path
prefix like /public/{company_slug}/menu.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Query, Request, Response
from pydantic import BaseModel
from sqlalchemy import select

from app.core.config import get_settings
from app.core.db import SessionDep
from app.models import AndroidRelease, Company, MenuCategory, MenuItem

router = APIRouter()


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


# ---------------------------------------------------------------- endpoints
@router.get("/client-compatibility", response_model=ClientCompatibilityDTO)
async def client_compatibility(
    request: Request,
    response: Response,
    session: SessionDep,
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
    if platform == "android":
        minimum = settings.android_min_supported_version_code
        # Missing means the existing direct-APK client/monitor contract. Play
        # and managed builds must never be handed our self-hosted APK, even if
        # they happen to share the same version code.
        distribution_channel = (
            request.headers.get("X-Client-Distribution-Channel", "direct").strip().lower()
            or "direct"
        )
        active_release = None
        if distribution_channel == "direct":
            active_release = (
                await session.execute(
                    select(AndroidRelease).where(
                        AndroidRelease.channel == "direct",
                        AndroidRelease.status == "active",
                    )
                )
            ).scalar_one_or_none()
        if active_release is not None and active_release.version_code >= minimum:
            latest = active_release.version_code
            update_url = active_release.update_url
            latest_version_name = active_release.version_name
            release_notes = active_release.release_notes
            apk_sha256 = active_release.apk_sha256
            apk_size_bytes = active_release.apk_size_bytes
            apk_signing_cert_sha256 = active_release.apk_signing_cert_sha256
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
    elif platform == "android" and update_url is not None and version_code < latest:
        compatibility_status = "update_available"
        default_message = "A newer app version is available. You can continue for now."
    elif platform == "ios" and version_code < latest:
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

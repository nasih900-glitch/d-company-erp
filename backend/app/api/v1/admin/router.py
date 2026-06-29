"""Admin endpoints — audit log read, system info."""

from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Request
from pydantic import BaseModel
from sqlalchemy import String, cast, distinct, or_, select

from app.core.db import SessionDep
from app.core.errors import AuthError
from app.core.permissions import requires
from app.core.security import decode_token, issue_audit_token, issue_pricing_token, verify_password
from app.core.tenant import TenantContext
from app.models import AuditLog, User

router = APIRouter()


class AuditEntry(BaseModel):
    id: int
    actor_user_id: UUID | None
    actor_name: str | None
    actor_email: str | None
    action: str
    entity_type: str
    entity_id: str
    before: dict[str, Any] | None
    after: dict[str, Any] | None
    ip: str | None
    user_agent: str | None
    created_at: datetime


class AuditFacetsDTO(BaseModel):
    entity_types: list[str]
    actions: list[str]


class AuditUnlockRequest(BaseModel):
    password: str


class AuditUnlockResponse(BaseModel):
    audit_token: str
    expires_in: int


class PricingUnlockResponse(BaseModel):
    pricing_token: str
    expires_in: int


AUDIT_AREA_ENTITY_TYPES: dict[str, tuple[str, ...]] = {
    "pos": ("Order", "OrderLine", "Payment", "Refund", "Shift"),
    "customers": ("Customer", "CustomerMembership", "MembershipTier"),
    "staff": ("User", "UserRole", "Role"),
    "inventory": ("Ingredient", "Supplier", "GRN"),
    "finance": ("Expense", "ExpenseCategory", "Partner", "CapitalEntry"),
    "menu": ("MenuCategory", "MenuItem"),
    "operations": ("Table", "Floor", "Reservation", "Station", "Event", "EventTicket"),
    "system": ("AuditAccess", "PricingAccess"),
}


def _apply_audit_area_filter(stmt: Any, area: str | None) -> Any:
    if not area:
        return stmt
    normalized = area.strip().lower()
    if normalized == "login":
        return stmt.where(AuditLog.action.like("login_%"))
    if normalized == "changes":
        return stmt.where(AuditLog.action.in_(("create", "update", "delete")))
    entity_types = AUDIT_AREA_ENTITY_TYPES.get(normalized)
    if entity_types:
        return stmt.where(AuditLog.entity_type.in_(entity_types))
    return stmt


def _request_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.client.host if request.client else None


def _audit_security_event(
    *,
    session: SessionDep,
    request: Request,
    tenant: TenantContext,
    action: str,
    details: dict[str, Any] | None = None,
    entity_type: str = "AuditAccess",
) -> None:
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action=action,
            entity_type=entity_type,
            entity_id=str(tenant.user_id),
            before=None,
            after={"result": action, **(details or {})},
            ip=_request_ip(request),
            user_agent=request.headers.get("user-agent"),
        )
    )


def _require_audit_unlock(x_audit_token: str | None, tenant: TenantContext) -> None:
    if not x_audit_token:
        raise AuthError("audit password unlock required")
    try:
        claims = decode_token(x_audit_token)
    except ValueError as exc:
        raise AuthError("audit password unlock expired or invalid") from exc
    if (
        claims.get("type") != "audit"
        or claims.get("scope") != "admin.audit.read"
        or claims.get("sub") != str(tenant.user_id)
        or claims.get("company_id") != str(tenant.company_id)
    ):
        raise AuthError("audit password unlock expired or invalid")


@router.post("/audit/unlock", response_model=AuditUnlockResponse)
async def unlock_audit(
    payload: AuditUnlockRequest,
    request: Request,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> AuditUnlockResponse:
    """Require the current user's password before audit data can be read."""
    user = (
        await session.execute(select(User).where(User.id == tenant.user_id))
    ).scalar_one_or_none()
    if not user or user.deleted_at:
        raise AuthError("user not found")

    if not verify_password(payload.password, user.password_hash):
        _audit_security_event(
            session=session,
            request=request,
            tenant=tenant,
            action="audit_unlock_failed",
        )
        await session.commit()
        raise AuthError("invalid audit password")

    _audit_security_event(
        session=session,
        request=request,
        tenant=tenant,
        action="audit_unlock_success",
    )
    return AuditUnlockResponse(
        audit_token=issue_audit_token(user_id=user.id, company_id=user.company_id),
        expires_in=10 * 60,
    )


@router.post("/pricing/unlock", response_model=PricingUnlockResponse)
async def unlock_pricing(
    payload: AuditUnlockRequest,
    request: Request,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> PricingUnlockResponse:
    """Require the current user's password before pricing can be changed."""
    user = (
        await session.execute(select(User).where(User.id == tenant.user_id))
    ).scalar_one_or_none()
    if not user or user.deleted_at:
        raise AuthError("user not found")

    if not verify_password(payload.password, user.password_hash):
        _audit_security_event(
            session=session,
            request=request,
            tenant=tenant,
            action="pricing_unlock_failed",
            entity_type="PricingAccess",
        )
        await session.commit()
        raise AuthError("invalid pricing password")

    _audit_security_event(
        session=session,
        request=request,
        tenant=tenant,
        action="pricing_unlock_success",
        entity_type="PricingAccess",
    )
    return PricingUnlockResponse(
        pricing_token=issue_pricing_token(user_id=user.id, company_id=user.company_id),
        expires_in=10 * 60,
    )


@router.get("/audit", response_model=list[AuditEntry])
async def list_audit(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
    x_audit_token: str | None = Header(default=None, alias="X-Audit-Token"),
    limit: int = 100,
    entity_type: str | None = None,
    action: str | None = None,
    actor_user_id: UUID | None = None,
    entity_id: str | None = None,
    area: str | None = None,
    q: str | None = None,
) -> list[AuditEntry]:
    """List audit log entries newest-first, scoped to this company.

    Filters: entity_type (e.g. 'Order'), action (create/update/delete),
    actor_user_id, entity_id. `q` does a substring match against any string
    in the before/after JSON.
    """
    _require_audit_unlock(x_audit_token, tenant)

    stmt = (
        select(AuditLog, User.name, User.email)
        .outerjoin(User, User.id == AuditLog.actor_user_id)
        .where(AuditLog.company_id == tenant.company_id)
        .order_by(AuditLog.id.desc())
        .limit(min(limit, 500))
    )
    if entity_type:
        stmt = stmt.where(AuditLog.entity_type == entity_type)
    if action:
        stmt = stmt.where(AuditLog.action == action)
    if actor_user_id:
        stmt = stmt.where(AuditLog.actor_user_id == actor_user_id)
    if entity_id:
        stmt = stmt.where(AuditLog.entity_id == entity_id)
    stmt = _apply_audit_area_filter(stmt, area)
    if q:
        # Cast JSONB to text and ilike — sufficient for "search for a phone
        # number" or "find an invoice change" without needing a full-text index.
        like = f"%{q}%"
        stmt = stmt.where(
            or_(
                cast(AuditLog.before, String).ilike(like),
                cast(AuditLog.after, String).ilike(like),
            )
        )
    rows = (await session.execute(stmt)).all()
    return [
        AuditEntry(
            id=r.AuditLog.id,
            actor_user_id=r.AuditLog.actor_user_id,
            actor_name=r.name,
            actor_email=r.email,
            action=r.AuditLog.action,
            entity_type=r.AuditLog.entity_type,
            entity_id=r.AuditLog.entity_id,
            before=r.AuditLog.before,
            after=r.AuditLog.after,
            ip=r.AuditLog.ip,
            user_agent=r.AuditLog.user_agent,
            created_at=r.AuditLog.created_at,
        )
        for r in rows
    ]


@router.get("/audit/facets", response_model=AuditFacetsDTO)
async def audit_facets(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
    x_audit_token: str | None = Header(default=None, alias="X-Audit-Token"),
) -> AuditFacetsDTO:
    """Lists distinct entity types and actions present in the audit log,
    so the UI can populate filter dropdowns without hard-coding."""
    _require_audit_unlock(x_audit_token, tenant)

    types = (
        await session.execute(
            select(distinct(AuditLog.entity_type)).where(
                AuditLog.company_id == tenant.company_id
            )
        )
    ).scalars().all()
    actions = (
        await session.execute(
            select(distinct(AuditLog.action)).where(
                AuditLog.company_id == tenant.company_id
            )
        )
    ).scalars().all()
    return AuditFacetsDTO(
        entity_types=sorted([t for t in types if t]),
        actions=sorted([a for a in actions if a]),
    )

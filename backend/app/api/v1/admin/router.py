"""Admin endpoints — audit log read, system info."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, Request
from pydantic import BaseModel
from sqlalchemy import String, and_, cast, distinct, or_, select

from app.core.client_ip import audit_user_agent, trusted_client_ip
from app.core.db import SessionDep
from app.core.errors import AuthError, BusinessRuleError, NotFoundError
from app.core.permissions import (
    AUDITOR_ACCESS,
    MODULE_PERMISSIONS,
    ROLE_DESCRIPTIONS,
    ROLE_PERMISSIONS,
    _role_allows_permission,
    requires,
)
from app.core.roles import PROTECTED_OWNER_ROLE
from app.core.security import decode_token, issue_audit_token, issue_pricing_token, verify_password
from app.core.tenant import TenantContext
from app.models import AuditLog, RolePermissionOverride, User

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
    terminal_id: UUID | None
    request_id: str | None
    client_platform: str | None
    client_version_code: int | None
    client_action_id: str | None
    client_reported_at: datetime | None
    client_was_offline: bool | None
    synced_at: datetime | None
    reason: str | None
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
    "pos": (
        "Order", "OrderLine", "Payment", "Refund", "MembershipPaymentRequest",
        "MembershipPaymentCashCollection", "MembershipPaymentProviderAction",
        "MembershipPaymentRequestResolution", "MembershipPayment",
        "MembershipPaymentAttemptResolution", "MembershipRefund",
        "MembershipRefundCashHandoff", "MembershipRefundProviderAction",
        "MembershipRefundResolution", "MembershipRefundSettlement", "Shift",
    ),
    "customers": ("Customer", "CustomerMembership", "MembershipTier"),
    "staff": ("User", "UserRole", "Role", "Attendance"),
    "inventory": (
        "Ingredient",
        "Supplier",
        "Batch",
        "StockMovement",
        "PurchaseOrder",
        "PurchaseOrderLine",
        "GoodsReceiptNote",
        "GRN",
    ),
    "finance": (
        "Account",
        "Asset",
        "Expense",
        "ExpenseCategory",
        "JournalEntry",
        "JournalLine",
        "ManualCollection",
        "MembershipPaymentRequest",
        "MembershipPaymentCashCollection",
        "MembershipPaymentProviderAction",
        "MembershipPaymentRequestResolution",
        "MembershipPayment",
        "MembershipPaymentAttemptResolution",
        "MembershipRefund",
        "MembershipRefundCashHandoff",
        "MembershipRefundProviderAction",
        "MembershipRefundResolution",
        "MembershipRefundSettlement",
        "Partner",
        "CapitalEntry",
    ),
    "menu": (
        "MenuCategory",
        "MenuItem",
        "MenuModifierGroup",
        "MenuModifier",
        "MenuVariant",
    ),
    "operations": (
        "Table",
        "Floor",
        "Reservation",
        "Station",
        "GamingSession",
        "GamingBooking",
        "Event",
        "EventTicket",
    ),
    "system": ("AuditAccess", "PricingAccess", "RolePermissionOverride"),
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
            ip=trusted_client_ip(request),
            user_agent=audit_user_agent(request),
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
    tenant: TenantContext = Depends(requires("settings.manage")),
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
    limit: int = Query(default=100, ge=1, le=500),
    before_id: int | None = Query(default=None, ge=1),
    entity_type: str | None = Query(default=None, max_length=100),
    action: str | None = Query(default=None, max_length=100),
    actor_user_id: UUID | None = None,
    entity_id: str | None = Query(default=None, max_length=64),
    terminal_id: UUID | None = None,
    request_id: str | None = Query(default=None, max_length=64),
    area: str | None = Query(default=None, max_length=32),
    q: str | None = Query(default=None, min_length=1, max_length=200),
) -> list[AuditEntry]:
    """List audit log entries newest-first, scoped to this company.

    Filters: entity_type (e.g. 'Order'), action (create/update/delete),
    actor_user_id, entity_id. `q` does a substring match against any string
    in the before/after JSON.
    """
    _require_audit_unlock(x_audit_token, tenant)

    stmt = (
        select(AuditLog, User.name, User.email)
        .outerjoin(
            User,
            and_(
                User.id == AuditLog.actor_user_id,
                User.company_id == AuditLog.company_id,
            ),
        )
        .where(AuditLog.company_id == tenant.company_id)
        .order_by(AuditLog.id.desc())
        .limit(limit)
    )
    if before_id:
        stmt = stmt.where(AuditLog.id < before_id)
    if entity_type:
        stmt = stmt.where(AuditLog.entity_type == entity_type)
    if action:
        stmt = stmt.where(AuditLog.action == action)
    if actor_user_id:
        stmt = stmt.where(AuditLog.actor_user_id == actor_user_id)
    if entity_id:
        stmt = stmt.where(AuditLog.entity_id == entity_id)
    if terminal_id:
        stmt = stmt.where(AuditLog.terminal_id == terminal_id)
    if request_id:
        stmt = stmt.where(AuditLog.request_id == request_id)
    stmt = _apply_audit_area_filter(stmt, area)
    if q:
        # Cast JSONB to text and ilike — sufficient for "search for a phone
        # number" or "find an invoice change" without needing a full-text index.
        escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        like = f"%{escaped}%"
        stmt = stmt.where(
            or_(
                cast(AuditLog.before, String).ilike(like, escape="\\"),
                cast(AuditLog.after, String).ilike(like, escape="\\"),
                AuditLog.reason.ilike(like, escape="\\"),
                AuditLog.request_id.ilike(like, escape="\\"),
                AuditLog.client_action_id.ilike(like, escape="\\"),
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
            terminal_id=r.AuditLog.terminal_id,
            request_id=r.AuditLog.request_id,
            client_platform=r.AuditLog.client_platform,
            client_version_code=r.AuditLog.client_version_code,
            client_action_id=r.AuditLog.client_action_id,
            client_reported_at=r.AuditLog.client_reported_at,
            client_was_offline=r.AuditLog.client_was_offline,
            synced_at=r.AuditLog.synced_at,
            reason=r.AuditLog.reason,
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
        (
            await session.execute(
                select(distinct(AuditLog.entity_type)).where(
                    AuditLog.company_id == tenant.company_id
                )
            )
        )
        .scalars()
        .all()
    )
    actions = (
        (
            await session.execute(
                select(distinct(AuditLog.action)).where(AuditLog.company_id == tenant.company_id)
            )
        )
        .scalars()
        .all()
    )
    return AuditFacetsDTO(
        entity_types=sorted([t for t in types if t]),
        actions=sorted([a for a in actions if a]),
    )


class AccessCell(BaseModel):
    role_code: str
    module: str
    default_allowed: bool
    override: bool | None
    allowed: bool
    default_access_level: Literal["blocked", "partial", "full"]
    access_level: Literal["blocked", "partial", "full"]
    effective_permissions: list[str]
    unavailable_permissions: list[str]
    ceiling_limited_permissions: list[str]


class AccessControlDTO(BaseModel):
    roles: dict[str, str]  # role_code -> description
    modules: list[str]
    cells: list[AccessCell]


class AccessControlUpdate(BaseModel):
    role_code: str
    module: str
    allowed: bool | None  # null clears the override, reverting to the role default


def _module_access(
    *,
    role_code: str,
    module: str,
    override: bool | None,
) -> tuple[Literal["blocked", "partial", "full"], list[str], list[str]]:
    """Resolve the exact permissions behind one coarse Access Control cell.

    A module switch is intentionally coarser than the permission registry. An
    allow override can expose ordinary permissions while immutable high-trust
    ceilings still withhold refunds, role administration, or financial writes.
    Returning that as a plain ``allowed=true`` would mislead an owner, so the
    API reports both the effective permission set and its blocked remainder.
    """
    module_permissions = MODULE_PERMISSIONS[module]
    effective = sorted(
        permission
        for permission in module_permissions
        if _role_allows_permission(
            role=role_code,
            perm=permission,
            module=module,
            override=override,
        )
    )
    unavailable = sorted(module_permissions - set(effective))
    if not effective:
        level: Literal["blocked", "partial", "full"] = "blocked"
    elif unavailable:
        level = "partial"
    else:
        level = "full"
    return level, effective, unavailable


def _access_cell(
    *,
    role_code: str,
    module: str,
    override: bool | None,
) -> AccessCell:
    default_level, _, _ = _module_access(
        role_code=role_code,
        module=module,
        override=None,
    )
    level, effective, unavailable = _module_access(
        role_code=role_code,
        module=module,
        override=override,
    )
    # These remain unavailable even when the coarse module switch is enabled.
    # Keep them distinct from permissions that are merely disabled by the
    # current default/override and may legitimately be enabled by the owner.
    _, _, ceiling_limited = _module_access(
        role_code=role_code,
        module=module,
        override=True,
    )
    return AccessCell(
        role_code=role_code,
        module=module,
        default_allowed=default_level != "blocked",
        override=override,
        # Backward-compatible switch state for older clients. New clients must
        # render ``access_level`` so partial access is never presented as full.
        allowed=level != "blocked",
        default_access_level=default_level,
        access_level=level,
        effective_permissions=effective,
        unavailable_permissions=unavailable,
        ceiling_limited_permissions=ceiling_limited,
    )


@router.get("/access-control", response_model=AccessControlDTO)
async def get_access_control(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> AccessControlDTO:
    """Protected-owner-only: every role's effective access per feature
    module, so it can be toggled without a code deploy."""
    overrides = (
        (
            await session.execute(
                select(RolePermissionOverride).where(
                    RolePermissionOverride.company_id == tenant.company_id
                )
            )
        )
        .scalars()
        .all()
    )
    override_by_key = {(o.role_code, o.module): o.allowed for o in overrides}

    role_codes = [r for r in ROLE_PERMISSIONS if r != PROTECTED_OWNER_ROLE]
    modules = sorted(MODULE_PERMISSIONS)
    cells: list[AccessCell] = []
    for role_code in role_codes:
        for module in modules:
            override = override_by_key.get((role_code, module))
            cells.append(
                _access_cell(
                    role_code=role_code,
                    module=module,
                    override=override,
                )
            )
    return AccessControlDTO(
        roles={r: ROLE_DESCRIPTIONS.get(r, r) for r in role_codes},
        modules=modules,
        cells=cells,
    )


@router.patch("/access-control", response_model=AccessCell)
async def update_access_control(
    payload: AccessControlUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> AccessCell:
    if payload.role_code == PROTECTED_OWNER_ROLE:
        raise BusinessRuleError("the protected owner role cannot be restricted")
    if payload.role_code not in ROLE_PERMISSIONS:
        raise NotFoundError(f"unknown role: {payload.role_code}")
    if payload.module not in MODULE_PERMISSIONS:
        raise NotFoundError(f"unknown module: {payload.module}")
    if payload.role_code == "auditor" and payload.allowed:
        write_shaped = sorted(MODULE_PERMISSIONS[payload.module] - AUDITOR_ACCESS)
        if write_shaped:
            # Defense in depth for this same rule also lives in
            # _has_permission (permissions.py), which never lets 'auditor'
            # resolve true for anything outside AUDITOR_ACCESS regardless of
            # what this endpoint accepts. This check exists purely to give
            # the owner a clear error instead of a silent no-op.
            raise BusinessRuleError(
                f"'{payload.module}' cannot be granted to the read-only 'auditor' "
                f"role: it includes non-read permission(s) {write_shaped}",
            )

    existing = (
        await session.execute(
            select(RolePermissionOverride).where(
                RolePermissionOverride.company_id == tenant.company_id,
                RolePermissionOverride.role_code == payload.role_code,
                RolePermissionOverride.module == payload.module,
            )
        )
    ).scalar_one_or_none()

    if payload.allowed is None:
        if existing:
            await session.delete(existing)
    elif existing:
        existing.allowed = payload.allowed
    else:
        session.add(
            RolePermissionOverride(
                id=uuid4(),
                company_id=tenant.company_id,
                role_code=payload.role_code,
                module=payload.module,
                allowed=payload.allowed,
            )
        )
    await session.flush()

    return _access_cell(
        role_code=payload.role_code,
        module=payload.module,
        override=payload.allowed,
    )

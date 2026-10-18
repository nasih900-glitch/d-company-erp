"""Tenant-scoped, stable snapshot reads for the semantic Audit API."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, date, datetime, time, timedelta
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import String, and_, cast, literal, or_, select

from app.core.errors import BusinessRuleError
from app.core.security import decode_token, issue_audit_page_cursor
from app.models import AuditLog, Branch, Company, User
from app.services.audit.catalogue import (
    BY_CODE,
    CATEGORIES,
    OUTCOMES,
    REFERENCE_FIELDS,
    category_expression,
    code_expression,
    state_expression,
)


def filter_fingerprint(filters: dict[str, Any]) -> str:
    value = json.dumps(filters, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(value.encode()).hexdigest()


def decode_cursor(
    token: str, *, company_id: UUID, user_id: UUID, fingerprint: str
) -> tuple[int, int, str]:
    try:
        claims = decode_token(token)
        snapshot, last, identity = claims["snapshot"], claims["last"], claims["snapshot_id"]
        if (
            claims.get("type") != "audit_page"
            or claims.get("company_id") != str(company_id)
            or claims.get("sub") != str(user_id)
            or claims.get("filters") != fingerprint
            or type(snapshot) is not int
            or type(last) is not int
            or snapshot < 1
            or last < 1
            or last > snapshot
            or not isinstance(identity, str)
            or len(identity) != 32
            or UUID(hex=identity).hex != identity
        ):
            raise ValueError("cursor scope")
        return snapshot, last, identity
    except (ValueError, KeyError, TypeError) as exc:
        raise BusinessRuleError(
            "Audit cursor expired or does not match these filters. Refresh the list."
        ) from exc


def encode_cursor(
    *, company_id: UUID, user_id: UUID, fingerprint: str, snapshot: int, last: int, snapshot_id: str
) -> str:
    return issue_audit_page_cursor(
        {
            "company_id": str(company_id),
            "sub": str(user_id),
            "filters": fingerprint,
            "snapshot": snapshot,
            "last": last,
            "snapshot_id": snapshot_id,
        }
    )


async def shop_timezone(session, *, company_id: UUID, branch_id: UUID | None) -> str:
    company_zone = await session.scalar(select(Company.timezone).where(Company.id == company_id))
    branch_zone = None
    if branch_id is not None:
        branch_zone = await session.scalar(
            select(Branch.timezone).where(Branch.id == branch_id, Branch.company_id == company_id)
        )
    zone = branch_zone or company_zone or "Asia/Kolkata"
    try:
        ZoneInfo(zone)
    except (ZoneInfoNotFoundError, ValueError):
        zone = "Asia/Kolkata"
    return zone


def date_bounds(date_from: date | None, date_to: date | None, timezone: str):
    if date_from is not None and date_to is not None and date_from > date_to:
        raise BusinessRuleError("Audit start date must not be after the end date.")
    if date_to == date.max:
        raise BusinessRuleError("Audit end date is outside the supported range.")
    zone = ZoneInfo(timezone)
    start = datetime.combine(date_from, time.min, zone).astimezone(UTC) if date_from else None
    end = (
        datetime.combine(date_to + timedelta(days=1), time.min, zone).astimezone(UTC)
        if date_to
        else None
    )
    return start, end


def predicates(filters: dict[str, Any], *, timezone: str) -> list:
    code, category, outcome = filters.get("code"), filters.get("category"), filters.get("outcome")
    if code and code not in BY_CODE:
        raise BusinessRuleError("Unsupported Audit action code.")
    if category and category not in CATEGORIES:
        raise BusinessRuleError("Unsupported Audit category.")
    if outcome and outcome not in OUTCOMES:
        raise BusinessRuleError("Unsupported Audit outcome.")
    result = []
    view = filters.get("view", "business")
    if view not in {"business", "all"}:
        raise BusinessRuleError("Unsupported Audit activity view.")
    if view == "business":
        result.append(
            or_(
                code_expression().is_not(None),
                category_expression().in_(
                    (
                        "login",
                        "pos",
                        "customers",
                        "staff",
                        "inventory",
                        "finance",
                        "menu",
                        "operations",
                    )
                ),
                AuditLog.action == "refresh_token_reuse_rejected",
            )
        )
    if code:
        result.append(code_expression() == code)
    if category:
        result.append(category_expression() == category)
    if outcome:
        result.append(state_expression(2) == outcome)
    if filters.get("actor_user_id"):
        result.append(
            and_(
                AuditLog.actor_user_id == filters["actor_user_id"],
                AuditLog.action != "login_failed",
            )
        )
    if filters.get("target_user_id"):
        result.append(
            or_(
                AuditLog.target_user_id == filters["target_user_id"],
                and_(
                    AuditLog.action == "login_failed",
                    AuditLog.actor_user_id == filters["target_user_id"],
                ),
            )
        )
    for key, column in (
        ("entity_id", AuditLog.entity_id),
        ("entity_type", AuditLog.entity_type),
        ("action", AuditLog.action),
        ("terminal_id", AuditLog.terminal_id),
        ("request_id", AuditLog.request_id),
    ):
        if filters.get(key):
            result.append(column == filters[key])
    reference = filters.get("reference")
    if reference:
        result.append(
            or_(
                AuditLog.source_reference == reference,
                AuditLog.entity_id == reference,
                *(
                    snapshot[field].astext == reference
                    for snapshot in (AuditLog.before, AuditLog.after)
                    for field in REFERENCE_FIELDS
                ),
            )
        )
    start, end = date_bounds(filters.get("date_from"), filters.get("date_to"), timezone)
    if start:
        result.append(AuditLog.created_at >= start)
    if end:
        result.append(AuditLog.created_at < end)
    query = filters.get("q")
    if query:
        code_token = query.upper()
        if code_token in BY_CODE:
            result.append(code_expression() == code_token)
        else:
            escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            pattern = f"%{escaped}%"
            code_matches = tuple(
                d.code
                for d in BY_CODE.values()
                if query.lower() in d.label.lower()
                or any(query.lower() in alias for alias in d.aliases)
            )
            result.append(
                or_(
                    cast(AuditLog.before, String).ilike(pattern, escape="\\"),
                    cast(AuditLog.after, String).ilike(pattern, escape="\\"),
                cast(AuditLog.business_details, String).ilike(pattern, escape="\\"),
                    AuditLog.reason.ilike(pattern, escape="\\"),
                    AuditLog.source_reference.ilike(pattern, escape="\\"),
                    AuditLog.entity_id.ilike(pattern, escape="\\"),
                    AuditLog.request_id.ilike(pattern, escape="\\"),
                    AuditLog.client_action_id.ilike(pattern, escape="\\"),
                    and_(AuditLog.action != "login_failed", User.name.ilike(pattern, escape="\\")),
                    and_(AuditLog.action != "login_failed", User.email.ilike(pattern, escape="\\")),
                    code_expression().in_(code_matches) if code_matches else literal(False),
                )
            )
    return result


def event_query(company_id: UUID):
    return (
        select(AuditLog, User.name, User.email)
        .outerjoin(
            User,
            and_(
                User.id == AuditLog.actor_user_id,
                User.company_id == AuditLog.company_id,
            ),
        )
        .where(AuditLog.company_id == company_id)
    )

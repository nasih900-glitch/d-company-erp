"""Automatic audit logging via SQLAlchemy session events.

Every INSERT/UPDATE/DELETE on any tracked model fires a hook that writes
to the `audit_log` table:

    timestamp · who · action (create/update/delete) · table · record-id ·
    before-JSON (for updates and deletes) · after-JSON (for creates and updates)

The "who" is pulled from a per-request ContextVar populated by middleware
in app.main. This avoids polluting every endpoint with `audit_log.create(...)`.

Append-only: we never UPDATE or DELETE rows in audit_log, so the trail
can't be tampered with from inside the app. (Postgres SUPERUSER can still
edit; that's why we recommend running the app under a restricted DB role.)

To add a model to the audit trail, just add its class to TRACKED below.
"""

from __future__ import annotations

import contextvars
from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import event
from sqlalchemy.inspection import inspect
from sqlalchemy.orm import Session

from app.models import (
    GRN,
    AuditLog,
    Account,
    Asset,
    Attendance,
    Batch,
    Branch,
    CapitalEntry,
    Company,
    Customer,
    CustomerMembership,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    Floor,
    GRNLine,
    GamingBooking,
    GamingSession,
    Ingredient,
    JournalEntry,
    JournalLine,
    MembershipTier,
    MenuCategory,
    MenuItem,
    MenuModifier,
    MenuVariant,
    OcrExtraction,
    OcrUpload,
    OcrVerification,
    Order,
    OrderLine,
    Partner,
    Payment,
    PayrollEntry,
    PurchaseOrder,
    PurchaseOrderLine,
    Recipe,
    RecipeLine,
    Refund,
    Reservation,
    Shift,
    Station,
    StockMovement,
    Supplier,
    Table,
    Terminal,
    Tournament,
    User,
    UserRole,
)

# ---------------------------------------------------------------------------
# Per-request actor context.
# Set by middleware at request start; read by the event hooks.
# ---------------------------------------------------------------------------
actor_ctx: contextvars.ContextVar[dict[str, Any] | None] = contextvars.ContextVar(
    "audit_actor", default=None,
)


def set_actor(*, user_id: UUID | None, company_id: UUID,
              ip: str | None = None, user_agent: str | None = None) -> None:
    actor_ctx.set({
        "user_id": user_id, "company_id": company_id,
        "ip": ip, "user_agent": user_agent,
    })


def clear_actor() -> None:
    actor_ctx.set(None)


# ---------------------------------------------------------------------------
# Tracked models — every write to one of these writes an audit row.
# ---------------------------------------------------------------------------
TRACKED: set[type] = {
    Account, Asset, Attendance,
    Batch, Branch, Company, Customer, CustomerMembership,
    Event, EventTicket,
    Expense, ExpenseCategory,
    Floor, GRN, GRNLine, GamingBooking, GamingSession,
    Ingredient, JournalEntry, JournalLine, MembershipTier,
    MenuCategory, MenuItem, MenuModifier, MenuVariant,
    OcrExtraction, OcrUpload, OcrVerification,
    Order, OrderLine, Partner, CapitalEntry,
    Payment, PayrollEntry, PurchaseOrder, PurchaseOrderLine,
    Recipe, RecipeLine, Refund, Reservation,
    Shift, Station, StockMovement, Supplier,
    Table, Terminal, Tournament, User, UserRole,
}


# Fields we redact from the audit record (sensitive)
_REDACT = {"password_hash", "mfa_secret"}


def _serialize(obj: Any) -> dict | None:
    """Snapshot the row's column values as JSON-safe dict."""
    if obj is None:
        return None
    try:
        mapper = inspect(obj.__class__)
    except Exception:
        return None
    out: dict[str, Any] = {}
    for col in mapper.columns:
        if col.key in _REDACT:
            out[col.key] = "***REDACTED***"
            continue
        val = getattr(obj, col.key, None)
        if isinstance(val, UUID):
            out[col.key] = str(val)
        elif isinstance(val, datetime):
            out[col.key] = val.isoformat()
        elif isinstance(val, (int, float, str, bool)) or val is None:
            out[col.key] = val
        else:
            out[col.key] = str(val)
    return out


def _captured_diff(obj: Any) -> dict[str, dict]:
    """For an updated object, return {field: {before, after}} for changed fields."""
    try:
        mapper = inspect(obj.__class__)
        state = inspect(obj)
    except Exception:
        return {}
    out: dict[str, dict] = {}
    for col in mapper.columns:
        attr = state.attrs.get(col.key)
        if attr is None:
            continue
        history = attr.history
        if not history.has_changes():
            continue
        before = history.deleted[0] if history.deleted else None
        after = history.added[0] if history.added else getattr(obj, col.key, None)
        if col.key in _REDACT:
            out[col.key] = {"before": "***REDACTED***", "after": "***REDACTED***"}
            continue

        def _safe(v):
            if isinstance(v, UUID):
                return str(v)
            if isinstance(v, datetime):
                return v.isoformat()
            return v if isinstance(v, (int, float, str, bool)) or v is None else str(v)

        out[col.key] = {"before": _safe(before), "after": _safe(after)}
    return out


def _entity_id(obj: Any) -> str:
    pk = getattr(obj, "id", None)
    if pk is None:
        return ""
    return str(pk)


def _entity_type(obj: Any) -> str:
    return obj.__class__.__name__


def _company_id_of(obj: Any) -> UUID | None:
    return getattr(obj, "company_id", None)


# ---------------------------------------------------------------------------
# Event hook
# ---------------------------------------------------------------------------
def install_audit_listeners() -> None:
    """Idempotent installer — call once on app startup."""
    if getattr(install_audit_listeners, "_installed", False):
        return
    install_audit_listeners._installed = True  # type: ignore[attr-defined]

    @event.listens_for(Session, "before_flush")
    def _before_flush(session: Session, flush_context, instances):
        actor = actor_ctx.get()
        # Fall back to system actor if the request didn't set one (worker, seed).
        company_id = actor["company_id"] if actor else None
        actor_user = actor["user_id"] if actor else None
        ip = actor["ip"] if actor else None
        ua = actor["user_agent"] if actor else None

        rows_to_add: list[AuditLog] = []

        # Inserts
        for obj in list(session.new):
            if type(obj) not in TRACKED:
                continue
            row_company = _company_id_of(obj) or company_id
            if row_company is None:
                continue
            rows_to_add.append(AuditLog(
                actor_user_id=actor_user,
                company_id=row_company,
                action="create",
                entity_type=_entity_type(obj),
                entity_id=_entity_id(obj),
                before=None,
                after=_serialize(obj),
                ip=ip, user_agent=ua,
            ))

        # Updates
        for obj in list(session.dirty):
            if type(obj) not in TRACKED:
                continue
            if not session.is_modified(obj, include_collections=False):
                continue
            row_company = _company_id_of(obj) or company_id
            if row_company is None:
                continue
            diff = _captured_diff(obj)
            if not diff:
                continue
            if type(obj) is User and set(diff).issubset({"last_login_at", "failed_login_count"}):
                continue
            # Soft-delete special case: deleted_at went from None→datetime
            is_soft_delete = (
                "deleted_at" in diff
                and diff["deleted_at"]["before"] is None
                and diff["deleted_at"]["after"] is not None
            )
            rows_to_add.append(AuditLog(
                actor_user_id=actor_user,
                company_id=row_company,
                action="delete" if is_soft_delete else "update",
                entity_type=_entity_type(obj),
                entity_id=_entity_id(obj),
                before={k: v["before"] for k, v in diff.items()},
                after={k: v["after"] for k, v in diff.items()},
                ip=ip, user_agent=ua,
            ))

        # Hard deletes
        for obj in list(session.deleted):
            if type(obj) not in TRACKED:
                continue
            row_company = _company_id_of(obj) or company_id
            if row_company is None:
                continue
            rows_to_add.append(AuditLog(
                actor_user_id=actor_user,
                company_id=row_company,
                action="delete",
                entity_type=_entity_type(obj),
                entity_id=_entity_id(obj),
                before=_serialize(obj),
                after=None,
                ip=ip, user_agent=ua,
            ))

        for row in rows_to_add:
            session.add(row)

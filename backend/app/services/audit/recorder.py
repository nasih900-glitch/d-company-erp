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
import hashlib
import math
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

from sqlalchemy import event, insert
from sqlalchemy.inspection import inspect
from sqlalchemy.orm import Session

from app.models import (
    GRN,
    Account,
    Asset,
    Attendance,
    AuditLog,
    Batch,
    Branch,
    CapitalEntry,
    ClientGamingCleanupReconciliation,
    Company,
    Customer,
    CustomerMembership,
    CustomerSpendReconciliation,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    FinanceSourceCorrection,
    Floor,
    GamingBooking,
    GamingPackage,
    GamingPlaytimeProgramSettings,
    GamingSession,
    GamingSessionExtension,
    GamingSessionPackageAmendment,
    GRNLine,
    Ingredient,
    JournalEntry,
    JournalLine,
    ManualCollection,
    MembershipBenefitReservation,
    MembershipCustomerSpendApplication,
    MembershipEvidenceReconciliation,
    MembershipPayment,
    MembershipPaymentAttemptResolution,
    MembershipPaymentCashCollection,
    MembershipPaymentCompletion,
    MembershipPaymentProviderAction,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundAttemptResolution,
    MembershipRefundCashHandoff,
    MembershipRefundCompletion,
    MembershipRefundProviderAction,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    MembershipTier,
    MenuCategory,
    MenuItem,
    MenuModifier,
    MenuModifierGroup,
    MenuVariant,
    OcrExtraction,
    OcrUpload,
    OcrVerification,
    Order,
    OrderCheckoutClaim,
    OrderLine,
    Partner,
    Payment,
    PayrollEntry,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundEvidenceReconciliation,
    PosRefundProviderPayoutStart,
    PosRefundProviderSettlement,
    PosRefundRequest,
    PosRefundWithdrawal,
    PurchaseOrder,
    PurchaseOrderLine,
    Recipe,
    RecipeLine,
    Refund,
    Reservation,
    RolePermissionOverride,
    Shift,
    Station,
    StockMovement,
    Supplier,
    SupplierPayment,
    Table,
    Terminal,
    TipPayout,
    Tournament,
    User,
    UserRole,
)

# ---------------------------------------------------------------------------
# Per-request actor context.
# Set by middleware at request start; read by the event hooks.
# ---------------------------------------------------------------------------
actor_ctx: contextvars.ContextVar[dict[str, Any] | None] = contextvars.ContextVar(
    "audit_actor",
    default=None,
)
request_ctx: contextvars.ContextVar[dict[str, Any] | None] = contextvars.ContextVar(
    "audit_request",
    default=None,
)


def set_actor(
    *,
    user_id: UUID | None,
    company_id: UUID,
    terminal_id: UUID | None = None,
    ip: str | None = None,
    user_agent: str | None = None,
    actor_kind: str = "authenticated",
) -> None:
    actor_ctx.set(
        {
            "user_id": user_id,
            "company_id": company_id,
            "terminal_id": terminal_id,
            "ip": ip,
            "user_agent": user_agent,
            "actor_kind": actor_kind,
        }
    )


def clear_actor() -> None:
    actor_ctx.set(None)


def set_request_context(
    *,
    request_id: str,
    client_platform: str,
    client_version_code: int | None,
    client_action_id: str | None,
    client_reported_at: datetime | None,
    client_was_offline: bool,
) -> None:
    """Capture request provenance independently of authentication.

    `created_at` remains the authoritative server timestamp. A client-reported
    timestamp is retained only as evidence for later offline reconciliation;
    accounting and ordering logic must never depend on it.
    """
    request_ctx.set(
        {
            "request_id": request_id,
            "client_platform": client_platform,
            "client_version_code": client_version_code,
            "client_action_id": client_action_id,
            "client_reported_at": client_reported_at,
            "client_was_offline": client_was_offline,
            "synced_at": datetime.now(UTC) if client_was_offline else None,
        }
    )


def clear_request_context() -> None:
    request_ctx.set(None)


# ---------------------------------------------------------------------------
# Tracked models — every write to one of these writes an audit row.
# ---------------------------------------------------------------------------
TRACKED: set[type] = {
    Account,
    Asset,
    Attendance,
    CustomerSpendReconciliation,
    Batch,
    Branch,
    Company,
    Customer,
    CustomerMembership,
    ClientGamingCleanupReconciliation,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    Floor,
    FinanceSourceCorrection,
    GRN,
    GRNLine,
    GamingBooking,
    GamingPackage,
    GamingPlaytimeProgramSettings,
    GamingSession,
    GamingSessionExtension,
    GamingSessionPackageAmendment,
    Ingredient,
    JournalEntry,
    JournalLine,
    ManualCollection,
    MembershipBenefitReservation,
    MembershipCustomerSpendApplication,
    MembershipEvidenceReconciliation,
    MembershipPayment,
    MembershipPaymentCashCollection,
    MembershipPaymentCompletion,
    MembershipPaymentAttemptResolution,
    MembershipPaymentProviderAction,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundAttemptResolution,
    MembershipRefundCashHandoff,
    MembershipRefundCompletion,
    MembershipRefundProviderAction,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    MembershipTier,
    MenuCategory,
    MenuItem,
    MenuModifier,
    MenuModifierGroup,
    MenuVariant,
    OcrExtraction,
    OcrUpload,
    OcrVerification,
    Order,
    OrderCheckoutClaim,
    OrderLine,
    Partner,
    CapitalEntry,
    Payment,
    PayrollEntry,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundEvidenceReconciliation,
    PosRefundProviderPayoutStart,
    PosRefundProviderSettlement,
    PosRefundRequest,
    PosRefundWithdrawal,
    PurchaseOrder,
    PurchaseOrderLine,
    Recipe,
    RecipeLine,
    Refund,
    Reservation,
    RolePermissionOverride,
    Shift,
    Station,
    StockMovement,
    Supplier,
    SupplierPayment,
    Table,
    Terminal,
    TipPayout,
    Tournament,
    User,
    UserRole,
}


# Fields we redact from the audit record (sensitive). Encrypted credentials are
# still credentials: retaining old ciphertext in append-only audit history
# would defeat rotation/disconnect erasure if the encryption key were later
# compromised.
_REDACT = {
    "google_sheets_signing_secret_ciphertext",
    "mfa_secret",
    "password_hash",
    "payment_key_secret",
    "token_hash",
    "password", "current_password", "new_password", "pending_password_hash",
    "access_token", "refresh_token", "refresh_token_hash", "audit_token", "pricing_token",
    "code_hash", "otp", "secret", "authorization", "cookie",
    "jwt_secret", "jwt_private_key",
}

_REDACTION_MARKER = "***REDACTED***"


def _is_redacted_column(column: Any) -> bool:
    """Return whether one mapped column must never enter generic audit JSON.

    The global names cover credentials wherever they occur. A model can also
    opt one exact column into redaction through SQLAlchemy ``Column.info``.
    Keeping the declaration beside the model avoids hiding same-named fields on
    unrelated models while preserving safe sibling evidence such as hashes.
    """
    return column.key in _REDACT or column.info.get("audit_redact") is True


def json_safe(value: Any, *, depth: int = 0) -> Any:
    """Bounded typed JSON; nested credentials stay redacted before persistence."""
    if depth > 8:
        return "[depth limit]"
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, str):
        return value[:8000]
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, (UUID, Decimal)):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, dict):
        return {
            str(key)[:120]: _REDACTION_MARKER
            if str(key).lower() in _REDACT or str(key).lower().endswith("_secret_ciphertext")
            or (str(key).lower() == "code" and any(
                marker in value for marker in ("otp", "code_hash", "challenge_id", "password_reset")
            ))
            else json_safe(item, depth=depth + 1)
            for key, item in list(value.items())[:200]
        }
    if isinstance(value, (list, tuple)):
        return [json_safe(item, depth=depth + 1) for item in value[:200]]
    return str(value)[:1000]


def _serialize(obj: Any) -> dict | None:
    if obj is None:
        return None
    try:
        mapper = inspect(obj.__class__)
    except Exception:
        return None
    return {
        col.key: _REDACTION_MARKER if _is_redacted_column(col)
        else json_safe(getattr(obj, col.key, None))
        for col in mapper.columns
    }


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
        # Decide redaction before reading either history value. Besides keeping
        # the resulting JSON safe, this avoids handing a nested private payload
        # to any future normalization/logging added below.
        if _is_redacted_column(col):
            out[col.key] = {
                "before": _REDACTION_MARKER,
                "after": _REDACTION_MARKER,
            }
            continue
        before = history.deleted[0] if history.deleted else None
        after = history.added[0] if history.added else getattr(obj, col.key, None)

        out[col.key] = {"before": json_safe(before), "after": json_safe(after)}

    return out


def _entity_id(obj: Any) -> str:
    if isinstance(obj, RolePermissionOverride):
        # A random row UUID does not identify which access cell changed. The
        # role/module pair is the stable operator-facing target and fits the
        # audit_log entity_id limit (30 + separator + 30 characters).
        return f"{obj.role_code}:{obj.module}"
    pk = getattr(obj, "id", None)
    if pk is None:
        return ""
    return str(pk)


def _entity_type(obj: Any) -> str:
    return obj.__class__.__name__


def _company_id_of(obj: Any) -> UUID | None:
    return getattr(obj, "company_id", None)


_REASON_FIELDS = (
    "reason",
    "comment",
    "notes",
    "note",
    "void_reason",
    "cancel_reason",
    "cancellation_reason",
    "adjustment_reason",
)


def _reason_of(obj: Any) -> str | None:
    if isinstance(obj, AuditLog):
        for snapshot in (obj.after, obj.before):
            if not isinstance(snapshot, dict):
                continue
            for field in _REASON_FIELDS:
                value = snapshot.get(field)
                if value is not None and str(value).strip():
                    return str(value).strip()[:500]
        return None
    for field in _REASON_FIELDS:
        value = getattr(obj, field, None)
        if value is not None and str(value).strip():
            return str(value).strip()[:500]
    return None


def _audit_context_fields(obj: Any | None = None) -> dict[str, Any]:
    actor = actor_ctx.get() or {}
    request = request_ctx.get() or {}
    return {
        "ip": actor.get("ip"),
        "user_agent": actor.get("user_agent"),
        "terminal_id": actor.get("terminal_id"),
        "request_id": request.get("request_id"),
        "client_platform": request.get("client_platform"),
        "client_version_code": request.get("client_version_code"),
        "client_action_id": request.get("client_action_id"),
        "client_reported_at": request.get("client_reported_at"),
        "client_was_offline": request.get("client_was_offline"),
        "synced_at": request.get("synced_at"),
        "reason": _reason_of(obj) if obj is not None else None,
    }


def _enrich_manual_audit_row(row: AuditLog) -> None:
    from app.services.audit.catalogue import (
        CATALOGUE_VERSION,
        DETAIL_FIELDS,
        category_for,
        classify_values,
        retained_reference,
    )

    for field, value in _audit_context_fields(row).items():
        if getattr(row, field, None) is None and value is not None:
            setattr(row, field, value)
    row.before = json_safe(row.before)
    row.after = json_safe(row.after)
    derived = classify_values(row.action, row.entity_type, row.before, row.after)
    if derived:
        row.action_code, row.stage, row.outcome = derived
    row.category = category_for(row.action, row.entity_type, row.action_code)
    row.catalogue_version = CATALOGUE_VERSION
    row.source_reference = row.source_reference or retained_reference(row.before, row.after)
    if row.action == "login_failed":
        row.target_user_id = row.target_user_id or row.actor_user_id
        row.actor_user_id = None
        row.actor_kind = "unauthenticated"
    elif row.action in {"otp_password_reset_success", "otp_account_created"}:
        row.actor_kind = "otp_approved_requester" if row.actor_user_id else "otp_approved_unknown"
    elif row.action.startswith("security_otp_"):
        row.actor_kind = "requester_recorded" if row.actor_user_id else "unauthenticated"
    elif row.action == "login_success":
        row.actor_kind = "authenticated"
    elif row.actor_kind is None and row.actor_user_id is not None:
        context = actor_ctx.get() or {}
        row.actor_kind = context.get("actor_kind", "recorded_actor") if (
            context.get("user_id") == row.actor_user_id
        ) else "recorded_actor"
    elif row.actor_kind is None:
        row.actor_kind = "unverified"
    row.business_details = json_safe(row.business_details or {
        key: value for key, value in (row.after or {}).items() if key in DETAIL_FIELDS
    })
    row.operation_id = row.operation_id or row.client_action_id or row.request_id
    if row.operation_id and len(row.operation_id) > 100:
        row.operation_id = "sha256:" + hashlib.sha256(row.operation_id.encode()).hexdigest()


def _stock_details(obj: Any, session: Session) -> dict | None:
    if not isinstance(obj, StockMovement):
        return None
    # Snapshot names/units and retained history while the authoritative source
    # transaction still holds them. Never consult mutable catalogue rows later
    # to reconstruct an older Audit event.
    objects = [*session.identity_map.values(), *session.new]
    batch = next((row for row in objects if isinstance(row, Batch) and row.id == obj.batch_id), None)
    if batch is None:
        return None
    ingredient = next((row for row in objects if isinstance(row, Ingredient)
                       and row.id == batch.ingredient_id), None)
    if ingredient is None:
        return None
    history = inspect(ingredient).attrs.current_qty.history
    details = {
        "ingredient_id": str(ingredient.id), "ingredient_name": ingredient.name,
        "unit": ingredient.base_unit, "qty_delta": json_safe(obj.qty_delta),
        "batch_id": str(batch.id), "type": obj.type,
        "ref_type": obj.ref_type, "ref_id": str(obj.ref_id) if obj.ref_id else None,
    }
    if history.deleted:
        details["balance_before"] = json_safe(history.deleted[0])
        details["balance_after"] = json_safe(ingredient.current_qty)
    return details


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
        for obj in session.dirty:
            if isinstance(obj, AuditLog) and session.is_modified(obj, include_collections=False):
                raise ValueError("audit log is append-only and cannot be updated")
        for obj in session.deleted:
            if isinstance(obj, AuditLog):
                raise ValueError("audit log is append-only and cannot be deleted")

        actor = actor_ctx.get()
        # Fall back to system actor if the request didn't set one (worker, seed).
        company_id = actor["company_id"] if actor else None
        actor_user = actor["user_id"] if actor else None
        rows_to_add: list[AuditLog] = []

        # Explicit security/auth entries are constructed by their endpoint.
        # Enrich them here too so they receive the same request/device/offline
        # provenance as automatically recorded model mutations.
        for obj in list(session.new):
            if isinstance(obj, AuditLog):
                _enrich_manual_audit_row(obj)

        # Preserve object references through flush; defaults/IDs exist afterwards.
        pending = session.info.setdefault("audit_pending_inserts", [])
        for obj in list(session.new):
            if type(obj) in TRACKED:
                row_company = _company_id_of(obj) or company_id
                if row_company is not None:
                    context = _audit_context_fields(obj)
                    stock_details = _stock_details(obj, session)
                    if stock_details is not None:
                        context["business_details"] = stock_details
                    pending.append((obj, row_company, actor_user, context))

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
            rows_to_add.append(
                AuditLog(
                    actor_user_id=actor_user,
                    company_id=row_company,
                    action="delete" if is_soft_delete else "update",
                    entity_type=_entity_type(obj),
                    entity_id=_entity_id(obj),
                    before={k: v["before"] for k, v in diff.items()},
                    after={k: v["after"] for k, v in diff.items()},
                    **_audit_context_fields(obj),
                )
            )

        # Hard deletes
        for obj in list(session.deleted):
            if type(obj) not in TRACKED:
                continue
            row_company = _company_id_of(obj) or company_id
            if row_company is None:
                continue
            rows_to_add.append(
                AuditLog(
                    actor_user_id=actor_user,
                    company_id=row_company,
                    action="delete",
                    entity_type=_entity_type(obj),
                    entity_id=_entity_id(obj),
                    before=_serialize(obj),
                    after=None,
                    **_audit_context_fields(obj),
                )
            )

        for row in rows_to_add:
            _enrich_manual_audit_row(row)
            session.add(row)

    @event.listens_for(Session, "after_flush_postexec")
    def _after_flush_postexec(session: Session, flush_context):
        for obj, company_id, actor_user_id, context in session.info.pop("audit_pending_inserts", []):
            row = AuditLog(
                actor_user_id=actor_user_id, company_id=company_id, action="create",
                entity_type=_entity_type(obj), entity_id=_entity_id(obj), before=None,
                after=_serialize(obj), **context,
            )
            _enrich_manual_audit_row(row)
            # Direct insert on this transaction's connection makes final-ID
            # evidence visible immediately after one flush, even when the
            # session intentionally disables autoflush. No recursive ORM row.
            values = {column.key: getattr(row, column.key, None)
                      for column in AuditLog.__table__.columns
                      if column.key not in {"id", "created_at"}}
            session.connection().execute(insert(AuditLog.__table__).values(**values))

    @event.listens_for(Session, "after_soft_rollback")
    def _after_rollback(session: Session, previous_transaction):
        session.info.pop("audit_pending_inserts", None)

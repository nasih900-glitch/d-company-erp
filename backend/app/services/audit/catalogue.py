"""Versioned business action vocabulary shared by every Audit client.

Mappings use retained evidence only. No current mutable source row is consulted
when describing an older event. Unknown evidence stays explicitly unclassified.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from sqlalchemy import and_, case, func, literal, or_

from app.models import AuditLog

CATALOGUE_VERSION = 1


@dataclass(frozen=True)
class ActionDefinition:
    code: str
    label: str
    category: str
    aliases: tuple[str, ...]
    stages: tuple[str, ...] = ("committed",)
    outcomes: tuple[str, ...] = ("completed",)


CATALOGUE: tuple[ActionDefinition, ...] = (
    ActionDefinition(
        "STK-RCV", "Stock received", "inventory", ("received", "goods receipt", "grn")
    ),
    ActionDefinition("STK-SAL", "Stock used by sale", "inventory", ("sale", "deducted")),
    ActionDefinition("STK-WST", "Stock wasted", "inventory", ("waste", "wasted")),
    ActionDefinition("STK-DMG", "Stock damaged", "inventory", ("damage", "damaged")),
    ActionDefinition("STK-ADJ", "Stock adjusted", "inventory", ("adjustment", "adjusted")),
    ActionDefinition("STK-RTN", "Refunded stock returned", "inventory", ("restock", "returned")),
    ActionDefinition("POS-PAY", "Payment recorded", "pos", ("paid", "payment", "collection")),
    ActionDefinition("POS-PCM", "Payment method corrected", "pos", ("tender", "method correction")),
    ActionDefinition(
        "POS-RFD",
        "Refund activity",
        "pos",
        ("refund", "payout"),
        (
            "recorded",
            "requested",
            "awaiting_settlement",
            "value_captured",
            "withdrawn",
            "reversed",
            "physically_settled",
        ),
        ("unknown", "pending", "completed", "withdrawn", "reversed"),
    ),
    ActionDefinition("FIN-COL", "Manual collection", "finance", ("manual collection", "receipt")),
    ActionDefinition(
        "FIN-REC",
        "Daily collection reconciliation",
        "finance",
        ("actuals", "reconcile"),
        ("recorded", "committed"),
        ("unresolved", "completed"),
    ),
    ActionDefinition(
        "GAM-STA",
        "Gaming session started",
        "operations",
        ("start", "started"),
        ("committed", "rejected"),
        ("completed", "failed"),
    ),
    ActionDefinition("GAM-STP", "Gaming session stopped", "operations", ("stop", "stopped")),
    ActionDefinition("GAM-PAU", "Gaming session paused", "operations", ("pause", "paused")),
    ActionDefinition("GAM-RES", "Gaming session resumed", "operations", ("resume", "resumed")),
    ActionDefinition("GAM-EXT", "Gaming time extended", "operations", ("extend", "extension")),
    ActionDefinition("GAM-JOIN", "Player joined", "operations", ("joined", "player", "controller")),
    ActionDefinition("GAM-LEAVE", "Player left", "operations", ("left", "leave")),
    ActionDefinition("GAM-ADD", "Gaming add-on added", "operations", ("drink", "addon", "add-on")),
    ActionDefinition("GAM-VOID", "Gaming add-on removed", "operations", ("void", "removed")),
    ActionDefinition(
        "GAM-POS", "Gaming session sent to POS", "operations", ("handoff", "checkout")
    ),
    ActionDefinition("SHF-OPN", "Shift opened", "pos", ("open shift", "opening float")),
    ActionDefinition(
        "SHF-CLS",
        "Shift close",
        "pos",
        ("close shift", "drawer count"),
        ("requested", "rejected", "committed"),
        ("pending", "failed", "completed"),
    ),
    ActionDefinition("SHF-REC", "Shift recovered", "pos", ("recovery", "isolated tablet")),
    ActionDefinition(
        "SEC-LGN",
        "Sign-in attempt",
        "login",
        ("login", "sign in"),
        ("authenticated", "rejected"),
        ("completed", "failed"),
    ),
    ActionDefinition(
        "SEC-PWD",
        "Password recovery",
        "system",
        ("password", "reset", "otp"),
        ("requested", "rejected", "committed"),
        ("pending", "failed", "completed"),
    ),
    ActionDefinition("SEC-ROL", "Staff access changed", "staff", ("role", "access", "permission")),
)
BY_CODE = {definition.code: definition for definition in CATALOGUE}
assert len(BY_CODE) == len(CATALOGUE) == 27

# This is also used for old unmapped technical records. Unknown is not System.
AREA_TYPES: dict[str, tuple[str, ...]] = {
    "pos": (
        "Order",
        "OrderLine",
        "Payment",
        "Refund",
        "Shift",
        "OrderCheckoutClaim",
        "PosRefundRequest",
        "PosRefundCashHandoff",
        "PosRefundCashHandoffCompletion",
        "PosRefundProviderPayoutStart",
        "PosRefundProviderSettlement",
        "PosRefundWithdrawal",
        "PosRefundEvidenceReconciliation",
        "MembershipPaymentRequest",
        "MembershipPaymentCashCollection",
        "MembershipPaymentProviderAction",
        "MembershipPaymentRequestResolution",
        "MembershipPayment",
        "MembershipPaymentCompletion",
        "MembershipPaymentAttemptResolution",
        "MembershipRefund",
        "MembershipRefundCashHandoff",
        "MembershipRefundCompletion",
        "MembershipRefundProviderAction",
        "MembershipRefundResolution",
        "MembershipRefundSettlement",
        "MembershipRefundAttemptRecovery",
        "MembershipRefundAttemptResolution",
    ),
    "customers": (
        "Customer",
        "CustomerMembership",
        "MembershipTier",
        "CustomerSpendReconciliation",
        "MembershipBenefitReservation",
        "MembershipCustomerSpendApplication",
    ),
    "staff": ("User", "UserRole", "Role", "Attendance", "PayrollEntry", "StaffAccess"),
    "inventory": (
        "Ingredient",
        "Supplier",
        "Batch",
        "StockMovement",
        "PurchaseOrder",
        "PurchaseOrderLine",
        "GoodsReceiptNote",
        "GRN",
        "GRNLine",
        "Recipe",
        "RecipeLine",
    ),
    "finance": (
        "Account",
        "Asset",
        "Expense",
        "ExpenseCategory",
        "JournalEntry",
        "JournalLine",
        "ManualCollection",
        "FinanceSourceCorrection",
        "Partner",
        "CapitalEntry",
        "SupplierPayment",
        "TipPayout",
        "DailyCollectionReconciliation",
    ),
    "menu": ("MenuCategory", "MenuItem", "MenuModifierGroup", "MenuModifier", "MenuVariant"),
    "operations": (
        "Table",
        "Floor",
        "Reservation",
        "Station",
        "GamingSession",
        "gaming_sessions",
        "GamingBooking",
        "GamingPackage",
        "GamingSessionExtension",
        "GamingSessionPackageAmendment",
        "GamingSessionParticipant",
        "GamingSessionAddon",
        "GamingPlaytimeProgramSettings",
        "Event",
        "EventTicket",
        "Tournament",
    ),
    "system": (
        "AuditAccess",
        "PricingAccess",
        "RolePermissionOverride",
        "AuthAccess",
        "AuthSession",
        "Company",
        "Branch",
        "Terminal",
        "RemoteAssistanceDeviceKey",
        "RemoteAssistanceGrant",
        "RemoteAssistanceSession",
        "RemoteAssistanceCommand",
    ),
}
CATEGORIES = ("login", *AREA_TYPES.keys(), "other")
OUTCOMES = ("completed", "failed", "pending", "withdrawn", "reversed", "unresolved", "unknown")

# ORM rows emitted as implementation companions to a source-defined operation.
# They remain immutable and available in All activity, but a newly recorded,
# uncoded CRUD row is not itself a second business action.
TECHNICAL_COMPANION_ENTITIES = (
    "OrderCheckoutClaim",
)
TECHNICAL_COMPANION_ACTIONS = ("create", "update", "delete")

# Codes that can be established from an explicit operation (not arbitrary CRUD).
EXPLICIT = {
    "gaming_session_started": ("GAM-STA", "committed", "completed"),
    "gaming_session_start_rejected": ("GAM-STA", "rejected", "failed"),
    "gaming_session_stopped": ("GAM-STP", "committed", "completed"),
    "gaming.session.pause": ("GAM-PAU", "committed", "completed"),
    "gaming.session.resume": ("GAM-RES", "committed", "completed"),
    "gaming_timer_extended": ("GAM-EXT", "committed", "completed"),
    "gaming_participant_joined": ("GAM-JOIN", "committed", "completed"),
    "gaming_participant_left": ("GAM-LEAVE", "committed", "completed"),
    "gaming_session_addon_added": ("GAM-ADD", "committed", "completed"),
    "gaming_session_addon_voided": ("GAM-VOID", "committed", "completed"),
    "gaming_session_handoff_to_pos": ("GAM-POS", "committed", "completed"),
    "gaming_session_sent_to_pos": ("GAM-POS", "committed", "completed"),
    "shift_android_origin_recovery_close": ("SHF-REC", "committed", "completed"),
    "shift_opened": ("SHF-OPN", "committed", "completed"),
    "shift_closed": ("SHF-CLS", "committed", "completed"),
    "shift_close_rejected": ("SHF-CLS", "rejected", "failed"),
    "payment_method_corrected": ("POS-PCM", "committed", "completed"),
    "daily_collections_reconciled": ("FIN-REC", "committed", "completed"),
    "daily_actuals_recorded": ("FIN-REC", "recorded", "unresolved"),
    "staff_access_changed": ("SEC-ROL", "committed", "completed"),
    "login_success": ("SEC-LGN", "authenticated", "completed"),
    "login_failed": ("SEC-LGN", "rejected", "failed"),
    "otp_password_reset_success": ("SEC-PWD", "committed", "completed"),
    "security_otp_requested": ("SEC-PWD", "requested", "pending"),
    "security_otp_delivery_failed": ("SEC-PWD", "rejected", "failed"),
    "security_otp_rejected": ("SEC-PWD", "rejected", "failed"),
}
STOCK_CODES = {
    "grn": "STK-RCV",
    "sale": "STK-SAL",
    "waste": "STK-WST",
    "damage": "STK-DMG",
    "adjustment": "STK-ADJ",
    "refund_restock": "STK-RTN",
}
REFUNDS = {
    "PosRefundCashHandoffCompletion": ("value_captured", "pending"),
    "PosRefundProviderSettlement": ("value_captured", "pending"),
    "MembershipRefundCompletion": ("value_captured", "pending"),
    "PosRefundRequest": ("requested", "pending"),
    "PosRefundCashHandoff": ("awaiting_settlement", "pending"),
    "PosRefundProviderPayoutStart": ("awaiting_settlement", "pending"),
    "PosRefundWithdrawal": ("withdrawn", "withdrawn"),
    "Refund": ("physically_settled", "completed"),
    "MembershipRefund": ("requested", "pending"),
    "MembershipRefundCashHandoff": ("awaiting_settlement", "pending"),
    "MembershipRefundProviderAction": ("awaiting_settlement", "pending"),
    "MembershipRefundResolution": ("recorded", "unknown"),
    "MembershipRefundSettlement": ("physically_settled", "completed"),
}
REFERENCE_FIELDS = ("invoice_no", "reference", "source_reference", "receipt_no", "ref_id")
DETAIL_FIELDS = (
    "payment_id", "correction_id", "original_method", "effective_method", "evidence_kind",
    "before_cash_minor", "before_upi_minor", "actual_cash_minor", "actual_upi_minor",
    "cash_delta_minor", "upi_delta_minor", "total_difference_minor", "source_allocation", "kind",
    "amount_minor",
    "total_minor",
    "line_total_minor",
    "expected_minor",
    "counted_minor",
    "variance_minor",
    "opening_minor",
    "method",
    "payment_method",
    "business_date",
    "date",
    "invoice_no",
    "reference",
    "order_id",
    "gaming_session_id",
    "station_id",
    "shift_id",
    "batch_id",
    "ingredient_id",
    "menu_item_name",
    "qty",
    "qty_delta",
    "base_unit",
    "unit",
    "type",
    "ref_type",
    "ref_id",
    "duration_minutes",
    "additional_minutes",
    "timer_minutes",
    "timer_before_minutes",
    "timer_after_minutes",
    "joined_at",
    "left_at",
    "play_elapsed_ms",
    "before_roles",
    "after_roles",
    "target_user_id",
    "branch_id",
    "reason",
    "note",
    "notes",
    "status",
    "target_email",
    "email",
    "approval",
    "purpose",
    "resolution",
    "resolved_at",
    "allowed",
    "role_code",
    "module",
    "opening_float_minor",
    "start_at",
    "end_at",
    "billable_minutes",
    "settled_at",
    "settled_by",
    "settlement_method",
    "receipt_no",
    "external_reference",
    "provider_settled_at",
    "external_ref",
    "withdrawn_at",
    "refund_id",
    "refund_request_id",
)


def refund_state(entity: str, values: dict) -> tuple[str, str]:
    def retained(*fields):
        return all(values.get(field) is not None and str(values[field]).strip() for field in fields)

    if entity == "PosRefundCashHandoffCompletion":
        return (
            ("value_captured", "pending")
            if retained("refund_request_id", "handed_over_at", "recorded_by")
            else ("recorded", "unknown")
        )
    if entity == "PosRefundProviderSettlement":
        return (
            ("value_captured", "pending")
            if retained(
                "refund_request_id", "provider_settled_at", "settled_by", "external_reference"
            )
            and values.get("settlement_method") in {"card", "upi", "qr", "wallet"}
            else ("recorded", "unknown")
        )
    if entity == "MembershipRefundCompletion":
        method = values.get("method")
        rail = method == "cash" or (
            method in {"card", "upi", "razorpay"} and retained("external_reference")
        )
        return (
            ("value_captured", "pending")
            if retained("refund_id", "completed_at", "completed_by", "amount_minor") and rail
            else ("recorded", "unknown")
        )
    if entity in {"Refund", "MembershipRefundSettlement"}:
        membership = entity == "MembershipRefundSettlement"
        method = values.get("method" if membership else "settlement_method")
        proof = retained(
            "amount_minor",
            "settled_at",
            "settled_by",
            "receipt_no",
            "refund_id" if membership else "request_id",
        )
        noncash_methods = (
            {"card", "upi", "razorpay"} if membership else {"card", "upi", "qr", "wallet"}
        )
        rail_proof = method == "cash" or (
            method in noncash_methods
            and retained("external_ref" if membership else "external_reference")
            and (membership or retained("provider_settled_at"))
        )
        return (
            ("physically_settled", "completed") if proof and rail_proof else ("recorded", "unknown")
        )
    if entity == "MembershipRefundResolution":
        if not retained("refund_id", "resolved_at", "resolution"):
            return "recorded", "unknown"
        resolution = values["resolution"]
        if resolution in {"cash_not_handed_over", "provider_not_completed"}:
            return "withdrawn", "withdrawn"
        if resolution == "cash_returned" or (
            resolution == "provider_reversed" and retained("external_reference")
        ):
            return "reversed", "reversed"
        return "recorded", "unknown"
    if entity == "PosRefundWithdrawal":
        if retained("refund_request_id", "withdrawn_at", "resolution") and values["resolution"] in {
            "cash_not_handed_over",
            "cash_handoff_abandoned",
            "provider_not_started",
            "provider_payout_abandoned",
        }:
            return "withdrawn", "withdrawn"
        return "recorded", "unknown"
    if entity in {"PosRefundRequest", "MembershipRefund"}:
        link = "order_id" if entity == "PosRefundRequest" else "payment_id"
        return (
            ("requested", "pending") if retained(link, "amount_minor") else ("recorded", "unknown")
        )
    if entity in REFUNDS:
        link = "refund_id" if entity.startswith("Membership") else "refund_request_id"
        return ("awaiting_settlement", "pending") if retained(link) else ("recorded", "unknown")
    return "recorded", "unknown"


def classify_values(action: str, entity: str, before: dict | None, after: dict | None):
    """Return (code,stage,outcome), or None when retained evidence is insufficient."""
    if action in EXPLICIT:
        if action.startswith("security_otp_") and (after or {}).get("purpose") != "password_reset":
            return None
        return EXPLICIT[action]
    if entity == "RolePermissionOverride" and action in {"create", "update", "delete"}:
        values = after or before or {}
        if "allowed" in values and (
            values["allowed"] is None or isinstance(values["allowed"], bool)
        ):
            return "SEC-ROL", "committed", "completed"
    if action != "create":
        return None
    values = after or {}
    if (
        entity == "StockMovement"
        and values.get("type") in STOCK_CODES
        and values.get("qty_delta") is not None
        and values.get("batch_id")
    ):
        return STOCK_CODES[values["type"]], "committed", "completed"
    if entity in {"Payment", "MembershipPayment"} and values.get("amount_minor") is not None:
        return "POS-PAY", "committed", "completed"
    if entity == "ManualCollection" and values.get("amount_minor") is not None:
        return "FIN-COL", "committed", "completed"
    if entity == "GamingSessionExtension" and values.get("duration_minutes") is not None:
        return "GAM-EXT", "committed", "completed"
    if entity in REFUNDS:
        stage, outcome = refund_state(entity, values)
        return "POS-RFD", stage, outcome
    return None


def category_for(action: str, entity: str, code: str | None = None) -> str:
    if code in BY_CODE:
        return BY_CODE[code].category
    if action.startswith("login_"):
        return "login"
    return next((area for area, types in AREA_TYPES.items() if entity in types), "other")


def retained_reference(before: dict | None, after: dict | None) -> str | None:
    for values in (after or {}, before or {}):
        for field in REFERENCE_FIELDS:
            value = values.get(field)
            if isinstance(value, str) and value.strip():
                return value.strip()[:120]
    return None


def is_technical_companion(row: AuditLog) -> bool:
    return bool(
        row.catalogue_version
        and row.action_code is None
        and row.action in TECHNICAL_COMPANION_ACTIONS
        and row.entity_type in TECHNICAL_COMPANION_ENTITIES
        and classify_values(row.action, row.entity_type, row.before, row.after) is None
    )


def describe(row: AuditLog) -> dict[str, Any]:
    derived = classify_values(row.action, row.entity_type, row.before, row.after)
    code = row.action_code or (derived[0] if derived else None)
    definition = BY_CODE.get(code or "")
    stage = row.stage or (derived[1] if derived else "unknown")
    outcome = row.outcome or (derived[2] if derived else "unknown")
    legacy_failed = row.action == "login_failed"
    actor_kind = row.actor_kind or ("unauthenticated" if legacy_failed else "unverified_legacy")
    technical = is_technical_companion(row)
    classification_source = (
        "technical_companion"
        if technical
        else (
            "recorded"
            if row.catalogue_version and (row.action_code or derived)
            else (
                "unclassified_recorded"
                if row.catalogue_version
                else ("derived_legacy" if derived else "unclassified_legacy")
            )
        )
    )
    return {
        "action_code": code,
        "action_label": definition.label if definition else (
            "Technical record" if technical else (
                "Unclassified record" if row.catalogue_version else "Other / legacy activity"
            )
        ),
        "category": "system" if technical else (
            row.category or category_for(row.action, row.entity_type, code)
        ),
        "stage": stage,
        "outcome": outcome,
        "classification_source": classification_source,
        "catalogue_version": row.catalogue_version or CATALOGUE_VERSION,
        "actor_kind": actor_kind,
        "target_user_id": row.target_user_id or (row.actor_user_id if legacy_failed else None),
        "source_reference": row.source_reference or retained_reference(row.before, row.after),
        "operation_id": row.operation_id,
        "summary": definition.label
        if definition
        else f"{row.entity_type} {row.action.replace('_', ' ')}",
        "business_details": row.business_details
        or {key: value for key, value in (row.after or {}).items() if key in DETAIL_FIELDS},
    }


def code_expression():
    """SQL equivalent of classify_values so exact filters also include eligible legacy evidence."""
    conditions = [
        (AuditLog.action == action, literal(code))
        for action, (code, _, _) in EXPLICIT.items()
        if not action.startswith("security_otp_")
    ]
    conditions += [
        (
            and_(AuditLog.action == action, AuditLog.after["purpose"].astext == "password_reset"),
            literal(code),
        )
        for action, (code, _, _) in EXPLICIT.items()
        if action.startswith("security_otp_")
    ]
    conditions.append(
        (
            and_(
                AuditLog.entity_type == "RolePermissionOverride",
                AuditLog.action.in_(("create", "update", "delete")),
                or_(
                    func.jsonb_typeof(AuditLog.after["allowed"]).in_(("boolean", "null")),
                    func.jsonb_typeof(AuditLog.before["allowed"]).in_(("boolean", "null")),
                ),
            ),
            literal("SEC-ROL"),
        )
    )
    created = AuditLog.action == "create"
    for stock_type, code in STOCK_CODES.items():
        conditions.append(
            (
                and_(
                    created,
                    AuditLog.entity_type == "StockMovement",
                    AuditLog.after["type"].astext == stock_type,
                    AuditLog.after["qty_delta"].astext.is_not(None),
                    AuditLog.after["batch_id"].astext.is_not(None),
                ),
                literal(code),
            )
        )
    for entity, code, field in (
        ("Payment", "POS-PAY", "amount_minor"),
        ("MembershipPayment", "POS-PAY", "amount_minor"),
        ("ManualCollection", "FIN-COL", "amount_minor"),
        ("GamingSessionExtension", "GAM-EXT", "duration_minutes"),
    ):
        conditions.append(
            (
                and_(
                    created,
                    AuditLog.entity_type == entity,
                    AuditLog.after[field].astext.is_not(None),
                ),
                literal(code),
            )
        )
    conditions.append((and_(created, AuditLog.entity_type.in_(tuple(REFUNDS))), literal("POS-RFD")))
    return func.coalesce(AuditLog.action_code, case(*conditions, else_=None))


def technical_companion_expression():
    """Fresh, known ORM companions; never a blanket uncoded-row rule."""
    return and_(
        AuditLog.catalogue_version.is_not(None),
        AuditLog.action_code.is_(None),
        AuditLog.action.in_(TECHNICAL_COMPANION_ACTIONS),
        AuditLog.entity_type.in_(TECHNICAL_COMPANION_ENTITIES),
    )


def category_expression():
    code = code_expression()
    conditions = [
        (code.in_(tuple(d.code for d in CATALOGUE if d.category == area)), literal(area))
        for area in AREA_TYPES
    ]
    conditions.insert(
        0, (or_(AuditLog.action.like("login_%"), code == "SEC-LGN"), literal("login"))
    )
    conditions.extend(
        (AuditLog.entity_type.in_(types), literal(area)) for area, types in AREA_TYPES.items()
    )
    return case(
        (technical_companion_expression(), literal("system")),
        else_=func.coalesce(AuditLog.category, case(*conditions, else_="other")),
    )


def state_expression(index: int):
    conditions = [
        (AuditLog.action == action, literal(values[index]))
        for action, values in EXPLICIT.items()
        if not action.startswith("security_otp_")
    ]
    conditions += [
        (
            and_(AuditLog.action == action, AuditLog.after["purpose"].astext == "password_reset"),
            literal(values[index]),
        )
        for action, values in EXPLICIT.items()
        if action.startswith("security_otp_")
    ]

    def retained(*fields):
        return and_(
            *(
                and_(
                    AuditLog.after[field].astext.is_not(None),
                    func.trim(AuditLog.after[field].astext) != "",
                )
                for field in fields
            )
        )

    created = AuditLog.action == "create"

    def add(entity, proof, stage, outcome):
        conditions.append(
            (
                and_(created, AuditLog.entity_type == entity, proof),
                literal(stage if index == 1 else outcome),
            )
        )

    add(
        "PosRefundCashHandoffCompletion",
        retained("refund_request_id", "handed_over_at", "recorded_by"),
        "value_captured",
        "pending",
    )
    add(
        "PosRefundProviderSettlement",
        and_(
            retained(
                "refund_request_id", "provider_settled_at", "settled_by", "external_reference"
            ),
            AuditLog.after["settlement_method"].astext.in_(("card", "upi", "qr", "wallet")),
        ),
        "value_captured",
        "pending",
    )
    method = AuditLog.after["method"].astext
    add(
        "MembershipRefundCompletion",
        and_(
            retained("refund_id", "completed_at", "completed_by", "amount_minor"),
            or_(
                method == "cash",
                and_(method.in_(("card", "upi", "razorpay")), retained("external_reference")),
            ),
        ),
        "value_captured",
        "pending",
    )
    for entity in ("Refund", "MembershipRefundSettlement"):
        membership = entity == "MembershipRefundSettlement"
        method = AuditLog.after["method" if membership else "settlement_method"].astext
        proof = and_(
            retained(
                "amount_minor",
                "settled_at",
                "settled_by",
                "receipt_no",
                "refund_id" if membership else "request_id",
            ),
            or_(
                method == "cash",
                and_(
                    method.in_(
                        ("card", "upi", "razorpay")
                        if membership
                        else ("card", "upi", "qr", "wallet")
                    ),
                    retained("external_ref" if membership else "external_reference"),
                    literal(True) if membership else retained("provider_settled_at"),
                ),
            ),
        )
        add(entity, proof, "physically_settled", "completed")
    resolution = AuditLog.after["resolution"].astext
    add(
        "MembershipRefundResolution",
        and_(
            retained("refund_id", "resolved_at"),
            resolution.in_(("cash_not_handed_over", "provider_not_completed")),
        ),
        "withdrawn",
        "withdrawn",
    )
    add(
        "MembershipRefundResolution",
        and_(
            retained("refund_id", "resolved_at"),
            or_(
                resolution == "cash_returned",
                and_(resolution == "provider_reversed", retained("external_reference")),
            ),
        ),
        "reversed",
        "reversed",
    )
    add(
        "PosRefundWithdrawal",
        and_(
            retained("refund_request_id", "withdrawn_at"),
            resolution.in_(
                (
                    "cash_not_handed_over",
                    "cash_handoff_abandoned",
                    "provider_not_started",
                    "provider_payout_abandoned",
                )
            ),
        ),
        "withdrawn",
        "withdrawn",
    )
    for entity in ("PosRefundRequest", "MembershipRefund"):
        add(
            entity,
            retained("amount_minor", "order_id" if entity == "PosRefundRequest" else "payment_id"),
            "requested",
            "pending",
        )
    for entity in (
        "PosRefundCashHandoff",
        "PosRefundProviderPayoutStart",
        "MembershipRefundCashHandoff",
        "MembershipRefundProviderAction",
    ):
        add(
            entity,
            retained("refund_id" if entity.startswith("Membership") else "refund_request_id"),
            "awaiting_settlement",
            "pending",
        )
    conditions.append(
        (
            and_(created, AuditLog.entity_type.in_(tuple(REFUNDS))),
            literal("recorded" if index == 1 else "unknown"),
        )
    )
    conditions.append(
        (code_expression().is_not(None), literal("committed" if index == 1 else "completed"))
    )
    stored = AuditLog.stage if index == 1 else AuditLog.outcome
    return func.coalesce(stored, case(*conditions, else_="unknown"))

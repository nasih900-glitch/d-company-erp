package cloud.dcompany.erp.ui.screens.audit

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun auditActor(entry: AuditEntry): String =
    entry.actorName?.takeIf { it.isNotBlank() }
        ?: entry.actorEmail?.takeIf { it.isNotBlank() }
        ?: "System / script"

internal fun auditActionLabel(action: String): String = when (action) {
    "create" -> "Created"
    "update" -> "Updated"
    "delete" -> "Deleted"
    "login_success" -> "Login success"
    "login_failed" -> "Login failed"
    "audit_unlock_success" -> "Audit unlocked"
    "audit_unlock_failed" -> "Audit unlock failed"
    "pricing_unlock_success" -> "Pricing unlocked"
    "pricing_unlock_failed" -> "Pricing unlock failed"
    else -> action.replace('_', ' ').replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
}

internal fun auditEntityLabel(entityType: String): String = when (entityType) {
    "AuditAccess" -> "Audit access"
    "PricingAccess" -> "Pricing access"
    "User" -> "User account"
    "UserRole" -> "User role"
    "Order" -> "Order / bill"
    "OrderLine" -> "Order item"
    "Payment" -> "Payment"
    "Refund" -> "Refund"
    "PosRefundRequest" -> "POS refund request"
    "PosRefundCashHandoff" -> "POS cash refund handover started"
    "PosRefundCashHandoffCompletion" -> "POS cash refund handed over"
    "PosRefundProviderPayoutStart" -> "POS provider refund started"
    "PosRefundProviderSettlement" -> "POS provider refund completed"
    "PosRefundWithdrawal" -> "POS refund withdrawal"
    "PosRefundEvidenceReconciliation" -> "POS refund evidence reconciliation"
    "MembershipPaymentRequest" -> "Membership payment request"
    "MembershipPaymentCashCollection" -> "Membership cash collection started"
    "MembershipPaymentProviderAction" -> "Membership provider payment started"
    "MembershipPaymentRequestResolution" -> "Membership payment request resolution"
    "MembershipPayment" -> "Membership payment"
    "MembershipPaymentAttemptResolution" -> "Legacy membership payment recovery"
    "MembershipRefund" -> "Membership refund request"
    "MembershipRefundCashHandoff" -> "Membership refund cash handover started"
    "MembershipRefundProviderAction" -> "Membership provider refund started"
    "MembershipRefundSettlement" -> "Membership refund settlement"
    "MembershipRefundResolution" -> "Membership refund withdrawal"
    "Shift" -> "POS shift"
    "CustomerMembership" -> "Membership"
    "MembershipTier" -> "Membership plan"
    "StockMovement" -> "Stock movement"
    "PurchaseOrder" -> "Purchase order"
    "PurchaseOrderLine" -> "Purchase order item"
    "GoodsReceiptNote", "GRN" -> "Stock received"
    "JournalEntry" -> "Journal entry"
    "JournalLine" -> "Journal line"
    "ManualCollection" -> "Manual collection"
    "CapitalEntry" -> "Partner capital"
    "MenuCategory" -> "Menu category"
    "MenuItem" -> "Menu item"
    "Station" -> "Gaming / shisha station"
    "GamingSession" -> "Gaming / shisha session"
    "GamingBooking" -> "Gaming booking"
    "EventTicket" -> "Event ticket"
    else -> entityType.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
}

internal fun auditEntryTitle(entry: AuditEntry): String = when (entry.action) {
    "login_success" -> "${auditActor(entry)} logged in"
    "login_failed" -> "Failed login attempt"
    "audit_unlock_success" -> "${auditActor(entry)} unlocked the Audit Log"
    "audit_unlock_failed" -> "Audit Log unlock failed"
    "pricing_unlock_success" -> "${auditActor(entry)} unlocked pricing"
    "pricing_unlock_failed" -> "Pricing unlock failed"
    else -> "${auditEntityLabel(entry.entityType)} ${auditActionLabel(entry.action).lowercase()}"
}

internal fun auditConnectionLabel(entry: AuditEntry): String = when (entry.clientWasOffline) {
    true -> "Captured offline and later synced"
    false -> "Online"
    null -> "Legacy / server activity"
}

internal fun auditClientLabel(entry: AuditEntry): String? {
    val platform = entry.clientPlatform?.takeIf { it.isNotBlank() } ?: return null
    return if (entry.clientVersionCode == null) platform else "$platform build ${entry.clientVersionCode}"
}

internal fun shortAuditId(value: String): String =
    if (value.length <= 12) value else "${value.take(8)}…${value.takeLast(4)}"

internal fun formatAuditTimestamp(
    value: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrNull()
        ?: return value
    return DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss", locale)
        .withZone(zoneId)
        .format(instant)
}

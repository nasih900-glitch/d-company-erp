package cloud.dcompany.erp.ui.screens.audit

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import cloud.dcompany.erp.core.net.asRupees
import kotlinx.serialization.json.*

internal fun auditActor(entry: AuditEntry): String {
    val label = entry.actorName?.takeIf(String::isNotBlank) ?: "Recorded account identity"
    if (entry.actorUserId != null && entry.actorKind == "unverified_legacy") return "Unverified legacy attribution · $label"
    if (entry.actorUserId == null || Regex("unauthenticated|unverified|unknown").containsMatchIn(entry.actorKind)) return "Requester not verified"
    return when (entry.actorKind) {
        "authenticated" -> "Authenticated actor · $label"
        "otp_approved_requester" -> "OTP-approved requester · $label"
        "requester_recorded" -> "Recorded requester · $label"
        else -> "Recorded actor (${auditBusinessLabel(entry.actorKind)}) · $label"
    }
}

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

internal fun auditEntryTitle(entry: AuditEntry): String =
    entry.actionCode?.let { "$it · ${entry.actionLabel}" }
        ?: "${auditEntityLabel(entry.entityType)} · ${auditActionLabel(entry.action)} (legacy / technical)"

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


internal fun auditBusinessLabel(value: String): String = when (value) {
    "qty_delta" -> "Quantity change"
    "qty" -> "Quantity"
    "base_unit" -> "Stock unit"
    "play_elapsed_ms" -> "Accumulated playing time"
    "before_roles" -> "Roles before"
    "after_roles" -> "Roles after"
    "original_method" -> "Originally recorded method"
    "effective_method" -> "Effective payment method"
    "before_cash_minor" -> "Recorded Cash before"
    "before_upi_minor" -> "Recorded UPI before"
    "actual_cash_minor" -> "Owner-confirmed Cash"
    "actual_upi_minor" -> "Owner-confirmed UPI"
    "cash_delta_minor" -> "Cash classification change"
    "upi_delta_minor" -> "UPI classification change"
    "source_allocation" -> "Allocation evidence"
    "evidence_kind" -> "Evidence basis"
    else -> value.removeSuffix("_minor").replace('_',' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

private val secretAuditField = Regex("password|secret|token|otp|authorization|cookie|private.?key|api.?key|access.?key",RegexOption.IGNORE_CASE)
private val technicalAuditField = Regex("(^id$|_id$|_ids$|fingerprint|hash|idempotency|client_action|request_id|email|phone)",RegexOption.IGNORE_CASE)

internal fun redactAuditValue(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key,item) -> if(secretAuditField.containsMatchIn(key)) JsonPrimitive("[redacted]") else redactAuditValue(item) })
    is JsonArray -> JsonArray(value.map(::redactAuditValue))
    else -> value
}

internal fun auditBusinessValue(key: String,value: JsonElement?,zone: ZoneId): String {
    if(secretAuditField.containsMatchIn(key)) return "[redacted]"
    if(value == null || value is JsonNull) return "Not recorded"
    if(value is JsonArray) return if(value.isEmpty()) { if(key.contains("roles")) "No roles" else "None retained" }
        else value.joinToString(" · ") { if(it is JsonPrimitive && it.isString) auditBusinessLabel(it.content) else auditBusinessValue(key,it,zone) }
    if(value is JsonObject) {
        val business=value.entries.filterNot { technicalAuditField.containsMatchIn(it.key) || secretAuditField.containsMatchIn(it.key) }
        return business.takeIf { it.isNotEmpty() }?.joinToString("; ") { (field,item) ->
            "${auditBusinessLabel(field)}: ${auditBusinessValue(field,item,zone)}"
        } ?: "Retained identities are available in Technical details"
    }
    val primitive=value as? JsonPrimitive ?: return "Not recorded"
    if(key.endsWith("_minor")) primitive.longOrNull?.let { return it.asRupees() }
    primitive.booleanOrNull?.let { return if(it) "Yes" else "No" }
    if(key.endsWith("_at")) return "${formatAuditTimestamp(primitive.content,zone)} · ${zone.id}"
    if(key.endsWith("_ms")) primitive.longOrNull?.let {
        val seconds=it/1000
        return "${seconds/3600} hr ${(seconds/60)%60} min ${seconds%60} sec playing time"
    }
    if(key in setOf("method","payment_method","original_method","effective_method")) return primitive.content.uppercase(Locale.ROOT)
    return primitive.content.takeIf(String::isNotBlank) ?: "Not recorded"
}

internal fun auditBusinessFacts(entry: AuditEntry): List<Pair<String,JsonElement>> =
    entry.businessDetails.orEmpty().entries.filterNot { technicalAuditField.containsMatchIn(it.key) || secretAuditField.containsMatchIn(it.key) }
        .map { it.key to it.value }

internal fun auditBusinessSummary(entry: AuditEntry): String {
    val facts=entry.businessDetails.orEmpty()
    val pieces=mutableListOf<String>()
    ((facts["amount_minor"] ?: facts["line_total_minor"] ?: facts["total_minor"]) as? JsonPrimitive)?.longOrNull?.let { pieces += it.asRupees() }
    val method=(facts["effective_method"] ?: facts["payment_method"] ?: facts["method"]) as? JsonPrimitive
    if(method != null) {
        val original=(facts["original_method"] as? JsonPrimitive)?.content
        pieces += (original?.let { "${it.uppercase(Locale.ROOT)} → " }.orEmpty()+method.content.uppercase(Locale.ROOT))
    }
    val quantity=facts["qty_delta"] ?: facts["qty"]
    if(quantity != null) {
        pieces += ((facts["menu_item_name"] ?: facts["ingredient_name_snapshot"] ?: facts["ingredient_name"]) as? JsonPrimitive)?.content ?: "Item name not retained"
        pieces += (((quantity as? JsonPrimitive)?.content ?: "Quantity not retained")+((facts["base_unit"] ?: facts["unit"]) as? JsonPrimitive)?.content?.let { " $it" }.orEmpty())
    }
    if(facts["before_roles"] is JsonArray || facts["after_roles"] is JsonArray) pieces += "Roles: ${auditBusinessValue("before_roles",facts["before_roles"],ZoneId.of("UTC"))} → ${auditBusinessValue("after_roles",facts["after_roles"],ZoneId.of("UTC"))}"
    return pieces.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: entry.summary.takeIf { it.isNotBlank() && it != entry.actionLabel }
        ?: "No additional business facts retained"
}

internal fun auditOrderId(entry: AuditEntry): String? {
    val retained=entry.businessDetails?.get("order_id") ?: entry.after?.get("order_id") ?: entry.before?.get("order_id")
    val candidate=(retained as? JsonPrimitive)?.content ?: entry.entityId.takeIf { entry.entityType == "Order" }
    return candidate?.takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }
}

internal fun auditBusinessChanges(entry: AuditEntry): List<Triple<String,JsonElement?,JsonElement?>> {
    val before=entry.before.orEmpty()
    val after=entry.after.orEmpty()
    return (before.keys+after.keys).distinct().filterNot {
        technicalAuditField.containsMatchIn(it) || secretAuditField.containsMatchIn(it) ||
            it in setOf("created_at","updated_at","auth_version","token_version")
    }.filter { before[it] != after[it] }.map { Triple(it,before[it],after[it]) }
}

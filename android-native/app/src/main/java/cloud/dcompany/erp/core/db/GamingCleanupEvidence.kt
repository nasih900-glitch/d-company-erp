package cloud.dcompany.erp.core.db

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class GamingCleanupLocalSnapshot(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("started_at_millis") val startedAtMillis: Long,
    @SerialName("ended_at_millis") val endedAtMillis: Long,
    @SerialName("timer_minutes") val timerMinutes: Int? = null,
    @SerialName("timer_ends_at_millis") val timerEndsAtMillis: Long? = null,
    @SerialName("billable_minutes") val billableMinutes: Int? = null,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    @SerialName("rate_per_hour_minor") val ratePerHourMinor: Long,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("customer_directory_revision") val customerDirectoryRevision: Long? = null,
    @SerialName("customer_directory_company_id") val customerDirectoryCompanyId: String? = null,
    @SerialName("package_id") val packageId: String? = null,
    @SerialName("billing_mode") val billingMode: String? = null,
    @SerialName("package_price_minor") val packagePriceMinor: Long? = null,
    @SerialName("package_duration_minutes") val packageDurationMinutes: Int? = null,
    @SerialName("package_variant") val packageVariant: String? = null,
    @SerialName("package_station_type_snapshot") val packageStationTypeSnapshot: String? = null,
    @SerialName("package_pricing_tier_snapshot") val packagePricingTierSnapshot: String? = null,
    @SerialName("extra_controllers") val extraControllers: Int,
    @SerialName("player_count") val playerCount: Int? = null,
    @SerialName("evidence_revision") val evidenceRevision: Long,
)

private val cleanupJson = Json { explicitNulls = false }

/**
 * Durable, deliberately non-sensitive operator feedback for the protected
 * stale-session cleanup protocol. These exact values may be stored in
 * [LocalGamingSessionEntity.lastError]; keep them free of customer details,
 * identifiers, exception text, and server response bodies.
 *
 * Updating this status must never advance cleanupEvidenceRevision because it
 * is operational feedback, not part of the immutable billing candidate.
 */
enum class GamingCleanupWorkflowStatus(val persistedMessage: String) {
    EVIDENCE_INCOMPLETE(
        "This stale session cannot be sent for owner review because its captured shift, stop time, or rate evidence is incomplete. Refresh Gaming, then ask the protected owner to review the tablet evidence if it remains blocked.",
    ),
    REPORT_RETRY_REQUIRED(
        "Stale-session evidence could not reach the ERP. Keep this tablet online and refresh Gaming. If this repeats, ask the protected owner to check Web Gaming and this device's connection.",
    ),
    WAITING_FOR_OWNER(
        "Stale-session evidence is verified and waiting for protected-owner approval in Web Gaming. Keep this tablet signed in and online, then refresh Gaming after approval.",
    ),
    APPROVED_WAITING_FOR_APPLY(
        "The protected owner approved this exact stale-session cleanup. Keep this tablet online and refresh Gaming until the station becomes available.",
    ),
    REVIEW_REQUIRED(
        "The stale-session evidence changed or no longer matches the ERP review. Refresh Gaming and ask the protected owner to review the current candidate; do not create a replacement record.",
    ),
    ACKNOWLEDGEMENT_PENDING(
        "The stale station overlay was retired on this tablet, but the ERP acknowledgement is still pending. Keep this tablet online and refresh Gaming; do not recreate the old session.",
    ),
    ACKNOWLEDGEMENT_REVIEW_REQUIRED(
        "The stale station overlay was retired on this tablet, but the ERP acknowledgement did not match its cleanup receipt. Ask the protected owner to review Web Gaming; do not recreate the old session.",
    ),
}

fun gamingCleanupWorkflowMessageOrNull(value: String?): String? =
    GamingCleanupWorkflowStatus.entries
        .firstOrNull { it.persistedMessage == value }
        ?.persistedMessage

fun LocalGamingSessionEntity.cleanupSnapshotOrNull(): GamingCleanupLocalSnapshot? {
    val shift = shiftId ?: return null
    val ended = endAtMillis ?: return null
    val rate = ratePerHourMinor ?: return null
    val players = packageId?.let {
        when (packageVariant) {
            "single" -> 1
            "dual" -> 2 + extraControllers
            else -> null
        }
    }
    return GamingCleanupLocalSnapshot(
        shiftId = shift,
        startedAtMillis = startedAtMillis,
        endedAtMillis = ended,
        timerMinutes = timerMinutes,
        timerEndsAtMillis = timerEndsAtMillis,
        billableMinutes = billableMinutes,
        amountMinor = amountMinor,
        ratePerHourMinor = rate,
        customerId = customerId,
        customerName = customerName,
        customerPhone = customerPhone,
        customerDirectoryRevision = customerDirectoryRevision,
        customerDirectoryCompanyId = customerDirectoryCompanyId,
        packageId = packageId,
        billingMode = billingMode,
        packagePriceMinor = packagePriceMinor,
        packageDurationMinutes = packageDurationMinutes,
        packageVariant = packageVariant,
        packageStationTypeSnapshot = packageStationTypeSnapshot,
        packagePricingTierSnapshot = packagePricingTierSnapshot,
        extraControllers = extraControllers,
        playerCount = players,
        evidenceRevision = cleanupEvidenceRevision,
    )
}

fun gamingCleanupSnapshotSha256(snapshot: GamingCleanupLocalSnapshot): String {
    val encoded = cleanupJson.encodeToJsonElement(
        GamingCleanupLocalSnapshot.serializer(), snapshot,
    ).jsonObject
    val canonical = cleanupJson.encodeToString(JsonObject(encoded.toSortedMap()))
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

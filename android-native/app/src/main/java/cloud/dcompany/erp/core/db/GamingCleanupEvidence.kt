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

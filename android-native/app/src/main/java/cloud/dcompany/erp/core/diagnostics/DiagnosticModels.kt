package cloud.dcompany.erp.core.diagnostics

import android.os.Build
import cloud.dcompany.erp.BuildConfig
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val MAX_DIAGNOSTIC_OUTBOX_ROWS = 200
internal const val MAX_DIAGNOSTIC_UPLOAD_BATCH = 25
internal const val MAX_REPORTED_PENDING_OUTBOX = 1_000_000

internal enum class DiagnosticEventType(val wireValue: String) {
    CRASH("crash"),
    ANR("anr"),
    API_FAILURE("api_failure"),
    SYNC_STALL("sync_stall"),
}

internal enum class DiagnosticSeverity(val wireValue: String) {
    WARNING("warning"),
    ERROR("error"),
    CRITICAL("critical"),
}

internal enum class DiagnosticComponent(val wireValue: String) {
    APP("app"),
    AUTH("auth"),
    GAMING("gaming"),
    POS("pos"),
    FINANCE("finance"),
    SYNC("sync"),
    NETWORK("network"),
    UPDATES("updates"),
    STORAGE("storage"),
}

internal enum class DiagnosticConnectivity(val wireValue: String) {
    ONLINE("online"),
    OFFLINE("offline"),
    UNKNOWN("unknown"),
}

internal enum class DiagnosticDurationBucket(val wireValue: String) {
    UNDER_5S("under_5s"),
    FIVE_TO_30S("5_to_30s"),
    THIRTY_SECONDS_TO_2M("30s_to_2m"),
    TWO_TO_10M("2_to_10m"),
    OVER_10M("over_10m"),
}

/**
 * Fully normalised diagnostic event. Every text field is selected from a
 * closed enum or a fixed reason-code table before it reaches durable storage.
 * There is intentionally nowhere to put an exception message, URL, header,
 * request/response body, token, employee/customer id, or payment detail.
 */
internal data class DiagnosticEvent(
    val clientEventId: String = UUID.randomUUID().toString(),
    /** Local-only source identity used to deduplicate OS exit history imports. */
    val localDedupeKey: String? = null,
    val eventType: DiagnosticEventType,
    val severity: DiagnosticSeverity,
    val occurredAtMillis: Long,
    val capturedVersionName: String = BuildConfig.VERSION_NAME,
    val capturedVersionCode: Int = BuildConfig.VERSION_CODE,
    val capturedOsApiLevel: Int = Build.VERSION.SDK_INT.coerceIn(21, 100),
    val component: DiagnosticComponent,
    val reasonCode: String,
    val failureFingerprint: String? = null,
    val httpStatus: Int? = null,
    val durationBucket: DiagnosticDurationBucket? = null,
    val connectivity: DiagnosticConnectivity = DiagnosticConnectivity.UNKNOWN,
    val pendingOutboxCount: Int? = null,
) {
    init {
        require(isCanonicalUuid(clientEventId))
        require(localDedupeKey == null || SHA_256_HEX.matches(localDedupeKey))
        require(occurredAtMillis > 0)
        require(SAFE_VERSION_NAME.matches(capturedVersionName))
        require(capturedVersionCode > 0)
        require(capturedOsApiLevel in 21..100)
        require(isSafeReasonCode(reasonCode))
        require(failureFingerprint == null || SHA_256_HEX.matches(failureFingerprint))
        require(httpStatus == null || httpStatus in 100..599)
        require(httpStatus == null || eventType == DiagnosticEventType.API_FAILURE)
        require(pendingOutboxCount == null || pendingOutboxCount in 0..MAX_REPORTED_PENDING_OUTBOX)
    }
}

@Serializable
internal data class ClientDiagnosticBatchRequest(
    @SerialName("installation_id") val installationId: String,
    val events: List<ClientDiagnosticEventRequest>,
) {
    init {
        require(isCanonicalUuid(installationId))
        require(events.size in 1..MAX_DIAGNOSTIC_UPLOAD_BATCH)
    }
}

@Serializable
internal data class ClientDiagnosticEventRequest(
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("event_type") val eventType: String,
    val severity: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("os_api_level") val osApiLevel: Int? = null,
    val component: String,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("failure_fingerprint") val failureFingerprint: String? = null,
    @SerialName("http_status") val httpStatus: Int? = null,
    @SerialName("duration_bucket") val durationBucket: String? = null,
    val connectivity: String,
    @SerialName("pending_outbox_count") val pendingOutboxCount: Int? = null,
)

@Serializable
internal data class ClientDiagnosticBatchResponse(
    @SerialName("installation_id") val installationId: String,
    @SerialName("server_time") val serverTime: String,
    @SerialName("accepted_event_ids") val acceptedEventIds: List<String> = emptyList(),
    @SerialName("duplicate_event_ids") val duplicateEventIds: List<String> = emptyList(),
)

internal fun DiagnosticEvent.toWireRequest(): ClientDiagnosticEventRequest =
    ClientDiagnosticEventRequest(
        clientEventId = clientEventId,
        eventType = eventType.wireValue,
        severity = severity.wireValue,
        occurredAt = Instant.ofEpochMilli(occurredAtMillis).toString(),
        versionName = capturedVersionName,
        versionCode = capturedVersionCode,
        osApiLevel = capturedOsApiLevel,
        component = component.wireValue,
        reasonCode = reasonCode,
        failureFingerprint = failureFingerprint,
        httpStatus = httpStatus,
        durationBucket = durationBucket?.wireValue,
        connectivity = connectivity.wireValue,
        pendingOutboxCount = pendingOutboxCount,
    )

internal fun isSafeReasonCode(value: String): Boolean = SAFE_REASON_CODE.matches(value)

internal fun isCanonicalUuid(value: String): Boolean {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return false
    return parsed.version() == 4 && parsed.toString() == value.lowercase()
}

/** Stable local ownership binding. The hash itself is never sent to the API. */
internal fun diagnosticScopeHash(companyId: String, userId: String, branchId: String?): String =
    sha256Hex(
        listOf(companyId.trim(), userId.trim(), branchId?.trim().orEmpty()).joinToString("\u0000"),
    )

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private val SAFE_REASON_CODE = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
private val SHA_256_HEX = Regex("^[0-9a-f]{64}$")
private val SAFE_VERSION_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$")

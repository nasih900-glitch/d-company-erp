package cloud.dcompany.erp.ui.screens.shift.work

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val StartWorkJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true }
internal const val START_WORK_DIRECTORY = "start-work-intents-v1"
internal const val START_WORK_PATH = "/api/v1/pos/shifts/start-work"
internal fun workDigest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun workUuid(value: String) { require(UUID.fromString(value).toString() == value) }

@Serializable internal data class StartWorkBody(@SerialName("opening_float_minor") val openingFloatMinor: Long)

@Serializable internal data class StartWorkReference(
    @SerialName("action_id") val actionId: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("actor_user_id") val actorId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("installation_id") val installationId: String?,
    @SerialName("client_platform") val platform: String = "android",
    @SerialName("opening_float_minor") val openingFloatMinor: Long,
    @SerialName("request_hash") val requestHash: String,
) {
    val actionKey get() = "shift-open:$actionId"
    val scopeKey get() = workDigest(listOf(actorId, companyId, branchId, terminalId).joinToString("|"))
    fun validate() {
        listOf(actionId, companyId, actorId, branchId, terminalId).forEach(::workUuid)
        require(UUID.fromString(actionId).version() == 4 && openingFloatMinor >= 0)
        require(platform in setOf("android", "web", "ios"))
        installationId?.let { workUuid(it); require(UUID.fromString(it).version() == 4) }
        require(platform != "android" || installationId != null)
        require(requestHash == expectedHash())
    }
    fun expectedHash() = workDigest("POST\n$START_WORK_PATH\n\n$actionKey\n{\"opening_float_minor\":$openingFloatMinor}")
}

@Serializable internal data class StartWorkReceipt(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("actor_user_id") val actorId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("installation_id") val installationId: String?,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("shift_status_at_start") val statusAtStart: String,
    @SerialName("opening_float_minor") val openingFloatMinor: Long,
    @SerialName("attendance_id") val attendanceId: String,
    @SerialName("clock_in_at") val clockInAt: String,
    @SerialName("attendance_created") val attendanceCreated: Boolean,
    @SerialName("original_attendance_preserved") val attendancePreserved: Boolean,
    @SerialName("started_at") val startedAt: String,
) {
    fun validate(ref: StartWorkReference) {
        listOf(receiptId, shiftId, attendanceId).forEach(::workUuid)
        require(companyId == ref.companyId && actorId == ref.actorId && branchId == ref.branchId && terminalId == ref.terminalId)
        require(installationId == ref.installationId && openingFloatMinor == ref.openingFloatMinor && statusAtStart == "open")
        require(attendanceCreated != attendancePreserved)
        val clock = OffsetDateTime.parse(clockInAt).toInstant()
        val start = OffsetDateTime.parse(startedAt).toInstant()
        require(clock <= start && (!attendanceCreated || clock == start))
    }
}

@Serializable internal data class StartWorkCurrentShift(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    val status: String,
    @SerialName("opening_client_platform") val platform: String,
    @SerialName("opening_client_installation_id") val installationId: String?,
)

@Serializable internal data class StartWorkCancellation(
    val id: String,
    @SerialName("action_key") val actionKey: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("actor_user_id") val actorId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("installation_id") val installationId: String?,
    @SerialName("opening_float_minor") val openingFloatMinor: Long,
    @SerialName("request_hash") val requestHash: String,
    @SerialName("client_platform") val platform: String,
    @SerialName("cancelled_by") val cancelledBy: String,
    @SerialName("cancelled_at") val cancelledAt: String,
    val reason: String,
)

@Serializable internal data class StartWorkOutcome(
    val state: String,
    val receipt: StartWorkReceipt? = null,
    val cancellation: StartWorkCancellation? = null,
    @SerialName("current_shift") val currentShift: StartWorkCurrentShift? = null,
) {
    fun validate(ref: StartWorkReference) {
        ref.validate()
        when (state) {
            "unconfirmed" -> require(receipt == null && cancellation == null && currentShift == null)
            "committed" -> {
                require(cancellation == null); val r = requireNotNull(receipt); r.validate(ref)
                val s = requireNotNull(currentShift)
                require(s.id == r.shiftId && s.companyId == ref.companyId && s.branchId == ref.branchId &&
                    s.terminalId == ref.terminalId && s.platform == ref.platform && s.installationId == ref.installationId && s.status in setOf("open", "closed"))
            }
            "cancelled" -> {
                require(receipt == null && currentShift == null); val c = requireNotNull(cancellation)
                workUuid(c.id); workUuid(c.cancelledBy); OffsetDateTime.parse(c.cancelledAt)
                require(c.actionKey == ref.actionKey && c.companyId == ref.companyId && c.actorId == ref.actorId &&
                    c.branchId == ref.branchId && c.terminalId == ref.terminalId && c.installationId == ref.installationId &&
                    c.openingFloatMinor == ref.openingFloatMinor && c.requestHash == ref.requestHash && c.platform == ref.platform && c.reason.trim().length in 12..500)
            }
            else -> error("Unknown start-work result. Keep the original request.")
        }
    }
}

@Serializable internal data class PendingStartWork(
    val version: Int = 1,
    val reference: StartWorkReference,
    val actorName: String,
    val authBinding: String,
    val phase: String = "UNKNOWN",
    val receipt: StartWorkReceipt? = null,
    val resolution: StartWorkOutcome? = null,
    val cancelReason: String? = null,
    val rejectedStatus: Int? = null,
) {
    fun validate() {
        require(version == 1 && actorName.length in 1..200 && authBinding.matches(Regex("[0-9a-f]{64}")))
        require(rejectedStatus == null || rejectedStatus in setOf(400,401,403,404,422))
        require(phase != "REJECTED" || rejectedStatus != null)
        reference.validate(); require(phase in setOf("UNKNOWN", "REJECTED", "CONFIRMED_AWAITING_HYDRATION", "RESOLVED"))
        receipt?.validate(reference)
        require(phase != "CONFIRMED_AWAITING_HYDRATION" || receipt != null)
        resolution?.validate(reference)
        require(phase != "RESOLVED" || resolution?.state in setOf("committed", "cancelled"))
        require(cancelReason == null || cancelReason.trim().length in 12..500)
    }
    val fingerprint get() = workDigest(StartWorkJson.encodeToString(reference))
    override fun toString() = "PendingStartWork(<redacted>, phase=$phase)"
}

/** Historical outcome identity is immutable; only its later shift observation can change. */
internal fun validateArchivedStartWork(expected: PendingStartWork, retained: PendingStartWork, outcome: StartWorkOutcome) {
    outcome.validate(expected.reference)
    check(retained.reference == expected.reference && retained.authBinding == expected.authBinding && retained.phase == "RESOLVED")
    val previous = requireNotNull(retained.resolution)
    previous.validate(expected.reference)
    check(previous.state == outcome.state && previous.receipt == outcome.receipt && previous.cancellation == outcome.cancellation)
}

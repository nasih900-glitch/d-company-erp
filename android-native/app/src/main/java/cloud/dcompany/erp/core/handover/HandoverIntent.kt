package cloud.dcompany.erp.core.handover

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun handoverUuid(value: String) {
    require(value.length == 36 && UUID.fromString(value).toString() == value) { "Invalid handover identity." }
}

@Serializable
internal data class HandoverScope(val actorId: String, val companyId: String, val branchId: String?, val terminalId: String?) {
    fun validate() { handoverUuid(actorId); handoverUuid(companyId); branchId?.let(::handoverUuid); terminalId?.let(::handoverUuid) }
    fun sameWorkspace(other: HandoverScope) = companyId == other.companyId && branchId == other.branchId && terminalId == other.terminalId
}

/** A process/session authority epoch, never an access/refresh token or credential fingerprint. */
@Serializable
internal data class HandoverAuthority(val scope: HandoverScope, val epoch: Long) {
    fun validate() { scope.validate(); require(epoch > 0) }
}

@Serializable
internal data class HandoverAction(
    val key: String,
    val origin: HandoverAuthority,
    val shiftId: String,
    val incomingUserId: String,
    val expectedRevision: Int,
) {
    fun validate() {
        handoverUuid(key); origin.validate(); handoverUuid(shiftId); handoverUuid(incomingUserId)
        require(origin.scope.branchId != null && origin.scope.terminalId != null) { "A till handover requires a verified branch and terminal." }
        require(incomingUserId != origin.scope.actorId && expectedRevision in 0 until Int.MAX_VALUE)
    }
}

@Serializable internal enum class HandoverStage { NOT_SENT, SENT_UNKNOWN, ACCEPTED }

@Serializable
internal data class AcceptedHandover(
    val receiptId: String,
    val actionKey: String,
    val companyId: String,
    val branchId: String,
    val terminalId: String,
    val shiftId: String,
    val initiatedBy: String,
    val incomingUserId: String,
    val fromRevision: Int,
    val toRevision: Int,
) {
    fun validate(action: HandoverAction) {
        handoverUuid(receiptId)
        require(actionKey == action.key && companyId == action.origin.scope.companyId &&
            branchId == action.origin.scope.branchId && terminalId == action.origin.scope.terminalId &&
            shiftId == action.shiftId && initiatedBy == action.origin.scope.actorId && incomingUserId == action.incomingUserId &&
            fromRevision == action.expectedRevision && toRevision == fromRevision + 1)
    }
}

@Serializable
internal data class HandoverIntent(
    val action: HandoverAction,
    val stage: HandoverStage = HandoverStage.NOT_SENT,
    val receipt: AcceptedHandover? = null,
    // Missing legacy data is unknown, never a reconstructed clean-operation receipt.
    val priorMutationUncertainty: Boolean = true,
) {
    fun validate() {
        action.validate()
        if (stage == HandoverStage.ACCEPTED) requireNotNull(receipt).validate(action) else require(receipt == null)
    }
    override fun toString() = "HandoverIntent(stage=$stage, identity=<redacted>)"
}

internal val HandoverJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

internal interface HandoverIntentStore {
    suspend fun read(): HandoverIntent?
    suspend fun begin(intent: HandoverIntent)
    suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent)
    suspend fun cancelNotSent(expected: HandoverIntent)
    suspend fun completeAccepted(expected: HandoverIntent) { error("Accepted handover storage is unavailable.") }
}

internal fun validateHandoverTransition(expected: HandoverIntent, next: HandoverIntent) {
    expected.validate(); next.validate()
    require(expected.action == next.action) { "The original handover action cannot change." }
    if (expected.stage == HandoverStage.NOT_SENT && next.stage == HandoverStage.SENT_UNKNOWN) {
        require(!expected.priorMutationUncertainty && !next.priorMutationUncertainty)
    }
    val bookkeepingOnly = expected.copy(priorMutationUncertainty = next.priorMutationUncertainty) == next
    require(bookkeepingOnly ||
        (expected.stage == HandoverStage.NOT_SENT && next.stage == HandoverStage.SENT_UNKNOWN) ||
        (expected.stage == HandoverStage.SENT_UNKNOWN && next.stage == HandoverStage.ACCEPTED)) {
        "A dispatched handover cannot be cancelled or rewritten."
    }
}

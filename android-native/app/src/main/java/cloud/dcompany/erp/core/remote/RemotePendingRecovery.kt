package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.handover.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response

internal enum class RemotePendingKind { DECISION, REVOKE, END }
internal enum class RemoteResolutionKind { MATCHED_ACTION_ACK, TERMINAL_SUPERSEDED }

/** Only the retained semantic action; never bearer, proof, nonce or credential-derived material. */
@Serializable
internal data class RemotePendingAction(val kind: RemotePendingKind, val targetId: String,
    val actionId: String, val decision: RemoteGrantDecisionWire? = null, val reason: String? = null) {
    fun validate() {
        require(isCanonicalUuidV4(targetId) && isCanonicalUuidV4(actionId))
        require(when (kind) {
            RemotePendingKind.DECISION -> decision != null && reason == null
            RemotePendingKind.REVOKE -> decision == null && reason == null
            RemotePendingKind.END -> decision == null && reason in REMOTE_DEVICE_END_REASONS
        })
    }
    fun matches(state: PersistedRemoteAssistanceState): Boolean = when (kind) {
        RemotePendingKind.DECISION -> state.pendingDecision == PendingRemoteGrantDecision(targetId, requireNotNull(decision), actionId)
        RemotePendingKind.REVOKE -> state.pendingRevocation == PendingRemoteGrantRevocation(targetId, actionId)
        RemotePendingKind.END -> state.pendingSessionEnd == PendingRemoteSessionEnd(targetId, actionId, requireNotNull(reason))
    }
    fun identity(scope: HandoverScope, installationId: String): KnownJournalAttempt {
        validate(); require(isCanonicalUuidV4(installationId))
        val stable = listOf(scope.companyId, scope.actorId, installationId, kind.name, targetId,
            actionId, decision?.name.orEmpty(), reason.orEmpty()).joinToString("\n").toByteArray()
        return try { KnownJournalAttempt(KnownJournalDomain.REMOTE_RECEIPT, scope, actionId, targetId,
            when (kind) { RemotePendingKind.DECISION -> "remote-grant-decision"; RemotePendingKind.REVOKE -> "remote-grant-revoke"; RemotePendingKind.END -> "remote-session-end" },
            remoteSha256Hex(stable)).also { it.validate() } } finally { stable.fill(0) }
    }
    override fun toString() = "RemotePendingAction(<redacted>)"
    companion object {
        fun decision(value: PendingRemoteGrantDecision) = RemotePendingAction(RemotePendingKind.DECISION, value.grantId, value.decisionId, value.decision)
        fun revoke(value: PendingRemoteGrantRevocation) = RemotePendingAction(RemotePendingKind.REVOKE, value.grantId, value.revocationId)
        fun end(value: PendingRemoteSessionEnd) = RemotePendingAction(RemotePendingKind.END, value.sessionId, value.endId, reason = value.reason)
    }
}

@Serializable
internal data class RemotePendingResolution(val action: RemotePendingAction, val kind: RemoteResolutionKind,
    val observedStatus: String) {
    fun valid(): Boolean = runCatching {
        action.validate()
        require(if (action.kind == RemotePendingKind.END) observedStatus in setOf("ended", "expired")
            else observedStatus in setOf("requested", "active", "declined", "revoked", "expired", "consumed"))
        if (kind == RemoteResolutionKind.MATCHED_ACTION_ACK) require(when (action.kind) {
            RemotePendingKind.END -> observedStatus == "ended"
            RemotePendingKind.REVOKE -> observedStatus == "revoked"
            RemotePendingKind.DECISION -> if (action.decision == RemoteGrantDecisionWire.DECLINED) observedStatus == "declined"
                else observedStatus in setOf("active", "consumed", "revoked", "expired")
        })
        if (kind == RemoteResolutionKind.TERMINAL_SUPERSEDED) require(observedStatus in
            if (action.kind == RemotePendingKind.END) setOf("ended", "expired") else setOf("declined", "revoked", "expired"))
    }.isSuccess
}

internal fun resolvedRemotePendingState(expected: PersistedRemoteAssistanceState, action: RemotePendingAction,
    resolution: RemotePendingResolution): PersistedRemoteAssistanceState? {
    if (!action.matches(expected) || resolution.action != action || !resolution.valid()) return null
    var next = when (action.kind) {
        RemotePendingKind.DECISION -> expected.copy(pendingDecision = null)
        RemotePendingKind.REVOKE -> expected.copy(pendingRevocation = null)
        RemotePendingKind.END -> expected.copy(pendingSessionEnd = null)
    }
    if (action.kind != RemotePendingKind.END && next.grantId == action.targetId &&
        resolution.observedStatus in setOf("declined", "revoked", "expired")) {
        next = next.copy(consent = if (resolution.observedStatus == "declined" && next.consentChoice != RemoteConsentChoice.REVOKED)
            RemoteConsentChoice.DENIED.storedValue else RemoteConsentChoice.REVOKED.storedValue)
    }
    return next.copy(lastMutationResolution = resolution).takeIf { validRemoteAssistanceState(it) != null }
}

@Serializable
internal data class RemoteGrantStatusResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("installation_id") val installationId: String,
    val id: String, val kind: String, val status: String,
    @SerialName("requested_for_user_id") val requestedForUserId: String,
    @SerialName("responded_by_user_id") val respondedByUserId: String?,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("responded_at") val respondedAt: String?,
    @SerialName("decision_id") val decisionId: String?,
    @SerialName("revoked_at") val revokedAt: String?,
    @SerialName("revocation_id") val revocationId: String?,
    @SerialName("consumed_at") val consumedAt: String?,
)

@Serializable
internal data class RemoteSessionStatusResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("installation_id") val installationId: String,
    val id: String, @SerialName("grant_id") val grantId: String, val status: String,
    @SerialName("requested_for_user_id") val requestedForUserId: String,
    @SerialName("responded_by_user_id") val respondedByUserId: String?,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("request_expires_at") val requestExpiresAt: String,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("ended_at") val endedAt: String?,
    @SerialName("end_id") val endId: String?,
    @SerialName("end_reason") val endReason: String?,
)

private fun evidenceTime(raw: String?, start: Instant, now: Instant): Instant? =
    parseRemoteInstant(raw)?.takeIf { !it.isBefore(start) && !it.isAfter(now) }

internal fun classifyRemoteGrantStatus(action: RemotePendingAction, scope: HandoverScope, installationId: String,
    value: RemoteGrantStatusResponse): RemotePendingResolution? {
    if (action.kind == RemotePendingKind.END || value.id != action.targetId || value.companyId != scope.companyId ||
        value.installationId != installationId || value.requestedForUserId != scope.actorId ||
        (value.respondedByUserId != null && value.respondedByUserId != scope.actorId)) return null
    val now = parseRemoteInstant(value.serverTime) ?: return null
    val start = parseRemoteInstant(value.requestedAt)?.takeIf { !it.isAfter(now) } ?: return null
    val expires = parseRemoteInstant(value.expiresAt)?.takeIf { it.isAfter(start) } ?: return null
    val kind = RemoteGrantKind.fromStored(value.kind) ?: return null
    val responseAbsent = value.respondedAt == null && value.respondedByUserId == null && value.decisionId == null
    val responsePresent = value.respondedByUserId == scope.actorId && value.decisionId?.let(::isCanonicalUuidV4) == true &&
        evidenceTime(value.respondedAt, start, now)?.let { it.isBefore(expires) } == true
    if (!responseAbsent && !responsePresent) return null
    if (value.status !in setOf("requested", "active", "declined", "revoked", "expired", "consumed")) return null
    if (value.status == "requested" && !responseAbsent) return null
    if (value.status in setOf("active", "declined", "consumed") && !responsePresent) return null
    if (value.status == "revoked") {
        if (value.revocationId?.let(::isCanonicalUuidV4) != true || evidenceTime(value.revokedAt, start, now) == null) return null
    } else if (value.revocationId != null || value.revokedAt != null) return null
    if (value.consumedAt != null && (kind != RemoteGrantKind.ONE_TIME || !responsePresent ||
            evidenceTime(value.consumedAt, requireNotNull(parseRemoteInstant(value.respondedAt)), now) == null || value.status !in setOf("consumed", "revoked"))) return null
    if (value.status == "consumed" && value.consumedAt == null) return null
    // A requested grant may be restrictively expired early on an intervening user change.
    if (value.status in setOf("requested", "active") && !now.isBefore(expires)) return null
    val matched = when (action.kind) {
        RemotePendingKind.DECISION -> value.decisionId == action.actionId && responsePresent &&
            (if (value.status == "declined") RemoteGrantDecisionWire.DECLINED else RemoteGrantDecisionWire.ACCEPTED) == action.decision
        RemotePendingKind.REVOKE -> value.status == "revoked" && value.revocationId == action.actionId
        RemotePendingKind.END -> false
    }
    if (action.kind == RemotePendingKind.DECISION && value.decisionId == action.actionId && !matched) return null
    val outcome = if (matched) RemoteResolutionKind.MATCHED_ACTION_ACK else if (value.status in setOf("declined", "revoked", "expired"))
        RemoteResolutionKind.TERMINAL_SUPERSEDED else return null
    return RemotePendingResolution(action, outcome, value.status)
}

internal fun classifyRemoteSessionStatus(action: RemotePendingAction, scope: HandoverScope, installationId: String,
    value: RemoteSessionStatusResponse): RemotePendingResolution? {
    if (action.kind != RemotePendingKind.END || value.id != action.targetId || value.companyId != scope.companyId ||
        value.installationId != installationId || value.requestedForUserId != scope.actorId || !isCanonicalUuidV4(value.grantId) ||
        (value.respondedByUserId != null && value.respondedByUserId != scope.actorId)) return null
    val now = parseRemoteInstant(value.serverTime) ?: return null
    val requested = parseRemoteInstant(value.requestedAt)?.takeIf { !it.isAfter(now) } ?: return null
    val requestExpiry = parseRemoteInstant(value.requestExpiresAt)?.takeIf { it.isAfter(requested) } ?: return null
    val unstarted = value.startedAt == null && value.expiresAt == null
    val started = evidenceTime(value.startedAt, requested, now)
    val expiry = parseRemoteInstant(value.expiresAt)
    if (!unstarted && (started == null || expiry == null || !expiry.isAfter(started) ||
            expiry.isAfter(started.plusMillis(REMOTE_SESSION_MAX_MILLIS)) || value.respondedByUserId != scope.actorId)) return null
    if (value.status !in setOf("requested", "active", "ended", "expired")) return null
    if (value.status == "requested" && (!unstarted || !now.isBefore(requestExpiry))) return null
    if (value.status == "active" && (unstarted || !now.isBefore(expiry))) return null
    if (value.status == "ended") {
        if (value.endId?.let(::isCanonicalUuidV4) != true || value.endReason !in setOf("owner_ended", "user_ended", "permission_revoked", "capture_stopped", "app_backgrounded", "grant_revoked", "grant_declined") ||
            evidenceTime(value.endedAt, started ?: requested, now) == null) return null
    } else if (value.endId != null || value.endReason != null || value.endedAt != null) return null
    // Grant expiry can end a session earlier than its own deadline. The exact signed terminal
    // state is authoritative; never invent an end action or infer it from a missing session.
    if (value.status !in setOf("ended", "expired")) return null
    if (value.endId == action.actionId && value.endReason != action.reason) return null
    val outcome = if (value.status == "ended" && value.endId == action.actionId && value.endReason == action.reason)
        RemoteResolutionKind.MATCHED_ACTION_ACK else RemoteResolutionKind.TERMINAL_SUPERSEDED
    return RemotePendingResolution(action, outcome, value.status)
}

/** Finite production adapter. The checkpoint is an exact owned journal CAS, not a clean-store predicate. */
internal class RemotePendingRecovery(
    private val manager: HandoverOperations, private val api: RemoteAssistanceApi,
    private val original: OriginalOperationBinding, private val requestScope: RemoteRequestScopeTag,
    private val keyId: String, private val originalAndPendingCurrent: () -> Boolean,
    private val checkpoint: suspend (RemotePendingResolution) -> Unit,
    private val postUncertain: () -> Unit,
    private val reassertPending: suspend () -> Unit,
) {
    suspend fun reconcile(action: RemotePendingAction): RemotePendingResolution? {
        action.validate()
        check(originalAndPendingCurrent() && original.isCurrent())
        reassertPending()
        check(originalAndPendingCurrent() && original.isCurrent())
        val installation = requestScope.scope.installationId
        check(requestScope.scope.companyId == original.authority.scope.companyId && requestScope.scope.userId == original.authority.scope.actorId)
        val identity = action.identity(original.authority.scope, installation)
        try {
            return manager.runKnownJournalAttempt(original, identity, {
                val result = when (action.kind) {
                    RemotePendingKind.DECISION -> {
                        val v = api.decideGrant(action.targetId, RemoteGrantDecisionRequest(installation, requireNotNull(action.decision), action.actionId), requestScope).requireBody()
                        validateGrantPost(action, v, installation)
                    }
                    RemotePendingKind.REVOKE -> validateGrantPost(action,
                        api.revokeGrant(action.targetId, RemoteGrantRevocationRequest(installation, action.actionId), requestScope).requireBody(), installation)
                    RemotePendingKind.END -> {
                        val v = api.endSession(action.targetId, RemoteSessionEndRequest(installation, action.actionId, requireNotNull(action.reason)), requestScope).requireBody()
                        check(v.id == action.targetId && v.installationId == installation && v.status == "ended" && v.endReason == action.reason &&
                            parseRemoteInstant(v.endedAt) != null && isCanonicalUuidV4(v.grantId))
                        RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, v.status)
                    }
                }
                check(originalAndPendingCurrent() && original.isCurrent()); result
            }, { checkpoint(it) })
        } catch (cancelled: CancellationException) { postUncertain(); throw cancelled }
        catch (_: Exception) {
            postUncertain()
            if (!originalAndPendingCurrent() || !original.isCurrent()) return null
        }
        // A failed replay is not a terminal receipt. Only a positive exact object can reconcile it.
        return manager.observeKnownRemoteStatus(original, KnownRemoteStatusObservation(identity, installation, keyId)) { permit ->
            val resolved = if (action.kind == RemotePendingKind.END) classifyRemoteSessionStatus(action,
                original.authority.scope, installation, api.sessionStatus(action.targetId, installation, requestScope))
            else classifyRemoteGrantStatus(action, original.authority.scope, installation,
                api.grantStatus(action.targetId, installation, requestScope))
            if (resolved != null) {
                check(originalAndPendingCurrent() && original.isCurrent())
                manager.coordinator.checkpointKnownAttempt(permit, identity, original.isCurrent) { checkpoint(resolved) }
            }
            resolved
        }
    }
    private fun validateGrantPost(action: RemotePendingAction, value: RemoteGrantResponse, installation: String): RemotePendingResolution {
        check(value.id == action.targetId && value.installationId == installation && value.requestedForUserId == original.authority.scope.actorId)
        check(value.respondedByUserId == null || value.respondedByUserId == original.authority.scope.actorId)
        if (action.kind == RemotePendingKind.REVOKE) check(value.status == "revoked" && parseRemoteInstant(value.revokedAt) != null)
        else check((if (value.status == "declined") RemoteGrantDecisionWire.DECLINED else RemoteGrantDecisionWire.ACCEPTED) == action.decision &&
            value.status in setOf("active", "declined", "consumed", "revoked", "expired") && value.respondedByUserId == original.authority.scope.actorId && parseRemoteInstant(value.respondedAt) != null)
        return RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, value.status)
    }
}
private fun <T> Response<T>.requireBody(): T {
    if (!isSuccessful) throw RemoteAssistanceHttpException(code())
    return checkNotNull(body()) { "An exact remote action response is required." }
}

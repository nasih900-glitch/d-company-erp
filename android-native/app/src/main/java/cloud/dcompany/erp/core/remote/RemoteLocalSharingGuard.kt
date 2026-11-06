package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.handover.OriginalOperationBinding

/** A RAM-only confirmation, never server consent or a durable revocation receipt. */
internal data class RemoteLocalSharingTarget(val original: OriginalOperationBinding, val grantId: String, val sessionId: String) {
    override fun toString() = "RemoteLocalSharingTarget(<redacted>)"
}
internal data class RemoteLocalPrivacyAction(val original: OriginalOperationBinding, val kind: RemotePendingKind,
    val targetId: String, val reason: String? = null) {
    override fun toString() = "RemoteLocalPrivacyAction(<redacted>)"
}

/** Exact process-local privacy boundary. No preference, poll, replay or new actor renews sharing. */
internal class RemoteLocalSharingGuard(private val originalIsCurrent: (OriginalOperationBinding) -> Boolean) {
    private var observed: RemoteLocalSharingTarget? = null
    private var confirmed: RemoteLocalSharingTarget? = null
    private var freshAllow: Pair<RemoteLocalSharingTarget, String>? = null
    private val writeHolds = mutableSetOf<RemoteLocalPrivacyAction>()

    private inline fun <T> current(original: OriginalOperationBinding, refused: T, state: () -> T): T {
        // Production mutations are additionally enclosed by RemoteOriginalCommit. Never call
        // an auth/view callback under this monitor (token-owned cleanup also enters here).
        if (!originalIsCurrent(original)) return refused
        return synchronized(this) { state() }
    }
    fun observe(original: OriginalOperationBinding, grantId: String?, sessionId: String?) = current(original, Unit) {
        val next = if (grantId?.let(::isCanonicalUuidV4) == true && sessionId?.let(::isCanonicalUuidV4) == true)
            RemoteLocalSharingTarget(original, grantId, sessionId) else null
        if (observed != next) { confirmed = null; freshAllow = null }
        observed = next
    }
    @Synchronized fun authorityChanged() { observed = null; confirmed = null; freshAllow = null }
    fun pause(original: OriginalOperationBinding) = current(original, Unit) {
        confirmed = null; freshAllow = null
    }
    fun noteFreshAllow(original: OriginalOperationBinding, grant: String, session: String?, decisionId: String) = current(original, Unit) {
        if (session != null) {
            val target = RemoteLocalSharingTarget(original, grant, session)
            if (observed == target && isCanonicalUuidV4(decisionId)) freshAllow = target to decisionId
        }
    }
    @Synchronized fun discardFreshAllow(original: OriginalOperationBinding, actionId: String) {
        if (freshAllow?.first?.original === original && freshAllow?.second == actionId) freshAllow = null
    }
    fun acceptFreshAllow(original: OriginalOperationBinding, action: RemotePendingAction,
        resolution: RemotePendingResolution, pendingIsClear: Boolean): Boolean = current(original, false) {
        val fresh = freshAllow ?: return@current false
        if (fresh.first.original !== original || fresh.second != action.actionId ||
            action.kind != RemotePendingKind.DECISION || action.decision != RemoteGrantDecisionWire.ACCEPTED ||
            action.targetId != fresh.first.grantId || resolution.action != action ||
            resolution.kind != RemoteResolutionKind.MATCHED_ACTION_ACK || !pendingIsClear || writeHolds.isNotEmpty() || observed != fresh.first) return@current false
        confirmed = fresh.first; freshAllow = null; true
    }
    fun resume(original: OriginalOperationBinding, grant: String, session: String, pendingIsClear: Boolean): Boolean = current(original, false) {
        val target = RemoteLocalSharingTarget(original, grant, session)
        if (observed != target || !pendingIsClear || writeHolds.isNotEmpty()) return@current false
        confirmed = target; freshAllow = null; true
    }
    fun allows(original: OriginalOperationBinding, grant: String, session: String): Boolean = current(original, false) {
        writeHolds.isEmpty() && confirmed == observed && confirmed == RemoteLocalSharingTarget(original, grant, session)
    }
    @Synchronized fun hasWriteHold(): Boolean = writeHolds.isNotEmpty()
    @Synchronized fun keepRetryTarget(original: OriginalOperationBinding, grant: String, session: String): Boolean =
        writeHolds.any { it.original === original && ((it.kind == RemotePendingKind.END && it.targetId == session) ||
            (it.kind == RemotePendingKind.REVOKE && it.targetId == grant)) }
    fun needsRevokeRetry(original: OriginalOperationBinding?, grant: String?): Boolean =
        if (original == null) false else current(original, false) {
            writeHolds.any { it.original === original && it.kind == RemotePendingKind.REVOKE && it.targetId == grant }
        }
    @Synchronized fun heldEndReason(original: OriginalOperationBinding, session: String): String? =
        writeHolds.firstOrNull { it.original === original && it.kind == RemotePendingKind.END && it.targetId == session }?.reason

    /** Stop before any throwing persistence. Only an explicit successful exact retry clears its hold. */
    fun <T> privacyRecord(action: RemoteLocalPrivacyAction, explicitRetry: Boolean,
        stop: () -> Unit, record: () -> T?, matches: (T) -> Boolean): T? {
        if (!originalIsCurrent(action.original)) return null
        pause(action.original)
        if (!originalIsCurrent(action.original)) return null
        stop()
        val result = try { record() } catch (_: Exception) { null }
        if (!originalIsCurrent(action.original)) return null
        val matched = result != null && matches(result)
        synchronized(this) {
            if (matched) {
                if (explicitRetry) writeHolds.remove(action)
            } else writeHolds.add(action)
        }
        return result
    }
}

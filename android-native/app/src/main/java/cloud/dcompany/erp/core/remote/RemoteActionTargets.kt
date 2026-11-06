package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.handover.HandoverAuthority
import cloud.dcompany.erp.core.handover.HandoverScope
import cloud.dcompany.erp.core.handover.OriginalOperationBinding
import cloud.dcompany.erp.core.handover.handoverUuid

/** The exact state displayed to this callback, rather than whatever a later caller happens to see. */
internal data class RemoteUiTarget(val authority: HandoverAuthority, val prompt: RemoteGrantPrompt?,
    val session: RemoteActiveSession?, val deviceKeyId: String?, val consentGrantId: String? = null, val displayedSessionId: String? = null) {
    override fun toString() = "RemoteUiTarget(<redacted>)"
}

internal class RemoteUiActionBinding internal constructor(val original: OriginalOperationBinding, val target: RemoteUiTarget) {
    fun matches(currentOriginal: OriginalOperationBinding?, currentTarget: RemoteUiTarget?): Boolean =
        original === currentOriginal && original.isCurrent() && target == currentTarget && target.authority == original.authority
    fun matchesConsentGrant(retainedGrantId: String?): Boolean =
        target.consentGrantId != null && target.consentGrantId == retainedGrantId
    override fun toString() = "RemoteUiActionBinding(<redacted>)"
}

internal fun captureRemoteUiAction(original: OriginalOperationBinding?, displayed: RemoteUiTarget?, current: RemoteUiTarget?): RemoteUiActionBinding? =
    if (original != null && original.isCurrent() && displayed != null && displayed == current && displayed.authority == original.authority)
        RemoteUiActionBinding(original, displayed) else null

/** Noncredential PendingIntent identity. Old process/session intents cannot be retargeted by UPDATE_CURRENT. */
internal data class RemoteStopTarget(val processIdentity: String, val authority: HandoverAuthority, val sessionId: String, val grantId: String) {
    fun validate() { handoverUuid(processIdentity); authority.validate(); handoverUuid(sessionId); handoverUuid(grantId) }
    val dataUri: String get() = "dcompany-remote-stop://stop/" + listOf(processIdentity, authority.epoch.toString(), authority.scope.actorId,
        authority.scope.companyId, authority.scope.branchId ?: "-", authority.scope.terminalId ?: "-", sessionId, grantId).joinToString("/")
    override fun toString() = "RemoteStopTarget(<redacted>)"
}
internal const val REMOTE_STOP_ACTION = "cloud.dcompany.erp.remote_assistance.STOP"
internal fun parseRemoteStopTarget(action: String?, data: String?): RemoteStopTarget? {
    if (action != REMOTE_STOP_ACTION || data == null || data.length > 512 || !data.startsWith("dcompany-remote-stop://stop/")) return null
    val parts = data.removePrefix("dcompany-remote-stop://stop/").split('/')
    if (parts.size != 8) return null
    return runCatching {
        val target = RemoteStopTarget(parts[0], HandoverAuthority(HandoverScope(parts[2], parts[3],
            parts[4].takeUnless { it == "-" }, parts[5].takeUnless { it == "-" }), requireNotNull(parts[1].toLongOrNull())), parts[6], parts[7])
        target.validate(); target.takeIf { it.dataUri == data }
    }.getOrNull()
}

package cloud.dcompany.erp.core.handover

/** Only existing durable, nonsecret journal intents may opt into exact attempt settlement. */
internal enum class KnownJournalDomain { STAFF_TIME, SHARED_ATTENDANCE, FINANCE, SYNC_ROW, REMOTE_RECEIPT }

/** No wire credentials, device proof nonce, or credential-derived fingerprint belongs here. */
internal data class KnownJournalAttempt(
    val domain: KnownJournalDomain,
    val scope: HandoverScope,
    val actionKey: String,
    val target: String,
    val kind: String,
    val storedIntentSha256: String,
) {
    fun validate() {
        scope.validate()
        for (value in listOf(actionKey, target, kind)) {
            require(value.length in 1..200 && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid known journal identity." }
        }
        require(storedIntentSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid stored intent fingerprint." }
    }
    override fun toString() = "KnownJournalAttempt(<redacted>)"
}

/** Only a positive exact ACTIVE key object can reconcile its retained enrollment. */
internal data class KnownRemoteKeyObservation(val identity: KnownJournalAttempt, val keyId: String, val installationId: String) {
    fun validate() {
        identity.validate(); handoverUuid(keyId); handoverUuid(installationId)
        require(identity.domain == KnownJournalDomain.REMOTE_RECEIPT && identity.kind == "remote-device-enrollment" && identity.target == keyId)
    }
    val encodedPath get() = "/api/v1/remote-assistance/device/keys/$keyId/status"
    override fun toString() = "KnownRemoteKeyObservation(<redacted>)"
}


/** Only these retained device actions may use an exact signed terminal-object observation. */
internal data class KnownRemoteStatusObservation(
    val identity: KnownJournalAttempt, val installationId: String, val keyId: String,
) {
    fun validate() {
        identity.validate(); handoverUuid(installationId); handoverUuid(keyId); handoverUuid(identity.target)
        require(identity.domain == KnownJournalDomain.REMOTE_RECEIPT && identity.kind in setOf(
            "remote-grant-decision", "remote-grant-revoke", "remote-session-end",
        ))
    }
    val encodedPath get() = "/api/v1/remote-assistance/device/" +
        (if (identity.kind == "remote-session-end") "sessions" else "grants") + "/${identity.target}/status"
    override fun toString() = "KnownRemoteStatusObservation(<redacted>)"
}

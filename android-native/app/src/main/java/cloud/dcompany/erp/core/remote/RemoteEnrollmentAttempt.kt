package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.handover.HandoverScope
import cloud.dcompany.erp.core.handover.KnownJournalAttempt
import cloud.dcompany.erp.core.handover.KnownJournalDomain

/** Only retained public enrollment identity; no auth binding, pairing code, nonce, signature or token. */
internal fun remoteEnrollmentAttempt(scope: HandoverScope, identity: PersistedRemoteDeviceIdentity): KnownJournalAttempt {
    require(validRemoteDeviceIdentity(identity) != null && identity.companyId == scope.companyId)
    val stable = listOf(identity.companyId, identity.installationId, identity.keyId, identity.enrollmentId, identity.fingerprintSha256).joinToString("\n").toByteArray(Charsets.UTF_8)
    return try {
        KnownJournalAttempt(KnownJournalDomain.REMOTE_RECEIPT, scope, identity.enrollmentId, identity.keyId,
            "remote-device-enrollment", remoteSha256Hex(stable)).also { it.validate() }
    } finally { stable.fill(0) }
}

/** Pure validation; callers decide separately whether a non-ACTIVE status needs later owned work. */
internal fun validatedRemoteDeviceStatus(previous: PersistedRemoteDeviceIdentity, response: RemoteDeviceKeyStatusResponse,
    activeOnly: Boolean = false): PersistedRemoteDeviceIdentity? {
    if (response.keyId != previous.keyId || response.installationId != previous.installationId || response.fingerprintSha256 != previous.fingerprintSha256) return null
    val status = RemoteDeviceKeyStatus.fromStored(response.status)?.takeUnless { it == RemoteDeviceKeyStatus.CREATED } ?: return null
    if (activeOnly && status != RemoteDeviceKeyStatus.ACTIVE) return null
    if (status == RemoteDeviceKeyStatus.ACTIVE) {
        val approved = parseRemoteInstant(response.approvedAt) ?: return null
        val enrolled = parseRemoteInstant(response.enrolledAt) ?: return null
        val now = parseRemoteInstant(response.serverTime) ?: return null
        if (response.revokedAt != null || approved.isBefore(enrolled) || approved.isAfter(now.plusSeconds(30))) return null
    }
    val updated = previous.copy(status = status.storedValue, fingerprintSha256 = response.fingerprintSha256,
        pairingCode = response.pairingCode, serverTime = response.serverTime, enrolledAt = response.enrolledAt,
        pendingExpiresAt = response.pendingExpiresAt, approvedAt = response.approvedAt, revokedAt = response.revokedAt)
    return validRemoteDeviceIdentity(updated)
}

internal enum class CreatedEnrollmentNextStep { POST_SAME_INTENT, ACTIVE_CHECKPOINTED, HELD }

/** Selection only; this neither authenticates nor resolves an unknown by itself. */
internal suspend fun createdEnrollmentNextStep(hasFinishedUnknown: Boolean,
    observeExactKey: suspend () -> Boolean, originalAndExpectedCurrent: () -> Boolean): CreatedEnrollmentNextStep {
    if (!originalAndExpectedCurrent()) return CreatedEnrollmentNextStep.HELD
    if (!hasFinishedUnknown) return CreatedEnrollmentNextStep.POST_SAME_INTENT
    if (observeExactKey()) return CreatedEnrollmentNextStep.ACTIVE_CHECKPOINTED
    return if (originalAndExpectedCurrent()) CreatedEnrollmentNextStep.POST_SAME_INTENT else CreatedEnrollmentNextStep.HELD
}

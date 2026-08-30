package cloud.dcompany.erp.core.remote

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val REMOTE_ASSISTANCE_PROTOCOL_VERSION = 1
internal const val REMOTE_HEARTBEAT_INTERVAL_MILLIS = 20_000L
internal const val REMOTE_BACKEND_ONLINE_WINDOW_MILLIS = 45_000L
internal const val REMOTE_FRAME_INTERVAL_MILLIS = 4_000L
internal const val REMOTE_BACKEND_FRAME_THROTTLE_MILLIS = 2_000L
internal const val REMOTE_FRAME_MAX_WIDTH = 960
internal const val REMOTE_FRAME_MAX_HEIGHT = 540
internal const val REMOTE_FRAME_MIN_WIDTH = 240
internal const val REMOTE_FRAME_MIN_HEIGHT = 180
internal const val REMOTE_FRAME_JPEG_QUALITY = 42
internal const val REMOTE_FRAME_MAX_BYTES = 256 * 1_024
internal const val REMOTE_SESSION_MAX_MILLIS = 15L * 60L * 1_000L
internal const val REMOTE_COMMAND_MAX_AGE_MILLIS = 2L * 60L * 1_000L
internal const val REMOTE_ONE_TIME_GRANT_MAX_MILLIS = 15L * 60L * 1_000L
internal const val REMOTE_ANYTIME_GRANT_MAX_MILLIS = 24L * 60L * 60L * 1_000L

internal enum class RemoteConsentChoice(val storedValue: String) {
    UNDECIDED("undecided"),
    ALLOWED("allowed"),
    DENIED("denied"),
    REVOKED("revoked");

    companion object {
        fun fromStored(value: String?): RemoteConsentChoice =
            entries.firstOrNull { it.storedValue == value } ?: UNDECIDED
    }
}

internal enum class RemoteSharingCapability(val wireValue: String) {
    AVAILABLE("available"),
    PERMISSION_REQUIRED("permission_required"),
    UNSUPPORTED("unsupported"),
}

@Serializable
internal enum class RemoteGrantDecisionWire {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("declined")
    DECLINED,
}

internal fun sharingCapability(choice: RemoteConsentChoice): RemoteSharingCapability =
    if (choice == RemoteConsentChoice.ALLOWED) {
        RemoteSharingCapability.AVAILABLE
    } else {
        RemoteSharingCapability.PERMISSION_REQUIRED
    }

@Serializable
internal data class RemoteDeviceHeartbeatRequest(
    @SerialName("installation_id") val installationId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("sharing_capability") val sharingCapability: String,
)

@Serializable
internal data class RemoteDeviceKeyEnrollmentRequest(
    @SerialName("key_id") val keyId: String,
    @SerialName("enrollment_id") val enrollmentId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("public_key_spki") val publicKeySpki: String,
    @SerialName("signed_at_epoch_seconds") val signedAtEpochSeconds: Long,
    val nonce: String,
    val signature: String,
)

@Serializable
internal data class RemoteDeviceKeyStatusResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("key_id") val keyId: String,
    @SerialName("installation_id") val installationId: String,
    val status: String,
    @SerialName("fingerprint_sha256") val fingerprintSha256: String,
    @SerialName("enrolled_at") val enrolledAt: String,
    @SerialName("pending_expires_at") val pendingExpiresAt: String,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("pairing_code") val pairingCode: String? = null,
)

@Serializable
internal data class RemoteDeviceStateResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("pending_grants") val pendingGrants: List<RemoteGrantResponse> = emptyList(),
    val session: RemoteSessionResponse? = null,
    val commands: List<RemoteCommandResponse> = emptyList(),
)

@Serializable
internal data class RemoteGrantResponse(
    val id: String,
    @SerialName("installation_id") val installationId: String,
    val kind: String,
    val status: String,
    @SerialName("requested_by_user_id") val requestedByUserId: String,
    @SerialName("requested_by_name") val requestedByName: String? = null,
    @SerialName("requested_for_user_id") val requestedForUserId: String,
    @SerialName("requested_for_name") val requestedForName: String? = null,
    @SerialName("responded_by_user_id") val respondedByUserId: String? = null,
    @SerialName("responded_by_name") val respondedByName: String? = null,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("responded_at") val respondedAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("consumed_at") val consumedAt: String? = null,
)

@Serializable
internal data class RemoteSessionResponse(
    val id: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("grant_id") val grantId: String,
    val status: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("requested_by_user_id") val requestedByUserId: String,
    @SerialName("requested_by_name") val requestedByName: String? = null,
    @SerialName("started_by_user_id") val startedByUserId: String? = null,
    @SerialName("started_by_name") val startedByName: String? = null,
    @SerialName("ended_by_user_id") val endedByUserId: String? = null,
    @SerialName("ended_by_name") val endedByName: String? = null,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("request_expires_at") val requestExpiresAt: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("next_sequence") val nextSequence: Long,
)

@Serializable
internal data class RemoteCommandResponse(
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val sequence: Long,
    val type: String,
    val module: String? = null,
    val status: String,
    @SerialName("issued_by_user_id") val issuedByUserId: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("resolved_by_user_id") val resolvedByUserId: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("rejection_reason_code") val rejectionReasonCode: String? = null,
)

@Serializable
internal data class RemoteGrantDecisionRequest(
    @SerialName("installation_id") val installationId: String,
    val decision: RemoteGrantDecisionWire,
    @SerialName("decision_id") val decisionId: String,
)

@Serializable
internal data class RemoteGrantRevocationRequest(
    @SerialName("installation_id") val installationId: String,
    @SerialName("revocation_id") val revocationId: String,
)

@Serializable
internal data class RemoteCommandResultRequest(
    @SerialName("installation_id") val installationId: String,
    val sequence: Long,
    val outcome: String,
    @SerialName("reason_code") val reasonCode: String? = null,
)

@Serializable
internal data class RemoteSessionEndRequest(
    @SerialName("installation_id") val installationId: String,
    @SerialName("end_id") val endId: String,
    val reason: String,
)

internal data class RemoteGrantPrompt(
    val grantId: String,
    val kind: RemoteGrantKind,
    val requesterName: String,
    val expiresAt: Instant,
)

internal enum class RemoteGrantKind(val storedValue: String) {
    ONE_TIME("one_time"),
    ANYTIME("anytime");

    companion object {
        fun fromStored(value: String?): RemoteGrantKind? =
            entries.firstOrNull { it.storedValue == value }
    }
}

internal data class RemoteActiveSession(
    val sessionId: String,
    val grantId: String,
    val expiresAt: Instant,
    val deadlineElapsedMillis: Long,
)

/** A repeated poll may shorten a session, but can never extend its first admitted deadline. */
internal fun clampRemoteSessionDeadline(
    existing: RemoteActiveSession?,
    incoming: RemoteActiveSession,
): RemoteActiveSession {
    if (
        existing == null ||
        existing.sessionId != incoming.sessionId ||
        existing.grantId != incoming.grantId
    ) return incoming
    return incoming.copy(
        expiresAt = minOf(existing.expiresAt, incoming.expiresAt),
        deadlineElapsedMillis = minOf(
            existing.deadlineElapsedMillis,
            incoming.deadlineElapsedMillis,
        ),
    )
}

internal data class RemoteSessionEvaluation(
    val active: RemoteActiveSession? = null,
    val rejectionReason: String? = null,
)

/**
 * The backend and client both cap a session at fifteen minutes. Server time is
 * used for the remaining duration so a mis-set tablet clock cannot prolong a
 * session; elapsed realtime then owns the local deadline so wall-clock changes
 * cannot prolong it either.
 */
internal fun evaluateRemoteSession(
    session: RemoteSessionResponse?,
    serverTimeRaw: String,
    nowElapsedMillis: Long,
    expectedInstallationId: String? = null,
): RemoteSessionEvaluation {
    if (session == null || session.status != "active") {
        return RemoteSessionEvaluation(rejectionReason = "session_inactive")
    }
    if (!isCanonicalUuidV4(session.id)) {
        return RemoteSessionEvaluation(rejectionReason = "invalid_session")
    }
    if (!isCanonicalUuidV4(session.grantId)) {
        return RemoteSessionEvaluation(rejectionReason = "invalid_grant")
    }
    if (expectedInstallationId != null && session.installationId != expectedInstallationId) {
        return RemoteSessionEvaluation(rejectionReason = "invalid_installation")
    }
    val serverTime = parseRemoteInstant(serverTimeRaw)
        ?: return RemoteSessionEvaluation(rejectionReason = "invalid_server_time")
    val startedAt = parseRemoteInstant(session.startedAt)
        ?: return RemoteSessionEvaluation(rejectionReason = "invalid_session_start")
    val expiresAt = parseRemoteInstant(session.expiresAt)
        ?: return RemoteSessionEvaluation(rejectionReason = "invalid_session_expiry")
    val totalMillis = runCatching { Duration.between(startedAt, expiresAt).toMillis() }.getOrNull()
        ?: return RemoteSessionEvaluation(rejectionReason = "invalid_session_duration")
    val remainingMillis = runCatching { Duration.between(serverTime, expiresAt).toMillis() }.getOrNull()
        ?: return RemoteSessionEvaluation(rejectionReason = "invalid_session_duration")
    if (totalMillis !in 60_000L..REMOTE_SESSION_MAX_MILLIS || remainingMillis <= 0L) {
        return RemoteSessionEvaluation(rejectionReason = "session_expired")
    }
    if (serverTime.isBefore(startedAt.minusSeconds(30))) {
        return RemoteSessionEvaluation(rejectionReason = "session_not_started")
    }
    val localRemaining = minOf(remainingMillis, REMOTE_SESSION_MAX_MILLIS)
    return RemoteSessionEvaluation(
        active = RemoteActiveSession(
            sessionId = session.id,
            grantId = session.grantId,
            expiresAt = expiresAt,
            deadlineElapsedMillis = nowElapsedMillis + localRemaining,
        ),
    )
}

internal fun pendingRemoteGrant(
    grants: List<RemoteGrantResponse>,
    serverTimeRaw: String,
    expectedInstallationId: String? = null,
    expectedUserId: String? = null,
): RemoteGrantPrompt? {
    val serverTime = parseRemoteInstant(serverTimeRaw) ?: return null
    return grants.asSequence()
        .filter { it.kind in setOf("one_time", "anytime") && it.status == "requested" }
        .filter { expectedInstallationId == null || it.installationId == expectedInstallationId }
        .filter { isCanonicalUuidV4(it.requestedForUserId) }
        .filter { expectedUserId == null || it.requestedForUserId == expectedUserId }
        .mapNotNull { grant ->
            val requestedAt = parseRemoteInstant(grant.requestedAt) ?: return@mapNotNull null
            val expiresAt = parseRemoteInstant(grant.expiresAt) ?: return@mapNotNull null
            val grantKind = RemoteGrantKind.fromStored(grant.kind) ?: return@mapNotNull null
            val ttlMillis = runCatching { Duration.between(requestedAt, expiresAt).toMillis() }
                .getOrNull()
                ?: return@mapNotNull null
            val remainingMillis = runCatching { Duration.between(serverTime, expiresAt).toMillis() }
                .getOrNull()
                ?: return@mapNotNull null
            val maximumTtl = when (grantKind) {
                RemoteGrantKind.ONE_TIME -> REMOTE_ONE_TIME_GRANT_MAX_MILLIS
                RemoteGrantKind.ANYTIME -> REMOTE_ANYTIME_GRANT_MAX_MILLIS
            }
            if (
                !isCanonicalUuidV4(grant.id) ||
                !expiresAt.isAfter(serverTime) ||
                serverTime.isBefore(requestedAt.minusSeconds(30)) ||
                ttlMillis !in 60_000L..maximumTtl ||
                remainingMillis !in 1L..maximumTtl
            ) return@mapNotNull null
            RemoteGrantPrompt(
                grantId = grant.id,
                kind = grantKind,
                requesterName = sanitizeRequesterName(grant.requestedByName),
                expiresAt = expiresAt,
            )
        }
        .minByOrNull(RemoteGrantPrompt::expiresAt)
}

internal enum class RemoteErpModule(val wireValue: String) {
    HELP("help");

    companion object {
        fun fromWire(value: String?): RemoteErpModule? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal sealed interface RemoteSemanticCommand {
    data class Navigate(val module: RemoteErpModule) : RemoteSemanticCommand
    data object Refresh : RemoteSemanticCommand
    data object CollectDiagnostics : RemoteSemanticCommand
}

internal data class RemoteCommandValidation(
    val command: RemoteSemanticCommand? = null,
    val rejectionReason: String? = null,
)

/** Never attempt sequence N+1 until sequence N has a proven server receipt. */
internal fun remoteCommandBatchHasContiguousSequence(
    commands: List<RemoteCommandResponse>,
    expectedSequence: Long,
    expectedSessionId: String? = null,
): Boolean {
    // Backend v1 admits only one outstanding command. A batch is therefore a
    // protocol/integrity failure, not an invitation to run a queued script.
    if (commands.size > 1) return false
    if (expectedSequence !in 1L..100L) return commands.isEmpty()
    if (
        expectedSessionId != null &&
        (!isCanonicalUuidV4(expectedSessionId) || commands.any { it.sessionId != expectedSessionId })
    ) return false
    if (commands.map(RemoteCommandResponse::commandId).distinct().size != commands.size) return false
    val pendingSequences = commands.map(RemoteCommandResponse::sequence).sorted()
    if (pendingSequences.isEmpty()) return true
    return pendingSequences.withIndex().all { (index, sequence) ->
        sequence == expectedSequence + index
    }
}

internal suspend fun processRemoteCommandsInOrder(
    commands: List<RemoteCommandResponse>,
    expectedSequence: Long,
    processAndAcknowledge: suspend (RemoteCommandResponse) -> Boolean,
) {
    if (!remoteCommandBatchHasContiguousSequence(commands, expectedSequence)) return
    var nextSequence = expectedSequence
    for (command in commands
        .asSequence()
        .filter { it.sequence >= expectedSequence }
        .sortedBy(RemoteCommandResponse::sequence)
    ) {
        // Missing or duplicate sequences are an integrity failure. Waiting for
        // a later poll is safer than executing around a gap.
        if (command.sequence != nextSequence) return
        if (!processAndAcknowledge(command)) return
        nextSequence += 1L
    }
}

internal fun validateRemoteCommand(
    command: RemoteCommandResponse,
    session: RemoteActiveSession,
    serverTimeRaw: String,
    foreground: Boolean,
    sensitiveOverlayVisible: Boolean = false,
): RemoteCommandValidation {
    if (!foreground) return RemoteCommandValidation(rejectionReason = "not_in_foreground")
    if (sensitiveOverlayVisible) {
        return RemoteCommandValidation(rejectionReason = "permission_denied")
    }
    if (command.status != "pending" || !isCanonicalUuidV4(command.commandId)) {
        return RemoteCommandValidation(rejectionReason = "unsupported_command")
    }
    if (command.sessionId != session.sessionId || !isCanonicalUuidV4(command.sessionId)) {
        return RemoteCommandValidation(rejectionReason = "session_inactive")
    }
    if (command.sequence !in 1L..100L) {
        return RemoteCommandValidation(rejectionReason = "unsupported_command")
    }
    val serverTime = parseRemoteInstant(serverTimeRaw)
        ?: return RemoteCommandValidation(rejectionReason = "execution_failed")
    val issuedAt = parseRemoteInstant(command.issuedAt)
        ?: return RemoteCommandValidation(rejectionReason = "unsupported_command")
    val commandAge = Duration.between(issuedAt, serverTime).toMillis()
    if (commandAge !in -30_000L..REMOTE_COMMAND_MAX_AGE_MILLIS) {
        return RemoteCommandValidation(rejectionReason = "session_inactive")
    }
    if (!issuedAt.isBefore(session.expiresAt)) {
        return RemoteCommandValidation(rejectionReason = "session_inactive")
    }

    val semantic = when (command.type) {
        "navigate" -> {
            val module = RemoteErpModule.fromWire(command.module)
                ?: return RemoteCommandValidation(rejectionReason = "module_unavailable")
            RemoteSemanticCommand.Navigate(module)
        }
        "refresh" -> if (command.module == null) {
            RemoteSemanticCommand.Refresh
        } else {
            return RemoteCommandValidation(rejectionReason = "unsupported_command")
        }
        "collect_diagnostics" -> if (command.module == null) {
            RemoteSemanticCommand.CollectDiagnostics
        } else {
            return RemoteCommandValidation(rejectionReason = "unsupported_command")
        }
        else -> return RemoteCommandValidation(rejectionReason = "unsupported_command")
    }
    return RemoteCommandValidation(command = semantic)
}

/** V1 UI actions and consent may appear only on the fully audited Help surface. */
internal fun remoteSemanticUiAdmission(
    routeKey: String?,
    sensitiveOverlayVisible: Boolean,
    appForeground: Boolean = true,
): Boolean = appForeground &&
    !sensitiveOverlayVisible &&
    routeKey?.trim()?.lowercase() == "help"

internal enum class RemoteCaptureDisposition {
    APP_WINDOW,
    PRIVACY_PLACEHOLDER,
}

/** Closed allowlist: adding a new ERP route never makes it remotely visible by default. */
internal fun remoteCaptureDisposition(
    routeKey: String?,
    sensitiveOverlayVisible: Boolean,
    appForeground: Boolean,
): RemoteCaptureDisposition {
    if (!appForeground || sensitiveOverlayVisible) {
        return RemoteCaptureDisposition.PRIVACY_PLACEHOLDER
    }
    // Dashboard and Gaming expose operational/financial values even without a
    // dialog. Help is the only v1 route whose full surface has been audited as
    // safe for app-window capture. All other routes fail closed.
    val safeRoutes = setOf("help")
    return if (routeKey?.trim()?.lowercase() in safeRoutes) {
        RemoteCaptureDisposition.APP_WINDOW
    } else {
        RemoteCaptureDisposition.PRIVACY_PLACEHOLDER
    }
}

internal fun mustReplaceWithPrivacyPlaceholder(
    capturedPrivacyPlaceholder: Boolean,
    beforeRoute: RemoteUiRouteSnapshot,
    afterRoute: RemoteUiRouteSnapshot,
    beforePrivacy: RemotePrivacySnapshot,
    afterPrivacy: RemotePrivacySnapshot,
    afterDisposition: RemoteCaptureDisposition,
): Boolean = !capturedPrivacyPlaceholder &&
    (
        afterDisposition == RemoteCaptureDisposition.PRIVACY_PLACEHOLDER ||
            beforeRoute.revision != afterRoute.revision ||
            beforePrivacy.revision != afterPrivacy.revision
    )

internal fun remoteFrameAdmissionAllowed(
    appForeground: Boolean,
    notificationVisible: Boolean,
    nowElapsedMillis: Long,
    deadlineElapsedMillis: Long,
    activeSessionMatches: Boolean,
): Boolean = appForeground &&
    notificationVisible &&
    nowElapsedMillis < deadlineElapsedMillis &&
    activeSessionMatches

internal fun remoteCommandExecutionAllowed(
    expected: RemoteActiveSession,
    current: RemoteActiveSession?,
    appForeground: Boolean,
    notificationVisible: Boolean,
    nowElapsedMillis: Long,
): Boolean = appForeground &&
    notificationVisible &&
    current != null &&
    current.sessionId == expected.sessionId &&
    current.grantId == expected.grantId &&
    nowElapsedMillis < current.deadlineElapsedMillis

internal fun parseRemoteInstant(value: String?): Instant? = value
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun isCanonicalUuidV4(value: String): Boolean {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return false
    return parsed.version() == 4 && parsed.toString() == value
}

private fun sanitizeRequesterName(value: String?): String = value
    .orEmpty()
    .replace(Regex("[\\r\\n\\t]"), " ")
    .trim()
    .take(80)
    .ifBlank { "D Company owner" }

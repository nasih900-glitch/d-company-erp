package cloud.dcompany.erp.core.remote

import android.content.Context
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class RemoteAssistanceJournalScope(
    val companyId: String,
    val installationId: String,
    val userId: String,
)

@Serializable
private data class ScopedRemoteAssistanceJournal(
    val scope: RemoteAssistanceJournalScope,
    val state: PersistedRemoteAssistanceState,
)

@Serializable
private data class PersistedRemoteAssistanceJournals(
    val format: Int,
    val journals: List<ScopedRemoteAssistanceJournal>,
)

@Serializable
internal data class PendingRemoteGrantDecision(
    val grantId: String,
    val decision: RemoteGrantDecisionWire,
    val decisionId: String,
)

@Serializable
internal data class PendingRemoteGrantRevocation(
    val grantId: String,
    val revocationId: String,
)

@Serializable
internal data class PendingRemoteSessionEnd(
    val sessionId: String,
    val endId: String,
    val reason: String,
)

@Serializable
internal data class RemoteCommandReceipt(
    val sessionId: String,
    val commandId: String,
    val sequence: Long,
    val state: String,
    val outcome: String? = null,
    val reasonCode: String? = null,
)

@Serializable
internal data class PersistedRemoteAssistanceState(
    val format: Int = REMOTE_STORE_FORMAT,
    val consent: String = RemoteConsentChoice.UNDECIDED.storedValue,
    val grantId: String? = null,
    val grantKind: String? = null,
    val grantExpiresAt: String? = null,
    val oneTimeSessionId: String? = null,
    val pendingDecision: PendingRemoteGrantDecision? = null,
    val pendingRevocation: PendingRemoteGrantRevocation? = null,
    val pendingSessionEnd: PendingRemoteSessionEnd? = null,
    val commandSessionId: String? = null,
    val lastAcknowledgedSequence: Long = 0L,
    val receipts: List<RemoteCommandReceipt> = emptyList(),
) {
    val consentChoice: RemoteConsentChoice
        get() = RemoteConsentChoice.fromStored(consent)
}

internal fun validRemoteAssistanceState(
    value: PersistedRemoteAssistanceState,
): PersistedRemoteAssistanceState? {
    if (value.format != REMOTE_STORE_FORMAT) return null
    val choice = RemoteConsentChoice.fromStored(value.consent)
    if (choice.storedValue != value.consent) return null
    val grantKind = RemoteGrantKind.fromStored(value.grantKind)
    val grantExpiry = parseRemoteInstant(value.grantExpiresAt)
    if (value.grantId == null) {
        if (
            choice != RemoteConsentChoice.UNDECIDED ||
            value.grantKind != null ||
            value.grantExpiresAt != null ||
            value.oneTimeSessionId != null
        ) return null
    } else {
        if (!isCanonicalUuidV4(value.grantId) || grantKind == null || grantExpiry == null) return null
        if (choice == RemoteConsentChoice.UNDECIDED) return null
        if (value.oneTimeSessionId != null) {
            if (grantKind != RemoteGrantKind.ONE_TIME || !isCanonicalUuidV4(value.oneTimeSessionId)) {
                return null
            }
        }
    }
    if (value.lastAcknowledgedSequence !in 0L..100L) return null
    if (value.commandSessionId != null && !isCanonicalUuidV4(value.commandSessionId)) return null
    if (value.receipts.size > MAX_REMOTE_COMMAND_RECEIPTS) return null
    if (value.receipts.any { !validRemoteCommandReceipt(it) }) return null
    if (value.receipts.map(RemoteCommandReceipt::commandId).distinct().size != value.receipts.size) {
        return null
    }
    if (
        value.receipts.map { it.sessionId to it.sequence }.distinct().size != value.receipts.size
    ) return null
    value.pendingDecision?.let {
        if (
            !isCanonicalUuidV4(it.grantId) ||
            !isCanonicalUuidV4(it.decisionId) ||
            it.decision !in RemoteGrantDecisionWire.entries ||
            it.grantId != value.grantId ||
            (it.decision == RemoteGrantDecisionWire.ACCEPTED && choice != RemoteConsentChoice.ALLOWED) ||
            (it.decision == RemoteGrantDecisionWire.DECLINED && choice != RemoteConsentChoice.DENIED)
        ) return null
    }
    value.pendingRevocation?.let {
        if (
            !isCanonicalUuidV4(it.grantId) ||
            !isCanonicalUuidV4(it.revocationId) ||
            it.grantId != value.grantId ||
            choice != RemoteConsentChoice.REVOKED
        ) return null
    }
    value.pendingSessionEnd?.let {
        if (
            !isCanonicalUuidV4(it.sessionId) ||
            !isCanonicalUuidV4(it.endId) ||
            it.reason !in REMOTE_DEVICE_END_REASONS
        ) return null
    }
    return value
}

internal data class RemoteGrantSessionAuthorization(
    val authorized: Boolean,
    val bindOneTimeSession: Boolean = false,
)

/** Server state alone never broadens the exact grant authorization stored after local consent. */
internal fun evaluatePersistedGrantAuthorization(
    state: PersistedRemoteAssistanceState,
    grantId: String,
    sessionId: String,
    serverTimeRaw: String,
): RemoteGrantSessionAuthorization {
    if (!isCanonicalUuidV4(grantId) || !isCanonicalUuidV4(sessionId)) {
        return RemoteGrantSessionAuthorization(false)
    }
    if (state.consentChoice != RemoteConsentChoice.ALLOWED || state.grantId != grantId) {
        return RemoteGrantSessionAuthorization(false)
    }
    val kind = RemoteGrantKind.fromStored(state.grantKind)
        ?: return RemoteGrantSessionAuthorization(false)
    if (kind == RemoteGrantKind.ONE_TIME && state.oneTimeSessionId != null) {
        // The grant had to be live when this exact one-time session was first
        // bound. Once bound, that session's independently validated short TTL
        // governs; crossing the request/grant expiry cannot cut it off or make
        // the grant reusable by another session.
        return RemoteGrantSessionAuthorization(state.oneTimeSessionId == sessionId)
    }
    val grantExpiresAt = parseRemoteInstant(state.grantExpiresAt)
        ?: return RemoteGrantSessionAuthorization(false)
    val serverTime = parseRemoteInstant(serverTimeRaw)
        ?: return RemoteGrantSessionAuthorization(false)
    if (!grantExpiresAt.isAfter(serverTime)) return RemoteGrantSessionAuthorization(false)
    return when (kind) {
        RemoteGrantKind.ANYTIME -> RemoteGrantSessionAuthorization(true)
        RemoteGrantKind.ONE_TIME -> RemoteGrantSessionAuthorization(
            authorized = true,
            bindOneTimeSession = true,
        )
    }
}

internal fun appendBoundedRemoteReceipt(
    existing: List<RemoteCommandReceipt>,
    receipt: RemoteCommandReceipt,
    maximum: Int = MAX_REMOTE_COMMAND_RECEIPTS,
): List<RemoteCommandReceipt> {
    require(maximum in 1..MAX_REMOTE_COMMAND_RECEIPTS)
    val retained = existing.filterNot { it.commandId == receipt.commandId }
    return (retained + receipt).takeLast(maximum)
}

/** A decision mutation is replayable only for the exact grant the user reviewed. */
internal fun replayableGrantDecision(
    state: PersistedRemoteAssistanceState,
    grantId: String,
): PendingRemoteGrantDecision? = state.pendingDecision?.takeIf { it.grantId == grantId }

/** Only journals with no action, receipt, or potentially active session may be forgotten. */
internal fun hasUnresolvedRemoteJournalWork(state: PersistedRemoteAssistanceState): Boolean =
    // An acknowledged ALLOWED grant remains live server authority until a
    // trusted server-time reconciliation proves expiry. Eviction must never
    // strand its local revoke path or turn a still-open grant into ambiguous
    // state merely to make room for another employee.
    state.consentChoice == RemoteConsentChoice.ALLOWED ||
        state.pendingDecision != null ||
        state.pendingRevocation != null ||
        state.pendingSessionEnd != null ||
        state.commandSessionId != null ||
        state.receipts.isNotEmpty()

private fun retiredExpiredGrantState(
    state: PersistedRemoteAssistanceState,
    serverTime: Instant,
    protectedActiveGrantId: String? = null,
): PersistedRemoteAssistanceState? {
    if (
        state.consentChoice != RemoteConsentChoice.ALLOWED ||
        state.grantId == protectedActiveGrantId ||
        state.pendingDecision != null ||
        state.pendingRevocation != null ||
        state.pendingSessionEnd != null ||
        state.commandSessionId != null ||
        state.receipts.isNotEmpty()
    ) return null
    val expiresAt = parseRemoteInstant(state.grantExpiresAt) ?: return null
    if (expiresAt.isAfter(serverTime)) return null
    return state.copy(
        consent = RemoteConsentChoice.UNDECIDED.storedValue,
        grantId = null,
        grantKind = null,
        grantExpiresAt = null,
        oneTimeSessionId = null,
    )
}

private fun validRemoteCommandReceipt(receipt: RemoteCommandReceipt): Boolean =
    isCanonicalUuidV4(receipt.sessionId) &&
        isCanonicalUuidV4(receipt.commandId) &&
        receipt.sequence in 1L..100L &&
        receipt.state in setOf(RECEIPT_RESERVED, RECEIPT_COMPLETED) &&
        when (receipt.state) {
            RECEIPT_RESERVED -> receipt.outcome == null && receipt.reasonCode == null
            RECEIPT_COMPLETED -> when (receipt.outcome) {
                "acknowledged" -> receipt.reasonCode == null
                "rejected" -> receipt.reasonCode in REMOTE_COMMAND_REJECTION_REASONS
                else -> false
            }
            else -> false
        }

/**
 * One small, synchronously committed safety journal. Consent, revoke, Stop and
 * command execution identities must survive response loss and process death;
 * none of them belongs in the financial Room database or its outbox.
 */
internal class RemoteAssistanceStore(
    context: Context,
    private val currentScope: () -> RemoteAssistanceJournalScope?,
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = STORE_LOCK

    fun snapshot(): PersistedRemoteAssistanceState = synchronized(lock) {
        readCurrentLocked()?.state ?: PersistedRemoteAssistanceState()
    }

    fun recordDecision(
        grantId: String,
        allow: Boolean,
        grantKind: RemoteGrantKind,
        grantExpiresAt: Instant,
    ): PendingRemoteGrantDecision? =
        synchronized(lock) {
            if (!isCanonicalUuidV4(grantId)) return@synchronized null
            val expiry = grantExpiresAt.toString()
            if (parseRemoteInstant(expiry) == null) return@synchronized null
            val journal = readCurrentLocked() ?: return@synchronized null
            val current = journal.state
            val decision = if (allow) {
                RemoteGrantDecisionWire.ACCEPTED
            } else {
                RemoteGrantDecisionWire.DECLINED
            }
            current.pendingDecision?.let { pending ->
                // A mutation ID is immutable once journalled. Never overwrite
                // an unacknowledged decision (even for a new grant) because the
                // first response may have reached the server before transport loss.
                return@synchronized pending.takeIf {
                    it.grantId == grantId &&
                        it.decision == decision &&
                        current.grantKind == grantKind.storedValue &&
                        current.grantExpiresAt == expiry
                }
            }
            val mutation = PendingRemoteGrantDecision(grantId, decision, uuid())
            if (!isCanonicalUuidV4(mutation.decisionId)) return@synchronized null
            val choice = if (allow) RemoteConsentChoice.ALLOWED else RemoteConsentChoice.DENIED
            val next = current.copy(
                consent = choice.storedValue,
                grantId = grantId,
                grantKind = grantKind.storedValue,
                grantExpiresAt = expiry,
                oneTimeSessionId = null,
                pendingDecision = mutation,
                pendingRevocation = null,
            )
            mutation.takeIf { writeLocked(journal.scope, next) }
        }

    /** Response-loss replay is grant-scoped; a different request always needs fresh consent. */
    fun pendingDecisionForGrant(grantId: String): PendingRemoteGrantDecision? = synchronized(lock) {
        val current = readCurrentLocked()?.state ?: return@synchronized null
        replayableGrantDecision(current, grantId)
    }

    fun acknowledgeDecision(grantId: String, decisionId: String): Boolean = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized false
        val current = journal.state
        val pending = current.pendingDecision ?: return@synchronized false
        if (pending.grantId != grantId || pending.decisionId != decisionId) return@synchronized false
        writeLocked(journal.scope, current.copy(pendingDecision = null))
    }

    /** Atomically binds a one-time grant to the first admitted session. */
    fun authorizeSession(grantId: String, sessionId: String, serverTimeRaw: String): Boolean =
        synchronized(lock) {
            val journal = readCurrentLocked() ?: return@synchronized false
            val current = journal.state
            // A locally journalled Stop is authoritative immediately. If the
            // backend repeats the still-active session after logout/network
            // loss, the same account must reconcile the immutable end action
            // before this session can ever be admitted again.
            if (current.pendingSessionEnd?.sessionId == sessionId) {
                return@synchronized false
            }
            val authorization = evaluatePersistedGrantAuthorization(
                state = current,
                grantId = grantId,
                sessionId = sessionId,
                serverTimeRaw = serverTimeRaw,
            )
            if (!authorization.authorized) return@synchronized false
            if (!authorization.bindOneTimeSession) return@synchronized true
            writeLocked(journal.scope, current.copy(oneTimeSessionId = sessionId))
        }

    fun recordRevocation(): PendingRemoteGrantRevocation? = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized null
        val current = journal.state
        current.pendingRevocation?.let { return@synchronized it }
        val grantId = current.grantId?.takeIf(::isCanonicalUuidV4) ?: return@synchronized null
        val mutation = PendingRemoteGrantRevocation(grantId, uuid())
        if (!isCanonicalUuidV4(mutation.revocationId)) return@synchronized null
        val next = current.copy(
            consent = RemoteConsentChoice.REVOKED.storedValue,
            pendingDecision = null,
            pendingRevocation = mutation,
        )
        mutation.takeIf { writeLocked(journal.scope, next) }
    }

    fun acknowledgeRevocation(grantId: String, revocationId: String): Boolean = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized false
        val current = journal.state
        val pending = current.pendingRevocation ?: return@synchronized false
        if (pending.grantId != grantId || pending.revocationId != revocationId) {
            return@synchronized false
        }
        writeLocked(journal.scope, current.copy(pendingRevocation = null))
    }

    fun recordSessionEnd(sessionId: String, reason: String): PendingRemoteSessionEnd? =
        synchronized(lock) {
            if (!isCanonicalUuidV4(sessionId) || reason !in REMOTE_DEVICE_END_REASONS) {
                return@synchronized null
            }
            val journal = readCurrentLocked() ?: return@synchronized null
            val current = journal.state
            current.pendingSessionEnd?.let { pending ->
                // End action IDs are immutable across response loss. A second
                // session can be stopped locally, but it may not overwrite the
                // first session's unacknowledged audit mutation.
                return@synchronized pending.takeIf { it.sessionId == sessionId }
            }
            val mutation = PendingRemoteSessionEnd(sessionId, uuid(), reason)
            if (!isCanonicalUuidV4(mutation.endId)) return@synchronized null
            mutation.takeIf {
                writeLocked(journal.scope, current.copy(pendingSessionEnd = mutation))
            }
        }

    fun acknowledgeSessionEnd(sessionId: String, endId: String): Boolean = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized false
        val current = journal.state
        val pending = current.pendingSessionEnd ?: return@synchronized false
        if (pending.sessionId != sessionId || pending.endId != endId) return@synchronized false
        writeLocked(journal.scope, current.copy(pendingSessionEnd = null))
    }

    /**
     * Called only after an authenticated device-state response proves there is
     * no active session, or proves this exact command session is terminal.
     * This makes a completed scope safely evictable without dropping work that
     * could still need an idempotent server receipt.
     */
    fun markCommandSessionTerminal(reportedSessionId: String? = null): Boolean = synchronized(lock) {
        if (reportedSessionId != null && !isCanonicalUuidV4(reportedSessionId)) {
            return@synchronized false
        }
        val journal = readCurrentLocked() ?: return@synchronized false
        val current = journal.state
        val commandSessionId = current.commandSessionId ?: return@synchronized true
        if (reportedSessionId != null && reportedSessionId != commandSessionId) {
            return@synchronized false
        }
        writeLocked(
            journal.scope,
            current.copy(
                commandSessionId = null,
                lastAcknowledgedSequence = 0L,
                receipts = current.receipts.filterNot { it.sessionId == commandSessionId },
            ),
        )
    }

    /** Forget ALLOWED authority only from an authenticated server-time expiry proof. */
    fun retireExpiredGrant(serverTimeRaw: String): Boolean = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized false
        val serverTime = parseRemoteInstant(serverTimeRaw) ?: return@synchronized false
        val retired = retiredExpiredGrantState(journal.state, serverTime)
            ?: return@synchronized false
        writeLocked(journal.scope, retired)
    }

    /**
     * A signed state poll supplies trusted server time for this exact company
     * and installation. It may narrow expired ALLOWED journals left by former
     * employees, but cannot touch another tenant/install, an active grant, or
     * any unresolved action/session/receipt.
     */
    fun retireExpiredInstallationGrants(
        serverTimeRaw: String,
        protectedActiveGrantId: String? = null,
    ): Int = synchronized(lock) {
        val authenticatedScope = currentScope()?.takeIf(::validJournalScope)
            ?: return@synchronized 0
        if (
            protectedActiveGrantId != null &&
            !isCanonicalUuidV4(protectedActiveGrantId)
        ) return@synchronized 0
        val serverTime = parseRemoteInstant(serverTimeRaw) ?: return@synchronized 0
        val current = readJournalsLocked()
        var retiredCount = 0
        val reconciled = current.journals.map { journal ->
            if (
                journal.scope.companyId != authenticatedScope.companyId ||
                journal.scope.installationId != authenticatedScope.installationId
            ) return@map journal
            val retired = retiredExpiredGrantState(
                state = journal.state,
                serverTime = serverTime,
                protectedActiveGrantId = protectedActiveGrantId,
            ) ?: return@map journal
            retiredCount += 1
            journal.copy(state = retired)
        }
        if (retiredCount == 0) return@synchronized 0
        // Never let a B->C scope switch race this deliberately narrowing
        // installation reconciliation.
        if (currentScope()?.takeIf(::validJournalScope) != authenticatedScope) {
            return@synchronized 0
        }
        val next = PersistedRemoteAssistanceJournals(
            format = REMOTE_JOURNALS_FORMAT,
            journals = reconciled,
        )
        if (!prefs.edit().putString(KEY_STATE, json.encodeToString(next)).commit()) {
            return@synchronized 0
        }
        retiredCount
    }

    fun activateCommandSession(sessionId: String): Boolean = synchronized(lock) {
        if (!isCanonicalUuidV4(sessionId)) return@synchronized false
        val journal = readCurrentLocked() ?: return@synchronized false
        val current = journal.state
        if (current.commandSessionId == sessionId) return@synchronized true
        writeLocked(
            journal.scope,
            current.copy(
                commandSessionId = sessionId,
                lastAcknowledgedSequence = 0L,
            ),
        )
    }

    fun afterSequence(sessionId: String, initialPoll: Boolean): Long = synchronized(lock) {
        if (initialPoll) return@synchronized 0L
        val current = readCurrentLocked()?.state ?: return@synchronized 0L
        if (current.commandSessionId == sessionId) current.lastAcknowledgedSequence else 0L
    }

    fun commandReceipt(commandId: String): RemoteCommandReceipt? = synchronized(lock) {
        readCurrentLocked()?.state?.receipts?.firstOrNull { it.commandId == commandId }
    }

    /**
     * A result response may reach the server immediately before process death.
     * Keep the completed local receipt replayable until its contiguous local
     * acknowledgement is durably advanced.
     */
    fun completedReceiptAwaitingAck(sessionId: String): RemoteCommandReceipt? = synchronized(lock) {
        val current = readCurrentLocked()?.state ?: return@synchronized null
        if (current.commandSessionId != sessionId) return@synchronized null
        current.receipts.singleOrNull {
            it.sessionId == sessionId &&
                it.sequence == current.lastAcknowledgedSequence + 1L &&
                it.state == RECEIPT_COMPLETED
        }
    }

    fun reserveCommand(
        sessionId: String,
        commandId: String,
        sequence: Long,
    ): RemoteCommandReceipt? = synchronized(lock) {
        if (
            !isCanonicalUuidV4(sessionId) ||
            !isCanonicalUuidV4(commandId) ||
            sequence !in 1L..100L
        ) return@synchronized null
        val journal = readCurrentLocked() ?: return@synchronized null
        val current = journal.state
        current.receipts.firstOrNull { it.commandId == commandId }?.let { existing ->
            return@synchronized existing.takeIf {
                it.sessionId == sessionId && it.sequence == sequence
            }
        }
        if (
            current.commandSessionId != sessionId ||
            sequence != current.lastAcknowledgedSequence + 1L
        ) return@synchronized null
        val receipt = RemoteCommandReceipt(
            sessionId = sessionId,
            commandId = commandId,
            sequence = sequence,
            state = RECEIPT_RESERVED,
        )
        val next = current.copy(receipts = appendBoundedRemoteReceipt(current.receipts, receipt))
        receipt.takeIf { writeLocked(journal.scope, next) }
    }

    fun completeCommand(
        commandId: String,
        outcome: String,
        reasonCode: String? = null,
    ): RemoteCommandReceipt? = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized null
        val current = journal.state
        val reserved = current.receipts.firstOrNull { it.commandId == commandId }
            ?: return@synchronized null
        val completed = reserved.copy(
            state = RECEIPT_COMPLETED,
            outcome = outcome,
            reasonCode = reasonCode,
        )
        if (!validRemoteCommandReceipt(completed)) return@synchronized null
        val next = current.copy(receipts = appendBoundedRemoteReceipt(current.receipts, completed))
        completed.takeIf { writeLocked(journal.scope, next) }
    }

    /** A reservation from a prior process is never executed again. */
    fun recoverInterruptedCommand(commandId: String): RemoteCommandReceipt? = synchronized(lock) {
        val receipt = readCurrentLocked()?.state?.receipts?.firstOrNull {
            it.commandId == commandId
        }
            ?: return@synchronized null
        if (receipt.state == RECEIPT_COMPLETED) return@synchronized receipt
        completeCommand(commandId, outcome = "rejected", reasonCode = "execution_failed")
    }

    fun acknowledgeCommand(commandId: String): Boolean = synchronized(lock) {
        val journal = readCurrentLocked() ?: return@synchronized false
        val current = journal.state
        val receipt = current.receipts.firstOrNull { it.commandId == commandId }
            ?.takeIf { it.state == RECEIPT_COMPLETED }
            ?: return@synchronized false
        if (
            current.commandSessionId != receipt.sessionId ||
            receipt.sequence != current.lastAcknowledgedSequence + 1L
        ) return@synchronized false
        writeLocked(
            journal.scope,
            current.copy(lastAcknowledgedSequence = receipt.sequence),
        )
    }

    private fun readCurrentLocked(): ScopedRemoteAssistanceJournal? {
        val scope = currentScope()?.takeIf(::validJournalScope) ?: return null
        val state = readJournalsLocked().journals.firstOrNull { it.scope == scope }?.state
            ?: PersistedRemoteAssistanceState()
        return ScopedRemoteAssistanceJournal(scope, state)
    }

    private fun readJournalsLocked(): PersistedRemoteAssistanceJournals {
        val empty = PersistedRemoteAssistanceJournals(REMOTE_JOURNALS_FORMAT, emptyList())
        val raw = prefs.getString(KEY_STATE, null) ?: return empty
        val decoded = runCatching {
            json.decodeFromString<PersistedRemoteAssistanceJournals>(raw)
        }.getOrNull() ?: return empty
        if (
            decoded.format != REMOTE_JOURNALS_FORMAT ||
            decoded.journals.size > MAX_REMOTE_SCOPED_JOURNALS ||
            decoded.journals.map(ScopedRemoteAssistanceJournal::scope).distinct().size !=
            decoded.journals.size ||
            decoded.journals.any {
                !validJournalScope(it.scope) || validRemoteAssistanceState(it.state) == null
            }
        ) return empty
        return decoded
    }

    private fun writeLocked(
        scope: RemoteAssistanceJournalScope,
        value: PersistedRemoteAssistanceState,
    ): Boolean {
        // Cache scope can be invalidated from another thread. Never commit an
        // A journal after the authenticated process has already moved to B.
        if (currentScope()?.takeIf(::validJournalScope) != scope) return false
        val valid = validRemoteAssistanceState(value) ?: return false
        val current = readJournalsLocked()
        var retained = current.journals.filterNot { it.scope == scope }
        if (retained.size >= MAX_REMOTE_SCOPED_JOURNALS) {
            val evictionIndex = retained.indexOfFirst {
                !hasUnresolvedRemoteJournalWork(it.state)
            }
            if (evictionIndex < 0) return false
            // Losing a completed old consent can only narrow authority: that
            // user will need a fresh explicit grant. Unresolved mutations and
            // potentially active command sessions are never eviction candidates.
            retained = retained.filterIndexed { index, _ -> index != evictionIndex }
        }
        val next = PersistedRemoteAssistanceJournals(
            format = REMOTE_JOURNALS_FORMAT,
            journals = retained + ScopedRemoteAssistanceJournal(scope, valid),
        )
        return prefs.edit().putString(KEY_STATE, json.encodeToString(next)).commit()
    }

    private companion object {
        const val PREFS_NAME = "dcompany_remote_assistance"
        const val KEY_STATE = "state"
        const val MAX_REMOTE_SCOPED_JOURNALS = 16
        val STORE_LOCK = Any()
    }
}

private fun validJournalScope(scope: RemoteAssistanceJournalScope): Boolean =
    isCanonicalUuidV4(scope.companyId) &&
        isCanonicalUuidV4(scope.installationId) &&
        isCanonicalUuidV4(scope.userId)

internal const val REMOTE_STORE_FORMAT = 2
private const val REMOTE_JOURNALS_FORMAT = 3
internal const val MAX_REMOTE_COMMAND_RECEIPTS = 128
internal const val RECEIPT_RESERVED = "reserved"
internal const val RECEIPT_COMPLETED = "completed"

internal val REMOTE_DEVICE_END_REASONS = setOf(
    "user_ended",
    "permission_revoked",
    "capture_stopped",
    "app_backgrounded",
)

internal val REMOTE_COMMAND_REJECTION_REASONS = setOf(
    "unsupported_command",
    "module_unavailable",
    "permission_denied",
    "not_in_foreground",
    "session_inactive",
    "execution_failed",
    "session_ended",
)

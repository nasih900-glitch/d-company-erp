package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

private val Context.outboxOwnerDataStore by preferencesDataStore(name = "dcompany_outbox_owner")

/** Stable server identities only. Names, email addresses, and tokens are never stored here. */
data class OutboxOwnerIdentity(
    val userId: String,
    val companyId: String,
    val branchId: String?,
) {
    companion object {
        fun from(me: MeResponse) = OutboxOwnerIdentity(
            userId = me.userId,
            companyId = me.companyId,
            branchId = me.branchId.normalizedBranch(),
        )
    }
}

private fun String?.normalizedBranch(): String? = this?.trim()?.takeIf(String::isNotEmpty)

/**
 * Parses only identity claims from an access token returned by the trusted
 * login endpoint. This is not JWT verification and is never used as server
 * authorisation; it is a pre-install check that stops a different account's
 * token becoming active long enough to drain the shared local outbox.
 */
object AccessTokenIdentityParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(token: String): OutboxOwnerIdentity? = runCatching {
        val parts = token.split('.')
        require(parts.size == 3)
        val payload = parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')
        val decoded = Base64.getUrlDecoder().decode(payload).decodeToString()
        val claims = json.parseToJsonElement(decoded).jsonObject
        val userId = claims["sub"]?.jsonPrimitive?.content?.trim().orEmpty()
        val companyId = claims["company_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val branchId = claims["branch_id"]
            ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            .normalizedBranch()
        require(userId.isNotEmpty() && companyId.isNotEmpty())
        OutboxOwnerIdentity(userId, companyId, branchId)
    }.getOrNull()
}

/** Small durable store for the one identity currently allowed to own this device's outbox. */
class OutboxOwnerStore(private val context: Context) {
    private val userKey = stringPreferencesKey("owner_user_id")
    private val companyKey = stringPreferencesKey("owner_company_id")
    private val branchKey = stringPreferencesKey("owner_branch_id")

    @Volatile private var cached: OutboxOwnerIdentity? = null

    suspend fun load() {
        val prefs = context.outboxOwnerDataStore.data.first()
        val userId = prefs[userKey]
        val companyId = prefs[companyKey]
        cached = if (userId.isNullOrBlank() || companyId.isNullOrBlank()) null
        else OutboxOwnerIdentity(userId, companyId, prefs[branchKey].normalizedBranch())
    }

    fun owner(): OutboxOwnerIdentity? = cached

    suspend fun remember(owner: OutboxOwnerIdentity) {
        context.outboxOwnerDataStore.edit { prefs ->
            prefs[userKey] = owner.userId
            prefs[companyKey] = owner.companyId
            if (owner.branchId == null) prefs.remove(branchKey) else prefs[branchKey] = owner.branchId
        }
        // Publish only after the durable write succeeds. A process death must
        // never leave memory claiming B owns the queue while disk still says A.
        cached = owner
    }
}

data class OutboxSnapshot(val groups: List<UnresolvedOutboxGroup>) {
    val count: Int = groups.sumOf { it.count }
    val isClean: Boolean get() = count == 0
}

/**
 * Staff cannot resolve every ownership blocker by waiting for Sync. Some rows
 * deliberately remain local after the server has accepted the write because a
 * second, explicit business action is still required (for example billing an
 * ended gaming session). Keep that distinction in one pure classifier so all
 * sign-out guidance remains truthful as new outbox resources are added.
 */
internal data class SignOutBlockers(
    val gamingAwaitingPosCount: Int,
    val activeRefundCount: Int,
    val kitchenPendingCount: Int,
    val kitchenRejectedCount: Int,
    val pendingWriteCount: Int,
    val rejectedWriteCount: Int,
)

internal fun OutboxSnapshot.signOutBlockers(): SignOutBlockers {
    fun countWhere(predicate: (UnresolvedOutboxGroup) -> Boolean): Int =
        groups.filter(predicate).sumOf(UnresolvedOutboxGroup::count)

    val gamingAwaitingPos = countWhere {
        it.resource == "gaming_sessions" && it.state == GamingSessionState.ENDED_UNBILLED
    }
    val activeRefunds = countWhere {
        it.resource == "refunds" && it.state in setOf(
            RefundState.ACCEPTED_CASH_DUE,
            RefundState.CASH_HANDOFF_IN_PROGRESS,
            RefundState.CASH_SETTLE_PENDING,
            RefundState.CASH_SETTLE_REJECTED,
            RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
            RefundState.CASH_FINALIZE_REJECTED,
            RefundState.ACCEPTED_PROVIDER_DUE,
            RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
            RefundState.PROVIDER_COMPLETION_PENDING,
            RefundState.PROVIDER_COMPLETION_REJECTED,
            RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
            RefundState.PROVIDER_FINALIZE_REJECTED,
        )
    }
    val kitchenPending = countWhere {
        it.resource in setOf("kitchen_advances", "kitchen_cancellation_acks") &&
            it.state == "pending"
    }
    val kitchenRejected = countWhere {
        it.resource in setOf("kitchen_advances", "kitchen_cancellation_acks") &&
            it.state == "rejected"
    }
    val workflowActionCount =
        gamingAwaitingPos + activeRefunds + kitchenPending + kitchenRejected
    val rejectedWrites = countWhere { group ->
        group.state.contains("rejected") &&
            group.resource !in setOf("kitchen_advances", "kitchen_cancellation_acks") &&
            !(group.resource == "refunds" && group.state in setOf(
                RefundState.ACCEPTED_CASH_DUE, RefundState.CASH_HANDOFF_IN_PROGRESS,
                RefundState.CASH_SETTLE_PENDING, RefundState.CASH_SETTLE_REJECTED,
                RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING, RefundState.CASH_FINALIZE_REJECTED,
                RefundState.ACCEPTED_PROVIDER_DUE, RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
                RefundState.PROVIDER_COMPLETION_PENDING, RefundState.PROVIDER_COMPLETION_REJECTED,
                RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING, RefundState.PROVIDER_FINALIZE_REJECTED,
            ))
    }
    return SignOutBlockers(
        gamingAwaitingPosCount = gamingAwaitingPos,
        activeRefundCount = activeRefunds,
        kitchenPendingCount = kitchenPending,
        kitchenRejectedCount = kitchenRejected,
        pendingWriteCount = (count - workflowActionCount - rejectedWrites).coerceAtLeast(0),
        rejectedWriteCount = rejectedWrites,
    )
}

/** Exact recovery copy for the account-safety dialog shown after a sign-out attempt. */
internal fun signOutBlockedMessage(snapshot: OutboxSnapshot): String {
    val blockers = snapshot.signOutBlockers()
    val summary = buildList {
        if (blockers.gamingAwaitingPosCount > 0) {
            add(
                if (blockers.gamingAwaitingPosCount == 1) {
                    "1 ended gaming session still needs a POS decision"
                } else {
                    "${blockers.gamingAwaitingPosCount} ended gaming sessions still need a POS decision"
                },
            )
        }
        if (blockers.activeRefundCount > 0) {
            add(
                if (blockers.activeRefundCount == 1) {
                    "1 refund still needs a safe payout or accounting decision"
                } else {
                    "${blockers.activeRefundCount} refunds still need safe payout or accounting decisions"
                },
            )
        }
        if (blockers.kitchenPendingCount > 0) {
            add(
                if (blockers.kitchenPendingCount == 1) {
                    "1 kitchen update is waiting for server confirmation"
                } else {
                    "${blockers.kitchenPendingCount} kitchen updates are waiting for server confirmation"
                },
            )
        }
        if (blockers.kitchenRejectedCount > 0) {
            add(
                if (blockers.kitchenRejectedCount == 1) {
                    "1 kitchen update needs review"
                } else {
                    "${blockers.kitchenRejectedCount} kitchen updates need review"
                },
            )
        }
        if (blockers.pendingWriteCount > 0) {
            add(
                if (blockers.pendingWriteCount == 1) {
                    "1 saved change is waiting for server confirmation"
                } else {
                    "${blockers.pendingWriteCount} saved changes are waiting for server confirmation"
                },
            )
        }
        if (blockers.rejectedWriteCount > 0) {
            add(
                if (blockers.rejectedWriteCount == 1) {
                    "1 saved change was rejected and needs recovery"
                } else {
                    "${blockers.rejectedWriteCount} saved changes were rejected and need recovery"
                },
            )
        }
    }

    val actions = buildList {
        if (blockers.gamingAwaitingPosCount > 0) {
            add(
                "Open Gaming. For each ended session, choose Send to POS to bill it, or " +
                    "Cancel / void and enter a reason if it should not be charged.",
            )
        }
        if (blockers.activeRefundCount > 0) {
            add(
                "Open Refunds and verify each task against the customer, drawer, and provider. If a payout " +
                    "was already started, do not pay twice after a restart; record its exact outcome and " +
                    "finish accounting, or use protected recovery only when no value moved.",
            )
        }
        if (blockers.kitchenPendingCount > 0 || blockers.kitchenRejectedCount > 0) {
            add(
                "Open Kitchen. Use Sync now for waiting updates. For an update that needs review, " +
                    "choose Check again; remove the saved update only after verifying the live " +
                    "server ticket is already correct.",
            )
        }
        if (blockers.pendingWriteCount > 0 || blockers.rejectedWriteCount > 0) {
            add(
                "Reconnect and wait for Sync to finish, then retry or correct any rejected work " +
                    "on the affected screen.",
            )
        }
    }

    val detail = when (summary.size) {
        0 -> "saved work still needs attention"
        1 -> summary.single()
        2 -> summary.joinToString(" and ")
        else -> summary.dropLast(1).joinToString(", ") + ", and " + summary.last()
    }
    return "Sign-out blocked: $detail. ${actions.joinToString(" ")} Then sign out."
}

sealed interface OutboxGateResult {
    data object Allowed : OutboxGateResult
    data class Blocked(val message: String) : OutboxGateResult
}

/** Pure policy split out so the mismatch and legacy-quarantine rules are unit-testable. */
object OutboxOwnershipPolicy {
    enum class Purpose { SIGN_IN, SYNC }

    fun decide(
        owner: OutboxOwnerIdentity?,
        candidate: OutboxOwnerIdentity?,
        unresolvedCount: Int,
        purpose: Purpose,
    ): OutboxGateResult {
        if (unresolvedCount == 0) {
            if (
                purpose == Purpose.SYNC && owner != null && candidate != null && owner != candidate
            ) {
                return OutboxGateResult.Blocked(
                    "Sync is paused while the account change is being verified. Wait a moment; " +
                        "if this remains, sign in again with the previous account or ask support.",
                )
            }
            return OutboxGateResult.Allowed
        }
        if (owner == null) {
            return OutboxGateResult.Blocked(
                "This tablet has $unresolvedCount older unsynced item(s), but their staff owner " +
                    "cannot be verified. Sync and account switching are locked to prevent posting " +
                    "them under the wrong person. Keep the tablet offline and ask a manager or " +
                    "support technician to recover the queue.",
            )
        }
        if (candidate == null) {
            return OutboxGateResult.Blocked(
                "This tablet has $unresolvedCount unsynced item(s) owned by the previous staff " +
                    "session. Sign in again with that same account, reconnect, and resolve the " +
                    "Sync warnings before switching users.",
            )
        }
        if (owner != candidate) {
            val action = if (purpose == Purpose.SIGN_IN) "signing in as another user" else "syncing"
            return OutboxGateResult.Blocked(
                "Blocked $action: $unresolvedCount unsynced item(s) belong to a different " +
                    "user, company, or branch. Sign in with the account that created them, then " +
                    "reconnect and resolve the Sync warnings before switching users.",
            )
        }
        return OutboxGateResult.Allowed
    }
}

/**
 * Global owner/quarantine gate for the existing shared Room outbox.
 *
 * This provides single-user-at-a-time safety only. It deliberately refuses
 * unknown legacy queues and identity changes while work is unresolved. It is
 * not a substitute for per-row owner/company/branch columns if concurrent or
 * intentionally shared multi-user offline operation is introduced later.
 */
class OutboxSafetyGate(
    private val db: ErpDatabase,
    private val owners: OutboxOwnerStore,
    private val tokens: TokenStore,
) {
    private val mutex = Mutex()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    suspend fun snapshot(): OutboxSnapshot = OutboxSnapshot(
        db.outboxSafetyDao().unresolvedGroups(),
    )

    /** Check a freshly returned access token before it replaces the active token. */
    suspend fun canInstallSession(accessToken: String): OutboxGateResult = mutex.withLock {
        val candidate = AccessTokenIdentityParser.parse(accessToken)
            ?: return@withLock blocked(
                "The server returned an unreadable session identity. No account was changed; " +
                    "check the connection and try again.",
            )
        val current = snapshot()
        result(
            OutboxOwnershipPolicy.decide(
                owners.owner(), candidate, current.count, OutboxOwnershipPolicy.Purpose.SIGN_IN,
            ),
        )
    }

    /**
     * Final check using `/auth/me`. The owner is replaced only if the outbox
     * is clean; otherwise the exact existing identity must match.
     */
    suspend fun bindAuthenticated(identity: OutboxOwnerIdentity): OutboxGateResult = mutex.withLock {
        val current = snapshot()
        val decision = OutboxOwnershipPolicy.decide(
            owners.owner(), identity, current.count, OutboxOwnershipPolicy.Purpose.SIGN_IN,
        )
        if (decision is OutboxGateResult.Allowed) {
            if (current.isClean) owners.remember(identity)
            _notice.value = null
        }
        result(decision)
    }

    /** Ordinary sign-out is intentionally refused until all captured work is resolved. */
    suspend fun canSignOut(): OutboxGateResult = mutex.withLock {
        val current = snapshot()
        if (current.isClean) {
            // Keep the last owner through sign-out. A later clean sign-in may
            // safely replace it, and retaining it narrows the race with an
            // already-running feature coroutine that finishes just after the
            // sign-out check.
            _notice.value = null
            return@withLock OutboxGateResult.Allowed
        }
        blocked(
            signOutBlockedMessage(current),
        )
    }

    /** Called at the start of every write-draining sync pass. */
    suspend fun canSync(): OutboxGateResult = mutex.withLock {
        val current = snapshot()
        val active = tokens.accessToken()?.let(AccessTokenIdentityParser::parse)
        result(
            OutboxOwnershipPolicy.decide(
                owners.owner(), active, current.count, OutboxOwnershipPolicy.Purpose.SYNC,
            ),
        )
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun publishNotice(message: String) {
        _notice.value = message
    }

    private fun result(value: OutboxGateResult): OutboxGateResult {
        if (value is OutboxGateResult.Blocked) _notice.value = value.message
        return value
    }

    private fun blocked(message: String): OutboxGateResult.Blocked =
        OutboxGateResult.Blocked(message).also { _notice.value = message }
}

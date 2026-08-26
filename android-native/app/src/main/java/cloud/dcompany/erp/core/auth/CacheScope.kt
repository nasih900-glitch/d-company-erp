package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import cloud.dcompany.erp.core.db.ErpDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class CacheScope(
    val userId: String,
    val companyId: String,
    val branchId: String?,
    val terminalId: String?,
) {
    init {
        require(userId.isNotBlank())
        require(companyId.isNotBlank())
    }
}

internal val SERVER_DERIVED_CACHE_TABLES = listOf(
    "menu_items",
    "menu_categories",
    "sync_meta",
    "gaming_stations",
    "gaming_session_cache",
    "kitchen_order_cache",
    "cafe_floors",
    "cafe_tables",
    "cafe_bill_cache",
    "refund_order_cache",
    "report_snapshots",
    "customer_cache",
    "staff_cache",
    "on_shift_cache",
    "ingredient_cache",
    "supplier_cache",
    "batch_cache",
    "expense_cache",
    "asset_cache",
    "capital_entry_cache",
    "event_cache",
    "event_ticket_cache",
    "membership_tier_cache",
    "customer_membership_cache",
    "customer_membership_history_cache",
    "company_cache",
    "branch_cache",
    "terminal_cache",
    "held_order_cache",
    "server_open_shift_cache",
    "shift_history_cache",
    "membership_payment_task_cache",
    "membership_refund_task_cache",
    "membership_refund_attempt_cache",
)

internal val LOCAL_DURABLE_TABLES = listOf(
    // Child rows precede their headers so this remains safe if Room gains
    // foreign-key enforcement for either aggregate in a later schema.
    "local_order_lines",
    "local_orders",
    "local_shifts",
    "local_gaming_sessions",
    "local_kitchen_advances",
    "local_table_orders",
    "local_cafe_actions",
    "local_cafe_bills",
    "local_kitchen_cancellation_acks",
    "local_refunds",
    "local_customers",
    "local_menu_categories",
    "local_menu_items",
    "local_staff",
    "local_ingredients",
    "local_suppliers",
    "local_grn_lines",
    "local_grns",
    "local_adjustments",
    "local_expenses",
    "local_assets",
    "local_capital_entries",
    "local_ticket_sales",
    "local_check_ins",
    "local_subscriptions",
    "local_membership_cancellations",
    "local_membership_refunds",
    "local_membership_payment_actions",
    "local_membership_refund_actions",
    "local_company_edits",
    "local_branches",
    "local_terminals",
    "local_held_order_payments",
)

internal val ALL_SCOPE_TABLES = SERVER_DERIVED_CACHE_TABLES + LOCAL_DURABLE_TABLES

internal interface ScopeDataPurger {
    /** Cheap preflight that preserves the prior valid marker on an obvious refusal. */
    suspend fun hasUnresolvedWork(): Boolean

    /**
     * Re-check and purge in one Room transaction. `false` means a local write
     * appeared after preflight, so the caller must leave the workspace locked.
     */
    suspend fun purgeIfClean(): Boolean
}

internal class RoomScopeDataPurger(private val db: ErpDatabase) : ScopeDataPurger {
    override suspend fun hasUnresolvedWork(): Boolean =
        db.outboxSafetyDao().unresolvedGroups().isNotEmpty()

    override suspend fun purgeIfClean(): Boolean = db.withTransaction {
        if (db.outboxSafetyDao().unresolvedGroups().isNotEmpty()) {
            false
        } else {
            ALL_SCOPE_TABLES.forEach { table ->
                db.cacheIsolationDao().delete(SimpleSQLiteQuery("DELETE FROM `$table`"))
            }
            true
        }
    }
}

internal interface CacheScopeMarker {
    fun current(): CacheScope?
    fun remember(scope: CacheScope): Boolean
    fun clear(): Boolean
}

/**
 * SharedPreferences is intentional here: synchronous commits let a scope
 * change durably invalidate the old marker before Room is touched, then publish
 * the new marker only after the complete Room purge commits.
 */
internal class SharedPreferencesCacheScopeMarker(context: Context) : CacheScopeMarker {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun current(): CacheScope? {
        if (prefs.getInt(KEY_FORMAT, 0) != FORMAT_VERSION) return null
        val userId = prefs.getString(KEY_USER, null)?.takeIf(String::isNotBlank) ?: return null
        val companyId = prefs.getString(KEY_COMPANY, null)?.takeIf(String::isNotBlank) ?: return null
        return CacheScope(
            userId = userId,
            companyId = companyId,
            branchId = prefs.getString(KEY_BRANCH, null).normalizedScopePart(),
            terminalId = prefs.getString(KEY_TERMINAL, null).normalizedScopePart(),
        )
    }

    override fun remember(scope: CacheScope): Boolean = prefs.edit()
        .putInt(KEY_FORMAT, FORMAT_VERSION)
        .putString(KEY_USER, scope.userId)
        .putString(KEY_COMPANY, scope.companyId)
        .putString(KEY_BRANCH, scope.branchId.orEmpty())
        .putString(KEY_TERMINAL, scope.terminalId.orEmpty())
        .commit()

    override fun clear(): Boolean = prefs.edit().clear().commit()

    private fun String?.normalizedScopePart(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val PREFS_NAME = "dcompany_cache_scope"
        const val FORMAT_VERSION = 1
        const val KEY_FORMAT = "format"
        const val KEY_USER = "user_id"
        const val KEY_COMPANY = "company_id"
        const val KEY_BRANCH = "branch_id"
        const val KEY_TERMINAL = "terminal_id"
    }
}

class CacheScopeException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class CacheScopeActivation { RETAINED, PURGED }

/**
 * Capability captured before a server request. A response may mutate a
 * replaceable cache only while this exact lease is still active.
 */
data class CacheScopeLease internal constructor(
    val scope: CacheScope,
    internal val generation: Long,
)

sealed interface ScopedCommitResult<out T> {
    data class Committed<T>(val value: T) : ScopedCommitResult<T>
    data object Stale : ScopedCommitResult<Nothing>
}

/** Owns the only transition that may expose server-derived Room rows to a session. */
class CacheIsolationCoordinator internal constructor(
    private val purger: ScopeDataPurger,
    private val marker: CacheScopeMarker,
) {
    private val mutex = Mutex()
    private val generations = AtomicLong(0L)

    @Volatile
    private var activeLease: CacheScopeLease? = null

    internal constructor(context: Context, db: ErpDatabase) : this(
        RoomScopeDataPurger(db),
        SharedPreferencesCacheScopeMarker(context),
    )

    fun isReady(): Boolean = activeLease != null

    /** Capture immediately before an authenticated read request is sent. */
    fun currentLease(): CacheScopeLease? = activeLease

    /**
     * Serialize the final Room write with scope transitions. A plain
     * check-then-write is insufficient because B could activate between them.
     */
    suspend fun commitIfCurrent(
        lease: CacheScopeLease,
        write: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (activeLease != lease) return@withLock false
        write()
        true
    }

    /** Value-returning form used by guarded outbox inserts and CAS updates. */
    suspend fun <T> commitResultIfCurrent(
        lease: CacheScopeLease,
        write: suspend () -> T,
    ): ScopedCommitResult<T> = mutex.withLock {
        if (activeLease != lease) return@withLock ScopedCommitResult.Stale
        ScopedCommitResult.Committed(write())
    }

    private fun deactivateLocked() {
        activeLease = null
        generations.incrementAndGet()
    }

    /**
     * Wait for any already-entered scoped Room commit before revoking the
     * lease. Callers must not publish SignedOut/Blocked until this returns.
     */
    suspend fun deactivate() = mutex.withLock {
        deactivateLocked()
    }

    /**
     * Sign-out's final outbox recheck and lease revocation are one critical
     * section. A feature write therefore either lands first and blocks the
     * gate, or observes the revoked lease and cannot land afterwards.
     */
    internal suspend fun deactivateAfterOutboxGate(
        gate: suspend () -> OutboxGateResult,
    ): OutboxGateResult = mutex.withLock {
        val decision = gate()
        if (decision is OutboxGateResult.Allowed) deactivateLocked()
        decision
    }

    suspend fun invalidate() = mutex.withLock {
        deactivateLocked()
        val cleared = try {
            marker.clear()
        } catch (error: Exception) {
            throw CacheScopeException("The invalid terminal scope could not be locked. Ask support.", error)
        }
        if (!cleared) throw CacheScopeException("The invalid terminal scope could not be locked. Ask support.")
    }

    /** Offline restore may retain only a scope previously validated and committed online. */
    suspend fun activateCached(scope: CacheScope): CacheScopeActivation = mutex.withLock {
        val stored = try {
            marker.current()
        } catch (error: Exception) {
            deactivateLocked()
            throw CacheScopeException(
                "The tablet could not verify the saved account scope. Connect and try again.",
                error,
            )
        }
        if (stored != scope) {
            deactivateLocked()
            throw CacheScopeException(
                "Connect once to verify this account, branch, and terminal before opening cached data.",
            )
        }
        activeLease = newLease(scope)
        CacheScopeActivation.RETAINED
    }

    /** A server-validated scope may replace another scope after an atomic full-data purge. */
    suspend fun activateValidated(scope: CacheScope): CacheScopeActivation = mutex.withLock {
        deactivateLocked()
        val stored = try {
            marker.current()
        } catch (error: Exception) {
            throw CacheScopeException(
                "The tablet could not read its saved account scope. Try again or ask support.",
                error,
            )
        }
        if (stored == scope) {
            activeLease = newLease(scope)
            return@withLock CacheScopeActivation.RETAINED
        }

        // Preserve a still-valid old marker when the refusal is already known.
        // This lets the exact previous workspace reopen and resolve its queue.
        val unresolvedBeforeInvalidation = try {
            purger.hasUnresolvedWork()
        } catch (error: Exception) {
            throw CacheScopeException(
                "The tablet could not verify whether saved work is still pending. No workspace was opened.",
                error,
            )
        }
        if (unresolvedBeforeInvalidation) {
            val message = if (stored == null) {
                "Saved work exists, but its full account, branch, and terminal scope cannot be proven. " +
                    "Reconnect with the previous setup or ask support; no workspace was opened."
            } else {
                "Saved work still belongs to the previous account, branch, or terminal. " +
                    "Reopen that exact workspace and resolve Sync before switching."
            }
            throw CacheScopeException(message)
        }

        // Fail closed across process death: once a scope change begins, the old
        // marker must never be able to reopen data that is being replaced.
        val cleared = try {
            marker.clear()
        } catch (error: Exception) {
            throw CacheScopeException(
                "The tablet could not lock the previous cache scope. No workspace was opened.",
                error,
            )
        }
        if (!cleared) {
            throw CacheScopeException(
                "The tablet could not lock the previous cache scope. No workspace was opened.",
            )
        }

        try {
            if (!purger.purgeIfClean()) {
                throw CacheScopeException(
                    "Saved work appeared while the account change was being secured. " +
                        "The workspace is locked; sign in with the previous account or ask support.",
                )
            }
        } catch (error: CacheScopeException) {
            throw error
        } catch (error: Exception) {
            throw CacheScopeException(
                "The tablet could not isolate cached data for this account. No workspace was opened.",
                error,
            )
        }
        val committed = try {
            marker.remember(scope)
        } catch (error: Exception) {
            throw CacheScopeException(
                "The tablet could not securely save the active account scope. Try again or ask support.",
                error,
            )
        }
        if (!committed) {
            throw CacheScopeException(
                "The tablet could not securely save the active account scope. Try again or ask support.",
            )
        }
        activeLease = newLease(scope)
        CacheScopeActivation.PURGED
    }

    private fun newLease(scope: CacheScope): CacheScopeLease =
        CacheScopeLease(scope, generations.incrementAndGet())
}

/**
 * Shared read-cache boundary for SyncEngine and feature ViewModels. The lease
 * is intentionally captured before [fetch], never after it returns.
 */
internal suspend fun <T> CacheIsolationCoordinator.fetchAndCommitScoped(
    fetch: suspend () -> T,
    store: suspend (T) -> Unit,
): Boolean {
    val lease = currentLease() ?: return false
    val payload = fetch()
    return commitIfCurrent(lease) { store(payload) }
}

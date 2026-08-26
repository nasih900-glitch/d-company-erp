package cloud.dcompany.erp.ui.screens.kitchen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.KitchenAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.KitchenAdvanceState
import cloud.dcompany.erp.core.db.KitchenOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.db.LocalKitchenCancellationAckEntity
import cloud.dcompany.erp.core.db.KitchenCancellationAckState
import cloud.dcompany.erp.core.db.shiftClosingMessageOr
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Fallback poll cadence — belt and suspenders on top of realtime push for
 * a board where staleness is a real operational risk, not just stale UI. */
const val KITCHEN_POLL_MS = 5_000L

/**
 * After a successful advance every button is briefly dead. A ticket that
 * changes state moves lane, the list reflows under the cook's finger, and a
 * gloved double-tap would otherwise land on whatever slid into that spot —
 * advancing a ticket nobody has started cooking. The server cannot save us
 * here: that second tap is a perfectly legal transition on a different order.
 */
private const val ADVANCE_LOCK_MS = 1_200L

data class KitchenUiState(
    val error: String? = null,
    /** Read failure for this cache only; write/action feedback stays in [error]. */
    val refreshError: String? = null,
    val notice: String? = null,
    val orders: List<KitchenOrder> = emptyList(),
    val savedAdvances: List<LocalKitchenAdvanceEntity> = emptyList(),
    val savedCancellationAcks: List<LocalKitchenCancellationAckEntity> = emptyList(),
    val includeServed: Boolean = false,
    /** The ticket whose advance is queued or in flight. */
    val busyOrderId: String? = null,
    val advanceLockedUntilMillis: Long = 0L,
    val everSynced: Boolean = false,
    val lastSyncedAtMillis: Long? = null,
    /** Ticks every second so "synced 4s ago" and the tap lock stay honest. */
    val nowMillis: Long = System.currentTimeMillis(),
) {
    fun lane(state: KitchenState): List<KitchenOrder> =
        orders.filter { KitchenState.from(it.kitchenState) == state }

    /** Anything the server sent with a state this build does not know about. */
    val unknownState: List<KitchenOrder>
        get() = orders.filter { KitchenState.from(it.kitchenState) == null }

    /**
     * When every active line on a ticket was already served, cancelling one
     * released line leaves no active line from which the backend can derive a
     * normal lane. The ticket correctly comes back as `served`, but the
     * unacknowledged cancellation still needs a prominent operational home.
     */
    val cancellationOnly: List<KitchenOrder>
        get() = orders.filter { it.lines.isEmpty() && it.pendingCancellations.isNotEmpty() }

    val newCount: Int get() = lane(KitchenState.RECEIVED).size
    val preparingCount: Int get() = lane(KitchenState.PREPARING).size
    val readyCount: Int get() = lane(KitchenState.READY).size

    val tapsLocked: Boolean get() = busyOrderId != null || nowMillis < advanceLockedUntilMillis

    val pendingAdvances: List<LocalKitchenAdvanceEntity>
        get() = savedAdvances.filter { it.state == KitchenAdvanceState.PENDING }

    val rejectedAdvances: List<LocalKitchenAdvanceEntity>
        get() = savedAdvances.filter { it.state == KitchenAdvanceState.REJECTED }

    val pendingCancellationAcks: List<LocalKitchenCancellationAckEntity>
        get() = savedCancellationAcks.filter { it.state == KitchenCancellationAckState.PENDING }

    val rejectedCancellationAcks: List<LocalKitchenCancellationAckEntity>
        get() = savedCancellationAcks.filter { it.state == KitchenCancellationAckState.REJECTED }

    val acknowledgingLineIds: Set<String>
        get() = savedCancellationAcks.mapTo(mutableSetOf()) { it.lineId }

    /** Seconds since the last successful read, or null before the first one. */
    val secondsSinceSync: Long?
        get() = lastSyncedAtMillis?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) }

    /**
     * A KDS that quietly stops updating is dangerous — the cook reads a frozen
     * screen as "no new orders". Two missed polls is enough to say so out loud.
     */
    val stale: Boolean get() = (secondsSinceSync ?: 0) > (KITCHEN_POLL_MS / 1000) * 3

    val blockingLoadError: String? get() = error ?: refreshError
}

/**
 * Room-backed for the live board (`includeServed = false`) — an advance is
 * captured to an outbox and applied optimistically, so a gloved tap keeps
 * working through a dropped link. A replay is idempotent at the same state,
 * while a stale tablet is reconciled against the authoritative active queue:
 * a server state at or beyond the target, or a ticket that has left active
 * work entirely, satisfies and removes the older saved action.
 *
 * `includeServed = true` (the "what happened today" history view) stays
 * online-only, deliberately: it's a look-back, not an operational need, and
 * caching a second, much larger dataset just to make browsing history work
 * offline isn't worth the complexity for something nobody needs mid-outage.
 */
class KitchenViewModel : ViewModel() {

    private val api = ApiClient.create<KitchenApi>()
    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db

    private val includeServed = MutableStateFlow(false)
    private val busyOrderId = MutableStateFlow<String?>(null)
    private val advanceLockedUntilMillis = MutableStateFlow(0L)
    private val nowMillis = MutableStateFlow(System.currentTimeMillis())
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    /** Non-null only while includeServed = true — bypasses Room entirely. */
    private val historySnapshot = MutableStateFlow<List<KitchenOrder>?>(null)
    @Volatile private var access = KitchenAccess()
    private val syncActions = KitchenSyncActions(
        pullActiveQueue = {
            viewModelScope.launch { appCtx.sync.refresh("kitchen") }
        },
        pullHistory = ::loadHistorySnapshot,
        drainKitchenOutbox = appCtx.sync::requestKitchenSync,
    )

    val state: StateFlow<KitchenUiState> = combine(
        db.kitchenDao().observeOrderCache(),
        combine(
            db.kitchenDao().observeUnresolvedAdvances(),
            db.kitchenDao().observeCancellationAcks(),
            ::Pair,
        ),
        historySnapshot,
        combine(includeServed, busyOrderId, advanceLockedUntilMillis, ::Triple),
        combine(
            error,
            notice,
            nowMillis,
            appCtx.db.syncMetaDao().observe("kitchen"),
            appCtx.sync.resourceRefreshErrors,
        ) { err, note, now, meta, refreshErrors ->
            KitchenStatus(
                error = err,
                refreshError = refreshErrors["kitchen"],
                notice = note,
                nowMillis = now,
                lastSyncedAtMillis = meta?.lastSyncMillis,
            )
        },
    ) { cache, localWork, history, uiA, uiB ->
        val (unresolved, cancellationAcks) = localWork
        val (includeServedNow, busy, lockedUntil) = uiA
        val pending = unresolved.filter { it.state == KitchenAdvanceState.PENDING }
        KitchenUiState(
            error = uiB.error,
            refreshError = uiB.refreshError,
            notice = uiB.notice,
            orders = history ?: mergeCacheWithPending(cache, pending),
            savedAdvances = unresolved,
            savedCancellationAcks = cancellationAcks,
            includeServed = includeServedNow,
            busyOrderId = busy,
            advanceLockedUntilMillis = lockedUntil,
            everSynced = uiB.lastSyncedAtMillis != null,
            lastSyncedAtMillis = uiB.lastSyncedAtMillis,
            nowMillis = uiB.nowMillis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KitchenUiState())

    /** Called only while the KDS is visibly composed. Keeping this clock in
     * the ViewModel would continue waking the tablet after staff leave KDS. */
    fun tick() {
        nowMillis.value = System.currentTimeMillis()
    }

    /** Re-reads the queue — realtime push already keeps Room fresh; this is the manual/poll path. */
    fun refresh() {
        syncActions.refresh(includeServed.value)
    }

    /** Manual retry from the error state: clears the message first so a repeat failure still reads as new. */
    fun retry() {
        error.value = null
        refresh()
    }

    /** Sends only KDS work, then refreshes the active queue in the same pass. */
    fun syncSavedAdvances() {
        error.value = null
        notice.value = null
        syncActions.advancesQueued()
    }

    fun setIncludeServed(include: Boolean) {
        if (include == includeServed.value) return
        includeServed.value = include
        error.value = null
        if (include) loadHistorySnapshot() else historySnapshot.value = null
    }

    private fun loadHistorySnapshot() {
        viewModelScope.launch {
            try {
                historySnapshot.value = api.queue(includeServed = true)
                error.value = null
            } catch (e: ApiException) {
                error.value = e.message ?: "Could not reach the server."
            }
        }
    }

    fun dismissError() { error.value = null }

    fun dismissNotice() { notice.value = null }

    fun updateAccess(next: KitchenAccess) {
        access = next
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canAdvanceTickets) {
        error.value = VIEW_ONLY_MESSAGE
    }

    fun retryRejectedAdvance(localId: String) {
        if (!requireWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var changed = 0
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        changed = db.kitchenDao().retryRejectedAdvance(localId)
                    }
                ) return@launch
                notice.value = if (changed == 0) {
                    "That kitchen update already changed. The recovery list is now current."
                } else null
                if (changed == 1) syncActions.advancesQueued()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "The kitchen update could not be queued again on this tablet. Try again."
            }
        }
    }

    /**
     * Called only after the screen's explicit confirmation. Deletion is
     * guarded to a still-rejected row, so a stale dialog cannot remove work
     * that a concurrent retry already made pending.
     */
    fun discardRejectedAdvance(localId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var changed = 0
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        changed = db.kitchenDao().discardRejectedAdvance(localId)
                    }
                ) return@launch
                notice.value = if (changed == 1) {
                    "Saved kitchen update removed. The board is reloading from server truth."
                } else {
                    "That kitchen update already changed, so nothing was removed."
                }
                if (changed == 1) refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "The rejected kitchen update could not be removed from this tablet. Try again."
            }
        }
    }

    fun acknowledgeCancellation(orderId: String, lineId: String) {
        if (!requireWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var inserted = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        inserted = db.kitchenDao().insertCancellationAck(
                            LocalKitchenCancellationAckEntity(
                                localId = UUID.randomUUID().toString(),
                                orderId = orderId,
                                lineId = lineId,
                                requestedAtMillis = System.currentTimeMillis(),
                            ),
                        ) != -1L
                    }
                ) return@launch
                notice.value = if (inserted) {
                    "Cancellation acknowledgement saved. It remains highlighted until the server confirms it."
                } else {
                    "This cancellation acknowledgement is already saved."
                }
                if (inserted) syncActions.advancesQueued()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.shiftClosingMessageOr(
                    "The cancellation acknowledgement was not saved. Check tablet storage and try again.",
                )
            }
        }
    }

    fun retryRejectedCancellationAck(localId: String) {
        if (!requireWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var changed = 0
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        changed = db.kitchenDao().retryCancellationAck(localId)
                    }
                ) return@launch
                notice.value = if (changed == 1) {
                    "The original cancellation acknowledgement is queued again."
                } else {
                    "That acknowledgement already changed state. Refresh KDS and review it."
                }
                if (changed == 1) syncActions.advancesQueued()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "The cancellation acknowledgement could not be queued again. It remains saved for recovery."
            }
        }
    }

    /**
     * Moves one ticket one rung up the ladder. Captured to the outbox and
     * applied optimistically rather than awaited — same reasoning as the
     * comment that used to live here: a gloved tap that appears to do
     * nothing for five seconds gets tapped again, and the server-enforced
     * ladder (no skip, no backwards step) is what makes a stale local view
     * safe to act on regardless.
     */
    fun advance(order: KitchenOrder) {
        if (!requireWrite()) return
        val current = KitchenState.from(order.kitchenState) ?: return
        val next = current.next ?: return
        val now = System.currentTimeMillis()
        if (busyOrderId.value != null || now < advanceLockedUntilMillis.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return

        busyOrderId.value = order.id
        error.value = null
        viewModelScope.launch {
            try {
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.kitchenDao().insertAdvance(
                            LocalKitchenAdvanceEntity(
                                localId = UUID.randomUUID().toString(),
                                orderId = order.id,
                                targetState = next.wire,
                                requestedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                ) return@launch
                advanceLockedUntilMillis.value = System.currentTimeMillis() + ADVANCE_LOCK_MS
                syncActions.advancesQueued()
                if (includeServed.value) loadHistorySnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "The kitchen update was not saved on this tablet. " +
                    "The ticket has not moved; check storage and try again."
            } finally {
                busyOrderId.value = null
            }
        }
    }
}

private data class KitchenStatus(
    val error: String?,
    val refreshError: String?,
    val notice: String?,
    val nowMillis: Long,
    val lastSyncedAtMillis: Long?,
)

/**
 * The cache holds only what the last pull returned; a locally-advanced
 * ticket overrides its state until that pull lands. Served tickets normally
 * drop off the active board, but one carrying an unacknowledged cancellation
 * must remain until Kitchen explicitly acknowledges it.
 */
internal fun mergeCacheWithPending(
    cache: List<KitchenOrderCacheEntity>,
    pending: List<LocalKitchenAdvanceEntity>,
): List<KitchenOrder> {
    val latestByOrder = pending.groupBy { it.orderId }
        .mapValues { (_, rows) -> rows.maxBy { it.requestedAtMillis } }
    return cache.mapNotNull { row ->
        val effectiveState = latestByOrder[row.id]?.targetState ?: row.kitchenState
        if (
            KitchenState.from(effectiveState) == KitchenState.SERVED &&
            row.pendingCancellations.isEmpty()
        ) {
            return@mapNotNull null
        }
        KitchenOrder(
            id = row.id,
            invoiceNo = row.invoiceNo,
            type = row.type,
            tableCode = row.tableCode,
            customerName = row.customerName,
            openedAt = row.openedAt,
            kitchenState = effectiveState,
            minutesWaiting = row.minutesWaiting,
            lines = row.lines,
            pendingCancellations = row.pendingCancellations,
        )
    }
}

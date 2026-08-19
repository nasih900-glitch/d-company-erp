package cloud.dcompany.erp.ui.screens.kitchen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.KitchenOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
    val orders: List<KitchenOrder> = emptyList(),
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

    val newCount: Int get() = lane(KitchenState.RECEIVED).size
    val preparingCount: Int get() = lane(KitchenState.PREPARING).size
    val readyCount: Int get() = lane(KitchenState.READY).size

    val tapsLocked: Boolean get() = busyOrderId != null || nowMillis < advanceLockedUntilMillis

    /** Seconds since the last successful read, or null before the first one. */
    val secondsSinceSync: Long?
        get() = lastSyncedAtMillis?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) }

    /**
     * A KDS that quietly stops updating is dangerous — the cook reads a frozen
     * screen as "no new orders". Two missed polls is enough to say so out loud.
     */
    val stale: Boolean get() = (secondsSinceSync ?: 0) > (KITCHEN_POLL_MS / 1000) * 3
}

/**
 * Room-backed for the live board (`includeServed = false`) — an advance is
 * captured to an outbox and applied optimistically, so a gloved tap keeps
 * working through a dropped link the same way POS billing does. Naturally
 * idempotent server-side (see KitchenState's doc comment), so unlike every
 * other outbox in this app there's no idempotency key to generate and
 * nothing to resolve at push time — just resend the target state.
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
    /** Non-null only while includeServed = true — bypasses Room entirely. */
    private val historySnapshot = MutableStateFlow<List<KitchenOrder>?>(null)

    val state: StateFlow<KitchenUiState> = combine(
        db.kitchenDao().observeOrderCache(),
        db.kitchenDao().observePendingAdvances(),
        historySnapshot,
        combine(includeServed, busyOrderId, advanceLockedUntilMillis, ::Triple),
        combine(error, nowMillis, appCtx.db.syncMetaDao().observe("kitchen"), ::Triple),
    ) { cache, pending, history, uiA, uiB ->
        val (includeServedNow, busy, lockedUntil) = uiA
        val (err, now, meta) = uiB
        KitchenUiState(
            error = err,
            orders = history ?: mergeCacheWithPending(cache, pending),
            includeServed = includeServedNow,
            busyOrderId = busy,
            advanceLockedUntilMillis = lockedUntil,
            everSynced = meta != null,
            lastSyncedAtMillis = meta?.lastSyncMillis,
            nowMillis = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KitchenUiState())

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                nowMillis.value = System.currentTimeMillis()
            }
        }
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(KITCHEN_POLL_MS)
                if (!includeServed.value) {
                    appCtx.sync.requestSync()
                    appCtx.sync.refresh("kitchen")
                }
            }
        }
    }

    /** Re-reads the queue — realtime push already keeps Room fresh; this is the manual/poll path. */
    fun refresh() {
        if (includeServed.value) {
            loadHistorySnapshot()
        } else {
            appCtx.sync.requestSync()
            viewModelScope.launch { appCtx.sync.refresh("kitchen") }
        }
    }

    /** Manual retry from the error state: clears the message first so a repeat failure still reads as new. */
    fun retry() {
        error.value = null
        refresh()
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

    /**
     * Moves one ticket one rung up the ladder. Captured to the outbox and
     * applied optimistically rather than awaited — same reasoning as the
     * comment that used to live here: a gloved tap that appears to do
     * nothing for five seconds gets tapped again, and the server-enforced
     * ladder (no skip, no backwards step) is what makes a stale local view
     * safe to act on regardless.
     */
    fun advance(order: KitchenOrder) {
        val current = KitchenState.from(order.kitchenState) ?: return
        val next = current.next ?: return
        val now = System.currentTimeMillis()
        if (busyOrderId.value != null || now < advanceLockedUntilMillis.value) return

        busyOrderId.value = order.id
        error.value = null
        viewModelScope.launch {
            db.kitchenDao().insertAdvance(
                LocalKitchenAdvanceEntity(
                    localId = UUID.randomUUID().toString(),
                    orderId = order.id,
                    targetState = next.wire,
                    requestedAtMillis = System.currentTimeMillis(),
                ),
            )
            busyOrderId.value = null
            advanceLockedUntilMillis.value = System.currentTimeMillis() + ADVANCE_LOCK_MS
            appCtx.sync.requestSync()
            if (includeServed.value) loadHistorySnapshot()
        }
    }
}

/**
 * The cache holds only what the last pull returned; a locally-advanced
 * ticket overrides its state until that pull lands, and a locally-advanced
 * *served* ticket drops off the active board entirely, matching what the
 * next queue read will return.
 */
private fun mergeCacheWithPending(
    cache: List<KitchenOrderCacheEntity>,
    pending: List<LocalKitchenAdvanceEntity>,
): List<KitchenOrder> {
    val latestByOrder = pending.groupBy { it.orderId }
        .mapValues { (_, rows) -> rows.maxBy { it.requestedAtMillis } }
    return cache.mapNotNull { row ->
        val effectiveState = latestByOrder[row.id]?.targetState ?: row.kitchenState
        if (KitchenState.from(effectiveState) == KitchenState.SERVED) return@mapNotNull null
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
        )
    }
}

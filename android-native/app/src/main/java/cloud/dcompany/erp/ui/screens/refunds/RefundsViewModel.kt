package cloud.dcompany.erp.ui.screens.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.LocalRefundEntity
import cloud.dcompany.erp.core.db.RefundOrderCacheEntity
import cloud.dcompany.erp.core.net.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** The reasons an owner actually gives; free text goes in the note. */
val REFUND_REASONS = listOf(
    "customer_unhappy" to "Customer unhappy",
    "wrong_item" to "Wrong item",
    "order_cancelled" to "Order cancelled",
    "billing_error" to "Billing error",
    "other" to "Other",
)

/** A refund the server definitively refused — the cash is already gone, someone must reconcile it. */
data class RejectedRefund(val invoiceNo: String?, val amountMinor: Long, val reason: String)

data class RefundsUiState(
    val orders: List<Order> = emptyList(),
    val query: String = "",
    val selected: Order? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    /** A refundable-orders pull has completed at least once on this device. */
    val everSynced: Boolean = false,
    val rejected: List<RejectedRefund> = emptyList(),
) {
    val visible: List<Order>
        get() {
            val q = query.trim().lowercase()
            return if (q.isEmpty()) orders
            else orders.filter { (it.invoiceNo ?: "").lowercase().contains(q) }
        }
}

/**
 * Room-backed like Gaming/Tables — the paid-order list is a shared,
 * wholesale-replaced cache (any terminal can refund any paid order, see
 * [RefundOrderCacheEntity]), and a refund captured on this tablet is queued
 * in [LocalRefundEntity] until it syncs. Unlike Shift/Gaming there is no
 * local "create" leg to wait on: an order id is only ever one this app has
 * already pulled from the server, so a refund is always ready to push the
 * moment it's captured (see SyncEngine.pushRefunds).
 *
 * Offline scope here was a deliberate call, not an oversight: this café runs
 * one active cashier/terminal at a time, so the double-refund risk a naive
 * offline queue would normally create (two terminals both refunding the same
 * order past its paid balance before either sees the other) cannot happen in
 * practice. The server is still the last word — the netting below only
 * prevents an already-doomed second attempt from being *entered*, using
 * `refundableMinor` (the server's own paid-minus-already-refunded figure,
 * see [RefundOrderCacheEntity]) further reduced by whatever this device
 * itself still has queued and unsynced.
 */
class RefundsViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db

    private val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<RefundsUiState> = combine(
        db.refundDao().observeRefundableOrders(),
        db.refundDao().observePendingLocalRefunds(),
        combine(query, selectedId, ::Pair),
        combine(busy, notice, ::Pair),
        combine(db.syncMetaDao().observe("orders"), db.refundDao().observeRejectedRefunds(), ::Pair),
    ) { cache, pending, qs, ui, metaAndRejected ->
        val (q, selId) = qs
        val (isBusy, noticeMsg) = ui
        val (meta, rejectedRows) = metaAndRejected
        // Multiple partial refunds against the same order can be queued
        // offline before any of them sync — sum them all, not just the
        // latest, so the shown balance reflects everything already promised.
        val pendingByOrder = pending.groupBy { it.orderId }
            .mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
        val orders = cache.map { it.toOrder(pendingByOrder[it.id] ?: 0L) }
        RefundsUiState(
            orders = orders,
            query = q,
            selected = orders.firstOrNull { it.id == selId },
            busy = isBusy,
            notice = noticeMsg,
            everSynced = meta != null,
            rejected = rejectedRows.map {
                RejectedRefund(it.invoiceNo, it.amountMinor, it.lastError ?: "Server refused this.")
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RefundsUiState())

    init {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("orders") }
    }

    fun load() {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("orders") }
    }

    fun search(q: String) { query.value = q }

    fun select(order: Order?) { selectedId.value = order?.id }

    fun dismissNotice() { notice.value = null }

    /**
     * Captures the refund locally and returns immediately — same guarantee
     * every other offline write in this app has. "Cannot be undone from this
     * app" (the confirmation dialog's own words) stays true either way; what
     * changes offline is only when the money actually moves through the
     * server's books, not whether the cashier's action here is final.
     */
    fun refund(order: Order, amountMinor: Long, reasonCode: String, note: String?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            db.refundDao().insertLocalRefund(
                LocalRefundEntity(
                    localId = UUID.randomUUID().toString(),
                    orderId = order.id,
                    invoiceNo = order.invoiceNo,
                    reasonCode = reasonCode,
                    amountMinor = amountMinor,
                    // Backend RefundCreate.note caps at 500 chars — clamp here
                    // too so an over-length note fails at capture, not as a
                    // silent rejection discovered only via the banner below.
                    note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(500),
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
            busy.value = false
            selectedId.value = null
            notice.value = if (appCtx.connectivity.online.value) {
                "Refund queued — sending now."
            } else {
                "Refund saved on this tablet. It will go through as soon as the connection returns."
            }
            appCtx.sync.requestSync()
        }
    }
}

private fun RefundOrderCacheEntity.toOrder(pendingRefundedMinor: Long): Order = Order(
    id = id,
    invoiceNo = invoiceNo,
    status = status,
    type = type,
    totalMinor = totalMinor,
    paidMinor = paidMinor,
    refundableMinor = (refundableMinor - pendingRefundedMinor).coerceAtLeast(0),
)

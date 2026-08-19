package cloud.dcompany.erp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.LocalOrderLineEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class CartLine(val item: MenuItemEntity, val qty: Int)

data class PosUiState(
    val categories: List<MenuCategoryEntity> = emptyList(),
    val items: List<MenuItemEntity> = emptyList(),
    val selectedCategoryId: String? = null,
    val cart: List<CartLine> = emptyList(),
    val online: Boolean = false,
    val pendingCount: Int = 0,
    val rejectedCount: Int = 0,
    val syncing: Boolean = false,
    val notice: String? = null,
    val menuEmpty: Boolean = false,
    /** A sync has completed at least once on this device. */
    val everSynced: Boolean = false,
    /**
     * `serverShiftId` once the shift's open leg has synced, its `localId`
     * while still offline-pending — either way, what an order should be
     * captured against. Null once a close has been requested, even before
     * that close reaches the server: billing must stop the instant staff
     * say "close", not whenever the network happens to confirm it.
     */
    val activeShiftId: String? = null,
) {
    val visibleItems: List<MenuItemEntity>
        get() = if (selectedCategoryId == null) items
            else items.filter { it.categoryId == selectedCategoryId }

    /**
     * Cart-side estimate only. The server does the canonical pricing — tax,
     * membership discounts, rounding — and that figure is what actually gets
     * charged once the sale syncs.
     */
    val estimateMinor: Long get() = cart.sumOf { it.item.basePriceMinor * it.qty }
    val cartCount: Int get() = cart.sumOf { it.qty }
}

class PosViewModel : ViewModel() {

    private val app = DCompanyApp.instance
    private val db = app.db

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val cart = MutableStateFlow<List<CartLine>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)

    /**
     * The UI reads Room, never the network. That is what makes the screen work
     * offline: a dropped link changes the banner, not the till. This includes
     * the shift — ShiftViewModel writes the same `local_shifts` table this
     * reads, so a shift opened offline is immediately billable here too,
     * with no live "am I on a shift" round trip of its own.
     */
    val state: StateFlow<PosUiState> = combine(
        combine(db.menuDao().observeItems(), db.menuDao().observeCategories(), ::Pair),
        combine(selectedCategory, cart, notice, ::Triple),
        combine(app.connectivity.online, app.sync.syncing, db.shiftDao().observeCurrent(), ::Triple),
        combine(app.sync.pendingCount, app.sync.rejectedCount, ::Pair),
        db.syncMetaDao().observe("menu"),
    ) { menu, ui, net, queue, meta ->
        val billableShift = net.third?.takeIf { it.state != ShiftState.CLOSE_PENDING }
        PosUiState(
            items = menu.first,
            categories = menu.second,
            selectedCategoryId = ui.first,
            cart = ui.second,
            notice = ui.third,
            online = net.first,
            syncing = net.second,
            activeShiftId = billableShift?.let { it.serverShiftId ?: it.localId },
            pendingCount = queue.first,
            rejectedCount = queue.second,
            menuEmpty = menu.first.isEmpty(),
            everSynced = meta != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PosUiState())

    init {
        app.sync.requestSync()
    }

    fun refresh() = app.sync.requestSync()

    fun selectCategory(id: String?) { selectedCategory.value = id }

    fun add(item: MenuItemEntity) = cart.update { lines ->
        val i = lines.indexOfFirst { it.item.id == item.id }
        if (i >= 0) lines.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + 1) }
        else lines + CartLine(item, 1)
    }

    fun remove(item: MenuItemEntity) = cart.update { lines ->
        val i = lines.indexOfFirst { it.item.id == item.id }
        if (i < 0) return@update lines
        val line = lines[i]
        if (line.qty <= 1) lines.filterIndexed { idx, _ -> idx != i }
        else lines.toMutableList().also { it[i] = line.copy(qty = line.qty - 1) }
    }

    fun clearCart() { cart.value = emptyList() }

    fun dismissNotice() { notice.value = null }

    /**
     * Captures the sale locally and returns immediately — the cashier is never
     * made to wait on the network to hand back change.
     *
     * Offline this produces a *provisional* receipt with no invoice number.
     * The GST tax-invoice number comes from one atomic per-branch counter in
     * Postgres; a tablet minting its own would eventually issue the same
     * number as another terminal, which is a real compliance problem, not a
     * cosmetic one. The number is therefore filled in when the sale syncs.
     */
    fun captureSale(method: String, tenderedMinor: Long) {
        val shift = state.value.activeShiftId
        if (shift == null) {
            notice.value = "No open shift on this terminal. Open a shift before billing."
            return
        }
        val lines = cart.value
        if (lines.isEmpty()) return

        val localId = UUID.randomUUID().toString()
        val order = LocalOrderEntity(
            localId = localId,
            shiftId = shift,
            type = "dine_in",
            estimateMinor = lines.sumOf { it.item.basePriceMinor * it.qty },
            paymentMethod = method,
            tenderedMinor = tenderedMinor,
            createdAtMillis = System.currentTimeMillis(),
            syncState = SyncState.PENDING,
        )
        val rows = lines.map {
            LocalOrderLineEntity(
                orderLocalId = localId,
                menuItemId = it.item.id,
                name = it.item.name,
                qty = it.qty,
                unitPriceMinor = it.item.basePriceMinor,
            )
        }
        viewModelScope.launch {
            db.orderDao().capture(order, rows)
            cart.value = emptyList()
            notice.value = if (app.connectivity.online.value) {
                "Sale recorded. Syncing now."
            } else {
                "Sale saved on this tablet. It will be sent, and the tax invoice " +
                    "number issued, as soon as the connection returns."
            }
            app.sync.requestSync()
        }
    }
}

/** Small helper so cart edits read as one expression. */
private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
    value = block(value)
}

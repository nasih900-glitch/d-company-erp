package cloud.dcompany.erp.ui.screens.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class TableCartLine(val item: MenuItemEntity, val qty: Int)

data class TablesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val floors: List<Floor> = emptyList(),
    val tables: List<CafeTable> = emptyList(),
    val menu: List<MenuItemEntity> = emptyList(),
    val selectedFloorId: String? = null,
    /** The table an order is currently being built for. */
    val openTable: CafeTable? = null,
    val cart: List<TableCartLine> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val visibleTables: List<CafeTable>
        get() = if (selectedFloorId == null) tables
            else tables.filter { it.floorId == selectedFloorId }

    val estimateMinor: Long get() = cart.sumOf { it.item.basePriceMinor * it.qty }
}

class TablesViewModel : ViewModel() {

    private val api = ApiClient.create<TablesApi>()
    private val app = DCompanyApp.instance

    private val local = MutableStateFlow(TablesUiState())

    /**
     * The menu comes from Room, not the network, so a table order can still be
     * built on a dropped link — same rule as the POS screen.
     */
    val state: StateFlow<TablesUiState> = combine(
        local,
        app.db.menuDao().observeItems(),
    ) { s, menu -> s.copy(menu = menu) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TablesUiState())

    init { load() }

    fun load() {
        local.value = local.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val floors = api.floors()
                val tables = api.tables()
                local.value = local.value.copy(loading = false, floors = floors, tables = tables)
            } catch (e: ApiException) {
                local.value = local.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load tables.",
                )
            }
        }
    }

    fun selectFloor(id: String?) { local.value = local.value.copy(selectedFloorId = id) }

    fun openTable(table: CafeTable) {
        local.value = local.value.copy(openTable = table, cart = emptyList())
    }

    fun closeTable() { local.value = local.value.copy(openTable = null, cart = emptyList()) }

    fun add(item: MenuItemEntity) = mutate { cart ->
        val i = cart.indexOfFirst { it.item.id == item.id }
        if (i >= 0) cart.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + 1) }
        else cart + TableCartLine(item, 1)
    }

    fun remove(item: MenuItemEntity) = mutate { cart ->
        val i = cart.indexOfFirst { it.item.id == item.id }
        if (i < 0) return@mutate cart
        val line = cart[i]
        if (line.qty <= 1) cart.filterIndexed { idx, _ -> idx != i }
        else cart.toMutableList().also { it[i] = line.copy(qty = line.qty - 1) }
    }

    fun dismissNotice() { local.value = local.value.copy(notice = null) }

    /**
     * Sends the table's order to the POS queue so it can be billed at the till.
     *
     * Unlike the POS screen this is deliberately NOT captured offline: a table
     * order has to become visible to whoever is on the till right now, and a
     * bill sitting invisibly in a local outbox is worse than being told to
     * retry. It needs the network, and says so.
     */
    fun sendToPos() {
        val table = local.value.openTable ?: return
        val lines = local.value.cart
        if (lines.isEmpty()) return
        val shift = app.shiftCache
        local.value = local.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val shiftId = shift.cachedShiftId()
            if (shiftId == null) {
                local.value = local.value.copy(
                    busy = false,
                    notice = "No open shift. Open a shift before sending an order.",
                )
                return@launch
            }
            try {
                val key = UUID.randomUUID().toString()
                val order = api.createOrder(
                    CreateOrderRequest(
                        type = "dine_in",
                        shiftId = shiftId,
                        lines = lines.map { OrderLineRequest(it.item.id, it.qty) },
                    ),
                    key,
                )
                api.sendToPos(order.id)
                local.value = local.value.copy(
                    busy = false,
                    openTable = null,
                    cart = emptyList(),
                    notice = "Sent table ${table.code} to the POS queue.",
                )
                load()
            } catch (e: Exception) {
                local.value = local.value.copy(
                    busy = false,
                    notice = "Could not send this order: ${e.message}",
                )
            }
        }
    }

    private inline fun mutate(block: (List<TableCartLine>) -> List<TableCartLine>) {
        local.value = local.value.copy(cart = block(local.value.cart))
    }
}

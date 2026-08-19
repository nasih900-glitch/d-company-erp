package cloud.dcompany.erp.ui.screens.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.CafeTableEntity
import cloud.dcompany.erp.core.db.FloorEntity
import cloud.dcompany.erp.core.db.LocalTableOrderEntity
import cloud.dcompany.erp.core.db.LocalTableOrderLine
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.TableOrderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class TableCartLine(val item: MenuItemEntity, val qty: Int)

data class TablesUiState(
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
    val everSynced: Boolean = false,
) {
    val visibleTables: List<CafeTable>
        get() = if (selectedFloorId == null) tables
            else tables.filter { it.floorId == selectedFloorId }

    val estimateMinor: Long get() = cart.sumOf { it.item.basePriceMinor * it.qty }
}

/**
 * Room-backed — floors/tables/menu read from Room like every other screen,
 * so the floor plan is still browsable on a dropped link. "Send to POS" now
 * captures to an outbox instead of calling the network directly: the table
 * order (create, then send) is queued as one unit and drained by
 * SyncEngine.pushTableOrders whenever a connection exists, following a
 * shift opened offline the same way a POS sale already does.
 *
 * This reverses an earlier, deliberate decision documented right here in a
 * previous version of this file: "a table order has to become visible to
 * whoever is on the till right now... it needs the network, and says so."
 * That's still true in spirit — a queued table order is genuinely invisible
 * to Kitchen/POS on other terminals until it syncs — but the same tradeoff
 * is already accepted for POS sales and gaming sessions, and Kitchen is
 * itself going Room-backed in this same phase, so the delay is bounded by
 * the same sync loop, not indefinite.
 */
class TablesViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db

    private val selectedFloorId = MutableStateFlow<String?>(null)
    private val openTableId = MutableStateFlow<String?>(null)
    private val cart = MutableStateFlow<List<TableCartLine>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<TablesUiState> = combine(
        combine(db.tablesDao().observeFloors(), db.tablesDao().observeTables(), ::Pair),
        db.menuDao().observeItems(),
        combine(selectedFloorId, openTableId, cart, ::Triple),
        combine(busy, notice, error, ::Triple),
        db.syncMetaDao().observe("tables"),
    ) { floorsAndTables, menu, uiA, uiB, meta ->
        val (floorRows, tableRows) = floorsAndTables
        val (selectedFloor, openId, cartLines) = uiA
        val (isBusy, noticeText, errorText) = uiB
        val tables = tableRows.map { it.toCafeTable() }
        TablesUiState(
            error = errorText,
            floors = floorRows.map { it.toFloor() },
            tables = tables,
            menu = menu,
            selectedFloorId = selectedFloor,
            openTable = tables.firstOrNull { it.id == openId },
            cart = cartLines,
            busy = isBusy,
            notice = noticeText,
            everSynced = meta != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TablesUiState())

    init {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("tables") }
    }

    fun load() {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("tables") }
    }

    fun selectFloor(id: String?) { selectedFloorId.value = id }

    fun openTable(table: CafeTable) {
        openTableId.value = table.id
        cart.value = emptyList()
    }

    fun closeTable() {
        openTableId.value = null
        cart.value = emptyList()
    }

    fun add(item: MenuItemEntity) = mutate { lines ->
        val i = lines.indexOfFirst { it.item.id == item.id }
        if (i >= 0) lines.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + 1) }
        else lines + TableCartLine(item, 1)
    }

    fun remove(item: MenuItemEntity) = mutate { lines ->
        val i = lines.indexOfFirst { it.item.id == item.id }
        if (i < 0) return@mutate lines
        val line = lines[i]
        if (line.qty <= 1) lines.filterIndexed { idx, _ -> idx != i }
        else lines.toMutableList().also { it[i] = line.copy(qty = line.qty - 1) }
    }

    fun dismissNotice() { notice.value = null }

    /**
     * Captures the table's order locally and returns immediately — same
     * guarantee POS billing already has. `sendToPos` still means "make it
     * visible on the till", it just no longer requires being online at the
     * exact moment a waiter finishes building the order to do that.
     */
    fun sendToPos() {
        val table = state.value.openTable ?: return
        val lines = cart.value
        if (lines.isEmpty()) return
        val shift = appCtx.shiftCache
        busy.value = true
        error.value = null
        viewModelScope.launch {
            val shiftId = shift.cachedShiftId()
            if (shiftId == null) {
                busy.value = false
                notice.value = "No open shift. Open a shift before sending an order."
                return@launch
            }
            db.tablesDao().insertLocalOrder(
                LocalTableOrderEntity(
                    localId = UUID.randomUUID().toString(),
                    tableId = table.id,
                    tableCode = table.code,
                    shiftId = shiftId,
                    lines = lines.map { LocalTableOrderLine(it.item.id, it.qty) },
                    createdAtMillis = System.currentTimeMillis(),
                    state = TableOrderState.PENDING,
                ),
            )
            busy.value = false
            openTableId.value = null
            cart.value = emptyList()
            notice.value = if (appCtx.connectivity.online.value) {
                "Sent table ${table.code} to the POS queue."
            } else {
                "Table ${table.code} saved on this tablet. It will reach the POS queue " +
                    "as soon as the connection returns."
            }
            appCtx.sync.requestSync()
        }
    }

    private inline fun mutate(block: (List<TableCartLine>) -> List<TableCartLine>) {
        cart.value = block(cart.value)
    }
}

private fun FloorEntity.toFloor() = Floor(id = id, branchId = branchId, name = name)

private fun CafeTableEntity.toCafeTable() = CafeTable(
    id = id,
    floorId = floorId,
    code = code,
    seats = seats,
    shape = shape,
    x = x,
    y = y,
    status = status,
)

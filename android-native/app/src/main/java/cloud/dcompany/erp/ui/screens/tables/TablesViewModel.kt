package cloud.dcompany.erp.ui.screens.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.ScopedCommitResult
import cloud.dcompany.erp.core.auth.TablesAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.CafeActionKind
import cloud.dcompany.erp.core.db.CafeActionLine
import cloud.dcompany.erp.core.db.CafeActionPayload
import cloud.dcompany.erp.core.db.CafeActionState
import cloud.dcompany.erp.core.db.CafeTableEntity
import cloud.dcompany.erp.core.db.FloorEntity
import cloud.dcompany.erp.core.db.LocalCafeActionEntity
import cloud.dcompany.erp.core.db.LocalCafeBillEntity
import cloud.dcompany.erp.core.db.LocalTableOrderEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.TableOrderState
import cloud.dcompany.erp.core.db.observeResolvedOpenShift
import cloud.dcompany.erp.core.sync.CafeBillLineProjection
import cloud.dcompany.erp.core.sync.CafeBillProjection
import cloud.dcompany.erp.core.sync.projectCafeBills
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class TableCartLine(
    val item: MenuItemEntity,
    val qty: Int,
    val note: String = "",
    val clientLineId: String = UUID.randomUUID().toString(),
)

data class BlockedCafeAction(
    val actionId: String,
    val tableCode: String,
    val actionLabel: String,
    val message: String,
    val conflict: Boolean,
    val createdAtMillis: Long,
)

data class TablesUiState(
    val error: String? = null,
    /** Tables-cache refresh failure, separate from local action feedback. */
    val refreshError: String? = null,
    val floors: List<Floor> = emptyList(),
    val tables: List<CafeTable> = emptyList(),
    val bills: List<CafeBillProjection> = emptyList(),
    val menu: List<MenuItemEntity> = emptyList(),
    val selectedFloorId: String? = null,
    val selectedTable: CafeTable? = null,
    /** Pre-v24 screen compatibility; removed with the v24 Compose rewrite. */
    val openTable: CafeTable? = null,
    val selectedBill: CafeBillProjection? = null,
    val draftingRound: Boolean = false,
    val cart: List<TableCartLine> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val everSynced: Boolean = false,
    val activeShiftId: String? = null,
    val blockedActions: List<BlockedCafeAction> = emptyList(),
    val rejectedOrders: List<RejectedTableOrder> = emptyList(),
    val retryingRejectedOrderIds: Set<String> = emptySet(),
    val online: Boolean = false,
) {
    val visibleTables: List<CafeTable>
        get() = if (selectedFloorId == null) tables else tables.filter { it.floorId == selectedFloorId }

    val estimateMinor: Long get() = cart.sumOf { it.item.basePriceMinor * it.qty }

    val blockingLoadError: String? get() = error ?: refreshError
}

private data class TableCacheSnapshot(
    val floors: List<FloorEntity>,
    val tables: List<CafeTableEntity>,
    val bills: List<CafeBillProjection>,
    val actions: List<LocalCafeActionEntity>,
)

private data class SelectionSnapshot(
    val floorId: String?,
    val tableId: String?,
    val drafting: Boolean,
    val cart: List<TableCartLine>,
)

private data class RuntimeSnapshot(
    val busy: Boolean,
    val notice: String?,
    val error: String?,
    val refreshError: String?,
    val shiftId: String?,
    val online: Boolean,
)

private data class TablesErrors(
    val local: String?,
    val refresh: String?,
)

/**
 * Persistent service-round workflow. A round is released to KDS first; Send
 * to POS is a later, separate freeze when the guest asks for the bill.
 */
class TablesViewModel : ViewModel() {

    private val app = DCompanyApp.instance
    private val db = app.db
    private val cafeDao = db.cafeOrderDao()
    private val resolvedShift = db.shiftDao().observeResolvedOpenShift(app.terminalStore.terminalIdFlow)

    private val selectedFloorId = MutableStateFlow<String?>(null)
    private val selectedTableId = MutableStateFlow<String?>(null)
    private val draftingRound = MutableStateFlow(false)
    private val cart = MutableStateFlow<List<TableCartLine>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    @Volatile private var access = TablesAccess()

    private val tableErrors = combine(error, app.sync.resourceRefreshErrors) {
            localError, refreshErrors ->
        TablesErrors(localError, refreshErrors["tables"])
    }

    private val cacheSnapshot = combine(
        db.tablesDao().observeFloors(),
        db.tablesDao().observeTables(),
        cafeDao.observeActiveBills(),
        cafeDao.observeLocalBills(),
        cafeDao.observeActions(),
    ) { floors, tables, serverBills, localBills, actions ->
        TableCacheSnapshot(
            floors = floors,
            tables = tables,
            bills = projectCafeBills(serverBills, localBills, actions),
            actions = actions,
        )
    }

    val state: StateFlow<TablesUiState> = combine(
        cacheSnapshot,
        db.menuDao().observeItems(),
        combine(selectedFloorId, selectedTableId, draftingRound, cart) {
                floorId, tableId, drafting, lines ->
            SelectionSnapshot(floorId, tableId, drafting, lines)
        },
        combine(busy, notice, tableErrors, resolvedShift, app.connectivity.online) {
                isBusy, noticeText, errors, shift, isOnline ->
            RuntimeSnapshot(
                busy = isBusy,
                notice = noticeText,
                error = errors.local,
                refreshError = errors.refresh,
                shiftId = shift?.shiftId,
                online = isOnline,
            )
        },
        db.syncMetaDao().observe("tables"),
    ) { cache, menu, selection, runtime, meta ->
        val billsByTable = cache.bills.associateBy { it.tableId }
        val tableRows = cache.tables.map { row ->
            val bill = billsByTable[row.id]
            row.toCafeTable(
                statusOverride = when {
                    bill == null -> null
                    bill.blockedActionId != null -> "needs attention"
                    bill.status == "sending_to_pos" -> "sending to pos"
                    bill.status == "held" -> "at pos"
                    bill.pendingActionCount > 0 -> "round syncing"
                    else -> "open bill"
                },
            )
        }
        val selectedTable = tableRows.firstOrNull { it.id == selection.tableId }
        TablesUiState(
            error = runtime.error,
            refreshError = runtime.refreshError,
            floors = cache.floors.map(FloorEntity::toFloor),
            tables = tableRows,
            bills = cache.bills,
            menu = menu,
            selectedFloorId = selection.floorId,
            selectedTable = selectedTable,
            openTable = selectedTable,
            selectedBill = selectedTable?.let { billsByTable[it.id] },
            draftingRound = selection.drafting,
            cart = selection.cart,
            busy = runtime.busy,
            notice = runtime.notice,
            everSynced = meta != null,
            activeShiftId = runtime.shiftId,
            blockedActions = cache.actions
                .filter { it.state == CafeActionState.CONFLICT || it.state == CafeActionState.REJECTED }
                .map { action ->
                    val bill = cache.bills.firstOrNull { it.localBillId == action.localBillId }
                    val tableCode = bill?.tableCode
                        ?: tableRows.firstOrNull { it.id == bill?.tableId }?.code
                        ?: "Unknown"
                    BlockedCafeAction(
                        actionId = action.actionId,
                        tableCode = tableCode,
                        actionLabel = cafeActionLabel(action.kind),
                        message = action.lastError?.takeIf(String::isNotBlank)
                            ?: "The server refused this action without an explanation.",
                        conflict = action.state == CafeActionState.CONFLICT,
                        createdAtMillis = action.createdAtMillis,
                    )
                },
            online = runtime.online,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TablesUiState())

    init {
        app.sync.requestSync()
        viewModelScope.launch { app.sync.refresh("tables") }
    }

    fun load() {
        app.sync.requestSync()
        viewModelScope.launch { app.sync.refresh("tables") }
    }

    fun selectFloor(id: String?) { selectedFloorId.value = id }

    fun updateAccess(next: TablesAccess) {
        access = next
    }

    private fun requireCreate(): Boolean = authorizeAction(access.canCreateOrders) {
        notice.value = VIEW_ONLY_MESSAGE
    }

    fun openTable(table: CafeTable) {
        if (busy.value) return
        selectedTableId.value = table.id
        val bill = state.value.bills.firstOrNull { it.tableId == table.id }
        val tableIsAvailable = table.status.equals("available", ignoreCase = true)
        draftingRound.value = bill == null && tableIsAvailable && access.canCreateOrders
        cart.value = emptyList()
        when {
            bill?.heldOrSending == true -> {
            notice.value = if (bill.status == "held") {
                "Table ${table.code}'s bill is locked in POS. Select it there to take payment."
            } else {
                "Table ${table.code}'s Send to POS action is saved and still syncing. Do not send it again."
            }
            }
            bill == null && !tableIsAvailable -> {
                notice.value = "Table ${table.code} is marked ${table.status}, but its active bill is not " +
                    "available on this tablet. Refresh Tables. If it remains missing, do not create a " +
                    "replacement order; ask a manager to check the table in POS."
                selectedTableId.value = null
            }
            bill == null && !access.canCreateOrders -> {
                notice.value = VIEW_ONLY_MESSAGE
                selectedTableId.value = null
            }
        }
    }

    fun closeTable() {
        if (busy.value) return
        selectedTableId.value = null
        draftingRound.value = false
        cart.value = emptyList()
    }

    fun startAnotherRound() {
        if (!requireCreate()) return
        val bill = state.value.selectedBill ?: return
        if (!bill.editable) {
            notice.value = "This bill cannot accept another round until its saved action is resolved."
            return
        }
        draftingRound.value = true
        cart.value = emptyList()
    }

    fun cancelDraft() {
        if (busy.value) return
        draftingRound.value = false
        cart.value = emptyList()
        if (state.value.selectedBill == null) selectedTableId.value = null
    }

    fun add(item: MenuItemEntity) {
        if (!requireCreate() || busy.value) return
        cart.update { lines ->
            val index = lines.indexOfFirst { it.item.id == item.id && it.note.isBlank() }
            if (index < 0) lines + TableCartLine(item = item, qty = 1)
            else lines.toMutableList().also { mutable ->
                mutable[index] = mutable[index].copy(qty = mutable[index].qty + 1)
            }
        }
    }

    fun remove(clientLineId: String) {
        if (!requireCreate() || busy.value) return
        cart.update { lines ->
            val index = lines.indexOfFirst { it.clientLineId == clientLineId }
            if (index < 0) lines
            else if (lines[index].qty <= 1) lines.filterIndexed { i, _ -> i != index }
            else lines.toMutableList().also { mutable ->
                mutable[index] = mutable[index].copy(qty = mutable[index].qty - 1)
            }
        }
    }

    /** Pre-v24 screen bridge. */
    fun remove(item: MenuItemEntity) {
        cart.value.firstOrNull { it.item.id == item.id }?.let { remove(it.clientLineId) }
    }

    fun increment(clientLineId: String) {
        if (!requireCreate() || busy.value) return
        cart.update { lines ->
            val index = lines.indexOfFirst { it.clientLineId == clientLineId }
            if (index < 0) lines else lines.toMutableList().also { mutable ->
                mutable[index] = mutable[index].copy(qty = mutable[index].qty + 1)
            }
        }
    }

    fun updateNote(clientLineId: String, value: String) {
        if (!requireCreate() || busy.value) return
        val safe = value.take(500)
        cart.update { lines ->
            lines.map { if (it.clientLineId == clientLineId) it.copy(note = safe) else it }
        }
    }

    /** Save one immutable service round; it is not a billing handoff. */
    fun saveRound() {
        if (!requireCreate() || busy.value) return
        val snapshot = state.value
        val table = snapshot.selectedTable ?: return
        val lines = snapshot.cart
        if (lines.isEmpty()) {
            notice.value = "Add at least one item before sending this round to Kitchen."
            return
        }
        val shiftId = snapshot.activeShiftId
        if (shiftId == null) {
            notice.value = "No usable shift is open on this terminal. Open a shift before taking a table order."
            return
        }
        val lease = app.cacheIsolation.currentLease() ?: run {
            notice.value = "This account's local workspace is not ready. Sign in online once, then try again."
            return
        }
        val selectedBill = snapshot.selectedBill
        val localBillId = selectedBill?.localBillId
            ?: selectedBill?.serverOrderId?.let { "server:$it" }
            ?: UUID.randomUUID().toString()
        val actionId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val bill = LocalCafeBillEntity(
            localBillId = localBillId,
            serverOrderId = selectedBill?.serverOrderId,
            tableId = table.id,
            tableCode = table.code,
            shiftId = shiftId,
            confirmedCheckoutVersion = selectedBill?.checkoutVersion,
            createdAtMillis = createdAt,
        )
        val action = LocalCafeActionEntity(
            actionId = actionId,
            localBillId = localBillId,
            kind = if (selectedBill == null) CafeActionKind.CREATE_ROUND else CafeActionKind.APPEND_ROUND,
            payload = CafeActionPayload(
                lines = lines.map { line ->
                    CafeActionLine(
                        clientLineId = line.clientLineId,
                        menuItemId = line.item.id,
                        name = line.item.name,
                        qty = line.qty,
                        note = line.note.trim().takeIf(String::isNotEmpty),
                        estimateUnitMinor = line.item.basePriceMinor,
                    )
                },
            ),
            capturedCheckoutVersion = selectedBill?.checkoutVersion,
            dedupeKey = "round:$localBillId:${lines.joinToString(":") { it.clientLineId }}",
            createdAtMillis = createdAt,
        )
        busy.value = true
        viewModelScope.launch {
            try {
                val result = app.cacheIsolation.commitResultIfCurrent(lease) {
                    if (selectedBill == null) cafeDao.captureNewBill(bill, action)
                    else cafeDao.captureAction(bill, action)
                }
                val inserted = (result as? ScopedCommitResult.Committed)?.value == true
                if (!inserted) {
                    notice.value = if (result is ScopedCommitResult.Stale) {
                        "The signed-in account or terminal changed. This round was not saved; review the items and try again."
                    } else {
                        "This table already has a saved bill or this round was already queued. Refresh before adding anything else."
                    }
                    return@launch
                }
                draftingRound.value = false
                cart.value = emptyList()
                notice.value = roundSavedNotice(table.code, app.connectivity.online.value)
                app.sync.requestSync()
            } catch (_: Exception) {
                notice.value = "The round was not saved. Your items are still on screen; check tablet storage and try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun requestLineCancellation(line: CafeBillLineProjection, reason: String) {
        if (!authorizeAction(access.canCancelItems) { notice.value = VIEW_ONLY_MESSAGE }) return
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) {
            notice.value = "Enter why this item is being cancelled. Kitchen and the audit history both need the reason."
            return
        }
        val billProjection = state.value.selectedBill ?: return
        if (!billProjection.editable || line.voided) {
            notice.value = "This item can no longer be cancelled from Tables. Refresh the bill and review its current state."
            return
        }
        if (billProjection.lines.count { !it.voided } <= 1) {
            notice.value = "This is the last active item. Ask an authorised cashier to void the whole bill instead."
            return
        }
        captureExistingBillAction(
            billProjection = billProjection,
            kind = CafeActionKind.VOID_LINE,
            payload = CafeActionPayload(
                targetClientLineId = line.clientLineId,
                targetServerLineId = line.serverLineId,
                reason = cleanReason.take(500),
            ),
            dedupeKey = "void:${billProjection.localBillId ?: billProjection.serverOrderId}:${line.stableKey}",
            successMessage = "Cancellation saved for ${line.name}. Kitchen will keep it visible until they acknowledge it.",
        )
    }

    fun sendSelectedBillToPos() {
        if (!authorizeAction(access.canSendToPos) { notice.value = VIEW_ONLY_MESSAGE }) return
        val bill = state.value.selectedBill ?: return
        if (!bill.editable) {
            notice.value = when {
                bill.heldOrSending -> "This bill is already at POS or still syncing there. Do not send it again."
                else -> "Resolve the saved table action before sending this bill to POS."
            }
            return
        }
        captureExistingBillAction(
            billProjection = bill,
            kind = CafeActionKind.SEND_TO_POS,
            payload = CafeActionPayload(),
            dedupeKey = "send:${bill.localBillId ?: bill.serverOrderId}",
            successMessage = sendSavedNotice(
                tableCode = state.value.selectedTable?.code ?: bill.tableCode.orEmpty(),
                online = app.connectivity.online.value,
            ),
        )
    }

    /** Pre-v24 screen bridge; the rewritten screen exposes the two actions separately. */
    fun sendToPos() {
        if (state.value.draftingRound) saveRound() else sendSelectedBillToPos()
    }

    /** Legacy rows are migrated to v24 and retried from the blocked-actions panel. */
    fun retryRejectedOrder(localId: String) {
        notice.value = "This saved legacy order is being upgraded. Refresh Tables and use its recovery action."
    }

    private fun captureExistingBillAction(
        billProjection: CafeBillProjection,
        kind: String,
        payload: CafeActionPayload,
        dedupeKey: String,
        successMessage: String,
    ) {
        if (busy.value) return
        val table = state.value.selectedTable ?: return
        val shiftId = state.value.activeShiftId
        if (shiftId == null) {
            notice.value = "No usable shift is open on this terminal. Open a shift before changing this bill."
            return
        }
        val lease = app.cacheIsolation.currentLease() ?: return
        val localBillId = billProjection.localBillId
            ?: billProjection.serverOrderId?.let { "server:$it" }
            ?: return
        val now = System.currentTimeMillis()
        val localBill = LocalCafeBillEntity(
            localBillId = localBillId,
            serverOrderId = billProjection.serverOrderId,
            tableId = billProjection.tableId,
            tableCode = table.code,
            shiftId = shiftId,
            confirmedCheckoutVersion = billProjection.checkoutVersion,
            createdAtMillis = now,
        )
        val action = LocalCafeActionEntity(
            actionId = UUID.randomUUID().toString(),
            localBillId = localBillId,
            kind = kind,
            payload = payload,
            capturedCheckoutVersion = billProjection.checkoutVersion,
            dedupeKey = dedupeKey,
            createdAtMillis = now,
        )
        busy.value = true
        viewModelScope.launch {
            try {
                val result = app.cacheIsolation.commitResultIfCurrent(lease) {
                    cafeDao.captureAction(localBill, action)
                }
                if ((result as? ScopedCommitResult.Committed)?.value == true) {
                    notice.value = successMessage
                    app.sync.requestSync()
                } else {
                    notice.value = "This action was already saved or the account changed. Refresh the bill before trying again."
                }
            } catch (_: Exception) {
                notice.value = "The action was not saved. Nothing was sent; review the bill and try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun retryBlockedAction(actionId: String) {
        if (busy.value) return
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before retrying. The original action remains saved on this tablet."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                if (!refreshBillsForRecovery()) {
                    notice.value = "The account or terminal changed during refresh. The original action remains saved."
                    return@launch
                }
                val action = cafeDao.action(actionId)
                if (action != null && !canRecover(action.kind)) {
                    notice.value = VIEW_ONLY_MESSAGE
                    return@launch
                }
                val bill = action?.let { cafeDao.localBill(it.localBillId) }
                val refreshed = bill?.serverOrderId?.let { cafeDao.billCacheByOrderId(it) }
                if (
                    action?.state == CafeActionState.CONFLICT &&
                    action.kind == CafeActionKind.CREATE_ROUND
                ) {
                    notice.value = "Another active bill now occupies this table. Review that confirmed bill; " +
                        "do not retry the saved first round. Discard it only after verifying its items were not accepted."
                    return@launch
                }
                if (
                    action?.state == CafeActionState.CONFLICT &&
                    bill?.serverOrderId != null && refreshed == null
                ) {
                    notice.value = "This bill is no longer active in Tables. Check POS or order history; " +
                        "do not replay this action. Discard it only after verifying the server outcome."
                    return@launch
                }
                val moved = when {
                    action == null -> 0
                    action.state == CafeActionState.CONFLICT && refreshed != null ->
                        cafeDao.rebaseAndRetryConflict(actionId, refreshed.checkoutVersion)
                    else -> cafeDao.retryAction(actionId)
                }
                notice.value = if (moved > 0) {
                    "The original saved action is queued again after refresh. Review this table until the server confirms it."
                } else {
                    "This action already changed state. Refresh Tables and review the bill."
                }
                if (moved > 0) app.sync.requestSync()
            } catch (_: Exception) {
                notice.value = "The retry could not be prepared. The original action is still saved; reconnect and try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun discardBlockedAction(actionId: String) {
        if (busy.value) return
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect and refresh the confirmed bill before discarding a saved action."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                if (!refreshBillsForRecovery()) {
                    notice.value = "The account or terminal changed during refresh. The saved action was not discarded."
                    return@launch
                }
                val action = cafeDao.action(actionId)
                if (action != null && !canRecover(action.kind)) {
                    notice.value = VIEW_ONLY_MESSAGE
                    return@launch
                }
                val discarded = cafeDao.discardBlockedAction(actionId)
                notice.value = if (discarded) {
                    "The refused local action was discarded after refresh. The confirmed server bill was not changed."
                } else {
                    "This action already changed state. Refresh Tables and review the bill."
                }
            } catch (_: Exception) {
                notice.value = "The saved action was not discarded. It remains available for recovery; reconnect and try again."
            } finally {
                busy.value = false
            }
        }
    }

    private fun canRecover(kind: String): Boolean = when (kind) {
        CafeActionKind.VOID_LINE -> access.canCancelItems
        CafeActionKind.SEND_TO_POS,
        CafeActionKind.LEGACY_CREATE_AND_SEND,
        -> access.canSendToPos
        CafeActionKind.CREATE_ROUND,
        CafeActionKind.APPEND_ROUND,
        -> access.canCreateOrders
        else -> false
    }

    /**
     * Recovery cannot rely on SyncEngine.refresh(), which intentionally
     * swallows expected offline API failures for ordinary screen refreshes.
     * A destructive discard or version rebase needs positive evidence that a
     * fresh active-bill response committed under this exact account scope.
     */
    private suspend fun refreshBillsForRecovery(): Boolean {
        return app.sync.refreshCafeBillsForRecovery()
    }

    fun dismissNotice() { notice.value = null }
}

internal fun roundSavedNotice(tableCode: String, online: Boolean): String = if (online) {
    "Table $tableCode's service round is saved and queued for server confirmation. Kitchen will see it after sync confirms."
} else {
    "Offline: Table $tableCode's service round is saved on this tablet. Kitchen cannot see it yet; it will release automatically after reconnect."
}

internal fun sendSavedNotice(tableCode: String, online: Boolean): String = if (online) {
    "Table $tableCode's bill handoff is saved and queued. It becomes selectable in POS only after server confirmation."
} else {
    "Offline: Table $tableCode's bill handoff is saved, but POS cannot see it yet. It will send automatically after reconnect."
}

private fun cafeActionLabel(kind: String): String = when (kind) {
    CafeActionKind.CREATE_ROUND -> "First service round"
    CafeActionKind.APPEND_ROUND -> "Later service round"
    CafeActionKind.VOID_LINE -> "Item cancellation"
    CafeActionKind.SEND_TO_POS -> "Send to POS"
    CafeActionKind.LEGACY_CREATE_AND_SEND -> "Legacy table handoff"
    else -> "Table action"
}

private fun FloorEntity.toFloor() = Floor(id = id, branchId = branchId, name = name)

private fun CafeTableEntity.toCafeTable(statusOverride: String? = null) = CafeTable(
    id = id,
    floorId = floorId,
    code = code,
    seats = seats,
    shape = shape,
    x = x,
    y = y,
    status = statusOverride ?: status,
)

// Kept through v24 so pre-v24 recovery tests and support tooling can still
// explain/quarantine a migrated local_table_orders row.
data class RejectedTableOrder(
    val localId: String,
    val tableId: String,
    val tableCode: String,
    val itemSummary: String,
    val createdAtMillis: Long,
    val error: String,
)

internal fun unresolvedTableOrderStates(
    orders: List<LocalTableOrderEntity>,
): Map<String, String> = orders.groupBy { it.tableId }.mapValues { (_, rows) ->
    if (rows.any { it.state == TableOrderState.REJECTED }) TableOrderState.REJECTED
    else TableOrderState.PENDING
}

internal fun LocalTableOrderEntity.toRejectedTableOrder(
    menuNames: Map<String, String>,
): RejectedTableOrder {
    val summary = lines.joinToString(" · ") { line ->
        "${line.qty} × ${menuNames[line.menuItemId] ?: "Unavailable menu item"}"
    }.ifBlank { "No item details were saved" }
    return RejectedTableOrder(
        localId = localId,
        tableId = tableId,
        tableCode = tableCode,
        itemSummary = summary,
        createdAtMillis = createdAtMillis,
        error = plainTableOrderError(lastError),
    )
}

internal fun plainTableOrderError(raw: String?): String {
    val compact = raw.orEmpty().trim().replace(Regex("\\s+"), " ")
    return when {
        compact.isBlank() -> "The server refused this order without an explanation."
        compact.startsWith("Could not sync this (app error):", ignoreCase = true) ->
            "The tablet could not prepare this order. Check that its menu items and shift are still valid, then retry."
        compact.startsWith("Request failed (HTTP", ignoreCase = true) ->
            "The server refused this order. Ask a manager to check the open shift and menu, then retry."
        else -> compact
    }
}

internal fun tableOrderQueuedNotice(tableCode: String, online: Boolean): String = if (online) {
    "Table $tableCode's order is saved on this tablet and queued for POS confirmation. It is not confirmed yet; the table stays blocked until the server accepts it."
} else {
    "Offline: Table $tableCode's order is saved on this tablet but has not reached POS. It will send automatically when the connection returns; the table stays blocked until the server accepts it."
}

internal fun tableOrderRetryQueuedNotice(tableCode: String): String =
    "The original saved order for Table $tableCode is queued again with the same order identity. It is not confirmed yet; the table stays blocked until the server accepts it."

internal fun tableOrderOutcomeNotice(tableCode: String, order: LocalTableOrderEntity?): String? = when {
    order == null -> "Table $tableCode's order reached POS and is ready to select there. Do not send a second order."
    order.state == TableOrderState.REJECTED ->
        "Table $tableCode's saved order was refused: ${plainTableOrderError(order.lastError)} The table remains blocked. Correct the cause, then use Retry after correction."
    else -> null
}

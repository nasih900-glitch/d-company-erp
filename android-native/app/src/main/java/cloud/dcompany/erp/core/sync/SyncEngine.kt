package cloud.dcompany.erp.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.CafeTableEntity
import cloud.dcompany.erp.core.db.FloorEntity
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.KitchenOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.LocalRefundEntity
import cloud.dcompany.erp.core.db.LocalTableOrderEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.RefundOrderCacheEntity
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.SyncMetaEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import cloud.dcompany.erp.core.net.PaymentRequest
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.screens.gaming.GamingApi
import cloud.dcompany.erp.ui.screens.gaming.SessionStartBody
import cloud.dcompany.erp.ui.screens.kitchen.KitchenApi
import cloud.dcompany.erp.ui.screens.kitchen.KitchenStateUpdate
import cloud.dcompany.erp.ui.screens.refunds.RefundBody
import cloud.dcompany.erp.ui.screens.refunds.RefundsApi
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import cloud.dcompany.erp.ui.screens.shift.ShiftCloseBody
import cloud.dcompany.erp.ui.screens.shift.ShiftOpenBody
import cloud.dcompany.erp.ui.screens.tables.TablesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Watches real connectivity. `hasInternet` requires NET_CAPABILITY_VALIDATED,
 * not merely "wifi associated" — a till connected to a cafe access point that
 * has lost its uplink is offline for our purposes, and treating it as online
 * is what produces spinners instead of sales.
 */
class ConnectivityObserver(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var onRegained: (() -> Unit)? = null

    fun start(onBackOnline: () -> Unit) {
        onRegained = onBackOnline
        _online.value = currentlyValidated()
        // registerDefaultNetworkCallback, NOT a capability-filtered request.
        // The filtered form only reports networks that already match, so in the
        // trial run toggling airplane mode never produced a callback: the
        // banner stayed hidden and the queue never drained. The default
        // callback reports every transition, and onCapabilitiesChanged is what
        // actually fires when a link becomes validated.
        manager?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = refresh()
            },
        )
    }

    private fun refresh() {
        val nowOnline = currentlyValidated()
        val wasOffline = !_online.value
        _online.value = nowOnline
        if (nowOnline && wasOffline) onRegained?.invoke()
    }

    private fun currentlyValidated(): Boolean {
        val active = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Pulls reference data down and drains captured sales up.
 *
 * Ordering matters: sales are pushed *before* the menu is refreshed, so a
 * queued sale is priced against the menu it was actually taken on rather than
 * against a menu that changed while the tablet was offline.
 */
class SyncEngine(
    private val db: ErpDatabase,
    private val scope: CoroutineScope,
) {

    private val shiftApi = ApiClient.create<ShiftApi>()
    private val gamingApi = ApiClient.create<GamingApi>()
    private val kitchenApi = ApiClient.create<KitchenApi>()
    private val tablesApi = ApiClient.create<TablesApi>()
    private val refundsApi = ApiClient.create<RefundsApi>()
    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val pendingCount = db.orderDao().observePendingCount()
    val rejectedCount = db.orderDao().observeRejectedCount()

    /**
     * Every outbox's rejected count combined, so a rejected row on a resource
     * nobody currently has open (e.g. a shift, once other resources join this
     * later) is never invisible. A *pending* row isn't included the same way —
     * it will sync on its own given time and doesn't need cross-screen
     * attention the way a stuck, rejected one does. Grows as more resources
     * gain an outbox — see the plan this phase started from.
     */
    val totalRejectedCount = combine(
        rejectedCount,
        db.shiftDao().observeRejectedCount(),
        db.gamingDao().observeRejectedCount(),
        db.kitchenDao().observeRejectedCount(),
        // Kotlin's combine() only has typed overloads up to 5 flows — nest
        // the last two into a Pair rather than adding a 6th argument, or it
        // silently falls back to the untyped vararg overload (see the same
        // fix in TablesViewModel.state).
        combine(db.tablesDao().observeRejectedCount(), db.refundDao().observeRejectedCount(), ::Pair),
    ) { orders, shifts, gaming, kitchen, tablesAndRefunds ->
        val (tables, refunds) = tablesAndRefunds
        orders + shifts + gaming + kitchen + tables + refunds
    }

    /**
     * Pull-only resources, fetched when a screen using them opens or a
     * realtime "changed" event names them — not on every sync(), which would
     * otherwise fan every reconnect out into GETs for screens nobody has
     * open. Push (the outbox drain) is always unconditional in sync(); this
     * map is purely the read side.
     */
    private val onDemandPulls: Map<String, suspend () -> Unit> = mapOf(
        "gaming" to ::pullGamingData,
        "kitchen" to ::pullKitchenData,
        "tables" to ::pullTablesData,
        // "orders" is the same realtime resource POS/Kitchen already broadcast
        // on (backend _PATH_RESOURCE_MAP maps /pos/orders, including the
        // nested /refunds route, to "orders") — reusing it means another
        // terminal issuing a refund or taking a payment already wakes this
        // pull, no new backend resource needed.
        "orders" to ::pullRefundableOrders,
    )

    /**
     * Same `ApiException`-only catch as [sync] — offline is the expected,
     * routine case here (a screen refreshing on open), not a bug. Letting it
     * propagate crashed the app the instant Gaming/Kitchen/Tables opened
     * without a connection, which defeats the entire point of caching these
     * resources in Room in the first place.
     */
    suspend fun refresh(resource: String) {
        try {
            onDemandPulls[resource]?.invoke()
        } catch (e: ApiException) {
            _lastError.value = e.message
        }
    }

    /** Every on-demand resource — used after a realtime reconnect-after-gap. */
    suspend fun refreshAllOnDemand() {
        for (pull in onDemandPulls.values) {
            try {
                pull()
            } catch (e: ApiException) {
                _lastError.value = e.message
            }
        }
    }

    fun requestSync() {
        scope.launch { sync() }
    }

    /**
     * A single-flight guard, not a queue: two overlapping syncs would push the
     * same pending rows twice. The idempotency key makes that harmless on the
     * server, but it still wastes a congested link.
     */
    suspend fun sync() {
        if (!mutex.tryLock()) return
        try {
            _syncing.value = true
            _lastError.value = null
            // Shifts before orders: an order captured against a shift that
            // hasn't synced yet has nothing to attach to until the shift's
            // open leg resolves (see pushPendingOrders).
            pushShifts()
            pushPendingOrders()
            pushGamingSessions()
            pushKitchenAdvances()
            pushTableOrders()
            pushRefunds()
            pullMenu()
            db.syncMetaDao().put(SyncMetaEntity("menu", System.currentTimeMillis()))
        } catch (e: ApiException) {
            _lastError.value = e.message
        } finally {
            _syncing.value = false
            mutex.unlock()
        }
    }

    /**
     * One unit of outbox work per row: `push` either fully succeeds or throws.
     * A non-`ApiException` is a bug on our side (e.g. a DTO mismatch) — reject
     * visibly rather than let it crash the app mid-sync, same reasoning as the
     * order push below. An ambiguous `ApiException` (no answer, or the server
     * is mid-flight) stops the whole drain so a bad link isn't hammered
     * further; a definitive refusal is parked for a human, since retrying
     * cannot change the answer.
     *
     * Returns false if the drain stopped early on an ambiguous failure.
     */
    private suspend fun <T> drainOutbox(
        rows: List<T>,
        markRejected: suspend (T, String) -> Unit,
        push: suspend (T) -> Unit,
    ): Boolean {
        for (row in rows) {
            try {
                push(row)
            } catch (e: Exception) {
                _lastError.value = e.message
                if (e !is ApiException) {
                    markRejected(row, "Could not sync this (app error): ${e.message}")
                    continue
                }
                if (e.isAmbiguous) return false
                markRejected(row, e.message ?: "Server refused this.")
            }
        }
        return true
    }

    private suspend fun pushShifts() {
        val dao = db.shiftDao()
        drainOutbox(
            rows = dao.pushable(),
            markRejected = { row, msg -> dao.markRejected(row.localId, msg) },
            push = ::pushShiftOne,
        )
    }

    /**
     * Open, then close if requested — in that order, since a close can't be
     * sent until the shift it refers to has a real server id. A close
     * requested before the open has synced is not a special case here: it's
     * already `state = close_pending` on this same row (see
     * ShiftViewModel.closeShift), so the check below just finds it waiting.
     */
    private suspend fun pushShiftOne(row: LocalShiftEntity) {
        val dao = db.shiftDao()
        var serverShiftId = row.serverShiftId
        if (serverShiftId == null) {
            val opened = shiftApi.open(
                ShiftOpenBody(row.openingFloatMinor),
                "shift-open:${row.localId}",
            )
            serverShiftId = opened.id
            dao.setServerShiftId(row.localId, serverShiftId)
            dao.transitionState(row.localId, fromState = ShiftState.OPEN_PENDING, toState = ShiftState.OPEN_SYNCED)
            // Legacy synchronous readers (e.g. Gaming's session-start) still
            // read this cache directly rather than observing Room — keep it
            // in step now that a real id exists, instead of a local placeholder.
            DCompanyApp.instance.shiftCache.remember(serverShiftId)
        }
        val current = dao.byLocalId(row.localId) ?: return
        if (current.state == ShiftState.CLOSE_PENDING) {
            val result = shiftApi.close(
                serverShiftId,
                ShiftCloseBody(current.countedMinor ?: 0L),
                "shift-close:${row.localId}",
            )
            dao.markClosed(row.localId, result.varianceMinor)
        }
    }

    /**
     * Same dependency-resolution need as orders: a session started against a
     * shift that was itself opened offline carries that shift's `localId`
     * until it resolves. Sessions whose shift hasn't synced yet are left
     * untouched, not rejected — the next sync() call picks them up once
     * pushShifts() (which runs first) has resolved it.
     */
    private suspend fun pushGamingSessions() {
        val dao = db.gamingDao()
        val shiftDao = db.shiftDao()
        val ready = dao.pushableSessions().filter { row ->
            // Only a still-unsynced start leg has a shift to wait on — a
            // stop-only row (serverId already set) or a genuinely shiftless
            // row is always ready.
            if (row.serverId != null || row.shiftId == null) return@filter true
            val localShift = shiftDao.byLocalId(row.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markSessionRejected(row.localId, msg) },
            push = ::pushGamingSessionOne,
        )
    }

    /**
     * Start, then stop if requested — same one-row-both-legs shape and same
     * reasoning as pushShiftOne: a stop requested before the start has
     * synced is already `state = stop_pending` on this row, so the check
     * below just finds it waiting once a real serverId exists.
     */
    private suspend fun pushGamingSessionOne(row: LocalGamingSessionEntity) {
        val dao = db.gamingDao()
        var serverId = row.serverId
        if (serverId == null) {
            // Guaranteed non-null here by GamingViewModel.start() — the only
            // call site that ever inserts a start_pending row. A violation
            // surfaces as a caught, rejected "app error" via drainOutbox
            // rather than crashing the sync.
            val shiftId = row.shiftId!!
            val resolvedShiftId = db.shiftDao().byLocalId(shiftId)?.serverShiftId ?: shiftId
            val started = gamingApi.start(
                SessionStartBody(
                    stationId = row.stationId,
                    shiftId = resolvedShiftId,
                    customerPhone = row.customerPhone,
                    timerMinutes = row.timerMinutes,
                ),
                "gaming-session-start:${row.localId}",
            )
            serverId = started.id
            dao.setSessionStarted(
                row.localId,
                serverId,
                started.status,
                started.timerEndsAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
            dao.transitionSessionState(
                row.localId,
                fromState = GamingSessionState.START_PENDING,
                toState = GamingSessionState.START_SYNCED,
            )
        }
        val current = dao.localSessionById(row.localId) ?: return
        if (current.state == GamingSessionState.STOP_PENDING) {
            val stopped = gamingApi.stop(serverId, "gaming-session-stop:${row.localId}")
            dao.markSessionStopped(
                row.localId,
                stopped.status,
                stopped.endAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                stopped.billableMinutes,
                stopped.amountMinor,
            )
        }
    }

    /**
     * Stations + every session on every terminal — a shared floor view, so
     * this always pulls the whole company's sessions, not just this
     * device's. On-demand only (see onDemandPulls): a screen open or a
     * realtime "gaming" event triggers it, not every sync().
     */
    private suspend fun pullGamingData() {
        val stations = gamingApi.stations()
        db.gamingDao().replaceStations(
            stations.map {
                GamingStationEntity(
                    id = it.id,
                    code = it.code,
                    name = it.name,
                    type = it.type,
                    ratePerHourMinor = it.ratePerHourMinor,
                    isActive = it.isActive,
                )
            },
        )
        val sessions = gamingApi.sessions()
        db.gamingDao().replaceSessionCache(
            sessions.map {
                GamingSessionCacheEntity(
                    id = it.id,
                    stationId = it.stationId,
                    status = it.status,
                    startAtMillis = runCatching { Instant.parse(it.startAt).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis()),
                    endAtMillis = it.endAt?.let { s -> runCatching { Instant.parse(s).toEpochMilli() }.getOrNull() },
                    timerMinutes = it.timerMinutes,
                    timerEndsAtMillis = it.timerEndsAt?.let { s ->
                        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                    },
                    billableMinutes = it.billableMinutes,
                    amountMinor = it.amountMinor,
                    customerName = it.customerName,
                    customerPhone = it.customerPhone,
                    orderId = it.orderId,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("gaming", System.currentTimeMillis()))
    }

    /**
     * No idempotency key, no dependency to resolve — `setState` is naturally
     * idempotent (see KitchenState's own doc comment), so a retry is just
     * calling it again. A synced row is deleted rather than kept: the next
     * pull already reflects the truth, and there's no local history worth
     * keeping for a ticket advance the way there is for a sale.
     */
    private suspend fun pushKitchenAdvances() {
        val dao = db.kitchenDao()
        drainOutbox(
            rows = dao.pendingAdvances(),
            markRejected = { row, msg -> dao.markAdvanceRejected(row.localId, msg) },
        ) { row ->
            kitchenApi.setState(row.orderId, KitchenStateUpdate(row.targetState))
            dao.deleteAdvance(row.localId)
        }
    }

    private suspend fun pullKitchenData() {
        val orders = kitchenApi.queue(includeServed = false)
        db.kitchenDao().replaceOrderCache(
            orders.map {
                KitchenOrderCacheEntity(
                    id = it.id,
                    invoiceNo = it.invoiceNo,
                    type = it.type,
                    tableCode = it.tableCode,
                    customerName = it.customerName,
                    openedAt = it.openedAt,
                    kitchenState = it.kitchenState,
                    minutesWaiting = it.minutesWaiting,
                    lines = it.lines,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("kitchen", System.currentTimeMillis()))
    }

    /**
     * Create, then send to POS — always both together, never independently
     * (unlike Shift/Gaming's open-then-maybe-close-later shape). A retry
     * that finds `orderId` already set skips straight to the send leg; both
     * legs are safe to repeat (create via its idempotency key, send because
     * the backend already treats re-sending an already-"held" order as a
     * no-op).
     */
    private suspend fun pushTableOrders() {
        val dao = db.tablesDao()
        val shiftDao = db.shiftDao()
        val ready = dao.pendingOrders().filter { row ->
            val localShift = shiftDao.byLocalId(row.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markOrderRejected(row.localId, msg) },
            push = ::pushTableOrderOne,
        )
    }

    private suspend fun pushTableOrderOne(row: LocalTableOrderEntity) {
        val dao = db.tablesDao()
        var orderId = row.orderId
        if (orderId == null) {
            val resolvedShiftId = db.shiftDao().byLocalId(row.shiftId)?.serverShiftId ?: row.shiftId
            val created = tablesApi.createOrder(
                CreateOrderRequest(
                    type = "dine_in",
                    shiftId = resolvedShiftId,
                    lines = row.lines.map { OrderLineRequest(it.menuItemId, it.qty) },
                    tableId = row.tableId,
                ),
                "table-order:${row.localId}",
            )
            orderId = created.id
            dao.setOrderId(row.localId, orderId)
        }
        tablesApi.sendToPos(orderId)
        dao.deleteLocalOrder(row.localId)
    }

    private suspend fun pullTablesData() {
        val floors = tablesApi.floors()
        db.tablesDao().replaceFloors(
            floors.map { FloorEntity(id = it.id, branchId = it.branchId, name = it.name) },
        )
        val tables = tablesApi.tables()
        db.tablesDao().replaceTables(
            tables.map {
                CafeTableEntity(
                    id = it.id,
                    floorId = it.floorId,
                    code = it.code,
                    seats = it.seats,
                    shape = it.shape,
                    x = it.x,
                    y = it.y,
                    status = it.status,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("tables", System.currentTimeMillis()))
    }

    /**
     * No shift id to resolve — a refund always targets an order the server
     * already knows about (this app never creates or shows an order that
     * isn't already synced, see [RefundOrderCacheEntity]), so unlike
     * orders/gaming/tables there's nothing here to wait on. A cash refund
     * does still need an open shift on THIS terminal at the moment it's
     * pushed (the backend checks the terminal, not a specific shift id) —
     * pushShifts() runs earlier in sync() so this device's own shift-open,
     * if it too happened offline, has already had a chance to resolve
     * within the same pass.
     */
    private suspend fun pushRefunds() {
        val dao = db.refundDao()
        drainOutbox(
            rows = dao.pushableRefunds(),
            markRejected = { row, msg -> dao.markRefundRejected(row.localId, msg) },
            push = ::pushRefundOne,
        )
    }

    private suspend fun pushRefundOne(row: LocalRefundEntity) {
        val result = refundsApi.refund(
            row.orderId,
            RefundBody(
                reasonCode = row.reasonCode,
                amountMinor = row.amountMinor,
                mode = "cash",
                note = row.note,
            ),
            "refund:${row.localId}",
        )
        db.refundDao().markRefundSynced(row.localId, result.settlementMethod)
        // The row leaving 'pending' drops it out of the local netting the
        // instant this returns — refresh the cache's server-computed balance
        // right away so a second refund queued in this same drain (or right
        // after) checks a current figure, not the pre-refund one.
        pullRefundableOrders()
    }

    /**
     * Paid orders eligible for a refund, for every terminal — a shared view
     * like Gaming's sessions. On-demand only (see onDemandPulls): opening
     * Refunds, an "orders" realtime event, or this device's own refund
     * finishing (see pushRefundOne) triggers it, not every sync().
     *
     * Passing status=paid explicitly (rather than the unfiltered call the
     * pre-offline screen made) matters for two reasons: it skips the
     * backend's default same-day date window, so an order paid yesterday is
     * still refundable today, and it's what makes the backend compute and
     * return refundable_minor at all (see OrderListItem/list_orders) —
     * unfiltered list_orders calls exist elsewhere in this app for today's
     * order history and don't need that figure.
     */
    private suspend fun pullRefundableOrders() {
        val orders = refundsApi.orders(status = "paid")
        db.refundDao().replaceOrderCache(
            orders.map {
                RefundOrderCacheEntity(
                    id = it.id,
                    invoiceNo = it.invoiceNo,
                    status = it.status,
                    type = it.type,
                    totalMinor = it.totalMinor,
                    paidMinor = it.paidMinor,
                    refundableMinor = it.refundableMinor,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("orders", System.currentTimeMillis()))
    }

    private suspend fun pullMenu() {
        val categories = ApiClient.api.menuCategories()
        val items = ApiClient.api.menuItems()
        db.menuDao().replaceMenu(
            items = items.map {
                MenuItemEntity(
                    id = it.id,
                    categoryId = it.categoryId,
                    sku = it.sku,
                    name = it.name,
                    basePriceMinor = it.basePriceMinor,
                    taxRate = it.taxRate,
                    isAvailable = it.isAvailable,
                )
            },
            categories = categories.map {
                MenuCategoryEntity(id = it.id, name = it.name, sortOrder = it.sortOrder)
            },
        )
    }

    /**
     * An order captured while its shift was still `open_pending` carries that
     * shift's `localId`, which the server has never heard of — pushShifts()
     * runs first, but a shift can itself be waiting on an ambiguous retry, so
     * this still has to check rather than assume the shift resolved. Orders
     * held back this way are simply left untouched (not rejected, not an
     * error): the next sync() call retries them once their shift catches up.
     */
    private suspend fun pushPendingOrders() {
        val dao = db.orderDao()
        val shiftDao = db.shiftDao()
        val ready = dao.byState(SyncState.PENDING).filter { order ->
            val localShift = shiftDao.byLocalId(order.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markRejected(row.localId, msg) },
            push = ::pushOne,
        )
    }

    private suspend fun pushOne(order: LocalOrderEntity) {
        val dao = db.orderDao()
        val lines = dao.linesFor(order.localId)
        // If order.shiftId is a local shift's id, resolve it to the real
        // server id (guaranteed non-null here — pushPendingOrders only lets
        // resolved orders through). Otherwise it was already a real server
        // shift id (the common case: shift opened while online).
        val resolvedShiftId = db.shiftDao().byLocalId(order.shiftId)?.serverShiftId ?: order.shiftId

        // Deterministic keys derived from the local id: replaying this whole
        // function after a crash reuses them and the server returns the stored
        // response rather than creating a second order or a second payment.
        val created = ApiClient.api.createOrder(
            CreateOrderRequest(
                type = order.type,
                shiftId = resolvedShiftId,
                lines = lines.map { OrderLineRequest(it.menuItemId, it.qty) },
                customerName = order.customerName,
                customerPhone = order.customerPhone,
            ),
            idempotencyKey = "order:${order.localId}",
        )

        // The server has now priced it, and that price may differ from the
        // offline estimate the customer actually paid against — a membership
        // discount, or a price edited while this tablet was offline.
        //
        // Auto-paying the server's figure would silently record money that was
        // never collected (or drop money that was), and the shift's cash count
        // would be wrong with nothing to show why. A partial payment is not an
        // escape either: the backend refuses one unless split payments are
        // enabled. So a mismatch is parked for a human, with both numbers in
        // the message. The order stays open and unpaid on the server, which is
        // the honest state.
        if (created.dueMinor != order.estimateMinor) {
            dao.markRejected(
                order.localId,
                "Price changed while offline: collected ${order.estimateMinor.asRupees()}, " +
                    "server bill is ${created.dueMinor.asRupees()}. " +
                    "Order ${created.id.take(8)} is open and unpaid — settle the difference.",
            )
            return
        }

        val paid = ApiClient.api.recordPayment(
            created.id,
            PaymentRequest(
                method = order.paymentMethod,
                amountMinor = created.dueMinor,
                expectedTotalMinor = created.totalMinor,
                expectedDueMinor = created.dueMinor,
                tipMinor = order.tipMinor,
            ),
            idempotencyKey = "payment:${order.localId}",
        )

        dao.markSynced(
            localId = order.localId,
            // The order id, not the payment id — paid.id identifies the Payment.
            serverId = created.id,
            invoiceNo = paid.invoiceNo,
            totalMinor = created.totalMinor,
        )
    }
}

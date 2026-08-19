package cloud.dcompany.erp.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.SyncMetaEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import cloud.dcompany.erp.core.net.PaymentRequest
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import cloud.dcompany.erp.ui.screens.shift.ShiftCloseBody
import cloud.dcompany.erp.ui.screens.shift.ShiftOpenBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val totalRejectedCount = combine(rejectedCount, db.shiftDao().observeRejectedCount()) { a, b -> a + b }

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

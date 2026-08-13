package cloud.dcompany.erp.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.SyncMetaEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import cloud.dcompany.erp.core.net.PaymentRequest
import cloud.dcompany.erp.core.net.asRupees
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val pendingCount = db.orderDao().observePendingCount()
    val rejectedCount = db.orderDao().observeRejectedCount()

    fun requestSync() {
        scope.launch { sync() }
    }

    /**
     * A single-flight guard, not a queue: two overlapping syncs would push the
     * same pending orders twice. The idempotency key makes that harmless on
     * the server, but it still wastes a congested link.
     */
    suspend fun sync() {
        if (!mutex.tryLock()) return
        try {
            _syncing.value = true
            _lastError.value = null
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

    private suspend fun pushPendingOrders() {
        val dao = db.orderDao()
        for (order in dao.byState(SyncState.PENDING)) {
            try {
                pushOne(order)
            } catch (e: Exception) {
                // Surface it either way. The trial run left a sale sitting in
                // the outbox with syncState=pending and no message anywhere —
                // from the till it looked identical to a completed sale.
                _lastError.value = e.message
                if (e !is ApiException) {
                    // Not a server answer at all — a bug on our side, e.g. a
                    // DTO that does not match the payload. This used to escape
                    // as an uncaught SerializationException and CRASH the app
                    // mid-sync, which is how a captured sale ended up stranded
                    // with the process dead. Park it visibly and keep going.
                    dao.markRejected(
                        order.localId,
                        "Could not send this sale (app error): ${e.message}",
                    )
                    continue
                }
                if (e.isAmbiguous) {
                    // No answer, or the server is mid-flight. The sale stays
                    // pending and the same idempotency key is replayed later,
                    // so a request that did land is never duplicated. Stop the
                    // whole drain: the link is bad, and hammering it with the
                    // rest of the queue only makes it worse.
                    return
                }
                // A definitive refusal. Retrying cannot change the answer, so
                // park it for a human instead of looping forever.
                dao.markRejected(order.localId, e.message ?: "Server refused this sale.")
            }
        }
    }

    private suspend fun pushOne(order: LocalOrderEntity) {
        val dao = db.orderDao()
        val lines = dao.linesFor(order.localId)

        // Deterministic keys derived from the local id: replaying this whole
        // function after a crash reuses them and the server returns the stored
        // response rather than creating a second order or a second payment.
        val created = ApiClient.api.createOrder(
            CreateOrderRequest(
                type = order.type,
                shiftId = order.shiftId,
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

package cloud.dcompany.erp.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.CafeTableEntity
import cloud.dcompany.erp.core.db.CapitalEntryCacheEntity
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.EventCacheEntity
import cloud.dcompany.erp.core.db.EventTicketCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity
import cloud.dcompany.erp.core.db.FloorEntity
import cloud.dcompany.erp.core.db.LocalAssetEntity
import cloud.dcompany.erp.core.db.LocalCapitalEntryEntity
import cloud.dcompany.erp.core.db.LocalCheckInEntity
import cloud.dcompany.erp.core.db.CustomerMembershipCacheEntity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.LocalMembershipCancellationEntity
import cloud.dcompany.erp.core.db.LocalSubscriptionEntity
import cloud.dcompany.erp.core.db.LocalTicketSaleEntity
import cloud.dcompany.erp.core.db.MembershipTierCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.BatchCacheEntity
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.IngredientCacheEntity
import cloud.dcompany.erp.core.db.KitchenOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalAdjustmentEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import cloud.dcompany.erp.core.db.LocalGrnEntity
import cloud.dcompany.erp.core.db.LocalGrnLineEntity
import cloud.dcompany.erp.core.db.LocalIngredientEntity
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.db.LocalCustomerEntity
import cloud.dcompany.erp.core.db.LocalMenuCategoryEntity
import cloud.dcompany.erp.core.db.LocalMenuItemEntity
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.LocalRefundEntity
import cloud.dcompany.erp.core.db.LocalStaffEntity
import cloud.dcompany.erp.core.db.LocalSupplierEntity
import cloud.dcompany.erp.core.db.BranchCacheEntity
import cloud.dcompany.erp.core.db.CompanyCacheEntity
import cloud.dcompany.erp.core.db.LocalBranchEntity
import cloud.dcompany.erp.core.db.LocalCompanyEditEntity
import cloud.dcompany.erp.core.db.LocalTerminalEntity
import cloud.dcompany.erp.core.db.TerminalCacheEntity
import cloud.dcompany.erp.core.db.LocalTableOrderEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.OnShiftEntity
import cloud.dcompany.erp.core.db.RefundOrderCacheEntity
import cloud.dcompany.erp.core.db.StaffCacheEntity
import cloud.dcompany.erp.core.db.SupplierCacheEntity
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.SyncMetaEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import cloud.dcompany.erp.core.net.PaymentRequest
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.screens.customers.CustomerUpdateBody
import cloud.dcompany.erp.ui.screens.customers.CustomerUpsertBody
import cloud.dcompany.erp.ui.screens.customers.CustomersApi
import cloud.dcompany.erp.ui.screens.events.EventsApi
import cloud.dcompany.erp.ui.screens.events.TicketSell
import cloud.dcompany.erp.ui.screens.finance.AssetCreate
import cloud.dcompany.erp.ui.screens.finance.CapitalEntryCreate
import cloud.dcompany.erp.ui.screens.finance.ExpenseCreate
import cloud.dcompany.erp.ui.screens.finance.FinanceApi
import cloud.dcompany.erp.ui.screens.gaming.GamingApi
import cloud.dcompany.erp.ui.screens.gaming.SessionStartBody
import cloud.dcompany.erp.ui.screens.inventory.AdjustmentBody
import cloud.dcompany.erp.ui.screens.memberships.MembershipsApi
import cloud.dcompany.erp.ui.screens.memberships.SubscribeRequest
import cloud.dcompany.erp.ui.screens.inventory.GrnBody
import cloud.dcompany.erp.ui.screens.inventory.GrnLineBody
import cloud.dcompany.erp.ui.screens.inventory.IngredientCreate
import cloud.dcompany.erp.ui.screens.inventory.IngredientUpdate
import cloud.dcompany.erp.ui.screens.inventory.InventoryApi
import cloud.dcompany.erp.ui.screens.inventory.SupplierBody
import cloud.dcompany.erp.ui.screens.kitchen.KitchenApi
import cloud.dcompany.erp.ui.screens.kitchen.KitchenStateUpdate
import cloud.dcompany.erp.ui.screens.menu.CategoryCreateBody
import cloud.dcompany.erp.ui.screens.menu.CategoryUpdateBody
import cloud.dcompany.erp.ui.screens.menu.ItemDetailsUpdateBody
import cloud.dcompany.erp.ui.screens.menu.MenuApi
import cloud.dcompany.erp.ui.screens.refunds.RefundBody
import cloud.dcompany.erp.ui.screens.refunds.RefundsApi
import cloud.dcompany.erp.ui.screens.settings.BranchWriteBody
import cloud.dcompany.erp.ui.screens.settings.CompanyUpdateBody
import cloud.dcompany.erp.ui.screens.settings.SettingsApi
import cloud.dcompany.erp.ui.screens.settings.TerminalCreateBody
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import cloud.dcompany.erp.ui.screens.shift.ShiftCloseBody
import cloud.dcompany.erp.ui.screens.shift.ShiftOpenBody
import cloud.dcompany.erp.ui.screens.staff.StaffApi
import cloud.dcompany.erp.ui.screens.staff.StaffUserUpdateBody
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
    private val customersApi = ApiClient.create<CustomersApi>()
    private val menuApi = ApiClient.create<MenuApi>()
    private val staffApi = ApiClient.create<StaffApi>()
    private val inventoryApi = ApiClient.create<InventoryApi>()
    private val financeApi = ApiClient.create<FinanceApi>()
    private val eventsApi = ApiClient.create<EventsApi>()
    private val membershipsApi = ApiClient.create<MembershipsApi>()
    private val settingsApi = ApiClient.create<SettingsApi>()
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
        // Kotlin's combine() only has typed overloads up to 5 flows — the
        // rest are pre-summed in a nested 5-arg combine (itself at the
        // typed-overload cap) rather than adding more top-level arguments,
        // or piling into a bigger tuple, which would just move the same
        // limit one level down (see the same fix in TablesViewModel.state).
        combine(
            db.tablesDao().observeRejectedCount(),
            db.refundDao().observeRejectedCount(),
            db.customerDao().observeRejectedCount(),
            db.menuWriteDao().observeRejectedCategoryCount(),
            // One more resource than fits this inner combine's own 5-arg cap
            // — nested one level deeper rather than restructuring the levels
            // above, which already have real, unrelated meaning (orders vs.
            // shifts vs. gaming vs. kitchen). Same pre-sum technique, just
            // applied again as the resource count grows past 10.
            combine(
                db.menuWriteDao().observeRejectedItemCount(),
                db.staffDao().observeRejectedCount(),
                // Same trick a third time: 4 more inventory sources than fit
                // this combine's own remaining slots, pre-summed one level
                // deeper again.
                combine(
                    db.inventoryDao().observeRejectedIngredientCount(),
                    db.inventoryDao().observeRejectedSupplierCount(),
                    db.inventoryDao().observeRejectedGrnCount(),
                    db.inventoryDao().observeRejectedAdjustmentCount(),
                ) { ingredients, suppliers, grns, adjustments ->
                    ingredients + suppliers + grns + adjustments
                },
                // Fourth application of the same pre-sum trick: 3 new Finance
                // sources (expense/asset/capital-entry) than fit this
                // combine's own remaining slots.
                combine(
                    db.financeDao().observeRejectedExpenseCount(),
                    db.financeDao().observeRejectedAssetCount(),
                    db.financeDao().observeRejectedCapitalEntryCount(),
                ) { expenses, assets, capitalEntries -> expenses + assets + capitalEntries },
                // Fifth application: 2 Events sources (ticket sale/check-in)
                // filled level 3's last remaining slot — so the 2 new
                // Memberships sources (subscribe/cancel) nest one level
                // deeper again, inside this same slot, rather than
                // widening level 3 past its 5-arg cap. Settings' 3 sources
                // (company edit/branch/terminal) join as a fourth argument
                // at this same nested level — still within its own 5-cap.
                combine(
                    db.eventDao().observeRejectedTicketSaleCount(),
                    db.eventDao().observeRejectedCheckInCount(),
                    combine(
                        db.membershipDao().observeRejectedSubscriptionCount(),
                        db.membershipDao().observeRejectedCancellationCount(),
                    ) { subscriptions, cancellations -> subscriptions + cancellations },
                    combine(
                        db.settingsDao().observeRejectedCompanyEditCount(),
                        db.settingsDao().observeRejectedBranchCount(),
                        db.settingsDao().observeRejectedTerminalCount(),
                    ) { companyEdit, branches, terminals -> companyEdit + branches + terminals },
                ) { ticketSales, checkIns, memberships, settings ->
                    ticketSales + checkIns + memberships + settings
                },
            ) { menuItems, staff, inventory, finance, events ->
                menuItems + staff + inventory + finance + events
            },
        ) { tables, refunds, customers, menuCategories, rest ->
            tables + refunds + customers + menuCategories + rest
        },
    ) { orders, shifts, gaming, kitchen, rest ->
        orders + shifts + gaming + kitchen + rest
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
        "customers" to ::pullCustomers,
        "staff" to ::pullStaff,
        "attendance" to ::pullOnShift,
        // Backend's realtime resource map deliberately does not split
        // inventory into ingredients/suppliers/grn/adjustments — everything
        // under /inventory broadcasts as one combined "inventory" resource,
        // so one write anywhere in the module refreshes both caches here.
        "inventory" to ::pullInventoryOnDemand,
        // Same reasoning for finance: /finance/* broadcasts as one combined
        // "finance" resource. Capital entries are per-partner and demand-
        // loaded on selection instead (see pullCapitalEntriesFor), same
        // shape as pullBatchesFor.
        "finance" to ::pullFinanceOnDemand,
        // /events/* broadcasts as one combined "events" resource too.
        // Per-event ticket lists are demand-loaded on selection instead
        // (see pullTicketsFor), same shape as pullBatchesFor.
        "events" to ::pullEventsData,
        // /memberships/* broadcasts as one combined "memberships" resource.
        // Per-customer membership status is demand-loaded on selection
        // instead (see pullMembershipFor), same shape as pullBatchesFor.
        "memberships" to ::pullTiers,
        // /settings/* broadcasts as one combined "settings" resource —
        // company profile, branches, and terminals all refresh together.
        "settings" to ::pullSettingsData,
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
            pushCustomers()
            pushStaff()
            // Ingredients/suppliers before GRN/adjustments: both depend on an
            // already-synced ingredient/supplier server id (the GRN/adjust
            // dialogs' own pickers exclude anything still local-only, so this
            // ordering isn't required for correctness, just gives a
            // same-pass chance for a just-created ingredient to be usable by
            // a GRN queued in the same batch of offline work).
            pushIngredients()
            pushSuppliers()
            pushGrns()
            pushAdjustments()
            // No cross-resource dependency here (a capital entry's partner
            // is never itself created offline, and an expense's branch/
            // category/supplier pickers only ever offer already-synced
            // options, same dependency-sidestep as GRN/adjustments above) —
            // placed here purely to keep all outbox pushes grouped before
            // the menu pull.
            pushExpenses()
            pushAssets()
            pushCapitalEntries()
            // Sales before check-ins: the tickets UI only ever offers
            // check-in for an already-synced ticket (dependency-sidestep,
            // same as GRN/adjustments only offering synced ingredients), so
            // in practice a check-in row's ticketId is never itself
            // pending — this ordering just matches the general "push a
            // dependency before its dependent" discipline everywhere else.
            pushTicketSales()
            pushCheckIns()
            // Subscribes before cancellations: a cancel row only ever
            // targets an already-synced subscription id (dependency-
            // sidestep, same as check-in only targeting synced tickets),
            // so this ordering matches the general discipline even though
            // it isn't strictly required in practice.
            pushSubscriptions()
            pushCancellations()
            // Branches before terminals: a new terminal only ever targets an
            // already-synced branch id (dependency-sidestep — the branch
            // picker only ever shows branch_cache rows), so a branch created
            // in this same drain must land first. Company edit has no
            // dependency either way.
            pushCompanyEdit()
            pushBranches()
            pushTerminals()
            // Right before the menu pull, same reasoning the pull's own
            // class doc already gives for running last: this device's own
            // just-synced category/item edits, and any other device's,
            // should already be reflected the instant pullMenu() runs.
            pushMenuCategories()
            pushMenuItems()
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

    /**
     * No shift or any other id to resolve first — see [LocalCustomerEntity]'s
     * doc comment for why a customer write never has a dependency chain the
     * way orders/gaming/tables do.
     */
    private suspend fun pushCustomers() {
        val dao = db.customerDao()
        drainOutbox(
            rows = dao.pushable(),
            markRejected = { row, msg -> dao.markRejected(row.localId, msg) },
            push = ::pushCustomerOne,
        )
    }

    /**
     * Order matters here, more than in most push-one functions:
     *
     * 1. `setServerId` lands the moment the response names it — independent
     *    of `state`, so a brand-new customer never has a window where it's
     *    absent from both the local override (still `state = pending`,
     *    still visible) AND the cache (not pulled yet). Without this
     *    separation, marking the row `synced` immediately would remove it
     *    from `observeLocal()` before the cache pull below had a chance to
     *    repopulate it — a guaranteed, every-single-create "the customer I
     *    just added vanished" flash. Landing `serverId` first also means
     *    `mergeCustomers` starts resolving this row against the real cache
     *    row (once pulled) by id rather than a since-superseded phone match.
     * 2. `pullCustomers()` runs before `markSynced` — if this GET fails (a
     *    dropped connection right after a successful write is a realistic
     *    cafe-wifi moment), the row is simply left `pending` with its real
     *    `serverId` already recorded; the next sync() retries it as a PATCH
     *    (absolute-set, so re-sending the same values is harmless) instead
     *    of leaving a `synced`-but-never-deleted, invisible orphan row that
     *    only some unrelated later push would happen to clean up.
     * 3. `markSynced` only applies if `version` still matches what was just
     *    pushed (see LocalCustomerEntity's doc comment) — if a newer edit
     *    landed on this row while this network call was in flight, this is
     *    a no-op and the row stays pending with the newer content, so the
     *    next sync() actually sends it instead of it being silently
     *    discarded when this stale write's response arrives.
     * 4. `deleteSynced()` only runs once this row is confirmed to actually
     *    be the one that got marked synced (step 3 applied) — deleting
     *    unconditionally here would remove a row a concurrent newer edit
     *    just legitimately re-armed back to pending.
     */
    private suspend fun pushCustomerOne(row: LocalCustomerEntity) {
        val dao = db.customerDao()
        val server = if (row.serverId == null) {
            customersApi.upsert(
                CustomerUpsertBody(
                    phone = row.phone!!,
                    name = row.name,
                    email = row.email,
                    birthday = row.birthday,
                    notes = row.notes,
                ),
            )
        } else {
            customersApi.update(
                row.serverId,
                CustomerUpdateBody(
                    name = row.name,
                    phone = row.phone,
                    email = row.email,
                    birthday = row.birthday,
                    notes = row.notes,
                ),
            )
        }
        dao.setServerId(row.localId, server.id)
        pullCustomers()
        val applied = dao.markSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSynced()
    }

    /**
     * The full customer list, wholesale-replaced — same shape as pullMenu.
     * On-demand only (see onDemandPulls): opening Customers, a "customers"
     * realtime event, or this device's own write finishing (see
     * pushCustomerOne) triggers it, not every sync().
     */
    private suspend fun pullCustomers() {
        val customers = customersApi.list(limit = 500)
        db.customerDao().replaceCache(
            customers.map {
                CustomerCacheEntity(
                    id = it.id,
                    name = it.name,
                    phone = it.phone,
                    email = it.email,
                    birthday = it.birthday,
                    visitCount = it.visitCount,
                    totalSpentMinor = it.totalSpentMinor,
                    loyaltyPoints = it.loyaltyPoints,
                    lifetimeGamingPointsEarned = it.lifetimeGamingPointsEarned,
                    gamingRank = it.gamingRank,
                    gamingRankFloor = it.gamingRankFloor,
                    nextGamingRank = it.nextGamingRank,
                    nextGamingRankFloor = it.nextGamingRankFloor,
                    pointsToNextGamingRank = it.pointsToNextGamingRank,
                    lastVisitAt = it.lastVisitAt,
                    notes = it.notes,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("customers", System.currentTimeMillis()))
    }

    private suspend fun pushStaff() {
        val dao = db.staffDao()
        drainOutbox(
            rows = dao.pushable(),
            markRejected = { row, msg -> dao.markRejected(row.localId, msg) },
            push = ::pushStaffOne,
        )
    }

    /**
     * `serverId` is always already set here — see [LocalStaffEntity]'s doc
     * comment on why a staff write is never a create. `pendingDelete` wins
     * over any field edits also sitting on the row: a delete is a terminal
     * state, there is nothing left to PATCH after it.
     *
     * The delete branch's `status != 404` check is the one real subtlety:
     * `DELETE /staff/users/{id}` 404s on an already-deleted user rather than
     * silently no-op'ing (backend/app/api/v1/staff/router.py delete_user), so
     * a dropped connection right after a successful delete would otherwise
     * make a clean retry look like a failure — see the entity's doc comment
     * for the full reasoning. Same pull-then-CAS-markSynced-then-
     * conditional-delete ordering as pushCustomerOne otherwise, for the same
     * reasons: `pullStaff()` before `markSynced` so a failed pull just
     * leaves the row pending for a harmless retry instead of a synced-but-
     * orphaned row; `markSynced`'s version check so a newer edit that landed
     * while this call was in flight is not silently discarded.
     */
    private suspend fun pushStaffOne(row: LocalStaffEntity) {
        val dao = db.staffDao()
        if (row.pendingDelete) {
            try {
                staffApi.delete(row.serverId)
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        } else {
            staffApi.update(
                row.serverId,
                StaffUserUpdateBody(
                    name = row.name,
                    phone = row.phone,
                    status = row.status,
                    roleCode = row.roleCode,
                ),
            )
        }
        pullStaff()
        val applied = dao.markSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSynced()
    }

    /**
     * The full staff list, wholesale-replaced — same shape as pullCustomers.
     * On-demand only (see onDemandPulls): opening Staff, a "staff" realtime
     * event, or this device's own write finishing (see pushStaffOne)
     * triggers it, not every sync().
     */
    private suspend fun pullStaff() {
        val users = staffApi.list()
        db.staffDao().replaceCache(
            users.map {
                StaffCacheEntity(
                    id = it.id,
                    email = it.email,
                    name = it.name,
                    phone = it.phone,
                    status = it.status,
                    rolesCsv = it.roles.joinToString(","),
                    lastLoginAt = it.lastLoginAt,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("staff", System.currentTimeMillis()))
    }

    /**
     * Who's currently clocked in — read-only, no outbox (see [OnShiftEntity]'s
     * doc comment on why clock-in/out can never be queued). On-demand only:
     * opening Staff, an "attendance" realtime event, or this device's own
     * clock-in/clock-out finishing (see StaffViewModel.clockIn/clockOut)
     * triggers it.
     */
    private suspend fun pullOnShift() {
        val rows = staffApi.onShift()
        db.attendanceDao().replaceOnShift(
            rows.map {
                OnShiftEntity(
                    attendanceId = it.id,
                    userId = it.userId,
                    userName = it.userName,
                    userEmail = it.userEmail,
                    branchId = it.branchId,
                    branchName = it.branchName,
                    clockInAt = it.clockInAt,
                )
            },
        )
    }

    // ---------------------------------------------------------- ingredients

    private suspend fun pushIngredients() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableIngredients(),
            markRejected = { row, msg -> dao.markIngredientRejected(row.localId, msg) },
            push = ::pushIngredientOne,
        )
    }

    /**
     * Unlike Staff, an ingredient genuinely can be created offline — there's
     * no live-round-trip block on it — so, matching Customers/Menu-category,
     * `serverId == null` means this row is still an unsynced create. Delete
     * is folded in via `pendingDelete`, same as Staff, including the same
     * 404-tolerance on the delete branch (see LocalIngredientEntity's doc
     * comment) — except here a delete can also target a row that was queued
     * and then removed before it ever reached the server at all
     * (`serverId == null`), which needs no network call, just dropping the
     * local row outright.
     */
    private suspend fun pushIngredientOne(row: LocalIngredientEntity) {
        val dao = db.inventoryDao()
        if (row.pendingDelete) {
            val serverId = row.serverId
            if (serverId == null) {
                dao.deleteLocalIngredient(row.localId)
                return
            }
            try {
                inventoryApi.deleteIngredient(serverId)
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        } else if (row.serverId == null) {
            val created = inventoryApi.createIngredient(
                IngredientCreate(
                    sku = row.sku.orEmpty(),
                    name = row.name.orEmpty(),
                    baseUnit = row.baseUnit.orEmpty(),
                    reorderThreshold = row.reorderThreshold ?: 0.0,
                    reorderQty = row.reorderQty ?: 0.0,
                ),
            )
            dao.setIngredientServerId(row.localId, created.id)
        } else {
            inventoryApi.updateIngredient(
                row.serverId,
                IngredientUpdate(
                    name = row.name.orEmpty(),
                    baseUnit = row.baseUnit.orEmpty(),
                    reorderThreshold = row.reorderThreshold ?: 0.0,
                    reorderQty = row.reorderQty ?: 0.0,
                ),
            )
        }
        pullIngredients()
        val applied = dao.markIngredientSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedIngredients()
    }

    private suspend fun pullIngredients() {
        val rows = inventoryApi.ingredients()
        db.inventoryDao().replaceIngredientCache(
            rows.map {
                IngredientCacheEntity(
                    id = it.id, sku = it.sku, name = it.name, baseUnit = it.baseUnit,
                    currentQty = it.currentQty, reorderThreshold = it.reorderThreshold,
                    reorderQty = it.reorderQty, avgCostMinor = it.avgCostMinor,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("ingredients", System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ suppliers

    private suspend fun pushSuppliers() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableSuppliers(),
            markRejected = { row, msg -> dao.markSupplierRejected(row.localId, msg) },
            push = ::pushSupplierOne,
        )
    }

    /** Same shape as pushIngredientOne — see its doc comment. */
    private suspend fun pushSupplierOne(row: LocalSupplierEntity) {
        val dao = db.inventoryDao()
        if (row.pendingDelete) {
            val serverId = row.serverId
            if (serverId == null) {
                dao.deleteLocalSupplier(row.localId)
                return
            }
            try {
                inventoryApi.deleteSupplier(serverId)
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        } else if (row.serverId == null) {
            val created = inventoryApi.createSupplier(
                SupplierBody(
                    name = row.name.orEmpty(), contact = row.contact,
                    gstin = row.gstin, paymentTerms = row.paymentTerms,
                ),
            )
            dao.setSupplierServerId(row.localId, created.id)
        } else {
            inventoryApi.updateSupplier(
                row.serverId,
                SupplierBody(
                    name = row.name.orEmpty(), contact = row.contact,
                    gstin = row.gstin, paymentTerms = row.paymentTerms,
                ),
            )
        }
        pullSuppliers()
        val applied = dao.markSupplierSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedSuppliers()
    }

    private suspend fun pullSuppliers() {
        val rows = inventoryApi.suppliers()
        db.inventoryDao().replaceSupplierCache(
            rows.map {
                SupplierCacheEntity(
                    id = it.id, name = it.name, contact = it.contact,
                    gstin = it.gstin, paymentTerms = it.paymentTerms,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("suppliers", System.currentTimeMillis()))
    }

    private suspend fun pullInventoryOnDemand() {
        pullIngredients()
        pullSuppliers()
    }

    /**
     * FIFO batches for one ingredient — demand-loaded on selection, not part
     * of [onDemandPulls] (that map is keyed by realtime *resource* name, this
     * is keyed by which ingredient a screen currently has open). Called
     * directly by InventoryViewModel.select().
     */
    suspend fun pullBatchesFor(ingredientId: String) {
        val rows = inventoryApi.batches(ingredientId)
        db.inventoryDao().replaceBatchesFor(
            ingredientId,
            rows.map {
                BatchCacheEntity(
                    id = it.id, ingredientId = it.ingredientId, receivedAt = it.receivedAt,
                    expiresAt = it.expiresAt, qtyOnHand = it.qtyOnHand,
                    costPerUnitMinor = it.costPerUnitMinor, lotCode = it.lotCode,
                )
            },
        )
    }

    // -------------------------------------------------------------------- GRN

    private suspend fun pushGrns() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableGrns(),
            markRejected = { row, msg -> dao.markGrnRejected(row.localId, msg) },
            push = ::pushGrnOne,
        )
    }

    /**
     * Shape D, single call (unlike pushOne for orders, there's no second
     * chained payment call — a GRN is complete in one POST). Insert-only, so
     * unlike ingredients/suppliers there is no version-CAS on markGrnSynced:
     * nothing can amend an already-captured GRN row out from under an
     * in-flight push the way an edit can for master data.
     */
    private suspend fun pushGrnOne(grn: LocalGrnEntity) {
        val dao = db.inventoryDao()
        val lines = dao.grnLinesFor(grn.localId)
        inventoryApi.postGrn(
            GrnBody(
                branchId = grn.branchId,
                supplierId = grn.supplierId,
                supplierInvoiceNo = grn.supplierInvoiceNo,
                notes = grn.notes,
                lines = lines.map {
                    GrnLineBody(
                        ingredientId = it.ingredientId, qty = it.qty,
                        unitCostMinor = it.unitCostMinor,
                        expiresAt = it.expiresAt, lotCode = it.lotCode,
                    )
                },
            ),
            key = "grn:${grn.localId}",
        )
        // The GRN itself rewrote current_qty/avg_cost_minor server-side —
        // reflect that immediately rather than waiting for the next
        // unrelated pull.
        pullIngredients()
        dao.markGrnSynced(grn.localId)
    }

    // ----------------------------------------------------------- adjustments

    private suspend fun pushAdjustments() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableAdjustments(),
            markRejected = { row, msg -> dao.markAdjustmentRejected(row.localId, msg) },
            push = ::pushAdjustmentOne,
        )
    }

    /** Same insert-only reasoning as pushGrnOne — no version-CAS needed. */
    private suspend fun pushAdjustmentOne(row: LocalAdjustmentEntity) {
        inventoryApi.postAdjustment(
            AdjustmentBody(
                ingredientId = row.ingredientId, branchId = row.branchId,
                qtyDelta = row.qtyDelta, type = row.type, note = row.note,
            ),
            key = "adjustment:${row.localId}",
        )
        pullIngredients()
        db.inventoryDao().markAdjustmentSynced(row.localId)
    }

    // ---------------------------------------------------------------- expenses

    private suspend fun pushExpenses() {
        val dao = db.financeDao()
        drainOutbox(
            rows = dao.pushableExpenses(),
            markRejected = { row, msg -> dao.markExpenseRejected(row.localId, msg) },
            push = ::pushExpenseOne,
        )
    }

    /** Insert-only, same reasoning as pushGrnOne/pushAdjustmentOne above — no
     * version-CAS needed: expense edit/delete stay web-only, so nothing can
     * amend this row out from under an in-flight push. */
    private suspend fun pushExpenseOne(row: LocalExpenseEntity) {
        financeApi.createExpense(
            ExpenseCreate(
                branchId = row.branchId, categoryId = row.categoryId, supplierId = row.supplierId,
                amountMinor = row.amountMinor, paidVia = row.paidVia, paidAt = row.paidAt,
                vendorName = row.vendorName, invoiceNo = row.invoiceNo, note = row.note,
            ),
            key = "expense:${row.localId}",
        )
        pullExpenses()
        db.financeDao().markExpenseSynced(row.localId)
    }

    private suspend fun pullExpenses() {
        val rows = financeApi.expenses()
        db.financeDao().replaceExpenseCache(
            rows.map {
                ExpenseCacheEntity(
                    id = it.id, branchId = it.branchId, categoryId = it.categoryId,
                    supplierId = it.supplierId, amountMinor = it.amountMinor, paidVia = it.paidVia,
                    paidAt = it.paidAt, vendorName = it.vendorName, invoiceNo = it.invoiceNo,
                    note = it.note,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("expenses", System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------- assets

    private suspend fun pushAssets() {
        val dao = db.financeDao()
        drainOutbox(
            rows = dao.pushableAssets(),
            markRejected = { row, msg -> dao.markAssetRejected(row.localId, msg) },
            push = ::pushAssetOne,
        )
    }

    /** Same insert-only reasoning as pushExpenseOne — assets have no backend
     * edit/delete endpoint at all, so there is nothing to guard against. */
    private suspend fun pushAssetOne(row: LocalAssetEntity) {
        financeApi.createAsset(
            AssetCreate(
                branchId = row.branchId, name = row.name, type = row.type,
                purchaseMinor = row.purchaseMinor, purchaseDate = row.purchaseDate,
                usefulLifeMonths = row.usefulLifeMonths, salvageMinor = row.salvageMinor,
                notes = row.notes,
            ),
            key = "asset:${row.localId}",
        )
        pullAssets()
        db.financeDao().markAssetSynced(row.localId)
    }

    private suspend fun pullAssets() {
        val rows = financeApi.assets()
        db.financeDao().replaceAssetCache(
            rows.map {
                AssetCacheEntity(
                    id = it.id, branchId = it.branchId, name = it.name, type = it.type,
                    purchaseMinor = it.purchaseMinor, purchaseDate = it.purchaseDate,
                    usefulLifeMonths = it.usefulLifeMonths, salvageMinor = it.salvageMinor,
                    depreciationMethod = it.depreciationMethod, notes = it.notes,
                    accumulatedDepreciationMinor = it.accumulatedDepreciationMinor,
                    bookValueMinor = it.bookValueMinor,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("assets", System.currentTimeMillis()))
    }

    // ---------------------------------------------------------- capital entries

    private suspend fun pushCapitalEntries() {
        val dao = db.financeDao()
        drainOutbox(
            rows = dao.pushableCapitalEntries(),
            markRejected = { row, msg -> dao.markCapitalEntryRejected(row.localId, msg) },
            push = ::pushCapitalEntryOne,
        )
    }

    /** Same insert-only reasoning — a capital entry is void-only after
     * creation, and void stays online-only (direct write, not queued). */
    private suspend fun pushCapitalEntryOne(row: LocalCapitalEntryEntity) {
        financeApi.createCapitalEntry(
            CapitalEntryCreate(
                partnerId = row.partnerId, type = row.type, amountMinor = row.amountMinor,
                effectiveAt = row.effectiveAt, settlementAccount = row.settlementAccount,
                sourceRef = row.sourceRef, note = row.note,
            ),
            key = "capital-entry:${row.localId}",
        )
        pullCapitalEntriesFor(row.partnerId)
        db.financeDao().markCapitalEntrySynced(row.localId)
    }

    /**
     * Per-partner, on-demand pull — called directly by FinanceViewModel on
     * partner selection, not part of [onDemandPulls] (that map is keyed by
     * realtime *resource* name, this is keyed by which partner's capital
     * history a screen currently has open). Same shape as pullBatchesFor.
     */
    suspend fun pullCapitalEntriesFor(partnerId: String) {
        val rows = financeApi.capitalEntries(partnerId)
        db.financeDao().replaceCapitalEntriesFor(
            partnerId,
            rows.map {
                CapitalEntryCacheEntity(
                    id = it.id, partnerId = it.partnerId, type = it.type,
                    amountMinor = it.amountMinor, effectiveAt = it.effectiveAt,
                    settlementAccount = it.settlementAccount, sourceRef = it.sourceRef,
                    note = it.note, createdByName = it.createdByName, createdAt = it.createdAt,
                    voidedAt = it.voidedAt, voidReason = it.voidReason, isVoided = it.isVoided,
                )
            },
        )
    }

    private suspend fun pullFinanceOnDemand() {
        pullExpenses()
        pullAssets()
    }

    // -------------------------------------------------------------- events

    private suspend fun pullEventsData() {
        val rows = eventsApi.listAll()
        db.eventDao().replaceEventCache(
            rows.map {
                EventCacheEntity(
                    id = it.id, name = it.name, description = it.description,
                    eventType = it.eventType, screen = it.screen, startsAt = it.startsAt,
                    endsAt = it.endsAt, capacity = it.capacity, sold = it.sold,
                    remaining = it.remaining, baseTicketPriceMinor = it.baseTicketPriceMinor,
                    sacCode = it.sacCode, taxRate = it.taxRate, status = it.status,
                    posterUrl = it.posterUrl,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("events", System.currentTimeMillis()))
    }

    /**
     * Per-event ticket list — demand-loaded on selection, not part of
     * [onDemandPulls] (that map is keyed by realtime *resource* name, this
     * is keyed by which event a screen currently has open). Called
     * directly by EventsViewModel.selectEvent().
     */
    suspend fun pullTicketsFor(eventId: String) {
        val rows = eventsApi.listTickets(eventId)
        db.eventDao().replaceTicketsFor(
            eventId,
            rows.map {
                EventTicketCacheEntity(
                    id = it.id, eventId = it.eventId, ticketNo = it.ticketNo,
                    eventName = it.eventName, customerName = it.customerName,
                    customerPhone = it.customerPhone, seat = it.seat,
                    pricePaidMinor = it.pricePaidMinor, status = it.status,
                    checkedInAt = it.checkedInAt,
                )
            },
        )
    }

    private suspend fun pushTicketSales() {
        val dao = db.eventDao()
        drainOutbox(
            rows = dao.pushableTicketSales(),
            markRejected = { row, msg -> dao.markTicketSaleRejected(row.localId, msg) },
            push = ::pushTicketSaleOne,
        )
    }

    /**
     * Insert-only, same reasoning as pushGrnOne/pushAdjustmentOne — no
     * version-CAS needed: a ticket sale is never edited, only ever queued
     * once. Refreshes the event (sold/remaining) and this event's ticket
     * list immediately, same as GRN refreshing ingredients after posting.
     */
    private suspend fun pushTicketSaleOne(row: LocalTicketSaleEntity) {
        eventsApi.sellTickets(
            row.eventId,
            TicketSell(
                customerName = row.customerName, customerPhone = row.customerPhone,
                seat = row.seat, qty = row.qty, note = row.note,
            ),
            key = "ticket-sale:${row.localId}",
        )
        pullEventsData()
        pullTicketsFor(row.eventId)
        db.eventDao().markTicketSaleSynced(row.localId)
    }

    private suspend fun pushCheckIns() {
        val dao = db.eventDao()
        drainOutbox(
            rows = dao.pushableCheckIns(),
            markRejected = { row, msg -> dao.markCheckInRejected(row.localId, msg) },
            push = ::pushCheckInOne,
        )
    }

    /** Shape C — targets an existing server ticket id, no CAS needed since
     * a check-in is a one-way transition the server itself already guards
     * (re-checking-in an already-checked-in ticket is a clean 4xx, not a
     * silent double-effect). */
    private suspend fun pushCheckInOne(row: LocalCheckInEntity) {
        eventsApi.checkIn(row.eventId, row.ticketId)
        pullTicketsFor(row.eventId)
        db.eventDao().markCheckInSynced(row.localId)
    }

    // --------------------------------------------------------- memberships

    private suspend fun pullTiers() {
        val rows = membershipsApi.listTiers()
        db.membershipDao().replaceTierCache(
            rows.map {
                MembershipTierCacheEntity(
                    id = it.id, code = it.code, name = it.name,
                    monthlyPriceMinor = it.monthlyPriceMinor, annualPriceMinor = it.annualPriceMinor,
                    foodDiscountPct = it.foodDiscountPct, gamingDiscountPct = it.gamingDiscountPct,
                    hookahDiscountPct = it.hookahDiscountPct, pointMultiplier = it.pointMultiplier,
                    freeGamingMinutesPerWeek = it.freeGamingMinutesPerWeek,
                    freeHookahPerMonth = it.freeHookahPerMonth, priorityBooking = it.priorityBooking,
                    description = it.description, sortOrder = it.sortOrder,
                )
            },
        )
        db.syncMetaDao().put(SyncMetaEntity("memberships", System.currentTimeMillis()))
    }

    /**
     * Per-customer active-membership lookup — demand-loaded on selection,
     * not part of [onDemandPulls] (that map is keyed by realtime *resource*
     * name, this is keyed by which customer a screen currently has open).
     * Called directly by MembershipsViewModel.selectCustomer(). A null
     * response (no active membership) clears this customer's cache row
     * rather than leaving a stale one showing.
     */
    suspend fun pullMembershipFor(customerId: String) {
        val sub = membershipsApi.getCustomerSubscription(customerId)
        db.membershipDao().replaceMembershipFor(
            customerId,
            listOfNotNull(sub).map {
                CustomerMembershipCacheEntity(
                    id = it.id, customerId = it.customerId, tierId = it.tierId,
                    tierCode = it.tierCode, tierName = it.tierName, billingCycle = it.billingCycle,
                    startsAt = it.startsAt, expiresAt = it.expiresAt, cancelledAt = it.cancelledAt,
                    autoRenew = it.autoRenew, amountPaidMinor = it.amountPaidMinor, isActive = it.isActive,
                )
            },
        )
    }

    private suspend fun pushSubscriptions() {
        val dao = db.membershipDao()
        drainOutbox(
            rows = dao.pushableSubscriptions(),
            markRejected = { row, msg -> dao.markSubscriptionRejected(row.localId, msg) },
            push = ::pushSubscriptionOne,
        )
    }

    /** Insert-only, same reasoning as pushTicketSaleOne — no version-CAS
     * needed: a subscribe is never edited, only ever queued once. */
    private suspend fun pushSubscriptionOne(row: LocalSubscriptionEntity) {
        membershipsApi.subscribe(
            SubscribeRequest(
                customerId = row.customerId, tierId = row.tierId,
                billingCycle = row.billingCycle, paidVia = row.paidVia,
            ),
            key = "membership-subscribe:${row.localId}",
        )
        pullMembershipFor(row.customerId)
        db.membershipDao().markSubscriptionSynced(row.localId)
    }

    private suspend fun pushCancellations() {
        val dao = db.membershipDao()
        drainOutbox(
            rows = dao.pushableCancellations(),
            markRejected = { row, msg -> dao.markCancellationRejected(row.localId, msg) },
            push = ::pushCancellationOne,
        )
    }

    /** Shape C — targets an existing server subscription id, no CAS needed
     * since a cancel is a one-way transition the server itself already
     * guards (re-cancelling an already-cancelled subscription is a clean
     * 4xx, not a silent double-effect), same as Events' check-in. */
    private suspend fun pushCancellationOne(row: LocalMembershipCancellationEntity) {
        membershipsApi.cancel(row.subscriptionId)
        pullMembershipFor(row.customerId)
        db.membershipDao().markCancellationSynced(row.localId)
    }

    // ------------------------------------------------------------ settings

    /** One combined pull for company/branches/terminals — mirrors the
     * backend's own settings routes all broadcasting as a single realtime
     * resource (see onDemandPulls). */
    private suspend fun pullSettingsData() {
        pullCompany()
        pullBranches()
        pullTerminals()
    }

    private suspend fun pullCompany() {
        val c = settingsApi.company()
        db.settingsDao().upsertCompany(
            CompanyCacheEntity(
                id = c.id, name = c.name, legalName = c.legalName, currency = c.currency,
                timezone = c.timezone, country = c.country, gstin = c.gstin, pan = c.pan,
                gstRegistrationType = c.gstRegistrationType, isComposition = c.isComposition,
                eInvoicingEnabled = c.eInvoicingEnabled,
                fiscalYearStartMonth = c.fiscalYearStartMonth, upiVpa = c.upiVpa,
            ),
        )
    }

    private suspend fun pullBranches() {
        val rows = settingsApi.branches()
        db.settingsDao().replaceBranchCache(
            rows.map {
                BranchCacheEntity(
                    id = it.id, name = it.name, code = it.code, address = it.address,
                    timezone = it.timezone, opensAt = it.opensAt, closesAt = it.closesAt,
                    stateCode = it.stateCode, fssaiLicenseNo = it.fssaiLicenseNo,
                    tradeLicenseNo = it.tradeLicenseNo, branchGstin = it.branchGstin,
                )
            },
        )
    }

    private suspend fun pullTerminals() {
        val rows = settingsApi.terminals()
        db.settingsDao().replaceTerminalCache(
            rows.map {
                TerminalCacheEntity(
                    id = it.id, branchId = it.branchId, name = it.name,
                    deviceId = it.deviceId, lastSeenAt = it.lastSeenAt,
                )
            },
        )
    }

    /** Shape C — no Idempotency-Key needed, "set fields to X" is naturally
     * safe to retry (see SettingsApi's class doc). */
    private suspend fun pushCompanyEdit() {
        val dao = db.settingsDao()
        drainOutbox(
            rows = listOfNotNull(dao.pushableCompanyEdit()),
            markRejected = { row, msg -> dao.markCompanyEditRejected(row.localId, msg) },
            push = ::pushCompanyEditOne,
        )
    }

    private suspend fun pushCompanyEditOne(row: LocalCompanyEditEntity) {
        settingsApi.updateCompany(
            CompanyUpdateBody(
                name = row.name, legalName = row.legalName, timezone = row.timezone,
                gstin = row.gstin, pan = row.pan, gstRegistrationType = row.gstRegistrationType,
                isComposition = row.isComposition, eInvoicingEnabled = row.eInvoicingEnabled,
                upiVpa = row.upiVpa,
            ),
            "settings-company:${row.localId}",
        )
        pullCompany()
        db.settingsDao().markCompanyEditSynced(row.localId)
    }

    /** Shape D — mandatory Idempotency-Key, no natural key a duplicate
     * retry could safely collide against (see SettingsApi's class doc). */
    private suspend fun pushBranches() {
        val dao = db.settingsDao()
        drainOutbox(
            rows = dao.pushableBranches(),
            markRejected = { row, msg -> dao.markBranchRejected(row.localId, msg) },
            push = ::pushBranchOne,
        )
    }

    private suspend fun pushBranchOne(row: LocalBranchEntity) {
        settingsApi.createBranch(
            BranchWriteBody(
                name = row.name, code = row.code, address = row.address, timezone = row.timezone,
                opensAt = row.opensAt, closesAt = row.closesAt, stateCode = row.stateCode,
                fssaiLicenseNo = row.fssaiLicenseNo, tradeLicenseNo = row.tradeLicenseNo,
                branchGstin = row.branchGstin,
            ),
            "settings-branch:${row.localId}",
        )
        pullBranches()
        db.settingsDao().markBranchSynced(row.localId)
    }

    private suspend fun pushTerminals() {
        val dao = db.settingsDao()
        drainOutbox(
            rows = dao.pushableTerminals(),
            markRejected = { row, msg -> dao.markTerminalRejected(row.localId, msg) },
            push = ::pushTerminalOne,
        )
    }

    private suspend fun pushTerminalOne(row: LocalTerminalEntity) {
        settingsApi.createTerminal(
            TerminalCreateBody(branchId = row.branchId, name = row.name, deviceId = row.deviceId),
            "settings-terminal:${row.localId}",
        )
        pullTerminals()
        db.settingsDao().markTerminalSynced(row.localId)
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
                    type = it.type,
                    basePriceMinor = it.basePriceMinor,
                    taxRate = it.taxRate,
                    hsnCode = it.hsnCode,
                    priceIncludesTax = it.priceIncludesTax,
                    isAvailable = it.isAvailable,
                    description = it.description,
                )
            },
            categories = categories.map {
                MenuCategoryEntity(id = it.id, name = it.name, sortOrder = it.sortOrder)
            },
        )
    }

    /**
     * Categories have no price fields, so unlike items they're fully
     * offline-capable — same shape and same ordering rationale as
     * pushCustomerOne (see its doc comment): setServerId lands independent
     * of state so a just-created category is never briefly absent from both
     * the local override and the cache; the cache pull runs before
     * markSynced so a dropped connection right after a successful write
     * leaves the row pending-with-known-serverId for a clean retry instead
     * of an invisible orphan; markSynced only applies if version still
     * matches what was actually pushed, so a re-edit landing while this
     * exact push was in flight isn't silently discarded.
     */
    private suspend fun pushMenuCategories() {
        val dao = db.menuWriteDao()
        drainOutbox(
            rows = dao.pushableCategories(),
            markRejected = { row, msg -> dao.markCategoryRejected(row.localId, msg) },
            push = ::pushMenuCategoryOne,
        )
    }

    private suspend fun pushMenuCategoryOne(row: LocalMenuCategoryEntity) {
        val dao = db.menuWriteDao()
        val server = if (row.serverId == null) {
            menuApi.createCategory(
                CategoryCreateBody(name = row.name!!, sortOrder = row.sortOrder ?: 0),
                "menu-category:${row.localId}",
            )
        } else {
            menuApi.updateCategory(
                row.serverId,
                CategoryUpdateBody(name = row.name, sortOrder = row.sortOrder),
            )
        }
        dao.setCategoryServerId(row.localId, server.id)
        pullMenu()
        val applied = dao.markCategorySynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedCategories()
    }

    /**
     * No id-resolution, no create leg — an item's `serverId` is always
     * already known (see [LocalMenuItemEntity]'s doc comment), so this is
     * the simplest push in the whole engine: one PATCH, then refresh.
     */
    private suspend fun pushMenuItems() {
        val dao = db.menuWriteDao()
        drainOutbox(
            rows = dao.pushableItems(),
            markRejected = { row, msg -> dao.markItemRejected(row.localId, msg) },
            push = ::pushMenuItemOne,
        )
    }

    private suspend fun pushMenuItemOne(row: LocalMenuItemEntity) {
        val dao = db.menuWriteDao()
        menuApi.updateItemDetails(
            row.serverId,
            ItemDetailsUpdateBody(
                categoryId = row.categoryId,
                name = row.name,
                description = row.description,
                isAvailable = row.isAvailable,
            ),
        )
        pullMenu()
        val applied = dao.markItemSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedItems()
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

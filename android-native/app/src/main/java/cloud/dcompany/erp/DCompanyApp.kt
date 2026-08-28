package cloud.dcompany.erp

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.room.Room
import cloud.dcompany.erp.core.alarm.GamingAlarmReconciler
import cloud.dcompany.erp.core.alarm.HeldOrderAlarmReconciler
import cloud.dcompany.erp.core.alarm.OperationalNotificationRouteStore
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.OutboxOwnerStore
import cloud.dcompany.erp.core.auth.OutboxSafetyGate
import cloud.dcompany.erp.core.auth.ShiftCache
import cloud.dcompany.erp.core.auth.TerminalStore
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.db.ALL_MIGRATIONS
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.SHIFT_CLOSING_WRITE_GUARD_CALLBACK
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ClientCompatibilityGate
import cloud.dcompany.erp.core.sync.ConnectivityObserver
import cloud.dcompany.erp.core.sync.BackgroundSyncScheduler
import cloud.dcompany.erp.core.sync.RealtimeClient
import cloud.dcompany.erp.core.sync.RealtimeEvent
import cloud.dcompany.erp.core.sync.RealtimeRefreshPolicy
import cloud.dcompany.erp.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DCompanyApp : Application() {

    companion object {
        /**
         * Importance is fixed when a channel is first created and cannot be
         * raised later — recreating with the same id is a no-op. If this ever
         * needs to change, the id must change with it.
         */
        const val ALARM_CHANNEL_ID = "dcompany_alarms_v1"

        lateinit var instance: DCompanyApp
            private set
    }

    lateinit var tokens: TokenStore
        private set
    lateinit var shiftCache: ShiftCache
        private set
    lateinit var terminalStore: TerminalStore
        private set
    lateinit var outboxOwnerStore: OutboxOwnerStore
        private set
    lateinit var outboxSafety: OutboxSafetyGate
        private set
    lateinit var cacheIsolation: CacheIsolationCoordinator
        private set
    lateinit var db: ErpDatabase
        private set
    lateinit var sync: SyncEngine
        private set
    internal lateinit var connectivity: ConnectivityObserver
        private set
    lateinit var realtime: RealtimeClient
        private set
    lateinit var clientCompatibility: ClientCompatibilityGate
        private set
    internal lateinit var notificationRoutes: OperationalNotificationRouteStore
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationRoutes = OperationalNotificationRouteStore(this)

        tokens = TokenStore(this)
        shiftCache = ShiftCache(this)
        // Blocking here is deliberate and bounded: a single small disk read,
        // and every screen downstream assumes the session is known.
        terminalStore = TerminalStore(this)
        outboxOwnerStore = OutboxOwnerStore(this)
        runBlocking {
            tokens.load()
            shiftCache.loadProfile()
            terminalStore.load()
            outboxOwnerStore.load()
        }
        ApiClient.init(tokens, terminalStore)
        clientCompatibility = ClientCompatibilityGate(
            checkCompatibility = {
                ApiClient.api.clientCompatibility(
                    platform = "android",
                    versionCode = BuildConfig.VERSION_CODE,
                )
            },
        )
        ApiClient.onUpdateRequired = clientCompatibility::requireUpdate

        db = Room.databaseBuilder(this, ErpDatabase::class.java, "dcompany.db")
            // No destructive fallback: this database holds captured sales that
            // exist nowhere else until they sync. Wiping it on a schema change
            // would destroy real money. Any future version needs a migration.
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(SHIFT_CLOSING_WRITE_GUARD_CALLBACK)
            .build()

        outboxSafety = OutboxSafetyGate(db, outboxOwnerStore, tokens)
        cacheIsolation = CacheIsolationCoordinator(this, db)
        sync = SyncEngine(
            db = db,
            scope = appScope,
            outboxSafety = outboxSafety,
            cacheIsolation = cacheIsolation,
            scheduleDurableSync = { BackgroundSyncScheduler.enqueue(this) },
        )
        realtime = RealtimeClient(tokens, appScope)
        connectivity = ConnectivityObserver(
            context = this,
            scope = appScope,
            backendReachability = ApiClient.backendReachability.state,
        )
        // Draining the queue the instant the link returns is the whole point:
        // staff should never have to remember to press a sync button.
        connectivity.start {
            if (tokens.hasSession() && cacheIsolation.isReady()) {
                sync.requestSync()
                realtime.connect()
            }
        }

        // The one place a "changed" event turns into an action: pull every
        // cache affected by that resource and, except for KDS's dedicated
        // narrow outbox, use the live link as an opportunity to drain saved
        // work. A reconnect after a real gap refreshes every on-demand
        // resource because anything could have changed while this device
        // wasn't listening.
        appScope.launch {
            realtime.changes.collect { event ->
                if (!cacheIsolation.isReady()) return@collect
                when (event) {
                    is RealtimeEvent.Changed -> {
                        // KDS owns a dedicated durable outbox and scoped sync
                        // pass. Its realtime event only invalidates Kitchen
                        // and Tables caches; draining every unrelated outbox
                        // here would turn one cook's tap into a broad sync on
                        // every connected tablet.
                        if (RealtimeRefreshPolicy.requiresBroadOutboxDrain(event.resource)) {
                            sync.requestSync()
                        }
                        sync.refreshRealtime(event.resource)
                    }
                    RealtimeEvent.ReconnectedAfterGap -> {
                        sync.requestSync()
                        sync.refreshAllOnDemand()
                    }
                }
            }
        }

        // Bounded and non-destructive: a definitive old-build result blocks,
        // while timeout/offline continues with the existing local state.
        appScope.launch { clientCompatibility.checkAtStartup() }

        createAlarmChannel()
        startAlarmReconciliation()
    }

    /**
     * Room changes are cancellation signals too: a paid/voided/removed order
     * or ended session must withdraw its alarm without that feature screen
     * being open. Scope readiness prevents scheduling an old employee's cache
     * during cold-start isolation.
     */
    private fun startAlarmReconciliation() {
        appScope.launch {
            combine(
                db.heldOrderDao().observeAll(),
                db.heldOrderDao().observeConfirmedTargetIds(),
            ) { orders, confirmed ->
                orders.map { Triple(it.id, it.heldAt, it.checkoutVersion) } to confirmed.sorted()
            }.distinctUntilChanged().collect {
                if (cacheIsolation.isReady()) HeldOrderAlarmReconciler.reconcile(this@DCompanyApp)
            }
        }
        appScope.launch {
            combine(
                db.gamingDao().observeSessionCache(),
                db.gamingDao().observeActiveLocalSessions(),
                db.gamingDao().observeStations(),
            ) { cache, local, stations ->
                Triple(
                    cache.map { Triple(it.id, it.status, it.timerEndsAtMillis) },
                    local.map { Triple(it.localId, it.state, it.timerEndsAtMillis) },
                    stations.map { it.id to it.name },
                )
            }.distinctUntilChanged().collect {
                if (cacheIsolation.isReady()) GamingAlarmReconciler.reconcile(this@DCompanyApp)
            }
        }
    }

    private fun createAlarmChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            "Session & order alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Gaming session overtime and held orders waiting too long."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }
}

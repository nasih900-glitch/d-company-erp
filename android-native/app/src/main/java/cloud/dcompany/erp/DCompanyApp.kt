package cloud.dcompany.erp

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
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
import cloud.dcompany.erp.ui.screens.settings.BugReportPrivacyScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        private const val STARTUP_STATE_TRACE = "DCompany.persisted-startup-state"
        private const val PERFORMANCE_LOG_TAG = "DCompanyPerformance"
        private const val COMPATIBILITY_RECHECK_INTERVAL_MILLIS = 15L * 60L * 1_000L

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
    private var lastCompatibilityCheckAtMillis = 0L
    private val compatibilityRecheckCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = requestCompatibilityRecheck()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationRoutes = OperationalNotificationRouteStore(this)

        tokens = TokenStore(this)
        shiftCache = ShiftCache(this)
        terminalStore = TerminalStore(this)
        outboxOwnerStore = OutboxOwnerStore(this)
        loadPersistedStartupState()
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
        BugReportPrivacyScheduler.ensureScheduled(this)

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
        lastCompatibilityCheckAtMillis = SystemClock.elapsedRealtime()
        appScope.launch { clientCompatibility.checkAtStartup() }
        registerActivityLifecycleCallbacks(compatibilityRecheckCallbacks)

        createAlarmChannel()
        startAlarmReconciliation()
    }

    /**
     * Authentication and cache ownership must be published before any API,
     * worker or screen can observe them. Keep that ordering, but do the four
     * independent disk reads concurrently on the IO pool rather than running
     * sequential DataStore work on Android's main thread.
     *
     * The trace section is visible in Perfetto and the debug timing gives QA a
     * stable cold-start signal without collecting employee or business data.
     */
    private fun loadPersistedStartupState() {
        val startedAt = SystemClock.elapsedRealtime()
        Trace.beginSection(STARTUP_STATE_TRACE)
        try {
            runBlocking(Dispatchers.IO) {
                listOf(
                    async { tokens.load() },
                    async { shiftCache.loadProfile() },
                    async { terminalStore.load() },
                    async { outboxOwnerStore.load() },
                ).awaitAll()
            }
        } finally {
            Trace.endSection()
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                PERFORMANCE_LOG_TAG,
                "$STARTUP_STATE_TRACE completed in " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms",
            )
        }
    }

    /**
     * A foreground return is a natural, low-noise opportunity to pick up a new
     * server release manifest. The gate stays in its current state while this
     * runs, and the throttle prevents rapid app switching from causing a burst
     * of compatibility traffic.
     */
    private fun requestCompatibilityRecheck() {
        if (!connectivity.online.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCompatibilityCheckAtMillis < COMPATIBILITY_RECHECK_INTERVAL_MILLIS) return
        lastCompatibilityCheckAtMillis = now
        appScope.launch { clientCompatibility.recheckNonBlocking() }
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

package cloud.dcompany.erp

import android.annotation.SuppressLint
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
import cloud.dcompany.erp.core.diagnostics.DiagnosticConnectivity
import cloud.dcompany.erp.core.diagnostics.DiagnosticsRuntime
import cloud.dcompany.erp.core.diagnostics.SyncHealthSample
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ClientCompatibilityGate
import cloud.dcompany.erp.core.net.ConnectivityObserver
import cloud.dcompany.erp.core.net.ClientCompatibilityState
import cloud.dcompany.erp.core.net.ClientUpdateRequirementStore
import cloud.dcompany.erp.core.sync.BackgroundSyncScheduler
import cloud.dcompany.erp.core.sync.RealtimeClient
import cloud.dcompany.erp.core.sync.RealtimeEvent
import cloud.dcompany.erp.core.sync.RealtimeRefreshPolicy
import cloud.dcompany.erp.core.sync.SyncEngine
import cloud.dcompany.erp.core.remote.RemoteAssistanceCoordinator
import cloud.dcompany.erp.core.sync.summarizeOutboxWork
import cloud.dcompany.erp.core.update.UpdateTelemetryCoordinator
import cloud.dcompany.erp.ui.screens.settings.BugReportPrivacyScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun compatibilityCheckIsDue(
    nowElapsedMillis: Long,
    lastCheckElapsedMillis: Long,
    intervalMillis: Long,
): Boolean {
    require(intervalMillis > 0)
    return nowElapsedMillis >= lastCheckElapsedMillis &&
        nowElapsedMillis - lastCheckElapsedMillis >= intervalMillis
}

internal fun nextCompatibilityDelayMillis(
    nowElapsedMillis: Long,
    lastCheckElapsedMillis: Long,
    intervalMillis: Long,
): Long {
    require(intervalMillis > 0)
    if (nowElapsedMillis < lastCheckElapsedMillis) return intervalMillis
    val elapsed = nowElapsedMillis - lastCheckElapsedMillis
    return if (elapsed >= intervalMillis) intervalMillis else intervalMillis - elapsed
}

/**
 * Thread-safe admission control shared by startup, foreground and reconnect
 * compatibility checks. Recording before a coroutine is launched prevents two
 * Android network callbacks from queueing duplicate requests behind the
 * compatibility gate's network mutex.
 */
internal class CompatibilityRecheckThrottle {
    private val lock = Any()
    private var lastAttemptElapsedMillis: Long? = null

    fun recordAttempt(nowElapsedMillis: Long) {
        require(nowElapsedMillis >= 0)
        synchronized(lock) {
            lastAttemptElapsedMillis = nowElapsedMillis
        }
    }

    fun claimIfDue(nowElapsedMillis: Long, minimumGapMillis: Long): Boolean {
        require(nowElapsedMillis >= 0)
        require(minimumGapMillis > 0)
        return synchronized(lock) {
            val previous = lastAttemptElapsedMillis
            when {
                previous == null -> true
                nowElapsedMillis < previous -> false
                nowElapsedMillis - previous < minimumGapMillis -> false
                else -> true
            }.also { claimed ->
                // Treat an elapsed-realtime reset as a new baseline. This is
                // defensive for tests/vendor clock bugs; elapsedRealtime does
                // not normally move backwards within one Android process.
                if (claimed || previous == null || nowElapsedMillis < previous) {
                    lastAttemptElapsedMillis = nowElapsedMillis
                }
            }
        }
    }

    fun nextDelayMillis(nowElapsedMillis: Long, intervalMillis: Long): Long {
        require(nowElapsedMillis >= 0)
        require(intervalMillis > 0)
        return synchronized(lock) {
            val previous = lastAttemptElapsedMillis ?: return@synchronized intervalMillis
            nextCompatibilityDelayMillis(
                nowElapsedMillis = nowElapsedMillis,
                lastCheckElapsedMillis = previous,
                intervalMillis = intervalMillis,
            )
        }
    }

    /** Delay before a new attempt may be claimed; zero means it may run now. */
    fun delayUntilClaimAllowed(nowElapsedMillis: Long, minimumGapMillis: Long): Long {
        require(nowElapsedMillis >= 0)
        require(minimumGapMillis > 0)
        return synchronized(lock) {
            val previous = lastAttemptElapsedMillis ?: return@synchronized 0L
            if (nowElapsedMillis < previous) {
                lastAttemptElapsedMillis = nowElapsedMillis
                return@synchronized minimumGapMillis
            }
            (minimumGapMillis - (nowElapsedMillis - previous)).coerceAtLeast(0L)
        }
    }
}

/** Pure start/stop accounting prevents overlapping periodic jobs in multi-window mode. */
internal class ForegroundActivityTracker {
    private var startedCount = 0

    fun onStarted(): Boolean {
        startedCount += 1
        return startedCount == 1
    }

    fun onStopped(): Boolean {
        if (startedCount == 0) return false
        startedCount -= 1
        return startedCount == 0
    }
}

private data class GamingAlarmCacheFingerprint(
    val id: String,
    val stationId: String,
    val status: String,
    val timerEndsAtMillis: Long?,
)

private data class GamingAlarmLocalFingerprint(
    val localId: String,
    val serverId: String?,
    val stationId: String,
    val state: String,
    val timerEndsAtMillis: Long?,
)

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
        // Matches the bounded compatibility request. It suppresses the pair of
        // Android callbacks generated by one recovery without delaying a
        // genuinely later successful reconnect by fifteen minutes.
        private const val RECONNECT_RECHECK_THROTTLE_MILLIS = 3_000L
        private const val DIAGNOSTIC_SYNC_SAMPLE_MILLIS = 60_000L

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
    internal lateinit var remoteAssistance: RemoteAssistanceCoordinator
        private set
    lateinit var clientCompatibility: ClientCompatibilityGate
        private set
    internal lateinit var updateTelemetry: UpdateTelemetryCoordinator
        private set
    internal lateinit var notificationRoutes: OperationalNotificationRouteStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationalAlarmReconciliationGeneration = MutableStateFlow(0L)
    private val compatibilityRecheckThrottle = CompatibilityRecheckThrottle()
    private val reconnectCompatibilityLock = Any()
    private var reconnectCompatibilityJob: Job? = null
    private val foregroundLock = Any()
    private val foregroundActivities = ForegroundActivityTracker()
    private var foregroundMaintenanceJob: Job? = null
    private var foregroundDiagnosticJob: Job? = null
    private val compatibilityRecheckCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            appScope.launch { updateTelemetry.reconcileInstallerReturn() }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
            synchronized(foregroundLock) {
                if (foregroundActivities.onStarted()) {
                    remoteAssistance.onAppForegrounded()
                    startForegroundMaintenanceLocked()
                    startForegroundDiagnosticSamplingLocked()
                }
            }
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
            synchronized(foregroundLock) {
                if (foregroundActivities.onStopped()) {
                    remoteAssistance.onAppBackgrounded()
                    foregroundMaintenanceJob?.cancel()
                    foregroundMaintenanceJob = null
                    foregroundDiagnosticJob?.cancel()
                    foregroundDiagnosticJob = null
                }
            }
        }
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
        val updateRequirementStore = ClientUpdateRequirementStore(
            context = this,
            installedVersionCode = BuildConfig.VERSION_CODE,
        )
        clientCompatibility = ClientCompatibilityGate(
            checkCompatibility = {
                ApiClient.api.clientCompatibility(
                    platform = "android",
                    versionCode = BuildConfig.VERSION_CODE,
                )
            },
            initialRequiredNotice = updateRequirementStore.restore(),
            persistRequiredNotice = { notice ->
                if (!updateRequirementStore.persist(notice)) {
                    Log.e(
                        "DCompanyUpdate",
                        "Could not persist the server-required update block.",
                    )
                }
            },
            clearRequiredNotice = updateRequirementStore::clearIfPolicyRevision,
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
        updateTelemetry = UpdateTelemetryCoordinator(
            context = this,
            tokens = tokens,
            cacheIsolation = cacheIsolation,
            db = db,
        )
        updateTelemetry.installation.observeInstalledVersion(
            currentVersionCode = BuildConfig.VERSION_CODE,
            currentVersionName = BuildConfig.VERSION_NAME,
            installedOverExistingApp = installedOverExistingApp(),
        )
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
            backendEvents = ApiClient.backendReachability.events,
            readinessProbe = ApiClient::probeBackendReadiness,
        )
        DiagnosticsRuntime.install(
            application = this,
            accessTokenProvider = tokens::accessToken,
            verifiedScopeProvider = { cacheIsolation.currentLease()?.scope },
            connectivityProvider = {
                when (connectivity.presentation.value.phase) {
                    cloud.dcompany.erp.core.net.ConnectivityPhase.ONLINE ->
                        DiagnosticConnectivity.ONLINE
                    cloud.dcompany.erp.core.net.ConnectivityPhase.NO_NETWORK ->
                        DiagnosticConnectivity.OFFLINE
                    cloud.dcompany.erp.core.net.ConnectivityPhase.VERIFYING,
                    cloud.dcompany.erp.core.net.ConnectivityPhase.SERVER_UNREACHABLE,
                    cloud.dcompany.erp.core.net.ConnectivityPhase.RECOVERING ->
                        DiagnosticConnectivity.UNKNOWN
                }
            },
        )
        remoteAssistance = RemoteAssistanceCoordinator(
            context = this,
            scope = appScope,
            cacheIsolation = cacheIsolation,
            realtime = realtime,
            online = connectivity.online,
            installationIdentity = updateTelemetry.installation,
            collectDiagnostics = ::collectRemoteDiagnostics,
        )
        startDiagnosticSyncObservation()

        // Establish the startup attempt before ConnectivityObserver publishes
        // its initial online edge. Otherwise an already-connected launch can
        // enqueue a reconnect recheck and a startup check back-to-back.
        compatibilityRecheckThrottle.recordAttempt(SystemClock.elapsedRealtime())
        appScope.launch { clientCompatibility.checkAtStartup() }

        // Draining the queue the instant the link returns is the whole point:
        // staff should never have to remember to press a sync button.
        connectivity.start(
            onValidatedReconnect = ::requestCompatibilityRecheckAfterReconnect,
            onBackOnline = {
                DiagnosticsRuntime.requestDelivery()
                if (tokens.hasSession() && cacheIsolation.isReady()) {
                    sync.requestSync()
                    realtime.connect()
                    appScope.launch { updateTelemetry.heartbeat() }
                }
            },
        )

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
        appScope.launch {
            clientCompatibility.state.collect { state ->
                recordCompatibilityOfferIfScoped(state)
            }
        }
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
    @SuppressLint("UnclosedTrace") // runBlocking returns on this caller thread; finally always closes it.
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
    private fun startForegroundMaintenanceLocked() {
        if (foregroundMaintenanceJob?.isActive == true) return
        foregroundMaintenanceJob = appScope.launch {
            while (isActive) {
                runForegroundMaintenance()
                delay(
                    compatibilityRecheckThrottle.nextDelayMillis(
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        intervalMillis = COMPATIBILITY_RECHECK_INTERVAL_MILLIS,
                    ),
                )
            }
        }
    }

    private fun startForegroundDiagnosticSamplingLocked() {
        if (foregroundDiagnosticJob?.isActive == true) return
        foregroundDiagnosticJob = appScope.launch {
            while (isActive) {
                recordCurrentDiagnosticSyncHealth()
                delay(DIAGNOSTIC_SYNC_SAMPLE_MILLIS)
            }
        }
    }

    private fun startDiagnosticSyncObservation() {
        appScope.launch {
            combine(
                db.outboxSafetyDao().observeUnresolvedGroups(),
                connectivity.online,
                sync.deliveryProgressMarker,
            ) { groups, online, progressMarker -> Triple(groups, online, progressMarker) }
                .collect { (groups, online, progressMarker) ->
                    if (!tokens.hasSession() || !cacheIsolation.isReady()) return@collect
                    DiagnosticsRuntime.recordSyncHealth(
                        SyncHealthSample(
                            pendingOutboxCount = summarizeOutboxWork(groups).retryableCount,
                            progressMarker = progressMarker,
                            online = online,
                        ),
                    )
                }
        }
    }

    private suspend fun recordCurrentDiagnosticSyncHealth() {
        if (!tokens.hasSession() || !cacheIsolation.isReady()) return
        val groups = db.outboxSafetyDao().unresolvedGroups()
        DiagnosticsRuntime.recordSyncHealth(
            SyncHealthSample(
                pendingOutboxCount = summarizeOutboxWork(groups).retryableCount,
                progressMarker = sync.deliveryProgressMarker.value,
                online = connectivity.online.value,
            ),
        )
    }

    private suspend fun runForegroundMaintenance() {
        if (!connectivity.online.value) return
        val now = SystemClock.elapsedRealtime()
        if (compatibilityRecheckThrottle.claimIfDue(now, COMPATIBILITY_RECHECK_INTERVAL_MILLIS)) {
            clientCompatibility.recheckNonBlocking()
        }
        if (tokens.hasSession() && cacheIsolation.isReady()) {
            updateTelemetry.heartbeat()
        }
    }

    private fun requestCompatibilityRecheckAfterReconnect() {
        val now = SystemClock.elapsedRealtime()
        synchronized(reconnectCompatibilityLock) {
            if (reconnectCompatibilityJob?.isActive == true) return
            val waitMillis = compatibilityRecheckThrottle.delayUntilClaimAllowed(
                nowElapsedMillis = now,
                minimumGapMillis = RECONNECT_RECHECK_THROTTLE_MILLIS,
            )
            reconnectCompatibilityJob = appScope.launch {
                if (waitMillis > 0) delay(waitMillis)
                val attemptAt = SystemClock.elapsedRealtime()
                if (
                    compatibilityRecheckThrottle.claimIfDue(
                        nowElapsedMillis = attemptAt,
                        minimumGapMillis = RECONNECT_RECHECK_THROTTLE_MILLIS,
                    )
                ) {
                    clientCompatibility.recheckNonBlocking()
                }
            }
        }
    }

    /** Invoked only after cache ownership and the authenticated outbox owner agree. */
    internal fun onVerifiedScopeAvailable() {
        DiagnosticsRuntime.onVerifiedScopeAvailable()
        remoteAssistance.onVerifiedScopeAvailable()
        requestOperationalAlarmReconciliation()
        appScope.launch {
            recordCurrentDiagnosticSyncHealth()
            recordCompatibilityOfferIfScoped(clientCompatibility.state.value)
            updateTelemetry.promotePendingUpgrade()
            updateTelemetry.reconcileInstallerReturn()
            if (connectivity.online.value) updateTelemetry.heartbeat()
        }
    }

    /** The existing diagnostics pipeline remains the only diagnostic payload path. */
    private suspend fun collectRemoteDiagnostics(): Boolean {
        if (!tokens.hasSession() || !cacheIsolation.isReady()) return false
        return runCatching {
            recordCurrentDiagnosticSyncHealth()
            DiagnosticsRuntime.requestDelivery()
            true
        }.getOrDefault(false)
    }

    private fun recordCompatibilityOfferIfScoped(state: ClientCompatibilityState) {
        val notice = when (state) {
            is ClientCompatibilityState.UpdateAvailable -> state.notice
            is ClientCompatibilityState.UpdateRequired -> state.notice
            ClientCompatibilityState.Checking,
            ClientCompatibilityState.Supported -> return
        }
        val versionCode = notice.latestVersionCode?.takeIf { it > BuildConfig.VERSION_CODE } ?: return
        val versionName = notice.latestVersionName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 80 }
            ?: return
        updateTelemetry.recordOffered(versionName, versionCode)
    }

    @Suppress("DEPRECATION")
    private fun installedOverExistingApp(): Boolean = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

    /**
     * Single process-wide reactive owner for held-order and Gaming alarms.
     * Room changes are cancellation signals too: a paid/voided/removed order
     * or ended session must withdraw its alarm without that feature screen
     * being open. Feature ViewModels may still request a one-shot reconcile
     * after an action, but must not collect their full UI state for this job.
     * Scope readiness prevents scheduling an old employee's cache during
     * cold-start isolation.
     */
    private fun startAlarmReconciliation() {
        appScope.launch {
            combine(
                db.heldOrderDao().observeAll(),
                db.heldOrderDao().observeConfirmedTargetIds(),
            ) { orders, confirmed ->
                orders.map { Triple(it.id, it.heldAt, it.checkoutVersion) }
                    .sortedBy { it.first } to confirmed.sorted()
            }.distinctUntilChanged()
                .combine(operationalAlarmReconciliationGeneration) { snapshot, _ -> snapshot }
                .onEach {
                    if (cacheIsolation.isReady()) {
                        check(HeldOrderAlarmReconciler.reconcile(this@DCompanyApp)) {
                            "Held-order alarm ledger could not be persisted"
                        }
                    }
                }
                .retryAlarmObservation("held-order")
                .collect { }
        }
        appScope.launch {
            combine(
                db.gamingDao().observeSessionCache(),
                db.gamingDao().observeActiveLocalSessions(),
                db.gamingDao().observeStations(),
            ) { cache, local, stations ->
                Triple(
                    cache.map {
                        GamingAlarmCacheFingerprint(
                            id = it.id,
                            stationId = it.stationId,
                            status = it.status,
                            timerEndsAtMillis = it.timerEndsAtMillis,
                        )
                    }.sortedBy { it.id },
                    local.map {
                        GamingAlarmLocalFingerprint(
                            localId = it.localId,
                            serverId = it.serverId,
                            stationId = it.stationId,
                            state = it.state,
                            timerEndsAtMillis = it.timerEndsAtMillis,
                        )
                    }.sortedBy { it.localId },
                    stations.map { it.id to it.name }.sortedBy { it.first },
                )
            }.distinctUntilChanged()
                .combine(operationalAlarmReconciliationGeneration) { snapshot, _ -> snapshot }
                .onEach {
                    if (cacheIsolation.isReady()) {
                        check(GamingAlarmReconciler.reconcile(this@DCompanyApp)) {
                            "Gaming alarm ledger could not be persisted"
                        }
                    }
                }
                .retryAlarmObservation("gaming")
                .collect { }
        }
    }

    /**
     * Room may publish its unchanged startup snapshot before authentication
     * proves which employee owns the cache. Advancing this generation makes
     * both process-owned observers replay that exact snapshot once the scope
     * is safe, while their retry operators continue to own platform failures.
     */
    internal fun requestOperationalAlarmReconciliation() {
        operationalAlarmReconciliationGeneration.update { generation ->
            if (generation == Long.MAX_VALUE) 0L else generation + 1L
        }
    }

    /**
     * Room flows are cold and immediately re-emit their current snapshot when
     * collected again. Retrying the whole observation therefore repairs both
     * an upstream Room failure and an AlarmManager/preferences failure without
     * requiring another user action or reopening a feature screen.
     */
    private fun <T> kotlinx.coroutines.flow.Flow<T>.retryAlarmObservation(
        alarmKind: String,
    ): kotlinx.coroutines.flow.Flow<T> = retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        val delayMillis = (1_000L shl attempt.coerceAtMost(5L).toInt())
            .coerceAtMost(30_000L)
        Log.e(
            PERFORMANCE_LOG_TAG,
            "$alarmKind alarm observation failed; retrying in ${delayMillis}ms",
            cause,
        )
        delay(delayMillis)
        true
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

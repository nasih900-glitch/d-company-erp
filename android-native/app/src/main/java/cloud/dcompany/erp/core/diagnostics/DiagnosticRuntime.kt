package cloud.dcompany.erp.core.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.SharedPreferencesCacheScopeMarker
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Synchronous one-event safety net for a fatal Java crash. Room/WorkManager
 * are unsafe from an uncaught-exception handler, so the handler commits one
 * already-normalised marker and immediately delegates to Android's original
 * crash handler. The next healthy process imports it into the durable outbox.
 */
internal class DiagnosticCrashMarkerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun write(event: DiagnosticEvent, localScopeHash: String?): Boolean {
        require(event.eventType == DiagnosticEventType.CRASH)
        return prefs.edit()
            .clear()
            .putString(KEY_EVENT_ID, event.clientEventId)
            .putLong(KEY_OCCURRED_AT, event.occurredAtMillis)
            .putString(KEY_REASON, event.reasonCode)
            .putString(KEY_FINGERPRINT, event.failureFingerprint)
            .putString(KEY_SCOPE_HASH, localScopeHash)
            .putString(KEY_CONNECTIVITY, event.connectivity.wireValue)
            .putString(KEY_VERSION_NAME, event.capturedVersionName)
            .putInt(KEY_VERSION_CODE, event.capturedVersionCode)
            .putInt(KEY_OS_API_LEVEL, event.capturedOsApiLevel)
            .commit()
    }

    fun peek(): CapturedCrashMarker? {
        val eventId = prefs.getString(KEY_EVENT_ID, null) ?: return null
        val marker = runCatching {
            val connectivity = prefs.getString(KEY_CONNECTIVITY, null)
                ?.let { value -> DiagnosticConnectivity.entries.firstOrNull { it.wireValue == value } }
                ?: DiagnosticConnectivity.UNKNOWN
            val event = DiagnosticEvent(
                clientEventId = eventId,
                eventType = DiagnosticEventType.CRASH,
                severity = DiagnosticSeverity.CRITICAL,
                occurredAtMillis = prefs.getLong(KEY_OCCURRED_AT, 0L),
                capturedVersionName = prefs.getString(KEY_VERSION_NAME, null).orEmpty(),
                capturedVersionCode = prefs.getInt(KEY_VERSION_CODE, 0),
                capturedOsApiLevel = prefs.getInt(KEY_OS_API_LEVEL, 0),
                component = DiagnosticComponent.APP,
                reasonCode = prefs.getString(KEY_REASON, null).orEmpty(),
                failureFingerprint = prefs.getString(KEY_FINGERPRINT, null),
                connectivity = connectivity,
            )
            val scopeHash = prefs.getString(KEY_SCOPE_HASH, null)
                ?.takeIf { SCOPE_HASH.matches(it) }
            CapturedCrashMarker(event, scopeHash)
        }.getOrNull()
        if (marker == null) prefs.edit().clear().apply()
        return marker
    }

    fun acknowledge(clientEventId: String): Boolean {
        if (prefs.getString(KEY_EVENT_ID, null) != clientEventId) return false
        return prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "dcompany_crash_marker"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_OCCURRED_AT = "occurred_at"
        const val KEY_REASON = "reason"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_SCOPE_HASH = "scope_hash"
        const val KEY_CONNECTIVITY = "connectivity"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_OS_API_LEVEL = "os_api_level"
    }
}

internal data class CapturedCrashMarker(
    val event: DiagnosticEvent,
    val localScopeHash: String?,
)

internal class DiagnosticExitLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    fun contains(sourceKey: String): Boolean = synchronized(lock) {
        sourceKey in readLocked()
    }

    fun remember(sourceKey: String): Boolean {
        if (!SCOPE_HASH.matches(sourceKey)) return false
        return synchronized(lock) {
            val keys = (readLocked() + sourceKey).takeLast(MAX_KEYS)
            prefs.edit().putString(KEY_IMPORTED_EXIT_KEYS, keys.joinToString(",")).commit()
        }
    }

    private fun readLocked(): List<String> = prefs.getString(KEY_IMPORTED_EXIT_KEYS, null)
        .orEmpty()
        .split(',')
        .filter { SCOPE_HASH.matches(it) }
        .distinct()
        .takeLast(MAX_KEYS)

    private companion object {
        const val PREFERENCES_NAME = "dcompany_diagnostic_exit_ledger"
        const val KEY_IMPORTED_EXIT_KEYS = "imported_exit_keys"
        const val MAX_KEYS = 32
    }
}

/**
 * Remembers only the hash of a scope that completed the authenticated cache
 * and outbox ownership gates. It contains no token or employee/company id.
 * Clearing is synchronous because a logout followed by process death must not
 * leave the next launch believing the old session was still active.
 */
internal class DiagnosticVerifiedScopeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun current(): String? = prefs.getString(KEY_SCOPE_HASH, null)
        ?.takeIf { SCOPE_HASH.matches(it) }

    fun remember(scopeHash: String): Boolean {
        if (!SCOPE_HASH.matches(scopeHash)) return false
        return prefs.edit().clear().putString(KEY_SCOPE_HASH, scopeHash).commit()
    }

    fun clear(): Boolean = prefs.edit().clear().commit()

    private companion object {
        const val PREFERENCES_NAME = "dcompany_diagnostic_verified_scope"
        const val KEY_SCOPE_HASH = "scope_hash"
    }
}

internal class PreviousProcessExitCapture(
    private val context: Context,
    private val ledger: DiagnosticExitLedger,
) {
    fun read(markerCrashAtMillis: Long? = null): List<DiagnosticEvent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return emptyList()
        return runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
                .mapNotNull { info -> normalizeExit(info, markerCrashAtMillis) }
        }.getOrDefault(emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    // Lint does not propagate the SDK guard through getHistoricalProcessExitReasons'
    // generic list/lambda. read() returns before this function can be reached
    // on API 26-29; keep the suppression narrow to the guarded API-30 mapper.
    @SuppressLint("NewApi")
    private fun normalizeExit(
        info: ApplicationExitInfo,
        markerCrashAtMillis: Long?,
    ): DiagnosticEvent? {
        val classification = when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> ExitClassification(
                DiagnosticEventType.ANR,
                "process_anr",
                DiagnosticSeverity.CRITICAL,
            )
            ApplicationExitInfo.REASON_CRASH -> ExitClassification(
                DiagnosticEventType.CRASH,
                "java_crash_exit",
                DiagnosticSeverity.CRITICAL,
            )
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitClassification(
                DiagnosticEventType.CRASH,
                "native_crash_exit",
                DiagnosticSeverity.CRITICAL,
            )
            ApplicationExitInfo.REASON_LOW_MEMORY -> ExitClassification(
                DiagnosticEventType.CRASH,
                "low_memory_exit",
                DiagnosticSeverity.ERROR,
            )
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitClassification(
                DiagnosticEventType.CRASH,
                "excessive_resource_exit",
                DiagnosticSeverity.ERROR,
            )
            else -> return null
        }
        val timestamp = info.timestamp.takeIf { it > 0 } ?: return null
        val sourceIdentity = sha256Hex(
            listOf(
                context.packageName,
                timestamp.toString(),
                info.reason.toString(),
                info.pid.toString(),
            ).joinToString("|"),
        )
        if (ledger.contains(sourceIdentity)) return null
        if (
            markerCrashAtMillis != null &&
            classification.eventType == DiagnosticEventType.CRASH &&
            kotlin.math.abs(timestamp - markerCrashAtMillis) <= CRASH_MARKER_MATCH_WINDOW_MILLIS
        ) {
            // The richer synchronous marker already represents this Java
            // crash. Do not create a second server incident for the same exit.
            ledger.remember(sourceIdentity)
            return null
        }
        return DiagnosticEvent(
            clientEventId = UUID.randomUUID().toString(),
            localDedupeKey = sourceIdentity,
            eventType = classification.eventType,
            severity = classification.severity,
            occurredAtMillis = timestamp,
            component = DiagnosticComponent.APP,
            reasonCode = classification.reasonCode,
            connectivity = DiagnosticConnectivity.UNKNOWN,
        )
    }

    private companion object {
        const val MAX_EXIT_REASONS = 10
        const val CRASH_MARKER_MATCH_WINDOW_MILLIS = 15_000L
    }
}

private data class ExitClassification(
    val eventType: DiagnosticEventType,
    val reasonCode: String,
    val severity: DiagnosticSeverity,
)

/** The token loader completed, including the legitimate signed-out case. */
internal data class PersistedDiagnosticStartupIdentity(
    val tokenIdentity: OutboxOwnerIdentity?,
)

/** Retains the first completed token load across a retry of another startup store. */
internal class PersistedDiagnosticStartupIdentityCapture {
    private val lock = Any()
    @Volatile private var captured: PersistedDiagnosticStartupIdentity? = null

    fun capture(tokenIdentity: OutboxOwnerIdentity?) {
        synchronized(lock) {
            if (captured == null) {
                captured = PersistedDiagnosticStartupIdentity(tokenIdentity)
            }
        }
    }

    fun current(): PersistedDiagnosticStartupIdentity? = captured
}

/** Immutable witnesses read before foreground restore can clear or replace them. */
internal data class PersistedDiagnosticStartupWitness(
    val cacheScope: CacheScope?,
    val verifiedScopeHash: String?,
)

/** Serialises duplicate Ready hooks and permits retry when durable import fails. */
internal class PersistedDiagnosticHistoryImportGate(
    private val witness: PersistedDiagnosticStartupWitness,
    private val importHistory: suspend (String?) -> Unit,
) {
    private val mutex = Mutex()
    private var completed = false

    suspend fun importOnce(identity: PersistedDiagnosticStartupIdentity) {
        mutex.withLock {
            if (completed) return
            val scopeHash = verifiedPersistedDiagnosticScopeHash(
                tokenIdentity = identity.tokenIdentity,
                persistedCacheScope = witness.cacheScope,
                persistedDiagnosticScopeHash = witness.verifiedScopeHash,
            )
            importHistory(scopeHash)
            completed = true
        }
    }
}

/**
 * Process entry point. Installation immediately protects new crashes and freezes
 * the prior process' non-secret scope witnesses. Historical exits are imported
 * separately after encrypted startup state has completed.
 */
internal object DiagnosticsRuntime {
    private val installLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scopedState = ScopedDiagnosticState()

    @Volatile private var installed = false
    @Volatile private var appContext: Context? = null
    @Volatile private var accessTokenProvider: (() -> String?)? = null
    @Volatile private var verifiedScopeProvider: (() -> CacheScope?)? = null
    @Volatile private var connectivityProvider: (() -> DiagnosticConnectivity)? = null
    @Volatile private var outbox: DiagnosticOutbox? = null
    @Volatile private var verifiedScopeStore: DiagnosticVerifiedScopeStore? = null
    @Volatile private var historicalImportGate: PersistedDiagnosticHistoryImportGate? = null

    fun install(
        application: Application,
        accessTokenProvider: () -> String?,
        verifiedScopeProvider: () -> CacheScope?,
        connectivityProvider: () -> DiagnosticConnectivity = { DiagnosticConnectivity.UNKNOWN },
    ) {
        isolateDiagnosticFailure(::reportDiagnosticFailure) {
            synchronized(installLock) {
                if (installed) return
                val context = application.applicationContext
                val markerStore = DiagnosticCrashMarkerStore(context)
                val exitLedger = DiagnosticExitLedger(context)
                val verifiedStore = DiagnosticVerifiedScopeStore(context)
                val startupWitness = runCatching {
                    PersistedDiagnosticStartupWitness(
                        cacheScope = SharedPreferencesCacheScopeMarker(context).current(),
                        verifiedScopeHash = verifiedStore.current(),
                    )
                }.getOrNull()
                val dao = DiagnosticDatabaseProvider.get(context).outboxDao()
                val diagnosticOutbox = DiagnosticOutbox(
                    dao = dao,
                    scheduleDelivery = {
                        isolateDiagnosticFailure(::reportDiagnosticFailure) {
                            DiagnosticSyncScheduler.enqueue(context)
                        }
                    },
                )
                this.appContext = context
                this.accessTokenProvider = accessTokenProvider
                this.verifiedScopeProvider = verifiedScopeProvider
                this.connectivityProvider = connectivityProvider
                this.outbox = diagnosticOutbox
                this.verifiedScopeStore = verifiedStore
                this.historicalImportGate = startupWitness?.let { witness ->
                    PersistedDiagnosticHistoryImportGate(witness) { persistedScopeHash ->
                        dao.quarantineUnboundPending()
                        val marker = markerStore.peek()
                        marker?.let {
                            val provenMarkerScope = it.localScopeHash
                                ?.takeIf { markerScope -> markerScope == persistedScopeHash }
                            diagnosticOutbox.capture(it.event, provenMarkerScope)
                            // A failed acknowledgement leaves the marker retryable.
                            check(markerStore.acknowledge(it.event.clientEventId)) {
                                "The historical crash marker could not be acknowledged"
                            }
                        }
                        PreviousProcessExitCapture(context, exitLedger)
                            .read(markerCrashAtMillis = marker?.event?.occurredAtMillis)
                            .forEach {
                                diagnosticOutbox.capture(it, persistedScopeHash)
                                it.localDedupeKey?.let { key ->
                                    check(exitLedger.remember(key)) {
                                        "The historical exit could not be recorded durably"
                                    }
                                }
                            }
                    }
                }
                installCrashHandler(markerStore)
                isolateDiagnosticFailure(::reportDiagnosticFailure) {
                    DiagnosticSyncScheduler.ensurePeriodic(context)
                }
                installed = true
            }
        }
    }

    /** Uses only the identity captured by the process-owned startup loader. */
    suspend fun importPersistedStartupHistory(identity: PersistedDiagnosticStartupIdentity) {
        val gate = historicalImportGate ?: return
        try {
            gate.importOnce(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportDiagnosticFailure(failure)
        }
    }

    /** Called from the one central HTTP error interceptor after classification. */
    fun recordApiFailure(observation: ApiFailureObservation) {
        isolateDiagnosticFailure(::reportDiagnosticFailure) {
            val event = ApiFailureNormalizer.normalize(observation, System.currentTimeMillis()) ?: return
            val localScope = currentVerifiedScopeHash()
            val key = requireNotNull(event.failureFingerprint)
            if (!scopedState.claimApiFailure(localScope, key, SystemClock.elapsedRealtime())) return
            capture(event, localScope)
        }
    }

    /** Called by the sync coordinator with counts only; no row/body data crosses this boundary. */
    fun recordSyncHealth(sample: SyncHealthSample, nowElapsedMillis: Long = SystemClock.elapsedRealtime()) {
        isolateDiagnosticFailure(::reportDiagnosticFailure) {
            val localScope = currentVerifiedScopeHash()
            val signal = scopedState.evaluateSync(localScope, nowElapsedMillis, sample) ?: return
            capture(
                DiagnosticEvent(
                    eventType = DiagnosticEventType.SYNC_STALL,
                    severity = if (signal.durationBucket == DiagnosticDurationBucket.OVER_10M) {
                        DiagnosticSeverity.ERROR
                    } else {
                        DiagnosticSeverity.WARNING
                    },
                    occurredAtMillis = System.currentTimeMillis(),
                    component = DiagnosticComponent.SYNC,
                    reasonCode = "outbox_progress_stalled",
                    failureFingerprint = sha256Hex("sync|outbox_progress_stalled"),
                    durationBucket = signal.durationBucket,
                    connectivity = DiagnosticConnectivity.ONLINE,
                    pendingOutboxCount = signal.pendingOutboxCount,
                ),
                localScope,
            )
        }
    }

    /** Called only after cache ownership and authenticated outbox ownership agree. */
    fun onVerifiedScopeAvailable() {
        isolateDiagnosticFailure(::reportDiagnosticFailure) {
            val localScope = currentVerifiedScopeHash()
            scopedState.observeScope(localScope)
            if (localScope == null) {
                // A stale durable witness must not survive a failed verification.
                verifiedScopeStore?.clear()
                return
            }
            if (verifiedScopeStore?.remember(localScope) != true) {
                // Best-effort fail closed for future process-exit attribution. The
                // current process can still safely bind rows to its live lease.
                verifiedScopeStore?.clear()
            }
            appContext?.let(DiagnosticSyncScheduler::enqueue)
        }
    }

    /** Logout/lock hook: reset state and durably revoke prior exit attribution. */
    fun onScopeUnavailable() {
        isolateDiagnosticFailure(::reportDiagnosticFailure) {
            scopedState.observeScope(null)
            verifiedScopeStore?.clear()
        }
    }

    /** Reconnect hook for rows captured while the app had no usable link. */
    fun requestDelivery() {
        isolateDiagnosticFailure(::reportDiagnosticFailure) {
            val localScope = currentVerifiedScopeHash()
            scopedState.observeScope(localScope)
            if (localScope != null) {
                appContext?.let(DiagnosticSyncScheduler::enqueue)
            }
        }
    }

    private fun capture(event: DiagnosticEvent, localScope: String?) {
        val target = outbox ?: return
        scope.launch {
            isolateDiagnosticFailure(::reportDiagnosticFailure) {
                target.capture(event, localScope)
            }
        }
    }

    private fun currentVerifiedScopeHash(): String? {
        val token = runCatching { accessTokenProvider?.invoke() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val identity = AccessTokenIdentityParser.parse(token) ?: return null
        val verifiedScope = runCatching { verifiedScopeProvider?.invoke() }.getOrNull()
        return verifiedDiagnosticScopeHash(identity, verifiedScope)
    }

    private fun installCrashHandler(markerStore: DiagnosticCrashMarkerStore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val event = CrashNormalizer.normalize(
                    throwable = throwable,
                    occurredAtMillis = System.currentTimeMillis(),
                    scopeConnectivity = connectivityProvider?.invoke()
                        ?: DiagnosticConnectivity.UNKNOWN,
                )
                if (event != null) markerStore.write(event, currentVerifiedScopeHash())
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Never swallow a fatal failure if a vendor runtime supplied
                // no default handler.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }
}

private fun reportDiagnosticFailure(failure: Exception) {
    // Never report a telemetry failure through telemetry itself or log private messages.
    Log.w("Diagnostics", "Optional diagnostic work failed (${failure.javaClass.simpleName}); existing evidence was kept.")
}

internal class DiagnosticRateLimiter(
    private val minimumIntervalMillis: Long = 60_000L,
) {
    init {
        require(minimumIntervalMillis > 0)
    }

    private val lastAccepted = ConcurrentHashMap<String, Long>()

    fun claim(key: String, nowElapsedMillis: Long): Boolean {
        require(key.isNotBlank())
        require(nowElapsedMillis >= 0)
        var accepted = false
        lastAccepted.compute(key) { _, previous ->
            if (
                previous == null ||
                nowElapsedMillis < previous ||
                nowElapsedMillis - previous >= minimumIntervalMillis
            ) {
                accepted = true
                nowElapsedMillis
            } else {
                previous
            }
        }
        return accepted
    }
}

private val SCOPE_HASH = Regex("^[0-9a-f]{64}$")

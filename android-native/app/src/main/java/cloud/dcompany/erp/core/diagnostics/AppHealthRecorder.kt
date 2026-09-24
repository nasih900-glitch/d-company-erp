package cloud.dcompany.erp.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import cloud.dcompany.erp.BuildConfig
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class AppHealthUiState(
    val recording: Boolean = false,
    val startedAtMillis: Long? = null,
    val records: List<AppHealthCapture> = emptyList(),
)

/**
 * Opt-in, five-minute local performance capture. Crash/ANR/API/sync failures
 * continue to use their existing automatic diagnostic outbox; frame samples
 * never enter it and cannot displace crash evidence.
 */
internal object AppHealthRecorder {
    private const val PREFS = "dcompany_app_health"
    private const val SCOPE_KEY = "scope_hash"
    private const val RECORDS_KEY = "records"
    private const val DURATION_MS = 5 * 60 * 1_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(AppHealthUiState())
    val state: StateFlow<AppHealthUiState> = _state.asStateFlow()
    @Volatile private var recording = false
    @Volatile private var screen = "other"
    private var appContext: Context? = null
    private var scopeHash: String? = null
    private var builder: HealthCaptureBuilder? = null
    private var startedAtMillis = 0L
    private var window: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var frameThread: HandlerThread? = null
    private var fallbackDeadlineNanos = 16_666_667L
    private val timeout = Runnable { stop("completed") }
    private val retentionCleanup = Runnable { pruneExpired() }

    fun isRecording(): Boolean = recording

    @Synchronized
    fun activateScope(context: Context, companyId: String, userId: String) {
        val nextHash = scopeHash(companyId, userId)
        if (scopeHash != null && scopeHash != nextHash) stop("account_changed")
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(SCOPE_KEY, null) != nextHash) {
            prefs.edit().clear().putString(SCOPE_KEY, nextHash).apply()
        }
        appContext = app
        scopeHash = nextHash
        val records = loadRecent(app)
        _state.value = _state.value.copy(records = records)
        scheduleRetention(records)
    }

    @Synchronized
    fun deactivateScope(companyId: String, userId: String) {
        if (scopeHash != scopeHash(companyId, userId)) return
        stop("account_changed")
        mainHandler.removeCallbacks(retentionCleanup)
        scopeHash = null
        screen = "other"
        _state.value = AppHealthUiState()
    }

    /** Destination names are enum constants; no route, URL or screen text is kept. */
    fun setScreen(destinationName: String) {
        screen = when (destinationName) {
            "Dashboard", "Pos", "Gaming", "Shift", "Customers", "Settings",
            "Inventory", "Finance", "Reports", "Refunds", "Help" ->
                destinationName.lowercase()
            else -> "other"
        }
    }

    @Synchronized
    fun start(targetWindow: Window): Boolean {
        if (scopeHash == null || recording) return false
        val thread = HandlerThread("DCompanyAppHealthFrames").apply { start() }
        val refreshRate = targetWindow.decorView.display?.refreshRate?.takeIf { it in 30f..240f } ?: 60f
        fallbackDeadlineNanos = (1_000_000_000.0 / refreshRate).toLong()
        val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) {
                metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0L }
                    ?: fallbackDeadlineNanos
            } else {
                fallbackDeadlineNanos
            }
            synchronized(this) {
                builder?.frame(
                    screen = screen,
                    totalDurationNanos = total,
                    deadlineNanos = deadline,
                    firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L,
                    droppedReports = dropped,
                )
            }
        }
        val attached = runCatching {
            targetWindow.addOnFrameMetricsAvailableListener(frameListener, Handler(thread.looper))
        }.isSuccess
        if (!attached) {
            thread.quitSafely()
            return false
        }
        window = targetWindow
        listener = frameListener
        frameThread = thread
        builder = HealthCaptureBuilder()
        startedAtMillis = System.currentTimeMillis()
        recording = true
        mainHandler.postDelayed(timeout, DURATION_MS)
        _state.value = _state.value.copy(recording = true, startedAtMillis = startedAtMillis)
        return true
    }

    @Synchronized
    fun recordRefresh(resource: String, elapsedMillis: Long, outcome: String) {
        if (!recording) return
        val label = when (resource) {
            "shifts", "gaming", "kitchen", "tables", "menu", "orders", "receipts",
            "customers", "staff", "attendance", "inventory", "finance", "events",
            "memberships", "settings" -> resource
            else -> "other"
        }
        builder?.refresh(label, elapsedMillis, outcome)
    }

    @Synchronized
    fun stop(reason: String = "stopped") {
        val activeBuilder = builder ?: return
        recording = false
        builder = null
        mainHandler.removeCallbacks(timeout)
        val oldListener = listener
        val oldWindow = window
        listener = null
        window = null
        if (oldListener != null && oldWindow != null) {
            runCatching { oldWindow.removeOnFrameMetricsAvailableListener(oldListener) }
        }
        frameThread?.quitSafely()
        frameThread = null
        val capture = activeBuilder.build(
            startedAtMillis = startedAtMillis,
            endedAtMillis = System.currentTimeMillis(),
            stopReason = reason.takeIf { it in setOf("completed", "stopped", "background", "account_changed") }
                ?: "stopped",
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidApiLevel = Build.VERSION.SDK_INT,
        )
        val app = appContext
        val records = recentHealthCaptures(listOf(capture) + _state.value.records, System.currentTimeMillis())
        if (app != null && scopeHash != null) persist(app, records)
        _state.value = AppHealthUiState(records = records)
        scheduleRetention(records)
    }

    @Synchronized
    fun deleteRecords() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(RECORDS_KEY)?.apply()
        _state.value = _state.value.copy(records = emptyList())
        mainHandler.removeCallbacks(retentionCleanup)
    }

    @Synchronized
    fun pruneExpired() {
        val app = appContext ?: return
        if (scopeHash == null) return
        val records = recentHealthCaptures(_state.value.records, System.currentTimeMillis())
        if (records != _state.value.records) {
            persist(app, records)
            _state.value = _state.value.copy(records = records)
        }
        scheduleRetention(records)
    }

    private fun loadRecent(context: Context): List<AppHealthCapture> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(RECORDS_KEY, null) ?: return emptyList()
        val records = recentHealthCaptures(
            runCatching { json.decodeFromString<List<AppHealthCapture>>(saved) }
                .getOrDefault(emptyList()),
            System.currentTimeMillis(),
        )
        persist(context, records)
        return records
    }

    private fun scheduleRetention(records: List<AppHealthCapture>) {
        mainHandler.removeCallbacks(retentionCleanup)
        if (scopeHash == null) return
        val nextExpiry = records.minOfOrNull { it.endedAtMillis + APP_HEALTH_RETENTION_MS } ?: return
        mainHandler.postDelayed(
            retentionCleanup,
            (nextExpiry - System.currentTimeMillis() + 1L).coerceAtLeast(1L),
        )
    }

    private fun persist(context: Context, records: List<AppHealthCapture>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(RECORDS_KEY, json.encodeToString(records)).apply()
    }

    private fun scopeHash(companyId: String, userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${companyId.trim()}:${userId.trim()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

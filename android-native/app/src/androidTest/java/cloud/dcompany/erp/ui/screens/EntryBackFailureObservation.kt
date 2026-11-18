package cloud.dcompany.erp.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.MainTestClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Cached, non-atomic facts only. Failure handling never requests UI work. */
internal class EntryBackFailureObservation {
    private var before: String? = null
    private var after: String? = null
    private var clockBefore: Long? = null
    private var autoAdvance: Boolean? = null
    private var firstClock: Long? = null
    private var lastClock: Long? = null
    private var firstWall: Long? = null
    private var lastWall: Long? = null
    private var polls = 0
    private val callbackSeen = AtomicReference<Boolean?>(false)
    private val callbackOnMain = AtomicReference<Boolean?>(null)

    fun callbackEntered() {
        runCatching { callbackSeen.set(true) }
        runCatching { callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper()) }
    }

    fun beforeDispatch(activity: ComponentActivity, clock: MainTestClock) {
        clockBefore = clockMs(clock)
        autoAdvance = runCatching { clock.autoAdvance }.getOrNull()
        before = runCatching { dispatchState(activity) }.getOrNull()
    }

    fun afterDispatch(activity: ComponentActivity) {
        after = runCatching { dispatchState(activity) }.getOrNull()
    }

    fun poll(clock: MainTestClock) {
        runCatching {
            val first = polls == 0
            polls = (polls + 1).coerceAtMost(1_000_000)
            val wall = runCatching { System.nanoTime() }.getOrNull()
            val virtual = clockMs(clock)
            if (first) {
                firstClock = virtual
                firstWall = wall
            }
            lastClock = virtual
            lastWall = wall
        }
    }

    fun attach(failure: Throwable, acknowledgements: CopyOnWriteArrayList<Long>) {
        // COW's sized spliterator retains one immutable array snapshot without
        // copying/traversing an arbitrarily long list or exposing its values.
        var kind = "UNAVAILABLE"
        var count: Int? = null
        runCatching {
            val snapshot = acknowledgements.spliterator()
            val size = snapshot.exactSizeIfKnown
            check(size >= 0)
            kind = when (size) {
                0L -> "EMPTY"
                1L -> {
                    var exact = false
                    check(snapshot.tryAdvance { exact = it == 94L })
                    if (exact) "EXACT_94" else "OTHER_SINGLE"
                }
                else -> "MULTIPLE"
            }
            count = size.coerceAtMost(1_000_000).toInt()
        }
        val callbackMainSnapshot = runCatching { callbackOnMain.get() }.getOrNull()
        val callbackSeenSnapshot = runCatching { callbackSeen.get() }.getOrNull()
        val record = JSONObject()
            .put("schema_version", 1).put("surface", "fresh_login_back")
            .put("stage", "after_failure").put("atomic_observation", false)
            .put("before_dispatch", before?.let(::JSONObject) ?: JSONObject.NULL)
            .put("after_dispatch", after?.let(::JSONObject) ?: JSONObject.NULL)
            .put("auto_advance", autoAdvance ?: JSONObject.NULL)
            .put("clock_before_ms", clockBefore ?: JSONObject.NULL)
            .put("clock_first_poll_ms", firstClock ?: JSONObject.NULL)
            .put("clock_last_poll_ms", lastClock ?: JSONObject.NULL)
            .put("poll_virtual_elapsed_ms", delta(firstClock, lastClock, 86_400_000) ?: JSONObject.NULL)
            .put("poll_wall_elapsed_ms", delta(firstWall, lastWall, 86_400_000_000_000)?.div(1_000_000) ?: JSONObject.NULL)
            .put("polls", polls).put("ack_state", kind).put("ack_count", count ?: JSONObject.NULL)
            .put("callback_seen", callbackSeenSnapshot ?: JSONObject.NULL)
            .put("callback_on_main", callbackMainSnapshot ?: JSONObject.NULL)
        failure.addSuppressed(AssertionError("DCOMPANY_ENTRY_BACK_FAILURE_V1=$record").apply {
            stackTrace = emptyArray()
        })
    }

    private fun clockMs(clock: MainTestClock): Long? = runCatching {
        clock.currentTime.takeIf { it in 0L..1_000_000_000_000L }
    }.getOrNull()

    private fun delta(first: Long?, last: Long?, maximum: Long): Long? = runCatching {
        if (first == null || last == null) null
        else Math.subtractExact(last, first).takeIf { it in 0L..maximum }
    }.getOrNull()

    private fun dispatchState(activity: ComponentActivity): String = JSONObject()
        .put("has_enabled_callbacks", runCatching { activity.onBackPressedDispatcher.hasEnabledCallbacks() }.getOrNull() ?: JSONObject.NULL)
        .put("lifecycle", runCatching { activity.lifecycle.currentState.name }.getOrNull() ?: JSONObject.NULL)
        .put("finishing", runCatching { activity.isFinishing }.getOrNull() ?: JSONObject.NULL)
        .put("destroyed", runCatching { activity.isDestroyed }.getOrNull() ?: JSONObject.NULL)
        .put("window_focus", runCatching { activity.window.decorView.hasWindowFocus() }.getOrNull() ?: JSONObject.NULL)
        .toString()
}

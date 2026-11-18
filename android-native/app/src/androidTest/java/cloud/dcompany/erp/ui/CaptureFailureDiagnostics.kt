package cloud.dcompany.erp.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import org.json.JSONObject

/** Read-only AFTER-failure facts, not a sample of the earlier forceRedraw interval. */
internal fun observePhysicalCaptureFailure(activity: ComponentActivity, filename: String, failure: Throwable) {
    require(filename in setOf(
        "01-gaming-online.png", "02-gaming-server-issue.png", "03-gaming-after-connectivity-stress.png",
        "component-gaming-timer-1hz-before.png", "component-gaming-timer-1hz-after.png",
        "component-connectivity-only-3s-before.png", "component-connectivity-only-3s-after.png",
    ))
    val handler = Handler(Looper.getMainLooper())
    val probe = captureFailureProbe(
        post = { handler.post(it) },
        remove = { handler.removeCallbacks(it) },
        collect = {
            val decor = activity.window.decorView
            JSONObject()
                .put("lifecycle", activity.lifecycle.currentState.name)
                .put("finishing", activity.isFinishing)
                .put("destroyed", activity.isDestroyed)
                .put("attached", decor.isAttachedToWindow)
                .put("shown", decor.isShown)
                .put("focused", decor.hasWindowFocus())
                .put("visibility", visibilityName(decor.visibility))
                .put("window_visibility", visibilityName(decor.windowVisibility))
                .put("width", decor.width.coerceAtLeast(0))
                .put("height", decor.height.coerceAtLeast(0))
                .put("hardware_accelerated", decor.isHardwareAccelerated)
                .toString()
        },
    )
    val record = JSONObject()
        .put("schema_version", 1)
        .put("stage", "capture_failure")
        .put("surface", "physical_component_harness")
        .put("screenshot", filename)
        .put("failure_kind", when (failure) {
            is androidx.compose.ui.test.ComposeTimeoutException -> "COMPOSE_TIMEOUT"
            is AssertionError -> "ASSERTION"
            else -> "OTHER"
        })
        .put("after_failure", true)
        .put("atomic_observation", false)
        .put("caller_is_main", Looper.myLooper() == Looper.getMainLooper())
        .put("wait_budget_ms", CAPTURE_PROBE_WAIT_MS)
        .put("post_accepted", probe.postAccepted)
        .put("wait_finished", probe.waitFinished)
        .put("status", probe.status.name)
        .put("window", probe.sample?.let(::JSONObject) ?: JSONObject.NULL)
    failure.addSuppressed(AssertionError("DCOMPANY_CAPTURE_FAILURE_V1=$record").apply {
        stackTrace = emptyArray()
    })
}

private fun visibilityName(value: Int): String = when (value) {
    View.VISIBLE -> "VISIBLE"
    View.INVISIBLE -> "INVISIBLE"
    View.GONE -> "GONE"
    else -> error("Unknown visibility")
}

package cloud.dcompany.erp.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt

internal const val COMPOSE_PIXEL_COPY_EXCEPTION =
    "androidx.compose.ui.test.android.PixelCopyException"

internal fun RuntimeException.isPixelCopyTimeout(): Boolean {
    if (javaClass.name != COMPOSE_PIXEL_COPY_EXCEPTION) return false
    val status = try {
        javaClass.getMethod("getCopyResultStatus").invoke(this) as? Int
    } catch (_: ReflectiveOperationException) {
        null
    }
    return status == PixelCopy.ERROR_TIMEOUT
}

/** Capture a proven activity workspace from its real window layer after HWUI readback times out. */
internal fun AndroidComposeTestRule<*, ComponentActivity>.captureWorkspaceAfterPixelCopyTimeout(
    rootTag: String,
    failure: RuntimeException,
): Bitmap {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !failure.isPixelCopyTimeout() ||
        (rootTag != "control-deck-full-shell" && rootTag != "gaming-full-workspace")
    ) {
        throw failure
    }
    val window = activity.window
    val shell = onNodeWithTag(rootTag).fetchSemanticsNode()
    val (bounds, windowBounds) = runOnIdle {
        val bounds = shell.boundsInWindow
        Rect(
            bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.right.roundToInt(), bounds.bottom.roundToInt(),
        ) to Rect(0, 0, window.decorView.width, window.decorView.height)
    }
    val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(window)) {
        "SurfaceFlinger did not capture the workspace window after the PixelCopy timeout."
    }
    var cropped: Bitmap? = null
    try {
        check(!bounds.isEmpty && windowBounds.contains(bounds) &&
            screenshot.width == windowBounds.width() && screenshot.height == windowBounds.height()
        ) {
            "Cannot map workspace bounds $bounds into the ${screenshot.width}×${screenshot.height} window capture."
        }
        return Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            .also { cropped = it }
    } finally {
        if (cropped !== screenshot) screenshot.recycle()
    }
}

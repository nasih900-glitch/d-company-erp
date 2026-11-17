package cloud.dcompany.erp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage as captureComposeImage
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

@RequiresApi(Build.VERSION_CODES.O)
internal fun SemanticsNodeInteraction.captureToImage(): ImageBitmap = try {
    captureComposeImage()
} catch (failure: RuntimeException) {
    captureActivityWindowAfterPixelCopyTimeout(failure)
}

/** Capture the same Activity-owned region through SurfaceFlinger after HWUI readback times out. */
internal fun SemanticsNodeInteraction.captureActivityWindowAfterPixelCopyTimeout(
    failure: RuntimeException,
): ImageBitmap {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !failure.isPixelCopyTimeout()) {
        throw failure
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val node = fetchSemanticsNode("Failed to retrieve the timed-out capture region.")
    var source: ActivityCaptureRegion? = null
    instrumentation.runOnMainSync { source = node.activityCaptureRegion() }
    val (window, bounds, windowBounds) = source ?: throw failure
    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot(window)) {
        "SurfaceFlinger did not capture the activity window after the PixelCopy timeout."
    }
    try {
        check(screenshot.width == windowBounds.width() && screenshot.height == windowBounds.height()) {
            "Cannot map activity bounds $windowBounds into the ${screenshot.width}×${screenshot.height} window capture."
        }
        val pixels = checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
            "SurfaceFlinger window capture could not be read."
        }
        try {
            // Match Compose's readable sRGB output, with no scaling between source and destination.
            val crop = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            try {
                Canvas(crop).drawBitmap(pixels, bounds, Rect(0, 0, crop.width, crop.height), null)
                Log.i("WorkspaceScreenshot", "Captured activity region $bounds through SurfaceFlinger after PixelCopy timeout")
                return crop.asImageBitmap()
            } catch (error: Throwable) {
                crop.recycle()
                throw error
            }
        } finally {
            pixels.recycle()
        }
    } finally {
        screenshot.recycle()
    }
}

private data class ActivityCaptureRegion(val window: Window, val bounds: Rect, val windowBounds: Rect)

private fun SemanticsNode.activityCaptureRegion(): ActivityCaptureRegion? {
    if (!layoutInfo.isAttached || !layoutInfo.isPlaced) return null
    val view = (root as? ViewRootForTest)?.view ?: return null
    val window = view.context.findActivity()?.window ?: return null
    val decor = window.peekDecorView() ?: return null
    if (!view.isAttachedToWindow || view.rootView !== decor || generateSequence(this) { it.parent }.any {
            it.config.contains(SemanticsProperties.IsDialog) || it.config.contains(SemanticsProperties.IsPopup)
        }
    ) return null

    // Compose 1.7.5 rounds clipped root bounds before adding the Compose view's window offset.
    val bounds = boundsInRoot.let {
        Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
    }
    val origin = IntArray(2).also(view::getLocationInWindow)
    bounds.offset(origin[0], origin[1])
    val windowBounds = Rect(0, 0, decor.width, decor.height)
    if (bounds.isEmpty || !windowBounds.contains(bounds)) return null
    return ActivityCaptureRegion(window, bounds, windowBounds)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.takeUnless { it === this }?.findActivity()
    else -> null
}

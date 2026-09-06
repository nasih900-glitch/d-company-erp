package cloud.dcompany.erp.core.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal data class RemoteEncodedFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val privacyPlaceholder: Boolean,
)

/**
 * Reference-counted because multiple nested sensitive dialogs may briefly
 * overlap during Compose disposal. A stale disposer can remove only its own
 * token, never another dialog's privacy shield.
 */
internal class RemoteCapturePrivacyController {
    private val lock = Any()
    private val nextToken = AtomicLong(0L)
    private val blockers = linkedSetOf<Long>()
    private val revisionCounter = AtomicLong(0L)
    private val _blocked = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    fun acquire(): RemoteCapturePrivacyToken {
        val id = nextToken.incrementAndGet()
        synchronized(lock) {
            blockers += id
            revisionCounter.incrementAndGet()
            _blocked.value = true
        }
        return RemoteCapturePrivacyToken {
            synchronized(lock) {
                blockers -= id
                revisionCounter.incrementAndGet()
                _blocked.value = blockers.isNotEmpty()
            }
        }
    }

    fun snapshot(): RemotePrivacySnapshot = synchronized(lock) {
        RemotePrivacySnapshot(blocked = blockers.isNotEmpty(), revision = revisionCounter.get())
    }
}

internal data class RemotePrivacySnapshot(val blocked: Boolean, val revision: Long)

internal class RemoteCapturePrivacyToken(private val release: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        release()
    }
}

/**
 * PixelCopy receives only this process's MainActivity Window. There is no
 * display token, MediaProjection, virtual display or accessibility surface in
 * this implementation, so another app can never become a capture source.
 */
internal class RemoteAppWindowCaptureSource(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val lock = Any()
    private var window: WeakReference<Window>? = null

    fun attach(appWindow: Window) = synchronized(lock) {
        window = WeakReference(appWindow)
    }

    fun detach(appWindow: Window? = null) = synchronized(lock) {
        val current = window?.get()
        if (appWindow == null || current === appWindow) {
            window?.clear()
            window = null
        }
    }

    suspend fun capture(): Bitmap? = withTimeoutOrNull(1_500L) {
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                val sourceWindow = synchronized(lock) { window?.get() }
                val view = sourceWindow?.decorView
                val sourceWidth = view?.width ?: 0
                val sourceHeight = view?.height ?: 0
                if (
                    sourceWindow == null ||
                    view == null ||
                    !view.isAttachedToWindow ||
                    sourceWidth <= 0 ||
                    sourceHeight <= 0
                ) {
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }
                val (targetWidth, targetHeight) = boundedFrameDimensions(sourceWidth, sourceHeight)
                if (!remoteFrameDimensionsAccepted(targetWidth, targetHeight)) {
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }
                val bitmap = runCatching {
                    Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                }.getOrNull()
                if (bitmap == null) {
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }
                try {
                    PixelCopy.request(
                        sourceWindow,
                        Rect(0, 0, sourceWidth, sourceHeight),
                        bitmap,
                        { result ->
                            if (!continuation.isActive) {
                                bitmap.recycle()
                            } else if (result == PixelCopy.SUCCESS) {
                                continuation.resume(bitmap)
                            } else {
                                bitmap.recycle()
                                continuation.resume(null)
                            }
                        },
                        mainHandler,
                    )
                } catch (_: Exception) {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
}

internal class RemoteFrameEncoder(
    private val source: RemoteAppWindowCaptureSource,
) {
    suspend fun encode(disposition: RemoteCaptureDisposition): RemoteEncodedFrame {
        if (disposition == RemoteCaptureDisposition.PRIVACY_PLACEHOLDER) {
            return encodePrivacyPlaceholder()
        }
        val bitmap = source.capture() ?: return encodePrivacyPlaceholder()
        return try {
            encodeBounded(bitmap, privacyPlaceholder = false) ?: encodePrivacyPlaceholder()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encodePrivacyPlaceholder(): RemoteEncodedFrame {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(16, 18, 22))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(239, 196, 92)
            textAlign = Paint.Align.CENTER
            textSize = 34f
            isFakeBoldText = true
        }
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 208, 214)
            textAlign = Paint.Align.CENTER
            textSize = 22f
        }
        canvas.drawText("Privacy protected", bitmap.width / 2f, 168f, title)
        canvas.drawText("Sensitive ERP details are hidden", bitmap.width / 2f, 212f, detail)
        return try {
            requireNotNull(encodeBounded(bitmap, privacyPlaceholder = true))
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeBounded(
        sourceBitmap: Bitmap,
        privacyPlaceholder: Boolean,
    ): RemoteEncodedFrame? {
        var working = sourceBitmap
        val qualities = intArrayOf(REMOTE_FRAME_JPEG_QUALITY, 34, 26)
        try {
            qualities.forEachIndexed { index, quality ->
                val output = ByteArrayOutputStream(64 * 1_024)
                if (working.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    val bytes = output.toByteArray()
                    if (
                        bytes.size in 1..REMOTE_FRAME_MAX_BYTES &&
                        remoteFrameDimensionsAccepted(working.width, working.height)
                    ) {
                        return RemoteEncodedFrame(
                            bytes = bytes,
                            width = working.width,
                            height = working.height,
                            privacyPlaceholder = privacyPlaceholder,
                        )
                    }
                    bytes.fill(0)
                }
                if (index < qualities.lastIndex) {
                    val nextWidth = (working.width * 0.78f).roundToInt()
                    val nextHeight = (working.height * 0.78f).roundToInt()
                    // Never upscale one axis or distort the ERP window merely to
                    // satisfy the backend floor. If another reduction would fall
                    // below that floor, fail closed to the fixed safe placeholder.
                    if (
                        nextWidth < REMOTE_FRAME_MIN_WIDTH ||
                        nextHeight < REMOTE_FRAME_MIN_HEIGHT
                    ) return null
                    val next = Bitmap.createScaledBitmap(
                        working,
                        nextWidth,
                        nextHeight,
                        true,
                    )
                    if (working !== sourceBitmap) working.recycle()
                    working = next
                }
            }
            return null
        } finally {
            if (working !== sourceBitmap && !working.isRecycled) working.recycle()
        }
    }
}

internal fun boundedFrameDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    require(sourceWidth > 0 && sourceHeight > 0)
    val scale = minOf(
        1f,
        REMOTE_FRAME_MAX_WIDTH.toFloat() / sourceWidth,
        REMOTE_FRAME_MAX_HEIGHT.toFloat() / sourceHeight,
    )
    return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
        (sourceHeight * scale).roundToInt().coerceAtLeast(1)
}

internal fun remoteFrameDimensionsAccepted(width: Int, height: Int): Boolean =
    width in REMOTE_FRAME_MIN_WIDTH..REMOTE_FRAME_MAX_WIDTH &&
        height in REMOTE_FRAME_MIN_HEIGHT..REMOTE_FRAME_MAX_HEIGHT

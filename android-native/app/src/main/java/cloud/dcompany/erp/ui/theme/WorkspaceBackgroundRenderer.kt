package cloud.dcompany.erp.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicReference

internal data class WorkspaceBackgroundRenderKey(
    val slot: WorkspaceBackgroundSlot,
    val source: WorkspaceBackgroundSource,
    val generation: Long,
    val contentId: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val decoderIdentity: Int,
)

internal data class WorkspaceBackgroundCropPlan(
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceRight: Int,
    val sourceBottom: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val sourceWidth: Int get() = sourceRight - sourceLeft
    val sourceHeight: Int get() = sourceBottom - sourceTop
    val outputPixels: Long get() = outputWidth.toLong() * outputHeight.toLong()
}

/**
 * The visible workspace and every scene-backed glass consumer share one global light falloff.
 * [localTop] translates the viewport-space gradient into a consumer's local draw coordinates.
 */
internal fun workspaceSceneScrimBrush(
    localTop: Float = 0f,
    viewportHeight: Float = Float.POSITIVE_INFINITY,
): Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.44f),
    0.45f to Color.Black.copy(alpha = 0.26f),
    1f to Color.Black.copy(alpha = 0.60f),
    startY = -localTop,
    endY = if (viewportHeight.isFinite()) viewportHeight - localTop else Float.POSITIVE_INFINITY,
)

internal val WorkspaceSceneScrimOverlay = Color.Black.copy(alpha = 0.10f)

internal fun workspaceBackgroundCropPlan(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): WorkspaceBackgroundCropPlan {
    require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
    val sourceAspect = sourceWidth.toDouble() / sourceHeight.toDouble()
    val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()
    val cropWidth: Int
    val cropHeight: Int
    if (sourceAspect > targetAspect) {
        cropHeight = sourceHeight
        cropWidth = (sourceHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
    } else {
        cropWidth = sourceWidth
        cropHeight = (sourceWidth / targetAspect).roundToInt().coerceIn(1, sourceHeight)
    }
    val left = (sourceWidth - cropWidth) / 2
    val top = (sourceHeight - cropHeight) / 2
    return WorkspaceBackgroundCropPlan(
        sourceLeft = left,
        sourceTop = top,
        sourceRight = left + cropWidth,
        sourceBottom = top + cropHeight,
        outputWidth = targetWidth,
        outputHeight = targetHeight,
    )
}

internal fun workspaceBackgroundSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

private object WorkspaceBackgroundBitmapCache {
    // 40 MiB leaves room inside the 64 MiB clear+soft starting envelope for one
    // newly decoded HD clear frame, two derivatives, and one bounded blur job.
    private val cache = object : LruCache<WorkspaceBackgroundRenderKey, Bitmap>(WorkspaceClearCacheBudgetKiB) {
        override fun sizeOf(key: WorkspaceBackgroundRenderKey, value: Bitmap): Int =
            (value.allocationByteCount / 1_024).coerceAtLeast(1)
    }

    @Synchronized fun get(key: WorkspaceBackgroundRenderKey): Bitmap? = cache.get(key)
    @Synchronized fun put(key: WorkspaceBackgroundRenderKey, value: Bitmap) {
        cache.put(key, value)
        // Eviction only drops the reference. A bitmap that Compose still draws is never recycled here.
    }
}

internal interface WorkspaceBackgroundDecoder {
    fun decodeRequested(
        image: WorkspaceBackgroundImage,
        targetWidth: Int,
        targetHeight: Int,
        context: android.content.Context,
    ): Bitmap?

    fun decodeBundled(
        slot: WorkspaceBackgroundSlot,
        targetWidth: Int,
        targetHeight: Int,
        context: android.content.Context,
    ): Bitmap?
}

private object ProductionWorkspaceBackgroundDecoder : WorkspaceBackgroundDecoder {
    override fun decodeRequested(
        image: WorkspaceBackgroundImage,
        targetWidth: Int,
        targetHeight: Int,
        context: android.content.Context,
    ): Bitmap? = decodeMeasuredBackground(
        sourceWidth = image.width,
        sourceHeight = image.height,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        decode = { options ->
            if (image.source == WorkspaceBackgroundSource.IMPORTED) {
                image.privatePath?.let { BitmapFactory.decodeFile(it, options) }
            } else {
                BitmapFactory.decodeResource(
                    context.resources,
                    image.resource ?: image.slot.defaultResource,
                    options,
                )
            }
        },
    )

    override fun decodeBundled(
        slot: WorkspaceBackgroundSlot,
        targetWidth: Int,
        targetHeight: Int,
        context: android.content.Context,
    ): Bitmap? = decodeMeasuredBackground(3_840, 2_160, targetWidth, targetHeight) { options ->
        BitmapFactory.decodeResource(context.resources, slot.defaultResource, options)
    }
}

@Composable
internal fun WorkspaceBackgroundRenderer(
    image: WorkspaceBackgroundImage,
    modifier: Modifier = Modifier,
    decoder: WorkspaceBackgroundDecoder = ProductionWorkspaceBackgroundDecoder,
    allowBundledFallback: Boolean = true,
    applyWorkspaceSceneGrade: Boolean = true,
    adaptNightGlassForegrounds: Boolean = true,
    backgroundColor: Color = Color.Black,
    testTagPrefix: String = "workspace",
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var measuredWidth by remember { mutableIntStateOf(0) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    var displayedSlot by remember { mutableStateOf<WorkspaceBackgroundSlot?>(null) }
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var completedKey by remember { mutableStateOf<WorkspaceBackgroundRenderKey?>(null) }
    var fallbackForKey by remember { mutableStateOf<WorkspaceBackgroundRenderKey?>(null) }
    var actualDisplayedKey by remember { mutableStateOf<WorkspaceBackgroundRenderKey?>(null) }
    var displayedState by remember { mutableStateOf(DisplayedSceneState.UNAVAILABLE) }
    var frameEpoch by remember { mutableStateOf(0L) }
    var softenedDerivative by remember { mutableStateOf<WorkspaceGlassDerivative?>(null) }
    var rootBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var layoutEpoch by remember { mutableStateOf(0L) }
    // The cache key and decoded output use the real viewport dimensions. Independently rounding
    // width and height changes the aspect ratio and would make ContentScale.Crop crop a second
    // time, shifting the scene. The byte-sized LRU remains the allocation bound.
    val viewportWidth = measuredWidth
    val viewportHeight = measuredHeight
    val key = WorkspaceBackgroundRenderKey(
        slot = image.slot,
        source = image.source,
        generation = image.generation,
        contentId = image.contentId,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        decoderIdentity = System.identityHashCode(decoder),
    )

    LaunchedEffect(key, image.privatePath) {
        if (viewportWidth == 0 || viewportHeight == 0) return@LaunchedEffect
        val cached = WorkspaceBackgroundBitmapCache.get(key)
        if (actualDisplayedKey != key) {
            // Keep the last valid clear frame while Phase A resolves the requested source. Its
            // actual key remains truthful, while REQUESTED_READY and the old derivative are both
            // withheld until the requested or an explicit bundled-fallback frame wins.
            displayedState = DisplayedSceneState.UNAVAILABLE
            softenedDerivative = null
            frameEpoch += 1
        }
        if (displayedSlot != image.slot) {
            // Keep the last valid frame until the correct slot is ready; the fixed scrim prevents a flash.
            displayedSlot = image.slot
            if (cached != null) displayedBitmap = cached
        }
        if (cached != null) {
            displayedSlot = image.slot
            displayedBitmap = cached
            completedKey = key
            fallbackForKey = null
            actualDisplayedKey = key
            displayedState = DisplayedSceneState.REQUESTED_READY
            frameEpoch += 1
            return@LaunchedEffect
        }
        completedKey = null
        fallbackForKey = null
        repeat(RequestedDecodeAttempts) { attempt ->
            val requested = withContext(Dispatchers.IO) {
                try {
                    decoder.decodeRequested(image, viewportWidth, viewportHeight, context)
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
            if (requested != null) {
                WorkspaceBackgroundBitmapCache.put(key, requested)
                displayedSlot = image.slot
                displayedBitmap = requested
                completedKey = key
                fallbackForKey = null
                actualDisplayedKey = key
                displayedState = DisplayedSceneState.REQUESTED_READY
                frameEpoch += 1
                return@LaunchedEffect
            }

            val defaultImage = bundledWorkspaceBackground(image.slot)
            if (allowBundledFallback && (image.source == WorkspaceBackgroundSource.IMPORTED ||
                    image.contentId != defaultImage.contentId) && fallbackForKey != key
            ) {
                val bundled = defaultImage
                val fallbackKey = WorkspaceBackgroundRenderKey(
                    slot = bundled.slot,
                    source = bundled.source,
                    generation = bundled.generation,
                    contentId = bundled.contentId,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    decoderIdentity = System.identityHashCode(decoder),
                )
                val fallback = WorkspaceBackgroundBitmapCache.get(fallbackKey) ?: withContext(Dispatchers.IO) {
                    try {
                        decoder.decodeBundled(image.slot, viewportWidth, viewportHeight, context)
                    } catch (_: OutOfMemoryError) {
                        null
                    } catch (_: RuntimeException) {
                        null
                    }
                }?.also { WorkspaceBackgroundBitmapCache.put(fallbackKey, it) }
                displayedSlot = image.slot
                displayedBitmap = fallback
                fallbackForKey = if (fallback != null) key else null
                if (fallback != null) {
                    actualDisplayedKey = fallbackKey
                    displayedState = DisplayedSceneState.BUNDLED_FALLBACK
                    frameEpoch += 1
                }
            }
            if (attempt + 1 < RequestedDecodeAttempts) delay(RequestedDecodeRetryMillis)
        }

        // Both requested attempts failed. A correctly keyed bundled fallback may remain visible,
        // but the imported key is deliberately not cached or marked requested-content ready.
        displayedSlot = image.slot
        if (fallbackForKey != key) {
            displayedBitmap = null
            actualDisplayedKey = null
            displayedState = DisplayedSceneState.UNAVAILABLE
            frameEpoch += 1
        }
    }

    val displayedFrame = displayedBitmap?.let { bitmap ->
        actualDisplayedKey?.let { actual ->
            DisplayedSceneFrame(
                actualKey = actual,
                requestedKey = key,
                state = displayedState,
                clearBitmap = bitmap,
                epoch = frameEpoch,
            )
        }
    }
    val materialFrame = displayedFrame?.takeIf { frame ->
        (frame.state == DisplayedSceneState.REQUESTED_READY && frame.actualKey == key) ||
            (frame.state == DisplayedSceneState.BUNDLED_FALLBACK && fallbackForKey == key)
    }
    LaunchedEffect(materialFrame?.actualKey, materialFrame?.epoch) {
        val frame = materialFrame ?: run {
            softenedDerivative = null
            return@LaunchedEffect
        }
        val derivativeSize = workspaceGlassDerivativeSize(
            frame.actualKey.viewportWidth,
            frame.actualKey.viewportHeight,
        )
        val derivativeKey = WorkspaceGlassDerivativeKey(
            actualKey = frame.actualKey,
            frameEpoch = frame.epoch,
            algorithmVersion = WorkspaceGlassAlgorithmVersion,
            width = derivativeSize.width,
            height = derivativeSize.height,
            blurRadius = WorkspaceGlassBlurRadius,
        )
        WorkspaceGlassBitmapCache.get(derivativeKey)?.let {
            softenedDerivative = it
            return@LaunchedEffect
        }
        softenedDerivative = null
        val workerOwned = AtomicReference<Bitmap?>(null)
        val result = try {
            withContext(Dispatchers.Default) {
                softenWorkspaceBackground(
                    source = frame.clearBitmap,
                    width = derivativeSize.width,
                    height = derivativeSize.height,
                    key = derivativeKey,
                ).also {
                    workerOwned.set(it.bitmap)
                }
            }.also { check(workerOwned.compareAndSet(it.bitmap, null)) }
        } catch (cancelled: CancellationException) {
            workerOwned.getAndSet(null)?.recycle()
            throw cancelled
        } catch (_: OutOfMemoryError) {
            workerOwned.getAndSet(null)?.recycle()
            return@LaunchedEffect
        } catch (_: RuntimeException) {
            workerOwned.getAndSet(null)?.recycle()
            return@LaunchedEffect
        }
        val stillCurrent = workspaceGlassCanPublish(
            actualDisplayedKey,
            frameEpoch,
            displayedBitmap,
            frame,
        )
        if (stillCurrent) {
            WorkspaceGlassBitmapCache.put(derivativeKey, result)
            softenedDerivative = result
        } else {
            result.bitmap.recycle()
        }
    }

    Box(
        modifier.fillMaxSize()
            .onSizeChanged {
                measuredWidth = it.width
                measuredHeight = it.height
            }
            .onGloballyPositioned {
                val next = it.boundsInRoot()
                if (it !== rootCoordinates || next != rootBounds) {
                    rootCoordinates = it
                    rootBounds = next
                    layoutEpoch += 1
                }
            }
            .background(backgroundColor)
            .testTag("$testTagPrefix-background-${image.slot.name.lowercase()}"),
    ) {
        displayedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .testTag("$testTagPrefix-background-bitmap-${image.slot.name.lowercase()}"),
            )
        }
        if (completedKey == key) {
            Box(
                Modifier.fillMaxSize()
                    .testTag("$testTagPrefix-background-ready-${image.slot.name.lowercase()}"),
            )
        }
        if (fallbackForKey == key) {
            Box(
                Modifier.fillMaxSize()
                    .testTag("$testTagPrefix-background-fallback-${image.slot.name.lowercase()}"),
            )
        }
        if (materialFrame != null &&
            softenedDerivative?.key?.actualKey == materialFrame.actualKey &&
            softenedDerivative?.key?.frameEpoch == materialFrame.epoch
        ) {
            Box(
                Modifier.fillMaxSize()
                    .testTag("$testTagPrefix-glass-ready-${image.slot.name.lowercase()}"),
            )
        }
        if (applyWorkspaceSceneGrade) {
            Box(
                Modifier.fillMaxSize().background(
                    workspaceSceneScrimBrush(),
                ),
            )
            Box(Modifier.fillMaxSize().background(WorkspaceSceneScrimOverlay))
        }
        val scene = displayedFrame?.let { frame ->
            val coordinates = rootCoordinates ?: return@let null
            WorkspaceGlassScene(
                frame = frame,
                derivative = softenedDerivative,
                rootCoordinates = coordinates,
                rootBoundsInRoot = rootBounds,
                layoutEpoch = layoutEpoch,
                applyWorkspaceSceneGrade = applyWorkspaceSceneGrade,
            )
        }
        val inheritedColors = MaterialTheme.colorScheme
        val inheritedTypography = MaterialTheme.typography
        val inheritedShapes = MaterialTheme.shapes
        val glassReady = materialFrame != null && scene != null &&
            scene.softenedKey?.actualKey == scene.frame.actualKey &&
            scene.softenedKey?.frameEpoch == scene.frame.epoch
        val nightGlassColors = remember(inheritedColors, adaptNightGlassForegrounds) {
            if (adaptNightGlassForegrounds && inheritedColors.background.luminance() < .5f) {
                inheritedColors.copy(
                    onSurfaceVariant = workspaceGlassRoleStyle(
                        WorkspaceGlassRole.PANEL,
                        day = false,
                    ).secondaryContent,
                )
            } else {
                null
            }
        }
        CompositionLocalProvider(LocalWorkspaceGlassScene provides scene) {
            MaterialTheme(
                colorScheme = if (glassReady && nightGlassColors != null) {
                    nightGlassColors
                } else {
                    inheritedColors
                },
                typography = inheritedTypography,
                shapes = inheritedShapes,
                content = content,
            )
        }
    }
}

private object WorkspaceGlassBitmapCache {
    private val cache = object : LruCache<WorkspaceGlassDerivativeKey, WorkspaceGlassDerivative>(WorkspaceGlassDerivativeCacheEntries) {}
    @Synchronized fun get(key: WorkspaceGlassDerivativeKey): WorkspaceGlassDerivative? = cache.get(key)
    @Synchronized fun put(key: WorkspaceGlassDerivativeKey, value: WorkspaceGlassDerivative) {
        cache.put(key, value)
        // Published bitmaps may still be leased by Compose; eviction only releases the cache reference.
    }
}

private suspend fun softenWorkspaceBackground(
    source: Bitmap,
    width: Int,
    height: Int,
    key: WorkspaceGlassDerivativeKey,
): WorkspaceGlassDerivative {
    check(workspaceGlassTransientBytes(androidx.compose.ui.unit.IntSize(width, height)) <= 4L * 1_024 * 1_024)
    val scaled = Bitmap.createScaledBitmap(source, width, height, true)
    try {
        val sourcePixels = IntArray(width * height)
        val horizontal = IntArray(width * height)
        val output = IntArray(width * height)
        scaled.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        blurRows(sourcePixels, horizontal, width, height, WorkspaceGlassBlurRadius)
        blurColumns(horizontal, output, width, height, WorkspaceGlassBlurRadius)
        coroutineContext.ensureActive()
        val safetyGrid = workspaceGlassSafetyGrid(output, width, height)
        val bitmap = Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(false)
        }
        return WorkspaceGlassDerivative(key = key, bitmap = bitmap, safetyGrid = safetyGrid)
    } finally {
        // createScaledBitmap may return the leased clear frame when dimensions match.
        if (scaled !== source) scaled.recycle()
    }
}

internal fun workspaceGlassSafetyGrid(
    pixels: IntArray,
    width: Int,
    height: Int,
    columns: Int = WorkspaceGlassSafetyGridColumns,
    rows: Int = WorkspaceGlassSafetyGridRows,
): WorkspaceGlassSafetyGrid {
    require(width > 0 && height > 0 && pixels.size == width * height && columns > 0 && rows > 0)
    val maxima = IntArray(columns * rows)
    for (gridY in 0 until rows) {
        val top = maxOf(0, floor(gridY.toDouble() * height / rows).toInt() - 1)
        val bottom = minOf(height, ceil((gridY + 1.0) * height / rows).toInt() + 1)
        for (gridX in 0 until columns) {
            val left = maxOf(0, floor(gridX.toDouble() * width / columns).toInt() - 1)
            val right = minOf(width, ceil((gridX + 1.0) * width / columns).toInt() + 1)
            var red = 0
            var green = 0
            var blue = 0
            for (y in top until bottom) {
                var index = y * width + left
                for (x in left until right) {
                    val color = pixels[index++]
                    red = maxOf(red, color ushr 16 and 0xFF)
                    green = maxOf(green, color ushr 8 and 0xFF)
                    blue = maxOf(blue, color and 0xFF)
                }
            }
            maxima[gridY * columns + gridX] =
                0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
    }
    return WorkspaceGlassSafetyGrid(columns, rows, maxima)
}

private suspend fun blurRows(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
    for (y in 0 until height) {
        if (y and 7 == 0) coroutineContext.ensureActive()
        var a = 0; var r = 0; var g = 0; var b = 0
        var right = minOf(width - 1, radius)
        for (x in 0..right) {
            val color = input[y * width + x]
            a += android.graphics.Color.alpha(color); r += android.graphics.Color.red(color)
            g += android.graphics.Color.green(color); b += android.graphics.Color.blue(color)
        }
        for (x in 0 until width) {
            val left = maxOf(0, x - radius)
            right = minOf(width - 1, x + radius)
            val count = right - left + 1
            output[y * width + x] = android.graphics.Color.argb(a / count, r / count, g / count, b / count)
            val remove = x - radius
            if (remove >= 0) {
                val color = input[y * width + remove]
                a -= android.graphics.Color.alpha(color); r -= android.graphics.Color.red(color)
                g -= android.graphics.Color.green(color); b -= android.graphics.Color.blue(color)
            }
            val add = x + radius + 1
            if (add < width) {
                val color = input[y * width + add]
                a += android.graphics.Color.alpha(color); r += android.graphics.Color.red(color)
                g += android.graphics.Color.green(color); b += android.graphics.Color.blue(color)
            }
        }
    }
}

private suspend fun blurColumns(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
    for (x in 0 until width) {
        if (x and 15 == 0) coroutineContext.ensureActive()
        var a = 0; var r = 0; var g = 0; var b = 0
        var bottom = minOf(height - 1, radius)
        for (y in 0..bottom) {
            val color = input[y * width + x]
            a += android.graphics.Color.alpha(color); r += android.graphics.Color.red(color)
            g += android.graphics.Color.green(color); b += android.graphics.Color.blue(color)
        }
        for (y in 0 until height) {
            val top = maxOf(0, y - radius)
            bottom = minOf(height - 1, y + radius)
            val count = bottom - top + 1
            output[y * width + x] = android.graphics.Color.argb(a / count, r / count, g / count, b / count)
            val remove = y - radius
            if (remove >= 0) {
                val color = input[remove * width + x]
                a -= android.graphics.Color.alpha(color); r -= android.graphics.Color.red(color)
                g -= android.graphics.Color.green(color); b -= android.graphics.Color.blue(color)
            }
            val add = y + radius + 1
            if (add < height) {
                val color = input[add * width + x]
                a += android.graphics.Color.alpha(color); r += android.graphics.Color.red(color)
                g += android.graphics.Color.green(color); b += android.graphics.Color.blue(color)
            }
        }
    }
}

internal const val WorkspaceGlassAlgorithmVersion = 2
internal const val WorkspaceGlassBlurRadius = 2

internal fun decodeMeasuredBackground(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    decode: (BitmapFactory.Options) -> Bitmap?,
): Bitmap? {
    val bitmap = decode(
        BitmapFactory.Options().apply {
            inSampleSize = workspaceBackgroundSampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return null
    val plan = workspaceBackgroundCropPlan(bitmap.width, bitmap.height, targetWidth, targetHeight)
    if (plan.sourceLeft == 0 && plan.sourceTop == 0 &&
        plan.sourceRight == bitmap.width && plan.sourceBottom == bitmap.height &&
        targetWidth == bitmap.width && targetHeight == bitmap.height
    ) return bitmap
    var output: Bitmap? = null
    return try {
        output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
                bitmap,
                Rect(plan.sourceLeft, plan.sourceTop, plan.sourceRight, plan.sourceBottom),
                Rect(0, 0, targetWidth, targetHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        bitmap.recycle()
        output
    } catch (failure: OutOfMemoryError) {
        output?.recycle()
        bitmap.recycle()
        throw failure
    } catch (failure: RuntimeException) {
        output?.recycle()
        bitmap.recycle()
        throw failure
    }
}

private const val RequestedDecodeAttempts = 2
private const val RequestedDecodeRetryMillis = 200L


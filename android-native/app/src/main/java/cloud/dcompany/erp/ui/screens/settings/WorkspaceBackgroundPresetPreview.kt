package cloud.dcompany.erp.ui.screens.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private object WorkspacePresetThumbnailCache {
    private const val BudgetKiB = 1_024
    private val cache = object : LruCache<String, Bitmap>(BudgetKiB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1_024).coerceAtLeast(1)
    }

    @Synchronized fun get(key: String): Bitmap? = cache.get(key)
    @Synchronized fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
        // Compose may still lease an evicted bitmap. Eviction releases only the cache reference.
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private val WorkspacePresetThumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
internal fun WorkspaceBackgroundPresetPreview(
    preset: WorkspaceBackgroundPreset,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = WorkspacePresetThumbnailCache.get(preset.contentId), preset.contentId) {
        if (value != null) return@produceState
        value = withContext(WorkspacePresetThumbnailDispatcher) {
            WorkspacePresetThumbnailCache.get(preset.contentId) ?: run {
                var owned: Bitmap? = null
                try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 8
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeResource(context.resources, preset.resource, options)
                    ?: return@run null
                owned = decoded
                val thumbnail = if (decoded.width == 320 && decoded.height == 180) decoded else {
                    Bitmap.createScaledBitmap(decoded, 320, 180, true).also { scaled ->
                        if (scaled !== decoded) {
                            decoded.recycle()
                            owned = scaled
                        }
                    }
                }
                coroutineContext.ensureActive()
                WorkspacePresetThumbnailCache.put(preset.contentId, thumbnail)
                owned = null
                thumbnail
                } finally {
                    owned?.takeUnless(Bitmap::isRecycled)?.recycle()
                }
            }
        }
    }
    val base = modifier.fillMaxWidth().aspectRatio(16f / 9f)
        .testTag("workspace-background-preview-${preset.id}")
    val frame = bitmap
    if (frame == null) {
        androidx.compose.foundation.layout.Box(base.background(Color(0xFF111824)))
    } else {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "${preset.label} preview",
            contentScale = ContentScale.Crop,
            modifier = base,
        )
    }
}

package cloud.dcompany.erp.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.ByteArrayOutputStream

internal const val BUG_REPORT_ATTACHMENT_MAX_BYTES = 2 * 1024 * 1024
internal const val BUG_REPORT_ATTACHMENT_SOURCE_MAX_BYTES = 12 * 1024 * 1024
private const val MAX_PREVIEW_EDGE = 1_600
private const val MAX_SOURCE_EDGE = 20_000
private const val MAX_SOURCE_PIXELS = 100_000_000L

data class BugReportAttachmentDraft(
    val filename: String,
    val contentType: String,
    val content: ByteArray,
) {
    val byteSize: Int get() = content.size
}

sealed interface BugReportAttachmentLoadResult {
    data class Ready(val attachment: BugReportAttachmentDraft) : BugReportAttachmentLoadResult
    data class Rejected(val message: String) : BugReportAttachmentLoadResult
}

/**
 * Reads only a deliberately selected image. It is decoded, scaled and
 * re-encoded, which drops EXIF/location metadata and prevents arbitrary file
 * bytes from entering the durable outbox. No screen-capture API is used.
 */
suspend fun loadBugReportAttachment(
    context: Context,
    uri: Uri,
): BugReportAttachmentLoadResult = withContext(Dispatchers.IO) {
    val sourceRead = try {
        context.contentResolver.openInputStream(uri)?.use(::readBugReportSource)
    } catch (_: Exception) {
        null
    }
    val source = when (sourceRead) {
        is BugReportSourceRead.Ready -> sourceRead.content
        BugReportSourceRead.TooLarge -> return@withContext BugReportAttachmentLoadResult.Rejected(
            "Choose a PNG, JPEG or WebP image no larger than 12 MiB. It will be compressed before sending.",
        )
        null -> return@withContext BugReportAttachmentLoadResult.Rejected(
            "This image could not be opened. Choose another screenshot.",
        )
    }

    try {
        sanitizeBugReportImage(source)
    } catch (_: OutOfMemoryError) {
        BugReportAttachmentLoadResult.Rejected(
            "This image needs too much tablet memory. Crop it, then choose it again.",
        )
    } catch (_: Exception) {
        BugReportAttachmentLoadResult.Rejected(
            "This image could not be prepared safely. Choose another screenshot.",
        )
    }
}

/** Pure image policy once a bounded provider stream has been read. */
internal fun sanitizeBugReportImage(source: ByteArray): BugReportAttachmentLoadResult {
    if (source.isEmpty() || detectImageType(source) == null) {
        return BugReportAttachmentLoadResult.Rejected(
            "Only real PNG, JPEG or WebP images can be attached.",
        )
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return BugReportAttachmentLoadResult.Rejected(
            "This image is damaged or unsupported. Choose another screenshot.",
        )
    }
    if (bounds.outWidth > MAX_SOURCE_EDGE || bounds.outHeight > MAX_SOURCE_EDGE ||
        bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_SOURCE_PIXELS
    ) {
        return BugReportAttachmentLoadResult.Rejected(
            "This image is too large to process safely. Crop it, then choose it again.",
        )
    }
    var sample = 1
    while (bounds.outWidth / sample > MAX_PREVIEW_EDGE * 2 ||
        bounds.outHeight / sample > MAX_PREVIEW_EDGE * 2
    ) {
        sample *= 2
    }
    val decoded = runCatching {
        BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull() ?: return BugReportAttachmentLoadResult.Rejected(
        "This image could not be decoded safely. Choose another screenshot.",
    )
    val scale = minOf(
        1f,
        MAX_PREVIEW_EDGE.toFloat() / maxOf(decoded.width, decoded.height).toFloat(),
    )
    val sanitised = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { decoded.recycle() }
    } else {
        decoded
    }

    val encoded = encodeWithinLimit(sanitised)
    sanitised.recycle()
    if (encoded == null) {
        return BugReportAttachmentLoadResult.Rejected(
            "The safe preview is still larger than 2 MiB. Crop the screenshot and choose it again.",
        )
    }
    return BugReportAttachmentLoadResult.Ready(
        BugReportAttachmentDraft(
            // Never preserve a provider filename: it may contain customer or
            // employee information. The server receives a neutral name.
            filename = "support-image.jpg",
            contentType = "image/jpeg",
            content = encoded,
        ),
    )
}

internal sealed interface BugReportSourceRead {
    data class Ready(val content: ByteArray) : BugReportSourceRead
    data object TooLarge : BugReportSourceRead
}

/**
 * Tablet screenshots can be larger than the server's 2 MiB attachment limit.
 * Read a bounded source first, then the caller downsizes and strips metadata.
 * The 12 MiB ceiling prevents an untrusted provider from exhausting memory.
 */
internal fun readBugReportSource(
    input: InputStream,
    maximumBytes: Int = BUG_REPORT_ATTACHMENT_SOURCE_MAX_BYTES,
): BugReportSourceRead {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (total > maximumBytes - read) return BugReportSourceRead.TooLarge
        output.write(buffer, 0, read)
        total += read
    }
    return BugReportSourceRead.Ready(output.toByteArray())
}

/** Decode the already-sanitised preview away from Compose's main thread. */
internal suspend fun decodeBugReportPreview(content: ByteArray): ImageBitmap? =
    withContext(Dispatchers.Default) {
        try {
            BitmapFactory.decodeByteArray(content, 0, content.size)?.asImageBitmap()
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

private fun encodeWithinLimit(bitmap: Bitmap): ByteArray? {
    for (quality in listOf(92, 84, 76, 68)) {
        val output = ByteArrayOutputStream()
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            val bytes = output.toByteArray()
            if (bytes.size <= BUG_REPORT_ATTACHMENT_MAX_BYTES) return bytes
        }
    }
    return null
}

private fun detectImageType(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    ) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

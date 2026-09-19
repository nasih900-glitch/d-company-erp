package cloud.dcompany.erp.ui.screens.finance

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal const val MAX_EXPENSE_RECEIPT_BYTES = 10 * 1024 * 1024
internal const val EXPENSE_RECEIPT_CHUNK_BYTES = 256 * 1024
internal const val EXPENSE_RECEIPT_CAMERA_SOURCE = "camera"
internal const val EXPENSE_RECEIPT_GALLERY_SOURCE = "gallery"
internal const val EXPENSE_RECEIPT_FILE_SOURCE = "file"

internal val EXPENSE_RECEIPT_PICKER_TYPES = arrayOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "application/pdf",
)

internal data class ExpenseReceiptDraft(
    val filename: String,
    val contentType: String,
    val content: ByteArray,
    val source: String,
)

internal sealed interface ExpenseReceiptLoadResult {
    data class Ready(val receipt: ExpenseReceiptDraft) : ExpenseReceiptLoadResult
    data class Rejected(val message: String) : ExpenseReceiptLoadResult
}

internal fun createExpenseReceiptCameraUri(context: Context): Uri {
    val directory = File(context.cacheDir, EXPENSE_RECEIPT_CAMERA_DIRECTORY).apply { mkdirs() }
    val file = File.createTempFile(
        EXPENSE_RECEIPT_CAMERA_PREFIX,
        EXPENSE_RECEIPT_CAMERA_SUFFIX,
        directory,
    )
    return try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.expense-receipts",
            file,
        )
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

internal fun pruneStaleExpenseReceiptCameraFiles(context: Context, activeUri: Uri? = null) =
    pruneExpenseReceiptCameraFiles(
        directory = File(context.cacheDir, EXPENSE_RECEIPT_CAMERA_DIRECTORY),
        activeFilename = activeUri?.lastPathSegment,
        nowMillis = System.currentTimeMillis(),
    )

internal fun discardExpenseReceiptCameraUri(context: Context, uri: Uri?) {
    if (uri == null) return
    runCatching { context.contentResolver.delete(uri, null, null) }
}

/**
 * Owns the temporary camera target until Android accepts the launch. A launch
 * can fail synchronously (for example, no camera activity); in that case the
 * just-created file is removed before the error returns to the form.
 */
internal fun <T> launchExpenseReceiptCapture(
    create: () -> T,
    retain: (T) -> Unit,
    launch: (T) -> Unit,
    discard: (T) -> Unit,
): Result<T> {
    val target = try {
        create()
    } catch (error: Exception) {
        return Result.failure(error)
    }
    return try {
        retain(target)
        launch(target)
        Result.success(target)
    } catch (error: Exception) {
        runCatching { discard(target) }
        Result.failure(error)
    }
}

internal suspend fun loadExpenseReceipt(
    context: Context,
    uri: Uri,
    source: String,
): ExpenseReceiptLoadResult = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(minOf(MAX_EXPENSE_RECEIPT_BYTES, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_EXPENSE_RECEIPT_BYTES) {
                    return@withContext ExpenseReceiptLoadResult.Rejected(
                        "Receipt files must be 10 MB or smaller.",
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: return@withContext ExpenseReceiptLoadResult.Rejected(
            "The selected receipt could not be opened. Choose it again.",
        )

        if (bytes.isEmpty()) {
            return@withContext ExpenseReceiptLoadResult.Rejected("The selected receipt is empty.")
        }
        val detectedType = detectExpenseReceiptType(bytes)
            ?: return@withContext ExpenseReceiptLoadResult.Rejected(
                "Choose a JPEG, PNG, WebP, or PDF receipt.",
            )
        if (detectedType.startsWith("image/") && !hasDecodableImageBounds(bytes)) {
            return@withContext ExpenseReceiptLoadResult.Rejected(
                "This image is damaged or is not a supported receipt photo.",
            )
        }
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        val extension = when (detectedType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".pdf"
        }
        val filename = sanitizeExpenseReceiptFilename(displayName, extension)
        val canonicalSource = when {
            source == EXPENSE_RECEIPT_CAMERA_SOURCE -> EXPENSE_RECEIPT_CAMERA_SOURCE
            detectedType == "application/pdf" -> EXPENSE_RECEIPT_FILE_SOURCE
            else -> EXPENSE_RECEIPT_GALLERY_SOURCE
        }
        ExpenseReceiptLoadResult.Ready(
            ExpenseReceiptDraft(
                filename = filename,
                contentType = detectedType,
                content = bytes,
                source = canonicalSource,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ExpenseReceiptLoadResult.Rejected(
            "The selected receipt could not be read. Choose it again.",
        )
    }
}

private fun hasDecodableImageBounds(bytes: ByteArray): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return options.outWidth > 0 && options.outHeight > 0
}

internal fun sanitizeExpenseReceiptFilename(name: String?, extension: String): String {
    val cleaned = name.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { it >= ' ' && it != '"' }
        .trim()
    val basename = cleaned.substringBeforeLast('.', cleaned).trim().ifBlank { "receipt" }
    return basename.take(200 - extension.length).trimEnd() + extension
}

internal fun detectExpenseReceiptType(bytes: ByteArray): String? {
    if (bytes.size >= 4 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() &&
        bytes.lastNonWhitespacePairIs(0xFF.toByte(), 0xD9.toByte())
    ) return "image/jpeg"
    if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
    ) return "image/png"
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    ) return "image/webp"
    if (bytes.size >= 10 &&
        bytes.copyOfRange(0, 5).decodeToString() == "%PDF-" &&
        bytes.lastIndexOfAscii("%%EOF", maxTailBytes = 4_096) >= 0
    ) {
        return "application/pdf"
    }
    return null
}

private fun ByteArray.lastNonWhitespacePairIs(first: Byte, second: Byte): Boolean {
    var end = lastIndex
    while (end >= 0 && this[end].toInt().toChar().isWhitespace()) end--
    return end >= 1 && this[end - 1] == first && this[end] == second
}

private fun ByteArray.lastIndexOfAscii(value: String, maxTailBytes: Int): Int {
    val needle = value.encodeToByteArray()
    val start = (size - maxTailBytes).coerceAtLeast(0)
    for (index in size - needle.size downTo start) {
        if (needle.indices.all { this[index + it] == needle[it] }) return index
    }
    return -1
}

internal fun expenseReceiptIdempotencyKey(localId: String): String =
    "expense-receipt:$localId"

internal fun expenseReceiptSha256(content: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString(separator = "") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

internal fun expenseReceiptContentChunks(content: ByteArray): List<ByteArray> {
    require(content.isNotEmpty()) { "Receipt content cannot be empty." }
    return content.indices.step(EXPENSE_RECEIPT_CHUNK_BYTES).map { start ->
        content.copyOfRange(start, minOf(start + EXPENSE_RECEIPT_CHUNK_BYTES, content.size))
    }
}

internal fun reassembleExpenseReceiptContent(
    chunks: List<ByteArray>,
    expectedSize: Int,
    expectedSha256: String,
): ByteArray {
    require(chunks.isNotEmpty()) { "The saved receipt payload is missing." }
    require(chunks.dropLast(1).all { it.size == EXPENSE_RECEIPT_CHUNK_BYTES }) {
        "The saved receipt chunks are incomplete."
    }
    require(chunks.last().size in 1..EXPENSE_RECEIPT_CHUNK_BYTES) {
        "The saved receipt chunk is invalid."
    }
    require(chunks.sumOf(ByteArray::size) == expectedSize) {
        "The saved receipt size does not match its metadata."
    }
    val content = ByteArray(expectedSize)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(content, destinationOffset = offset)
        offset += chunk.size
    }
    require(expenseReceiptSha256(content) == expectedSha256) {
        "The saved receipt failed its integrity check."
    }
    return content
}

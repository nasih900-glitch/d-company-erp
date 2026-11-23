package cloud.dcompany.erp.ui.theme

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import android.system.Os
import android.system.OsConstants
import androidx.annotation.DrawableRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.R
import cloud.dcompany.erp.ui.Destination
import java.io.File
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.coroutines.resume

internal enum class WorkspaceBackgroundSlot(
    val visibleLabel: String,
    val privateFileName: String,
    @DrawableRes val defaultResource: Int,
) {
    HOME("Home cover", "home.jpg", R.drawable.workspace_home_default),
    WORKSPACE("Workspace cover", "workspace.jpg", R.drawable.workspace_shared_default),
}

internal fun workspaceBackgroundSlot(destination: Destination): WorkspaceBackgroundSlot =
    WorkspaceBackgroundSlot.WORKSPACE

internal enum class WorkspaceBackgroundSource { BUNDLED, IMPORTED }

internal data class WorkspaceBackgroundImage(
    val slot: WorkspaceBackgroundSlot,
    val source: WorkspaceBackgroundSource,
    val width: Int,
    val height: Int,
    val generation: Long,
    val contentId: String,
    val privatePath: String? = null,
    @DrawableRes val resource: Int? = null,
    val presetId: String? = null,
) {
    val dimensionsLabel: String get() = "${width} × ${height}"
}

internal data class WorkspaceBackgroundUiState(
    val home: WorkspaceBackgroundImage = bundledWorkspaceBackground(WorkspaceBackgroundSlot.HOME),
    val workspace: WorkspaceBackgroundImage = bundledWorkspaceBackground(WorkspaceBackgroundSlot.WORKSPACE),
    val selection: WorkspaceBackgroundSelection = WorkspaceBackgroundSelection.default(),
    val savedCustomAvailable: Boolean = false,
    val legacyHomeCustomAvailable: Boolean = false,
    val busySlot: WorkspaceBackgroundSlot? = null,
    val cancelling: Boolean = false,
    val initializing: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    fun image(slot: WorkspaceBackgroundSlot): WorkspaceBackgroundImage =
        if (slot == WorkspaceBackgroundSlot.HOME) home else workspace

    fun replacing(slot: WorkspaceBackgroundSlot, image: WorkspaceBackgroundImage): WorkspaceBackgroundUiState =
        if (slot == WorkspaceBackgroundSlot.HOME) copy(home = image) else copy(workspace = image)
}

internal fun bundledWorkspaceBackground(
    slot: WorkspaceBackgroundSlot,
    generation: Long = 0L,
    presetId: String = WorkspaceBackgroundPresets.DefaultId,
): WorkspaceBackgroundImage {
    if (slot == WorkspaceBackgroundSlot.HOME) return WorkspaceBackgroundImage(
        slot = slot,
        source = WorkspaceBackgroundSource.BUNDLED,
        width = 3_840,
        height = 2_160,
        generation = generation,
        contentId = "ce0f12e8b46011eaa69161c031d23d10e545f9b89f2f49a6effb11f34640dcfe",
        resource = slot.defaultResource,
    )
    val preset = WorkspaceBackgroundPresets.find(presetId) ?: WorkspaceBackgroundPresets.default
    return WorkspaceBackgroundImage(
        slot = WorkspaceBackgroundSlot.WORKSPACE,
        source = WorkspaceBackgroundSource.BUNDLED,
        width = preset.width,
        height = preset.height,
        generation = generation,
        contentId = preset.contentId,
        resource = preset.resource,
        presetId = preset.id,
    )
}

internal data class WorkspaceBackgroundBounds(
    val width: Int,
    val height: Int,
    val orientation: Int,
) {
    val orientedWidth: Int get() = if (orientation in 5..8) height else width
    val orientedHeight: Int get() = if (orientation in 5..8) width else height
}

internal data class WorkspaceBackgroundDecodePlan(
    val sampleSize: Int,
    val outputWidth: Int,
    val outputHeight: Int,
)

internal const val WorkspaceBackgroundInputCapBytes = 32L * 1_024L * 1_024L
internal const val WorkspaceBackgroundCanonicalCapBytes = 20L * 1_024L * 1_024L

internal fun validateWorkspaceBackgroundInputLength(bytes: Long): String? = when {
    bytes <= 0L -> "The selected image is empty."
    bytes > WorkspaceBackgroundInputCapBytes -> "Images may not exceed 32 MiB."
    else -> null
}

internal fun validateWorkspaceBackgroundCanonicalLength(bytes: Long): String? = when {
    bytes <= 0L -> "The normalized image is empty."
    bytes > WorkspaceBackgroundCanonicalCapBytes -> "The normalized image exceeds the 20 MiB device limit."
    else -> null
}

internal fun workspaceBackgroundDecodePlan(
    width: Int,
    height: Int,
    maximumEdge: Int = 4_096,
    maximumPixels: Long = 10_000_000L,
): WorkspaceBackgroundDecodePlan {
    require(width > 0 && height > 0)
    val scale = minOf(
        1.0,
        maximumEdge.toDouble() / max(width, height),
        sqrt(maximumPixels.toDouble() / (width.toLong() * height.toLong())),
    )
    return WorkspaceBackgroundDecodePlan(
        // BitmapFactory's density scaler performs the bounded non-power-of-two reduction during
        // decode. Keeping inSampleSize at one avoids the 4097px -> 2049px quality cliff.
        sampleSize = 1,
        outputWidth = (width * scale).toInt().coerceAtLeast(1),
        outputHeight = (height * scale).toInt().coerceAtLeast(1),
    )
}

internal fun validateWorkspaceBackgroundBounds(bounds: WorkspaceBackgroundBounds): String? {
    val width = bounds.orientedWidth
    val height = bounds.orientedHeight
    return when {
        bounds.width <= 0 || bounds.height <= 0 -> "The selected file is not a readable image."
        bounds.width > 8_192 || bounds.height > 8_192 -> "Images may not exceed 8192 pixels on either edge."
        bounds.width.toLong() * bounds.height.toLong() > 24_000_000L -> "Images may not exceed 24 megapixels."
        width <= height -> "Choose a landscape image."
        max(width, height) < 2_560 || minOf(width, height) < 1_080 ->
            "Choose a landscape image at least 2560 × 1080."
        else -> null
    }
}

internal data class WorkspaceBackgroundMutation(
    val image: WorkspaceBackgroundImage,
    val selection: WorkspaceBackgroundSelection = WorkspaceBackgroundSelection.default(image.generation),
    val savedCustomAvailable: Boolean = image.source == WorkspaceBackgroundSource.IMPORTED,
    val legacyHomeCustomAvailable: Boolean = false,
    val durabilityWarning: String? = null,
)

internal class WorkspaceBackgroundInputException(message: String) : Exception(message)

private fun backgroundInputError(message: String): Nothing = throw WorkspaceBackgroundInputException(message)

private val WorkspaceBackgroundCancellationExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "workspace-background-cancel").apply { isDaemon = true }
}

internal enum class WorkspaceBackgroundOperationPhase { ACTIVE, COMMITTING, COMMITTED, CANCELLED }

internal class WorkspaceBackgroundCommitArbiter {
    private val phase = AtomicReference(WorkspaceBackgroundOperationPhase.ACTIVE)

    fun cancel(): Boolean = phase.compareAndSet(
        WorkspaceBackgroundOperationPhase.ACTIVE,
        WorkspaceBackgroundOperationPhase.CANCELLED,
    )

    fun beginCommit(): Boolean = phase.compareAndSet(
        WorkspaceBackgroundOperationPhase.ACTIVE,
        WorkspaceBackgroundOperationPhase.COMMITTING,
    )

    fun committed() {
        check(phase.compareAndSet(
            WorkspaceBackgroundOperationPhase.COMMITTING,
            WorkspaceBackgroundOperationPhase.COMMITTED,
        ))
    }

    fun phase(): WorkspaceBackgroundOperationPhase = phase.get()
}

internal fun atomicReplaceWorkspaceBackground(
    temp: File,
    target: File,
    arbiter: WorkspaceBackgroundCommitArbiter,
    move: (File, File) -> Unit = { source, destination ->
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
    forceDirectory: () -> String? = { null },
): String? {
    check(arbiter.beginCommit()) { "This image change was cancelled before it could be saved." }
    try {
        move(temp, target)
    } catch (_: AtomicMoveNotSupportedException) {
        error("This tablet cannot atomically store the selected image.")
    }
    arbiter.committed()
    return forceDirectory()
}

internal fun resetWorkspaceBackgroundFile(
    target: File,
    arbiter: WorkspaceBackgroundCommitArbiter,
    delete: (File) -> Boolean = File::delete,
    forceDirectory: () -> String? = { null },
): String? {
    check(arbiter.beginCommit()) { "This image change was cancelled before it could be saved." }
    val removed = !target.exists() || delete(target)
    if (!removed) error("Could not reset the selected background.")
    arbiter.committed()
    return forceDirectory()
}

internal interface WorkspaceBackgroundRepository {
    suspend fun load(): WorkspaceBackgroundUiState
    suspend fun import(
        slot: WorkspaceBackgroundSlot,
        uri: Uri,
        arbiter: WorkspaceBackgroundCommitArbiter = WorkspaceBackgroundCommitArbiter(),
    ): WorkspaceBackgroundMutation
    suspend fun reset(
        slot: WorkspaceBackgroundSlot,
        arbiter: WorkspaceBackgroundCommitArbiter = WorkspaceBackgroundCommitArbiter(),
    ): WorkspaceBackgroundMutation
    suspend fun selectPreset(
        presetId: String,
        arbiter: WorkspaceBackgroundCommitArbiter = WorkspaceBackgroundCommitArbiter(),
    ): WorkspaceBackgroundMutation
    suspend fun useSavedCustom(
        arbiter: WorkspaceBackgroundCommitArbiter = WorkspaceBackgroundCommitArbiter(),
    ): WorkspaceBackgroundMutation
}

internal class WorkspaceBackgroundStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceBackgroundRepository {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "workspace-backgrounds")
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutex: Mutex get() = StorageMutex

    override suspend fun load(): WorkspaceBackgroundUiState = withContext(ioDispatcher) {
        mutex.withLock {
            cleanupRecognizedTemps()
            loadUnifiedLocked()
        }
    }

    override suspend fun import(
        slot: WorkspaceBackgroundSlot,
        uri: Uri,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = withContext(ioDispatcher) {
        withMutationAdmission { importLocked(WorkspaceBackgroundSlot.WORKSPACE, uri, arbiter) }
    }

    override suspend fun reset(
        slot: WorkspaceBackgroundSlot,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = selectPreset(WorkspaceBackgroundPresets.DefaultId, arbiter)

    override suspend fun selectPreset(
        presetId: String,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = withContext(ioDispatcher) {
        withMutationAdmission { selectPresetLocked(presetId, arbiter) }
    }

    override suspend fun useSavedCustom(
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = withContext(ioDispatcher) {
        withMutationAdmission { useSavedCustomLocked(arbiter) }
    }

    private suspend inline fun <T> withMutationAdmission(block: () -> T): T =
        mutex.withLock { block() }

    private fun loadUnifiedLocked(): WorkspaceBackgroundUiState {
        ensureDirectory()
        val warnings = mutableListOf<String>()
        val workspaceCustom = inspectCustom(WorkspaceBackgroundSlot.WORKSPACE)
        val homeCustom = inspectCustom(WorkspaceBackgroundSlot.HOME)
        var selection = readSelection(warnings)
        if (selection == null) {
            val generation = nextGeneration(null)
            selection = when {
                workspaceCustom != null -> WorkspaceBackgroundSelection(
                    WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE,
                    generation = generation,
                    expectedCustomContentId = workspaceCustom.contentId,
                )
                homeCustom != null -> WorkspaceBackgroundSelection(
                    WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME,
                    generation = generation,
                    expectedCustomContentId = homeCustom.contentId,
                )
                else -> WorkspaceBackgroundSelection.default(generation)
            }
            runCatching { writeSelection(selection, arbiter = null) }
                .onFailure { warnings += "The workspace image is available, but its selection could not be saved for restart." }
        }
        selection = reconcileSelectionWithLoadedCustom(
            selection = selection,
            workspaceCustom = workspaceCustom,
            legacyHomeCustom = homeCustom,
            nextGeneration = { nextGeneration(selection) },
            persist = { writeSelection(it, arbiter = null) },
            warnings = warnings,
        )
        val image = resolveSelection(selection, workspaceCustom, homeCustom, warnings)
        return WorkspaceBackgroundUiState(
            home = homeCustom ?: bundledWorkspaceBackground(WorkspaceBackgroundSlot.HOME),
            workspace = image,
            selection = selection,
            savedCustomAvailable = workspaceCustom != null || homeCustom != null,
            legacyHomeCustomAvailable = homeCustom != null,
            error = warnings.firstOrNull(),
        )
    }

    private fun selectPresetLocked(
        presetId: String,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation {
        val preset = WorkspaceBackgroundPresets.find(presetId)
            ?: backgroundInputError("That bundled workspace image is not available.")
        ensureDirectory()
        val warnings = mutableListOf<String>()
        val current = readSelection(warnings)
        if (current?.kind == WorkspaceBackgroundSelectionKind.PRESET && current.presetId == preset.id) {
            return mutationFor(
                bundledWorkspaceBackground(WorkspaceBackgroundSlot.WORKSPACE, current.generation, preset.id),
                current,
                warnings.firstOrNull(),
            )
        }
        val next = WorkspaceBackgroundSelection(
            kind = WorkspaceBackgroundSelectionKind.PRESET,
            presetId = preset.id,
            generation = nextGeneration(current),
        )
        val warning = writeSelection(next, arbiter)
        return mutationFor(
            bundledWorkspaceBackground(WorkspaceBackgroundSlot.WORKSPACE, next.generation, preset.id),
            next,
            warning,
        )
    }

    private fun useSavedCustomLocked(
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation {
        ensureDirectory()
        val workspaceCustom = inspectCustom(WorkspaceBackgroundSlot.WORKSPACE)
        val legacyHomeCustom = inspectCustom(WorkspaceBackgroundSlot.HOME)
        val custom = workspaceCustom ?: legacyHomeCustom
            ?: backgroundInputError("No saved custom workspace image is available on this tablet.")
        val selectedKind = if (workspaceCustom != null) {
            WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE
        } else {
            WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME
        }
        val warnings = mutableListOf<String>()
        val current = readSelection(warnings)
        if (current?.kind == selectedKind &&
            current.expectedCustomContentId == custom.contentId
        ) return mutationFor(custom.copy(generation = current.generation), current, warnings.firstOrNull())
        val next = WorkspaceBackgroundSelection(
            kind = selectedKind,
            generation = nextGeneration(current),
            expectedCustomContentId = custom.contentId,
        )
        val warning = writeSelection(next, arbiter)
        return mutationFor(custom.copy(generation = next.generation), next, warning)
    }

    private suspend fun importLocked(
        slot: WorkspaceBackgroundSlot,
        uri: Uri,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation {
        if (uri.scheme != "content") backgroundInputError("Choose an image from Android's document picker.")
        ensureDirectory()
        cleanupRecognizedTemps()
        val priorState = loadUnifiedLocked()
        val priorSelection = priorState.selection
        var inputTemp: File? = null
        var outputTemp: File? = null
        return try {
            inputTemp = File(directory, ".${slot.privateFileName}.input-${UUID.randomUUID()}")
            copyContentToPrivateTemp(uri, inputTemp)
            val format = detectFormat(inputTemp)
            if (format == WorkspaceImageFormat.UNKNOWN) backgroundInputError("Choose a JPEG or PNG image.")
            val inspection = inspectWorkspaceImageContainer(inputTemp, format)
            if (!inspection.complete) backgroundInputError("The selected image is incomplete or damaged.")
            if (inspection.animatedPng) backgroundInputError("Animated PNG files are not supported.")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputTemp.absolutePath, options)
            val orientation = if (format == WorkspaceImageFormat.JPEG) readOrientation(inputTemp) else 1
            val bounds = WorkspaceBackgroundBounds(options.outWidth, options.outHeight, orientation)
            validateWorkspaceBackgroundBounds(bounds)?.let(::backgroundInputError)
            coroutineContext.ensureActive()
            val plan = workspaceBackgroundDecodePlan(bounds.orientedWidth, bounds.orientedHeight)
            outputTemp = File(directory, ".${slot.privateFileName}.tmp-${UUID.randomUUID()}")
            val (outputWidth, outputHeight) = try {
                canonicalizeImage(inputTemp, outputTemp, orientation, plan, options.outWidth, options.outHeight)
            } catch (_: OutOfMemoryError) {
                backgroundInputError("The selected image is too large for this tablet.")
            }
            if (!inspectWorkspaceImageContainer(outputTemp, WorkspaceImageFormat.JPEG).complete) {
                backgroundInputError("The normalized image could not be verified.")
            }
            validateWorkspaceBackgroundCanonicalLength(outputTemp.length())?.let(::backgroundInputError)
            coroutineContext.ensureActive()
            val contentId = sha256(outputTemp)
            val target = slotFile(slot)
            val durabilityWarning = atomicReplaceWorkspaceBackground(
                temp = outputTemp,
                target = target,
                arbiter = arbiter,
                forceDirectory = ::forceDirectoryAfterCommit,
            )
            outputTemp = null
            val generation = nextGeneration(priorSelection)
            val nextSelection = WorkspaceBackgroundSelection(
                kind = WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE,
                generation = generation,
                expectedCustomContentId = contentId,
            )
            val committed = WorkspaceBackgroundImage(
                slot = WorkspaceBackgroundSlot.WORKSPACE,
                source = WorkspaceBackgroundSource.IMPORTED,
                width = outputWidth,
                height = outputHeight,
                generation = generation,
                contentId = contentId,
                privatePath = target.absolutePath,
            )
            val selectionWarning = runCatching { writeSelection(nextSelection, arbiter = null) }
                .fold(
                    onSuccess = { it },
                    onFailure = {
                        if (priorSelection.kind == WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE) {
                            return WorkspaceBackgroundMutation(
                                image = committed.copy(generation = priorSelection.generation),
                                selection = priorSelection.copy(expectedCustomContentId = contentId),
                                savedCustomAvailable = true,
                                legacyHomeCustomAvailable = priorState.legacyHomeCustomAvailable,
                                durabilityWarning = "The replacement is active, but its restart metadata needs repair.",
                            )
                        }
                        return WorkspaceBackgroundMutation(
                            image = priorState.workspace,
                            selection = priorSelection,
                            savedCustomAvailable = true,
                            legacyHomeCustomAvailable = priorState.legacyHomeCustomAvailable,
                            durabilityWarning = "The custom image was saved, but the previous background remains active because the selection could not be saved.",
                        )
                    },
                )
            WorkspaceBackgroundMutation(
                image = committed,
                selection = nextSelection,
                savedCustomAvailable = true,
                legacyHomeCustomAvailable = priorState.legacyHomeCustomAvailable,
                durabilityWarning = durabilityWarning ?: selectionWarning,
            )
        } catch (cancelled: CancellationException) {
            inputTemp?.delete()
            outputTemp?.delete()
            throw cancelled
        } catch (failure: Exception) {
            inputTemp?.delete()
            outputTemp?.delete()
            throw failure
        } finally {
            inputTemp?.delete()
        }
    }

    private suspend fun copyContentToPrivateTemp(uri: Uri, target: File): Long =
        suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val descriptorRef = AtomicReference<android.content.res.AssetFileDescriptor?>()
        val streamRef = AtomicReference<Closeable?>()
        val cancellationScheduled = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            if (cancellationScheduled.compareAndSet(false, true)) {
                WorkspaceBackgroundCancellationExecutor.execute {
                    runCatching { signal.cancel() }
                    runCatching { streamRef.getAndSet(null)?.close() }
                    runCatching { descriptorRef.getAndSet(null)?.close() }
                    target.delete()
                }
            }
        }
        try {
            val descriptor = appContext.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                ?: error("The selected image could not be opened.")
            descriptorRef.set(descriptor)
            val input = descriptor.createInputStream()
            streamRef.set(input)
            var total = 0L
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1_024)
                while (true) {
                    if (!continuation.isActive) throw CancellationException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    validateWorkspaceBackgroundInputLength(total)?.takeIf { total > WorkspaceBackgroundInputCapBytes }
                        ?.let(::backgroundInputError)
                    output.write(buffer, 0, read)
                }
                validateWorkspaceBackgroundInputLength(total)?.let(::backgroundInputError)
                output.fd.sync()
            }
            if (continuation.isActive) continuation.resume(total)
        } catch (failure: Exception) {
            target.delete()
            if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
        } finally {
            runCatching { streamRef.getAndSet(null)?.close() }
            runCatching { descriptorRef.getAndSet(null)?.close() }
        }
    }

    private fun canonicalizeImage(
        input: File,
        output: File,
        orientation: Int,
        plan: WorkspaceBackgroundDecodePlan,
        encodedWidth: Int,
        encodedHeight: Int,
    ): Pair<Int, Int> {
        var working: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            working = BitmapFactory.decodeFile(
                input.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = plan.sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = true
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                    }
                    val encodedEdge = max(encodedWidth, encodedHeight)
                    val outputEdge = max(plan.outputWidth, plan.outputHeight)
                    if (outputEdge < encodedEdge) {
                        // Explicit density scaling is available on every supported API and avoids
                        // allocating the full accepted 24MP source before normalization.
                        inScaled = true
                        inDensity = encodedEdge
                        inTargetDensity = outputEdge
                    }
                },
            ) ?: backgroundInputError("The selected image could not be decoded.")
            val oriented = normalizeOrientation(working, orientation)
            if (oriented !== working) {
                working.recycle()
                working = oriented
            }
            if (working.width != plan.outputWidth || working.height != plan.outputHeight) {
                val scaled = Bitmap.createScaledBitmap(working, plan.outputWidth, plan.outputHeight, true)
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                }
            }
            flattened = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(Color.rgb(8, 14, 23))
                drawBitmap(working, 0f, 0f, null)
            }
            FileOutputStream(output).use { stream ->
                check(flattened.compress(Bitmap.CompressFormat.JPEG, 94, stream)) {
                    "Could not encode the selected image."
                }
                stream.fd.sync()
            }
            return flattened.width to flattened.height
        } finally {
            working?.takeUnless(Bitmap::isRecycled)?.recycle()
            flattened?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun inspectCustom(slot: WorkspaceBackgroundSlot): WorkspaceBackgroundImage? {
        val file = slotFile(slot)
        if (!file.isFile || file.length() !in 1..WorkspaceBackgroundCanonicalCapBytes) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val complete = inspectWorkspaceImageContainer(file, WorkspaceImageFormat.JPEG).complete
        val valid = complete && canDecodeWorkspaceCanonical(file) &&
            options.outWidth > 0 && options.outHeight > 0 && options.outWidth > options.outHeight &&
            options.outWidth <= 4_096 && options.outHeight <= 4_096 &&
            options.outWidth.toLong() * options.outHeight.toLong() <= 10_000_000L
        if (!valid) return null
        return WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = options.outWidth,
            height = options.outHeight,
            generation = 0L,
            contentId = sha256(file),
            privatePath = file.absolutePath,
        )
    }

    private fun resolveSelection(
        selection: WorkspaceBackgroundSelection,
        workspaceCustom: WorkspaceBackgroundImage?,
        homeCustom: WorkspaceBackgroundImage?,
        warnings: MutableList<String>,
    ): WorkspaceBackgroundImage = when (selection.kind) {
        WorkspaceBackgroundSelectionKind.PRESET -> {
            val preset = WorkspaceBackgroundPresets.find(selection.presetId)
            if (preset == null) {
                warnings += "The saved workspace image choice is unavailable, so the default is active."
                bundledWorkspaceBackground(WorkspaceBackgroundSlot.WORKSPACE, selection.generation)
            } else bundledWorkspaceBackground(
                WorkspaceBackgroundSlot.WORKSPACE,
                selection.generation,
                preset.id,
            )
        }
        WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE -> {
            val custom = workspaceCustom
            if (custom == null) {
                warnings += "The saved custom workspace image is unreadable, so the default is active."
                bundledWorkspaceBackground(WorkspaceBackgroundSlot.WORKSPACE, selection.generation)
            } else {
                if (selection.expectedCustomContentId != null &&
                    selection.expectedCustomContentId != custom.contentId
                ) warnings += "The saved custom workspace image changed; its current device copy is active."
                custom.copy(generation = selection.generation.coerceAtLeast(1L))
            }
        }
        WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME -> {
            val custom = homeCustom
            if (custom == null) {
                warnings += "The previous Home image is unreadable, so the default is active."
                bundledWorkspaceBackground(WorkspaceBackgroundSlot.WORKSPACE, selection.generation)
            } else custom.copy(generation = selection.generation.coerceAtLeast(1L))
        }
    }

    private fun mutationFor(
        image: WorkspaceBackgroundImage,
        selection: WorkspaceBackgroundSelection,
        warning: String?,
    ) = WorkspaceBackgroundMutation(
        image = image,
        selection = selection,
        savedCustomAvailable = inspectCustom(WorkspaceBackgroundSlot.WORKSPACE) != null ||
            inspectCustom(WorkspaceBackgroundSlot.HOME) != null,
        legacyHomeCustomAvailable = inspectCustom(WorkspaceBackgroundSlot.HOME) != null,
        durabilityWarning = warning,
    )

    private fun nextGeneration(current: WorkspaceBackgroundSelection?): Long {
        val legacy = maxOf(
            preferences.getLong(generationKey(WorkspaceBackgroundSlot.HOME), 0L),
            preferences.getLong(generationKey(WorkspaceBackgroundSlot.WORKSPACE), 0L),
        ).coerceAtLeast(0L)
        val base = maxOf(current?.generation ?: 0L, legacy)
        check(base < Long.MAX_VALUE) { "The workspace image generation cannot advance." }
        return base + 1L
    }

    private fun readSelection(warnings: MutableList<String>): WorkspaceBackgroundSelection? {
        val file = selectionFile()
        if (!file.isFile) return null
        if (file.length() !in 1..SelectionFileCapBytes) {
            warnings += "The saved workspace image choice is invalid, so the default is active."
            return WorkspaceBackgroundSelection.default(nextGeneration(null))
        }
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("schemaVersion") != SelectionSchemaVersion) error("schema")
            val generation = json.getLong("generation")
            if (generation < 0L) error("generation")
            val kind = WorkspaceBackgroundSelectionKind.valueOf(json.getString("selectionKind"))
            val presetId = json.optString("presetId").takeIf(String::isNotBlank)
            val contentId = json.optString("contentId").takeIf { it.matches(ContentIdRegex) }
            when (kind) {
                WorkspaceBackgroundSelectionKind.PRESET -> {
                    if (WorkspaceBackgroundPresets.find(presetId) == null) error("preset")
                    WorkspaceBackgroundSelection(kind, presetId, generation)
                }
                WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE,
                WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME -> {
                    if (contentId == null) error("contentId")
                    WorkspaceBackgroundSelection(kind, generation = generation, expectedCustomContentId = contentId)
                }
            }
        } catch (_: Exception) {
            warnings += "The saved workspace image choice is invalid, so the default is active."
            WorkspaceBackgroundSelection.default(nextGeneration(null))
        }
    }

    private fun writeSelection(
        selection: WorkspaceBackgroundSelection,
        arbiter: WorkspaceBackgroundCommitArbiter?,
    ): String? {
        ensureDirectory()
        val json = JSONObject().apply {
            put("schemaVersion", SelectionSchemaVersion)
            put("selectionKind", selection.kind.name)
            put("generation", selection.generation)
            selection.presetId?.let { put("presetId", it) }
            selection.expectedCustomContentId?.let { put("contentId", it) }
        }.toString()
        val encoded = json.toByteArray(Charsets.UTF_8)
        check(encoded.size in 1..SelectionFileCapBytes.toInt()) { "Workspace selection metadata is invalid." }
        val temp = File(directory, ".workspace-selection-v2.json.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            arbiter?.let {
                check(it.beginCommit()) { "This image change was cancelled before it could be saved." }
            }
            try {
                Files.move(
                    temp.toPath(),
                    selectionFile().toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                error("This tablet cannot atomically save the workspace image selection.")
            }
            arbiter?.committed()
            return forceDirectoryAfterCommit()
        } finally {
            temp.delete()
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private background storage." }
    }

    private fun slotFile(slot: WorkspaceBackgroundSlot) = File(directory, slot.privateFileName)
    private fun selectionFile() = File(directory, SelectionFileName)
    private fun generationKey(slot: WorkspaceBackgroundSlot) = "${slot.name.lowercase()}_generation"
    private fun contentKey(slot: WorkspaceBackgroundSlot) = "${slot.name.lowercase()}_content_id"

    private fun cleanupRecognizedTemps() {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith(".home.jpg.tmp-") ||
                file.name.startsWith(".workspace.jpg.tmp-") ||
                file.name.startsWith(".home.jpg.input-") ||
                file.name.startsWith(".workspace.jpg.input-") ||
                file.name.startsWith(".workspace-selection-v2.json.tmp-")) {
                file.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Rename is the commit point; fsync failure is reported without pretending the old image survived. */
    private fun forceDirectoryAfterCommit(): String? = runCatching {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }.exceptionOrNull()?.let {
        "The new image is active, but Android could not confirm directory durability."
    }

    private companion object {
        const val PREFERENCES = "dcompany_workspace_backgrounds"
        const val SelectionSchemaVersion = 2
        const val SelectionFileName = "workspace-selection-v2.json"
        const val SelectionFileCapBytes = 4L * 1_024L
        val ContentIdRegex = Regex("^[0-9a-f]{64}$")
        val StorageMutex = Mutex()
    }
}

internal fun reconcileSelectionWithLoadedCustom(
    selection: WorkspaceBackgroundSelection,
    workspaceCustom: WorkspaceBackgroundImage?,
    legacyHomeCustom: WorkspaceBackgroundImage?,
    nextGeneration: () -> Long,
    persist: (WorkspaceBackgroundSelection) -> String?,
    warnings: MutableList<String>,
): WorkspaceBackgroundSelection {
    return when (selection.kind) {
    WorkspaceBackgroundSelectionKind.PRESET -> selection
    WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE -> {
        val custom = workspaceCustom ?: return selection
        if (selection.expectedCustomContentId == custom.contentId) return selection
        val repaired = selection.copy(
            generation = nextGeneration(),
            expectedCustomContentId = custom.contentId,
        )
        runCatching { persist(repaired) }.fold(
            onSuccess = { warning ->
                warning?.let(warnings::add)
                repaired
            },
            onFailure = {
                warnings += "The replacement workspace image is active, but its restart metadata still needs repair."
                selection.copy(expectedCustomContentId = custom.contentId)
            },
        )
    }
    WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME -> {
        val custom = legacyHomeCustom ?: return selection
        if (selection.expectedCustomContentId == custom.contentId) return selection
        warnings += "The previous Home image no longer matches its saved identity, so the default workspace image is active."
        val fallback = WorkspaceBackgroundSelection.default(nextGeneration())
        runCatching { persist(fallback) }
            .onSuccess { warning -> warning?.let(warnings::add) }
            .onFailure {
                warnings += "The safe default is active, but its selection could not be saved for restart."
            }
        fallback
    }
    }
}

internal class WorkspaceBackgroundController @JvmOverloads constructor(
    application: Application,
    private val store: WorkspaceBackgroundRepository = WorkspaceBackgroundStore(application),
    private val importTimeoutMillis: Long = IMPORT_TIMEOUT_MILLIS,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(WorkspaceBackgroundUiState(initializing = true))
    val state: StateFlow<WorkspaceBackgroundUiState> = mutableState.asStateFlow()
    private var operationId = 0L
    private var operationJob: Job? = null
    private var timeoutJob: Job? = null
    private var operationArbiter: WorkspaceBackgroundCommitArbiter? = null

    init {
        viewModelScope.launch {
            val loaded = try {
                store.load().copy(initializing = false)
            } catch (_: Exception) {
                WorkspaceBackgroundUiState(
                    initializing = false,
                    error = "Saved workspace backgrounds could not be checked, so bundled images are active.",
                )
            }
            if (operationId == 0L) mutableState.value = loaded
        }
    }

    fun import(slot: WorkspaceBackgroundSlot, uri: Uri) {
        if (mutableState.value.busySlot != null || mutableState.value.initializing) return
        val canonicalSlot = WorkspaceBackgroundSlot.WORKSPACE
        val id = ++operationId
        val arbiter = WorkspaceBackgroundCommitArbiter()
        operationArbiter = arbiter
        mutableState.value = mutableState.value.copy(busySlot = canonicalSlot, message = null, error = null)
        timeoutJob = viewModelScope.launch {
            delay(importTimeoutMillis)
            if (id == operationId && arbiter.cancel()) {
                operationJob?.cancel(CancellationException("Image provider timed out."))
                mutableState.value = mutableState.value.copy(
                    cancelling = true,
                    message = null,
                    error = "The image provider did not respond within 30 seconds.",
                )
            }
        }
        operationJob = viewModelScope.launch {
            try {
                val result = store.import(canonicalSlot, uri, arbiter)
                if (id != operationId) return@launch
                timeoutJob?.cancel()
                mutableState.value = mutableState.value.replacing(canonicalSlot, result.image).copy(
                    selection = result.selection,
                    savedCustomAvailable = result.savedCustomAvailable,
                    legacyHomeCustomAvailable = result.legacyHomeCustomAvailable,
                    busySlot = null,
                    cancelling = false,
                    message = result.durabilityWarning ?: "Workspace background updated on this tablet.",
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                if (id == operationId) {
                    val timedOut = mutableState.value.error != null
                    mutableState.value = mutableState.value.copy(
                        busySlot = null,
                        cancelling = false,
                        message = if (timedOut) null else "Image change cancelled. The existing image is still active.",
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                if (id == operationId) {
                    mutableState.value = mutableState.value.copy(
                        busySlot = null,
                        cancelling = false,
                        message = null,
                        error = if (failure is WorkspaceBackgroundInputException) failure.message
                        else "Could not import the selected image. Choose a different JPEG or PNG and try again.",
                    )
                }
            } finally {
                if (id == operationId) {
                    timeoutJob?.cancel()
                    timeoutJob = null
                    operationJob = null
                    operationArbiter = null
                }
            }
        }
    }

    fun reset(slot: WorkspaceBackgroundSlot) {
        if (mutableState.value.busySlot != null || mutableState.value.initializing) return
        val id = ++operationId
        val arbiter = WorkspaceBackgroundCommitArbiter()
        operationArbiter = arbiter
        mutableState.value = mutableState.value.copy(
            busySlot = WorkspaceBackgroundSlot.WORKSPACE,
            message = null,
            error = null,
        )
        operationJob = viewModelScope.launch {
            try {
                val result = store.reset(WorkspaceBackgroundSlot.WORKSPACE, arbiter)
                if (id != operationId) return@launch
                mutableState.value = mutableState.value.replacing(WorkspaceBackgroundSlot.WORKSPACE, result.image).copy(
                    selection = result.selection,
                    savedCustomAvailable = result.savedCustomAvailable,
                    legacyHomeCustomAvailable = result.legacyHomeCustomAvailable,
                    busySlot = null,
                    cancelling = false,
                    message = result.durabilityWarning ?: "The default workspace background is active.",
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                if (id == operationId) {
                    mutableState.value = mutableState.value.copy(
                        busySlot = null,
                        cancelling = false,
                        message = "Image change cancelled. The existing image is still active.",
                    )
                }
                throw cancelled
            } catch (_: Exception) {
                if (id == operationId) {
                    mutableState.value = mutableState.value.copy(
                        busySlot = null,
                        cancelling = false,
                        message = null,
                        error = "Could not reset the image. The current image is still active.",
                    )
                }
            } finally {
                if (id == operationId) {
                    operationJob = null
                    operationArbiter = null
                }
            }
        }
    }

    fun selectPreset(presetId: String) = runSelectionOperation(
        successMessage = WorkspaceBackgroundPresets.find(presetId)?.let { "${it.label} is active." }
            ?: "Workspace background updated.",
    ) { arbiter -> store.selectPreset(presetId, arbiter) }

    fun useSavedCustom() = runSelectionOperation(
        successMessage = "Saved custom workspace image is active.",
    ) { arbiter -> store.useSavedCustom(arbiter) }

    private fun runSelectionOperation(
        successMessage: String,
        mutation: suspend (WorkspaceBackgroundCommitArbiter) -> WorkspaceBackgroundMutation,
    ) {
        if (mutableState.value.busySlot != null || mutableState.value.initializing) return
        val id = ++operationId
        val arbiter = WorkspaceBackgroundCommitArbiter()
        operationArbiter = arbiter
        mutableState.value = mutableState.value.copy(
            busySlot = WorkspaceBackgroundSlot.WORKSPACE,
            message = null,
            error = null,
        )
        operationJob = viewModelScope.launch {
            try {
                val result = mutation(arbiter)
                if (id != operationId) return@launch
                mutableState.value = mutableState.value.copy(
                    workspace = result.image,
                    selection = result.selection,
                    savedCustomAvailable = result.savedCustomAvailable,
                    legacyHomeCustomAvailable = result.legacyHomeCustomAvailable,
                    busySlot = null,
                    cancelling = false,
                    message = result.durabilityWarning ?: successMessage,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                if (id == operationId) mutableState.value = mutableState.value.copy(
                    busySlot = null,
                    cancelling = false,
                    message = "Image change cancelled. The existing image is still active.",
                )
                throw cancelled
            } catch (failure: Exception) {
                if (id == operationId) mutableState.value = mutableState.value.copy(
                    busySlot = null,
                    cancelling = false,
                    message = null,
                    error = if (failure is WorkspaceBackgroundInputException) failure.message
                    else "Could not change the workspace image. The current image is still active.",
                )
            } finally {
                if (id == operationId) {
                    operationJob = null
                    operationArbiter = null
                }
            }
        }
    }

    fun clearNotice() {
        mutableState.value = mutableState.value.copy(message = null, error = null)
    }

    fun cancel() {
        if (mutableState.value.busySlot == null) return
        val arbiter = operationArbiter ?: return
        if (!arbiter.cancel()) return
        timeoutJob?.cancel()
        operationJob?.cancel()
        mutableState.value = mutableState.value.copy(
            cancelling = true,
            message = "Cancelling image change…",
            error = null,
        )
    }

    private companion object {
        const val IMPORT_TIMEOUT_MILLIS = 30_000L
    }
}

internal enum class WorkspaceImageFormat { JPEG, PNG, UNKNOWN }

private val PngSignature = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

internal data class WorkspaceImageContainerInspection(
    val complete: Boolean,
    val animatedPng: Boolean = false,
)

internal fun detectFormat(file: File): WorkspaceImageFormat {
    val bytes = ByteArray(8)
    val size = FileInputStream(file).use { it.read(bytes) }
    return when {
    size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> WorkspaceImageFormat.JPEG
    size >= 8 && bytes.contentEquals(PngSignature) -> WorkspaceImageFormat.PNG
    else -> WorkspaceImageFormat.UNKNOWN
    }
}

internal fun isAnimatedPng(file: File): Boolean =
    inspectWorkspaceImageContainer(file, WorkspaceImageFormat.PNG).animatedPng

internal fun inspectWorkspaceImageContainer(
    file: File,
    format: WorkspaceImageFormat,
): WorkspaceImageContainerInspection = try {
    when (format) {
        WorkspaceImageFormat.JPEG -> RandomAccessFile(file, "r").use { input ->
            if (input.length() < 4L) return@use WorkspaceImageContainerInspection(false)
            val startsWithJpeg = input.readUnsignedByte() == 0xFF &&
                input.readUnsignedByte() == 0xD8 && input.readUnsignedByte() == 0xFF
            input.seek(input.length() - 2L)
            val endsWithEoi = input.readUnsignedByte() == 0xFF && input.readUnsignedByte() == 0xD9
            WorkspaceImageContainerInspection(startsWithJpeg && endsWithEoi)
        }
        WorkspaceImageFormat.PNG -> RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(8)
            if (input.length() < 20L) return@use WorkspaceImageContainerInspection(false)
            input.readFully(signature)
            if (!signature.contentEquals(PngSignature)) return@use WorkspaceImageContainerInspection(false)
            var sawHeader = false
            var sawPixels = false
            var sawEnd = false
            var animated = false
            var chunks = 0
            val buffer = ByteArray(64 * 1_024)
            while (input.filePointer < input.length()) {
                if (input.length() - input.filePointer < 12L) {
                    return@use WorkspaceImageContainerInspection(false)
                }
                val length = input.readInt().toLong() and 0xFFFF_FFFFL
                if (length > WorkspaceBackgroundInputCapBytes ||
                    length + 8L > input.length() - input.filePointer
                ) return@use WorkspaceImageContainerInspection(false)
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = String(typeBytes, Charsets.US_ASCII)
                if (chunks == 0 && (type != "IHDR" || length != 13L)) {
                    return@use WorkspaceImageContainerInspection(false)
                }
                val crc = CRC32().apply { update(typeBytes) }
                var remaining = length
                while (remaining > 0L) {
                    val read = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    remaining -= read
                }
                val expectedCrc = input.readInt().toLong() and 0xFFFF_FFFFL
                if (crc.value != expectedCrc) return@use WorkspaceImageContainerInspection(false)
                when (type) {
                    "IHDR" -> sawHeader = true
                    "IDAT" -> sawPixels = true
                    "acTL" -> animated = true
                    "IEND" -> {
                        if (length != 0L || input.filePointer != input.length()) {
                            return@use WorkspaceImageContainerInspection(false)
                        }
                        sawEnd = true
                    }
                }
                chunks += 1
                if (sawEnd) break
            }
            WorkspaceImageContainerInspection(sawHeader && sawPixels && sawEnd, animated)
        }
        WorkspaceImageFormat.UNKNOWN -> WorkspaceImageContainerInspection(false)
    }
} catch (_: IOException) {
    WorkspaceImageContainerInspection(false)
} catch (_: RuntimeException) {
    WorkspaceImageContainerInspection(false)
}

private fun canDecodeWorkspaceCanonical(file: File): Boolean = try {
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = Bitmap.Config.RGB_565
        },
    )?.also(Bitmap::recycle) != null
} catch (_: OutOfMemoryError) {
    false
} catch (_: RuntimeException) {
    false
}

internal fun readOrientation(file: File): Int = runCatching {
    ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
}.getOrDefault(1)

private fun normalizeOrientation(source: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
        return source
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

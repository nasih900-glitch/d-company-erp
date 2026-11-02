package cloud.dcompany.erp.ui.theme

import android.app.ActivityOptions
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.RandomAccessFile
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.screens.settings.WorkspaceBackgroundSettingsCard
import cloud.dcompany.erp.ui.screens.settings.rememberWorkspaceBackgroundPendingSlot
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceBackgroundUiTest {
    @get:Rule val compose = createBackgroundComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val root = File(base.cacheDir, "workspace-background-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            base.getSharedPreferences("test-${root.name}-$name", mode)
    }

    @After fun cleanup() {
        root.deleteRecursively()
        base.getSharedPreferences("test-${root.name}-dcompany_workspace_backgrounds", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun productionImporterPersistsOneWorkspaceSelectionAndPresetChangesPreserveCustomBytes() = runBlocking {
        assertRequestedDisplay()
        val png = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.ALPHA_PNG)
        val store = WorkspaceBackgroundStore(context)
        val importedMutation = store.import(WorkspaceBackgroundSlot.HOME, png)
        val imported = importedMutation.image
        assertEquals(WorkspaceBackgroundSlot.WORKSPACE, imported.slot)
        assertEquals(WorkspaceBackgroundSource.IMPORTED, imported.source)
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE, importedMutation.selection.kind)
        assertEquals(2560, imported.width)
        assertEquals(1080, imported.height)
        val canonical = BitmapFactory.decodeFile(imported.privatePath!!)
        assertFalse(canonical.hasAlpha())
        canonical.recycle()
        compose.setContent {
            WorkspaceBackgroundRenderer(imported, Modifier.fillMaxSize())
        }
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
        assertImportedBitmapMatchesCanonical(imported, "workspace-background-bitmap-workspace")
        val reloaded = store.load()
        assertEquals(imported.contentId, reloaded.workspace.contentId)
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE, reloaded.selection.kind)
        val customBytes = File(requireNotNull(imported.privatePath)).readBytes()

        val preset = store.selectPreset("ghost_of_tsushima")
        assertEquals("ghost_of_tsushima", preset.selection.presetId)
        assertEquals(WorkspaceBackgroundSource.BUNDLED, preset.image.source)
        assertTrue(customBytes.contentEquals(File(requireNotNull(imported.privatePath)).readBytes()))
        assertTrue(store.load().savedCustomAvailable)

        val restored = store.useSavedCustom()
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE, restored.selection.kind)
        assertEquals(imported.contentId, restored.image.contentId)
        val reset = store.reset(WorkspaceBackgroundSlot.HOME)
        assertEquals(WorkspaceBackgroundPresets.DefaultId, reset.selection.presetId)
        assertTrue(customBytes.contentEquals(File(requireNotNull(imported.privatePath)).readBytes()))
    }

    @Test
    fun selectionSchemaRejectsMissingCustomIdentityAndRepairsStaleIdentityAtomically() = runBlocking {
        assertRequestedDisplay()
        val store = WorkspaceBackgroundStore(context)
        val imported = store.import(
            WorkspaceBackgroundSlot.WORKSPACE,
            WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.VALID_JPEG),
        ).image
        val selectionFile = root.resolve("workspace-backgrounds/workspace-selection-v2.json")
        selectionFile.writeText(
            """{"schemaVersion":2,"selectionKind":"CUSTOM_WORKSPACE","generation":21,"contentId":"${"0".repeat(64)}"}""",
        )
        val repaired = WorkspaceBackgroundStore(context).load()
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE, repaired.selection.kind)
        assertEquals(imported.contentId, repaired.selection.expectedCustomContentId)
        assertEquals(22L, repaired.selection.generation)
        assertTrue(selectionFile.readText().contains(imported.contentId))

        selectionFile.writeText(
            """{"schemaVersion":2,"selectionKind":"CUSTOM_WORKSPACE","generation":23}""",
        )
        val rejected = WorkspaceBackgroundStore(context).load()
        assertEquals(WorkspaceBackgroundSelectionKind.PRESET, rejected.selection.kind)
        assertEquals(WorkspaceBackgroundPresets.DefaultId, rejected.workspace.presetId)
        assertTrue(rejected.savedCustomAvailable)
        assertTrue(requireNotNull(rejected.error).contains("invalid"))
        assertTrue(File(requireNotNull(imported.privatePath)).isFile)
    }

    @Test
    fun legacyHomeOnlyMigrationSurvivesPresetSelectionReuseAndRestart() = runBlocking {
        assertRequestedDisplay()
        val seedingStore = WorkspaceBackgroundStore(context)
        val seeded = seedingStore.import(
            WorkspaceBackgroundSlot.WORKSPACE,
            WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.VALID_JPEG),
        ).image
        val directory = root.resolve("workspace-backgrounds")
        val workspaceFile = File(requireNotNull(seeded.privatePath))
        val legacyFile = directory.resolve("home.jpg")
        workspaceFile.copyTo(legacyFile, overwrite = true)
        assertTrue(workspaceFile.delete())
        assertTrue(directory.resolve("workspace-selection-v2.json").delete())
        val legacyBytes = legacyFile.readBytes()

        val store = WorkspaceBackgroundStore(context)
        val migrated = store.load()
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME, migrated.selection.kind)
        assertEquals(seeded.contentId, migrated.workspace.contentId)
        assertTrue(migrated.savedCustomAvailable)
        store.selectPreset("call_of_duty")
        assertTrue(legacyBytes.contentEquals(legacyFile.readBytes()))
        val reused = store.useSavedCustom()
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME, reused.selection.kind)
        assertEquals(seeded.contentId, reused.image.contentId)

        val restarted = WorkspaceBackgroundStore(context).load()
        assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME, restarted.selection.kind)
        assertEquals(seeded.contentId, restarted.workspace.contentId)
        assertTrue(legacyBytes.contentEquals(legacyFile.readBytes()))
    }

    @Test
    fun productionImporterRejectsCorruptAndPortraitWithoutReplacingCommittedBytes() = runBlocking {
        assertRequestedDisplay()
        val store = WorkspaceBackgroundStore(context)
        val valid = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.VALID_JPEG)
        val committed = store.import(WorkspaceBackgroundSlot.WORKSPACE, valid).image
        val before = File(committed.privatePath!!).readBytes()
        val portrait = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.PORTRAIT_JPEG)
        runCatching { store.import(WorkspaceBackgroundSlot.WORKSPACE, portrait) }
            .onSuccess { error("portrait import unexpectedly succeeded") }
        val corrupt = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.BROKEN_PNG)
        runCatching { store.import(WorkspaceBackgroundSlot.WORKSPACE, corrupt) }
            .onSuccess { error("corrupt import unexpectedly succeeded") }
        val truncatedJpeg = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.TRUNCATED_JPEG)
        runCatching { store.import(WorkspaceBackgroundSlot.WORKSPACE, truncatedJpeg) }
            .onSuccess { error("truncated JPEG import unexpectedly succeeded") }
        val truncatedPng = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.TRUNCATED_PNG)
        runCatching { store.import(WorkspaceBackgroundSlot.WORKSPACE, truncatedPng) }
            .onSuccess { error("truncated PNG import unexpectedly succeeded") }
        val animatedPng = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.ANIMATED_PNG)
        runCatching { store.import(WorkspaceBackgroundSlot.WORKSPACE, animatedPng) }
            .onSuccess { error("animated PNG import unexpectedly succeeded") }
        val tooSmall = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.TOO_SMALL_JPEG)
        runCatching { store.import(WorkspaceBackgroundSlot.WORKSPACE, tooSmall) }
            .onSuccess { error("below-minimum import unexpectedly succeeded") }
        assertTrue(before.contentEquals(File(committed.privatePath!!).readBytes()))
    }

    @Test
    fun productionImporterPreservesUsefulResolutionAcross4096Boundary() = runBlocking {
        assertRequestedDisplay()
        val store = WorkspaceBackgroundStore(context)
        val boundary = store.import(
            WorkspaceBackgroundSlot.HOME,
            WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.EDGE_4096_JPEG),
        ).image
        assertEquals(4096, boundary.width)
        assertEquals(1080, boundary.height)
        val justOver = store.import(
            WorkspaceBackgroundSlot.HOME,
            WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.EDGE_4097_JPEG),
        ).image
        assertEquals(4096, justOver.width)
        assertEquals(1079, justOver.height)
        assertTrue(justOver.width.toLong() * justOver.height <= 10_000_000L)
    }

    @Test
    fun exifImportAndCorruptRestartPublishTruthfulUnifiedFallbackWithoutDeletingEvidence() = runBlocking {
        assertRequestedDisplay()
        val store = WorkspaceBackgroundStore(context)
        val rotated = store.import(
            WorkspaceBackgroundSlot.HOME,
            WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.EXIF_ROTATED_JPEG),
        ).image
        assertEquals(2560, rotated.width)
        assertEquals(1080, rotated.height)
        RandomAccessFile(requireNotNull(rotated.privatePath), "rw").use { it.setLength(it.length() - 2L) }

        val restarted = WorkspaceBackgroundStore(context).load()
        assertEquals(WorkspaceBackgroundSource.BUNDLED, restarted.workspace.source)
        assertEquals(WorkspaceBackgroundPresets.DefaultId, restarted.workspace.presetId)
        assertTrue(requireNotNull(restarted.error).contains("unreadable"))
        assertTrue(File(requireNotNull(rotated.privatePath)).exists())
    }

    @Test
    fun controllerDeadlineBlocksRetryUntilWorkerTerminatesAndPromptFailureStaysTerminal() {
        assertRequestedDisplay()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val blocked = BlockingWorkspaceBackgroundRepository(holdMillis = 700L)
        val controller = WorkspaceBackgroundController(application, blocked, importTimeoutMillis = 100L)
        compose.waitUntil(2_000L) { !controller.state.value.initializing }
        controller.import(WorkspaceBackgroundSlot.HOME, Uri.parse("content://fixture/blocked"))
        compose.waitUntil(2_000L) { controller.state.value.cancelling }
        assertEquals(WorkspaceBackgroundSlot.WORKSPACE, controller.state.value.busySlot)
        controller.import(WorkspaceBackgroundSlot.WORKSPACE, Uri.parse("content://fixture/retry"))
        assertEquals(1, blocked.importCalls.get())
        compose.waitUntil(3_000L) { controller.state.value.busySlot == null }
        assertTrue(requireNotNull(controller.state.value.error).contains("30 seconds"))
        assertEquals(1, blocked.importCalls.get())

        val prompt = PromptFailureWorkspaceBackgroundRepository()
        val promptController = WorkspaceBackgroundController(application, prompt, importTimeoutMillis = 100L)
        compose.waitUntil(2_000L) { !promptController.state.value.initializing }
        promptController.import(WorkspaceBackgroundSlot.HOME, Uri.parse("content://fixture/portrait"))
        compose.waitUntil(2_000L) {
            promptController.state.value.busySlot == null && promptController.state.value.error != null
        }
        val original = promptController.state.value.error
        Thread.sleep(250L)
        assertEquals("Choose a landscape image.", original)
        assertEquals(original, promptController.state.value.error)
        assertEquals(1, prompt.importCalls.get())
    }

    @Test
    fun productionStoreSerializesRetryUntilCancellationIgnoringProviderTerminates() = runBlocking {
        assertRequestedDisplay()
        val store = WorkspaceBackgroundStore(context)
        val retry = WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.RETRY_VALID_JPEG)
        val committed = retryAfterHeldProvider(store, WorkspaceBackgroundSlot.HOME) {
            store.import(WorkspaceBackgroundSlot.WORKSPACE, retry)
        }.image
        assertEquals(WorkspaceBackgroundSlot.WORKSPACE, committed.slot)
        assertEquals(WorkspaceBackgroundSource.IMPORTED, committed.source)

        val selected = retryAfterHeldProvider(store, WorkspaceBackgroundSlot.WORKSPACE) {
            WorkspaceBackgroundStore(context).selectPreset("call_of_duty")
        }
        assertEquals("call_of_duty", selected.selection.presetId)
        assertEquals(WorkspaceBackgroundSource.BUNDLED, selected.image.source)
    }

    private suspend fun retryAfterHeldProvider(
        store: WorkspaceBackgroundStore,
        slot: WorkspaceBackgroundSlot,
        retry: suspend () -> WorkspaceBackgroundMutation,
    ): WorkspaceBackgroundMutation = coroutineScope {
        val controlled = WorkspaceBackgroundFixtures.create(
            WorkspaceBackgroundFixtureRecipe.CONTROLLED_VALID_JPEG,
        )
        val arbiter = WorkspaceBackgroundCommitArbiter()
        val first = async { store.import(slot, controlled, arbiter) }
        var second: Deferred<WorkspaceBackgroundMutation>? = null
        var operationFailure: Throwable? = null
        try {
            WorkspaceBackgroundFixtures.awaitProviderEntry(controlled)
            assertProviderHeld(controlled)
            assertThrows(FileNotFoundException::class.java) {
                base.contentResolver.openAssetFileDescriptor(controlled, "r")?.use { }
            }
            assertThrows(IllegalStateException::class.java) {
                WorkspaceBackgroundFixtures.disposeControlled(controlled)
            }
            assertProviderHeld(controlled)
            assertTrue(arbiter.cancel())
            first.cancel()
            val retryStarted = CompletableDeferred<Unit>()
            val attempt = async(Dispatchers.IO) {
                retryStarted.complete(Unit)
                retry()
            }
            second = attempt
            withTimeout(5_000L) { retryStarted.await() }
            // Give the admitted retry CPU time; the provider remains held until explicit release.
            delay(150L)
            assertProviderHeld(controlled)
            val observed = if (attempt.isCompleted) {
                runCatching { "completed with ${attempt.await().image.source}" }
                    .getOrElse { "failed with ${it.javaClass.simpleName}" }
            } else {
                "pending"
            }
            assertFalse("retry must wait for the sole provider/store worker: $observed", attempt.isCompleted)
            WorkspaceBackgroundFixtures.releaseProvider(controlled)
            withTimeout(5_000L) {
                first.join()
                val result = attempt.await()
                assertTrue(WorkspaceBackgroundFixtures.controlledState(controlled).exited)
                result
            }
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            // Release inside this scope, before structured concurrency waits for the blocked child.
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { WorkspaceBackgroundFixtures.releaseProvider(controlled) }
            cleanup { first.cancel() }
            cleanup { second?.cancel() }
            runCatching {
                withContext(NonCancellable) {
                    withTimeout(5_000L) {
                        first.join()
                        second?.join()
                    }
                }
            }.exceptionOrNull()?.let(cleanupFailures::add)
            cleanup {
                assertFalse(WorkspaceBackgroundFixtures.controlledState(controlled).failed)
            }
            cleanup { WorkspaceBackgroundFixtures.disposeControlled(controlled) }
            cleanup {
                assertThrows(IllegalArgumentException::class.java) {
                    WorkspaceBackgroundFixtures.controlledState(controlled)
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                val primary = operationFailure ?: cleanupFailures.removeAt(0)
                cleanupFailures.filter { it !== primary }.forEach(primary::addSuppressed)
                if (operationFailure == null) throw primary
            }
        }
    }

    private fun assertProviderHeld(uri: Uri) {
        val state = WorkspaceBackgroundFixtures.controlledState(uri)
        assertTrue("the first provider must have entered", state.entered)
        assertFalse("the test must still own the provider release", state.released)
        assertFalse("the first provider must still occupy the worker", state.exited)
        assertFalse("the provider fail-safe must not supply an implicit release", state.failed)
    }

    @Test
    fun fixtureProviderAdmitsOnlyExactSignedTargetAndTokensAreSingleUse() {
        assertRequestedDisplay()
        assertTrue(WorkspaceBackgroundFixtureProvider.callerAllowed(10, 10, null, false))
        assertTrue(WorkspaceBackgroundFixtureProvider.callerAllowed(10, 20, arrayOf("cloud.dcompany.erp"), true))
        assertFalse(WorkspaceBackgroundFixtureProvider.callerAllowed(10, 20, arrayOf("cloud.dcompany.erp"), false))
        assertFalse(WorkspaceBackgroundFixtureProvider.callerAllowed(10, 20, arrayOf("cloud.dcompany.erp", "other"), true))
        assertFalse(WorkspaceBackgroundFixtureProvider.callerAllowed(10, 20, arrayOf("other"), true))

        val uri = WorkspaceBackgroundFixtures.create(
            WorkspaceBackgroundFixtureRecipe.VALID_JPEG,
            base.contentResolver,
        )
        assertTrue(uri.pathSegments.singleOrNull { it.matches(Regex("[0-9a-f]{32}")) } != null)
        base.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { descriptor ->
            assertTrue(descriptor.length > 0L)
        }
        assertThrows(FileNotFoundException::class.java) {
            base.contentResolver.openAssetFileDescriptor(uri, "r")
        }
        val controlled = WorkspaceBackgroundFixtures.create(
            WorkspaceBackgroundFixtureRecipe.CONTROLLED_VALID_JPEG,
        )
        try {
            for (method in listOf(
                "controlled-fixture-state",
                "release-controlled-fixture",
                "dispose-controlled-fixture",
            )) {
                assertThrows(IllegalArgumentException::class.java) {
                    base.contentResolver.call(WorkspaceBackgroundFixtures.Authority, method, "invalid", null)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    base.contentResolver.call(
                        WorkspaceBackgroundFixtures.Authority, method, uri.lastPathSegment, null,
                    )
                }
                assertThrows(IllegalArgumentException::class.java) {
                    base.contentResolver.call(
                        WorkspaceBackgroundFixtures.Authority, method, controlled.lastPathSegment,
                        Bundle().apply { putBoolean("unexpected", true) },
                    )
                }
            }
            val initial = WorkspaceBackgroundFixtures.controlledState(controlled)
            assertFalse(initial.entered || initial.released || initial.exited || initial.failed)
            WorkspaceBackgroundFixtures.releaseProvider(controlled)
            assertTrue(WorkspaceBackgroundFixtures.controlledState(controlled).released)
        } finally {
            WorkspaceBackgroundFixtures.disposeControlled(controlled)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBackgroundFixtures.controlledState(controlled)
        }
        assertProviderRejected("CRUD") {
            base.contentResolver.insert(
                Uri.parse("content://${WorkspaceBackgroundFixtures.Authority}/fixture/00000000000000000000000000000000"),
                ContentValues(),
            )
        }
        assertProviderRejected("query") {
            base.contentResolver.query(uri, null, null, null, null)
        }
        listOf(
            "content://${WorkspaceBackgroundFixtures.Authority}/fixture/not-a-token",
            "content://${WorkspaceBackgroundFixtures.Authority}/fixture/00000000000000000000000000000000/extra",
            "content://${WorkspaceBackgroundFixtures.Authority}/fixture/00000000000000000000000000000000",
        ).forEach { value ->
            assertThrows(FileNotFoundException::class.java) {
                base.contentResolver.openAssetFileDescriptor(Uri.parse(value), "r")
            }
        }
        val writeToken = WorkspaceBackgroundFixtures.create(
            WorkspaceBackgroundFixtureRecipe.BROKEN_PNG,
            base.contentResolver,
        )
        assertThrows(FileNotFoundException::class.java) {
            base.contentResolver.openAssetFileDescriptor(writeToken, "w")
        }
    }

    @Test
    fun rendererRetriesRequestedContentWithoutCachingFallbackAsImportedReady() {
        assertRequestedDisplay()
        val requestedColor = Color.rgb(34, 174, 92)
        val fallbackColor = Color.rgb(181, 52, 48)
        val requestedCalls = AtomicInteger()
        val decoder = object : WorkspaceBackgroundDecoder {
            override fun decodeRequested(
                image: WorkspaceBackgroundImage,
                targetWidth: Int,
                targetHeight: Int,
                context: Context,
            ): Bitmap? = if (requestedCalls.incrementAndGet() == 1) null else
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(requestedColor)
                }

            override fun decodeBundled(
                slot: WorkspaceBackgroundSlot,
                targetWidth: Int,
                targetHeight: Int,
                context: Context,
            ): Bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
                eraseColor(fallbackColor)
            }
        }
        val imported = bundledWorkspaceBackground(WorkspaceBackgroundSlot.HOME).copy(
            source = WorkspaceBackgroundSource.IMPORTED,
            generation = 991L,
            contentId = UUID.randomUUID().toString(),
            privatePath = "/unavailable-test-image",
        )
        compose.setContent {
            WorkspaceBackgroundRenderer(imported, Modifier.fillMaxSize(), decoder)
        }
        compose.waitUntil(4_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-fallback-home")).fetchSemanticsNodes().size == 1
        }
        assertEquals(
            0,
            compose.onAllNodes(hasTestTag("workspace-background-ready-home")).fetchSemanticsNodes().size,
        )
        compose.waitUntil(4_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-home")).fetchSemanticsNodes().size == 1
        }
        assertEquals(2, requestedCalls.get())
        assertEquals(
            0,
            compose.onAllNodes(hasTestTag("workspace-background-fallback-home")).fetchSemanticsNodes().size,
        )
        val bitmap = compose.onNodeWithTag("workspace-background-bitmap-home").captureToImage().asAndroidBitmap()
        try {
            val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val expected = rendererCompositedPixel(requestedColor, bitmap.height, bitmap.height / 2)
            recordRendererColorProof(
                name = "background-requested-retry-composite",
                bitmap = bitmap,
                source = requestedColor,
                expected = expected,
                actual = center,
                tolerance = 2,
            )
            assertColorChannelsWithin(expected, center, tolerance = 2)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun pendingPickerSlotSurvivesSavedStateRecreationAndNullCanClearIt() {
        assertRequestedDisplay()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val pending = rememberWorkspaceBackgroundPendingSlot()
            Column {
                Button(
                    onClick = { pending.value = WorkspaceBackgroundSlot.WORKSPACE.name },
                    modifier = Modifier.testTag("set-pending-workspace"),
                ) { Text("Set pending") }
                Button(
                    onClick = { pending.value = null },
                    modifier = Modifier.testTag("clear-pending-workspace"),
                ) { Text("Clear pending") }
                Text(pending.value ?: "none", modifier = Modifier.testTag("pending-workspace-value"))
            }
        }
        compose.onNodeWithTag("set-pending-workspace").performClick()
        compose.onNodeWithTag("pending-workspace-value").assertTextEquals(WorkspaceBackgroundSlot.WORKSPACE.name)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pending-workspace-value").assertTextEquals(WorkspaceBackgroundSlot.WORKSPACE.name)
        compose.onNodeWithTag("clear-pending-workspace").performClick()
        compose.onNodeWithTag("pending-workspace-value").assertTextEquals("none")
    }

    @Test
    fun settingsCardExposesSixVerifiedPresetsAndPreservesSavedCustomChoice() {
        assertRequestedDisplay()
        var reset: WorkspaceBackgroundSlot? = null
        var selectedPreset: String? = null
        var usedSavedCustom = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                WorkspaceBackgroundSettingsCard(
                    state = WorkspaceBackgroundUiState(
                        workspace = bundledWorkspaceBackground(
                            WorkspaceBackgroundSlot.WORKSPACE,
                            presetId = "ghost_of_tsushima",
                        ),
                        selection = WorkspaceBackgroundSelection(
                            WorkspaceBackgroundSelectionKind.PRESET,
                            presetId = "ghost_of_tsushima",
                            generation = 4L,
                        ),
                        savedCustomAvailable = true,
                    ),
                    onImport = { _, _ -> },
                    onReset = { reset = it },
                    onSelectPreset = { selectedPreset = it },
                    onUseSavedCustom = { usedSavedCustom += 1 },
                    onCancelOperation = {},
                    onClearNotice = {},
                )
            }
        }
        compose.onNodeWithTag("workspace-background-settings").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-preset-gallery").performScrollToIndex(5)
        compose.onNodeWithTag("workspace-background-select-ghost_of_tsushima", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onAllNodes(
            hasTestTag("workspace-background-select-ghost_of_tsushima") and
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onNodeWithTag("workspace-background-preset-gallery").performScrollToIndex(0)
        compose.onNodeWithTag("workspace-background-select-gta_vi", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithTag("workspace-background-change-workspace")
            .performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("workspace-background-use-saved-custom")
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, usedSavedCustom)
        compose.onNodeWithTag("workspace-background-reset-workspace")
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(WorkspaceBackgroundSlot.WORKSPACE, reset)
        compose.onNodeWithTag("workspace-background-select-gta_vi", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("gta_vi", selectedPreset)
        captureRoot("background-settings-card")
    }

    @Test
    fun settingsGalleryPhysicalSwipeReachesGhostAndReturnsToGta() {
        assertRequestedDisplay()
        var selectedPreset: String? = null
        val activePreset = mutableStateOf("gta_vi")
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                WorkspaceBackgroundSettingsCard(
                    state = WorkspaceBackgroundUiState(
                        workspace = bundledWorkspaceBackground(
                            WorkspaceBackgroundSlot.WORKSPACE,
                            presetId = activePreset.value,
                        ),
                        selection = WorkspaceBackgroundSelection(
                            WorkspaceBackgroundSelectionKind.PRESET,
                            presetId = activePreset.value,
                            generation = 5L,
                        ),
                    ),
                    onImport = { _, _ -> },
                    onReset = {},
                    onSelectPreset = {
                        selectedPreset = it
                        activePreset.value = it
                    },
                    onUseSavedCustom = {},
                    onCancelOperation = {},
                    onClearNotice = {},
                )
            }
        }

        val gallery = compose.onNodeWithTag("workspace-background-preset-gallery")
            .assertIsDisplayed()
        fun horizontalOffset(): Float = gallery.fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
            .value()
        fun isFullyVisible(tag: String): Boolean {
            val nodes = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (nodes.size != 1) return false
            val viewport = gallery.getUnclippedBoundsInRoot()
            val target = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            return target.left >= viewport.left - 0.5.dp &&
                target.right <= viewport.right + 0.5.dp &&
                target.top >= viewport.top - 0.5.dp &&
                target.bottom <= viewport.bottom + 0.5.dp
        }

        val gtaTag = "workspace-background-select-gta_vi"
        val ghostTag = "workspace-background-select-ghost_of_tsushima"
        assertTrue(isFullyVisible(gtaTag))
        val initialOffset = horizontalOffset()

        repeat(3) {
            if (!isFullyVisible(ghostTag)) {
                gallery.performTouchInput { swipeLeft(durationMillis = 400L) }
                compose.waitForIdle()
            }
        }
        val farOffset = horizontalOffset()
        assertTrue("Physical left swipe must advance the gallery", farOffset > initialOffset + 1f)
        assertFalse("The first preset must leave the fully visible viewport", isFullyVisible(gtaTag))
        assertTrue("The far-end Ghost preset must be fully visible before touch", isFullyVisible(ghostTag))
        compose.onNodeWithTag(ghostTag, useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        assertEquals("ghost_of_tsushima", selectedPreset)
        compose.waitForIdle()

        repeat(3) {
            if (!isFullyVisible(gtaTag)) {
                gallery.performTouchInput { swipeRight(durationMillis = 400L) }
                compose.waitForIdle()
            }
        }
        val returnedOffset = horizontalOffset()
        assertTrue("Physical right swipe must return toward the first preset", returnedOffset < farOffset - 1f)
        assertTrue(isFullyVisible(gtaTag))
        compose.onNodeWithTag(gtaTag, useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        assertEquals("gta_vi", selectedPreset)
    }

    @Test
    fun settingsCardUsesOpenDocumentAndHandlesNullBusyCancelAndResetCallbacks() {
        assertRequestedDisplay()
        val selected = Uri.parse("content://selected/workspace")
        val registry = RecordingActivityResultRegistry(listOf(null, selected))
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var imported: Pair<WorkspaceBackgroundSlot, Uri>? = null
        var reset: WorkspaceBackgroundSlot? = null
        var cancelled = 0
        val state = mutableStateOf(
            WorkspaceBackgroundUiState(
                workspace = bundledWorkspaceBackground(
                    WorkspaceBackgroundSlot.WORKSPACE,
                    generation = 3L,
                    presetId = "ghost_of_tsushima",
                ),
                selection = WorkspaceBackgroundSelection(
                    WorkspaceBackgroundSelectionKind.PRESET,
                    presetId = "ghost_of_tsushima",
                    generation = 3L,
                ),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WorkspaceBackgroundSettingsCard(
                        state = state.value,
                        onImport = { slot, uri -> imported = slot to uri },
                        onReset = { reset = it },
                        onCancelOperation = { cancelled += 1 },
                        onClearNotice = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("workspace-background-change-workspace").performScrollTo().performClick()
        assertNull(imported)
        compose.onNodeWithTag("workspace-background-change-workspace").performScrollTo().performClick()
        assertEquals(WorkspaceBackgroundSlot.WORKSPACE to selected, imported)
        assertEquals(2, registry.launchedMimeTypes.size)
        registry.launchedMimeTypes.forEach { types ->
            assertEquals(listOf("image/jpeg", "image/png"), types.toList())
        }

        compose.runOnIdle { state.value = state.value.copy(busySlot = WorkspaceBackgroundSlot.WORKSPACE) }
        compose.onNodeWithTag("workspace-background-cancel").performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, cancelled)
        compose.onNodeWithTag("workspace-background-reset-workspace").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-background-change-workspace").assertIsNotEnabled()

        compose.runOnIdle { state.value = state.value.copy(busySlot = null) }
        compose.onNodeWithTag("workspace-background-reset-workspace").performScrollTo().performClick()
        assertEquals(WorkspaceBackgroundSlot.WORKSPACE, reset)
    }

    @Test
    fun settingsCardIsReachableAtCompactFontScaleInDayAndNight() {
        assertRequestedDisplay()
        val appearance = mutableStateOf(AppearancePreference.Day)
        val importedWorkspace = bundledWorkspaceBackground(
            WorkspaceBackgroundSlot.WORKSPACE,
            generation = 12L,
            presetId = "ghost_of_tsushima",
        )
        val state = mutableStateOf(
            WorkspaceBackgroundUiState(
                workspace = importedWorkspace,
                selection = WorkspaceBackgroundSelection(
                    WorkspaceBackgroundSelectionKind.PRESET,
                    presetId = "ghost_of_tsushima",
                    generation = 12L,
                ),
                savedCustomAvailable = true,
                message = "Background updated on this tablet.",
            ),
        )
        val density = compose.activity.resources.displayMetrics.density
        val registry = RecordingActivityResultRegistry(listOf(null, null, null, null))
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalDensity provides Density(density, fontScale = 1.3f),
            ) {
                DCompanyTheme(appearance.value) {
                    Column(
                        Modifier.width(552.dp).height(800.dp)
                            .testTag("background-settings-compact-root")
                            .verticalScroll(rememberScrollState()),
                    ) {
                        WorkspaceBackgroundSettingsCard(
                            state = state.value,
                            onImport = { _, _ -> },
                            onReset = {},
                            onCancelOperation = {},
                            onClearNotice = {},
                        )
                    }
                }
            }
        }
        listOf(AppearancePreference.Day, AppearancePreference.Night).forEach { preference ->
            compose.runOnIdle { appearance.value = preference }
            compose.onNodeWithTag("workspace-background-settings").assertIsDisplayed()
            WorkspaceBackgroundPresets.entries.forEachIndexed { index, preset ->
                compose.onNodeWithTag("workspace-background-preset-gallery").performScrollToIndex(index)
                compose.onNodeWithTag("workspace-background-select-${preset.id}", useUnmergedTree = true)
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.Selected,
                            preset.id == "ghost_of_tsushima",
                        ),
                    )
            }
            compose.onNodeWithTag("workspace-background-change-workspace")
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertFullyInsideCompactRoot("workspace-background-change-workspace")
            compose.onNodeWithTag("workspace-background-active-source")
                .performScrollTo().assertIsDisplayed()
            assertFullyInsideCompactRoot("workspace-background-active-source")
            compose.onNodeWithTag("workspace-background-message").performScrollTo().assertIsDisplayed()
            assertFullyInsideCompactRoot("workspace-background-message")
            captureRoot("background-settings-${preference.name.lowercase()}-font1_3-before-assert")
            listOf(
                "Workspace background",
                "Game gallery",
                "Background updated on this tablet.",
            ).forEach { text ->
                assertTextDoesNotOverflow(
                    text = text,
                    evidencePrefix = "compact-${preference.name.lowercase()}",
                )
            }
            assertAllTextDoesNotOverflow("Use default")
            captureRoot("background-settings-${preference.name.lowercase()}-font1_3")

            compose.runOnIdle {
                state.value = state.value.copy(
                    busySlot = WorkspaceBackgroundSlot.WORKSPACE,
                    cancelling = false,
                    message = null,
                    error = null,
                )
            }
            compose.onNodeWithTag("workspace-background-cancel").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertFullyInsideCompactRoot("workspace-background-cancel")
            listOf(
                "workspace-background-change-workspace",
                "workspace-background-use-saved-custom",
                "workspace-background-reset-workspace",
                "workspace-background-cancel",
            ).forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo()
                    .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                assertFullyInsideCompactRoot(tag)
            }
            assertAllTextDoesNotOverflow("Changing…")
            compose.onNodeWithTag("workspace-background-change-workspace").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Changing in progress",
                ),
            )
            assertAllTextDoesNotOverflow("Cancel")
            assertAllTextDoesNotOverflow("Use saved custom")
            assertAllTextDoesNotOverflow("Use default")

            compose.runOnIdle {
                state.value = state.value.copy(
                    cancelling = true,
                    message = "Cancelling image change…",
                )
            }
            compose.onNodeWithTag("workspace-background-cancel").assertDoesNotExist()
            compose.onNodeWithTag("workspace-background-change-workspace").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertFullyInsideCompactRoot("workspace-background-change-workspace")
            assertAllTextDoesNotOverflow("Cancelling")
            assertTextDoesNotOverflow("Cancelling image change…")

            compose.runOnIdle {
                state.value = state.value.copy(
                    busySlot = null,
                    cancelling = false,
                    message = null,
                    error = "Could not import the selected image. Choose a different JPEG or PNG and try again.",
                )
            }
            compose.onNodeWithTag("workspace-background-error").performScrollTo().assertIsDisplayed()
            assertFullyInsideCompactRoot("workspace-background-error")
            assertTextDoesNotOverflow(
                "Could not import the selected image. Choose a different JPEG or PNG and try again.",
            )
            compose.runOnIdle {
                state.value = state.value.copy(
                    message = "Background updated on this tablet.",
                    error = null,
                )
            }
        }
        compose.runOnIdle {
            state.value = state.value.copy(
                selection = WorkspaceBackgroundSelection(
                    kind = WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME,
                    generation = 13L,
                    expectedCustomContentId = "a".repeat(64),
                ),
                savedCustomAvailable = true,
                busySlot = null,
                cancelling = false,
            )
        }
        compose.onNodeWithTag("workspace-background-use-saved-custom").assertDoesNotExist()
    }

    @Test
    fun productionShellKeepsOneContentIdentityAcrossDashboardAndOtherRoutes() {
        assertRequestedDisplay()
        val current = mutableStateOf(Destination.Dashboard)
        compose.setContent {
            WorkspaceScaffold(
                destinations = listOf(Destination.Dashboard, Destination.Gaming, Destination.Settings),
                currentDestination = current.value,
                employeeName = "Test",
                locationLabel = "Test shop",
                connectivityProblem = SyncAvailabilityProblem.NONE,
                outboxWorkStatus = OutboxWorkStatus(),
                syncing = false,
                canChangeTill = false,
                onOpenSupport = {},
                onChangeTill = {},
                onSignOut = {},
                onDestinationChanged = { current.value = it },
            ) { destination, _ -> Box(Modifier.fillMaxSize()) { Text(destination.label) } }
        }
        compose.onNodeWithTag("workspace-background-workspace").assertIsDisplayed()
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace")).fetchSemanticsNodes().size == 1
        }
        val dashboardNode = compose.onNodeWithTag("workspace-background-workspace")
            .fetchSemanticsNode().id
        assertBackgroundPixels("workspace-background-workspace", "background-shell-home-layer")
        compose.onNodeWithTag("workspace-rail-Gaming").performClick()
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-background-workspace").assertIsDisplayed()
        assertEquals(
            dashboardNode,
            compose.onNodeWithTag("workspace-background-workspace").fetchSemanticsNode().id,
        )
        assertBackgroundPixels("workspace-background-workspace", "background-shell-workspace-layer")
        assertEquals(workspaceBackgroundSlot(Destination.Dashboard), workspaceBackgroundSlot(Destination.Gaming))
        captureRoot("background-shell-workspace-slot")
    }

    @Test
    fun shellWithholdsDefaultArtworkUntilSavedCustomLoadCompletesInWideAndCompactLayouts() = runBlocking {
        assertRequestedDisplay()
        val store = WorkspaceBackgroundStore(context)
        val imported = store.import(
            WorkspaceBackgroundSlot.WORKSPACE,
            WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.VALID_JPEG),
        ).image
        val loaded = store.load()
        val loadGate = CompletableDeferred<Unit>()
        val controller = WorkspaceBackgroundController(
            ApplicationProvider.getApplicationContext<Application>(),
            DelayedLoadWorkspaceBackgroundRepository(loadGate, loaded),
        )
        val width = mutableStateOf(1_280)
        compose.setContent {
            val backgroundState by controller.state.collectAsState()
            DCompanyTheme(AppearancePreference.Night) {
                Box(Modifier.width(width.value.dp).height(800.dp)) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Dashboard, Destination.Settings),
                        currentDestination = Destination.Dashboard,
                        employeeName = "Test",
                        locationLabel = "Test shop",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(),
                        syncing = false,
                        canChangeTill = false,
                        onOpenSupport = {},
                        onChangeTill = {},
                        onSignOut = {},
                        backgroundState = backgroundState,
                    ) { _, _ -> Box(Modifier.fillMaxSize()) }
                }
            }
        }

        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-initializing").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-workspace").assertDoesNotExist()
        compose.runOnIdle { width.value = 600 }
        compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-initializing").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-workspace").assertDoesNotExist()

        loadGate.complete(Unit)
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace"))
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-background-initializing").assertDoesNotExist()
        assertImportedBitmapMatchesCanonical(imported, "workspace-background-bitmap-workspace")

        compose.runOnIdle { width.value = 1_280 }
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace"))
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        assertImportedBitmapMatchesCanonical(imported, "workspace-background-bitmap-workspace")
    }

    private fun assertRequestedDisplay() {
        val requested = InstrumentationRegistry.getArguments()
            .getString(BackgroundTestDisplayIdArgument)?.toIntOrNull()
        val display = checkNotNull(compose.activity.display)
        assertEquals(requested ?: 0, display.displayId)
        if (requested in setOf(2, 5, 6)) {
            assertEquals(2560, display.mode.physicalWidth)
            assertEquals(1600, display.mode.physicalHeight)
            assertEquals(320, compose.activity.resources.displayMetrics.densityDpi)
        }
    }

    private fun captureRoot(name: String) {
        compose.waitForIdle()
        val directory = captureDirectory()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val png = directory.resolve("$name.png")
        try {
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val arguments = InstrumentationRegistry.getArguments()
        directory.resolve("$name.provenance.json").writeText(
            """{"phase":"UNIFIED_WORKSPACE_BACKGROUND_BG_R1","capture_api":"ComposeTestRule.onRoot().captureToImage()","test_class":"cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiTest","application_id":"${cloud.dcompany.erp.BuildConfig.APPLICATION_ID}","android_api":${Build.VERSION.SDK_INT},"display_id":${compose.activity.display?.displayId ?: -1},"density_dpi":${compose.activity.resources.displayMetrics.densityDpi},"source_inventory_sha256":${arguments.getString("dcompany.sourceInventorySha256").json()},"target_apk_sha256":${arguments.getString("dcompany.targetApkSha256").json()},"test_apk_sha256":${arguments.getString("dcompany.testApkSha256").json()},"png":"${png.name}","png_sha256":"${png.sha256()}"}
""",
        )
    }

    private fun assertBackgroundPixels(tag: String, name: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val colors = linkedSetOf<Int>()
        var luminanceMin = 255
        var luminanceMax = 0
        val xStep = (bitmap.width / 24).coerceAtLeast(1)
        val yStep = (bitmap.height / 16).coerceAtLeast(1)
        for (y in 0 until bitmap.height step yStep) {
            for (x in 0 until bitmap.width step xStep) {
                val color = bitmap.getPixel(x, y)
                colors += color
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                luminanceMin = minOf(luminanceMin, luminance)
                luminanceMax = maxOf(luminanceMax, luminance)
            }
        }
        try {
            assertTrue("decoded background must contain real image variation", colors.size >= 12)
            assertTrue("decoded background must not be a flat/black placeholder", luminanceMax - luminanceMin >= 24)
            val directory = captureDirectory()
            val png = directory.resolve("$name.png")
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            directory.resolve("$name.fingerprint.json").writeText(
                """{"sampled_unique_colors":${colors.size},"luminance_min":$luminanceMin,"luminance_max":$luminanceMax,"png":"${png.name}","png_sha256":"${png.sha256()}"}\n""",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertImportedBitmapMatchesCanonical(image: WorkspaceBackgroundImage, tag: String) {
        val canonical = checkNotNull(BitmapFactory.decodeFile(requireNotNull(image.privatePath)))
        val rendered = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        try {
            val plan = workspaceBackgroundCropPlan(
                sourceWidth = canonical.width,
                sourceHeight = canonical.height,
                targetWidth = rendered.width,
                targetHeight = rendered.height,
            )
            val proofs = listOf(
                0.20f to 0.20f,
                0.80f to 0.20f,
                0.50f to 0.50f,
                0.20f to 0.80f,
                0.80f to 0.80f,
            ).map { (normalizedX, normalizedY) ->
                val outputX = (normalizedX * (rendered.width - 1)).roundToInt()
                val outputY = (normalizedY * (rendered.height - 1)).roundToInt()
                val sourceX = (
                    plan.sourceLeft +
                        (outputX + 0.5f) * (plan.sourceRight - plan.sourceLeft) / rendered.width
                    ).toInt().coerceIn(plan.sourceLeft, plan.sourceRight - 1)
                val sourceY = (
                    plan.sourceTop +
                        (outputY + 0.5f) * (plan.sourceBottom - plan.sourceTop) / rendered.height
                    ).toInt().coerceIn(plan.sourceTop, plan.sourceBottom - 1)
                val source = canonical.getPixel(sourceX, sourceY)
                val expected = rendererCompositedPixel(source, rendered.height, outputY)
                val actual = rendered.getPixel(outputX, outputY)
                ImportedPixelProof(outputX, outputY, sourceX, sourceY, source, expected, actual)
            }
            recordImportedMultipointProof(
                name = "background-imported-canonical-multipoint",
                bitmap = rendered,
                sourceWidth = canonical.width,
                sourceHeight = canonical.height,
                cropLeft = plan.sourceLeft,
                cropTop = plan.sourceTop,
                cropRight = plan.sourceRight,
                cropBottom = plan.sourceBottom,
                proofs = proofs,
                tolerance = 4,
            )
            proofs.forEach { proof ->
                assertColorChannelsWithin(proof.expected, proof.actual, tolerance = 4)
            }
        } finally {
            canonical.recycle()
            rendered.recycle()
        }
    }

    private fun assertTextDoesNotOverflow(text: String, evidencePrefix: String? = null) {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("one text layout for $text", 1, results.size)
        val result = results.single()
        evidencePrefix?.let { prefix ->
            val bounds = node.getUnclippedBoundsInRoot()
            val rootBounds = compose.onNodeWithTag("background-settings-compact-root")
                .getUnclippedBoundsInRoot()
            val safeText = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            captureDirectory().resolve("$prefix-$safeText.text-layout.json").writeText(
                """{"text":${text.json()},"density":${compose.activity.resources.displayMetrics.density},"font_scale":1.3,"layout_width_px":${result.size.width},"layout_height_px":${result.size.height},"constraints_min_width_px":${result.layoutInput.constraints.minWidth},"constraints_max_width_px":${result.layoutInput.constraints.maxWidth},"paragraph_width_px":${result.multiParagraph.width},"max_intrinsic_width_px":${result.multiParagraph.maxIntrinsicWidth},"line_count":${result.lineCount},"did_overflow_width":${result.didOverflowWidth},"did_overflow_height":${result.didOverflowHeight},"line_end":${result.getLineEnd(result.lineCount - 1)},"visible_end":${result.getLineEnd(result.lineCount - 1, visibleEnd = true)},"node_bounds_dp":{"left":${bounds.left.value},"top":${bounds.top.value},"right":${bounds.right.value},"bottom":${bounds.bottom.value}},"root_bounds_dp":{"left":${rootBounds.left.value},"top":${rootBounds.top.value},"right":${rootBounds.right.value},"bottom":${rootBounds.bottom.value}}}\n""",
            )
        }
        assertTextLayoutGlyphsFit(text, result, bounds = node.getUnclippedBoundsInRoot())
    }

    private fun rendererCompositedPixel(source: Int, renderedHeight: Int, outputY: Int): Int {
        val verticalFraction = outputY.toFloat() / (renderedHeight - 1).coerceAtLeast(1)
        val verticalAlpha = if (verticalFraction <= 0.45f) {
            0.44f + (0.26f - 0.44f) * (verticalFraction / 0.45f)
        } else {
            0.26f + (0.60f - 0.26f) * ((verticalFraction - 0.45f) / 0.55f)
        }
        fun darken(channel: Int, alpha: Float): Int = (channel * (1f - alpha)).roundToInt()
        fun channel(value: Int): Int = darken(darken(value, verticalAlpha), 0.10f)
        return Color.rgb(channel(Color.red(source)), channel(Color.green(source)), channel(Color.blue(source)))
    }

    private fun assertColorChannelsWithin(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("red expected=${Color.red(expected)} actual=${Color.red(actual)}", kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue("green expected=${Color.green(expected)} actual=${Color.green(actual)}", kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue("blue expected=${Color.blue(expected)} actual=${Color.blue(actual)}", kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }

    private fun recordRendererColorProof(
        name: String,
        bitmap: Bitmap,
        source: Int,
        expected: Int,
        actual: Int,
        tolerance: Int,
    ) {
        val directory = captureDirectory()
        val png = directory.resolve("$name.png")
        png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val centerFraction = (bitmap.height / 2f) / (bitmap.height - 1).coerceAtLeast(1)
        val verticalAlpha = if (centerFraction <= 0.45f) {
            0.44f + (0.26f - 0.44f) * (centerFraction / 0.45f)
        } else {
            0.26f + (0.60f - 0.26f) * ((centerFraction - 0.45f) / 0.55f)
        }
        directory.resolve("$name.color-proof.json").writeText(
            """{"capture_api":"ComposeTestRule.captureToImage","tagged_image_region_includes_later_sibling_scrims":true,"source_rgb":[${Color.red(source)},${Color.green(source)},${Color.blue(source)}],"vertical_black_alpha":$verticalAlpha,"final_black_alpha":0.10,"expected_rgb":[${Color.red(expected)},${Color.green(expected)},${Color.blue(expected)}],"actual_rgb":[${Color.red(actual)},${Color.green(actual)},${Color.blue(actual)}],"per_channel_tolerance":$tolerance,"png":"${png.name}","png_sha256":"${png.sha256()}"}\n""",
        )
    }

    private fun assertTextLayoutGlyphsFit(
        text: String,
        result: androidx.compose.ui.text.TextLayoutResult,
        bounds: androidx.compose.ui.unit.DpRect,
    ) {
        val lastLine = result.lineCount - 1
        assertEquals("$text final line end", text.length, result.getLineEnd(lastLine))
        assertEquals("$text final visible end", text.length, result.getLineEnd(lastLine, visibleEnd = true))
        assertFalse("$text height overflow", result.didOverflowHeight)
        repeat(result.lineCount) { line ->
            assertFalse("$text line $line ellipsized", result.isLineEllipsized(line))
            assertTrue("$text line $line left", result.getLineLeft(line) >= -1f)
            assertTrue("$text line $line right", result.getLineRight(line) <= result.size.width + 1f)
            assertTrue("$text line $line top", result.getLineTop(line) >= -1f)
            assertTrue("$text line $line bottom", result.getLineBottom(line) <= result.size.height + 1f)
        }
        text.forEachIndexed { index, character ->
            if (!character.isWhitespace()) {
                val glyph = result.getBoundingBox(index)
                assertTrue("$text glyph $index left", glyph.left >= -1f)
                assertTrue("$text glyph $index right", glyph.right <= result.size.width + 1f)
                assertTrue("$text glyph $index top", glyph.top >= -1f)
                assertTrue("$text glyph $index bottom", glyph.bottom <= result.size.height + 1f)
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(
            "$text node/layout width",
            kotlin.math.abs((bounds.right - bounds.left).value * density - result.size.width) <= 1f,
        )
        assertTrue(
            "$text node/layout height",
            kotlin.math.abs((bounds.bottom - bounds.top).value * density - result.size.height) <= 1f,
        )
    }

    private data class ImportedPixelProof(
        val outputX: Int,
        val outputY: Int,
        val sourceX: Int,
        val sourceY: Int,
        val source: Int,
        val expected: Int,
        val actual: Int,
    )

    private fun recordImportedMultipointProof(
        name: String,
        bitmap: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        proofs: List<ImportedPixelProof>,
        tolerance: Int,
    ) {
        val directory = captureDirectory()
        val png = directory.resolve("$name.png")
        png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val pointsJson = proofs.joinToString(separator = ",") { proof ->
            val verticalFraction = proof.outputY.toFloat() / (bitmap.height - 1).coerceAtLeast(1)
            val verticalAlpha = if (verticalFraction <= 0.45f) {
                0.44f + (0.26f - 0.44f) * (verticalFraction / 0.45f)
            } else {
                0.26f + (0.60f - 0.26f) * ((verticalFraction - 0.45f) / 0.55f)
            }
            """{"output_xy":[${proof.outputX},${proof.outputY}],"source_xy":[${proof.sourceX},${proof.sourceY}],"source_rgb":[${Color.red(proof.source)},${Color.green(proof.source)},${Color.blue(proof.source)}],"vertical_black_alpha":$verticalAlpha,"final_black_alpha":0.10,"expected_rgb":[${Color.red(proof.expected)},${Color.green(proof.expected)},${Color.blue(proof.expected)}],"actual_rgb":[${Color.red(proof.actual)},${Color.green(proof.actual)},${Color.blue(proof.actual)}]}"""
        }
        directory.resolve("$name.color-proof.json").writeText(
            """{"capture_api":"ComposeTestRule.captureToImage","source_size":[${sourceWidth},${sourceHeight}],"output_size":[${bitmap.width},${bitmap.height}],"crop_rect":[${cropLeft},${cropTop},${cropRight},${cropBottom}],"mapping":"independent centered source crop to output coordinates","per_channel_tolerance":$tolerance,"points":[$pointsJson],"png":"${png.name}","png_sha256":"${png.sha256()}"}\n""",
        )
    }

    private fun assertAllTextDoesNotOverflow(text: String) {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("expected at least one text layout for $text", count > 0)
        repeat(count) { index ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals("one text layout for $text[$index]", 1, results.size)
            val result = results.single()
            assertTextLayoutGlyphsFit(text, result, nodes[index].getUnclippedBoundsInRoot())
        }
    }

    private fun assertFullyInsideCompactRoot(tag: String) {
        val rootBounds = compose.onNodeWithTag("background-settings-compact-root")
            .getUnclippedBoundsInRoot()
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$tag left outside compact root", bounds.left >= rootBounds.left)
        assertTrue("$tag right outside compact root", bounds.right <= rootBounds.right)
        assertTrue("$tag top outside compact root", bounds.top >= rootBounds.top)
        assertTrue("$tag bottom outside compact root", bounds.bottom <= rootBounds.bottom)
    }

    private fun assertProviderRejected(operation: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(
            "$operation must be rejected by the provider",
            failure is UnsupportedOperationException || failure?.cause is UnsupportedOperationException,
        )
    }

    private fun captureDirectory(): File {
            val profile = InstrumentationRegistry.getArguments()
                .getString(BackgroundTestDisplayIdArgument)
                ?.toIntOrNull()
                ?.let { "display-$it-hd" }
                ?: "default-display"
        return base.filesDir.resolve("phase-a-background-screenshots-v2")
            .resolve(profile).resolve(CaptureRunId).apply { check(isDirectory || mkdirs()) }
    }

    private fun String?.json(): String = this?.let { "\"$it\"" } ?: "null"
    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { val CaptureRunId = "${System.currentTimeMillis()}-${android.os.Process.myPid()}" }

}

private class RecordingActivityResultRegistry(
    private val results: List<Uri?>,
) : ActivityResultRegistry() {
    val launchedMimeTypes = mutableListOf<Array<String>>()
    private var resultIndex = 0

    @Suppress("UNCHECKED_CAST")
    override fun <I : Any?, O : Any?> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launchedMimeTypes += (input as Array<String>).copyOf()
        dispatchResult(requestCode, results.getOrNull(resultIndex++) as O)
    }
}

private class BlockingWorkspaceBackgroundRepository(
    private val holdMillis: Long,
) : WorkspaceBackgroundRepository {
    val importCalls = AtomicInteger()
    override suspend fun load() = WorkspaceBackgroundUiState()
    override suspend fun import(
        slot: WorkspaceBackgroundSlot,
        uri: Uri,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation {
        importCalls.incrementAndGet()
        withContext(Dispatchers.IO) { Thread.sleep(holdMillis) }
        return WorkspaceBackgroundMutation(bundledWorkspaceBackground(slot, generation = 1L))
    }
    override suspend fun reset(
        slot: WorkspaceBackgroundSlot,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ) = WorkspaceBackgroundMutation(bundledWorkspaceBackground(slot, generation = 1L))
    override suspend fun selectPreset(
        presetId: String,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Preset selection is unexpected in the blocking importer fixture.")
    override suspend fun useSavedCustom(
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Saved custom selection is unexpected in the blocking importer fixture.")
}

private class PromptFailureWorkspaceBackgroundRepository : WorkspaceBackgroundRepository {
    val importCalls = AtomicInteger()
    override suspend fun load() = WorkspaceBackgroundUiState()
    override suspend fun import(
        slot: WorkspaceBackgroundSlot,
        uri: Uri,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation {
        importCalls.incrementAndGet()
        throw WorkspaceBackgroundInputException("Choose a landscape image.")
    }
    override suspend fun reset(
        slot: WorkspaceBackgroundSlot,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ) = WorkspaceBackgroundMutation(bundledWorkspaceBackground(slot, generation = 1L))
    override suspend fun selectPreset(
        presetId: String,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Preset selection is unexpected in the importer failure fixture.")
    override suspend fun useSavedCustom(
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Saved custom selection is unexpected in the importer failure fixture.")
}

private class DelayedLoadWorkspaceBackgroundRepository(
    private val gate: CompletableDeferred<Unit>,
    private val loaded: WorkspaceBackgroundUiState,
) : WorkspaceBackgroundRepository {
    override suspend fun load(): WorkspaceBackgroundUiState {
        gate.await()
        return loaded
    }

    override suspend fun import(
        slot: WorkspaceBackgroundSlot,
        uri: Uri,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Import is unexpected in the delayed-load fixture.")

    override suspend fun reset(
        slot: WorkspaceBackgroundSlot,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Reset is unexpected in the delayed-load fixture.")

    override suspend fun selectPreset(
        presetId: String,
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Preset selection is unexpected in the delayed-load fixture.")

    override suspend fun useSavedCustom(
        arbiter: WorkspaceBackgroundCommitArbiter,
    ): WorkspaceBackgroundMutation = error("Saved custom selection is unexpected in the delayed-load fixture.")
}

private const val BackgroundTestDisplayIdArgument = "dcompany.testDisplayId"

private val RequestedBackgroundDisplay: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(BackgroundTestDisplayIdArgument)?.let { value ->
        val requested = value.toIntOrNull()
        require(requested in setOf(0, 2, 5, 6)) {
            "Only verified HD displays 0, 2, 5 and 6 are supported: $value"
        }
        requested
    }
}

private fun ActivityScenarioRule<ComponentActivity>.backgroundActivity(): ComponentActivity {
    var activity: ComponentActivity? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity).also { current ->
        if (RequestedBackgroundDisplay == 0) {
            val display = checkNotNull(current.display)
            check(display.displayId == 0 && display.mode.physicalWidth == 2_560 &&
                display.mode.physicalHeight == 1_600 && current.resources.displayMetrics.densityDpi == 320) {
                "Display 0 background proof requires the verified 2560x1600px, 320dpi host"
            }
        }
    }
}

private fun createBackgroundComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val requested = RequestedBackgroundDisplay
    val rule = if (requested == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(requested).toBundle(),
        )
    }
    return AndroidComposeTestRule(rule) { it.backgroundActivity() }
}

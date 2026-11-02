package cloud.dcompany.erp.ui.theme

import cloud.dcompany.erp.ui.Destination
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBackgroundPolicyTest {
    @Test
    fun everyAuthenticatedDestinationUsesTheSingleWorkspaceSelection() {
        Destination.entries.forEach { destination ->
            assertEquals(
                WorkspaceBackgroundSlot.WORKSPACE,
                workspaceBackgroundSlot(destination),
            )
        }
    }

    @Test
    fun presetRegistryHasStableUniqueIdsAndVerifiedFullHdOrBetterAssets() {
        assertEquals(6, WorkspaceBackgroundPresets.entries.size)
        assertEquals(WorkspaceBackgroundPresets.entries.size, WorkspaceBackgroundPresets.entries.map { it.id }.toSet().size)
        assertEquals(WorkspaceBackgroundPresets.DefaultId, WorkspaceBackgroundPresets.default.id)
        WorkspaceBackgroundPresets.entries.forEach { preset ->
            assertTrue(preset.id.matches(Regex("[a-z0-9_]+")))
            assertEquals(3_840, preset.width)
            assertEquals(2_160, preset.height)
            assertTrue(preset.contentId.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun customIdentityRepairPublishesActualBytesAndLegacyMismatchFailsClosed() {
        fun imported(contentId: String) = WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = 2_560,
            height = 1_080,
            generation = 0L,
            contentId = contentId,
            privatePath = "/private/workspace.jpg",
        )
        val actual = imported("b".repeat(64))
        val stale = WorkspaceBackgroundSelection(
            kind = WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE,
            generation = 7L,
            expectedCustomContentId = "a".repeat(64),
        )
        val failedWarnings = mutableListOf<String>()
        val afterFailedRepair = reconcileSelectionWithLoadedCustom(
            selection = stale,
            workspaceCustom = actual,
            legacyHomeCustom = null,
            nextGeneration = { 8L },
            persist = { error("injected selection write failure") },
            warnings = failedWarnings,
        )
        assertEquals(7L, afterFailedRepair.generation)
        assertEquals(actual.contentId, afterFailedRepair.expectedCustomContentId)
        assertTrue(failedWarnings.single().contains("restart metadata"))

        var persisted: WorkspaceBackgroundSelection? = null
        val repairedWarnings = mutableListOf<String>()
        val repaired = reconcileSelectionWithLoadedCustom(
            selection = stale,
            workspaceCustom = actual,
            legacyHomeCustom = null,
            nextGeneration = { 8L },
            persist = { persisted = it; null },
            warnings = repairedWarnings,
        )
        assertEquals(8L, repaired.generation)
        assertEquals(actual.contentId, repaired.expectedCustomContentId)
        assertEquals(repaired, persisted)
        assertTrue(repairedWarnings.isEmpty())

        val legacyWarnings = mutableListOf<String>()
        val legacyFallback = reconcileSelectionWithLoadedCustom(
            selection = WorkspaceBackgroundSelection(
                WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME,
                generation = 4L,
                expectedCustomContentId = "c".repeat(64),
            ),
            workspaceCustom = null,
            legacyHomeCustom = actual.copy(slot = WorkspaceBackgroundSlot.HOME),
            nextGeneration = { 5L },
            persist = { null },
            warnings = legacyWarnings,
        )
        assertEquals(WorkspaceBackgroundSelectionKind.PRESET, legacyFallback.kind)
        assertEquals(WorkspaceBackgroundPresets.DefaultId, legacyFallback.presetId)
        assertTrue(legacyWarnings.single().contains("no longer matches"))
    }

    @Test
    fun importBoundsAcceptLandscapeAndUltrawideButRejectUnsafeOrSmallInputs() {
        assertNull(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(2560, 1080, 1)))
        assertNull(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(3840, 1600, 1)))
        assertNull(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(1080, 2560, 6)))
        assertTrue(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(1080, 2560, 1))!!.contains("landscape"))
        assertTrue(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(2559, 1080, 1))!!.contains("2560"))
        assertTrue(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(9000, 1200, 1))!!.contains("8192"))
        assertTrue(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(6000, 5000, 1))!!.contains("24 megapixels"))
        assertNull(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(6000, 4000, 1)))
        assertTrue(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(6001, 4000, 1))!!.contains("24 megapixels"))
        assertNull(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(8192, 2000, 1)))
        assertTrue(validateWorkspaceBackgroundBounds(WorkspaceBackgroundBounds(8193, 2000, 1))!!.contains("8192"))
        assertNull(validateWorkspaceBackgroundInputLength(WorkspaceBackgroundInputCapBytes))
        assertTrue(validateWorkspaceBackgroundInputLength(WorkspaceBackgroundInputCapBytes + 1)!!.contains("32 MiB"))
        assertNull(validateWorkspaceBackgroundCanonicalLength(WorkspaceBackgroundCanonicalCapBytes))
        assertTrue(validateWorkspaceBackgroundCanonicalLength(WorkspaceBackgroundCanonicalCapBytes + 1)!!.contains("20 MiB"))
    }

    @Test
    fun canonicalAndRendererPlansBoundMemoryWithoutUpscalingImports() {
        val plan = workspaceBackgroundDecodePlan(8192, 4096)
        assertEquals(1, plan.sampleSize)
        assertTrue(plan.outputWidth <= 4096)
        assertTrue(plan.outputWidth.toLong() * plan.outputHeight <= 10_000_000L)
        val unchanged = workspaceBackgroundDecodePlan(2560, 1080)
        assertEquals(2560, unchanged.outputWidth)
        assertEquals(1080, unchanged.outputHeight)
        assertEquals(2, workspaceBackgroundSampleSize(3840, 2160, 1280, 800))
        assertEquals(1, workspaceBackgroundSampleSize(2560, 1080, 2560, 1600))
    }

    @Test
    fun canonicalPlanPreservesLargestSafeScaleAcrossEdgeBoundaryAndUltrawideInputs() {
        val atBoundary = workspaceBackgroundDecodePlan(4096, 1080)
        assertEquals(4096, atBoundary.outputWidth)
        assertEquals(1080, atBoundary.outputHeight)

        val overBoundary = workspaceBackgroundDecodePlan(4097, 1080)
        assertEquals(4096, overBoundary.outputWidth)
        assertEquals(1079, overBoundary.outputHeight)
        assertTrue(overBoundary.outputWidth.toLong() * overBoundary.outputHeight <= 10_000_000L)

        val acceptedUltrawide = workspaceBackgroundDecodePlan(8192, 1080)
        assertEquals(4096, acceptedUltrawide.outputWidth)
        assertEquals(540, acceptedUltrawide.outputHeight)

        val maximumMegapixels = workspaceBackgroundDecodePlan(6000, 4000)
        assertTrue(maximumMegapixels.outputWidth in 3872..3873)
        assertTrue(maximumMegapixels.outputHeight in 2581..2582)
        assertTrue(maximumMegapixels.outputWidth.toLong() * maximumMegapixels.outputHeight <= 10_000_000L)
    }

    @Test
    fun rendererCenterCropsExtremeAspectsIntoExactMeasuredPixelBudget() {
        val ultrawide = workspaceBackgroundCropPlan(4096, 540, 2560, 1600)
        assertEquals(864, ultrawide.sourceWidth)
        assertEquals(540, ultrawide.sourceHeight)
        assertEquals(1616, ultrawide.sourceLeft)
        assertEquals(4_096_000L, ultrawide.outputPixels)
        assertEquals(2560, ultrawide.outputWidth)
        assertEquals(1600, ultrawide.outputHeight)

        val tallViewport = workspaceBackgroundCropPlan(3840, 2160, 800, 1280)
        assertEquals(1350, tallViewport.sourceWidth)
        assertEquals(2160, tallViewport.sourceHeight)
        assertEquals(1_024_000L, tallViewport.outputPixels)
        assertEquals(
            tallViewport.sourceWidth.toDouble() / tallViewport.sourceHeight,
            tallViewport.outputWidth.toDouble() / tallViewport.outputHeight,
            0.001,
        )

        val actualWorkspaceViewport = workspaceBackgroundCropPlan(3840, 2160, 1264, 784)
        assertEquals(1264, actualWorkspaceViewport.outputWidth)
        assertEquals(784, actualWorkspaceViewport.outputHeight)
        assertEquals(
            1264.0 / 784.0,
            actualWorkspaceViewport.sourceWidth.toDouble() / actualWorkspaceViewport.sourceHeight,
            0.001,
        )
    }

    @Test
    fun containerInspectionRejectsTruncatedJpegPngAndBadPngCrc() {
        val directory = createTempDirectory("workspace-background-containers").toFile()
        val jpeg = File(directory, "complete.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0xFF.toByte(), 0xD9.toByte()))
        }
        assertTrue(inspectWorkspaceImageContainer(jpeg, WorkspaceImageFormat.JPEG).complete)
        jpeg.writeBytes(jpeg.readBytes().dropLast(2).toByteArray())
        assertFalse(inspectWorkspaceImageContainer(jpeg, WorkspaceImageFormat.JPEG).complete)

        val png = File(directory, "complete.png").apply { writeBytes(structuralPng()) }
        assertTrue(inspectWorkspaceImageContainer(png, WorkspaceImageFormat.PNG).complete)
        png.writeBytes(png.readBytes().dropLast(4).toByteArray())
        assertFalse(inspectWorkspaceImageContainer(png, WorkspaceImageFormat.PNG).complete)
        val badCrc = structuralPng().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        png.writeBytes(badCrc)
        assertFalse(inspectWorkspaceImageContainer(png, WorkspaceImageFormat.PNG).complete)
    }

    @Test
    fun bundledDefaultsAndGalleryAreExactVerified3840By2160Files() {
        val root = projectRoot().resolve("src/main/res/drawable-nodpi")
        val expected = mapOf(
            "workspace_home_default.jpg" to "ce0f12e8b46011eaa69161c031d23d10e545f9b89f2f49a6effb11f34640dcfe",
            "workspace_shared_default.jpg" to "1cb71c65397fb9337887a052ae3b8ca513878d67157bf911941f586ab8288b60",
            "workspace_preset_call_of_duty.jpg" to "9ae6c50c9097a0e48c1c7850a029cddad1bedaa1f42b97851d0cb92508315ef3",
            "workspace_preset_assassins_creed.jpg" to "96564ae5a3b0add567a06e9f319c13e4754e02613fdc1ad8846453f67dd50790",
            "workspace_preset_fc27.jpg" to "ab51cde3444c1081e615a84a4305cdb56104d7cbd7d0422389f5bd363036cdd1",
            "workspace_preset_wukong.jpg" to "464620be4c5f5a50a69ef9261666d2c1e1ae274c50863da76a7caddbc539455e",
            "workspace_preset_ghost_of_tsushima.png" to "6ae5b41dfdd4ab381f9a4bd69f5fc121116819552c5561bbfe48d43ea3751bc7",
        )
        expected.forEach { (name, sha) ->
            val file = root.resolve(name)
            assertEquals(sha, file.readBytes().sha256())
        }
    }

    @Test
    fun cancellationBeforeCommitAndMoveFailureRetainOldFile() {
        val directory = createTempDirectory("workspace-background").toFile()
        val target = File(directory, "home.jpg").apply { writeText("old") }
        val cancelledTemp = File(directory, ".home.jpg.tmp-cancelled").apply { writeText("new") }
        val cancelled = WorkspaceBackgroundCommitArbiter().apply { assertTrue(cancel()) }
        assertThrows(IllegalStateException::class.java) {
            atomicReplaceWorkspaceBackground(cancelledTemp, target, cancelled)
        }
        assertEquals("old", target.readText())

        val failedTemp = File(directory, ".home.jpg.tmp-failed").apply { writeText("new") }
        assertThrows(IllegalStateException::class.java) {
            atomicReplaceWorkspaceBackground(
                failedTemp,
                target,
                WorkspaceBackgroundCommitArbiter(),
                move = { _, _ -> error("injected precommit failure") },
            )
        }
        assertEquals("old", target.readText())
    }

    @Test
    fun renameIsCommitPointAndDirectoryWarningDoesNotMisreportOldFile() {
        val directory = createTempDirectory("workspace-background").toFile()
        val target = File(directory, "workspace.jpg").apply { writeText("old") }
        val temp = File(directory, ".workspace.jpg.tmp-new").apply { writeText("new") }
        val arbiter = WorkspaceBackgroundCommitArbiter()
        val warning = atomicReplaceWorkspaceBackground(
            temp,
            target,
            arbiter,
            move = { source, destination ->
                destination.delete()
                assertTrue(source.renameTo(destination))
            },
            forceDirectory = { "durability warning" },
        )
        assertEquals("new", target.readText())
        assertEquals("durability warning", warning)
        assertEquals(WorkspaceBackgroundOperationPhase.COMMITTED, arbiter.phase())
        assertFalse(arbiter.cancel())
    }

    @Test
    fun resetUsesUnlinkAsCommitPoint() {
        val directory = createTempDirectory("workspace-background").toFile()
        val target = File(directory, "home.jpg").apply { writeText("custom") }
        assertThrows(IllegalStateException::class.java) {
            resetWorkspaceBackgroundFile(
                target,
                WorkspaceBackgroundCommitArbiter(),
                delete = { false },
            )
        }
        assertTrue(target.exists())

        val committed = WorkspaceBackgroundCommitArbiter()
        val warning = resetWorkspaceBackgroundFile(
            target,
            committed,
            forceDirectory = { "directory sync warning" },
        )
        assertFalse(target.exists())
        assertEquals("directory sync warning", warning)
        assertEquals(WorkspaceBackgroundOperationPhase.COMMITTED, committed.phase())
    }

    private fun projectRoot(): File = File(System.getProperty("user.dir")).let { cwd ->
        if (cwd.name == "app") cwd else cwd.resolve("app")
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun structuralPng(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            output.writePngChunk("IHDR", ByteArray(13))
            output.writePngChunk("IDAT", byteArrayOf(1, 2, 3))
            output.writePngChunk("IEND", byteArrayOf())
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writePngChunk(type: String, payload: ByteArray) {
        writeInt(payload.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(typeBytes)
        write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }
        writeInt(crc.value.toInt())
    }
}

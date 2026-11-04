package cloud.dcompany.erp.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import cloud.dcompany.erp.ui.screens.home.ExecutiveHomeFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceGlassPolicyTest {
    private val sceneScrimExtrema = listOf(.44f, .26f, .60f)

    @Test
    fun uniformSceneAdmissionMatchesIndependentContrastOracleAcrossLuminanceRange() {
        val scenes = (0..255).map { gray ->
            Color(0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray)
        } + listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow)
        fun originalRatio(first: Color, second: Color): Double =
            (maxOf(first.luminance(), second.luminance()).toDouble() + .05) /
                (minOf(first.luminance(), second.luminance()).toDouble() + .05)
        WorkspaceGlassAdaptiveNightRoles.forEach { role ->
            val normal = workspaceGlassRoleStyle(role, day = false)
            listOf(normal, normal.copy(primaryContent = Color.Black, secondaryContent = Color.Black)).forEach { style ->
                listOf(false, true).forEach { grade ->
                    scenes.forEach { scene ->
                        val floor = if (role == WorkspaceGlassRole.MODAL) .60f else .58f
                        // A uniform scene's linear scrim reaches all extrema at these three heights.
                        val expected = WorkspaceGlassAdaptiveAlphaCandidates.firstOrNull { alpha ->
                            alpha >= floor && listOf(0f, .45f, 1f).all { y ->
                                val graded = workspaceGlassCompleteSceneColor(scene, role, y, grade)
                                val material = Color(0xFF05060B).copy(alpha = alpha).compositeOver(graded)
                                originalRatio(style.primaryContent, material) >= 4.8 &&
                                    originalRatio(style.secondaryContent, material) >= 4.8 &&
                                    originalRatio(style.iconContent, material) >= 3.2
                            }
                        }
                        assertEquals(
                            "role=$role scene=$scene grade=$grade primary=${style.primaryContent}",
                            expected,
                            workspaceGlassAdmittedTintAlpha(
                                uniformGrid(scene), derivativeKey(), Rect(0f, 0f, 1280f, 800f),
                                1280, 800, role, style, applySceneGrade = grade,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun derivativeKeyTracksEveryDisplayedPixelIdentityField() {
        val base = WorkspaceBackgroundRenderKey(
            WorkspaceBackgroundSlot.HOME,
            WorkspaceBackgroundSource.IMPORTED,
            7,
            "a".repeat(64),
            1280,
            800,
            41,
        )
        val key = WorkspaceGlassDerivativeKey(base, 9L, WorkspaceGlassAlgorithmVersion, 512, 320, WorkspaceGlassBlurRadius)
        assertNotEquals(key, key.copy(actualKey = base.copy(slot = WorkspaceBackgroundSlot.WORKSPACE)))
        assertNotEquals(key, key.copy(actualKey = base.copy(source = WorkspaceBackgroundSource.BUNDLED)))
        assertNotEquals(key, key.copy(actualKey = base.copy(generation = 8)))
        assertNotEquals(key, key.copy(actualKey = base.copy(contentId = "b".repeat(64))))
        assertNotEquals(key, key.copy(actualKey = base.copy(viewportWidth = 1279)))
        assertNotEquals(key, key.copy(actualKey = base.copy(viewportHeight = 799)))
        assertNotEquals(key, key.copy(actualKey = base.copy(decoderIdentity = 42)))
        assertNotEquals(key, key.copy(frameEpoch = 10L))
        assertNotEquals(key, key.copy(algorithmVersion = key.algorithmVersion + 1))
        assertNotEquals(key, key.copy(blurRadius = key.blurRadius + 1))
        val fallback = base.copy(
            source = WorkspaceBackgroundSource.BUNDLED,
            generation = 0,
            contentId = bundledWorkspaceBackground(WorkspaceBackgroundSlot.HOME).contentId,
        )
        assertNotEquals(
            "a bundled fallback must not satisfy the imported request key",
            key,
            key.copy(actualKey = fallback),
        )
    }

    @Test
    fun sceneGradePolicyKeepsEveryRoleCompleteAndBypassExact() {
        WorkspaceGlassRole.entries.forEach { role ->
            assertEquals("Day $role must keep the complete scene grade", 1f, workspaceGlassSceneGradeStrength(role, day = true), 0f)
            assertEquals("Night $role must keep the complete scene grade", 1f, workspaceGlassSceneGradeStrength(role, day = false), 0f)
        }

        val scene = Color(0xFF9B6731)
        assertEquals(
            "Scenes that opt out of the workspace grade must remain byte-exact",
            scene,
            workspaceGlassCompleteSceneColor(
                scene = scene,
                role = WorkspaceGlassRole.PANEL,
                normalizedY = .45f,
                applySceneGrade = false,
            ),
        )
    }

    @Test
    fun derivativeBudgetStaysWithinPublishedLimitsAcrossAcceptanceViewports() {
        listOf(1280 to 800, 2560 to 1600, 600 to 800, 3840 to 2160).forEach { (width, height) ->
            val size = workspaceGlassDerivativeSize(width, height)
            assertTrue(maxOf(size.width, size.height) <= 512)
            assertTrue(size.width.toLong() * size.height <= 160_000)
            assertTrue(workspaceGlassTransientBytes(size) <= 4L * 1_024 * 1_024)
        }
        val largest = workspaceGlassDerivativeSize(2560, 1600)
        val publishedDerivatives = largest.width.toLong() * largest.height * 4L *
            WorkspaceGlassDerivativeCacheEntries
        val oneNewHdClearFrame = 2560L * 1600 * 4L
        assertTrue(
            WorkspaceClearCacheBudgetKiB * 1024L + publishedDerivatives +
                workspaceGlassTransientBytes(largest) + oneNewHdClearFrame <=
                WorkspaceGlassCombinedStartingBudgetBytes,
        )
    }

    @Test
    fun adjacentSceneLocalBoundsMapToAdjacentDerivativePixels() {
        val left = workspaceGlassSourceRect(Rect(8f, 8f, 192f, 792f), 1280, 800, 512, 320)!!
        val right = workspaceGlassSourceRect(Rect(192f, 8f, 1272f, 64f), 1280, 800, 512, 320)!!
        assertEquals(3, left.left)
        assertEquals(3, left.top)
        assertEquals(77, left.right)
        assertEquals(317, left.bottom)
        assertEquals(76, right.left)
        assertEquals(3, right.top)
        assertEquals(509, right.right)
        assertEquals(26, right.bottom)
        assertEquals(
            "Conservative floor/ceil mapping must retain one shared derivative pixel at a fractional seam",
            1,
            left.right - right.left,
        )
        assertEquals(left.top, right.top)
        val clipped = workspaceGlassSourceRect(Rect(-20f, -10f, 80f, 60f), 1280, 800, 512, 320)!!
        assertEquals(0, clipped.left)
        assertEquals(0, clipped.top)
        assertEquals(32, clipped.right)
        assertEquals(24, clipped.bottom)
    }

    @Test
    fun lateCompletionCannotPublishAfterReplacementOrResize() {
        val candidate = WorkspaceBackgroundRenderKey(
            WorkspaceBackgroundSlot.HOME,
            WorkspaceBackgroundSource.BUNDLED,
            0,
            "a".repeat(64),
            1280,
            800,
            3,
        )
        assertTrue(workspaceGlassIdentityCanPublish(candidate, 9, candidate, 9))
        assertFalse(workspaceGlassIdentityCanPublish(candidate, 10, candidate, 9))
        assertFalse(
            workspaceGlassIdentityCanPublish(
                candidate.copy(viewportHeight = 720),
                9,
                candidate,
                9,
            ),
        )
        assertFalse(
            workspaceGlassIdentityCanPublish(
                candidate.copy(contentId = "b".repeat(64)),
                9,
                candidate,
                9,
            ),
        )
    }

    @Test
    fun everyRoleHasOpaqueFallbackReadableContentAndAVisibleEdge() {
        WorkspaceGlassRole.entries.forEach { role ->
            listOf(false, true).forEach { day ->
                val style = workspaceGlassRoleStyle(role, day)
                assertEquals("$role day=$day fallback", 1f, style.fallback.alpha, 0f)
                if (day || role !in WorkspaceGlassAdaptiveNightRoles) {
                    assertTrue("$role day=$day stroke", style.stroke.alpha >= .39f)
                }
                assertTrue("$role day=$day reflection", style.innerHighlight.alpha > 0f)
                assertTrue(
                    "$role day=$day reflection must remain subordinate to the contour",
                    style.innerHighlight.alpha <= .80f,
                )
                assertTrue(style.fallback.luminance() in 0f..1f)
                val materials = if (!day && role in WorkspaceGlassAdaptiveNightRoles) {
                    val alpha = admittedAlpha(Color.White, role, style)
                    sceneScrimExtrema.map { completeNightMaterial(role, style, it, alpha) }
                } else {
                    listOf(style.tint.compositeOver(if (day) Color.Black else Color.White))
                }
                materials.forEachIndexed { index, material ->
                    val position = if (materials.size == 1) "hostile scene" else "scrim[$index]"
                    if (!day && role in WorkspaceGlassAdaptiveNightRoles) {
                        val renderedEdge = style.stroke.compositeOver(material)
                        val edgeDistance = renderedColorDistance(renderedEdge, material)
                        assertTrue(
                            "$role Night $position alpha-composited edge must remain visibly distinct: " +
                                "edge=$renderedEdge material=$material distance=$edgeDistance",
                            edgeDistance >= .015f,
                        )
                    }
                    if (role != WorkspaceGlassRole.DISABLED) {
                        assertTrue(
                            "$role day=$day $position primary text",
                            contrast(style.primaryContent, material) >= 4.5f,
                        )
                        assertTrue(
                            "$role day=$day $position secondary text",
                            contrast(style.secondaryContent, material) >= 4.5f,
                        )
                    }
                    assertTrue(
                        "$role day=$day $position icon",
                        contrast(style.iconContent, material) >= 3f,
                    )
                }
            }
        }
    }

    @Test
    fun adaptiveNightAdmissionUsesRoleFloorsAndRejectsUnsafeOrStaleInputs() {
        val dark = Color(0xFF10182A)
        val white = Color.White
        val blue = Color(0xFF0066FF)
        val bounds = Rect(0f, 0f, 1280f, 800f)
        WorkspaceGlassAdaptiveNightRoles.forEach { role ->
            val style = workspaceGlassRoleStyle(role, day = false)
            val darkAlpha = workspaceGlassAdmittedTintAlpha(
                uniformGrid(dark), derivativeKey(epoch = 4L), bounds, 1280, 800, role, style,
            )
            val whiteAlpha = workspaceGlassAdmittedTintAlpha(
                uniformGrid(white), derivativeKey(epoch = 4L), bounds, 1280, 800, role, style,
            )
            val blueAlpha = workspaceGlassAdmittedTintAlpha(
                uniformGrid(blue), derivativeKey(epoch = 4L), bounds, 1280, 800, role, style,
            )
            val expectedDarkAlpha = when (role) {
                WorkspaceGlassRole.RAIL,
                WorkspaceGlassRole.PANEL,
                WorkspaceGlassRole.UTILITY,
                WorkspaceGlassRole.CONTROL,
                -> WorkspaceGlassNeutralNightTintFloor
                WorkspaceGlassRole.MODAL -> .62f
                else -> error("$role is not adaptive")
            }
            assertEquals("$role dark scene must select its documented safe candidate", expectedDarkAlpha, darkAlpha!!, 0f)
            assertEquals(
                "$role hostile white under the complete grade must select its documented safe candidate",
                expectedDarkAlpha,
                whiteAlpha!!,
                0f,
            )
            assertTrue(
                "$role style floor must differ from its documented decimal only by 8-bit alpha quantization",
                kotlin.math.abs(style.tint.alpha - when (role) {
                    WorkspaceGlassRole.RAIL,
                    WorkspaceGlassRole.PANEL,
                    WorkspaceGlassRole.UTILITY,
                    WorkspaceGlassRole.CONTROL,
                    -> WorkspaceGlassNeutralNightTintFloor
                    WorkspaceGlassRole.MODAL -> .60f
                    else -> error("$role is not adaptive")
                }) <= 1f / 255f,
            )
            assertTrue("$role saturated blue must have a bounded admitted alpha", blueAlpha != null)
        }

        val unsafe = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = false).copy(
            primaryContent = Color(0xFF05060B),
            secondaryContent = Color(0xFF05060B),
            iconContent = Color(0xFF05060B),
        )
        assertEquals(
            "No candidate must fall back when complete foreground contrast cannot pass",
            null,
            workspaceGlassAdmittedTintAlpha(
                uniformGrid(Color.Black), derivativeKey(epoch = 4L), bounds, 1280, 800,
                WorkspaceGlassRole.PANEL, unsafe,
            ),
        )
        assertNotEquals(derivativeKey(epoch = 4L), derivativeKey(epoch = 5L))
    }

    @Test
    fun nightNeutralFinishUsesTheSharedFloorAndFineDirectionalContourOnly() {
        WorkspaceGlassNeutralNightRoles.forEach { role ->
            val style = workspaceGlassRoleStyle(role, day = false)
            assertEquals(
                "$role must use the shared .58 Night floor",
                WorkspaceGlassNeutralNightTintFloor,
                style.tint.alpha,
                1f / 255f,
            )
            assertEquals("$role Night stroke must retain the packed .14 alpha", 0x24A9B2C4, style.stroke.toArgb())
            val expectedHighlight = if (role in setOf(WorkspaceGlassRole.PANEL, WorkspaceGlassRole.RAIL)) {
                0x40A9B2C4
            } else {
                0x33A9B2C4
            }
            assertEquals("$role must retain its single directional highlight", expectedHighlight, style.innerHighlight.toArgb())
            assertEquals("$role Night contour must remain one physical pixel at 320dpi", .5f, workspaceGlassContourWidth(role, false).value, 0f)
            assertTrue("$role Day tint must remain independent", workspaceGlassRoleStyle(role, day = true).tint != style.tint)
        }
        assertEquals(.75f, workspaceGlassContourWidth(WorkspaceGlassRole.PANEL, day = true).value, 0f)
        assertEquals(1f, workspaceGlassContourWidth(WorkspaceGlassRole.MODAL, day = false).value, 0f)
        assertEquals(1f, workspaceGlassContourWidth(WorkspaceGlassRole.SELECTED_OUTLINE, day = false).value, 0f)
    }

    @Test
    fun workspaceGoldStaysScopedAndSelectedLabelsKeepContrastInBothModes() {
        val fallback = Color(0xFF77561B)
        assertEquals(fallback, workspacePresentationAccent(glassAvailable = false, day = false, fallback = fallback))
        assertEquals(fallback, workspacePresentationAccent(glassAvailable = true, day = true, fallback = fallback))
        assertEquals(WorkspacePresentationGold, workspacePresentationAccent(glassAvailable = true, day = false, fallback = fallback))
        assertEquals(0xFFFFD24A.toInt(), WorkspacePresentationGold.toArgb())
        assertTrue(contrast(WorkspacePresentationOnGold, WorkspacePresentationGold) >= 4.5f)
        listOf(false, true).forEach { day ->
            val selected = workspaceGlassRoleStyle(WorkspaceGlassRole.SELECTED, day)
            assertEquals(WorkspacePresentationGold, selected.fallback)
            assertEquals(WorkspacePresentationOnGold, selected.primaryContent)
        }
    }

    @Test
    fun safetyGridIncludesBilinearNeighbourAndHotspotControlsOnlyIntersectingConsumers() {
        val componentMaxGrid = workspaceGlassSafetyGrid(
            intArrayOf(0xFF200102.toInt(), 0xFF034005.toInt(), 0xFF060760.toInt()),
            width = 3,
            height = 1,
            columns = 1,
            rows = 1,
        )
        assertEquals(
            "The pure packed-ARGB grid must retain each component maximum",
            Color(0xFF204060),
            componentMaxGrid.colorAt(0, 0),
        )
        val width = 64
        val height = 40
        val pixels = IntArray(width * height) { 0xFF080C12.toInt() }
        pixels[20 * width + 31] = 0xFFFFFFFF.toInt()
        val grid = workspaceGlassSafetyGrid(pixels, width, height, columns = 2, rows = 1)
        assertEquals(Color.White, grid.colorAt(0, 0))
        assertEquals("The one-pixel footprint must protect the adjacent bilinear cell", Color.White, grid.colorAt(1, 0))

        val panel = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = false)
        val hotspot = IntArray(WorkspaceGlassSafetyGridColumns * WorkspaceGlassSafetyGridRows) {
            0xFF0C121C.toInt()
        }.also {
            // The white hotspot must force adaptive escalation when this test bypasses the grade;
            // the distant dark peer must stay at the shared neutral floor.
            it[9 * WorkspaceGlassSafetyGridColumns] = 0xFFFFFFFF.toInt()
        }
        val hotspotGrid = WorkspaceGlassSafetyGrid(
            WorkspaceGlassSafetyGridColumns,
            WorkspaceGlassSafetyGridRows,
            hotspot,
        )
        val hotspotSource = workspaceGlassSourceRect(
            Rect(0f, 360f, 40f, 400f),
            1280,
            800,
            derivativeKey().width,
            derivativeKey().height,
        )!!
        assertEquals(0, hotspotSource.left)
        assertEquals(144, hotspotSource.top)
        assertEquals(16, hotspotSource.right)
        assertEquals(160, hotspotSource.bottom)
        assertEquals(Color.White, hotspotGrid.colorAt(0, 9))
        val hotspotAlpha = workspaceGlassAdmittedTintAlpha(
            hotspotGrid, derivativeKey(), Rect(0f, 360f, 40f, 400f), 1280, 800,
            WorkspaceGlassRole.PANEL, panel, applySceneGrade = false,
        )!!
        val distantAlpha = workspaceGlassAdmittedTintAlpha(
            hotspotGrid, derivativeKey(), Rect(640f, 360f, 800f, 400f), 1280, 800,
            WorkspaceGlassRole.PANEL, panel, applySceneGrade = false,
        )!!
        val bilinearNeighbourAlpha = workspaceGlassAdmittedTintAlpha(
            grid, derivativeKey(), Rect(640f, 0f, 1280f, 800f), 1280, 800,
            WorkspaceGlassRole.PANEL, panel, applySceneGrade = false,
        )!!
        val gradeMinimum = workspaceGlassCompleteSceneColor(
            scene = Color.White,
            role = WorkspaceGlassRole.PANEL,
            normalizedY = .45f,
            applySceneGrade = true,
        )
        val gradedFloorMaterial = Color(0xFF05060B)
            .copy(alpha = WorkspaceGlassNeutralNightTintFloor).compositeOver(gradeMinimum)
        val gradedFloorContrasts = listOf(panel.primaryContent, panel.secondaryContent, panel.iconContent).map {
            workspaceGlassContrastRatio(it, gradedFloorMaterial)
        }
        val ungradedRejectedMaterial = Color(0xFF05060B).copy(alpha = .58f).compositeOver(Color.White)
        val ungradedAdmittedMaterial = Color(0xFF05060B).copy(alpha = .62f).compositeOver(Color.White)
        val ungradedRejectedSecondary = workspaceGlassContrastRatio(panel.secondaryContent, ungradedRejectedMaterial)
        val ungradedAdmittedContrasts = listOf(panel.primaryContent, panel.secondaryContent, panel.iconContent).map {
            workspaceGlassContrastRatio(it, ungradedAdmittedMaterial)
        }
        assertEquals("The complete scene grade must quantize hostile white to its rendered value", 0xFFAAAAAA.toInt(), gradeMinimum.toArgb())
        assertEquals("The deliberate neutral floor's full-grade material must stay exact", 0xFF4A4B4E.toInt(), gradedFloorMaterial.toArgb())
        assertEquals(
            "The neutral floor's full-grade primary contrast must retain its independent value",
            7.98093,
            gradedFloorContrasts[0],
            .00001,
        )
        assertEquals(
            "The neutral floor's full-grade secondary contrast must retain its independent value",
            7.76360,
            gradedFloorContrasts[1],
            .00001,
        )
        assertEquals("The ungraded preceding candidate must stay exact", 0xFF6E6E71.toInt(), ungradedRejectedMaterial.toArgb())
        assertEquals("The ungraded admitted candidate must stay exact", 0xFF646568.toInt(), ungradedAdmittedMaterial.toArgb())
        assertEquals(
            "The ungraded preceding candidate must retain its independent secondary contrast",
            4.52385,
            ungradedRejectedSecondary,
            .00001,
        )
        assertTrue(
            "The deliberate full-grade floor must satisfy primary, secondary and icon floors; contrasts=$gradedFloorContrasts",
            gradedFloorContrasts[0] >= 4.8 && gradedFloorContrasts[1] >= 4.8 && gradedFloorContrasts[2] >= 3.2,
        )
        assertTrue(
            "The ungraded preceding candidate must fail the 4.8 body floor; contrast=$ungradedRejectedSecondary",
            ungradedRejectedSecondary < 4.8,
        )
        assertTrue(
            "The ungraded admitted candidate must satisfy all floors; contrasts=$ungradedAdmittedContrasts",
            ungradedAdmittedContrasts[0] >= 4.8 &&
                ungradedAdmittedContrasts[1] >= 4.8 &&
                ungradedAdmittedContrasts[2] >= 3.2,
        )
        val admittedIndex = WorkspaceGlassAdaptiveAlphaCandidates.indexOf(.62f)
        assertTrue(admittedIndex > 0)
        assertEquals(.58f, WorkspaceGlassAdaptiveAlphaCandidates[admittedIndex - 1], 0f)
        assertEquals("The ungraded white hotspot must select the first safe candidate", .62f, hotspotAlpha, 0f)
        assertEquals("The expanded bilinear neighbour must receive the same ungraded protection", .62f, bilinearNeighbourAlpha, 0f)
        assertEquals("The ungraded dark peer must remain at the shared neutral floor", WorkspaceGlassNeutralNightTintFloor, distantAlpha, 0f)
    }

    @Test
    fun derivativeKernelAndManagedBudgetStayWithinTheGlass5Contract() {
        assertEquals(2, WorkspaceGlassAlgorithmVersion)
        assertEquals(2, WorkspaceGlassBlurRadius)
        val target = workspaceGlassDerivativeSize(2560, 1600)
        assertEquals(506, target.width)
        assertEquals(316, target.height)
        assertTrue(workspaceGlassTransientBytes(target) <= 4L * 1_024 * 1_024)
    }

    @Test
    fun normalAndOutlinedRolesRemainSceneResponsiveWhileSelectedEdgesStayDistinct() {
        val transmissiveRoles = listOf(
            WorkspaceGlassRole.RAIL,
            WorkspaceGlassRole.UTILITY,
            WorkspaceGlassRole.CONTROL,
            WorkspaceGlassRole.PANEL,
            WorkspaceGlassRole.SELECTED_OUTLINE,
        )
        transmissiveRoles.forEach { role ->
            listOf(false, true).forEach { day ->
                val style = workspaceGlassRoleStyle(role, day)
                assertTrue(
                    "$role day=$day must remain genuinely transmissive",
                    style.tint.alpha < .90f,
                )
                val overDark = style.tint.compositeOver(Color.Black)
                val overLight = style.tint.compositeOver(Color.White)
                assertTrue(
                    "$role day=$day must visibly respond to scene luminance",
                    kotlin.math.abs(overLight.luminance() - overDark.luminance()) >= .015f,
                )
            }
        }
        listOf(false, true).forEach { day ->
            val style = workspaceGlassRoleStyle(WorkspaceGlassRole.SELECTED_OUTLINE, day)
            val hostileScene = if (day) Color.Black else Color.White
            val material = style.tint.compositeOver(hostileScene)
            val renderedStroke = style.stroke.compositeOver(material)
            assertTrue(
                "outlined selection day=$day alpha-composited perimeter",
                contrast(renderedStroke, material) >= 3f,
            )
        }
    }

    @Test
    fun homeShortcutAccentIconsRemainDistinctOnTransmissivePanels() {
        val nightStyle = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = false)
        val dayPanel = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = true)
            .tint.compositeOver(Color.Black)

        sceneScrimExtrema.forEach { scrim ->
            val nightPanel = completeNightMaterial(
                WorkspaceGlassRole.PANEL,
                nightStyle,
                scrim,
                admittedAlpha(Color.White, WorkspaceGlassRole.PANEL, nightStyle),
            )
            val renderedContrast = contrast(ExecutiveHomeFinish.Lavender, nightPanel)
            assertTrue(
                "Night shortcut Lavender must retain 3:1 after full panel composition: " +
                    "scrim=$scrim panel=$nightPanel contrast=$renderedContrast",
                renderedContrast >= 3f,
            )
        }
        assertTrue(contrast(Color(0xFF5F4C9B), dayPanel) >= 3f)
    }

    private fun completeNightMaterial(
        role: WorkspaceGlassRole,
        style: WorkspaceGlassRoleStyle,
        scrimAlpha: Float,
        tintAlpha: Float = style.tint.alpha,
    ): Color {
        val grade = workspaceGlassSceneGradeStrength(role, day = false)
        val gradedScene = Color.Black.copy(alpha = scrimAlpha * grade).compositeOver(Color.White)
        val overlaidScene = Color.Black.copy(alpha = .10f * grade).compositeOver(gradedScene)
        return Color(0xFF05060B).copy(alpha = tintAlpha).compositeOver(overlaidScene)
    }

    private fun admittedAlpha(scene: Color, role: WorkspaceGlassRole, style: WorkspaceGlassRoleStyle): Float =
        requireNotNull(
            workspaceGlassAdmittedTintAlpha(
                uniformGrid(scene), derivativeKey(), Rect(0f, 0f, 1280f, 800f), 1280, 800, role, style,
            ),
        )

    private fun derivativeKey(epoch: Long = 1L): WorkspaceGlassDerivativeKey = WorkspaceGlassDerivativeKey(
        actualKey = WorkspaceBackgroundRenderKey(
            WorkspaceBackgroundSlot.WORKSPACE,
            WorkspaceBackgroundSource.BUNDLED,
            1L,
            "policy-scene",
            1280,
            800,
            1,
        ),
        frameEpoch = epoch,
        algorithmVersion = WorkspaceGlassAlgorithmVersion,
        width = 512,
        height = 320,
        blurRadius = WorkspaceGlassBlurRadius,
    )

    private fun uniformGrid(color: Color): WorkspaceGlassSafetyGrid = WorkspaceGlassSafetyGrid(
        WorkspaceGlassSafetyGridColumns,
        WorkspaceGlassSafetyGridRows,
        IntArray(WorkspaceGlassSafetyGridColumns * WorkspaceGlassSafetyGridRows) { color.toArgb() },
    )

    private fun contrast(first: Color, second: Color): Float {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + .05f) / (dark + .05f)
    }

    private fun renderedColorDistance(first: Color, second: Color): Float =
        kotlin.math.abs(first.red - second.red) +
            kotlin.math.abs(first.green - second.green) +
            kotlin.math.abs(first.blue - second.blue)
}

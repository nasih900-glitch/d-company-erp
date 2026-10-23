package cloud.dcompany.erp.ui.screens

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val EntryDisplayIdArgument = "dcompany.testDisplayId"
private const val SourceInventoryArgument = "dcompany.sourceInventorySha256"
private const val TargetApkArgument = "dcompany.targetApkSha256"
private const val TestApkArgument = "dcompany.testApkSha256"

private val RequestedEntryDisplayId: Int? by lazy {
    val raw = InstrumentationRegistry.getArguments().getString(EntryDisplayIdArgument)
    if (raw == null) {
        null
    } else {
        val requested = raw.toIntOrNull()
            ?: error("Invalid -e $EntryDisplayIdArgument value: $raw")
        require(requested == 2) {
            "Only the verified HD display 2 is supported; requested display was $requested"
        }
        requested
    }
}

private fun <A : ComponentActivity> ActivityScenarioRule<A>.entryActivity(): A {
    var current: A? = null
    scenario.onActivity { current = it }
    return checkNotNull(current) { "ActivityScenario has no resumed activity" }
}

private fun createEntryComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val activityRule = if (RequestedEntryDisplayId == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(RequestedEntryDisplayId!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(activityRule) { it.entryActivity() }
}

private data class EntryHostProof(
    val requestedDisplayId: Int?,
    val observedDisplayId: Int,
    val modeWidthPx: Int,
    val modeHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val windowBounds: Rect,
    val decorWidthPx: Int,
    val decorHeightPx: Int,
)

private data class PixelContrastProof(
    val peak: Double,
    val pixelsMeetingThreshold: Int,
)

@RunWith(AndroidJUnit4::class)
class ExecutiveEntryPresentationUiTest {
    @get:Rule
    val compose = createEntryComposeRule()

    @Test
    fun splashUsesOriginalMarkSafePhotoAndHonestInitializationCopy() {
        render(widthDp = 1_280, heightDp = 800) {
            ExecutiveEntrySplash()
        }
        captureRoot(
            name = "entry-splash-1280x800",
            testName = "splashUsesOriginalMarkSafePhotoAndHonestInitializationCopy",
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.0f,
            photoExpected = true,
        )

        compose.onNodeWithTag("executive-entry-splash").assertIsDisplayed()
        compose.onNodeWithTag("entry-lounge-photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("D Company logo").assertIsDisplayed()
        compose.onNodeWithText("Preparing your workspace").assertIsDisplayed()
    }

    @Test
    fun landscapeLoginMatchesReferenceSplitBrandAndIllustratedArtwork() {
        renderLogin(widthDp = 1_280, heightDp = 800)
        captureRoot(
            name = "entry-login-landscape-1280x800",
            testName = "landscapeLoginMatchesReferenceSplitBrandAndIllustratedArtwork",
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.0f,
            photoExpected = true,
        )

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val formZone = compose.onNodeWithTag("login-form-zone").getUnclippedBoundsInRoot()
        val photoZone = compose.onNodeWithTag("login-photo-zone").getUnclippedBoundsInRoot()
        val form = compose.onNodeWithTag("login-form").getUnclippedBoundsInRoot()
        val brand = compose.onNodeWithTag("login-brand-panel").getUnclippedBoundsInRoot()
        val formZoneWidth = formZone.right - formZone.left
        val rootWidth = root.right - root.left
        val formWidth = form.right - form.left
        val formFraction = formZoneWidth / rootWidth
        assertTrue("Form zone must occupy 38–42%: $formFraction", formFraction in 0.38f..0.42f)
        assertTrue("Form width must stay within 360–400dp: $formWidth", formWidth in 360.dp..400.dp)
        assertTrue("Photo must begin after the form zone", photoZone.left >= formZone.right)
        val brandCenterOffset = abs(
            ((brand.left.value + brand.right.value) / 2f) -
                ((formZone.left.value + formZone.right.value) / 2f),
        )
        assertTrue("Brand must stay centered in the form panel: $brandCenterOffset", brandCenterOffset <= 1f)
        assertTrue("Brand must begin below the top reference breathing room", brand.top >= 80.dp)
        compose.onNodeWithTag("login-version").assertIsDisplayed()
        assertTextDoesNotOverflow("v${BuildConfig.VERSION_NAME}", scrollIntoView = false)
        compose.onNodeWithContentDescription("D Company illustrated login artwork").assertIsDisplayed()
        assertEssentialTargets()
    }

    @Test
    fun compactFontScalePrioritizesActionsWithoutClippingOrDecorativeArtwork() {
        renderLogin(widthDp = 600, heightDp = 800, fontScale = 1.3f)
        captureRoot(
            name = "entry-login-portrait-600x800-font1_3",
            testName = "compactFontScalePrioritizesActionsWithoutClippingOrDecorativeArtwork",
            widthDp = 600,
            heightDp = 800,
            fontScale = 1.3f,
            photoExpected = false,
        )

        compose.onNodeWithTag("login-layout-compact").assertIsDisplayed()
        compose.onNodeWithTag("login-layout-wide").assertDoesNotExist()
        compose.onNodeWithTag("login-photo").assertDoesNotExist()
        compose.onNodeWithTag("login-photo-zone").assertDoesNotExist()
        compose.onNodeWithTag("login-version").assertDoesNotExist()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
        assertEssentialTargets()
        listOf("D COMPANY", "GAMING LOUNGE", "Forgot password?", "Login")
            .forEach(::assertTextDoesNotOverflow)
    }

    @Test
    fun reducedHeightBusyErrorKeepsStatusAndDisabledActionsReachable() {
        val signingIn = mutableStateOf(false)
        val error = "This account is temporarily locked. Wait, then try again or ask an owner."
        render(widthDp = 600, heightDp = 360, fontScale = 1.3f) {
            LoginWorkspace(
                signingIn = signingIn.value,
                error = error,
                onSignIn = { _, _ -> },
                appearancePreference = AppearancePreference.Night,
                onAppearancePreferenceChanged = {},
                onRecover = {},
            )
        }

        compose.onNodeWithTag("login-photo").assertDoesNotExist()
        compose.onNodeWithTag("login-email").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("login-recover").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        assertEssentialTargets()

        val stableTags = listOf(
            "login-form",
            "login-email",
            "login-password",
            "login-recover",
            "login-notice",
            "login-submit",
        )
        val idleGeometry = controlGeometry(stableTags)
        compose.runOnUiThread { signingIn.value = true }
        compose.waitForIdle()
        val busyGeometry = controlGeometry(stableTags)
        assertEquals(
            "Idle and busy states must keep form/control geometry stable",
            idleGeometry,
            busyGeometry,
        )

        assertEssentialTargets()
        compose.onNodeWithTag("login-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        captureRoot(
            name = "entry-login-reduced-600x360-font1_3-busy-error",
            testName = "reducedHeightBusyErrorKeepsStatusAndDisabledActionsReachable",
            widthDp = 600,
            heightDp = 360,
            fontScale = 1.3f,
            photoExpected = false,
        )
        compose.onNodeWithTag("login-email").assertIsNotEnabled()
        compose.onNodeWithTag("login-recover").assertIsNotEnabled()
        listOf("D COMPANY", "GAMING LOUNGE", "Signing in…", error)
            .forEach(::assertTextDoesNotOverflow)
    }

    @Test
    fun busyActionMeetsResolvedContrastInNightAndDay() {
        val appearance = mutableStateOf(AppearancePreference.Night)
        assertHost()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DCompanyTheme(appearance.value) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(600.dp).height(360.dp)) {
                            LoginWorkspace(
                                signingIn = true,
                                error = null,
                                onSignIn = { _, _ -> },
                                appearancePreference = appearance.value,
                                onAppearancePreferenceChanged = {},
                                onRecover = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        listOf(AppearancePreference.Night, AppearancePreference.Day).forEach { preference ->
            if (appearance.value != preference) {
                compose.runOnUiThread { appearance.value = preference }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("login-submit")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        "Signing in",
                    ),
                )
            captureRoot(
                name = "entry-login-busy-${preference.name.lowercase()}-600x360-font1_3",
                testName = "busyActionMeetsResolvedContrastInNightAndDay",
                widthDp = 600,
                heightDp = 360,
                fontScale = 1.3f,
                photoExpected = false,
            )
            assertBusyButtonContrast(preference)
        }
    }

    private fun renderLogin(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1.0f,
        signingIn: Boolean = false,
        error: String? = null,
    ) {
        render(widthDp, heightDp, fontScale) {
            LoginWorkspace(
                signingIn = signingIn,
                error = error,
                onSignIn = { _, _ -> },
                appearancePreference = AppearancePreference.Night,
                onAppearancePreferenceChanged = {},
                onRecover = {},
            )
        }
    }

    private fun render(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1.0f,
        content: @Composable () -> Unit,
    ) {
        assertHost()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(widthDp.dp).height(heightDp.dp)) { content() }
                    }
                }
            }
        }
    }

    private fun assertEssentialTargets() {
        listOf("login-email", "login-password", "login-recover", "login-submit", "login-appearance")
            .forEach { tag ->
                compose.onNodeWithTag(tag)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
                assertFullyInsideRoot(tag)
            }
    }

    private fun assertFullyInsideRoot(tag: String) {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val control = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue(
            "$tag must stay inside the rendered root: control=$control root=$root",
            control.left >= root.left && control.top >= root.top &&
                control.right <= root.right && control.bottom <= root.bottom,
        )
    }

    private fun controlGeometry(tags: List<String>): Map<String, List<Float>> =
        tags.associateWith { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            listOf(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
        }

    private fun assertBusyButtonContrast(preference: AppearancePreference) {
        val buttonBitmap = compose.onNodeWithTag("login-submit").captureToImage().asAndroidBitmap()
        val indicator = compose.onNodeWithTag("login-progress", useUnmergedTree = true)
        val indicatorBounds = indicator.getUnclippedBoundsInRoot()
        val indicatorWidthDp = (indicatorBounds.right - indicatorBounds.left).value
        val indicatorHeightDp = (indicatorBounds.bottom - indicatorBounds.top).value
        val directory = outputDirectory()
        val preferenceName = preference.name.lowercase()
        val buttonStem = "entry-login-busy-button-$preferenceName-font1_3"
        val buttonImage = directory.resolve("$buttonStem.png")
        val background: Int
        val textContrast: PixelContrastProof
        try {
            background = dominantOpaqueColor(buttonBitmap)
            textContrast = contrastProof(
                bitmap = buttonBitmap,
                background = background,
                left = (buttonBitmap.width * 0.05f).roundToInt(),
                right = (buttonBitmap.width * 0.70f).roundToInt(),
                threshold = 4.5,
            )
            buttonImage.outputStream().use { output ->
                check(buttonBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            buttonBitmap.recycle()
        }

        val indicatorBitmap = indicator.captureToImage().asAndroidBitmap()
        val indicatorBackground: Int
        val indicatorContrast: PixelContrastProof
        val indicatorStem = "entry-login-busy-indicator-$preferenceName-font1_3"
        val indicatorImage = directory.resolve("$indicatorStem.png")
        try {
            indicatorBackground = dominantOpaqueColor(indicatorBitmap)
            indicatorContrast = contrastProof(
                bitmap = indicatorBitmap,
                background = indicatorBackground,
                left = 0,
                right = indicatorBitmap.width,
                threshold = 3.0,
                top = 0,
                bottom = indicatorBitmap.height,
            )
            indicatorImage.outputStream().use { output ->
                check(indicatorBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            indicatorBitmap.recycle()
        }

        directory.resolve("$buttonStem.contrast.json").writeText(
            """
            {
              "phase": "C1-repair-6",
              "test_class": "cloud.dcompany.erp.ui.screens.ExecutiveEntryPresentationUiTest",
              "test_name": "busyActionMeetsResolvedContrastInNightAndDay",
              "source_inventory_sha256": ${jsonString(evidencePinOrNull(SourceInventoryArgument))},
              "target_apk_sha256": ${jsonString(evidencePinOrNull(TargetApkArgument))},
              "test_apk_sha256": ${jsonString(evidencePinOrNull(TestApkArgument))},
              "appearance": "${preference.name}",
              "button_png": "${buttonImage.name}",
              "button_png_sha256": "${sha256(buttonImage)}",
              "background_argb": "${argbHex(background)}",
              "resolved_text_peak_contrast": ${formatContrast(textContrast.peak)},
              "resolved_text_pixels_at_4_5": ${textContrast.pixelsMeetingThreshold},
              "required_text_contrast": 4.5,
              "required_text_pixels_at_4_5": 8,
              "indicator_png": "${indicatorImage.name}",
              "indicator_png_sha256": "${sha256(indicatorImage)}",
              "indicator_background_argb": "${argbHex(indicatorBackground)}",
              "indicator_width_dp": $indicatorWidthDp,
              "indicator_height_dp": $indicatorHeightDp,
              "resolved_indicator_peak_contrast": ${formatContrast(indicatorContrast.peak)},
              "resolved_indicator_pixels_at_3_0": ${indicatorContrast.pixelsMeetingThreshold},
              "required_indicator_contrast": 3.0,
              "required_indicator_pixels_at_3_0": "nonempty"
            }
            """.trimIndent() + "\n",
        )

        assertEquals("Busy indicator width must stay 20dp", 20f, indicatorWidthDp, 0.5f)
        assertEquals("Busy indicator height must stay 20dp", 20f, indicatorHeightDp, 0.5f)
        assertTrue(
            "$preference busy label contrast must be at least 4.5:1; actual=${textContrast.peak}",
            textContrast.peak >= 4.5,
        )
        assertTrue(
            "$preference busy label must contain resolved core pixels at 4.5:1; " +
                "count=${textContrast.pixelsMeetingThreshold}",
            textContrast.pixelsMeetingThreshold >= 8,
        )
        assertTrue(
            "$preference busy indicator must be at least 3.0:1; actual=${indicatorContrast.peak}",
            indicatorContrast.peak >= 3.0,
        )
        assertTrue(
            "$preference busy indicator must contain resolved stroke pixels at 3.0:1; " +
                "count=${indicatorContrast.pixelsMeetingThreshold}",
            indicatorContrast.pixelsMeetingThreshold > 0,
        )
    }

    private fun dominantOpaqueColor(bitmap: Bitmap): Int {
        val counts = mutableMapOf<Int, Int>()
        val top = (bitmap.height * 0.2f).roundToInt()
        val bottom = (bitmap.height * 0.8f).roundToInt()
        for (y in top until bottom) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if ((color ushr 24) == 0xff) counts[color] = (counts[color] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
            ?: error("Busy button capture has no opaque pixels")
    }

    private fun contrastProof(
        bitmap: Bitmap,
        background: Int,
        left: Int,
        right: Int,
        threshold: Double,
        top: Int = (bitmap.height * 0.2f).roundToInt(),
        bottom: Int = (bitmap.height * 0.8f).roundToInt(),
    ): PixelContrastProof {
        var peak = 1.0
        var pixelsMeetingThreshold = 0
        val safeLeft = max(0, left)
        val safeRight = min(bitmap.width, right)
        val safeTop = max(0, top)
        val safeBottom = min(bitmap.height, bottom)
        for (y in safeTop until safeBottom) {
            for (x in safeLeft until safeRight) {
                val color = bitmap.getPixel(x, y)
                if ((color ushr 24) == 0xff) {
                    val contrast = contrastRatio(color, background)
                    peak = max(peak, contrast)
                    if (contrast >= threshold) {
                        pixelsMeetingThreshold += 1
                    }
                }
            }
        }
        return PixelContrastProof(
            peak = peak,
            pixelsMeetingThreshold = pixelsMeetingThreshold,
        )
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = max(firstLuminance, secondLuminance)
        val darker = min(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((argb ushr shift) and 0xff) / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun argbHex(color: Int): String = "#%08X".format(Locale.US, color)

    private fun formatContrast(value: Double): String = "%.3f".format(Locale.US, value)

    private fun assertTextDoesNotOverflow(text: String, scrollIntoView: Boolean = true) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        if (scrollIntoView) node.performScrollTo()
        node.assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$text must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertFalse(
            "$text must not overflow: size=${layout.size} width=${layout.didOverflowWidth} " +
                "height=${layout.didOverflowHeight}",
            layout.hasVisualOverflow,
        )
    }

    private fun assertHost(): EntryHostProof {
        val activity = compose.activity
        var proof: EntryHostProof? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val display = checkNotNull(activity.display) { "Entry test host has no display" }
            val metrics = activity.resources.displayMetrics
            proof = EntryHostProof(
                requestedDisplayId = RequestedEntryDisplayId,
                observedDisplayId = display.displayId,
                modeWidthPx = display.mode.physicalWidth,
                modeHeightPx = display.mode.physicalHeight,
                densityDpi = metrics.densityDpi,
                density = metrics.density,
                windowBounds = Rect(activity.windowManager.currentWindowMetrics.bounds),
                decorWidthPx = activity.window.decorView.width,
                decorHeightPx = activity.window.decorView.height,
            )
        }
        return checkNotNull(proof).also { host ->
            if (host.requestedDisplayId == null) {
                assertEquals("Default entry host must use display 0", 0, host.observedDisplayId)
            } else {
                assertEquals("HD entry host must use display 2", 2, host.observedDisplayId)
                assertEquals(2_560, host.modeWidthPx)
                assertEquals(1_600, host.modeHeightPx)
                assertEquals(320, host.densityDpi)
                assertEquals(2_560, host.windowBounds.width())
                assertEquals(1_600, host.windowBounds.height())
                assertEquals(2_560, host.decorWidthPx)
                assertEquals(1_600, host.decorHeightPx)
                evidencePin(SourceInventoryArgument)
                evidencePin(TargetApkArgument)
                evidencePin(TestApkArgument)
            }
            assertTrue(host.windowBounds.width() > 0 && host.windowBounds.height() > 0)
            assertTrue(host.windowBounds.width() <= host.modeWidthPx)
            assertTrue(host.windowBounds.height() <= host.modeHeightPx)
            assertTrue(host.decorWidthPx > 0 && host.decorHeightPx > 0)
        }
    }

    private fun captureRoot(
        name: String,
        testName: String,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        photoExpected: Boolean,
    ) {
        compose.waitForIdle()
        val host = assertHost()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertEquals((widthDp * host.density).roundToInt(), bitmap.width)
            assertEquals((heightDp * host.density).roundToInt(), bitmap.height)
            assertTrue(bitmap.width <= host.windowBounds.width())
            assertTrue(bitmap.height <= host.windowBounds.height())
            val directory = outputDirectory()
            val image = directory.resolve("$name.png")
            image.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val requested = host.requestedDisplayId?.toString() ?: "null"
            val splashExpected = photoExpected && name.startsWith("entry-splash")
            val loginArtworkExpected = photoExpected && !splashExpected
            val loungeCropJson = if (splashExpected) {
                val bounds = compose.onNodeWithTag("entry-lounge-photo", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                val crop = authorizedLoungeSourceCrop(
                    destinationWidthPx = ((bounds.right - bounds.left).value * host.density).roundToInt(),
                    destinationHeightPx = ((bounds.bottom - bounds.top).value * host.density).roundToInt(),
                )
                "{\"left\":${crop.left},\"top\":${crop.top},\"right\":${crop.right}," +
                    "\"bottom\":${crop.bottom}}"
            } else {
                "null"
            }
            val loginArtworkCropJson = if (loginArtworkExpected) {
                val bounds = compose.onNodeWithTag("login-photo", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                val crop = if (name.contains("portrait")) {
                    LoginReferenceArtworkBounds
                } else {
                    loginReferenceArtworkSourceCrop(
                        destinationWidthPx = ((bounds.right - bounds.left).value * host.density).roundToInt(),
                        destinationHeightPx = ((bounds.bottom - bounds.top).value * host.density).roundToInt(),
                    )
                }
                "{\"left\":${crop.left},\"top\":${crop.top},\"right\":${crop.right}," +
                    "\"bottom\":${crop.bottom}}"
            } else {
                "null"
            }
            directory.resolve("$name.provenance.json").writeText(
                """
                {
                  "phase": "C1",
                  "capture_api": "ComposeTestRule.onRoot().captureToImage()",
                  "test_class": "cloud.dcompany.erp.ui.screens.ExecutiveEntryPresentationUiTest",
                  "test_name": "$testName",
                  "test_source": "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ExecutiveEntryPresentationUiTest.kt",
                  "base_commit": "$BaseCommit",
                  "source_inventory_sha256": ${jsonString(evidencePinOrNull(SourceInventoryArgument))},
                  "target_apk_sha256": ${jsonString(evidencePinOrNull(TargetApkArgument))},
                  "test_apk_sha256": ${jsonString(evidencePinOrNull(TestApkArgument))},
                  "application_id": "${BuildConfig.APPLICATION_ID}",
                  "device": "${Build.MANUFACTURER} ${Build.MODEL}",
                  "android_api": ${Build.VERSION.SDK_INT},
                  "authorized_lounge_sha256": "$AuthorizedLoungeAssetSha256",
                  "authorized_lounge_source_crop": $loungeCropJson,
                  "login_reference_sha256": "$LoginReferenceAssetSha256",
                  "login_reference_artwork_source_crop": $loginArtworkCropJson,
                  "requested_display_id": $requested,
                  "observed_display_id": ${host.observedDisplayId},
                  "display_mode_width_px": ${host.modeWidthPx},
                  "display_mode_height_px": ${host.modeHeightPx},
                  "activity_density_dpi": ${host.densityDpi},
                  "activity_density": ${host.density},
                  "window_bounds": "${host.windowBounds.flattenToString()}",
                  "window_width_px": ${host.windowBounds.width()},
                  "window_height_px": ${host.windowBounds.height()},
                  "decor_width_px": ${host.decorWidthPx},
                  "decor_height_px": ${host.decorHeightPx},
                  "expected_width_dp": $widthDp,
                  "expected_height_dp": $heightDp,
                  "font_scale": $fontScale,
                  "actual_width_px": ${bitmap.width},
                  "actual_height_px": ${bitmap.height},
                  "png_file": "${image.name}",
                  "png_bytes": ${image.length()},
                  "png_sha256": "${sha256(image)}"
                }
                """.trimIndent() + "\n",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun outputDirectory(): File {
        val profile = if (RequestedEntryDisplayId == null) "default-display" else "display-2-hd"
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-c1-login-reference-r2-screenshots-v1")
            .resolve(profile)
            .resolve(CaptureRunId)
            .apply { check(isDirectory || mkdirs()) }
    }

    private fun evidencePin(key: String): String = checkNotNull(evidencePinOrNull(key)) {
        "Missing required -e $key <sha256> for display-2 evidence"
    }

    private fun evidencePinOrNull(key: String): String? =
        InstrumentationRegistry.getArguments().getString(key)?.also { value ->
            require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid $key SHA-256: $value" }
        }

    private fun jsonString(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val BaseCommit = "8a5039fdefcaa4aed24335546134c7a77ff88ef6"
        val CaptureRunId = "${System.currentTimeMillis()}-${android.os.Process.myPid()}"
    }
}

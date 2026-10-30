package cloud.dcompany.erp.ui.screens

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.AuthenticatedWorkspaceHandoff
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.AuthState
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.workspaceGlassRoleStyle
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
        require(requested in setOf(0, 2, 5, 6)) {
            "Only verified HD displays 0, 2, 5 and 6 are supported; requested display was $requested"
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

private data class AuxiliaryContrastTarget(
    val tag: String,
    val evidenceName: String,
    val threshold: Double,
    val minimumPixels: Int,
)

@RunWith(AndroidJUnit4::class)
class ExecutiveEntryPresentationUiTest {
    @get:Rule
    val compose = createEntryComposeRule()

    @Test
    fun freshLoginSuccessBlocksWorkspaceUntilExactContinueAndKeepsAccessibleTargets() {
        val signedIn = mutableStateOf(
            AuthState.SignedIn(
                me = MeResponse(
                    userId = "connected-user",
                    email = "connected@example.test",
                    name = "Connected Employee",
                    companyId = "connected-company",
                    branchId = "connected-branch",
                    accessibleModules = listOf("gaming"),
                ),
                freshLoginNonce = 41L,
            ),
        )
        val acknowledgements = mutableListOf<Long>()
        render(widthDp = 1_280, heightDp = 800) {
            AuthenticatedWorkspaceHandoff(
                signedIn = signedIn.value,
                backgroundState = WorkspaceBackgroundUiState(),
                onContinue = { acknowledged ->
                    acknowledgements += acknowledged
                    val accepted = acknowledged == signedIn.value.freshLoginNonce
                    if (accepted) {
                        signedIn.value = signedIn.value.copy(freshLoginNonce = null)
                    }
                    accepted
                },
            ) {
                Box(Modifier.fillMaxSize().testTag("workspace-after-fresh-login"))
            }
        }

        compose.onNodeWithText("Login successful").assertIsDisplayed()
        compose.onNodeWithText("Welcome, Connected Employee").assertIsDisplayed()
        compose.onNodeWithTag("workspace-after-fresh-login").assertDoesNotExist()
        captureRoot(
            name = "entry-fresh-login-1280x800",
            testName = "freshLoginSuccessBlocksWorkspaceUntilExactContinueAndKeepsAccessibleTargets",
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.0f,
            photoExpected = true,
        )
        compose.onNodeWithTag("fresh-login-continue")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { acknowledgements == listOf(41L) }
        compose.onNodeWithTag("workspace-after-fresh-login").assertIsDisplayed()
        assertEquals(listOf(41L), acknowledgements)
    }

    @Test
    fun freshLoginSwipeRejectsShortHorizontalAndDownGesturesThenAcceptsUpwardGesture() {
        val acknowledgements = mutableListOf<Long>()
        render(widthDp = 1_280, heightDp = 800) {
            FreshLoginSuccessScreen(
                nonce = 72L,
                employeeName = "Gesture Employee",
                backgroundState = WorkspaceBackgroundUiState(),
                onContinue = {
                    acknowledgements.add(it)
                    true
                },
            )
        }
        val swipeOwner = compose.onNodeWithTag("fresh-login-swipe-owner")

        swipeOwner.performTouchInput {
            swipe(center, Offset(center.x, center.y - 24f), durationMillis = 250)
        }
        compose.waitForIdle()
        assertTrue("A short swipe must not continue", acknowledgements.isEmpty())

        swipeOwner.performTouchInput {
            swipe(center, Offset(center.x + 180f, center.y), durationMillis = 250)
        }
        compose.waitForIdle()
        assertTrue("A horizontal swipe must not continue", acknowledgements.isEmpty())

        swipeOwner.performTouchInput {
            swipe(center, Offset(center.x, center.y + 80f), durationMillis = 250)
        }
        compose.waitForIdle()
        assertTrue("A downward swipe must not continue", acknowledgements.isEmpty())

        swipeOwner.performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - 320f), delayMillis = 240)
            cancel()
        }
        compose.waitForIdle()
        assertTrue("A cancelled swipe past the threshold must not continue", acknowledgements.isEmpty())

        swipeOwner.performTouchInput {
            swipe(center, Offset(center.x, center.y - 360f), durationMillis = 350)
        }
        compose.waitUntil(timeoutMillis = 5_000) { acknowledgements == listOf(72L) }
        assertEquals(listOf(72L), acknowledgements)
    }

    @Test
    fun rejectedScopeOrStaleNonceAcknowledgementRestoresInteractivePrompt() {
        var currentNonce = 99L
        var scopeValid = true
        val attempts = mutableListOf<Long>()
        render(widthDp = 1_280, heightDp = 800) {
            FreshLoginSuccessScreen(
                nonce = 99L,
                employeeName = "Scope Employee",
                backgroundState = WorkspaceBackgroundUiState(),
                onContinue = { requested ->
                    attempts += requested
                    scopeValid && requested == currentNonce
                },
            )
        }

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("fresh-login-continue").performClick()
            compose.mainClock.advanceTimeBy(130)
            scopeValid = false
            compose.mainClock.advanceTimeBy(600)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals(listOf(99L), attempts)
        compose.onNodeWithTag("fresh-login-continue").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("fresh-login-stage").assertIsDisplayed()

        scopeValid = true
        currentNonce = 100L
        compose.onNodeWithTag("fresh-login-continue").performClick()
        compose.waitForIdle()
        assertEquals(listOf(99L, 99L), attempts)
        compose.onNodeWithTag("fresh-login-continue").assertIsDisplayed().assertIsEnabled()

        currentNonce = 99L
        compose.onNodeWithTag("fresh-login-continue").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { attempts == listOf(99L, 99L, 99L) }
    }

    @Test
    fun compactLargeTextSuccessKeepsCopyAndContinueFullyReachable() {
        var acknowledgements = 0
        render(widthDp = 600, heightDp = 500, fontScale = 1.3f) {
            FreshLoginSuccessScreen(
                nonce = 83L,
                employeeName = "Large Text Employee",
                backgroundState = WorkspaceBackgroundUiState(),
                onContinue = {
                    acknowledgements++
                    true
                },
            )
        }

        listOf(
            "fresh-login-brand-mark",
            "fresh-login-title",
            "fresh-login-employee",
            "fresh-login-instruction",
            "fresh-login-up-arrow",
            "fresh-login-continue",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertFullyInsideRoot(tag)
        }
        compose.onNodeWithTag("fresh-login-continue").assertHeightIsAtLeast(48.dp)
        captureRoot(
            name = "entry-fresh-login-600x500-font1_3",
            testName = "compactLargeTextSuccessKeepsCopyAndContinueFullyReachable",
            widthDp = 600,
            heightDp = 500,
            fontScale = 1.3f,
            photoExpected = true,
        )
        listOf(
            "Login successful",
            "Welcome, Large Text Employee",
            "Swipe up to open your workspace",
        ).forEach { text -> assertTextDoesNotOverflow(text, scrollIntoView = false) }
    }

    @Test
    fun freshLoginReadySceneUsesRoleBoundGlassAndContrastInNightAndDay() {
        val appearance = mutableStateOf(AppearancePreference.Night)
        assertHost()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.3f),
            ) {
                DCompanyTheme(appearance.value) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(600.dp).height(500.dp)) {
                            FreshLoginSuccessScreen(
                                nonce = 87L,
                                employeeName = "Contrast Employee",
                                backgroundState = WorkspaceBackgroundUiState(),
                                onContinue = { true },
                            )
                        }
                    }
                }
            }
        }
        listOf(AppearancePreference.Night, AppearancePreference.Day).forEach { preference ->
            if (appearance.value != preference) {
                compose.runOnUiThread { appearance.value = preference }
            }
            compose.waitForIdle()
            awaitTag("fresh-login-background-ready-workspace")
            awaitTag("fresh-login-glass-ready-workspace")

            val day = preference == AppearancePreference.Day
            val panel = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day)
            val prompt = workspaceGlassRoleStyle(WorkspaceGlassRole.CONTROL, day)
            assertEquals(panel.primaryContent.toArgb(), textColor("fresh-login-title"))
            assertEquals(panel.secondaryContent.toArgb(), textColor("fresh-login-employee"))
            assertEquals(prompt.iconContent.toArgb(), textColor("fresh-login-up-arrow"))
            assertEquals(prompt.secondaryContent.toArgb(), textColor("fresh-login-instruction"))

            listOf(
                "fresh-login-success-card",
                "fresh-login-swipe-owner",
                "fresh-login-title",
                "fresh-login-employee",
                "fresh-login-up-arrow",
                "fresh-login-instruction",
                "fresh-login-continue",
            ).forEach { tag ->
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                assertFullyInsideRoot(tag)
            }
            compose.onNodeWithTag("fresh-login-continue").assertHeightIsAtLeast(48.dp)
            assertTaggedForegroundContrast("fresh-login-title", 4.8, 8, preference)
            assertTaggedForegroundContrast("fresh-login-employee", 4.8, 8, preference)
            assertTaggedForegroundContrast("fresh-login-instruction", 4.8, 8, preference)
            assertTaggedForegroundContrast("fresh-login-up-arrow", 3.2, 1, preference)
            listOf(
                "Login successful",
                "Welcome, Contrast Employee",
                "Swipe up to open your workspace",
            ).forEach { text -> assertTextDoesNotOverflow(text, scrollIntoView = false) }
        }
    }

    @Test
    fun freshLoginBackUsesTheSameBoundedCosmeticContinuation() {
        val acknowledgements = mutableListOf<Long>()
        render(widthDp = 1_280, heightDp = 800) {
            FreshLoginSuccessScreen(
                nonce = 94L,
                employeeName = "Back Employee",
                backgroundState = WorkspaceBackgroundUiState(),
                onContinue = {
                    acknowledgements.add(it)
                    true
                },
            )
        }

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(timeoutMillis = 5_000) { acknowledgements == listOf(94L) }
        assertEquals(listOf(94L), acknowledgements)
    }

    @Test
    fun splashUsesSelectedSceneOriginalMarkAndHonestInitializationCopy() {
        render(widthDp = 1_280, heightDp = 800) {
            ExecutiveEntrySplash()
        }
        captureRoot(
            name = "entry-splash-1280x800",
            testName = "splashUsesSelectedSceneOriginalMarkAndHonestInitializationCopy",
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.0f,
            photoExpected = true,
        )

        compose.onNodeWithTag("executive-entry-splash").assertIsDisplayed()
        compose.onNodeWithTag("entry-splash-scene").assertIsDisplayed()
        compose.onNodeWithContentDescription("D Company logo").assertIsDisplayed()
        compose.onNodeWithText("Preparing your workspace").assertIsDisplayed()
        assertSplashGeometryAndText()
    }

    @Test
    fun compactLargeTextSplashKeepsBrandAndCachedVerificationCopySeparate() {
        val availableHeight = mutableStateOf(800)
        val statusText = mutableStateOf("Preparing your workspace")
        val status = "Restoring Avery's workspace safely"
        val fixedBrandTags = listOf(
            "entry-splash-brand-mark",
            "entry-splash-brand-name",
            "entry-splash-brand-tagline",
        )
        assertHost()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(600.dp).height(availableHeight.value.dp)) {
                            ExecutiveEntrySplash(statusText = statusText.value)
                        }
                    }
                }
            }
        }
        captureRoot(
            name = "entry-splash-portrait-600x800-font1_3",
            testName = "compactLargeTextSplashKeepsBrandAndCachedVerificationCopySeparate",
            widthDp = 600,
            heightDp = 800,
            fontScale = 1.3f,
            photoExpected = true,
        )
        assertSplashGeometryAndText()
        val loadingBrandGeometry = controlGeometry(fixedBrandTags)
        compose.runOnUiThread { statusText.value = status }
        compose.waitForIdle()
        assertEquals(
            "Loading and cached verification must retain identical Splash brand geometry",
            loadingBrandGeometry,
            controlGeometry(fixedBrandTags),
        )
        captureRoot(
            name = "entry-splash-cached-600x800-font1_3",
            testName = "compactLargeTextSplashKeepsBrandAndCachedVerificationCopySeparate",
            widthDp = 600,
            heightDp = 800,
            fontScale = 1.3f,
            photoExpected = true,
        )
        assertSplashGeometryAndText(status)

        compose.runOnUiThread { availableHeight.value = 360 }
        compose.waitForIdle()
        captureRoot(
            name = "entry-splash-compact-600x360-font1_3",
            testName = "compactLargeTextSplashKeepsBrandAndCachedVerificationCopySeparate",
            widthDp = 600,
            heightDp = 360,
            fontScale = 1.3f,
            photoExpected = true,
        )

        compose.onNodeWithTag("entry-splash-scene").assertIsDisplayed()
        compose.onNodeWithText(status).assertIsDisplayed()
        assertSplashGeometryAndText(status)
    }

    @Test
    fun landscapeLoginFillsMeasuredViewportAndKeepsControlsInitiallyReachable() {
        renderLogin(widthDp = 1_280, heightDp = 800)

        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodes(hasTestTag("login-scene-background-ready-workspace"))
                .fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasTestTag("login-scene-glass-ready-workspace"))
                    .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("login-scene-background-ready-workspace").assertIsDisplayed()
        compose.onNodeWithTag("login-scene-glass-ready-workspace").assertIsDisplayed()
        assertEquals(
            "Login must never substitute the unrelated workspace fallback artwork",
            0,
            compose.onAllNodes(hasTestTag("login-scene-background-fallback-workspace"))
                .fetchSemanticsNodes().size,
        )

        val root = mainEntryRoot().getUnclippedBoundsInRoot()
        val composition = compose.onNodeWithTag("login-composition").getUnclippedBoundsInRoot()
        val backdrop = compose.onNodeWithTag("login-backdrop").getUnclippedBoundsInRoot()
        val formZone = compose.onNodeWithTag("login-form-zone").getUnclippedBoundsInRoot()
        val photoZone = compose.onNodeWithTag("login-photo-zone").getUnclippedBoundsInRoot()
        val form = compose.onNodeWithTag("login-form").getUnclippedBoundsInRoot()
        val brand = compose.onNodeWithTag("login-brand-panel").getUnclippedBoundsInRoot()
        val brandMark = compose.onNodeWithTag("login-brand-mark").getUnclippedBoundsInRoot()
        val emailLabel = compose.onNodeWithTag("login-email-label").getUnclippedBoundsInRoot()
        val formZoneWidth = formZone.right - formZone.left
        val compositionWidth = composition.right - composition.left
        val compositionHeight = composition.bottom - composition.top
        val formWidth = form.right - form.left
        val formFraction = formZoneWidth / compositionWidth
        val controlFraction = formWidth / formZoneWidth
        assertBoundsMatch("Composition must fill the measured safe viewport", composition, root)
        assertBoundsMatch("Backdrop must cover the full composition without letterboxing", backdrop, root)
        assertTrue("Glass panel must occupy 35–37%: $formFraction", formFraction in 0.35f..0.37f)
        assertTrue("Controls must occupy 78–80% of form zone: $controlFraction", controlFraction in 0.78f..0.80f)
        assertTrue("Photo must begin after the form zone", photoZone.left >= formZone.right)
        assertTrue("Glass panel must retain a 47–49dp left inset: $formZone", (formZone.left - composition.left).value in 47f..49f)
        assertTrue("Glass panel must retain a 47–49dp top inset: $formZone", (formZone.top - composition.top).value in 47f..49f)
        assertTrue("Glass panel must retain a 47–49dp bottom inset: $formZone", (composition.bottom - formZone.bottom).value in 47f..49f)
        compose.onNodeWithTag("login-glass-material").assertIsDisplayed()
        val brandCenterOffset = abs(
            ((brand.left.value + brand.right.value) / 2f) -
                ((formZone.left.value + formZone.right.value) / 2f),
        )
        assertTrue("Brand must stay centered in the form panel: $brandCenterOffset", brandCenterOffset <= 1f)
        val panelHeight = formZone.bottom - formZone.top
        val brandTopFraction = (brand.top - formZone.top) / panelHeight
        val brandEndFraction = (brand.bottom - formZone.top) / panelHeight
        val markCenterFraction = (((brandMark.top + brandMark.bottom) / 2f) - formZone.top) / panelHeight
        val formStartFraction = (emailLabel.top - formZone.top) / panelHeight
        assertTrue("Brand band must begin near 6% of glass: $brandTopFraction", brandTopFraction in 0.05f..0.07f)
        assertTrue("Brand subtitle band must end near 30% of glass: $brandEndFraction", brandEndFraction in 0.29f..0.31f)
        assertTrue("Original brand mark must center near 11% of glass: $markCenterFraction", markCenterFraction in 0.09f..0.13f)
        assertTrue("First field label must begin near 36% of glass: $formStartFraction", formStartFraction in 0.35f..0.38f)
        assertInkWidthFraction("login-brand-name", formZoneWidth, 0.48f..0.62f)
        assertInkWidthFraction("login-brand-subtitle", formZoneWidth, 0.30f..0.46f)
        listOf("login-email", "login-password", "login-recover", "login-submit", "login-version")
            .forEach { tag -> assertInitiallyVisibleTarget(tag, root, formZone) }
        compose.onNodeWithTag("login-email").assertHeightIsAtLeast(64.dp)
        compose.onNodeWithTag("login-password").assertHeightIsAtLeast(64.dp)
        compose.onNodeWithTag("login-recover").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("login-submit")
            .assertHeightIsAtLeast(64.dp)
            .assertIsNotEnabled()
        assertTextDoesNotOverflow("v${BuildConfig.VERSION_NAME}", scrollIntoView = false)
        assertTextDoesNotOverflow("Login", scrollIntoView = false)
        val submit = compose.onNodeWithTag("login-submit").getUnclippedBoundsInRoot()
        val version = compose.onNodeWithTag("login-version").getUnclippedBoundsInRoot()
        assertTrue("Disabled Login and version must not overlap: submit=$submit version=$version", submit.bottom <= version.top)
        assertDisabledLoginContrast()
        compose.onNodeWithContentDescription("D Company illustrated login artwork").assertIsDisplayed()
        captureRoot(
            name = "entry-login-landscape-1280x800",
            testName = "landscapeLoginFillsMeasuredViewportAndKeepsControlsInitiallyReachable",
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.0f,
            photoExpected = true,
        )

        compose.onNodeWithTag("login-email").performTextReplacement("splash-preview@example.test")
        compose.onNodeWithTag("login-password").performTextReplacement("dummy-secret")
        closeSoftKeyboard()
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithTag("login-submit").assertIsEnabled()
        captureRoot(
            name = "entry-login-landscape-enabled-1280x800",
            testName = "landscapeLoginFillsMeasuredViewportAndKeepsControlsInitiallyReachable",
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.0f,
            photoExpected = true,
        )
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
            photoExpected = true,
        )

        compose.onNodeWithTag("login-layout-compact").assertIsDisplayed()
        compose.onNodeWithTag("login-layout-wide").assertDoesNotExist()
        compose.onNodeWithTag("login-photo").assertIsDisplayed()
        compose.onNodeWithTag("login-photo-zone").assertDoesNotExist()
        compose.onNodeWithTag("login-glass-material").assertIsDisplayed()
        compose.onNodeWithTag("login-version").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
        assertEssentialTargets()
        listOf("D COMPANY", "GAMING LOUNGE", "Forgot password?", "Login")
            .forEach(::assertTextDoesNotOverflow)
    }

    @Test
    fun wideHeightReductionKeepsFormIdentityFocusAndTypedState() {
        val appearance = mutableStateOf(AppearancePreference.Night)
        val availableHeight = mutableStateOf(800)
        val expectedEmail = "synthetic@example.test"
        val expectedPassword = "temporary-secret"
        var submittedCredentials: Pair<String, String>? = null
        assertHost()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DCompanyTheme(appearance.value) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(1_280.dp).height(availableHeight.value.dp)) {
                            LoginWorkspace(
                                signingIn = false,
                                error = null,
                                onSignIn = { email, password -> submittedCredentials = email to password },
                                appearancePreference = appearance.value,
                                onAppearancePreferenceChanged = { appearance.value = it },
                                onRecover = {},
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("login-layout-wide").assertIsDisplayed()
        compose.onNodeWithTag("login-email")
            .performScrollTo()
            .performTextReplacement(expectedEmail)
        val password = compose.onNodeWithTag("login-password").performScrollTo()
        password.performTextReplacement(expectedPassword)
        password.performClick().assertIsFocused()
        closeSoftKeyboard()
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        password.assertIsFocused()
        val fullHeightControlGeometry = horizontalControlGeometry(
            listOf("login-email", "login-password", "login-recover", "login-submit"),
        )
        compose.runOnUiThread { availableHeight.value = 550 }
        compose.waitForIdle()
        val intermediateHeightControlGeometry = horizontalControlGeometry(
            listOf("login-email", "login-password", "login-recover", "login-submit"),
        )
        assertEquals(
            "Intermediate wide height must preserve horizontal control geometry and target heights",
            fullHeightControlGeometry,
            intermediateHeightControlGeometry,
        )
        compose.onNodeWithTag("login-brand-mark").assertDoesNotExist()
        compose.onNodeWithTag("login-appearance")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        captureRoot(
            name = "entry-login-wide-intermediate-1280x550-font1_3",
            testName = "wideHeightReductionKeepsFormIdentityFocusAndTypedState",
            widthDp = 1_280,
            heightDp = 550,
            fontScale = 1.3f,
            photoExpected = true,
        )
        compose.runOnUiThread { availableHeight.value = 420 }
        compose.waitForIdle()
        val reducedHeightControlGeometry = horizontalControlGeometry(
            listOf("login-email", "login-password", "login-recover", "login-submit"),
        )
        assertEquals(
            "Wide height reduction must preserve horizontal control geometry and target heights",
            fullHeightControlGeometry,
            reducedHeightControlGeometry,
        )

        compose.onNodeWithTag("login-layout-wide").assertIsDisplayed()
        compose.onNodeWithTag("login-layout-compact").assertDoesNotExist()
        compose.onNodeWithTag("login-photo").assertIsDisplayed()
        compose.onNodeWithTag("login-brand-mark").assertDoesNotExist()
        val reducedRoot = mainEntryRoot().getUnclippedBoundsInRoot()
        assertBoundsMatch(
            "Simulated IME-sized composition must follow the reduced safe viewport",
            compose.onNodeWithTag("login-composition").getUnclippedBoundsInRoot(),
            reducedRoot,
        )
        assertBoundsMatch(
            "Simulated IME-sized backdrop must still cover the reduced safe viewport",
            compose.onNodeWithTag("login-backdrop").getUnclippedBoundsInRoot(),
            reducedRoot,
        )
        compose.onNodeWithTag("login-email")
            .performScrollTo()
            .assertTextEquals(expectedEmail)
        compose.onNodeWithTag("login-password")
            .assertIsFocused()
            .assert(
                SemanticsMatcher("password remains masked at the original length") { node ->
                    node.config.contains(SemanticsProperties.EditableText) &&
                        node.config[SemanticsProperties.EditableText].text.let { editable ->
                            editable.length == expectedPassword.length && editable != expectedPassword
                        }
                },
            )
        compose.onNodeWithTag("login-appearance")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("appearance-option-Day").performClick()
        compose.runOnIdle { assertEquals(AppearancePreference.Day, appearance.value) }
        compose.onNodeWithTag("login-email")
            .performScrollTo()
            .assertTextEquals(expectedEmail)
        compose.onNodeWithTag("login-submit")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(expectedEmail to expectedPassword, submittedCredentials)
        }
        captureRoot(
            name = "entry-login-wide-reduced-1280x420-font1_3",
            testName = "wideHeightReductionKeepsFormIdentityFocusAndTypedState",
            widthDp = 1_280,
            heightDp = 420,
            fontScale = 1.3f,
            photoExpected = true,
        )
        assertEssentialTargets()
        listOf("Forgot password?", "Login")
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

        compose.onNodeWithTag("login-photo").assertIsDisplayed()
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
            photoExpected = true,
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
                photoExpected = true,
            )
            assertBusyButtonContrast(preference)
            assertBusyAuxiliaryContrast(preference)
            if (preference == AppearancePreference.Day) {
                listOf("login-brand-name", "login-email-label").forEach { tag ->
                    compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    assertTaggedTextContrast(tag)
                }
                listOf("D COMPANY", "GAMING LOUNGE", "Email").forEach(::assertTextDoesNotOverflow)
            }
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
        listOf("login-email", "login-password", "login-recover", "login-submit")
            .forEach { tag ->
                val target = compose.onNodeWithTag(tag).performScrollTo()
                println("entry-essential-target tag=$tag bounds=${target.getUnclippedBoundsInRoot()}")
                target
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
                assertFullyInsideRoot(tag)
            }
        val appearance = compose.onNodeWithTag("login-appearance")
        val appearanceIsScrollOwned = compose.onAllNodes(
            hasTestTag("login-appearance") and hasAnyAncestor(hasScrollAction()),
        ).fetchSemanticsNodes().isNotEmpty()
        if (appearanceIsScrollOwned) appearance.performScrollTo()
        appearance
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("login-appearance")
    }

    private fun assertSplashGeometryAndText(status: String = "Preparing your workspace") {
        val root = mainEntryRoot().getUnclippedBoundsInRoot()
        val mark = compose.onNodeWithTag("entry-splash-brand-mark").getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("entry-splash-brand-name").getUnclippedBoundsInRoot()
        val tagline = compose.onNodeWithTag("entry-splash-brand-tagline").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("entry-status").getUnclippedBoundsInRoot()
        val rootHeight = root.bottom - root.top
        val markCenter = (((mark.top + mark.bottom) / 2f) - root.top) / rootHeight
        assertTrue("Splash D must stay near 33% height: $markCenter", markCenter in 0.32f..0.34f)
        assertTrue("Splash D and company name must not overlap: mark=$mark name=$name", mark.bottom <= name.top)
        assertTrue("Splash company name and tagline must not overlap: name=$name tagline=$tagline", name.bottom <= tagline.top)
        assertTrue("Splash tagline and status must not overlap: tagline=$tagline footer=$footer", tagline.bottom <= footer.top)
        listOf("entry-splash-brand-mark", "entry-splash-brand-name", "entry-splash-brand-tagline", "entry-status")
            .forEach(::assertFullyInsideRoot)
        listOf("D COMPANY", "GAMING LOUNGE", status).forEach { text ->
            assertTextDoesNotOverflow(text, scrollIntoView = false)
        }
    }

    private fun assertFullyInsideRoot(tag: String) {
        val root = mainEntryRoot().getUnclippedBoundsInRoot()
        val control = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue(
            "$tag must stay inside the rendered root: control=$control root=$root",
            control.left >= root.left && control.top >= root.top &&
                control.right <= root.right && control.bottom <= root.bottom,
        )
    }

    private fun assertInitiallyVisibleTarget(
        tag: String,
        root: androidx.compose.ui.unit.DpRect,
        formZone: androidx.compose.ui.unit.DpRect,
    ) {
        val target = compose.onNodeWithTag(tag).assertIsDisplayed()
        val bounds = target.getUnclippedBoundsInRoot()
        assertTrue(
            "$tag must be initially visible without semantic scrolling: target=$bounds root=$root",
            bounds.left >= root.left && bounds.top >= root.top &&
                bounds.right <= root.right && bounds.bottom <= root.bottom,
        )
        assertTrue(
            "$tag must remain inside the form zone: target=$bounds formZone=$formZone",
            bounds.left >= formZone.left && bounds.top >= formZone.top &&
                bounds.right <= formZone.right && bounds.bottom <= formZone.bottom,
        )
    }

    private fun assertBoundsMatch(
        message: String,
        actual: androidx.compose.ui.unit.DpRect,
        expected: androidx.compose.ui.unit.DpRect,
    ) {
        assertTrue(
            "$message: actual=$actual expected=$expected",
            abs(actual.left.value - expected.left.value) <= 1f &&
                abs(actual.top.value - expected.top.value) <= 1f &&
                abs(actual.right.value - expected.right.value) <= 1f &&
                abs(actual.bottom.value - expected.bottom.value) <= 1f,
        )
    }

    private fun assertDisabledLoginContrast() {
        val bitmap = compose.onNodeWithTag("login-submit").captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(bitmap)
            val proof = contrastProof(
                bitmap = bitmap,
                background = background,
                left = (bitmap.width * 0.05f).roundToInt(),
                right = (bitmap.width * 0.70f).roundToInt(),
                threshold = 4.5,
                top = 0,
                bottom = bitmap.height,
            )
            assertTrue("Disabled Login must remain legible at 4.5:1; actual=${proof.peak}", proof.peak >= 4.5)
            assertTrue(
                "Disabled Login must retain resolved core label pixels; count=${proof.pixelsMeetingThreshold}",
                proof.pixelsMeetingThreshold >= 8,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun controlGeometry(tags: List<String>): Map<String, List<Float>> =
        tags.associateWith { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            listOf(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
        }

    private fun horizontalControlGeometry(tags: List<String>): Map<String, List<Float>> =
        tags.associateWith { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            listOf(bounds.left.value, bounds.right.value, (bounds.bottom - bounds.top).value)
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

    private fun assertBusyAuxiliaryContrast(preference: AppearancePreference) {
        val directory = outputDirectory()
        val preferenceName = preference.name.lowercase()
        listOf(
            AuxiliaryContrastTarget(
                tag = "login-password-visibility",
                evidenceName = "password-visibility",
                threshold = 3.0,
                minimumPixels = 1,
            ),
            AuxiliaryContrastTarget(
                tag = "login-recover",
                evidenceName = "recovery-action",
                threshold = 4.5,
                minimumPixels = 8,
            ),
        ).forEach { target ->
            val node = compose.onNodeWithTag(target.tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
            val bitmap = node.captureToImage().asAndroidBitmap()
            val stem = "entry-login-busy-${target.evidenceName}-$preferenceName-font1_3"
            val imageFile = directory.resolve("$stem.png")
            val background: Int
            val proof: PixelContrastProof
            try {
                background = dominantOpaqueColor(bitmap)
                proof = contrastProof(
                    bitmap = bitmap,
                    background = background,
                    left = 0,
                    right = bitmap.width,
                    threshold = target.threshold,
                    top = 0,
                    bottom = bitmap.height,
                )
                imageFile.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
            directory.resolve("$stem.contrast.json").writeText(
                """
                {
                  "phase": "C1-login-fidelity-R5",
                  "test_class": "cloud.dcompany.erp.ui.screens.ExecutiveEntryPresentationUiTest",
                  "test_name": "busyActionMeetsResolvedContrastInNightAndDay",
                  "source_inventory_sha256": ${jsonString(evidencePinOrNull(SourceInventoryArgument))},
                  "target_apk_sha256": ${jsonString(evidencePinOrNull(TargetApkArgument))},
                  "test_apk_sha256": ${jsonString(evidencePinOrNull(TestApkArgument))},
                  "appearance": "${preference.name}",
                  "tag": "${target.tag}",
                  "png": "${imageFile.name}",
                  "png_sha256": "${sha256(imageFile)}",
                  "background_argb": "${argbHex(background)}",
                  "resolved_peak_contrast": ${formatContrast(proof.peak)},
                  "resolved_pixels_at_threshold": ${proof.pixelsMeetingThreshold},
                  "required_contrast": ${target.threshold},
                  "required_pixels": ${target.minimumPixels}
                }
                """.trimIndent() + "\n",
            )
            assertTrue(
                "${preference.name} ${target.evidenceName} contrast must be at least " +
                    "${target.threshold}:1; actual=${proof.peak}",
                proof.peak >= target.threshold,
            )
            assertTrue(
                "${preference.name} ${target.evidenceName} must contain resolved pixels at " +
                    "${target.threshold}:1; count=${proof.pixelsMeetingThreshold}",
                proof.pixelsMeetingThreshold >= target.minimumPixels,
            )
        }
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

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
    }

    private fun textColor(tag: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$tag must expose one text layout", 1, layouts.size)
        return layouts.single().layoutInput.style.color.toArgb()
    }

    private fun assertTaggedForegroundContrast(
        tag: String,
        threshold: Double,
        minimumPixels: Int,
        preference: AppearancePreference,
    ) {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(bitmap)
            val proof = contrastProof(
                bitmap = bitmap,
                background = background,
                left = 0,
                right = bitmap.width,
                threshold = threshold,
                top = 0,
                bottom = bitmap.height,
            )
            assertTrue(
                "$preference $tag must resolve at $threshold:1; actual=${proof.peak}",
                proof.peak >= threshold,
            )
            assertTrue(
                "$preference $tag must contain at least $minimumPixels resolved foreground pixels; " +
                    "count=${proof.pixelsMeetingThreshold}",
                proof.pixelsMeetingThreshold >= minimumPixels,
            )
        } finally {
            bitmap.recycle()
        }
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

    private fun assertInkWidthFraction(tag: String, referenceWidth: androidx.compose.ui.unit.Dp, expected: ClosedFloatingPointRange<Float>) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$tag must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertFalse("$tag must not overflow", layout.hasVisualOverflow)
        val inkWidthPx = layout.getLineRight(0) - layout.getLineLeft(0)
        val referenceWidthPx = with(compose.density) { referenceWidth.toPx() }
        val fraction = inkWidthPx / referenceWidthPx
        assertTrue("$tag ink fraction $fraction must be in $expected", fraction in expected)
    }

    private fun assertTaggedTextContrast(tag: String) {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(bitmap)
            val proof = contrastProof(
                bitmap = bitmap,
                background = background,
                left = 0,
                right = bitmap.width,
                threshold = 4.5,
                top = 0,
                bottom = bitmap.height,
            )
            assertTrue("$tag must resolve at 4.5:1 in compact Day; actual=${proof.peak}", proof.peak >= 4.5)
            assertTrue("$tag must contain resolved text pixels in compact Day", proof.pixelsMeetingThreshold >= 8)
        } finally {
            bitmap.recycle()
        }
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
                assertEquals(
                    "HD entry host must use its requested display",
                    host.requestedDisplayId,
                    host.observedDisplayId,
                )
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
        val bitmap = mainEntryRoot().captureToImage().asAndroidBitmap()
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
            val freshLoginExpected = photoExpected && name.startsWith("entry-fresh-login")
            val loginArtworkExpected = photoExpected && !splashExpected && !freshLoginExpected
            val splashCropJson = if (splashExpected) {
                val bounds = compose.onNodeWithTag("entry-splash-scene", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                val crop = splashSceneSourceCrop(
                    destinationWidthPx = ((bounds.right - bounds.left).value * host.density).roundToInt(),
                    destinationHeightPx = ((bounds.bottom - bounds.top).value * host.density).roundToInt(),
                )
                "{\"left\":${crop.left},\"top\":${crop.top},\"right\":${crop.right}," +
                    "\"bottom\":${crop.bottom}}"
            } else {
                "null"
            }
            val loginSceneSourceJson = if (loginArtworkExpected) {
                "{\"left\":0,\"top\":0,\"right\":1672,\"bottom\":941}"
            } else "null"
            directory.resolve("$name.provenance.json").writeText(
                """
                {
                  "phase": "${if (freshLoginExpected) "FRESH_LOGIN_HANDOFF" else "C1_SPLASH"}",
                  "capture_api": "ComposeTestRule.onNode(isRoot + entry descendant).captureToImage()",
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
                  "splash_lounge_hd_r1_sha256": "$SplashLoungeHdR1Sha256",
                  "splash_lounge_hd_r1_source_crop": $splashCropJson,
                  "splash_lounge_hd_r1_dimensions_px": "1672x941",
                  "splash_lounge_provenance": "Prepared clean scene derived from the selected Splash reference; no baked application UI and not 4K",
                  "login_reference_sha256": "$LoginReferenceAssetSha256",
                  "login_scene_hd_r1_sha256": "$LoginSceneHdR1Sha256",
                  "login_scene_hd_r1_source_rect": $loginSceneSourceJson,
                  "login_scene_hd_r1_dimensions_px": "1672x941",
                  "login_scene_provenance": "AI reconstruction from supplied Login reference; clean scene with no baked controls or branding; not original pixels and not 4K",
                  "login_scene_rendering": "viewport-filling centerCrop with proportional edge cropping and native overlays",
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

    private fun mainEntryRoot() = compose.onNode(
        isRoot() and hasAnyDescendant(
            hasTestTag("executive-entry-splash") or
                hasTestTag("login-layout-wide") or
                hasTestTag("login-layout-compact") or
                hasTestTag("fresh-login-root"),
        ),
        useUnmergedTree = true,
    )

    private fun outputDirectory(): File {
        val profile = RequestedEntryDisplayId?.let { "display-$it-hd" } ?: "default-display"
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-c1-splash-screenshots-v1")
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
        const val BaseCommit = "72f3d8aaf51ec2b69d76b8cb8aa18d339c3459c0"
        val CaptureRunId = "${System.currentTimeMillis()}-${android.os.Process.myPid()}"
    }
}

package cloud.dcompany.erp.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LoginWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nextFocusesPasswordAndImeSubmitsExactCredentialsWhileBusyBlocksActions() {
        val busy = mutableStateOf(false)
        val submitted = mutableListOf<Pair<String, String>>()
        compose.setContent {
            LoginTestTheme(AppearancePreference.Day) {
                LoginWorkspace(
                    signingIn = busy.value, error = null,
                    onSignIn = { email, password -> submitted += email to password; busy.value = true },
                    appearancePreference = AppearancePreference.Day,
                    onAppearancePreferenceChanged = {}, onRecover = {},
                )
            }
        }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-email").performTextReplacement("synthetic@example.test")
        compose.onNodeWithTag("login-email").performImeAction()
        compose.onNodeWithTag("login-password").assertIsFocused().performTextReplacement(" exact secret ")
        compose.onNodeWithTag("login-password").performImeAction()
        compose.onNodeWithTag("login-submit").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("login-recover").assertIsNotEnabled()
        compose.onNodeWithTag("login-email").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("synthetic@example.test" to " exact secret "), submitted)
        }
    }

    @Test
    fun appearanceChangeKeepsTypedValuesAndRecoveryClearsPassword() {
        val appearance = mutableStateOf(AppearancePreference.Day)
        var recoveredEmail: String? = null
        var submittedPassword: String? = null
        compose.setContent {
            LoginTestTheme(appearance.value) {
                LoginWorkspace(
                    signingIn = false, error = null, onSignIn = { _, password -> submittedPassword = password },
                    appearancePreference = appearance.value,
                    onAppearancePreferenceChanged = { appearance.value = it },
                    onRecover = { recoveredEmail = it },
                )
            }
        }
        compose.onNodeWithTag("login-email").performTextReplacement("synthetic@example.test")
        compose.onNodeWithTag("login-password").performTextReplacement("temporary-secret")
        compose.onNodeWithTag("login-appearance").performClick()
        compose.onNodeWithTag("appearance-option-Night").performClick()
        compose.runOnIdle { assertEquals(AppearancePreference.Night, appearance.value) }
        compose.onNodeWithTag("login-email").assertTextEquals("synthetic@example.test")
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithTag("login-password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("temporary-secret")),
        )
        closeSoftKeyboard()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("temporary-secret", submittedPassword) }
        compose.onNodeWithTag("login-recover").performClick()
        compose.onNodeWithTag("login-password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
        compose.runOnIdle { assertEquals("synthetic@example.test", recoveredEmail) }
    }

    @Test
    fun restorationRetainsOnlyEmailAndRemasksPassword() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LoginTestTheme(AppearancePreference.Day) {
                LoginWorkspace(false, null, { _, _ -> }, AppearancePreference.Day, {}, onRecover = {})
            }
        }
        compose.onNodeWithTag("login-email").performTextReplacement("synthetic@example.test")
        compose.onNodeWithTag("login-password").performTextReplacement("never-persist-this")
        compose.onNodeWithContentDescription("Show password").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("login-email").assertTextEquals("synthetic@example.test")
        compose.onNodeWithTag("login-password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
    }

    @Test
    fun compactKeyboardHeightWithLargeTypeKeepsErrorsAndSubmitReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LoginTestTheme(AppearancePreference.Night) {
                    Box(Modifier.width(360.dp).height(310.dp)) {
                        LoginWorkspace(
                            false,
                            "This account is temporarily locked. Wait, then try again or ask an owner.",
                            { _, _ -> }, AppearancePreference.Night, {}, onRecover = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("login-email").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun wideKeyboardKeepsVersionOutsideVisibleControlsAndSubmitFullyReachable() {
        val submitted = mutableListOf<Pair<String, String>>()
        compose.setContent {
            LoginTestTheme(AppearancePreference.Night) {
                LoginWorkspace(false, null, { email, password -> submitted += email to password },
                    AppearancePreference.Night, {}, onRecover = {})
            }
        }
        compose.onNodeWithTag("login-layout-wide").assertIsDisplayed()
        compose.onNodeWithTag("login-email").performTextReplacement("synthetic@example.test")
        compose.onNodeWithTag("login-password").performTextReplacement("local-test-password")
        for (field in listOf("login-email", "login-password")) {
            compose.onNodeWithTag(field).performScrollTo().performClick()
            compose.waitUntil(5_000) {
                var visible = false
                compose.runOnIdle {
                    visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            // Check the field's automatic bring-into-view position before scrolling
            // to the bottom, where old content padding could hide the overlap.
            assertVersionDoesNotCoverVisibleControls()
        }
        val submit = compose.onNodeWithTag("login-submit").performScrollTo()
        submit.assertIsDisplayed().assertIsEnabled()
        assertVersionDoesNotCoverVisibleControls()
        val visible = submit.fetchSemanticsNode().boundsInRoot
        val unclipped = submit.getUnclippedBoundsInRoot()
        val unclippedHeight = unclipped.bottom - unclipped.top
        val screenNodes = listOf("login-submit", "login-version").map { tag ->
            tag to compose.onNodeWithTag(tag).fetchSemanticsNode()
        }
        compose.runOnIdle {
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("Submit must retain its complete touch target above the footer",
                unclippedHeight.value >= 48f &&
                    kotlin.math.abs(visible.height - unclippedHeight.value * density) <= 1f)
            val safeScreen = AndroidRect()
            compose.activity.window.decorView.getWindowVisibleDisplayFrame(safeScreen)
            assertTrue("The keyboard must still be open while checking screen containment",
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            for ((tag, node) in screenNodes) {
                val topLeft = node.layoutInfo.coordinates.localToScreen(Offset.Zero)
                val bottom = topLeft.y + node.layoutInfo.coordinates.size.height
                assertTrue("$tag must be fully inside the usable screen above the keyboard",
                    topLeft.y >= safeScreen.top && bottom <= safeScreen.bottom)
            }
        }
        submit.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("synthetic@example.test" to "local-test-password"), submitted)
        }
        closeSoftKeyboard()
        compose.onNodeWithTag("login-email").assertTextEquals("synthetic@example.test")
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
        assertVersionDoesNotCoverVisibleControls()
        compose.onNodeWithTag("login-submit").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(List(2) { "synthetic@example.test" to "local-test-password" }, submitted)
        }
    }

    private fun assertVersionDoesNotCoverVisibleControls() {
        compose.onNodeWithTag("login-version").assertIsDisplayed()
        val tags = listOf("login-version", "login-email", "login-password", "login-recover", "login-submit")
        val nodes = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode() }
        // IME/bring-into-view animations can relayout between separate semantics
        // queries. Read every rectangle in one UI-thread snapshot, not mixed frames.
        val (bounds, imeVisible, safeFrame) = compose.runOnIdle {
            val frame = AndroidRect()
            compose.activity.window.decorView.getWindowVisibleDisplayFrame(frame)
            Triple(
                nodes.mapValues { (_, node) -> node.boundsInRoot },
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()),
                frame.toShortString(),
            )
        }
        val footer = bounds.getValue("login-version")
        try {
            for (tag in tags.drop(1)) {
                val control = bounds.getValue(tag)
                if (control.width > 0f && control.height > 0f) {
                    assertTrue("Version footer must not cover visible $tag; " +
                        "bounds=$bounds, imeVisible=$imeVisible, safeFrame=$safeFrame",
                        !footer.overlaps(control))
                }
            }
        } catch (failure: AssertionError) {
            try { capture("login59-keyboard-overlap-${android.os.SystemClock.uptimeMillis()}", "login-composition") }
            catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
            throw failure
        }
    }

    @Test
    fun captureDayAndNightTabletAndPortraitLayouts() {
        val appearance = mutableStateOf(AppearancePreference.Day)
        val portrait = mutableStateOf(false)
        compose.setContent {
            LoginTestTheme(appearance.value) {
                Box(if (portrait.value) Modifier.width(600.dp).height(740.dp) else Modifier.width(1280.dp).height(740.dp)) {
                    LoginWorkspace(false, null, { _, _ -> }, appearance.value,
                        { appearance.value = it }, onRecover = {})
                }
            }
        }
        compose.onNodeWithTag("login-brand-panel").assertIsDisplayed()
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
        capture("login51-day-landscape")
        compose.onNodeWithTag("login-appearance").performClick()
        compose.onNodeWithTag("appearance-option-Night").performClick()
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
        capture("login51-night-landscape")
        compose.runOnIdle { portrait.value = true }
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
        capture("login51-night-portrait")
    }

    @Test
    fun recoveryDialogObservesTheRequestedEntrySceneAndProjectedMaterialOwner() {
        var observedSceneContentId: String? = null
        compose.setContent {
            LoginTestTheme(AppearancePreference.Night) {
                Box(Modifier.width(1_280.dp).height(800.dp)) {
                    LoginWorkspace(
                        signingIn = false,
                        error = null,
                        onSignIn = { _, _ -> },
                        appearancePreference = AppearancePreference.Night,
                        onAppearancePreferenceChanged = {},
                        onRecover = {},
                        overlay = {
                            val scene = LocalWorkspaceGlassScene.current
                            SideEffect {
                                observedSceneContentId = scene?.frame?.actualKey?.contentId
                            }
                            PasswordRecoveryDialog(
                                state = PasswordRecoveryState(open = true),
                                onEmailChanged = {},
                                onCodeChanged = {},
                                onNewPasswordChanged = {},
                                onConfirmPasswordChanged = {},
                                onRequestCode = {},
                                onConfirm = {},
                                onUseDifferentEmail = {},
                                onCancel = {},
                            )
                        },
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodes(hasTestTag("login-scene-glass-ready-workspace"))
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("password-recovery-dialog").assertIsDisplayed()
        compose.onNodeWithText("Reset password").assertIsDisplayed()
        compose.onNodeWithText("Login email").assertIsDisplayed()
        compose.onNodeWithTag("login-scene-glass-ready-workspace").assertIsDisplayed()
        compose.runOnIdle { assertEquals(LoginSceneHdR1Sha256, observedSceneContentId) }
    }

    private fun capture(name: String, tag: String? = null) {
        val destination = File(compose.activity.getExternalFilesDir(null), "login51-review").apply { mkdirs() }
        File(destination, "$name.png").outputStream().use {
            val target = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
            assertTrue(target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

@Composable
private fun LoginTestTheme(preference: AppearancePreference, content: @Composable () -> Unit) {
    DCompanyTheme(preference) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

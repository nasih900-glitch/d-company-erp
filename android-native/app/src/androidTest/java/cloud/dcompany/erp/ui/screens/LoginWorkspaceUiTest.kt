package cloud.dcompany.erp.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
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
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
        capture("login51-night-landscape")
        compose.runOnIdle { portrait.value = true }
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
        capture("login51-night-portrait")
    }

    private fun capture(name: String) {
        val destination = File(compose.activity.getExternalFilesDir(null), "login51-review").apply { mkdirs() }
        File(destination, "$name.png").outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

@Composable
private fun LoginTestTheme(preference: AppearancePreference, content: @Composable () -> Unit) {
    DCompanyTheme(preference) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

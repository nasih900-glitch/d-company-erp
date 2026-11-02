package cloud.dcompany.erp.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for IME visibility and focus across a fully inset Login viewport. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalComposeUiApi::class)
class LoginZeroViewportFocusUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val height=mutableStateOf(550)
    private var actualInputMode: InputModeManager? = null
    private fun show() {
        compose.setContent {
            val density=LocalDensity.current
            val inputMode=LocalInputModeManager.current
            SideEffect { actualInputMode=inputMode }
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface {
                        Box(Modifier.width(1280.dp).height(height.value.dp)) {
                            LoginWorkspace(false,null,{_,_->},AppearancePreference.Night,{},onRecover={})
                        }
                    }
                }
            }
        }
        closeSoftKeyboard()
        awaitIme(false)
    }
    private fun awaitIme(visible: Boolean) {
        compose.waitUntil(timeoutMillis=5_000) {
            (ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true) == visible
        }
    }
    private fun zeroAndReturn(restoredHeight: Int = 420) {
        compose.runOnUiThread { height.value=0 }
        compose.waitForIdle()
        compose.runOnUiThread { height.value=restoredHeight }
        compose.waitForIdle()
    }
    @Test fun zeroViewportDoesNotFocusAnInputWhenNoneWasFocused() {
        show()
        compose.onNodeWithTag("login-email").assertIsNotFocused()
        compose.onNodeWithTag("login-password").assertIsNotFocused()
        zeroAndReturn()
        awaitIme(false)
        compose.onNodeWithTag("login-email").assertIsNotFocused()
        compose.onNodeWithTag("login-password").assertIsNotFocused()
    }
    @Test fun appearanceButtonFocusDoesNotBecomeInputFocusAfterZeroViewport() {
        show()
        try {
            compose.runOnIdle { check(checkNotNull(actualInputMode).requestInputMode(InputMode.Keyboard)) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Day, night and system appearance")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assertIsFocused()
            compose.onNodeWithTag("login-email").assertIsNotFocused()
            compose.onNodeWithTag("login-password").assertIsNotFocused()
            zeroAndReturn()
            awaitIme(false)
            compose.onNodeWithTag("login-email").assertIsNotFocused()
            compose.onNodeWithTag("login-password").assertIsNotFocused()
        } finally {
            // Restore ordinary touch input after testing keyboard focus.
            compose.onRoot().performTouchInput { click(Offset(2f,2f)) }
        }
    }
    @Test fun emailFocusAndExactTypedValueReturnWithoutFocusingPassword() {
        show()
        val email=compose.onNodeWithTag("login-email")
        email.performScrollTo().performTextReplacement("own-synthetic@example.test")
        email.performClick().assertIsFocused()
        awaitIme(true)
        zeroAndReturn(550)
        awaitIme(true)
        email.assertIsFocused()
        email.assertTextEquals("own-synthetic@example.test")
        compose.onNodeWithTag("login-password").assertIsNotFocused()
        closeSoftKeyboard()
        awaitIme(false)
        compose.waitForIdle()
        email.assertIsFocused()
        zeroAndReturn()
        awaitIme(false)
        email.performScrollTo().assertIsFocused()
        email.assertTextEquals("own-synthetic@example.test")
        compose.onNodeWithTag("login-password").assertIsNotFocused()
    }
}

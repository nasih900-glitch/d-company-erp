package cloud.dcompany.erp.ui.screens

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import androidx.test.platform.app.InstrumentationRegistry
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
        val originalTouchMode = compose.runOnIdle { compose.activity.window.decorView.isInTouchMode }
        var scenarioFailure: Throwable? = null
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
        } catch (failure: Throwable) {
            scenarioFailure = failure
            throw failure
        } finally {
            try {
                restoreNativeTouchMode(originalTouchMode)
            } catch (cleanupFailure: Throwable) {
                val failure = scenarioFailure
                if (failure == null) throw cleanupFailure
                failure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun restoreNativeTouchMode(originalTouchMode: Boolean) {
        if (originalTouchMode) {
            // Compose's view-level touch dispatcher does not restore Android's
            // global touch mode. Use the native dispatcher in the visible blank
            // area below this fixture, outside system bars and edge gestures.
            val point = compose.runOnIdle {
                val view = compose.activity.window.decorView
                val visible = android.graphics.Rect()
                check(view.getGlobalVisibleRect(visible)) { "Login fixture is not visible" }
                val window = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(window)
                check(visible.intersect(window)) { "Login fixture is outside the visible window" }
                val margin = 32f * view.resources.displayMetrics.density
                check(visible.width() > margin * 2 && visible.height() > margin * 2)
                val y = visible.bottom - margin
                check(y > visible.top + height.value * view.resources.displayMetrics.density) {
                    "No blank native touch-restoration area below the Login fixture"
                }
                Offset(visible.left + margin, y)
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int) {
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    check(automation.injectInputEvent(event, true)) { "Android rejected native touch restoration" }
                } finally {
                    event.recycle()
                }
            }
            var downFailure: Throwable? = null
            try {
                inject(MotionEvent.ACTION_DOWN)
            } catch (failure: Throwable) {
                downFailure = failure
                throw failure
            } finally {
                try {
                    inject(MotionEvent.ACTION_UP)
                } catch (releaseFailure: Throwable) {
                    val failure = downFailure
                    if (failure == null) throw releaseFailure
                    failure.addSuppressed(releaseFailure)
                }
            }
        } else {
            compose.runOnIdle {
                if (compose.activity.window.decorView.isInTouchMode) {
                    check(checkNotNull(actualInputMode).requestInputMode(InputMode.Keyboard)) {
                        "Android rejected native keyboard-mode restoration"
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { compose.activity.window.decorView.isInTouchMode == originalTouchMode }
        }
        check(compose.runOnIdle { compose.activity.window.decorView.isInTouchMode } == originalTouchMode) {
            "Native input mode was not restored to its original state"
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

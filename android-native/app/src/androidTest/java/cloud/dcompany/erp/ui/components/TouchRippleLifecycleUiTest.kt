package cloud.dcompany.erp.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.ui.captureToImage
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.WorkspaceInteractionFeedback
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TouchRippleLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun touchPressAvoidsNativeRippleHostsWhileKeyboardKeepsFocusAndEnter() {
        val manager = AtomicReference<InputModeManager>()
        val view = AtomicReference<View>()
        val clicks = AtomicInteger()
        val foundationClicks = AtomicInteger()
        compose.setContent {
            val currentManager = LocalInputModeManager.current
            val currentView = LocalView.current
            SideEffect { manager.set(currentManager); view.set(currentView) }
            DCompanyTheme(AppearancePreference.Night) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
                    WorkspaceInteractionFeedback {
                        Column(Modifier.width(400.dp).height(220.dp).testTag("native-ripple-root")) {
                            Box(
                                Modifier.width(280.dp).height(64.dp).testTag("native-ripple-foundation")
                                    .selectable(selected = false, role = Role.Tab) { foundationClicks.incrementAndGet() }
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            ) { Text("Store workspace") }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { clicks.incrementAndGet() },
                                modifier = Modifier.width(280.dp).height(64.dp).testTag("native-ripple-button"),
                            ) { Text("Confirm action") }
                        }
                    }
                }
            }
        }

        val button = compose.onNodeWithTag("native-ripple-button")
        val root = compose.onNodeWithTag("native-ripple-root")
        val foundation = compose.onNodeWithTag("native-ripple-foundation")
        compose.waitForIdle()
        val originalMode = manager.get().inputMode
        fun enterNativeTouchMode() {
            val bounds = root.fetchSemanticsNode().boundsInRoot
            val location = compose.runOnIdle { IntArray(2).also(view.get()::getLocationOnScreen) }
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action,
                    location[0] + bounds.right - 8f, location[1] + bounds.bottom - 8f, 0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
                } finally { event.recycle() }
            }
            compose.waitUntil(2_000) { manager.get().inputMode == InputMode.Touch }
            compose.waitForIdle()
        }
        try {
            repeat(2) { cycle ->
                enterNativeTouchMode()
                compose.waitUntil(2_000) { compose.runOnIdle { nativeRippleHosts(view.get().rootView).pressed == 0 } }
                val beforeTouch = compose.runOnIdle { nativeRippleHosts(view.get().rootView) }
                if (cycle == 0) assertEquals(NativeRippleHosts(0, 0, 0), beforeTouch)
                foundation.performTouchInput { down(center); advanceEventTime(120) }
                try {
                    compose.mainClock.advanceTimeBy(160)
                    assertEquals("Foundation touch must not allocate or activate a native ripple host",
                        beforeTouch, compose.runOnIdle { nativeRippleHosts(view.get().rootView) })
                    assertEquals(0, foundationClicks.get())
                    assertEquals(cycle, clicks.get())
                } finally { foundation.performTouchInput { cancel() } }

                val foundationBefore = foundation.captureToImage().asAndroidBitmap()
                try {
                    compose.runOnIdle { assertTrue(manager.get().requestInputMode(InputMode.Keyboard)) }
                    compose.waitUntil(2_000) { manager.get().inputMode == InputMode.Keyboard }
                    foundation.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                    foundation.assertIsFocused()
                    val focused = foundation.captureToImage().asAndroidBitmap()
                    try {
                        assertTrue("Foundation keyboard focus must retain its visible outline on cycle $cycle",
                            changedPixels(foundationBefore, focused) > 16)
                    } finally { focused.recycle() }
                } finally { foundationBefore.recycle() }

                val unfocused = button.captureToImage().asAndroidBitmap()
                try {
                    button.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                    button.assertIsFocused()
                    compose.mainClock.advanceTimeBy(240)
                    val focused = button.captureToImage().asAndroidBitmap()
                    try {
                        assertTrue("M3 keyboard focus must remain visibly indicated on cycle $cycle",
                            changedPixels(unfocused, focused) > 16)
                    } finally { focused.recycle() }
                } finally { unfocused.recycle() }
                button.performKeyInput { keyDown(Key.Enter) }
                compose.mainClock.advanceTimeBy(160)
                val keyboardHosts = compose.runOnIdle { nativeRippleHosts(view.get().rootView) }
                assertTrue("The exact pinned-class probe must observe the M3 keyboard positive control", keyboardHosts.count > 0)
                button.performKeyInput { keyUp(Key.Enter) }
                button.assertIsFocused()
                compose.runOnIdle {
                    assertEquals(cycle + 1, clicks.get())
                    assertEquals(0, foundationClicks.get())
                }
            }
        } finally {
            if (originalMode == InputMode.Keyboard) {
                compose.runOnIdle { manager.get().requestInputMode(InputMode.Keyboard) }
            } else enterNativeTouchMode()
        }
    }

    private fun changedPixels(first: Bitmap, second: Bitmap): Int {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        var changed = 0
        for (y in 0 until first.height step 2) for (x in 0 until first.width step 2) {
            val before = first.getPixel(x, y)
            val after = second.getPixel(x, y)
            val distance = listOf(0, 8, 16).sumOf { shift -> abs((before ushr shift and 255) - (after ushr shift and 255)) }
            if (distance > 6) changed++
        }
        return changed
    }
}

private data class NativeRippleHosts(val count: Int, val activeCallbacks: Int, val pressed: Int)

/** Exact Compose material-ripple 1.7.5 probe, confined to this regression test. */
private fun nativeRippleHosts(root: View): NativeRippleHosts {
    val hostClass = Class.forName("androidx.compose.material.ripple.RippleHostView")
    val callback = hostClass.getDeclaredField("onInvalidateRipple").apply { isAccessible = true }
    val ripple = hostClass.getDeclaredField("ripple").apply { isAccessible = true }
    var count = 0
    var activeCallbacks = 0
    var pressed = 0
    fun visit(view: View) {
        if (view.javaClass == hostClass) {
            count++
            if (callback.get(view) != null) activeCallbacks++
            if ((ripple.get(view) as? Drawable)?.state?.contains(android.R.attr.state_pressed) == true) pressed++
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
    }
    visit(root)
    return NativeRippleHosts(count, activeCallbacks, pressed)
}

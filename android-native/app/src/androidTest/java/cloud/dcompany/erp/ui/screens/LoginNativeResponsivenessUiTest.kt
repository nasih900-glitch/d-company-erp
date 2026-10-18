package cloud.dcompany.erp.ui.screens

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Replays the input event from the emulator ANR against the real entry activity. */
@RunWith(AndroidJUnit4::class)
class LoginNativeResponsivenessUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun repeatedLoginActivityRemainsResponsiveToMouseHoverAndKeyboard() {
        repeat(3) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val email = awaitNode { it.className == "android.widget.EditText" && containsDescription(it, "Email") }
                val bounds = Rect().also(email::getBoundsInScreen)
                listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT).forEach { action ->
                    pointer(action, bounds, InputDevice.SOURCE_MOUSE)
                }
                val password = awaitNode { it.className == "android.widget.EditText" && containsDescription(it, "Password") }
                password.getBoundsInScreen(bounds)
                val gestureStart = SystemClock.uptimeMillis()
                pointer(MotionEvent.ACTION_DOWN, bounds, InputDevice.SOURCE_TOUCHSCREEN, gestureStart)
                pointer(MotionEvent.ACTION_UP, bounds, InputDevice.SOURCE_TOUCHSCREEN, gestureStart)
                awaitNode { it.className == "android.widget.EditText" && it.isFocused && containsDescription(it, "Password") }
                val deadline = SystemClock.uptimeMillis() + 5_000
                var keyboardVisible = false
                while (!keyboardVisible && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity {
                        keyboardVisible = ViewCompat.getRootWindowInsets(it.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    if (!keyboardVisible) SystemClock.sleep(50)
                }
                assertTrue("The real login must open its keyboard", keyboardVisible)
                instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
                instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
                awaitNode { it.text?.toString() == "Welcome back." }
            }
        }
    }

    private fun pointer(action: Int, bounds: Rect, source: Int, downTime: Long = SystemClock.uptimeMillis()) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        event.source = source
        try {
            assertTrue("Input must reach Android's dispatcher", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                find(root, predicate)?.let { return it }
            }
            SystemClock.sleep(50)
        }
        error("The login did not respond within ten seconds")
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, predicate)?.let { found -> return found } }
        }
        return null
    }

    private fun containsDescription(node: AccessibilityNodeInfo, description: String): Boolean =
        find(node) { it.contentDescription?.toString() == description } != null
}

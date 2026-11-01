package cloud.dcompany.erp.ui.screens.gaming

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import android.util.Log
import android.view.Choreographer
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VoidImeDisplayArgument = "dcompany.testDisplayId"
private const val VoidImeReason = "Reviewed custom reason while keyboard stays open"
private const val VoidImeLogTag = "GamingVoidIme"

private val RequestedVoidImeDisplayId: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(VoidImeDisplayArgument)?.let { raw ->
        val requested = raw.toIntOrNull()
        require(requested in setOf(0, 2, 5, 6)) {
            "Only verified HD displays 0, 2, 5 and 6 are supported: $raw"
        }
        requested
    }
}

private fun <A : ComponentActivity> ActivityScenarioRule<A>.voidImeActivity(): A {
    var activity: A? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity)
}

private fun createVoidImeComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val rule = if (RequestedVoidImeDisplayId == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(RequestedVoidImeDisplayId!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(rule) { it.voidImeActivity() }
}

@RunWith(AndroidJUnit4::class)
class GamingVoidImeUiTest {
    @get:Rule
    val compose = createVoidImeComposeRule()

    private var lastImeEvidence: VoidImeEvidence? = null

    @Test
    fun retainedAddonVoidKeepsRealImeOpenAtFontScaleOnePointThree() {
        var submitted: Pair<String, String?>? = null
        val target = retainedVoidTarget()

        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                DCompanyTheme {
                    CancelUnbilledSessionDialog(
                        stationName = "PlayStation 5 Station With Long Review Name",
                        amountMinor = 15_000,
                        reasonRequired = true,
                        activeAddons = listOf(retainedVoidAddon()),
                        eligibleTargets = listOf(target),
                        onDismiss = {},
                        onConfirm = {},
                        onConfirmWithTarget = { reason, targetId -> submitted = reason to targetId },
                    )
                }
            }
        }

        assertRequestedHost()
        withInteractiveWindows {
            compose.onNodeWithContentDescription("Cancellation reason: Other or add details")
                .performScrollTo()
                .performTouchInput { click(center) }

            val custom = compose.onNodeWithContentDescription("Custom cancellation reason")
                .performScrollTo()
                .assertIsDisplayed()
            custom.performTouchInput { click(center) }
            custom.assertIsFocused()

            // The physical field tap is the primary user path. If Android ignores
            // that first request, exercise the product's visible recovery action
            // rather than manufacturing an IME with a test-only system call.
            if (awaitImeOrNull(custom, timeoutMillis = 2_000) == null) {
                awaitKeyboardRecoveryDisplayed(custom)
                    .assertCompleteTouchOwnerWithoutIme()
                    .performTouchInput { click(center) }
            }
            custom.assertIsFocused()
            awaitIme(custom).assertImeAndCompleteTouchOwner("Custom reason field")

            custom.performTextReplacement(VoidImeReason)
            custom.assertIsFocused().assertTextContains(VoidImeReason)
            awaitIme(custom).assertImeAndCompleteTouchOwner("Custom reason field after typing")

            val targetOwner = compose.onNode(
                hasText("${target.terminalName} · ${target.openedByName}") and hasClickAction(),
            ).performScrollTo().assertIsDisplayed()
            awaitIme(targetOwner).assertImeAndCompleteTouchOwner("Retained-bill target")
            targetOwner.performTouchInput { click(center) }

            custom.assertTextContains(VoidImeReason)
            awaitIme(targetOwner).assertImeAndCompleteTouchOwner("Selected retained-bill target")

            val confirm = compose.onNode(hasText("Void session") and hasClickAction())
                .bringIntoViewIfNeeded()
                .assertIsDisplayed()
                .assertIsEnabled()
            awaitIme(confirm).assertImeAndCompleteTouchOwner("Void confirmation")
            confirm.performTouchInput { click(center) }
        }

        compose.runOnIdle {
            assertEquals(VoidImeReason to target.shiftId, submitted)
        }
    }

    private fun assertRequestedHost() {
        if (RequestedVoidImeDisplayId == null) return
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val display = checkNotNull(activity.display) { "Hosted activity has no display" }
            assertEquals(RequestedVoidImeDisplayId, display.displayId)
            assertEquals(2_560, display.mode.physicalWidth)
            assertEquals(1_600, display.mode.physicalHeight)
            assertEquals(320, activity.resources.displayMetrics.densityDpi)
        }
    }

    private inline fun <T> withInteractiveWindows(block: () -> T): T {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        return try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            block()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun SemanticsNodeInteraction.bringIntoViewIfNeeded(): SemanticsNodeInteraction =
        if (isDisplayed()) this else performScrollTo()

    private fun SemanticsNodeInteraction.assertCompleteTouchOwnerWithoutIme(): SemanticsNodeInteraction {
        val evidence = readImeEvidence()
        evidence.assertCompleteTouchOwner("Keyboard recovery")
        return this
    }

    private fun awaitKeyboardRecoveryDisplayed(
        custom: SemanticsNodeInteraction,
        timeoutMillis: Long = 3_000,
    ): SemanticsNodeInteraction {
        val recovery = compose.onNodeWithContentDescription(
            "Show keyboard for custom cancellation reason",
        )
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        do {
            recovery.performScrollTo()
            awaitAndroidFrame()
            compose.waitForIdle()
            if (recovery.isDisplayed()) return recovery
        } while (android.os.SystemClock.uptimeMillis() < deadline)

        val failure = AssertionError("Keyboard recovery never became fully displayed")
        val details = linkedMapOf<String, String>()
        fun collect(label: String, block: () -> Any?) {
            runCatching(block).fold(
                onSuccess = { details[label] = it.toString() },
                onFailure = {
                    details[label] = "unavailable:${it::class.java.simpleName}"
                    failure.addSuppressed(it)
                },
            )
        }
        collect("lastIme") { lastImeEvidence }
        collect("custom") { custom.readImeEvidence() }
        collect("recovery") { recovery.readImeEvidence() }
        collect("scrolls") { readScrollEvidence() }
        collect("screenshot") {
            saveRootScreenshot(recovery, "keyboard-recovery-timeout").absolutePath
        }
        val diagnostic = details.entries.joinToString(separator = " ") { (key, value) -> "$key=$value" }
        failure.addSuppressed(IllegalStateException("Sanitized diagnostics: $diagnostic"))
        Log.e(VoidImeLogTag, "${failure.message}; $diagnostic", failure)
        throw failure
    }

    private fun readScrollEvidence(): List<VoidScrollEvidence> =
        compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().mapIndexedNotNull { index, node ->
            val property = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
            if (!node.config.contains(property)) return@mapIndexedNotNull null
            val range = node.config[property]
            VoidScrollEvidence(index, range.value(), range.maxValue(), range.reverseScrolling)
        }

    private fun saveRootScreenshot(
        interaction: SemanticsNodeInteraction,
        label: String,
    ): File {
        val root = interaction.fetchSemanticsNode().root as ViewRootForTest
        var bitmap: Bitmap? = null
        try {
            compose.runOnIdle {
                val view = root.view
                check(view.width > 0 && view.height > 0) {
                    "Cannot capture zero-sized dialog root ${view.width}x${view.height}"
                }
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(checkNotNull(bitmap)))
            }
            val file = File(
                checkNotNull(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                ),
                "gaming-void-ime-$label.png",
            )
            file.outputStream().use { output ->
                check(checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Failed to encode ${file.absolutePath}"
                }
            }
            return file
        } finally {
            bitmap?.recycle()
        }
    }

    private fun awaitIme(
        interaction: SemanticsNodeInteraction,
        timeoutMillis: Long = 10_000,
    ): VoidImeEvidence = checkNotNull(awaitImeOrNull(interaction, timeoutMillis)) {
        "Software IME did not become stable on the hosted display; last=$lastImeEvidence"
    }

    private fun awaitImeOrNull(
        interaction: SemanticsNodeInteraction,
        timeoutMillis: Long = 10_000,
    ): VoidImeEvidence? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        var previous: VoidImeEvidence? = null
        var stableSamples = 0
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            awaitAndroidFrame()
            val current = interaction.readImeEvidence()
            lastImeEvidence = current
            stableSamples = if (current.imeReady && current == previous) stableSamples + 1 else 0
            previous = current
            if (stableSamples >= 2) return current
        }
        return null
    }

    private fun SemanticsNodeInteraction.readImeEvidence(): VoidImeEvidence {
        val node = fetchSemanticsNode()
        val root = node.root as ViewRootForTest
        return compose.runOnIdle {
            val view = root.view
            val activityDisplayId = checkNotNull(compose.activity.display).displayId
            val rootDisplayId = checkNotNull(view.display).displayId
            check(activityDisplayId == rootDisplayId) {
                "Activity display $activityDisplayId differs from root display $rootDisplayId"
            }
            RequestedVoidImeDisplayId?.let { requested ->
                check(rootDisplayId == requested) {
                    "Requested display $requested but dialog is on $rootDisplayId"
                }
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val imeWindow = automation.windowsOnAllDisplays[rootDisplayId]
                ?.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val imeBounds = imeWindow?.let { AndroidRect().also(it::getBoundsInScreen) }
            check(imeWindow == null || imeWindow.displayId == rootDisplayId) {
                "IME display ${imeWindow?.displayId} differs from root display $rootDisplayId"
            }
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val visibleFrame = AndroidRect().also(view::getWindowVisibleDisplayFrame)
            val screenOrigin = IntArray(2).also(view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(view::getLocationInWindow)
            val position = node.positionOnScreen
            val bounds = Rect(
                position.x,
                position.y,
                position.x + node.size.width,
                position.y + node.size.height,
            )
            VoidImeEvidence(
                displayId = rootDisplayId,
                bounds = bounds,
                clippedBounds = node.boundsInWindow.translate(
                    (screenOrigin[0] - windowOrigin[0]).toFloat(),
                    (screenOrigin[1] - windowOrigin[1]).toFloat(),
                ),
                imeBounds = imeBounds,
                imeInsetBottom = imeInsets.bottom,
                imeVisibleByInsets = insets.isVisible(WindowInsetsCompat.Type.ime()),
                systemBars = systemBars,
                visibleFrame = Rect(
                    visibleFrame.left.toFloat(),
                    visibleFrame.top.toFloat(),
                    visibleFrame.right.toFloat(),
                    visibleFrame.bottom.toFloat(),
                ),
                minimumTouchSize = 48f * view.resources.displayMetrics.density,
                density = view.resources.displayMetrics.density,
                densityDpi = view.resources.displayMetrics.densityDpi,
                rootWidth = view.width,
                rootHeight = view.height,
                windowFocused = view.hasWindowFocus(),
                layoutPending = root.hasPendingMeasureOrLayout || view.isLayoutRequested,
            )
        }
    }

    private fun awaitAndroidFrame() {
        val rendered = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback { rendered.countDown() }
        }
        check(rendered.await(2, TimeUnit.SECONDS)) { "Android did not render another frame" }
    }

    private fun retainedVoidAddon() = SessionAddon(
        id = "addon-retained-1",
        gamingSessionId = "session-retained-1",
        clientLineId = "client-line-retained-1",
        menuItemId = "menu-water",
        menuItemName = "Water",
        menuItemType = "drink",
        modifiers = JsonArray(emptyList()),
        qty = 1,
        catalogUnitPriceMinor = 700,
        unitPriceMinor = 700,
        lineTotalMinor = 700,
        discountMinor = 0,
        taxRate = 18.0,
        taxableValueMinor = 600,
        cgstMinor = 50,
        sgstMinor = 50,
        igstMinor = 0,
        cessMinor = 0,
        createdBy = "owner-1",
        createdTerminalId = "terminal-gaming-1",
        createdAt = "2026-10-26T08:10:00Z",
    )

    private fun retainedVoidTarget() = PosTargetShift(
        shiftId = "shift-pos-1",
        terminalId = "terminal-pos-1",
        terminalName = "Front Cafe Point of Sale Counter",
        openedBy = "cashier-1",
        openedByName = "Long Employee Display Name",
        openedAt = "2026-10-26T08:00:00Z",
    )
}

private data class VoidImeEvidence(
    val displayId: Int,
    val bounds: Rect,
    val clippedBounds: Rect,
    val imeBounds: AndroidRect?,
    val imeInsetBottom: Int,
    val imeVisibleByInsets: Boolean,
    val systemBars: Insets,
    val visibleFrame: Rect,
    val minimumTouchSize: Float,
    val density: Float,
    val densityDpi: Int,
    val rootWidth: Int,
    val rootHeight: Int,
    val windowFocused: Boolean,
    val layoutPending: Boolean,
) {
    val imeReady: Boolean
        get() = imeBounds?.isEmpty == false && imeInsetBottom > 0 && imeVisibleByInsets &&
            windowFocused && !layoutPending

    fun assertImeAndCompleteTouchOwner(label: String) {
        assertTrue("$label requires a same-display software IME: $this", imeReady)
        assertCompleteTouchOwner(label)
        assertTrue(
            "$label must remain entirely above the IME: $this",
            bounds.bottom <= checkNotNull(imeBounds).top + 1f,
        )
    }

    fun assertCompleteTouchOwner(label: String) {
        assertTrue(
            "$label must expose a complete 48dp owner: $this",
            bounds.width >= minimumTouchSize - 1f && bounds.height >= minimumTouchSize - 1f,
        )
        assertTrue(
            "$label must not be clipped by an ancestor scroll container: $this",
            abs(bounds.left - clippedBounds.left) <= 1f &&
                abs(bounds.top - clippedBounds.top) <= 1f &&
                abs(bounds.right - clippedBounds.right) <= 1f &&
                abs(bounds.bottom - clippedBounds.bottom) <= 1f,
        )
        assertTrue(
            "$label must remain inside the visible window: $this",
            bounds.left >= visibleFrame.left - 1f && bounds.top >= visibleFrame.top - 1f &&
                bounds.right <= visibleFrame.right + 1f && bounds.bottom <= visibleFrame.bottom + 1f,
        )
        assertTrue("$label window must retain focus: $this", windowFocused)
        assertTrue("$label layout must be settled: $this", !layoutPending)
        assertTrue(
            "$label must have positive geometry: $this",
            abs(bounds.width) > 0f && abs(bounds.height) > 0f,
        )
    }
}

private data class VoidScrollEvidence(
    val index: Int,
    val value: Float,
    val maxValue: Float,
    val reverseScrolling: Boolean,
)

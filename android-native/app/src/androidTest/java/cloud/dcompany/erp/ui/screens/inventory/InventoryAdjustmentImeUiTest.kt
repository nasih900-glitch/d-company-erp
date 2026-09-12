package cloud.dcompany.erp.ui.screens.inventory

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.ui.components.FormDialog
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.PickerField
import cloud.dcompany.erp.ui.components.QuantityField
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Regression coverage for the inventory adjustment dialog with a real Android IME. */
class InventoryAdjustmentImeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longAdjustmentKeepsBothActionsTouchableAboveRealSoftwareIme() {
        var confirmations = 0
        var dismissals = 0
        val dialogVisible = mutableStateOf(true)

        compose.setContent {
            var quantity by remember { mutableStateOf("") }
            var note by remember { mutableStateOf("") }
            DCompanyTheme {
                if (dialogVisible.value) {
                    FormDialog(
                        title = "Adjust Arabica coffee beans",
                        confirmLabel = "Queue adjustment",
                        busy = false,
                        error = null,
                        onDismiss = {
                            dismissals++
                            dialogVisible.value = false
                        },
                        onConfirm = { confirmations++ },
                    ) {
                        Text("On hand now: 18 kg", color = Brand.ForegroundMuted)
                        InfoRow(label = "Branch", value = "D Company Main Branch")
                        Text(
                            TRANSFER_UNAVAILABLE_MESSAGE,
                            color = Brand.Warning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        PickerField(
                            label = "Type",
                            selectedLabel = "Count correction",
                            options = listOf(ADJ_COUNT to "Count correction"),
                            onSelect = {},
                        )
                        QuantityField(
                            value = quantity,
                            onValueChange = { quantity = it },
                            label = "Correction in kg (minus if the real count is lower)",
                            allowNegative = true,
                            modifier = Modifier.fillMaxWidth().testTag(QUANTITY_TAG),
                        )
                        if (quantity == "-1000") {
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Brand.SurfaceRaised, Radius.shapeSm)
                                    .padding(12.dp)
                                    .testTag(PREVIEW_TAG),
                            ) {
                                Text(
                                    "Removes 1000 kg from stock",
                                    color = Brand.Danger,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("18 → 0 kg", color = Brand.Foreground)
                                Text(
                                    PREVIEW_WARNING,
                                    color = Brand.Warning,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("Note (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        val quantity = compose.onNodeWithTag(QUANTITY_TAG)
        quantity.performClick()
            .assertIsFocused()
            .performTextReplacement("-1000")
        quantity.assertTextContains("-1000")
        val transferWarning = compose.onNodeWithText(TRANSFER_UNAVAILABLE_MESSAGE)
        val previewWarning = compose.onNodeWithText(PREVIEW_WARNING)
        val resolvedContent = compose.onNodeWithTag(PREVIEW_TAG)

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val confirm = compose.onNode(dialogButton("Queue adjustment"))
            val cancel = compose.onNode(dialogButton("Cancel"))
            val dialog = compose.onNode(dialogPane("Adjust Arabica coffee beans"))
            val initialEvidence = awaitStableRealImeEvidence(
                confirm,
                cancel,
                resolvedContent,
                dialog,
            )
            initialEvidence.assertRealSoftwareIme()
            quantity.assertIsFocused().assertTextContains("-1000")

            transferWarning.performScrollTo().assertIsDisplayed()
            previewWarning.performScrollTo().assertIsDisplayed()
            val evidence = awaitStableRealImeEvidence(
                confirm,
                cancel,
                resolvedContent,
                dialog,
            )
            saveEvidence("inventory-adjustment-ime-actions.png", evidence)

            evidence.assertRealSoftwareIme()
            quantity.assertIsFocused().assertTextContains("-1000")
            evidence.assertResolvedContentVisible()
            evidence.confirm.assertCompleteTouchTargetAbove(evidence, "Queue adjustment")
            evidence.cancel.assertCompleteTouchTargetAbove(evidence, "Cancel")

            injectAndroidTap(
                evidence.confirm.touchBounds.center.x,
                evidence.confirm.touchBounds.center.y,
            )
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1, confirmations) }

            val afterConfirm = awaitStableRealImeEvidence(
                confirm,
                cancel,
                resolvedContent,
                dialog,
            )
            saveEvidence("inventory-adjustment-ime-cancel.png", afterConfirm)
            afterConfirm.assertRealSoftwareIme()
            afterConfirm.cancel.assertCompleteTouchTargetAbove(afterConfirm, "Cancel")
            injectAndroidTap(
                afterConfirm.cancel.touchBounds.center.x,
                afterConfirm.cancel.touchBounds.center.y,
            )
            compose.waitForIdle()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }

        compose.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(1, dismissals)
            assertTrue("Cancel should dismiss the adjustment dialog", !dialogVisible.value)
        }
    }

    @Test
    fun backdropDismissesOnlyWhenIdleAndNeverFromBlankDialogContent() {
        var dismissals = 0
        val busy = mutableStateOf(false)
        val dialogVisible = mutableStateOf(true)

        compose.setContent {
            DCompanyTheme {
                if (dialogVisible.value) {
                    FormDialog(
                        title = BACKDROP_DIALOG_TITLE,
                        confirmLabel = "Save",
                        busy = busy.value,
                        error = null,
                        onDismiss = {
                            dismissals++
                            dialogVisible.value = false
                        },
                        onConfirm = {},
                    ) {
                        Text("Backdrop behavior fixture")
                        Box(
                            Modifier.fillMaxWidth()
                                .height(80.dp)
                                .testTag(INTERIOR_BLANK_TAG),
                        )
                    }
                }
            }
        }

        val blankContent = compose.onNodeWithTag(INTERIOR_BLANK_TAG).assertIsDisplayed()
        injectAndroidTap(blankContent.fetchSemanticsNode().visualBoundsOnScreen().center)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Blank content inside the surface must not dismiss", 0, dismissals)
            assertTrue(dialogVisible.value)
        }

        val idleDialog = compose.onNode(dialogPane(BACKDROP_DIALOG_TITLE)).assertIsDisplayed()
        injectAndroidTap(backdropPointOutside(idleDialog))
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Idle backdrop tap must dismiss once", 1, dismissals)
            assertTrue(!dialogVisible.value)
            busy.value = true
            dialogVisible.value = true
        }
        compose.waitForIdle()

        val busyDialog = compose.onNode(dialogPane(BACKDROP_DIALOG_TITLE)).assertIsDisplayed()
        injectAndroidTap(backdropPointOutside(busyDialog))
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Busy backdrop tap must not dismiss", 1, dismissals)
            assertTrue(dialogVisible.value)
        }
    }

    @Test
    fun androidBackDismissesOnlyWhenIdle() {
        var dismissals = 0
        val busy = mutableStateOf(false)
        val dialogVisible = mutableStateOf(true)

        compose.setContent {
            DCompanyTheme {
                if (dialogVisible.value) {
                    FormDialog(
                        title = BACK_DIALOG_TITLE,
                        confirmLabel = "Save",
                        busy = busy.value,
                        error = null,
                        onDismiss = {
                            dismissals++
                            dialogVisible.value = false
                        },
                        onConfirm = {},
                    ) {
                        Text("Android Back behavior fixture")
                    }
                }
            }
        }

        val idleDialog = compose.onNode(dialogPane(BACK_DIALOG_TITLE)).assertIsDisplayed()
        idleDialog.awaitDialogWindowFocus()
        injectAndroidBack()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Android Back must dismiss an idle dialog once", 1, dismissals)
            assertTrue(!dialogVisible.value)
            busy.value = true
            dialogVisible.value = true
        }
        compose.waitForIdle()

        val busyDialog = compose.onNode(dialogPane(BACK_DIALOG_TITLE)).assertIsDisplayed()
        busyDialog.awaitDialogWindowFocus()
        injectAndroidBack()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Android Back must not dismiss a busy dialog", 1, dismissals)
            assertTrue(dialogVisible.value)
        }
    }

    @Test
    fun rawConfirmTapHonorsBusyAndExplicitDisabledState() {
        var confirmations = 0
        val busy = mutableStateOf(true)
        val confirmEnabled = mutableStateOf(true)

        compose.setContent {
            DCompanyTheme {
                FormDialog(
                    title = CONFIRM_GUARD_DIALOG_TITLE,
                    confirmLabel = "Save",
                    busy = busy.value,
                    error = null,
                    onDismiss = {},
                    onConfirm = { confirmations++ },
                    confirmEnabled = confirmEnabled.value,
                ) {
                    Text("Confirm callback guard fixture")
                }
            }
        }

        val busyConfirm = compose.onNode(dialogButton("Save…")).assertIsDisplayed()
        injectAndroidTap(busyConfirm.touchCenterOnScreen())
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Busy confirm must suppress its callback", 0, confirmations)
            busy.value = false
            confirmEnabled.value = false
        }
        compose.waitForIdle()

        val disabledConfirm = compose.onNode(dialogButton("Save")).assertIsDisplayed()
        injectAndroidTap(disabledConfirm.touchCenterOnScreen())
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Explicitly disabled confirm must suppress its callback", 0, confirmations)
            confirmEnabled.value = true
        }
        compose.waitForIdle()

        val enabledConfirm = compose.onNode(dialogButton("Save")).assertIsDisplayed()
        injectAndroidTap(enabledConfirm.touchCenterOnScreen())
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Enabled confirm control must exercise the same raw tap path", 1, confirmations)
        }
    }

    @Test
    fun spaciousToCompactImeTransitionAcceptsNativeInputAcrossFields() {
        var sku by mutableStateOf("")
        var name by mutableStateOf("")
        var reorderAt by mutableStateOf("")
        var reorderQuantity by mutableStateOf("")

        compose.setContent {
            DCompanyTheme {
                FormDialog(
                    title = FOCUS_TRANSITION_DIALOG_TITLE,
                    confirmLabel = "Save",
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = {},
                ) {
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it },
                        label = { Text("SKU (e.g. MILK-1L, COFFEE-BEAN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(NAME_FOCUS_TRANSITION_TAG),
                    )
                    PickerField(
                        label = "Base unit",
                        selectedLabel = "g (grams)",
                        options = listOf("g" to "g (grams)"),
                        onSelect = {},
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuantityField(
                            value = reorderAt,
                            onValueChange = { reorderAt = it },
                            label = "Reorder at",
                            modifier = Modifier.weight(1f).testTag(LOWER_FOCUS_TRANSITION_TAG),
                        )
                        QuantityField(
                            value = reorderQuantity,
                            onValueChange = { reorderQuantity = it },
                            label = "Reorder qty",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        val dialog = compose.onNode(dialogPane(FOCUS_TRANSITION_DIALOG_TITLE))
            .assertIsDisplayed()
        dialog.assertSpaciousWithoutIme()
        val lowerField = compose.onNodeWithTag(LOWER_FOCUS_TRANSITION_TAG)
            .assertIsDisplayed()
        injectAndroidTap(lowerField.touchCenterOnScreen())
        compose.waitForIdle()
        lowerField.assertIsFocused()

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val confirm = compose.onNode(dialogButton("Save"))
            val cancel = compose.onNode(dialogButton("Cancel"))
            val lowerFieldEvidence = awaitStableRealImeEvidence(
                confirm,
                cancel,
                lowerField,
                dialog,
            )
            lowerFieldEvidence.assertRealSoftwareIme()
            lowerFieldEvidence.assertCompactDialogHeight()
            lowerField.assertIsFocused()

            injectAndroidKey(KeyEvent.KEYCODE_1)
            injectAndroidKey(KeyEvent.KEYCODE_2)
            compose.waitForIdle()
            lowerField.assertIsFocused().assertTextContains("12")

            val nameField = compose.onNodeWithTag(NAME_FOCUS_TRANSITION_TAG)
            nameField.performScrollTo().assertIsDisplayed()
            injectAndroidTap(nameField.touchCenterOnScreen())
            compose.waitForIdle()
            nameField.assertIsFocused()

            val nameFieldEvidence = awaitStableRealImeEvidence(
                confirm,
                cancel,
                nameField,
                dialog,
            )
            nameFieldEvidence.assertRealSoftwareIme()
            injectAndroidKey(KeyEvent.KEYCODE_A)
            injectAndroidKey(KeyEvent.KEYCODE_B)
            compose.waitForIdle()
            nameField.assertIsFocused().assertTextContains("ab")
            lowerField.assertTextContains("12")

            lowerField.performScrollTo().assertIsDisplayed()
            injectAndroidTap(lowerField.touchCenterOnScreen())
            compose.waitForIdle()
            lowerField.assertIsFocused()
            injectAndroidKey(KeyEvent.KEYCODE_3)
            compose.waitForIdle()
            lowerField.assertIsFocused().assertTextContains("123")
            nameField.assertTextContains("ab")

            injectAndroidBack()
            val hiddenEvidence = awaitStableHiddenImeEvidence(
                confirm,
                cancel,
                lowerField,
                dialog,
            )
            hiddenEvidence.assertSoftwareImeHidden()
            hiddenEvidence.assertSpaciousDialogHeight()
            dialog.assertIsDisplayed()
            lowerField.assertTextContains("123")
            nameField.assertTextContains("ab")

            injectAndroidTap(lowerField.touchCenterOnScreen())
            val reopenedEvidence = awaitStableRealImeEvidence(
                confirm,
                cancel,
                lowerField,
                dialog,
            )
            reopenedEvidence.assertRealSoftwareIme()
            lowerField.assertIsFocused().assertTextContains("123")
            nameField.assertTextContains("ab")
            saveEvidence("form-dialog-native-focus-transition.png", reopenedEvidence)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    @Test
    fun spaciousLongBodyRevealsNewErrorAndKeepsBottomReachable() {
        var error by mutableStateOf<String?>(null)

        compose.setContent {
            DCompanyTheme {
                FormDialog(
                    title = LATE_ERROR_DIALOG_TITLE,
                    confirmLabel = "Save",
                    busy = false,
                    error = error,
                    onDismiss = {},
                    onConfirm = {},
                ) {
                    repeat(20) { index -> Text("Long form row ${index + 1}") }
                    Text("Long form bottom", modifier = Modifier.testTag(LONG_BODY_BOTTOM_TAG))
                }
            }
        }

        val dialog = compose.onNode(dialogPane(LATE_ERROR_DIALOG_TITLE)).assertIsDisplayed()
        dialog.assertSpaciousWithoutIme()
        val scroll = compose.onNode(hasScrollAction())
        val bottom = compose.onNodeWithTag(LONG_BODY_BOTTOM_TAG)
        bottom.performScrollTo().assertIsDisplayed()
        bottom.assertWhollyVisible("Long spacious form bottom")
        val bottomPosition = scroll.verticalScrollPosition()
        assertTrue("Long spacious form must have a real scroll range", bottomPosition > 0f)

        compose.runOnIdle { error = LATE_SERVER_ERROR }
        compose.waitForIdle()
        val lateError = compose.onNodeWithText(LATE_SERVER_ERROR).assertIsDisplayed()
        lateError.assertWhollyVisible("New server error")
        assertTrue(
            "A newly introduced error must return the main scroll owner to the top",
            scroll.verticalScrollPosition() <= 1f,
        )

        bottom.performScrollTo().assertIsDisplayed()
        bottom.assertWhollyVisible("Long spacious form bottom after error")
    }

    @Test
    fun narrowLongErrorKeepsWrappedActionsAboveRealSoftwareIme() {
        var quantity by mutableStateOf("")

        compose.setContent {
            DCompanyTheme {
                FormDialog(
                    title = NARROW_DIALOG_TITLE,
                    confirmLabel = "Queue adjustment",
                    busy = false,
                    error = LONG_VALIDATION_ERROR,
                    onDismiss = {},
                    onConfirm = {},
                    width = 260.dp,
                ) {
                    Text(
                        NARROW_BODY_START,
                        modifier = Modifier.testTag(NARROW_BODY_START_TAG),
                    )
                    Text(
                        NARROW_BODY_WARNING,
                        modifier = Modifier.testTag(NARROW_BODY_WARNING_TAG),
                    )
                    QuantityField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = "Correction in kg",
                        allowNegative = true,
                        modifier = Modifier.fillMaxWidth().testTag(NARROW_QUANTITY_TAG),
                    )
                    Text(
                        NARROW_BODY_END,
                        modifier = Modifier.testTag(NARROW_BODY_END_TAG),
                    )
                }
            }
        }

        val quantityField = compose.onNodeWithTag(NARROW_QUANTITY_TAG)
        quantityField.performScrollTo().assertIsDisplayed()
            .performClick().assertIsFocused()
        val error = compose.onNodeWithText(LONG_VALIDATION_ERROR)

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val confirm = compose.onNode(dialogButton("Queue adjustment"))
            val cancel = compose.onNode(dialogButton("Cancel"))
            val dialog = compose.onNode(dialogPane(NARROW_DIALOG_TITLE))
            val initialEvidence = awaitStableRealImeEvidence(confirm, cancel, error, dialog)
            initialEvidence.assertRealSoftwareIme()
            quantityField.assertIsFocused()

            injectAndroidKey(KeyEvent.KEYCODE_1)
            injectAndroidKey(KeyEvent.KEYCODE_2)
            compose.waitForIdle()
            quantityField.assertIsFocused().assertTextContains("12")

            error.performScrollTo().assertIsDisplayed()
            val evidence = awaitStableRealImeEvidence(confirm, cancel, error, dialog)
            saveEvidence("form-dialog-narrow-long-error-ime.png", evidence)

            evidence.assertRealSoftwareIme()
            quantityField.assertIsFocused().assertTextContains("12")
            evidence.confirm.assertCompleteTouchTargetAbove(evidence, "Queue adjustment")
            evidence.cancel.assertCompleteTouchTargetAbove(evidence, "Cancel")
            evidence.assertActionsWrapWithoutOverlap()
            error.assertUnclippedAboveActions(evidence)

            val scroll = compose.onNode(hasScrollAction())
            val start = compose.onNodeWithTag(NARROW_BODY_START_TAG)
            start.performScrollTo().assertIsDisplayed()
            val startBounds = start.assertWhollyVisible("Narrow body start marker")
            val startScrollPosition = scroll.verticalScrollPosition()

            val warning = compose.onNodeWithTag(NARROW_BODY_WARNING_TAG)
            warning.performScrollTo().assertIsDisplayed()
            val warningBounds = warning.elementBoundsOnScreen()
            assertTrue(
                "Scrollable body viewport must fit one complete rendered marker line: " +
                    "warning=$warningBounds marker=$startBounds evidence=$evidence",
                warningBounds.clippedBounds.height >= startBounds.visualBounds.height - 1f,
            )

            quantityField.performScrollTo().assertIsDisplayed().assertIsFocused()
            val searchedScrollPositions = mutableListOf(scroll.verticalScrollPosition())
            val manualGlyphPairs = mutableListOf<String>()
            for (stage in 0..MAX_EDITABLE_REVEAL_SWIPES) {
                quantityField.assertIsFocused().assertTextContains("12")
                val value12Evidence = awaitStableRealImeEvidence(
                    confirm,
                    cancel,
                    quantityField,
                    dialog,
                )
                value12Evidence.assertRealSoftwareIme()
                val value12Screenshot = saveEvidence(
                    "form-dialog-narrow-editable-stage-$stage-value-12.png",
                    value12Evidence,
                )
                val pairedScrollPosition = scroll.verticalScrollPosition()

                quantityField.performTextReplacement("13")
                quantityField.assertIsFocused().assertTextContains("13")
                val value13Evidence = awaitStableRealImeEvidence(
                    confirm,
                    cancel,
                    quantityField,
                    dialog,
                )
                value13Evidence.assertRealSoftwareIme()
                val value13Screenshot = saveEvidence(
                    "form-dialog-narrow-editable-stage-$stage-value-13.png",
                    value13Evidence,
                )
                val afterReplacementScrollPosition = scroll.verticalScrollPosition()
                assertTrue(
                    "Paired 12/13 screenshots must retain the same body scroll position: " +
                        "before=$pairedScrollPosition after=$afterReplacementScrollPosition",
                    abs(pairedScrollPosition - afterReplacementScrollPosition) <= 1f,
                )
                manualGlyphPairs +=
                    "stage=$stage scroll=$pairedScrollPosition " +
                    "12=${value12Screenshot.absolutePath} 13=${value13Screenshot.absolutePath}"

                quantityField.performTextReplacement("12")
                quantityField.assertIsFocused().assertTextContains("12")
                if (stage < MAX_EDITABLE_REVEAL_SWIPES) {
                    scroll.performTouchInput { swipeUp(durationMillis = 250) }
                    compose.waitForIdle()
                    searchedScrollPositions += scroll.verticalScrollPosition()
                }
            }
            recordManualGlyphGate(manualGlyphPairs)

            val end = compose.onNodeWithTag(NARROW_BODY_END_TAG)
            end.performScrollTo().assertIsDisplayed()
            val endBounds = end.assertWhollyVisible("Narrow body end marker")
            val endScrollPosition = scroll.verticalScrollPosition()
            assertTrue(
                "End marker must require a later scroll position: " +
                    "start=$startScrollPosition end=$endScrollPosition",
                endScrollPosition > startScrollPosition,
            )
            Log.i(
                LOG_TAG,
                "Narrow body reachability: start=$startBounds@$startScrollPosition; " +
                    "end=$endBounds@$endScrollPosition; " +
                    "swipes=$searchedScrollPositions",
            )
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private data class ControlBounds(
        val visualBounds: Rect,
        val clippedVisualBounds: Rect,
        val touchBounds: Rect,
    )

    private data class ElementBounds(
        val visualBounds: Rect,
        val clippedBounds: Rect,
    )

    private data class EdgeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class ImeEvidence(
        val confirm: ControlBounds,
        val cancel: ControlBounds,
        val resolvedContentBounds: Rect,
        val resolvedContentClippedBounds: Rect,
        val dialogBounds: Rect,
        val dialogClippedBounds: Rect,
        val imeBounds: AndroidRect?,
        val imeInsetBottom: Int,
        val imeVisibleByInsets: Boolean,
        val statusBarsInsets: EdgeInsets,
        val navigationBarsInsets: EdgeInsets,
        val systemBarsInsets: EdgeInsets,
        val systemGesturesInsets: EdgeInsets,
        val visibleFrame: AndroidRect,
        val safeParentBoundsInRoot: Rect,
        val rootBoundsOnScreen: AndroidRect,
        val rootOriginInWindowX: Int,
        val rootOriginInWindowY: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val density: Float,
        val windowFocused: Boolean,
        val layoutPending: Boolean,
    ) {
        val readyForCapture: Boolean
            get() = imeBounds != null && !imeBounds.isEmpty && imeInsetBottom > 0 &&
                imeVisibleByInsets && windowFocused && !layoutPending

        val readyWithoutIme: Boolean
            get() = imeBounds == null && imeInsetBottom == 0 && !imeVisibleByInsets &&
                windowFocused && !layoutPending

        val formDialogAvailableHeightDp: Float
            get() = visibleFrame.height() / density - 2f * Spacing.md.value

        fun assertRealSoftwareIme() {
            assertTrue("Expected a TYPE_INPUT_METHOD accessibility window: $this", imeBounds != null)
            assertTrue("Expected non-empty software IME bounds: $this", imeBounds?.isEmpty == false)
            assertTrue("Expected WindowInsetsCompat.Type.ime() to be visible: $this", imeVisibleByInsets)
            assertTrue("Expected a positive real IME inset: $this", imeInsetBottom > 0)
        }

        fun assertSoftwareImeHidden() {
            assertTrue("Expected no TYPE_INPUT_METHOD accessibility window: $this", imeBounds == null)
            assertTrue("Expected WindowInsetsCompat.Type.ime() to be hidden: $this", !imeVisibleByInsets)
            assertTrue("Expected no residual IME inset: $this", imeInsetBottom == 0)
            assertTrue("Expected the dialog window to retain focus: $this", windowFocused)
            assertTrue("Expected the hidden-IME layout to be settled: $this", !layoutPending)
        }

        fun assertCompactDialogHeight() {
            assertTrue(
                "Real IME must move the fixture below FormDialog's compact threshold: $this",
                formDialogAvailableHeightDp < 520f,
            )
        }

        fun assertSpaciousDialogHeight() {
            assertTrue(
                "Hidden IME must return the fixture above FormDialog's compact threshold: $this",
                formDialogAvailableHeightDp >= 520f,
            )
        }

        fun assertResolvedContentVisible() {
            val ime = checkNotNull(imeBounds)
            val tolerancePx = 1f
            assertTrue(
                "Resolved adjustment preview must not be clipped: $this",
                abs(resolvedContentBounds.left - resolvedContentClippedBounds.left) <= tolerancePx &&
                    abs(resolvedContentBounds.top - resolvedContentClippedBounds.top) <= tolerancePx &&
                    abs(resolvedContentBounds.right - resolvedContentClippedBounds.right) <= tolerancePx &&
                    abs(resolvedContentBounds.bottom - resolvedContentClippedBounds.bottom) <= tolerancePx,
            )
            assertTrue(
                "Resolved adjustment preview must remain above the software IME: $this",
                resolvedContentBounds.bottom <= ime.top + tolerancePx,
            )
        }

        fun assertActionsWrapWithoutOverlap() {
            val tolerancePx = 1f
            val verticalOverlap = minOf(
                confirm.visualBounds.bottom,
                cancel.visualBounds.bottom,
            ) - maxOf(
                confirm.visualBounds.top,
                cancel.visualBounds.top,
            )
            assertTrue(
                "Narrow dialog actions must wrap onto separate rows: $this",
                verticalOverlap <= tolerancePx,
            )
            assertTrue(
                "Narrow dialog must honor its requested 260dp maximum width: $this",
                dialogBounds.width <= 260f * density + tolerancePx,
            )
        }
    }

    private fun ControlBounds.assertCompleteTouchTargetAbove(evidence: ImeEvidence, label: String) {
        val ime = checkNotNull(evidence.imeBounds)
        val minimumTouchPx = 48f * evidence.density
        val tolerancePx = 1f
        assertTrue(
            "$label must expose a complete 48dp touch target: touchBounds=$touchBounds " +
                "minimumPx=$minimumTouchPx",
            touchBounds.width >= minimumTouchPx - tolerancePx &&
                touchBounds.height >= minimumTouchPx - tolerancePx,
        )
        assertTrue(
            "$label visual control must not be clipped: visualBounds=$visualBounds " +
                "clippedVisualBounds=$clippedVisualBounds",
            abs(visualBounds.left - clippedVisualBounds.left) <= tolerancePx &&
                abs(visualBounds.top - clippedVisualBounds.top) <= tolerancePx &&
                abs(visualBounds.right - clippedVisualBounds.right) <= tolerancePx &&
                abs(visualBounds.bottom - clippedVisualBounds.bottom) <= tolerancePx,
        )
        assertTrue(
            "$label visual control must be wholly above the software IME: " +
                "visualBounds=$visualBounds ime=$ime",
            visualBounds.bottom <= ime.top + tolerancePx,
        )
        assertTrue(
            "$label expanded touch target must be wholly above the software IME: " +
                "touchBounds=$touchBounds ime=$ime",
            touchBounds.bottom <= ime.top + tolerancePx,
        )
        assertTrue(
            "$label must remain inside the visible dialog window: " +
                "touchBounds=$touchBounds frame=${evidence.visibleFrame}",
            touchBounds.left >= evidence.visibleFrame.left - tolerancePx &&
                touchBounds.top >= evidence.visibleFrame.top - tolerancePx &&
                touchBounds.right <= evidence.visibleFrame.right + tolerancePx &&
                touchBounds.bottom <= evidence.visibleFrame.bottom + tolerancePx,
        )
    }

    private fun dialogButton(label: String): SemanticsMatcher =
        hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button,
        )

    private fun dialogPane(title: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    /** Waits only for stable Android window state; pass/fail geometry is asserted after capture. */
    private fun awaitStableRealImeEvidence(
        confirm: SemanticsNodeInteraction,
        cancel: SemanticsNodeInteraction,
        resolvedContent: SemanticsNodeInteraction,
        dialog: SemanticsNodeInteraction,
    ): ImeEvidence {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        compose.waitForIdle()
        var previous: ImeEvidence? = null
        var stableSamples = 0
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                awaitAndroidFrame()
                val current = readImeEvidence(confirm, cancel, resolvedContent, dialog)
                if (current != previous) Log.i(LOG_TAG, "IME evidence: $current")
                stableSamples = if (current.readyForCapture && current == previous) {
                    stableSamples + 1
                } else {
                    0
                }
                previous = current
                stableSamples >= 2
            }
        } catch (failure: Throwable) {
            previous?.let { saveEvidence("inventory-adjustment-ime-timeout.png", it) }
            throw failure
        }
        return checkNotNull(previous) { "No IME/window evidence was observed" }
    }

    /** Waits for both IME removal and the resulting spacious dialog geometry to settle. */
    private fun awaitStableHiddenImeEvidence(
        confirm: SemanticsNodeInteraction,
        cancel: SemanticsNodeInteraction,
        resolvedContent: SemanticsNodeInteraction,
        dialog: SemanticsNodeInteraction,
    ): ImeEvidence {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        compose.waitForIdle()
        var previous: ImeEvidence? = null
        var stableSamples = 0
        compose.waitUntil(timeoutMillis = 5_000) {
            awaitAndroidFrame()
            val current = readImeEvidence(confirm, cancel, resolvedContent, dialog)
            if (current != previous) Log.i(LOG_TAG, "Hidden IME evidence: $current")
            stableSamples = if (current.readyWithoutIme && current == previous) {
                stableSamples + 1
            } else {
                0
            }
            previous = current
            stableSamples >= 2
        }
        return checkNotNull(previous) { "No hidden-IME/window evidence was observed" }
    }

    private fun readImeEvidence(
        confirm: SemanticsNodeInteraction,
        cancel: SemanticsNodeInteraction,
        resolvedContent: SemanticsNodeInteraction,
        dialog: SemanticsNodeInteraction,
    ): ImeEvidence {
        val confirmNode = confirm.fetchSemanticsNode()
        val cancelNode = cancel.fetchSemanticsNode()
        val resolvedContentNode = resolvedContent.fetchSemanticsNode()
        val dialogNode = dialog.fetchSemanticsNode()
        val root = confirmNode.root as ViewRootForTest
        check(cancelNode.root === confirmNode.root) { "Dialog actions must share one Android window" }
        check(resolvedContentNode.root === confirmNode.root) {
            "Resolved adjustment content must share the dialog window"
        }
        check(dialogNode.root === confirmNode.root) { "Dialog surface must share the dialog window" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val imeBounds = automation.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { AndroidRect().also(it::getBoundsInScreen) }

        return compose.runOnIdle {
            val view = root.view
            val screenOrigin = IntArray(2).also(view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(view::getLocationInWindow)
            val offsetX = (screenOrigin[0] - windowOrigin[0]).toFloat()
            val offsetY = (screenOrigin[1] - windowOrigin[1]).toFloat()
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val systemGestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val visibleFrame = AndroidRect().also(view::getWindowVisibleDisplayFrame)
            ImeEvidence(
                confirm = confirmNode.toControlBounds(
                    screenOrigin[0].toFloat(),
                    screenOrigin[1].toFloat(),
                    offsetX,
                    offsetY,
                ),
                cancel = cancelNode.toControlBounds(
                    screenOrigin[0].toFloat(),
                    screenOrigin[1].toFloat(),
                    offsetX,
                    offsetY,
                ),
                resolvedContentBounds = resolvedContentNode.visualBoundsOnScreen(),
                resolvedContentClippedBounds = resolvedContentNode.boundsInWindow.translate(
                    offsetX,
                    offsetY,
                ),
                dialogBounds = dialogNode.visualBoundsOnScreen(),
                dialogClippedBounds = dialogNode.boundsInWindow.translate(offsetX, offsetY),
                imeBounds = imeBounds,
                imeInsetBottom = ime.bottom,
                imeVisibleByInsets = insets.isVisible(WindowInsetsCompat.Type.ime()),
                statusBarsInsets = statusBars.toEvidence(),
                navigationBarsInsets = navigationBars.toEvidence(),
                systemBarsInsets = systemBars.toEvidence(),
                systemGesturesInsets = systemGestures.toEvidence(),
                visibleFrame = visibleFrame,
                safeParentBoundsInRoot = Rect(
                    (visibleFrame.left - screenOrigin[0]).toFloat(),
                    (visibleFrame.top - screenOrigin[1]).toFloat(),
                    (visibleFrame.right - screenOrigin[0]).toFloat(),
                    (visibleFrame.bottom - screenOrigin[1]).toFloat(),
                ),
                rootBoundsOnScreen = AndroidRect(
                    screenOrigin[0],
                    screenOrigin[1],
                    screenOrigin[0] + view.width,
                    screenOrigin[1] + view.height,
                ),
                rootOriginInWindowX = windowOrigin[0],
                rootOriginInWindowY = windowOrigin[1],
                displayWidth = view.resources.displayMetrics.widthPixels,
                displayHeight = view.resources.displayMetrics.heightPixels,
                density = view.resources.displayMetrics.density,
                windowFocused = view.hasWindowFocus(),
                layoutPending = root.hasPendingMeasureOrLayout || view.isLayoutRequested,
            )
        }
    }

    private fun androidx.core.graphics.Insets.toEvidence() = EdgeInsets(left, top, right, bottom)

    private fun SemanticsNodeInteraction.verticalScrollPosition(): Float =
        fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun SemanticsNodeInteraction.elementBoundsOnScreen(): ElementBounds {
        val node = fetchSemanticsNode()
        val root = node.root as ViewRootForTest
        return compose.runOnIdle {
            val view = root.view
            val screenOrigin = IntArray(2).also(view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(view::getLocationInWindow)
            ElementBounds(
                visualBounds = node.visualBoundsOnScreen(),
                clippedBounds = node.boundsInWindow.translate(
                    (screenOrigin[0] - windowOrigin[0]).toFloat(),
                    (screenOrigin[1] - windowOrigin[1]).toFloat(),
                ),
            )
        }
    }

    private fun SemanticsNodeInteraction.assertWhollyVisible(label: String): ElementBounds {
        val bounds = elementBoundsOnScreen()
        val tolerancePx = 1f
        assertTrue(
            "$label must be wholly visible: $bounds",
            abs(bounds.visualBounds.left - bounds.clippedBounds.left) <= tolerancePx &&
                abs(bounds.visualBounds.top - bounds.clippedBounds.top) <= tolerancePx &&
                abs(bounds.visualBounds.right - bounds.clippedBounds.right) <= tolerancePx &&
                abs(bounds.visualBounds.bottom - bounds.clippedBounds.bottom) <= tolerancePx,
        )
        return bounds
    }

    private fun SemanticsNodeInteraction.assertUnclippedAboveActions(evidence: ImeEvidence) {
        val node = fetchSemanticsNode()
        val root = node.root as ViewRootForTest
        compose.runOnIdle {
            val view = root.view
            val screenOrigin = IntArray(2).also(view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(view::getLocationInWindow)
            val offsetX = (screenOrigin[0] - windowOrigin[0]).toFloat()
            val offsetY = (screenOrigin[1] - windowOrigin[1]).toFloat()
            val visualBounds = node.visualBoundsOnScreen()
            val clippedBounds = node.boundsInWindow.translate(offsetX, offsetY)
            val tolerancePx = 1f
            assertTrue(
                "Long validation error must not be clipped: visual=$visualBounds " +
                    "clipped=$clippedBounds evidence=$evidence",
                abs(visualBounds.left - clippedBounds.left) <= tolerancePx &&
                    abs(visualBounds.top - clippedBounds.top) <= tolerancePx &&
                    abs(visualBounds.right - clippedBounds.right) <= tolerancePx &&
                    abs(visualBounds.bottom - clippedBounds.bottom) <= tolerancePx,
            )
            assertTrue(
                "Long validation error must remain above the fixed actions: " +
                    "error=$visualBounds evidence=$evidence",
                visualBounds.bottom <= minOf(
                    evidence.confirm.visualBounds.top,
                    evidence.cancel.visualBounds.top,
                ) + tolerancePx,
            )
        }
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.toControlBounds(
        rootScreenX: Float,
        rootScreenY: Float,
        offsetX: Float,
        offsetY: Float,
    ): ControlBounds {
        return ControlBounds(
            visualBounds = visualBoundsOnScreen(),
            clippedVisualBounds = boundsInWindow.translate(offsetX, offsetY),
            touchBounds = touchBoundsInRoot.translate(rootScreenX, rootScreenY),
        )
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.visualBoundsOnScreen(): Rect {
        val position = positionOnScreen
        return Rect(
            position.x,
            position.y,
            position.x + size.width,
            position.y + size.height,
        )
    }

    private fun SemanticsNodeInteraction.touchCenterOnScreen(): Offset {
        val node = fetchSemanticsNode()
        val root = node.root as ViewRootForTest
        return compose.runOnIdle {
            val origin = IntArray(2).also(root.view::getLocationOnScreen)
            node.touchBoundsInRoot.translate(
                origin[0].toFloat(),
                origin[1].toFloat(),
            ).center
        }
    }

    private fun backdropPointOutside(dialog: SemanticsNodeInteraction): Offset {
        val node = dialog.fetchSemanticsNode()
        val root = node.root as ViewRootForTest
        return compose.runOnIdle {
            val view = root.view
            val visibleFrame = AndroidRect().also(view::getWindowVisibleDisplayFrame)
            val dialogBounds = node.visualBoundsOnScreen()
            val margin = 4f * view.resources.displayMetrics.density
            val candidates = listOf(
                Offset(visibleFrame.left + margin, dialogBounds.center.y),
                Offset(visibleFrame.right - margin, dialogBounds.center.y),
                Offset(dialogBounds.center.x, visibleFrame.top + margin),
                Offset(dialogBounds.center.x, visibleFrame.bottom - margin),
            )
            candidates.firstOrNull { point ->
                point.x >= visibleFrame.left && point.x <= visibleFrame.right &&
                    point.y >= visibleFrame.top && point.y <= visibleFrame.bottom &&
                    !dialogBounds.contains(point)
            } ?: error(
                "No tappable backdrop point outside dialog=$dialogBounds frame=$visibleFrame",
            )
        }
    }

    private fun saveEvidence(fileName: String, evidence: ImeEvidence): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidenceDir = checkNotNull(instrumentation.targetContext.getExternalFilesDir("test-evidence"))
        check(evidenceDir.exists() || evidenceDir.mkdirs()) {
            "Could not create instrumentation evidence directory: $evidenceDir"
        }
        val screenshot = File(evidenceDir, fileName)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not provide the required IME screenshot"
        }
        try {
            screenshot.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    "Android failed to encode IME screenshot: $screenshot"
                }
            }
        } finally {
            bitmap.recycle()
        }
        val result = "screenshot=${screenshot.absolutePath}; evidence=$evidence"
        Log.i(LOG_TAG, result)
        instrumentation.addResults(Bundle().apply { putString("inventoryAdjustmentImeEvidence", result) })
        return screenshot
    }

    private fun recordManualGlyphGate(pairs: List<String>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result =
            "MANUAL REVIEW REQUIRED: confirm one same-position screenshot pair shows the " +
                "complete entered values 12 then 13; no Compose coordinate oracle is used. " +
                pairs.joinToString(separator = "; ")
        Log.w(LOG_TAG, result)
        instrumentation.addResults(Bundle().apply {
            putString("inventoryAdjustmentManualGlyphGate", result)
        })
    }

    private fun awaitAndroidFrame() {
        val nextFrame = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback { nextFrame.countDown() }
        }
        check(nextFrame.await(2, TimeUnit.SECONDS)) { "Android did not render another frame" }
    }

    private fun injectAndroidTap(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                x,
                y,
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                check(automation.injectInputEvent(event, true)) { "Android rejected $action touch event" }
            } finally {
                event.recycle()
            }
        }
    }

    private fun injectAndroidTap(point: Offset) = injectAndroidTap(point.x, point.y)

    private fun injectAndroidBack() {
        injectAndroidKey(KeyEvent.KEYCODE_BACK)
    }

    private fun injectAndroidKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private fun SemanticsNodeInteraction.awaitDialogWindowFocus() {
        val root = fetchSemanticsNode().root as ViewRootForTest
        compose.waitUntil(timeoutMillis = 5_000) {
            root.view.hasWindowFocus()
        }
    }

    private fun SemanticsNodeInteraction.assertSpaciousWithoutIme() {
        val node = fetchSemanticsNode()
        val root = node.root as ViewRootForTest
        compose.runOnIdle {
            val view = root.view
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            val visibleFrame = AndroidRect().also(view::getWindowVisibleDisplayFrame)
            val contentHeightDp = visibleFrame.height() / view.resources.displayMetrics.density -
                2f * Spacing.md.value
            assertTrue(
                "Fixture must start without an IME before focusing the lower field: " +
                    "frame=$visibleFrame dialog=${node.visualBoundsOnScreen()}",
                !insets.isVisible(WindowInsetsCompat.Type.ime()),
            )
            assertTrue(
                "Fixture must start in spacious FormDialog mode: " +
                    "contentHeightDp=$contentHeightDp frame=$visibleFrame",
                contentHeightDp >= 520f,
            )
        }
    }

    private companion object {
        const val BACK_DIALOG_TITLE = "Android Back dismissal"
        const val BACKDROP_DIALOG_TITLE = "Backdrop dismissal"
        const val CONFIRM_GUARD_DIALOG_TITLE = "Confirm callback guards"
        const val FOCUS_TRANSITION_DIALOG_TITLE = "Native input focus transition"
        const val INTERIOR_BLANK_TAG = "form-dialog-interior-blank"
        const val LATE_ERROR_DIALOG_TITLE = "Long form server error"
        const val LATE_SERVER_ERROR =
            "The server rejected this form. Review the highlighted values and try again."
        const val LOWER_FOCUS_TRANSITION_TAG = "form-dialog-lower-focus-transition"
        const val LONG_BODY_BOTTOM_TAG = "form-dialog-long-body-bottom"
        const val LONG_VALIDATION_ERROR =
            "The correction cannot be queued until the branch count and reason are reviewed."
        const val NARROW_BODY_END = "End"
        const val NARROW_BODY_END_TAG = "form-dialog-narrow-body-end"
        const val NARROW_BODY_START = "Start"
        const val NARROW_BODY_START_TAG = "form-dialog-narrow-body-start"
        const val NARROW_BODY_WARNING =
            "Review the branch count before queueing this correction."
        const val NARROW_BODY_WARNING_TAG = "form-dialog-narrow-body-warning"
        const val NARROW_DIALOG_TITLE = "Review count correction"
        const val NARROW_QUANTITY_TAG = "form-dialog-narrow-quantity"
        const val MAX_EDITABLE_REVEAL_SWIPES = 4
        const val QUANTITY_TAG = "inventory-adjustment-quantity"
        const val PREVIEW_TAG = "inventory-adjustment-preview"
        const val PREVIEW_WARNING =
            "That exceeds this branch's recorded balance and cannot be queued."
        const val NAME_FOCUS_TRANSITION_TAG = "form-dialog-name-focus-transition"
        const val LOG_TAG = "InventoryAdjustmentIme"
    }
}

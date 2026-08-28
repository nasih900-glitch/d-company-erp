package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Regression coverage for the 960 x 600dp Shift page's bounded content area. */
class ShiftCompactUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingFloatAndPrimaryActionRemainReachableInCompactShiftPanels() {
        var submittedMinor: Long? = null

        compose.setContent {
            DCompanyTheme {
                // The app shell and summary row leave approximately this much
                // vertical space on the 960 x 600dp production layout.
                Box(Modifier.width(872.dp).height(360.dp)) {
                    CompactShiftPanels(
                        stateIdentity = "closed",
                        currentPanel = {
                            OpenShiftForm(
                                online = true,
                                busy = false,
                                canOpen = true,
                                blockedByRejectedShift = false,
                                onOpenShift = { submittedMinor = it },
                            )
                        },
                        historyPanel = {
                            Box(Modifier.height(280.dp)) { Text("Past shifts") }
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performScrollTo()
            .assertIsDisplayed()
            .performTextReplacement("210.50")

        compose.onNodeWithText("Open shift with ₹210.50")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { assertEquals(21_050L, submittedMinor) }
        compose.onNodeWithContentDescription("Opening float (₹), 210.50")
            .assertIsNotFocused()
    }

    @Test
    fun newShiftIdentityStartsReplacementPanelAtItsTop() {
        compose.setContent {
            DCompanyTheme {
                var stateIdentity by remember { mutableStateOf("closed") }
                Box(Modifier.width(872.dp).height(360.dp)) {
                    CompactShiftPanels(
                        stateIdentity = stateIdentity,
                        currentPanel = {
                            if (stateIdentity == "closed") {
                                OpenShiftForm(
                                    online = true,
                                    busy = false,
                                    canOpen = true,
                                    blockedByRejectedShift = false,
                                    onOpenShift = { stateIdentity = "local:shift-1" },
                                )
                            } else {
                                // Mirrors the close panel: its heading must be
                                // visible even though the body is much taller
                                // than this compact viewport.
                                Column {
                                    Text("Close current shift")
                                    Spacer(Modifier.height(700.dp))
                                    Text("Close panel end")
                                }
                            }
                        },
                        historyPanel = {
                            Box(Modifier.height(280.dp)) { Text("Past shifts") }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Open shift with ₹0.00")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText("Close current shift").assertIsDisplayed()
    }

    @Test
    fun denominationInputsRemainReachableInsideCompactShiftScroll() {
        compose.setContent {
            DCompanyTheme {
                val counts = remember { mutableStateMapOf<Long, String>() }
                Box(Modifier.width(872.dp).height(360.dp)) {
                    CompactShiftPanels(
                        stateIdentity = "open:shift-1",
                        currentPanel = {
                            Column {
                                Text("Count the drawer")
                                DenominationCountGrid(
                                    compactLayout = true,
                                    counts = counts,
                                    onCountChange = { note, value -> counts[note] = value },
                                    enabled = true,
                                )
                                Text("Close shift")
                            }
                        },
                        historyPanel = {
                            Box(Modifier.height(280.dp)) { Text("Past shifts") }
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Count of ₹500 notes")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performTextReplacement("1")

        compose.onNodeWithText("₹500.00")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Close shift")
            .performScrollTo()
            .assertIsDisplayed()
    }
}

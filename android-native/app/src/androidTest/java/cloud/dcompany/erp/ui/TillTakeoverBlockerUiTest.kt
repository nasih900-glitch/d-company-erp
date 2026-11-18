package cloud.dcompany.erp.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import cloud.dcompany.erp.core.handover.*
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TillTakeoverBlockerUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun blockedDialogExplainsExistingWorkAndKeepsCashierWithoutApproving() {
        val blocker = TillPreparationBlocker(TillPreparationStage.LOCAL_BEFORE_SERVER, true, false, 0, 0, null,
            LocalHandoverWork(true, setOf(LocalHandoverWorkKind.ROOM), roomPendingCount = 2))
        compose.setContent { DCompanyTheme {
            TillTakeoverDialog(TillTakeoverUi("qa-shift", preparationBlocker = blocker), {}, {}, { _, _, _, _ -> error("Must not approve") })
        } }
        compose.onNodeWithText("2 saved tablet write(s)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep the current cashier", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("till-takeover-confirm").assertIsNotEnabled()
    }

    @Test fun normalHandoverPassesSelectedCashierAndBothInputsOnceThenClearsTheForm() {
        val state = TillTakeoverUi("qa-shift", revision = 1, people = listOf(TillOperatorCandidate("staff-b", "Staff B")))
        val submissions = mutableListOf<List<String>>()
        compose.setContent { DCompanyTheme {
            TillTakeoverDialog(state, {}, {}, { selectedState, selectedId, email, password ->
                assertEquals(state, selectedState)
                submissions += listOf(selectedId, email, password)
            })
        } }
        val confirm = compose.onNodeWithTag("till-takeover-confirm")
        confirm.assertIsNotEnabled()
        compose.onNodeWithText("Staff B").performClick()
        val email = compose.onNodeWithTag("till-takeover-email")
        val password = compose.onNodeWithTag("till-takeover-password")
        email.performScrollTo().performTextReplacement("b@example.test")
        confirm.assertIsNotEnabled()
        password.performScrollTo().performTextReplacement("synthetic-password")
        confirm.assertIsEnabled().performClick()
        val cleared = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        email.assert(cleared)
        password.assert(cleared)
        confirm.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(listOf("staff-b", "b@example.test", "synthetic-password")), submissions) }
    }
}

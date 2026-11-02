package cloud.dcompany.erp.ui.screens.staff.time

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.AppearancePreference
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaffTimeScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val user = "11111111-1111-4111-8111-111111111111"
    private val other = "55555555-5555-4555-8555-555555555555"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val entry = TimeAttendance("44444444-4444-4444-8444-444444444444", user, branch,
        "2026-11-02T08:00:00Z", "2026-11-02T09:00:00Z", 0, "app", "2026-11-02T09:00:00Z")
    private fun state(owner: Boolean = false) = StaffTimeState(user, "2026-11", owner,
        summary = StaffTimeSummary(user, "Synthetic worker", "2026-11", "Asia/Kolkata", "2026-11-02T09:00:00Z",
            emptyList(), 0, 0, 0, emptyList(), "draft"), storageReady = true, fresh = true,
        policyHistoryLoaded = true)
    private var clocks = 0; private var confirms = 0; private var retries = 0; private var selected: String? = null
    private fun render(initial: StaffTimeState, width: Int = 1100, height: Int = 700): MutableState<StaffTimeState> {
        val s = mutableStateOf(initial)
        compose.setContent { DCompanyTheme(AppearancePreference.Night) { Surface {
            Box(Modifier.requiredSize(width.dp, height.dp)) {
                StaffTimeContent(s.value, {}, { selected = it }, {}, {}, {}, { clocks++ }, {}, {},
                    { transform -> s.value = s.value.copy(draft = s.value.draft?.let(transform)) },
                    { s.value = s.value.copy(draft = null) }, { confirms++ }, { retries++ }, {})
            }
        } } }
        return s
    }
    @Test fun staffSeesOnlyOwnTimeWithNoPayOrPeopleAndClockReviewIsExplicit() {
        render(state())
        compose.onNodeWithText("My working time").assertIsDisplayed()
        compose.onNodeWithText("Draft pay").assertDoesNotExist()
        compose.onNodeWithTag("staff-time-choose-person").assertDoesNotExist()
        compose.onNodeWithTag("staff-time-clock").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clocks); assertEquals(0, confirms) }
    }
    @Test fun staleSnapshotDisablesClockAndRetainsServerFacts() {
        render(state().copy(fresh = false))
        compose.onNodeWithTag("staff-time-clock").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Closed presence: 0h 0m 0s").performScrollTo().assertIsDisplayed()
    }
    @Test fun ownerChooserIncludesDepartedHistoryWithoutUsingDirectoryUi() {
        render(state(true).copy(people = listOf(TimePerson(other, "Synthetic departed", "inactive", true))))
        compose.onNodeWithTag("staff-time-choose-person").performClick()
        compose.onNodeWithText("Synthetic departed · departed / history").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(other, selected) }
        compose.onNodeWithText("Choose one person").assertDoesNotExist()
    }
    @Test fun unknownBreakHistoryIsClearlyNotZeroConfirmedWork() {
        val a = state(true); render(a.copy(summary = a.summary!!.copy(entries = listOf(entry), closedGrossSeconds = 3600,
            closedWorkSeconds = null, reviewAttendanceIds = listOf(entry.id))))
        compose.onNodeWithText("History", substring = false).performClick()
        compose.onNodeWithText("Actual break: not confirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Owner review required").assertIsDisplayed()
        compose.onNodeWithText("Recorded break: 0 minutes").assertDoesNotExist()
    }
    @Test fun draftPayIsExplicitlyUnactivatedAndNewRevisionFieldsStayBlank() {
        val d = TimeEditDraft(TimeWriteKind.POLICY, user, "Synthetic worker", 0, effectiveFrom = "2026-11-02")
        val s = render(state(true))
        compose.onNodeWithText("Draft pay", substring = false).performClick()
        compose.onNodeWithText("Payable amount: not activated. Overtime pay: not activated.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { s.value = s.value.copy(draft = d) }
        compose.onNode(hasText("Draft monthly amount") and hasSetTextAction()).performScrollTo()
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("Cancel", substring = false).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertNull(s.value.draft); assertEquals(0, confirms) }
    }
    @Test fun clockOutRequiresOwnActualBreakEntryAndDoesNotPrefillPlan() {
        val d = TimeEditDraft(TimeWriteKind.SELF_OUT, user, "Synthetic worker", 0, attendance = entry.copy(clockOutAt = null))
        val s = render(state().copy(draft = d))
        val field = compose.onNode(hasText("Actual break in minutes — enter 0 if none") and hasSetTextAction())
        field.performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        field.performTextInput("0")
        compose.runOnIdle { assertEquals("0", s.value.draft?.breakText) }
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, confirms) }
    }
    @Test fun changedAuthorityUnknownRequestCannotBeReplayedOrDiscarded() {
        val i = StaffTimeIntent("66666666-6666-4666-8666-666666666666", user, company, branch, null, "b".repeat(64),
            user, "Synthetic worker", TimeWriteKind.SELF_IN, payload = StaffTimeJson.encodeToString(TimeSelfClockIn(branch)))
        render(state().copy(pending = PendingTimeAttempt(i), pendingAuthorityChanged = true))
        compose.onNodeWithText("Access or sign-in changed.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry exact request").assertDoesNotExist()
        compose.onNodeWithText("Review rejection").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, retries) }
    }
    @Test fun retryIsAlwaysConfirmedAndUsesOnlyFrozenCallback() {
        val i = StaffTimeIntent("66666666-6666-4666-8666-666666666666", user, company, branch, null, "b".repeat(64),
            user, "Synthetic worker", TimeWriteKind.SELF_IN, payload = StaffTimeJson.encodeToString(TimeSelfClockIn(branch)))
        render(state().copy(pending = PendingTimeAttempt(i)))
        compose.onNodeWithText("Retry exact request", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText("Retry the retained request?").assertIsDisplayed()
        compose.onNode(hasText("Retry exact request") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun compactViewportKeepsClockAndDialogCancelUsableAt48dp() {
        val s = render(state(), 500, 420)
        compose.onNodeWithTag("staff-time-content-list").performScrollToNode(hasTestTag("staff-time-clock"))
        compose.onNodeWithTag("staff-time-clock").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { s.value = s.value.copy(draft = TimeEditDraft(TimeWriteKind.SELF_OUT, user, "Synthetic worker", 0,
            attendance = entry.copy(clockOutAt = null))) }
        compose.onNodeWithText("Cancel", substring = false).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertNull(s.value.draft) }
    }
}

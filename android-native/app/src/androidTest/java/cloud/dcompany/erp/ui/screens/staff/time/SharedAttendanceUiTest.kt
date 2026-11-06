package cloud.dcompany.erp.ui.screens.staff.time

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SharedAttendanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private val terminal = "44444444-4444-4444-8444-444444444444"
    private val b = "55555555-5555-4555-8555-555555555555"
    private val row = SharedTimePerson(b, "Synthetic B", false, null)
    @Test fun nonOwnerSharedPickerDoesNotExposeSalaryAndPasswordCancelDoesNotWrite() {
        var state by mutableStateOf(SharedAttendanceState(people = listOf(row), ready = true))
        var sent = 0
        compose.setContent { MaterialTheme { SharedAttendanceCard(state, true, {}, {},
            { state = state.copy(selected = row) }, { state = state.copy(selected = null) },
            { _, _ -> sent++ }, { sent++ }, {}) } }
        compose.onNodeWithTag("shared-attendance-open").performClick()
        compose.onNodeWithTag("shared-person-$b").performClick()
        compose.onNodeWithTag("shared-attendance-password").performTextInput("synthetic-password")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, sent) }
        compose.onAllNodesWithText("Draft pay").assertCountEquals(0)
        compose.onNodeWithTag("shared-attendance-open").performClick()
        compose.onNodeWithTag("shared-person-$b").performClick()
        compose.onNodeWithTag("shared-attendance-confirm").assertIsNotEnabled()
    }
    @Test fun clockOutRequiresExplicitActualBreak() {
        val out = row.copy(onDuty = true, attendanceId = a)
        var submitted: String? = null
        compose.setContent { MaterialTheme { SharedAttendanceCard(SharedAttendanceState(selected = out, ready = true), true,
            {}, {}, {}, {}, { _, breaks -> submitted = breaks }, {}, {}) } }
        compose.onNodeWithTag("shared-attendance-password").performTextInput("synthetic-password")
        compose.onNodeWithTag("shared-attendance-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("shared-attendance-break").performTextInput("0")
        compose.onNodeWithTag("shared-attendance-confirm").performClick()
        compose.runOnIdle { assertEquals("0", submitted) }
    }
    @Test fun offlineCardNeverOffersAutomaticWriteOrCredentialPrompt() {
        var writes = 0
        compose.setContent { MaterialTheme { SharedAttendanceCard(SharedAttendanceState(), false,
            {}, {}, {}, {}, { _, _ -> writes++ }, { writes++ }, {}) } }
        compose.onNodeWithTag("shared-attendance-open").assertIsNotEnabled()
        compose.onAllNodesWithTag("shared-attendance-password").assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, writes) }
    }
    @Test fun separatePrivateStoresSurviveRecreationAndCannotReplaceOwnAttendance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = File(context.noBackupFilesDir.canonicalFile, "shared-qa-${UUID.randomUUID()}")
        check(parent.mkdir())
        val ownDir = File(parent, "own"); val sharedDir = File(parent, "shared")
        val scope = timeDigest(listOf(a, company, branch, terminal).joinToString("|"))
        val own = AtomicStaffTimePendingStore(ownDir)
        val shared = SharedAttendancePendingStore(sharedDir)
        val ownIntent = PendingTimeAttempt(StaffTimeIntent(UUID.randomUUID().toString(), a, company, branch, terminal,
            "a".repeat(64), a, "Synthetic A", TimeWriteKind.SELF_IN, payload = StaffTimeJson.encodeToString(TimeSelfClockIn(branch))))
        val sharedIntent = PendingTimeAttempt(StaffTimeIntent(UUID.randomUUID().toString(), a, company, branch, terminal,
            "a".repeat(64), b, row.name, TimeWriteKind.SHARED_IN, payload = StaffTimeJson.encodeToString(SharedTimeFields(b))))
        try {
            own.save(scope, ownIntent); shared.save(scope, sharedIntent)
            assertEquals(ownIntent, AtomicStaffTimePendingStore(ownDir).read(scope))
            assertEquals(sharedIntent, SharedAttendancePendingStore(sharedDir).read(scope))
            assertTrue(shared.hasPending(a, company, branch, terminal))
            assertFalse(shared.hasPending(b, company, branch, terminal))
            assertFalse(File(sharedDir, "$scope.json").readText().contains("password"))
            shared.complete(scope, sharedIntent.intent.key)
            assertFalse(shared.hasPending(a, company, branch, terminal))
            assertEquals(ownIntent, own.read(scope))
        } finally {
            listOf(ownDir, sharedDir).forEach { dir -> dir.listFiles()?.forEach { check(it.delete()) }; check(dir.delete()) }
            check(parent.delete())
        }
    }
}

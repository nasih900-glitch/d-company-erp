package cloud.dcompany.erp.ui.screens.staff.time

import java.util.UUID
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StaffTimeHistoryReadinessTest {
    private fun id() = UUID.randomUUID().toString()
    private fun attempt(shared: Boolean): PendingTimeAttempt {
        val actor = id(); val branch = id()
        return PendingTimeAttempt(StaffTimeIntent(id(), actor, id(), branch, if (shared) id() else null,
            "b".repeat(64), actor, "Synthetic history", if (shared) TimeWriteKind.SHARED_IN else TimeWriteKind.SELF_IN,
            payload = if (shared) StaffTimeJson.encodeToString(SharedTimeFields(actor)) else StaffTimeJson.encodeToString(TimeSelfClockIn(branch))), 422)
    }
    private fun name(entry: PendingTimeAttempt): String = entry.intent.let {
        timeDigest(listOf(it.actorId, it.companyId, it.branchId, it.terminalId.orEmpty()).joinToString("|")) + "-rejected-${it.key}.json"
    }
    @Test fun exactAcknowledgedRejectionHistoryIsRecognizedForBothPurposesOnly() {
        for (shared in listOf(false, true)) {
            val entry = attempt(shared); val raw = StaffTimeJson.encodeToString(entry)
            val purpose = if (shared) StaffTimePendingPurpose.SHARED else StaffTimePendingPurpose.PERSONAL_AND_OWNER
            assertTrue(acknowledgedStaffTimeHistory(name(entry), raw, purpose))
            assertFalse(acknowledgedStaffTimeHistory(name(entry), raw, StaffTimePendingPurpose.entries.single { it != purpose }))
        }
    }
    @Test fun activeBackupsUnknownNamesAndForgedScopeOrKeyCannotBeHistory() {
        val entry = attempt(false); val raw = StaffTimeJson.encodeToString(entry); val name = name(entry)
        for (bad in listOf(name + ".bak", name + ".new", "unknown-rejected-history.json", "f".repeat(64) + name.drop(64),
            name.replace(entry.intent.key, id()), name.substringBefore("-rejected") + ".json")) {
            assertFalse(bad, acknowledgedStaffTimeHistory(bad, raw, StaffTimePendingPurpose.PERSONAL_AND_OWNER))
        }
    }
    @Test fun unknownOutcomeMalformedPayloadAndNondefinitiveRejectionCannotBeHistory() {
        val entry = attempt(false); val name = name(entry)
        for (raw in listOf("{malformed", StaffTimeJson.encodeToString(entry.copy(rejectedStatus = null)),
            StaffTimeJson.encodeToString(entry.copy(rejectedStatus = 500)),
            StaffTimeJson.encodeToString(entry.copy(intent = entry.intent.copy(payload = "{}"))))) {
            assertFalse(acknowledgedStaffTimeHistory(name, raw, StaffTimePendingPurpose.PERSONAL_AND_OWNER))
        }
    }
}

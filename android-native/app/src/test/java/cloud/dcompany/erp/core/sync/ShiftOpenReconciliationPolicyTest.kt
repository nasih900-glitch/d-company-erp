package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftOpenReconciliationPolicyTest {
    private val shift = ShiftDetail(
        id = "shift-a",
        branchId = "branch-a",
        terminalId = "terminal-a",
        status = "open",
        openedAt = "2026-09-05T10:00:00Z",
    )

    @Test
    fun `open and close retain one causal local identity`() {
        val ids = shiftLifecycleActionIds("tablet-shift-123")

        assertEquals("shift-open:tablet-shift-123", ids.open)
        assertEquals("shift-close:tablet-shift-123", ids.close)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank causal local identity is rejected`() {
        shiftLifecycleActionIds("   ")
    }

    @Test
    fun `post migration protocol never permits heuristic linking`() {
        assertFalse(allowsLegacyShiftOpeningMatch(
            shift.copy(
                openingReceiptRecorded = true,
                openingProtocolRevision = 1,
            ),
            "terminal-a",
            "branch-a",
        ))
        // Web opens are intentionally unkeyed, but revision 1 still makes
        // their protocol vintage authoritative and blocks heuristic linking.
        assertFalse(allowsLegacyShiftOpeningMatch(
            shift.copy(
                openingReceiptRecorded = false,
                openingProtocolRevision = 1,
            ),
            "terminal-a",
            "branch-a",
        ))
        // A response from a pre-0069 backend, and a row migrated from that
        // schema, has no protocol revision and is the only legacy case.
        assertTrue(allowsLegacyShiftOpeningMatch(shift, "terminal-a", "branch-a"))
    }

    @Test
    fun `missing and mismatched live capability fail closed`() {
        assertFalse(allowsLegacyShiftOpeningMatch(null, "terminal-a", "branch-a"))
        assertFalse(allowsLegacyShiftOpeningMatch(shift, "terminal-b", "branch-a"))
        assertFalse(allowsLegacyShiftOpeningMatch(shift, "terminal-a", "branch-b"))
        assertFalse(allowsLegacyShiftOpeningMatch(shift.copy(status = "closed"), "terminal-a", "branch-a"))
    }

    @Test
    fun `recovery uses live per shift receipt provenance`() {
        val root = listOf("src/main/java", "app/src/main/java", "android-native/app/src/main/java")
            .map(::File).first { it.isDirectory }
        val source = root.resolve("cloud/dcompany/erp/core/sync/SyncEngine.kt").readText()
        val recovery = source.substringAfter("private suspend fun verifyAndClearRejectedShiftOpenAlreadyLocked(")
            .substringBefore("/**")
        assertTrue(recovery.contains("allowsLegacyShiftOpeningMatch(detail, terminalId, branchId)"))
        assertTrue(recovery.contains("allowLegacyOpeningMatch = allowLegacyOpeningMatch"))
        assertFalse(recovery.contains("ApiClient.api.terminals(branchId)"))
        assertFalse(recovery.contains("activeValidatedTerminal"))

        val openingPush = source.substringAfter("private suspend fun pushShiftOpen(")
            .substringBefore("private suspend fun pushShiftCloses(")
        val closingPush = source.substringAfter("private suspend fun pushShiftCloses(")
            .substringBefore("private suspend fun pushGamingSessions(")
        assertTrue(openingPush.contains("shiftLifecycleActionIds(row.localId)"))
        assertTrue(openingPush.contains("actionIds.open"))
        assertTrue(openingPush.contains("requireShiftInstallationId(checkoutClientInstance)"))
        assertTrue(openingPush.contains("installationId,"))
        assertTrue(closingPush.contains("shiftLifecycleActionIds(row.localId)"))
        assertTrue(closingPush.contains("actionIds.close"))
        assertTrue(closingPush.contains("requireShiftInstallationId(checkoutClientInstance)"))
        assertTrue(closingPush.contains("installationId,"))
    }

    @Test
    fun `missing installation identity preserves the saved shift action`() {
        val failure = runCatching { requireShiftInstallationId { null } }.exceptionOrNull()
        assertTrue(failure is cloud.dcompany.erp.core.net.ApiException)
        failure as cloud.dcompany.erp.core.net.ApiException
        assertTrue(failure.mustPreserveOutbox)
        assertEquals("shift_installation_identity_unavailable", failure.code)
    }
}

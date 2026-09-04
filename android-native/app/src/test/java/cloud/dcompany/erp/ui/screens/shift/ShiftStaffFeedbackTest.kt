package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftHistoryRow
import cloud.dcompany.erp.core.db.ShiftHistorySource
import cloud.dcompany.erp.core.db.ShiftSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStaffFeedbackTest {

    @Test
    fun `another authorised staff member is explicitly told they can close`() {
        val message = shiftCloseHandoverMessage(
            shift = openShift(),
            currentUserId = "sameer",
            canClose = true,
        )

        assertTrue(message.orEmpty().contains("may close this shift"))
        assertTrue(message.orEmpty().contains("Rafi remains the opener"))
        assertTrue(message.orEmpty().contains("recorded as the closer"))
        assertFalse(message.orEmpty().contains("protected owner"))
    }

    @Test
    fun `permission remains the authority instead of opener ownership`() {
        assertNull(
            shiftCloseHandoverMessage(
                shift = openShift(),
                currentUserId = "sameer",
                canClose = false,
            ),
        )
    }

    @Test
    fun `open conflict identifies opener workspace and next action`() {
        val message = shiftAlreadyOpenMessage(
            shift = openShift(),
            workspaceLabel = "Main Shop · Hybrid",
        )

        assertTrue(message.contains("already open"))
        assertTrue(message.contains("Rafi"))
        assertTrue(message.contains("Main Shop · Hybrid"))
        assertTrue(message.contains("Use that shift for billing"))
        assertTrue(message.contains("Do not open a duplicate"))
    }

    @Test
    fun `workspace label removes blanks and duplicate names`() {
        assertEquals("Main Shop", shiftWorkspaceLabel(" Main Shop ", "Main Shop"))
        assertEquals("Main Shop · Hybrid", shiftWorkspaceLabel("Main Shop", "Hybrid"))
        assertNull(shiftWorkspaceLabel(" ", null))
    }

    @Test
    fun `server history names the closer while local history explains pending attribution`() {
        assertEquals("Sameer", shiftHistoryCloserLabel(history(source = ShiftHistorySource.SERVER, closer = "Sameer")))
        assertEquals(
            "waiting for server confirmation",
            shiftHistoryCloserLabel(history(source = ShiftHistorySource.LOCAL, closer = null)),
        )
        assertEquals(
            "not provided by the server",
            shiftHistoryCloserLabel(history(source = ShiftHistorySource.SERVER, closer = null)),
        )
    }

    private fun openShift() = ResolvedOpenShift(
        shiftId = "shift-1",
        source = ShiftSource.SERVER_CACHE,
        openedAtMillis = 1_000,
        openingFloatMinor = 5_000,
        expectedMinor = 5_000,
        openedByUserId = "rafi",
        openedByName = "Rafi",
        openedByEmail = "rafi@example.test",
    )

    private fun history(source: ShiftHistorySource, closer: String?) = ShiftHistoryRow(
        stableId = "history-1",
        serverShiftId = "shift-1",
        source = source,
        openedAtMillis = 1_000,
        closedAtMillis = 2_000,
        openingFloatMinor = 5_000,
        expectedMinor = 5_000,
        countedMinor = 5_000,
        varianceMinor = 0,
        grossCollectionsMinor = 0,
        cashCollectionsMinor = 0,
        cardCollectionsMinor = 0,
        upiCollectionsMinor = 0,
        otherCollectionsMinor = 0,
        totalRefundsMinor = 0,
        netCollectionsMinor = 0,
        openedByUserId = "rafi",
        openedByName = "Rafi",
        openedByEmail = "rafi@example.test",
        closedByUserId = closer?.let { "sameer" },
        closedByName = closer,
        closedByEmail = closer?.let { "sameer@example.test" },
    )
}

package cloud.dcompany.erp.core.db

import cloud.dcompany.erp.ui.screens.shift.ShiftCloseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftCloseCountPolicyTest {

    @Test
    fun `missing count fails closed with recovery guidance`() {
        val result = ShiftCloseCountPolicy.validate(null)

        assertTrue(result is ShiftCloseCountValidation.Invalid)
        val message = (result as ShiftCloseCountValidation.Invalid).message
        assertTrue(message.contains("missing its drawer count"))
        assertTrue(message.contains("was not sent to the server"))
        assertTrue(message.contains("Continue shift"))
    }

    @Test
    fun `negative count fails closed with recovery guidance`() {
        val result = ShiftCloseCountPolicy.validate(-1)

        assertTrue(result is ShiftCloseCountValidation.Invalid)
        val message = (result as ShiftCloseCountValidation.Invalid).message
        assertTrue(message.contains("negative drawer count"))
        assertTrue(message.contains("was not sent to the server"))
    }

    @Test
    fun `zero and positive counts remain valid without coercion`() {
        assertEquals(
            ShiftCloseCountValidation.Valid(0),
            ShiftCloseCountPolicy.validate(0),
        )
        assertEquals(
            ShiftCloseCountValidation.Valid(108_300),
            ShiftCloseCountPolicy.validate(108_300),
        )
    }

    @Test
    fun `network body refuses negative count as a final construction guard`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShiftCloseBody(-1)
        }
        assertEquals(0L, ShiftCloseBody(0).countedMinor)
    }

    @Test
    fun `pre-open policy rejects malformed unsynced closes but preserves valid zero`() {
        val unsyncedClose = LocalShiftEntity(
            localId = "unsynced-close",
            serverShiftId = null,
            terminalId = "terminal-a",
            branchId = "branch-a",
            openingFloatMinor = 5_000,
            openedAtMillis = 1_000,
            state = ShiftState.CLOSE_PENDING,
            countedMinor = null,
            closedAtMillis = 2_000,
        )

        val missing = ShiftCloseCountPolicy.preflightRejection(unsyncedClose)
        val negative = ShiftCloseCountPolicy.preflightRejection(unsyncedClose.copy(countedMinor = -1))

        assertEquals("unsynced-close", missing?.localId)
        assertTrue(missing?.message.orEmpty().contains("was not sent to the server"))
        assertEquals("unsynced-close", negative?.localId)
        assertEquals(null, ShiftCloseCountPolicy.preflightRejection(unsyncedClose.copy(countedMinor = 0)))
        assertEquals(
            null,
            ShiftCloseCountPolicy.preflightRejection(
                unsyncedClose.copy(state = ShiftState.OPEN_PENDING, countedMinor = null),
            ),
        )
    }

    @Test
    fun `close captured by another terminal cannot reach the network stage`() {
        val wrongTerminalClose = LocalShiftEntity(
            localId = "shift-close-terminal-b",
            serverShiftId = "server-shift-terminal-b",
            terminalId = "terminal-b",
            branchId = "branch-a",
            openingFloatMinor = 5_000,
            openedAtMillis = 1_000,
            state = ShiftState.CLOSE_PENDING,
            countedMinor = 0,
            closedAtMillis = 2_000,
        )
        var networkCalls = 0

        ShiftCloseCountPolicy.filterForTerminal(
            rows = listOf(wrongTerminalClose to 0L),
            currentTerminalId = "terminal-a",
        ).forEach { networkCalls += 1 }

        assertEquals(0, networkCalls)
    }

    @Test
    fun `current and legacy terminal closes remain eligible`() {
        val currentTerminalClose = LocalShiftEntity(
            localId = "shift-close-terminal-a",
            serverShiftId = "server-shift-terminal-a",
            terminalId = "terminal-a",
            branchId = "branch-a",
            openingFloatMinor = 5_000,
            openedAtMillis = 1_000,
            state = ShiftState.CLOSE_PENDING,
            countedMinor = 0,
            closedAtMillis = 2_000,
        )
        val legacyClose = currentTerminalClose.copy(
            localId = "shift-close-legacy",
            serverShiftId = "server-shift-legacy",
            terminalId = null,
        )

        assertEquals(
            listOf(currentTerminalClose to 0L, legacyClose to 0L),
            ShiftCloseCountPolicy.filterForTerminal(
                rows = listOf(currentTerminalClose to 0L, legacyClose to 0L),
                currentTerminalId = "terminal-a",
            ),
        )
    }
}

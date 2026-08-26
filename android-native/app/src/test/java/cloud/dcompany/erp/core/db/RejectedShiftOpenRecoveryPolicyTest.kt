package cloud.dcompany.erp.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RejectedShiftOpenRecoveryPolicyTest {

    @Test
    fun `same rejected identity in current scope is eligible for retry or verified clear`() {
        assertNull(
            rejectedOpenRecoveryPrecondition(
                row = rejectedShift(),
                terminalId = TERMINAL,
                branchId = BRANCH,
                serverShiftPresent = false,
            ),
        )
    }

    @Test
    fun `server shift prevents local clear so it can be reconciled instead`() {
        val result = rejectedOpenRecoveryPrecondition(
            row = rejectedShift(),
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftPresent = true,
        )

        assertEquals(RejectedOpenRecoveryStatus.SERVER_SHIFT_PRESENT, result?.status)
    }

    @Test
    fun `different terminal or branch cannot mutate rejected shift`() {
        val wrongTerminal = rejectedOpenRecoveryPrecondition(
            row = rejectedShift(),
            terminalId = "terminal-b",
            branchId = BRANCH,
            serverShiftPresent = false,
        )
        val wrongBranch = rejectedOpenRecoveryPrecondition(
            row = rejectedShift(),
            terminalId = TERMINAL,
            branchId = "branch-b",
            serverShiftPresent = false,
        )

        assertEquals(RejectedOpenRecoveryStatus.WRONG_SCOPE, wrongTerminal?.status)
        assertEquals(RejectedOpenRecoveryStatus.WRONG_SCOPE, wrongBranch?.status)
    }

    @Test
    fun `only unresolved rejected open without server identity can be recovered`() {
        val alreadyChanged = rejectedOpenRecoveryPrecondition(
            row = rejectedShift().copy(state = ShiftState.OPEN_PENDING),
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftPresent = false,
        )
        val hasServerIdentity = rejectedOpenRecoveryPrecondition(
            row = rejectedShift().copy(serverShiftId = "server-shift"),
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftPresent = false,
        )

        assertEquals(RejectedOpenRecoveryStatus.CHANGED, alreadyChanged?.status)
        assertEquals(RejectedOpenRecoveryStatus.CHANGED, hasServerIdentity?.status)
    }

    @Test
    fun `server shift opened much later is a different drawer lifecycle`() {
        val result = rejectedOpenServerMatch(
            row = rejectedShift(),
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverOpenedByUserId = "user-a",
            serverOpeningFloatMinor = 50_000,
            serverOpenedAtMillis = 1_000 + REJECTED_OPEN_RACE_TOLERANCE_MILLIS + 1,
        )

        assertEquals(RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT, result?.status)
    }

    @Test
    fun `same opening race is causally eligible for selected reconciliation`() {
        assertNull(
            rejectedOpenServerMatch(
                row = rejectedShift(),
                terminalId = TERMINAL,
                branchId = BRANCH,
                serverOpenedByUserId = "user-a",
                serverOpeningFloatMinor = 50_000,
                serverOpenedAtMillis = 1_000 + REJECTED_OPEN_RACE_TOLERANCE_MILLIS,
            ),
        )
    }

    @Test
    fun `same till and time cannot link a shift opened by different staff`() {
        val result = rejectedOpenServerMatch(
            row = rejectedShift(),
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverOpenedByUserId = "user-b",
            serverOpeningFloatMinor = 50_000,
            serverOpenedAtMillis = 1_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT, result?.status)
    }

    @Test
    fun `same staff and time cannot link a different opening float`() {
        val result = rejectedOpenServerMatch(
            row = rejectedShift(),
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverOpenedByUserId = "user-a",
            serverOpeningFloatMinor = 50_001,
            serverOpenedAtMillis = 1_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT, result?.status)
    }

    @Test
    fun `clock skew is bounded in both timestamp directions`() {
        val earliestAllowed = 1_000 - REJECTED_OPEN_RACE_TOLERANCE_MILLIS
        val tooEarly = earliestAllowed - 1

        assertNull(
            rejectedOpenServerMatch(
                row = rejectedShift(),
                terminalId = TERMINAL,
                branchId = BRANCH,
                serverOpenedByUserId = "user-a",
                serverOpeningFloatMinor = 50_000,
                serverOpenedAtMillis = earliestAllowed,
            ),
        )
        assertEquals(
            RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT,
            rejectedOpenServerMatch(
                row = rejectedShift(),
                terminalId = TERMINAL,
                branchId = BRANCH,
                serverOpenedByUserId = "user-a",
                serverOpeningFloatMinor = 50_000,
                serverOpenedAtMillis = tooEarly,
            )?.status,
        )
    }

    @Test
    fun `timestamp window saturates instead of overflowing`() {
        assertEquals(
            RejectedOpenTimestampWindow(Long.MIN_VALUE, Long.MIN_VALUE + 10),
            rejectedOpenTimestampWindow(Long.MIN_VALUE, raceToleranceMillis = 10),
        )
        assertEquals(
            RejectedOpenTimestampWindow(Long.MAX_VALUE - 10, Long.MAX_VALUE),
            rejectedOpenTimestampWindow(Long.MAX_VALUE, raceToleranceMillis = 10),
        )
    }

    private fun rejectedShift() = LocalShiftEntity(
        localId = "stable-local-id",
        terminalId = TERMINAL,
        branchId = BRANCH,
        openingFloatMinor = 50_000,
        openedAtMillis = 1_000,
        openedByUserId = "user-a",
        state = ShiftState.OPEN_REJECTED,
        lastError = "Server refused this shift open.",
    )

    private companion object {
        const val TERMINAL = "terminal-a"
        const val BRANCH = "branch-a"
    }
}

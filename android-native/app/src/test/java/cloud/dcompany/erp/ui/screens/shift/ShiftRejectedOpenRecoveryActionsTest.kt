package cloud.dcompany.erp.ui.screens.shift

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftRejectedOpenRecoveryActionsTest {

    @Test
    fun `current shift disables impossible retry but keeps online verification available`() {
        val actions = rejectedOpenRecoveryActions(
            hasCurrentShift = true,
            online = true,
            canRecover = true,
            busy = false,
        )

        assertFalse(actions.retryEnabled)
        assertTrue(actions.verifyEnabled)
        assertTrue(actions.guidance.contains("Retry is unavailable"))
        assertTrue(actions.guidance.contains("no captured work"))
        assertTrue(actions.guidance.contains("resolve or close the current shift"))
    }

    @Test
    fun `offline recovery permits stable retry only when no current shift exists`() {
        val noCurrent = rejectedOpenRecoveryActions(
            hasCurrentShift = false,
            online = false,
            canRecover = true,
            busy = false,
        )
        val current = rejectedOpenRecoveryActions(
            hasCurrentShift = true,
            online = false,
            canRecover = true,
            busy = false,
        )

        assertTrue(noCurrent.retryEnabled)
        assertFalse(noCurrent.verifyEnabled)
        assertFalse(current.retryEnabled)
        assertFalse(current.verifyEnabled)
    }

    @Test
    fun `permission or in-flight operation disables every recovery button`() {
        val noPermission = rejectedOpenRecoveryActions(
            hasCurrentShift = false,
            online = true,
            canRecover = false,
            busy = false,
        )
        val busy = rejectedOpenRecoveryActions(
            hasCurrentShift = false,
            online = true,
            canRecover = true,
            busy = true,
        )

        assertFalse(noPermission.retryEnabled)
        assertFalse(noPermission.verifyEnabled)
        assertFalse(busy.retryEnabled)
        assertFalse(busy.verifyEnabled)
    }
}

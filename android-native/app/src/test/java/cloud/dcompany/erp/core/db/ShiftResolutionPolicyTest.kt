package cloud.dcompany.erp.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftResolutionPolicyTest {

    private val server = ServerOpenShiftEntity(
        terminalId = "terminal-1",
        serverShiftId = "shift-server",
        branchId = "branch-1",
        status = "open",
        openingFloatMinor = 10_000,
        expectedMinor = 12_500,
        openedAtMillis = 1_000,
        openedByUserId = "cashier-a",
        openedByName = "Rafi",
        openedByEmail = "rafi@example.com",
        verifiedAtMillis = 2_000,
    )

    @Test
    fun `adopts a server shift for operational work without granting money access`() {
        val resolved = requireNotNull(ShiftResolutionPolicy.resolve(null, server))

        assertEquals("shift-server", resolved.shiftId)
        assertEquals(ShiftSource.SERVER_CACHE, resolved.source)
        assertFalse(resolved.canManageMoney(ShiftActor("waiter-b", protectedAccess = false)))
        assertTrue(resolved.canManageMoney(ShiftActor("cashier-a", protectedAccess = false)))
        assertTrue(resolved.canManageMoney(ShiftActor("owner", protectedAccess = true)))
    }

    @Test
    fun `unresolved local open overlays an older server cache`() {
        val local = local(state = ShiftState.OPEN_PENDING, serverShiftId = null)

        val resolved = requireNotNull(ShiftResolutionPolicy.resolve(local, server))

        assertEquals("local-shift", resolved.shiftId)
        assertEquals(ShiftSource.LOCAL_OUTBOX, resolved.source)
        assertEquals("cashier-a", resolved.openedByUserId)
    }

    @Test
    fun `matching server row enriches a synced local lifecycle`() {
        val local = local(state = ShiftState.OPEN_SYNCED, serverShiftId = server.serverShiftId)

        val resolved = requireNotNull(ShiftResolutionPolicy.resolve(local, server))

        assertEquals(server.expectedMinor, resolved.expectedMinor)
        assertEquals(server.openedByName, resolved.openedByName)
        assertEquals(server.openedAtMillis, resolved.openedAtMillis)
    }

    @Test
    fun `pending close suppresses even a still-open server cache`() {
        assertNull(
            ShiftResolutionPolicy.resolve(
                local(state = ShiftState.CLOSE_PENDING, serverShiftId = server.serverShiftId),
                server,
            ),
        )
    }

    @Test
    fun `rejected local open superseded by server never overlays authority`() {
        val resolved = requireNotNull(
            ShiftResolutionPolicy.resolve(
                local(state = ShiftState.OPEN_SUPERSEDED, serverShiftId = server.serverShiftId),
                server,
            ),
        )

        assertEquals(ShiftSource.SERVER_CACHE, resolved.source)
        assertNull(resolved.local)
        assertEquals(server.serverShiftId, resolved.shiftId)
    }

    @Test
    fun `unknown opener fails closed for money but not operational adoption`() {
        val resolved = requireNotNull(
            ShiftResolutionPolicy.resolve(null, server.copy(openedByUserId = null)),
        )

        assertEquals("shift-server", resolved.shiftId)
        assertFalse(resolved.canManageMoney(ShiftActor("cashier-a", protectedAccess = false)))
        assertTrue(resolved.moneyAccessMessage(ShiftActor("cashier-a", false))!!.contains("not been verified"))
    }

    private fun local(state: String, serverShiftId: String?) = LocalShiftEntity(
        localId = "local-shift",
        serverShiftId = serverShiftId,
        openingFloatMinor = 5_000,
        openedAtMillis = 500,
        openedByUserId = "cashier-a",
        openedByName = "Rafi",
        openedByEmail = "rafi@example.com",
        state = state,
    )
}

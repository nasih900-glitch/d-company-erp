package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ServerOpenShiftEntity
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.db.ShiftState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftCloseOriginPolicyTest {
    @Test
    fun `same installation may adopt a colleagues shift without owning its opener identity`() {
        val shift = resolved(server = server(origin = INSTALLATION))
        val result = shiftCloseOriginDecision(shift, INSTALLATION)
        assertTrue(result.allowed)
        assertNull(result.message)
        assertTrue(shift.openedByUserId == "another-user")
    }

    @Test
    fun `different or unverified installation cannot capture an adopted Android drawer count`() {
        for (echo in listOf(null, OTHER_INSTALLATION)) {
            val result = shiftCloseOriginDecision(resolved(server = server(origin = echo)), INSTALLATION)
            assertFalse(result.allowed)
            assertTrue(result.message.orEmpty().contains("Recover Android shift"))
            assertFalse(result.message.orEmpty().contains("from this browser"))
        }
        assertFalse(shiftCloseOriginDecision(resolved(server = server(origin = INSTALLATION)), null).allowed)
        assertFalse(shiftCloseOriginDecision(resolved(server = server(origin = "invalid")), "invalid").allowed)
    }

    @Test
    fun `legacy and web server shifts preserve backend authoritative closing`() {
        assertTrue(shiftCloseOriginDecision(resolved(server = server(platform = null)), INSTALLATION).allowed)
        assertTrue(shiftCloseOriginDecision(resolved(server = server(platform = "web")), INSTALLATION).allowed)
    }

    @Test
    fun `original Code21 local lifecycle retains causal close without installation echo`() {
        assertTrue(shiftCloseOriginDecision(resolved(local = local()), INSTALLATION).allowed)
        assertTrue(shiftCloseOriginDecision(resolved(local = local(), server = server()), INSTALLATION).allowed)
        assertTrue(shiftCloseOriginDecision(resolved(local = local(ShiftState.OPEN_PENDING)), null).allowed)
    }

    @Test
    fun `definitive origin rejection prevents retry and recount but other blockers do not`() {
        for (error in listOf(
            "This Android app installation is not verified as the one that opened this shift. The shift remains open.",
            "This shift was opened by the Android tablet and cannot be closed from this browser or a different app installation while saved work may still be waiting.",
        )) {
            for (state in listOf(ShiftState.CLOSE_REJECTED, ShiftState.OPEN_SYNCED)) {
                val row = local(state).copy(lastError = error)
                assertFalse(shiftCloseOriginDecision(resolved(local = row, server = server()), INSTALLATION).allowed)
                // A fresh, exact-origin echo can resolve the earlier refusal.
                assertTrue(shiftCloseOriginDecision(resolved(local = row, server = server(origin = INSTALLATION)), INSTALLATION).allowed)
            }
        }
        val blocked = local(ShiftState.CLOSE_REJECTED).copy(lastError = "There is one unpaid bill.")
        assertTrue(shiftCloseOriginDecision(resolved(local = blocked, server = server()), INSTALLATION).allowed)
    }

    private fun resolved(local: LocalShiftEntity? = null, server: ServerOpenShiftEntity? = null): ResolvedOpenShift =
        requireNotNull(ShiftResolutionPolicy.resolve(local, server, includeClosingIntent = true))

    private fun local(state: String = ShiftState.OPEN_SYNCED) = LocalShiftEntity(
        localId = "retained-original", serverShiftId = "shift", terminalId = "terminal", branchId = "branch",
        openingFloatMinor = 0, openedAtMillis = 1000, openedByUserId = "another-user", state = state,
    )

    private fun server(platform: String? = "android", origin: String? = null) = ServerOpenShiftEntity(
        terminalId = "terminal", serverShiftId = "shift", branchId = "branch", status = "open",
        openingFloatMinor = 0, openedAtMillis = 1000, openedByUserId = "another-user",
        openingClientPlatform = platform, openingClientInstallationId = origin, verifiedAtMillis = 2000,
    )

    private companion object {
        const val INSTALLATION = "11111111-1111-4111-8111-111111111111"
        const val OTHER_INSTALLATION = "22222222-2222-4222-8222-222222222222"
    }
}

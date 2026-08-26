package cloud.dcompany.erp.core.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalNotificationRouteTest {

    @Test
    fun `signed out route waits without losing target`() {
        assertEquals(
            OperationalRouteDecision.WAIT_FOR_SIGN_IN,
            operationalRouteDecision(signedIn = false, destinationAllowed = false),
        )
    }

    @Test
    fun `authorized destination navigates and denied role is explicit`() {
        assertEquals(
            OperationalRouteDecision.NAVIGATE,
            operationalRouteDecision(signedIn = true, destinationAllowed = true),
        )
        assertEquals(
            OperationalRouteDecision.ACCESS_DENIED,
            operationalRouteDecision(signedIn = true, destinationAllowed = false),
        )
    }

    @Test
    fun `open token is exact one time material with bounded lifetime`() {
        assertTrue(operationalOpenTokenValid("secret", "secret", 1_000, 2_000))
        assertFalse(operationalOpenTokenValid("secret", "forged", 1_000, 2_000))
        assertFalse(operationalOpenTokenValid("secret", "secret", 2_000, 1_000))
        assertFalse(
            operationalOpenTokenValid(
                expectedToken = "secret",
                suppliedToken = "secret",
                issuedAtMillis = 1_000,
                nowMillis = 1_000 + 24L * 60L * 60L * 1_000L + 1L,
            ),
        )
    }
}

package cloud.dcompany.erp.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAuthenticationGateTest {

    @Test
    fun `resource frames are rejected before authenticated acknowledgement`() {
        val gate = RealtimeAuthenticationGate()
        gate.opened(token = "employee-a-token", wasReconnect = false)

        assertFalse(gate.canProcessAuthenticatedFrame("employee-a-token"))
    }

    @Test
    fun `authenticated acknowledgement unlocks only the exact presented token`() {
        val gate = RealtimeAuthenticationGate()
        gate.opened(token = "employee-a-token", wasReconnect = false)

        val decision = gate.acknowledge("employee-a-token")

        assertTrue(decision.accepted)
        assertFalse(decision.publishReconnect)
        assertTrue(gate.canProcessAuthenticatedFrame("employee-a-token"))
    }

    @Test
    fun `token rotation invalidates the old authenticated socket`() {
        val gate = RealtimeAuthenticationGate()
        gate.opened(token = "old-access-token", wasReconnect = false)
        assertTrue(gate.acknowledge("old-access-token").accepted)

        assertFalse(gate.canProcessAuthenticatedFrame("rotated-access-token"))
        assertFalse(gate.presentedTokenIsCurrent("rotated-access-token"))
    }

    @Test
    fun `logout invalidates an authenticated socket before close callback`() {
        val gate = RealtimeAuthenticationGate()
        gate.opened(token = "employee-a-token", wasReconnect = false)
        assertTrue(gate.acknowledge("employee-a-token").accepted)

        assertFalse(gate.canProcessAuthenticatedFrame(null))
        assertFalse(gate.presentedTokenIsCurrent(null))
    }

    @Test
    fun `another employee or company token cannot acknowledge stale socket`() {
        val gate = RealtimeAuthenticationGate()
        gate.opened(token = "company-a-employee-a", wasReconnect = true)

        val decision = gate.acknowledge("company-b-employee-b")

        assertFalse(decision.accepted)
        assertFalse(decision.publishReconnect)
        assertFalse(gate.canProcessAuthenticatedFrame("company-b-employee-b"))
    }

    @Test
    fun `reconnect gap is published only once after authenticated acknowledgement`() {
        val gate = RealtimeAuthenticationGate()
        gate.opened(token = "employee-a-token", wasReconnect = true)

        val first = gate.acknowledge("employee-a-token")
        val duplicate = gate.acknowledge("employee-a-token")

        assertTrue(first.accepted)
        assertTrue(first.publishReconnect)
        assertTrue(duplicate.accepted)
        assertFalse(duplicate.publishReconnect)
    }
}

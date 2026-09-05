package cloud.dcompany.erp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkCallbackTrackerTest {
    @Test
    fun `availability waits for ordered capabilities rather than guessing validation`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        assertNull(tracker.capabilitiesChanged("wifi", validated = true))
        tracker.available("wifi")
        assertEquals(false, tracker.capabilitiesChanged("wifi", validated = false))
        assertEquals(true, tracker.capabilitiesChanged("wifi", validated = true))
    }

    @Test
    fun `losing current default reports no network despite a stale manager snapshot`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        tracker.available("wifi")
        assertEquals(true, tracker.capabilitiesChanged("wifi", validated = true))
        assertEquals(false, tracker.lost("wifi"))
        assertNull(tracker.lost("wifi"))
        assertNull(tracker.capabilitiesChanged("wifi", validated = true))
    }

    @Test
    fun `old loss cannot overwrite a validated replacement default`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        tracker.available("wifi")
        tracker.capabilitiesChanged("wifi", validated = true)
        tracker.available("cellular")
        assertEquals(true, tracker.capabilitiesChanged("cellular", validated = true))
        assertNull(tracker.lost("wifi"))
        assertEquals(true, tracker.capabilitiesChanged("cellular", validated = true))
        assertEquals(false, tracker.lost("cellular"))
    }

    @Test
    fun `stale capabilities cannot validate or invalidate the new default`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        tracker.available("old-wifi")
        tracker.available("new-wifi")
        assertNull(tracker.capabilitiesChanged("old-wifi", validated = true))
        assertNull(tracker.capabilitiesChanged("old-wifi", validated = false))
        assertEquals(false, tracker.capabilitiesChanged("new-wifi", validated = false))
        assertEquals(true, tracker.capabilitiesChanged("new-wifi", validated = true))
    }

    @Test
    fun `healthy handover retains chrome but reproves the replacement network`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        val machine = ConnectivityStateMachine(
            ConnectivityMachineState(phase = ConnectivityPhase.ONLINE, generation = 4L),
        )
        tracker.available("wifi")
        tracker.capabilitiesChanged("wifi", validated = true)
        assertTrue(tracker.available("cellular"))
        val handover = machine.reduce(ConnectivityEvent.DefaultNetworkChanged)
        assertEquals(ConnectivityPhase.ONLINE, handover.state.phase)
        assertTrue(handover.state.awaitingDefaultCapabilities)
        assertTrue(handover.effects.isEmpty())
        assertNull(tracker.lost("wifi"))
        val transition = machine.reduce(
            ConnectivityEvent.NetworkChanged(
                validated = requireNotNull(tracker.capabilitiesChanged("cellular", validated = true)),
                notifyReconnect = true,
            ),
        )
        assertEquals(ConnectivityPhase.ONLINE, transition.state.phase)
        assertEquals(6L, transition.state.generation)
        assertTrue(transition.state.probeInFlight)
        assertFalse(transition.state.awaitingDefaultCapabilities)
        assertEquals(listOf(ConnectivityEffect.StartProbe(6L)), transition.effects)
        val verified = machine.reduce(ConnectivityEvent.ProbeCompleted(6L, successful = true))
        assertEquals(ConnectivityPhase.ONLINE, verified.state.phase)
        assertEquals(
            listOf(ConnectivityEffect.NotifyValidatedReconnect, ConnectivityEffect.NotifyBackOnline),
            verified.effects,
        )
    }

    @Test
    fun `new default invalidates old readiness before replacement capabilities arrive`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        val machine = ConnectivityStateMachine(
            ConnectivityMachineState(phase = ConnectivityPhase.ONLINE, generation = 4L),
        )
        tracker.available("wifi")
        val oldProbe = machine.reduce(ConnectivityEvent.BackendTransportFailure)
        assertTrue(oldProbe.state.probeInFlight)
        assertTrue(tracker.available("cellular"))
        val replacement = machine.reduce(ConnectivityEvent.DefaultNetworkChanged)
        assertTrue(replacement.state.awaitingDefaultCapabilities)
        assertFalse(replacement.state.probeInFlight)

        listOf(false, true).forEach { oldResult ->
            val stale = machine.reduce(
                ConnectivityEvent.ProbeCompleted(oldProbe.state.generation, successful = oldResult),
            )
            assertEquals(replacement.state, stale.state)
            assertTrue(stale.effects.isEmpty())
        }
        val oldTransport = machine.reduce(ConnectivityEvent.BackendTransportFailure)
        assertEquals(replacement.state, oldTransport.state)
        assertTrue(oldTransport.effects.isEmpty())
        val unvalidated = machine.reduce(
            ConnectivityEvent.NetworkChanged(
                validated = requireNotNull(tracker.capabilitiesChanged("cellular", validated = false)),
                notifyReconnect = true,
            ),
        )
        assertEquals(ConnectivityPhase.NO_NETWORK, unvalidated.state.phase)
        assertFalse(unvalidated.state.awaitingDefaultCapabilities)
    }

    @Test
    fun `current loss invalidates readiness and cannot become server issue`() {
        val tracker = DefaultNetworkCallbackTracker<String>()
        val machine = ConnectivityStateMachine(
            ConnectivityMachineState(phase = ConnectivityPhase.ONLINE, generation = 4L),
        )
        tracker.available("wifi")
        tracker.capabilitiesChanged("wifi", validated = true)
        val probing = machine.reduce(ConnectivityEvent.BackendTransportFailure)
        assertTrue(probing.state.probeInFlight)
        val offline = machine.reduce(
            ConnectivityEvent.NetworkChanged(
                validated = requireNotNull(tracker.lost("wifi")),
                notifyReconnect = true,
            ),
        )
        assertEquals(ConnectivityPhase.NO_NETWORK, offline.state.phase)
        assertFalse(offline.state.probeInFlight)
        assertFalse(offline.state.presentation.online)
        listOf(false, true).forEach { oldResult ->
            val stale = machine.reduce(
                ConnectivityEvent.ProbeCompleted(probing.state.generation, successful = oldResult),
            )
            assertEquals(ConnectivityPhase.NO_NETWORK, stale.state.phase)
            assertTrue(stale.effects.isEmpty())
        }
    }
}

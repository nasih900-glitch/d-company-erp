package cloud.dcompany.erp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityStateMachineTest {

    @Test
    fun `cold start verifies readiness without pretending it is a reconnect`() {
        val machine = ConnectivityStateMachine()

        val detected = machine.reduce(
            ConnectivityEvent.NetworkChanged(validated = true, notifyReconnect = false),
        )

        assertEquals(ConnectivityPhase.VERIFYING, detected.state.phase)
        assertEquals(listOf(ConnectivityEffect.StartProbe(1L)), detected.effects)
        assertFalse(detected.effects.contains(ConnectivityEffect.NotifyValidatedReconnect))

        val proven = machine.reduce(ConnectivityEvent.ProbeCompleted(1L, successful = true))
        assertEquals(ConnectivityPhase.ONLINE, proven.state.phase)
        assertEquals(listOf(ConnectivityEffect.NotifyBackOnline), proven.effects)
    }

    @Test
    fun `transport failure verifies before showing an outage while network loss is immediate`() {
        val machine = onlineMachine(generation = 4L)

        val serverFailure = machine.reduce(ConnectivityEvent.BackendTransportFailure)

        assertEquals(ConnectivityPhase.VERIFYING, serverFailure.state.phase)
        assertEquals(5L, serverFailure.state.generation)
        assertTrue(serverFailure.state.probeInFlight)
        assertFalse(serverFailure.state.presentation.online)
        assertEquals(BackendReachability.UNKNOWN, serverFailure.state.presentation.backendReachability)
        assertEquals(listOf(ConnectivityEffect.StartProbe(5L)), serverFailure.effects)

        val networkFailure = machine.reduce(
            ConnectivityEvent.NetworkChanged(validated = false, notifyReconnect = true),
        )
        assertEquals(ConnectivityPhase.NO_NETWORK, networkFailure.state.phase)
        assertFalse(networkFailure.state.probeInFlight)
        assertTrue(networkFailure.effects.isEmpty())
    }

    @Test
    fun `a failure during recovery invalidates the old settle timer`() {
        val machine = ConnectivityStateMachine(
            ConnectivityMachineState(
                phase = ConnectivityPhase.RECOVERING,
                generation = 9L,
            ),
        )

        val failed = machine.reduce(ConnectivityEvent.BackendTransportFailure)
        assertEquals(ConnectivityPhase.VERIFYING, failed.state.phase)
        assertEquals(10L, failed.state.generation)
        assertFalse(failed.state.presentation.online)

        val stale = machine.reduce(ConnectivityEvent.RecoverySettled(9L))
        assertEquals(failed.state, stale.state)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `transient transport blip never flashes server unreachable when readiness succeeds`() {
        val machine = onlineMachine(generation = 7L)
        val phases = mutableListOf<ConnectivityPhase>()

        machine.reduce(ConnectivityEvent.BackendTransportFailure).also {
            phases += it.state.phase
            assertEquals(ConnectivityPhase.VERIFYING, it.state.phase)
            assertFalse(it.state.presentation.online)
        }
        machine.reduce(ConnectivityEvent.BackendTransportFailure).also {
            phases += it.state.phase
            assertEquals(8L, it.state.generation)
            assertTrue(it.state.probeInFlight)
            assertTrue(it.effects.isEmpty())
        }
        machine.reduce(ConnectivityEvent.ProbeCompleted(8L, successful = true)).also {
            phases += it.state.phase
            assertEquals(ConnectivityPhase.RECOVERING, it.state.phase)
        }
        machine.reduce(ConnectivityEvent.RecoverySettled(8L)).also {
            phases += it.state.phase
            assertEquals(ConnectivityPhase.ONLINE, it.state.phase)
        }

        assertFalse(phases.contains(ConnectivityPhase.SERVER_UNREACHABLE))
    }

    @Test
    fun `readiness failure is the authority that exposes server unreachable`() {
        val machine = onlineMachine(generation = 11L)

        val checking = machine.reduce(ConnectivityEvent.BackendTransportFailure)
        assertEquals(ConnectivityPhase.VERIFYING, checking.state.phase)

        val failedProof = machine.reduce(
            ConnectivityEvent.ProbeCompleted(generation = 12L, successful = false),
        )
        assertEquals(ConnectivityPhase.SERVER_UNREACHABLE, failedProof.state.phase)
        assertEquals(BackendReachability.UNREACHABLE, failedProof.state.presentation.backendReachability)
        assertEquals(listOf(ConnectivityEffect.RetryLater(12L, 2_000L)), failedProof.effects)

        val duplicateTransportFailure = machine.reduce(ConnectivityEvent.BackendTransportFailure)
        assertEquals(failedProof.state, duplicateTransportFailure.state)
        assertTrue(duplicateTransportFailure.effects.isEmpty())
    }

    @Test
    fun `reconnect callbacks wait for readiness proof and recovery stability`() {
        val machine = ConnectivityStateMachine()
        machine.reduce(
            ConnectivityEvent.NetworkChanged(validated = true, notifyReconnect = true),
        ).also { validating ->
            assertEquals(listOf(ConnectivityEffect.StartProbe(1L)), validating.effects)
            assertFalse(validating.effects.contains(ConnectivityEffect.NotifyValidatedReconnect))
        }

        val proven = machine.reduce(ConnectivityEvent.ProbeCompleted(1L, successful = true))
        assertEquals(ConnectivityPhase.RECOVERING, proven.state.phase)
        assertEquals(
            listOf(
                ConnectivityEffect.SettleRecovery(
                    generation = 1L,
                    delayMillis = CONNECTIVITY_RECOVERY_STABILITY_MILLIS,
                ),
            ),
            proven.effects,
        )

        val settled = machine.reduce(ConnectivityEvent.RecoverySettled(1L))
        assertEquals(ConnectivityPhase.ONLINE, settled.state.phase)
        assertEquals(
            listOf(
                ConnectivityEffect.NotifyValidatedReconnect,
                ConnectivityEffect.NotifyBackOnline,
            ),
            settled.effects,
        )
    }

    @Test
    fun `probe results from an old network generation are ignored`() {
        val machine = ConnectivityStateMachine()
        machine.reduce(ConnectivityEvent.NetworkChanged(validated = true, notifyReconnect = false))
        machine.reduce(ConnectivityEvent.NetworkChanged(validated = false, notifyReconnect = true))

        val stale = machine.reduce(ConnectivityEvent.ProbeCompleted(1L, successful = true))

        assertEquals(ConnectivityPhase.NO_NETWORK, stale.state.phase)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `ordinary HTTP success only requests authoritative readiness proof`() {
        val machine = ConnectivityStateMachine(
            ConnectivityMachineState(
                phase = ConnectivityPhase.SERVER_UNREACHABLE,
                generation = 3L,
            ),
        )

        val response = machine.reduce(ConnectivityEvent.BackendHttpResponse)

        assertEquals(ConnectivityPhase.VERIFYING, response.state.phase)
        assertEquals(4L, response.state.generation)
        assertEquals(listOf(ConnectivityEffect.StartProbe(4L)), response.effects)
    }

    @Test
    fun `duplicate callbacks do not duplicate recovery effects`() {
        val machine = onlineMachine(generation = 2L)

        val duplicateNetwork = machine.reduce(
            ConnectivityEvent.NetworkChanged(validated = true, notifyReconnect = true),
        )
        val duplicateResponse = machine.reduce(ConnectivityEvent.BackendHttpResponse)

        assertTrue(duplicateNetwork.effects.isEmpty())
        assertTrue(duplicateResponse.effects.isEmpty())
        assertEquals(ConnectivityPhase.ONLINE, machine.state.phase)
    }

    @Test
    fun `failed readiness proof retries with bounded backoff`() {
        val machine = ConnectivityStateMachine()
        machine.reduce(ConnectivityEvent.NetworkChanged(validated = true, notifyReconnect = true))

        val failed = machine.reduce(ConnectivityEvent.ProbeCompleted(1L, successful = false))
        assertEquals(ConnectivityPhase.SERVER_UNREACHABLE, failed.state.phase)
        assertEquals(
            listOf(ConnectivityEffect.RetryLater(1L, 2_000L)),
            failed.effects,
        )

        val retry = machine.reduce(ConnectivityEvent.RetryProbe(1L))
        assertTrue(retry.state.probeInFlight)
        assertEquals(listOf(ConnectivityEffect.StartProbe(1L)), retry.effects)
    }

    private fun onlineMachine(generation: Long): ConnectivityStateMachine = ConnectivityStateMachine(
        ConnectivityMachineState(
            phase = ConnectivityPhase.ONLINE,
            generation = generation,
        ),
    )
}

package cloud.dcompany.erp.auditdriver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedNetworkWaiterTest {
    @Test
    fun `already validated network succeeds immediately`() {
        val clock = FakeClock()

        val outcome = clock.await { NetworkCapabilityState.VALIDATED }

        assertEquals(ValidatedNetworkWaitResult.VALIDATED, outcome.result)
        assertEquals(NetworkCapabilityState.VALIDATED, outcome.capabilityState)
        assertEquals(0L, outcome.elapsedMillis)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `missing active network fails closed at the exact deadline`() {
        val clock = FakeClock()

        val outcome = clock.await { NetworkCapabilityState.MISSING }

        assertEquals(ValidatedNetworkWaitResult.TIMED_OUT, outcome.result)
        assertEquals(NetworkCapabilityState.MISSING, outcome.capabilityState)
        assertEquals(MAX_VALIDATED_NETWORK_WAIT_MILLIS, outcome.elapsedMillis)
    }

    @Test
    fun `internet without validation cannot satisfy the precondition`() {
        val clock = FakeClock()

        val outcome = clock.await { NetworkCapabilityState.INTERNET_ONLY }

        assertEquals(ValidatedNetworkWaitResult.TIMED_OUT, outcome.result)
        assertEquals(NetworkCapabilityState.INTERNET_ONLY, outcome.capabilityState)
    }

    @Test
    fun `validation delayed for thirty seconds remains inside its separate budget`() {
        val clock = FakeClock()

        val outcome = clock.await {
            if (clock.nowMillis >= 30_000L) {
                NetworkCapabilityState.VALIDATED
            } else {
                NetworkCapabilityState.WITHOUT_INTERNET
            }
        }

        assertEquals(ValidatedNetworkWaitResult.VALIDATED, outcome.result)
        assertEquals(30_000L, outcome.elapsedMillis)
        assertEquals(NetworkCapabilityState.VALIDATED, outcome.capabilityState)
    }

    @Test
    fun `custom shorter timeout is still exact and bounded`() {
        val clock = FakeClock()

        val outcome = clock.await(timeoutMillis = 1_100L) {
            NetworkCapabilityState.WITHOUT_INTERNET
        }

        assertEquals(ValidatedNetworkWaitResult.TIMED_OUT, outcome.result)
        assertEquals(1_100L, outcome.elapsedMillis)
        assertEquals(listOf(250L, 250L, 250L, 250L, 100L), clock.sleeps)
    }

    @Test
    fun `validated sample completing at the deadline is rejected`() {
        val clock = FakeClock()

        val outcome = clock.await(timeoutMillis = 1_000L) {
            clock.nowMillis = 1_000L
            NetworkCapabilityState.VALIDATED
        }

        assertEquals(ValidatedNetworkWaitResult.TIMED_OUT, outcome.result)
        assertEquals(1_000L, outcome.elapsedMillis)
        assertEquals(NetworkCapabilityState.VALIDATED, outcome.capabilityState)
    }

    @Test
    fun `capability read exception is a sanitized failure outcome`() {
        val clock = FakeClock()

        val outcome = clock.await {
            throw SecurityException("sensitive platform detail")
        }

        assertEquals(ValidatedNetworkWaitResult.ERROR, outcome.result)
        assertEquals(0L, outcome.elapsedMillis)
        assertEquals(NetworkCapabilityState.UNKNOWN_ERROR, outcome.capabilityState)
    }

    private class FakeClock {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()

        fun await(
            timeoutMillis: Long = MAX_VALIDATED_NETWORK_WAIT_MILLIS,
            poll: () -> NetworkCapabilityState,
        ): ValidatedNetworkWaitOutcome = awaitValidatedNetwork(
            timeoutMillis = timeoutMillis,
            monotonicMillis = { nowMillis },
            pollCapabilities = poll,
            sleepMillis = { duration ->
                sleeps += duration
                nowMillis += duration
            },
        )
    }
}

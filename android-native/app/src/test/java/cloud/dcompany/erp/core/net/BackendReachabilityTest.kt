package cloud.dcompany.erp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendReachabilityTest {

    @Test
    fun `tracker starts unknown and treats validated network optimistically`() {
        val tracker = BackendReachabilityTracker()

        assertEquals(BackendReachability.UNKNOWN, tracker.state.value)
        assertTrue(
            backendIsOnline(
                networkValidated = true,
                backendReachability = tracker.state.value,
            ),
        )
    }

    @Test
    fun `transport failure overrides validated wifi`() {
        val tracker = BackendReachabilityTracker()

        tracker.recordTransportFailure()

        assertEquals(BackendReachability.UNREACHABLE, tracker.state.value)
        assertFalse(
            backendIsOnline(
                networkValidated = true,
                backendReachability = tracker.state.value,
            ),
        )
    }

    @Test
    fun `an HTTP response proves recovery and network loss still wins`() {
        val tracker = BackendReachabilityTracker()
        tracker.recordTransportFailure()

        tracker.recordHttpResponse()

        assertEquals(BackendReachability.REACHABLE, tracker.state.value)
        assertTrue(
            backendIsOnline(
                networkValidated = true,
                backendReachability = tracker.state.value,
            ),
        )
        assertFalse(
            backendIsOnline(
                networkValidated = false,
                backendReachability = tracker.state.value,
            ),
        )
    }

    @Test
    fun `HTTP API refusal means reachable while no response means unreachable`() {
        val tracker = BackendReachabilityTracker()

        tracker.recordApiFailure(ApiException("Forbidden", status = 403))
        assertEquals(BackendReachability.REACHABLE, tracker.state.value)

        tracker.recordApiFailure(ApiException("No response", status = null))
        assertEquals(BackendReachability.UNREACHABLE, tracker.state.value)
    }

    @Test
    fun `an explicitly cancelled call never publishes a server outage`() {
        assertFalse(shouldPublishTransportFailure(callCancelled = true))
        assertTrue(shouldPublishTransportFailure(callCancelled = false))
    }

    @Test
    fun `a cancelled nested API failure publishes only when an HTTP response exists`() {
        assertFalse(
            shouldPublishApiReachability(
                ApiException("No response", status = null),
                callCancelled = true,
            ),
        )
        assertTrue(
            shouldPublishApiReachability(
                ApiException("No response", status = null),
                callCancelled = false,
            ),
        )
        assertTrue(
            shouldPublishApiReachability(
                ApiException("Forbidden", status = 403),
                callCancelled = true,
            ),
        )
    }
}

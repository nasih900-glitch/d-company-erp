package cloud.dcompany.erp.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSocketLifecycleTest {

    @Test
    fun `delayed terminal callback from old socket cannot affect replacement`() {
        val lifecycle = RealtimeSocketLifecycle<FakeSocket>()
        val first = requireNotNull(lifecycle.open { FakeSocket("first") })

        // The watchdog (or a real failure) has already retired this socket and
        // a reconnect has installed its replacement before OkHttp delivers the
        // old socket's delayed onClosed callback.
        assertTrue(
            lifecycle.clearIfCurrent(first.generation, first.socket) {},
        )
        val replacement = requireNotNull(lifecycle.open { FakeSocket("replacement") })

        var staleCallbackSideEffects = 0
        assertFalse(
            lifecycle.clearIfCurrent(first.generation, first.socket) {
                staleCallbackSideEffects += 1
            },
        )

        assertEquals(0, staleCallbackSideEffects)
        assertTrue(lifecycle.isCurrent(replacement))
    }

    @Test
    fun `delayed frame from old socket is ignored after reconnect`() {
        val lifecycle = RealtimeSocketLifecycle<FakeSocket>()
        val first = requireNotNull(lifecycle.open { FakeSocket("first") })
        assertTrue(lifecycle.clearIfCurrent(first.generation, first.socket) {})
        val replacement = requireNotNull(lifecycle.open { FakeSocket("replacement") })

        var deliveredFrames = 0
        assertFalse(
            lifecycle.ifCurrent(first.generation, first.socket) {
                deliveredFrames += 1
            },
        )
        assertTrue(
            lifecycle.ifCurrent(replacement.generation, replacement.socket) {
                deliveredFrames += 1
            },
        )

        assertEquals(1, deliveredFrames)
    }

    private class FakeSocket(val name: String)
}

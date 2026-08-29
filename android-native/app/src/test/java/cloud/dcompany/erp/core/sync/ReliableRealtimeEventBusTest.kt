package cloud.dcompany.erp.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReliableRealtimeEventBusTest {

    @Test
    fun `event published before process collector starts is retained`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bus = ReliableRealtimeEventBus(workerScope)
            bus.publish(RealtimeEvent.Changed("finance"))

            val received = CompletableDeferred<RealtimeEvent>()
            val collector = workerScope.launch {
                bus.events.collect { event ->
                    received.complete(event)
                }
            }

            assertEquals(
                RealtimeEvent.Changed("finance"),
                withTimeout(1_000) { received.await() },
            )
            collector.cancel()
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun `burst behind an active resource coalesces to one trailing invalidation`() {
        val pending = RealtimeEventAccumulator()

        pending.add(RealtimeEvent.Changed("gaming"))
        assertEquals(RealtimeEvent.Changed("gaming"), pending.takeNext())

        repeat(1_000) { pending.add(RealtimeEvent.Changed("gaming")) }

        assertEquals(RealtimeEvent.Changed("gaming"), pending.takeNext())
        assertNull(pending.takeNext())
    }

    @Test
    fun `reconnect supersedes pre-gap work but preserves post-gap changes`() {
        val pending = RealtimeEventAccumulator()

        pending.add(RealtimeEvent.Changed("orders"))
        pending.add(RealtimeEvent.Changed("finance"))
        pending.add(RealtimeEvent.ReconnectedAfterGap)
        pending.add(RealtimeEvent.Changed("inventory"))
        pending.add(RealtimeEvent.Changed("receipts"))

        assertEquals(RealtimeEvent.ReconnectedAfterGap, pending.takeNext())
        assertEquals(RealtimeEvent.Changed("inventory"), pending.takeNext())
        assertEquals(RealtimeEvent.Changed("receipts"), pending.takeNext())
        assertNull(pending.takeNext())
    }

    @Test
    fun `slow subscriber receives every distinct resource in a large burst`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bus = ReliableRealtimeEventBus(workerScope)
            val received = Channel<RealtimeEvent>(Channel.UNLIMITED)
            val releaseFirst = CompletableDeferred<Unit>()
            val collector = workerScope.launch {
                bus.events.collect { event ->
                    received.send(event)
                    if (event == RealtimeEvent.Changed("first")) releaseFirst.await()
                }
            }

            bus.publish(RealtimeEvent.Changed("first"))
            assertEquals(
                RealtimeEvent.Changed("first"),
                withTimeout(1_000) { received.receive() },
            )

            val expected = (1..100).map { RealtimeEvent.Changed("resource-$it") }
            expected.forEach(bus::publish)
            releaseFirst.complete(Unit)

            val actual = expected.indices.map {
                withTimeout(1_000) { received.receive() }
            }
            assertEquals(expected, actual)
            collector.cancel()
        } finally {
            workerScope.cancel()
        }
    }
}

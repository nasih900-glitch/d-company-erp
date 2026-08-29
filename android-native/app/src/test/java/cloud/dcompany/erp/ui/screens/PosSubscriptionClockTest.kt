package cloud.dcompany.erp.ui.screens

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PosSubscriptionClockTest {
    @Test
    fun clockIsColdAndRestartsWithFreshTimeForEachVisibleSubscription() = runBlocking {
        val reads = AtomicLong(0L)
        val clock = subscriptionClock(
            periodMillis = 30_000L,
            nowMillis = reads::incrementAndGet,
        )

        assertEquals(0L, reads.get())
        assertEquals(1L, clock.first())
        assertEquals(1L, reads.get())
        assertEquals(2L, clock.first())
        assertEquals(2L, reads.get())
    }
}

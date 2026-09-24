package cloud.dcompany.erp.core.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedGamingPullTest {

    @Test
    fun `board requests overlap but never exceed the cap and retain input order`() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        val result = mapInBoundedChunks((1..8).toList(), concurrency = 3) { item ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try {
                delay((4 - item % 3) * 10L)
                item * 10
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals((1..8).map { it * 10 }, result)
        assertEquals(3, maximum.get())
        assertEquals(0, active.get())
    }

    @Test
    fun `one failed item prevents a partial result`() = runBlocking {
        val failure = try {
            mapInBoundedChunks((1..6).toList(), concurrency = 3) { item ->
                if (item == 2) error("failed board item")
                delay(10)
                item
            }
            null
        } catch (exception: IllegalStateException) {
            exception
        }

        assertTrue(failure?.message == "failed board item")
    }
}

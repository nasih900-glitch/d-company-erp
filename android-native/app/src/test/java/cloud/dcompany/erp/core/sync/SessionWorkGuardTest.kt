package cloud.dcompany.erp.core.sync

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkGuardTest {

    @Test
    fun `paused old pass cannot publish feedback or enter another leg after scope switch`() =
        runBlocking {
            val monitor = Any()
            var currentGeneration = 1
            var feedback: String? = null
            val nextResourceLegs = AtomicInteger(0)
            val oldFeedbackPublished = AtomicBoolean(true)
            val oldPassPaused = CompletableDeferred<Unit>()
            val resumeOldPass = CompletableDeferred<Unit>()

            val guard = SessionWorkGuard<Int>(
                isCurrent = { lease ->
                    synchronized(monitor) { lease == currentGeneration }
                },
                publishIfCurrent = { lease, update ->
                    synchronized(monitor) {
                        if (lease == currentGeneration) {
                            update()
                            true
                        } else {
                            false
                        }
                    }
                },
            )

            val oldPass = async {
                guard.withLease(1) {
                    oldPassPaused.complete(Unit)
                    resumeOldPass.await()

                    oldFeedbackPublished.set(
                        guard.publishFromContext { feedback = "old employee failure" },
                    )
                    try {
                        guard.ensureCurrent(1)
                        nextResourceLegs.incrementAndGet()
                    } catch (_: CancellationException) {
                        // Expected: the old pass stops before its next resource.
                    }
                }
            }

            oldPassPaused.await()
            synchronized(monitor) { currentGeneration = 2 }
            guard.withLease(2) {
                assertTrue(guard.publishFromContext { feedback = "current employee ready" })
            }

            resumeOldPass.complete(Unit)
            oldPass.await()

            assertFalse(oldFeedbackPublished.get())
            assertEquals(0, nextResourceLegs.get())
            assertEquals("current employee ready", feedback)
        }
}

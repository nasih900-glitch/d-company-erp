package cloud.dcompany.erp.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CaptureFailureProbeTest {
    @Test fun successDoesNotObserveAndFailurePreservesIdentityAndDetails() {
        var captures = 0
        assertEquals(7, captureWithFailureObservation({ captures++; 7 }, { error("not failure") }))
        val cause = IllegalStateException("private cause")
        val original = AssertionError("private message", cause)
        val stack = original.stackTrace.clone()
        var observations = 0
        try {
            captureWithFailureObservation({ captures++; throw original }, {
                assertSame(original, it)
                observations++
                error("diagnostic failed")
            })
            fail("failure swallowed")
        } catch (actual: Throwable) {
            assertSame(original, actual)
            assertSame(cause, actual.cause)
            assertEquals("private message", actual.message)
            assertArrayEquals(stack, actual.stackTrace)
        }
        assertEquals(2, captures)
        assertEquals(1, observations)
    }

    @Test fun completedSampleAndExactCallbackRemoval() {
        var posted: Runnable? = null
        val result = captureFailureProbe({ posted = it; it.run(); true }, {
            assertSame(posted, it)
        }, { "immutable sample" })
        assertEquals(CaptureProbeStatus.COMPLETED, result.status)
        assertEquals("immutable sample", result.sample)
        assertTrue(result.postAccepted)
        assertTrue(result.waitFinished)
    }

    @Test fun rejectionAndCollectionFailureRemainClosed() {
        val rejected = captureFailureProbe({ false }, {}, { error("must not collect") }, { error("must not wait") })
        assertEquals(CaptureProbeStatus.POST_REJECTED, rejected.status)
        assertFalse(rejected.postAccepted)
        val failed = captureFailureProbe({ it.run(); true }, {}, { error("private collector error") })
        assertEquals(CaptureProbeStatus.COLLECTION_FAILED, failed.status)
        assertNull(failed.sample)
    }

    @Test fun timeoutPreventsQueuedCallbackFromCollecting() {
        lateinit var callback: Runnable
        var collected = false
        val result = captureFailureProbe({ callback = it; true }, {}, { collected = true; 9 }, { false })
        callback.run()
        assertEquals(CaptureProbeStatus.WAIT_EXPIRED, result.status)
        assertNull(result.sample)
        assertFalse(collected)
        assertFalse(result.waitFinished)
    }

    @Test fun startedCallbackCannotPublishAfterCallerCloses() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var worker: Thread
        val result = captureFailureProbe({ callback ->
            worker = Thread { callback.run() }.apply { start() }; true
        }, {}, {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            "late private sample"
        }, {
            check(started.await(2, TimeUnit.SECONDS))
            false
        })
        release.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(CaptureProbeStatus.WAIT_EXPIRED, result.status)
        assertNull(result.sample)
    }

    @Test fun completionMayWinDeadlineButActualWaitReturnIsRetained() {
        lateinit var callback: Runnable
        val result = captureFailureProbe({ callback = it; true }, {}, { 8 }, { callback.run(); false })
        assertEquals(CaptureProbeStatus.COMPLETED, result.status)
        assertEquals(8, result.sample)
        assertFalse(result.waitFinished)
    }

    @Test fun interruptedWaitRestoresFlagAndRemovesCallbackEvenWhenRemovalFails() {
        var removed = false
        try {
            val result = captureFailureProbe({ true }, { removed = true; error("remove failed") }, { 0 }, {
                throw InterruptedException("private")
            })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(removed)
            assertEquals(CaptureProbeStatus.COLLECTION_FAILED, result.status)
            assertNull(result.sample)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun postExceptionIsBestEffortAndCallbackIsRemoved() {
        var removed = false
        val result = captureFailureProbe({ error("private handler failure") }, { removed = true }, { 0 })
        assertTrue(removed)
        assertEquals(CaptureProbeStatus.COLLECTION_FAILED, result.status)
    }
}

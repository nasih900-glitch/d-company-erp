package cloud.dcompany.erp.ui.theme

import org.junit.Assert.*
import org.junit.Test

class BackgroundLifecycleTraceTest {
    @Test fun immutableOrderedSnapshotHasRelativeTimesAndClosedPhaseFacts() {
        var now = 70_000L
        val trace = BackgroundLifecycleTrace { now }
        trace.record(BackgroundPhase.RULE_ENTER)
        now += 12
        trace.record(BackgroundPhase.OWNER_BOUND)
        now += 8
        trace.record(BackgroundPhase.ACTIVITY_PRE_DESTROY, BackgroundLifecycle.CREATED, true, false)
        val saved = trace.close()
        assertEquals(listOf(1, 2, 3), saved.events.map { it.sequence })
        assertEquals(listOf(0L, 12L, 20L), saved.events.map { it.uptimeMs })
        assertNull(saved.events[0].lifecycle)
        assertEquals(BackgroundLifecycle.CREATED, saved.events[2].lifecycle)
        assertNull(trace.record(BackgroundPhase.ACTIVITY_DESTROYED, BackgroundLifecycle.DESTROYED, true, true))
        assertEquals(saved, trace.close())
    }

    @Test fun capBoundsEventsAndLogAdmissionsWithoutDropSpam() {
        val trace = BackgroundLifecycleTrace { 1L }
        var admitted = 0
        repeat(40) { if (trace.record(BackgroundPhase.RULE_ENTER) != null) admitted++ }
        val saved = trace.close()
        assertEquals(32, admitted)
        assertEquals(32, saved.events.size)
        assertEquals(8, saved.dropped)
        assertEquals((1..32).toList(), saved.events.map { it.sequence })
    }

    @Test fun phaseOnlyMarkersCannotSmuggleLifecycleReads() {
        val trace = BackgroundLifecycleTrace { 0L }
        assertThrows(IllegalStateException::class.java) {
            trace.record(BackgroundPhase.BODY_RETURNED, BackgroundLifecycle.RESUMED, false, false)
        }
        assertThrows(IllegalStateException::class.java) { trace.record(BackgroundPhase.ACTIVITY_STOPPED) }
        assertTrue(trace.close().events.isEmpty())
    }

    @Test fun nonMonotonicOrUnboundedOffsetsNeverEnterSnapshot() {
        var now = 10L
        val trace = BackgroundLifecycleTrace { now }
        trace.record(BackgroundPhase.RULE_ENTER)
        now = 9
        assertThrows(IllegalStateException::class.java) { trace.record(BackgroundPhase.OWNER_BOUND) }
        now = 86_400_011
        assertThrows(IllegalStateException::class.java) { trace.record(BackgroundPhase.OWNER_BOUND) }
        assertEquals(1, trace.close().events.size)
    }

    @Test fun closeRejectsRacingLateCallbacks() {
        val trace = BackgroundLifecycleTrace { 0L }
        trace.record(BackgroundPhase.RULE_ENTER)
        val saved = trace.close()
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val thread = Thread {
            runCatching { repeat(100) { assertNull(trace.record(BackgroundPhase.OWNER_BOUND)) } }
                .onFailure(failed::set)
        }
        thread.start()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertNull(failed.get())
        assertEquals(saved, trace.close())
    }

    @Test fun outerBodyEvaluatesOnceAndPreservesOriginalFailureDespiteDiagnosticErrors() {
        var calls = 0
        var observed = 0
        var closed = false
        val cause = IllegalArgumentException("cause")
        val original = IllegalStateException("original", cause)
        val stack = original.stackTrace.clone()
        try {
            evaluateBackgroundRuleBody(
                { calls++; throw original }, { fail("success marker") },
                { assertSame(original, it); observed++; error("observer failed") },
                { closed = true; error("cleanup failed") },
            )
            fail("failure swallowed")
        } catch (actual: Throwable) {
            assertSame(original, actual)
            assertSame(cause, actual.cause)
            assertArrayEquals(stack, actual.stackTrace)
        }
        assertEquals(1, calls)
        assertEquals(1, observed)
        assertTrue(closed)
    }

    @Test fun diagnosticErrorsNeverFailPassingBody() {
        var calls = 0
        var closed = false
        evaluateBackgroundRuleBody(
            { calls++ }, { error("marker failed") }, { fail("failure observer") },
            { closed = true; error("cleanup failed") },
        )
        assertEquals(1, calls)
        assertTrue(closed)
    }

    @Test fun closeSerializesWithAdmittedPublicationAndRejectsLaterLogs() {
        val trace = BackgroundLifecycleTrace { 0L }
        val publishing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closing = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                trace.record(BackgroundPhase.RULE_ENTER, publish = {
                    publishing.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                })
            }.onFailure(errors::set)
        }
        worker.start()
        assertTrue(publishing.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val closer = Thread { closing.countDown(); trace.close(); closed.countDown() }
        closer.start()
        assertTrue(closing.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(closed.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown()
        worker.join(2_000)
        closer.join(2_000)
        assertFalse(worker.isAlive)
        assertFalse(closer.isAlive)
        assertNull(errors.get())
        assertNull(trace.record(BackgroundPhase.OWNER_BOUND, publish = { fail("late log") }))
    }
}

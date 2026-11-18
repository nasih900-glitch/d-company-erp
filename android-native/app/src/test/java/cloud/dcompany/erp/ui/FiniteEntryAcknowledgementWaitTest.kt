package cloud.dcompany.erp.ui

import org.junit.Assert.*
import org.junit.Test

class FiniteEntryAcknowledgementWaitTest {
    private class Clock(previous: Boolean = true) : RailWaitClock {
        var real = 0L
        val origin = 1_616L
        override var autoAdvance = previous
        override var currentTime = origin
        var cost = 0L
        var polls = 0
        val advances = mutableListOf<Long>()
        val failure = AssertionError("fixed five-second deadline")
        var afterAdvance: () -> Unit = {}
        override fun advanceExact(milliseconds: Long) {
            assertFalse(autoAdvance)
            assertEquals(real / 1_000_000 - (currentTime - origin), milliseconds)
            assertTrue(milliseconds > 0)
            advances += milliseconds
            currentTime += milliseconds
            real += cost
            afterAdvance()
        }
        fun wait(predicate: () -> Boolean) = waitForFiniteEntryAcknowledgement(
            clock = this, timeout = { throw failure }, nanoTime = { real },
            sleepNanos = { assertTrue(it in 1..10_000_000L); real += it },
        ) { polls++; predicate() }
        fun timesOut(predicate: () -> Boolean) {
            try { wait(predicate); fail("accepted outside budget") } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
        }
    }

    @Test fun costlyFrameWorkStillCompletesFirstFramePlusFiniteTweenWithinRealBudget() {
        val c = Clock()
        c.cost = 280_000_000
        var firstFrame: Long? = null
        val acknowledgements = mutableListOf<Long>()
        c.wait {
            if (firstFrame == null && c.currentTime > c.origin) firstFrame = c.currentTime
            if (firstFrame != null && c.currentTime - firstFrame!! >= 260 && acknowledgements.isEmpty()) {
                acknowledgements += 94L
            }
            acknowledgements == listOf(94L)
        }
        assertEquals(listOf(94L), acknowledgements)
        assertTrue(c.real < 5_000_000_000)
        assertEquals(listOf(10L, 290L), c.advances)
        assertTrue(c.autoAdvance)
    }

    @Test fun absentWrongAndMultipleAcknowledgementsRemainFailures() {
        for (acknowledgements in listOf(emptyList(), listOf(93L), listOf(94L, 94L))) {
            val c = Clock()
            c.cost = 280_000_000
            c.timesOut { acknowledgements == listOf(94L) }
            assertTrue(c.autoAdvance)
        }
    }

    @Test fun callbackAt4999IsAcceptedBut5000And5001AreNot() {
        for (millis in listOf(4_999L, 5_000L, 5_001L)) {
            val c = Clock()
            val predicate = { c.real = millis * 1_000_000; true }
            if (millis < 5_000) c.wait(predicate) else c.timesOut(predicate)
            assertTrue(c.advances.isEmpty())
            assertTrue(c.autoAdvance)
        }
    }

    @Test fun blockedClockCrossingDeadlineDoesNoFurtherPredicateWork() {
        val c = Clock()
        c.cost = 5_000_000_000
        c.timesOut { false }
        assertEquals(1, c.polls)
        assertEquals(listOf(10L), c.advances)
    }

    @Test fun predicateFailureIdentityAndBothActualModesArePreserved() {
        for (previous in listOf(true, false)) {
            val c = Clock(previous)
            val failure = AssertionError("original predicate failure")
            try { c.wait { throw failure }; fail("failure swallowed") } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
            assertEquals(previous, c.autoAdvance)
            assertEquals(1, c.polls)
            assertTrue(c.advances.isEmpty())
        }
    }

    @Test fun interruptionIdentityAndInterruptFlagArePreservedWithoutAnotherPoll() {
        val c = Clock(false)
        val failure = InterruptedException("original interruption")
        try {
            try { c.wait { throw failure }; fail("interruption swallowed") } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(c.autoAdvance)
            assertEquals(1, c.polls)
        } finally { Thread.interrupted() }
    }

    @Test fun truePredicateCannotCreateVirtualLeadOrRewind() {
        for (delta in listOf(-1L, 1L)) {
            val c = Clock()
            try { c.wait { c.currentTime += delta; true }; fail("invalid clock accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(1, c.polls)
            assertTrue(c.advances.isEmpty())
            assertEquals(0L, c.real)
            assertTrue(c.autoAdvance)
        }
    }

    @Test fun overadvancingAdapterFailsBeforeAnotherPredicate() {
        val c = Clock(false)
        c.afterAdvance = { c.currentTime++ }
        try { c.wait { false }; fail("adapter lead accepted") } catch (_: IllegalStateException) { }
        assertEquals(1, c.polls)
        assertEquals(listOf(10L), c.advances)
        assertFalse(c.autoAdvance)
    }

    @Test fun successRestoresFalseModeAndZeroGapDoesNotAdvance() {
        val c = Clock(false)
        c.wait { true }
        assertFalse(c.autoAdvance)
        assertTrue(c.advances.isEmpty())
        assertEquals(c.origin, c.currentTime)
    }
}

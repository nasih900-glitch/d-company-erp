package cloud.dcompany.erp.ui

import org.junit.Assert.*
import org.junit.Test

class WallPacedRailWaitTest {
    private class Fixture(previous: Boolean = true, val origin: Long = 731) : RailWaitClock {
        var realNanos = 0L
        override var autoAdvance = previous
        override var currentTime = origin
        val advances = mutableListOf<Long>()
        val sleeps = mutableListOf<Long>()
        val timeout = AssertionError("fixed real deadline")
        var predicates = 0
        var advanceAction: () -> Unit = {}
        var sleepAction: (Long) -> Unit = { realNanos += it }
        override fun advanceExact(milliseconds: Long) {
            assertFalse(autoAdvance)
            assertTrue(milliseconds > 0)
            assertEquals(realNanos / 1_000_000 - (currentTime - origin), milliseconds)
            advances += milliseconds
            currentTime += milliseconds
            assertTrue(currentTime - origin <= realNanos / 1_000_000)
            advanceAction()
        }
        fun wait(predicate: () -> Boolean) = waitForWallPacedRailVisibility(
            clock = this, timeout = { throw timeout }, nanoTime = { realNanos },
            sleepNanos = {
                assertTrue(it > 0 && it <= 10_000_000 && it <= 5_000_000_000 - realNanos)
                sleeps += it
                sleepAction(it)
            },
        ) { predicates++; assertFalse(autoAdvance); predicate() }
        fun expectTimeout(predicate: () -> Boolean) {
            try { wait(predicate); fail("late success") } catch (actual: Throwable) { assertSame(timeout, actual) }
        }
    }

    @Test fun trueAt4999msIsAccepted() {
        val f = Fixture()
        f.wait { f.realNanos = 4_999_000_000; true }
        assertTrue(f.autoAdvance)
        assertEquals(1, f.predicates)
    }

    @Test fun trueAt5000msIsRejected() {
        val f = Fixture()
        f.expectTimeout { f.realNanos = 5_000_000_000; true }
        assertTrue(f.autoAdvance)
        assertTrue(f.sleeps.isEmpty())
    }

    @Test fun trueAt5001msIsRejected() {
        val f = Fixture()
        f.expectTimeout { f.realNanos = 5_001_000_000; true }
        assertEquals(1, f.predicates)
    }

    @Test fun exactSevenMsGapFromNonzeroOriginIsUnrounded() {
        val f = Fixture(origin = 7_013)
        f.sleepAction = {}
        f.wait { f.realNanos += 7_000_000; f.predicates == 2 }
        assertEquals(listOf(7L), f.advances)
        assertEquals(7_020, f.currentTime)
    }

    @Test fun repeatedObservationsAdvanceOnlyGapWithoutAccumulatingElapsedAgain() {
        val f = Fixture()
        f.sleepAction = {}
        f.wait { f.realNanos += 7_900_000; f.predicates == 4 }
        assertEquals(listOf(7L, 8L, 8L), f.advances)
        assertEquals(23, f.currentTime - f.origin)
    }

    @Test fun zeroGapNeverAdvances() {
        val f = Fixture()
        f.sleepAction = {}
        f.wait { f.predicates == 2 }
        assertTrue(f.advances.isEmpty())
        assertEquals(f.origin, f.currentTime)
    }

    @Test fun predicateCreatedLeadIsRejectedEvenOnTrueWithoutWaitingItOut() {
        for (satisfied in listOf(true, false)) {
            val f = Fixture()
            try {
                f.wait { f.currentTime += 3; satisfied }
                fail("virtual lead accepted")
            } catch (actual: IllegalStateException) {
                assertEquals("Rail test clock escaped the real-time budget", actual.message)
            }
            assertEquals(0L, f.realNanos)
            assertEquals(f.origin + 3, f.currentTime)
            assertTrue(f.sleeps.isEmpty())
            assertTrue(f.advances.isEmpty())
            assertTrue(f.autoAdvance)
        }
    }

    @Test fun advanceCreatedLeadIsRejectedBeforeAnotherPredicate() {
        val f = Fixture(previous = false)
        f.advanceAction = { f.currentTime++ }
        try { f.wait { false }; fail("virtual lead accepted") } catch (actual: IllegalStateException) {
            assertEquals("Rail test clock escaped the real-time budget", actual.message)
        }
        assertEquals(1, f.predicates)
        assertEquals(listOf(10L), f.advances)
        assertEquals(listOf(10_000_000L), f.sleeps)
        assertFalse(f.autoAdvance)
    }

    @Test fun predicateRewindIsRejectedEvenOnTrueWithoutCompensatingAdvance() {
        val f = Fixture()
        try { f.wait { f.currentTime--; true }; fail("rewind accepted") } catch (actual: IllegalStateException) {
            assertEquals("Rail test clock escaped the real-time budget", actual.message)
        }
        assertEquals(f.origin - 1, f.currentTime)
        assertTrue(f.advances.isEmpty())
        assertTrue(f.sleeps.isEmpty())
        assertTrue(f.autoAdvance)
    }

    @Test fun negativeInitialVirtualElapsedIsRejectedBeforeAdvanceOrPredicate() {
        var reads = 0
        val clock = object : RailWaitClock {
            override var autoAdvance = true
            override val currentTime: Long get() = if (++reads == 1) 731L else 730L
            override fun advanceExact(milliseconds: Long) { fail("compensating advance") }
        }
        try {
            waitForWallPacedRailVisibility(clock, { fail("timeout"); error("unreachable") }, { 0 }, { fail("sleep") }) {
                fail("predicate"); true
            }
            fail("negative elapsed accepted")
        } catch (actual: IllegalStateException) {
            assertEquals("Rail test clock escaped the real-time budget", actual.message)
        }
        assertTrue(clock.autoAdvance)
    }

    @Test fun leadBetweenObservationsIsRejectedBeforeAdvanceOrPredicate() {
        var reads = 0
        val clock = object : RailWaitClock {
            override var autoAdvance = true
            override val currentTime: Long get() = if (++reads == 1) 731L else 734L
            override fun advanceExact(milliseconds: Long) { fail("advance with existing lead") }
        }
        try {
            waitForWallPacedRailVisibility(clock, { fail("timeout"); error("unreachable") }, { 0 }, { fail("sleep") }) {
                fail("predicate"); true
            }
            fail("virtual lead accepted")
        } catch (actual: IllegalStateException) {
            assertEquals("Rail test clock escaped the real-time budget", actual.message)
        }
        assertEquals(2, reads)
        assertTrue(clock.autoAdvance)
    }

    @Test fun deadlineAfterClockReadHasPriorityOverInvalidVirtualLead() {
        var reads = 0
        var real = 0L
        val original = AssertionError("deadline")
        val clock = object : RailWaitClock {
            override var autoAdvance = true
            override val currentTime: Long
                get() = if (++reads == 1) 731L else { real = 5_000_000_000; 5_732L }
            override fun advanceExact(milliseconds: Long) { fail("after deadline") }
        }
        try {
            waitForWallPacedRailVisibility(clock, { throw original }, { real }, { fail("sleep") }) { fail("predicate"); true }
            fail("deadline accepted")
        } catch (actual: Throwable) { assertSame(original, actual) }
        assertTrue(clock.autoAdvance)
    }

    @Test fun advanceCrossingDeadlineStopsBeforeAnotherPredicate() {
        val f = Fixture()
        f.advanceAction = { f.realNanos = 5_000_000_000 }
        f.expectTimeout { false }
        assertEquals(1, f.predicates)
        assertEquals(listOf(10L), f.advances)
        assertTrue(f.autoAdvance)
    }

    @Test fun oversleepStopsBeforeAnotherAdvanceOrPredicate() {
        val f = Fixture()
        f.sleepAction = { f.realNanos = 5_001_000_000 }
        f.expectTimeout { false }
        assertEquals(1, f.predicates)
        assertTrue(f.advances.isEmpty())
        assertEquals(listOf(10_000_000L), f.sleeps)
    }

    @Test fun falsePredicateHasOneFixedBudgetAndNeverRunsAtDeadline() {
        val f = Fixture()
        f.expectTimeout { assertTrue(f.realNanos < 5_000_000_000); false }
        assertEquals(5_000_000_000, f.realNanos)
        assertEquals(500, f.predicates)
        assertEquals(500, f.sleeps.size)
        assertEquals(4_990, f.currentTime - f.origin)
    }

    @Test fun fractionalRemainingBudgetBoundsSleepRequest() {
        val f = Fixture()
        f.expectTimeout { f.realNanos = 4_999_750_000; false }
        assertEquals(listOf(250_000L), f.sleeps)
        assertEquals(1, f.predicates)
    }

    @Test fun slowPredicatesCannotStarveDueVirtualWorkOrCreateLead() {
        val f = Fixture()
        f.wait {
            f.realNanos += 200_000_000
            (f.currentTime - f.origin) / 16 >= 50
        }
        assertTrue(f.realNanos < 5_000_000_000)
        assertTrue(f.currentTime - f.origin >= 800)
        assertTrue(f.currentTime - f.origin <= f.realNanos / 1_000_000)
    }

    @Test fun falsePriorModeIsRestoredOnSuccessTimeoutAndFailure() {
        val success = Fixture(previous = false)
        success.wait { true }
        assertFalse(success.autoAdvance)
        val timeout = Fixture(previous = false)
        timeout.expectTimeout { timeout.realNanos = 5_000_000_000; false }
        assertFalse(timeout.autoAdvance)
        val failed = Fixture(previous = false)
        val original = IllegalStateException("predicate")
        try { failed.wait { throw original }; fail("swallowed") } catch (actual: Throwable) { assertSame(original, actual) }
        assertFalse(failed.autoAdvance)
    }

    @Test fun predicateFailureRetainsOriginalIdentityAndRestoresTrueMode() {
        val f = Fixture()
        val original = IllegalStateException("predicate")
        try { f.wait { throw original }; fail("swallowed") } catch (actual: Throwable) { assertSame(original, actual) }
        assertTrue(f.autoAdvance)
    }

    @Test fun advanceFailureRetainsOriginalIdentityAndRestoresMode() {
        val f = Fixture()
        val original = IllegalStateException("clock")
        f.advanceAction = { throw original }
        try { f.wait { false }; fail("swallowed") } catch (actual: Throwable) { assertSame(original, actual) }
        assertTrue(f.autoAdvance)
    }

    @Test fun interruptedSleepRetainsOriginalIdentityRestoresModeAndInterruptFlag() {
        val f = Fixture()
        val original = InterruptedException("sleep")
        f.sleepAction = { throw original }
        try {
            try { f.wait { false }; fail("swallowed") } catch (actual: Throwable) { assertSame(original, actual) }
            assertTrue(f.autoAdvance)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun originalModeReadCostConsumesSameAcceptanceBudget() {
        var real = 0L
        var mode = true
        val original = AssertionError("deadline")
        val clock = object : RailWaitClock {
            override var autoAdvance: Boolean
                get() { real += 5_000_000_000; return mode }
                set(value) { mode = value }
            override val currentTime = 400L
            override fun advanceExact(milliseconds: Long) { fail("after deadline") }
        }
        try {
            waitForWallPacedRailVisibility(clock, { throw original }, { real }, { fail("sleep") }) { fail("predicate"); true }
            fail("swallowed")
        } catch (actual: Throwable) { assertSame(original, actual) }
        assertTrue(mode)
    }

    @Test fun publicClockReadCrossingDeadlineDoesNotAdvanceOrEvaluatePredicate() {
        var real = 0L
        var reads = 0
        val original = AssertionError("deadline")
        val clock = object : RailWaitClock {
            override var autoAdvance = true
            override val currentTime: Long
                get() { if (++reads == 2) real = 5_000_000_000; return 400L }
            override fun advanceExact(milliseconds: Long) { fail("after deadline") }
        }
        try {
            waitForWallPacedRailVisibility(clock, { throw original }, { real }, { fail("sleep") }) { fail("predicate"); true }
            fail("swallowed")
        } catch (actual: Throwable) { assertSame(original, actual) }
        assertTrue(clock.autoAdvance)
        assertEquals(2, reads)
    }
}

package cloud.dcompany.erp.ui

import java.util.concurrent.TimeUnit

internal interface RailWaitClock {
    var autoAdvance: Boolean
    val currentTime: Long
    fun advanceExact(milliseconds: Long)
}

/** Real-time acceptance guard, not preemption of a blocked clock or predicate. */
internal fun waitForWallPacedRailVisibility(
    clock: RailWaitClock,
    timeout: () -> Nothing,
    nanoTime: () -> Long = System::nanoTime,
    sleepNanos: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
    predicate: () -> Boolean,
) {
    val realOrigin = nanoTime()
    val previousAutoAdvance = clock.autoAdvance
    var originalFailure: Throwable? = null
    try {
        val virtualOrigin = clock.currentTime
        clock.autoAdvance = false
        fun elapsed(): Long = (nanoTime() - realOrigin).also { check(it >= 0) }
        fun remaining(): Long = (5_000_000_000L - elapsed()).also { if (it <= 0) timeout() }
        fun checkedGap(): Long {
            remaining()
            val virtualElapsed = clock.currentTime - virtualOrigin
            // Include public-clock read time before scheduling any work.
            val realElapsedMs = (5_000_000_000L - remaining()) / 1_000_000L
            check(virtualElapsed >= 0 && virtualElapsed <= realElapsedMs) {
                "Rail test clock escaped the real-time budget"
            }
            return realElapsedMs - virtualElapsed
        }
        while (true) {
            val gap = checkedGap()
            if (gap > 0) clock.advanceExact(gap)
            checkedGap()
            val satisfied = predicate()
            checkedGap()
            val left = remaining()
            if (satisfied) return
            sleepNanos(minOf(10_000_000L, left))
        }
    } catch (failure: Throwable) {
        originalFailure = failure
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        throw failure
    } finally {
        try {
            clock.autoAdvance = previousAutoAdvance
        } catch (restoreFailure: Throwable) {
            // A second clock failure must not replace the original failure.
            if (originalFailure == null) throw restoreFailure
        }
    }
}

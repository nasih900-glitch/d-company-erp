package cloud.dcompany.erp.ui

import java.util.concurrent.TimeUnit

/** Pace finite cosmetic entry acknowledgements under the unchanged real five-second guard. */
internal fun waitForFiniteEntryAcknowledgement(
    clock: RailWaitClock,
    timeout: () -> Nothing,
    nanoTime: () -> Long = System::nanoTime,
    sleepNanos: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
    predicate: () -> Boolean,
) = waitForWallPacedRailVisibility(clock, timeout, nanoTime, sleepNanos, predicate)

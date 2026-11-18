package cloud.dcompany.erp.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal const val CAPTURE_PROBE_WAIT_MS = 150L
internal enum class CaptureProbeStatus { COMPLETED, WAIT_EXPIRED, POST_REJECTED, COLLECTION_FAILED }
internal data class CaptureProbeResult<T>(
    val status: CaptureProbeStatus,
    val postAccepted: Boolean,
    val waitFinished: Boolean,
    val sample: T?,
)

private sealed interface CaptureProbeState<out T> {
    data object Pending : CaptureProbeState<Nothing>
    data class Completed<T>(val value: T) : CaptureProbeState<T>
    data class Closed(val reason: CaptureProbeStatus) : CaptureProbeState<Nothing>
}

/** Only the caller wait is bounded. A callback already running cannot publish after closure. */
internal fun <T> captureFailureProbe(
    post: (Runnable) -> Boolean,
    remove: (Runnable) -> Unit,
    collect: () -> T,
    await: (CountDownLatch) -> Boolean = { it.await(CAPTURE_PROBE_WAIT_MS, TimeUnit.MILLISECONDS) },
): CaptureProbeResult<T> {
    val state = AtomicReference<CaptureProbeState<T>>(CaptureProbeState.Pending)
    val ready = CountDownLatch(1)
    val callback = Runnable {
        try {
            if (state.get() === CaptureProbeState.Pending) {
                val sample = collect()
                state.compareAndSet(CaptureProbeState.Pending, CaptureProbeState.Completed(sample))
            }
        } catch (_: Throwable) {
            state.compareAndSet(CaptureProbeState.Pending, CaptureProbeState.Closed(CaptureProbeStatus.COLLECTION_FAILED))
        } finally {
            ready.countDown()
        }
    }
    var accepted = false
    var finished = false
    try {
        accepted = post(callback)
        if (accepted) finished = await(ready)
        state.compareAndSet(CaptureProbeState.Pending, CaptureProbeState.Closed(
            if (accepted) CaptureProbeStatus.WAIT_EXPIRED else CaptureProbeStatus.POST_REJECTED,
        ))
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        state.compareAndSet(CaptureProbeState.Pending, CaptureProbeState.Closed(CaptureProbeStatus.COLLECTION_FAILED))
    } catch (_: Throwable) {
        state.compareAndSet(CaptureProbeState.Pending, CaptureProbeState.Closed(CaptureProbeStatus.COLLECTION_FAILED))
    } finally {
        runCatching { remove(callback) }
    }
    return when (val frozen = state.get()) {
        is CaptureProbeState.Completed -> CaptureProbeResult(CaptureProbeStatus.COMPLETED, accepted, finished, frozen.value)
        is CaptureProbeState.Closed -> CaptureProbeResult(frozen.reason, accepted, finished, null)
        CaptureProbeState.Pending -> error("Capture probe was not closed")
    }
}

internal fun <T> captureWithFailureObservation(capture: () -> T, observe: (Throwable) -> Unit): T =
    try {
        capture()
    } catch (failure: Throwable) {
        runCatching { observe(failure) }
        throw failure
    }

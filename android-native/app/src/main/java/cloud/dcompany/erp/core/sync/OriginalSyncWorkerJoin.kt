package cloud.dcompany.erp.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lifetime completion only. Cancellation/empty jobs never replace the separate unresolved/domain checks. */
internal suspend fun awaitOriginalWorkerLifetimes(
    timeoutMillis: Long,
    requireOriginalClosed: suspend () -> Unit,
    snapshot: () -> List<Job>,
): Boolean {
    require(timeoutMillis in 1..300_000)
    return try {
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                requireOriginalClosed()
                val workers = snapshot().filterNot { it.isCompleted }
                if (workers.isEmpty()) {
                    requireOriginalClosed()
                    if (snapshot().none { !it.isCompleted }) return@withTimeoutOrNull true
                } else workers.joinAll()
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

/** Register first, attach exact-job completion cleanup second, and only then allow the LAZY body to start. */
internal fun launchRegisteredSessionWorker(
    scope: kotlinx.coroutines.CoroutineScope,
    register: (Job) -> Boolean,
    unregister: (Job) -> Unit,
    onRegistrationRejected: () -> Unit,
    block: suspend (() -> Unit) -> Unit,
): Job {
    val entered = java.util.concurrent.atomic.AtomicBoolean(false)
    val rejected = java.util.concurrent.atomic.AtomicBoolean(false)
    fun rejectOnce() { if (rejected.compareAndSet(false, true)) onRegistrationRejected() }
    val worker = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        entered.set(true); block(::rejectOnce)
    }
    val registered = try { register(worker) } catch (failure: Throwable) {
        rejectOnce(); worker.cancel(); throw failure
    }
    worker.invokeOnCompletion {
        unregister(worker)
        if (!entered.get()) rejectOnce()
    }
    if (registered) worker.start() else {
        rejectOnce(); worker.cancel(kotlinx.coroutines.CancellationException("Authenticated sync scope changed"))
    }
    return worker
}

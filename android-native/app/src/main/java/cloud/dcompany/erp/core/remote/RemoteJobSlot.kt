package cloud.dcompany.erp.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Exact job bookkeeping only; idle is never proof that a Remote journal/server action settled. */
internal class RemoteJobSlot<O : Any> {
    private class Entry<O>(val owner: O, val job: Job)
    private val lock = Any()
    private var current: Entry<O>? = null

    fun reserve(scope: CoroutineScope, owner: O, block: suspend CoroutineScope.() -> Unit): Job? = synchronized(lock) {
        if (current?.job?.isCompleted == false) return@synchronized null
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        val entry = Entry(owner, job)
        current = entry
        // An already-cancelled parent's completion can run synchronously here.
        job.invokeOnCompletion { synchronized(lock) { if (current === entry) current = null } }
        job
    }

    fun launch(scope: CoroutineScope, owner: O, block: suspend CoroutineScope.() -> Unit): Job? =
        reserve(scope, owner, block)?.also { it.start() }

    fun snapshot(owner: O? = null): Job? = synchronized(lock) {
        current?.takeIf { owner == null || it.owner == owner }?.job
    }

    /** Retain the exact cancelled handle until its real finally/children finish; join outside this lock. */
    fun cancel(owner: O? = null): Job? {
        val captured = snapshot(owner)
        // Completion callbacks may run synchronously; never run them under this monitor.
        captured?.cancel()
        return captured
    }
}

/** A finished poll only schedules a fresh-owner check; it never retargets the old operation. */
internal fun restartRemotePollAfterCompletion(job: Job, scope: CoroutineScope,
    restartRequested: () -> Boolean, ensurePolling: () -> Unit) {
    job.invokeOnCompletion {
        scope.launch(Dispatchers.Default) { if (restartRequested()) ensurePolling() }
    }
}

package cloud.dcompany.erp.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class OriginalSyncWorkerJoinTest {
    @Test fun joinsActualStructuredChildLifetimeOutsideProviderLock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val guard = Any(); val workers = mutableListOf<kotlinx.coroutines.Job>()
        val worker = scope.launch { launch { entered.complete(Unit); release.await() } }
        synchronized(guard) { workers += worker }
        worker.invokeOnCompletion { synchronized(guard) { workers.remove(worker) } }
        try {
            entered.await()
            val result = async { awaitOriginalWorkerLifetimes(2_000, { synchronized(guard) { Unit } }, { synchronized(guard) { workers.toList() } }) }
            yield(); assertFalse(result.isCompleted)
            release.complete(Unit); assertTrue(result.await())
        } finally { scope.cancel() }
    }
    @Test fun cancelledParentWithStillRunningCleanupChildIsNotYetJoined() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val worker = scope.launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        try {
            entered.await(); worker.cancel()
            assertFalse(awaitOriginalWorkerLifetimes(10, {}, { listOf(worker).filterNot { it.isCompleted } }))
            release.complete(Unit)
            assertTrue(awaitOriginalWorkerLifetimes(2_000, {}, { listOf(worker).filterNot { it.isCompleted } }))
            assertTrue(worker.isCancelled)
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun lateWorkerRegistrationIsIncludedAndStaleAuthorityCannotReportDone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstRelease = CompletableDeferred<Unit>(); val lateRelease = CompletableDeferred<Unit>()
        val first = scope.launch { firstRelease.await() }
        val late = scope.launch { lateRelease.await() }
        var calls = 0
        try {
            val result = async { awaitOriginalWorkerLifetimes(2_000, {}, {
                calls++; if (calls == 1) listOf(first) else listOf(first, late).filterNot { it.isCompleted }
            }) }
            yield(); firstRelease.complete(Unit); first.join(); yield()
            assertFalse(result.isCompleted)
            lateRelease.complete(Unit); assertTrue(result.await())
            var current = true
            assertFalse(awaitOriginalWorkerLifetimes(100, { check(current) }, { current = false; emptyList() }))
        } finally { firstRelease.complete(Unit); lateRelease.complete(Unit); scope.cancel() }
    }
    @Test fun completedRetainedMapEntryCannotCreateAnUnboundedJoinLoop() = runBlocking {
        val completed = kotlinx.coroutines.Job().also { it.complete() }
        assertTrue(awaitOriginalWorkerLifetimes(100, {}, { listOf(completed) }))
    }

}

package cloud.dcompany.erp.core.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedResourceRefreshRunnerTest {

    @Test
    fun `requests during an active pull share exactly one trailing pull`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = ConflatedResourceRefreshRunner(workerScope)
            val calls = AtomicInteger(0)
            val activeCalls = AtomicInteger(0)
            val maxActiveCalls = AtomicInteger(0)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val refresh: suspend () -> ResourceRefreshResult = {
                val call = calls.incrementAndGet()
                val active = activeCalls.incrementAndGet()
                maxActiveCalls.updateAndGet { current -> maxOf(current, active) }
                try {
                    if (call == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    ResourceRefreshResult.Refreshed("kitchen")
                } finally {
                    activeCalls.decrementAndGet()
                }
            }

            val first = async { runner.run("kitchen", refresh) }
            firstStarted.await()
            // Register the whole burst before releasing the first pass. Default
            // runBlocking scheduling could otherwise let a very fast trailing
            // pass finish before the later test coroutines have even started.
            val burst = List(100) {
                async(start = CoroutineStart.UNDISPATCHED) { runner.run("kitchen", refresh) }
            }

            releaseFirst.complete(Unit)
            assertTrue(first.await() is ResourceRefreshResult.Refreshed)
            burst.forEach { assertTrue(it.await() is ResourceRefreshResult.Refreshed) }

            assertEquals(2, calls.get())
            assertEquals(1, maxActiveCalls.get())
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun `different resources can refresh independently`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = ConflatedResourceRefreshRunner(workerScope)
            val kitchenStarted = CompletableDeferred<Unit>()
            val tablesStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val kitchen = async {
                runner.run("kitchen") {
                    kitchenStarted.complete(Unit)
                    release.await()
                    ResourceRefreshResult.Refreshed("kitchen")
                }
            }
            val tables = async {
                runner.run("tables") {
                    tablesStarted.complete(Unit)
                    release.await()
                    ResourceRefreshResult.Refreshed("tables")
                }
            }

            kitchenStarted.await()
            tablesStarted.await()
            assertFalse(kitchen.isCompleted)
            assertFalse(tables.isCompleted)

            release.complete(Unit)
            assertEquals(ResourceRefreshResult.Refreshed("kitchen"), kitchen.await())
            assertEquals(ResourceRefreshResult.Refreshed("tables"), tables.await())
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun `different authenticated cohorts never share a refresh result`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = ConflatedResourceRefreshRunner(workerScope)
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val first = async {
                runner.run("gaming", cohort = 41L) {
                    firstStarted.complete(Unit)
                    release.await()
                    ResourceRefreshResult.Refreshed("gaming")
                }
            }
            firstStarted.await()
            val second = async {
                runner.run("gaming", cohort = 42L) {
                    secondStarted.complete(Unit)
                    release.await()
                    ResourceRefreshResult.Refreshed("gaming")
                }
            }

            withTimeout(1_000) { secondStarted.await() }
            release.complete(Unit)
            assertEquals(ResourceRefreshResult.Refreshed("gaming"), first.await())
            assertEquals(ResourceRefreshResult.Refreshed("gaming"), second.await())
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun `session teardown cancels an old pull before the next cohort starts`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = ConflatedResourceRefreshRunner(workerScope)
            val oldStarted = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            val old = async {
                runner.run("gaming", cohort = 51L) {
                    oldStarted.complete(Unit)
                    neverRelease.await()
                    ResourceRefreshResult.Refreshed("gaming")
                }
            }
            oldStarted.await()

            runner.cancelAll()
            try {
                old.await()
                throw AssertionError("The previous session refresh should be cancelled")
            } catch (_: CancellationException) {
                // Expected: no old authenticated request may convoy the new one.
            }

            val current = withTimeout(1_000) {
                runner.run("gaming", cohort = 52L) {
                    ResourceRefreshResult.Refreshed("gaming")
                }
            }
            assertEquals(ResourceRefreshResult.Refreshed("gaming"), current)
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun `late completion from cancelled slot cannot remove replacement slot with same key`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = ConflatedResourceRefreshRunner(workerScope)
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val old = async {
                runner.run("gaming", cohort = 61L) {
                    oldStarted.complete(Unit)
                    // Model a library call that delays cancellation cleanup.
                    withContext(NonCancellable) { releaseOld.await() }
                    ResourceRefreshResult.Refreshed("old")
                }
            }
            oldStarted.await()
            runner.cancelAll()
            try {
                old.await()
                throw AssertionError("The detached waiter should be cancelled")
            } catch (_: CancellationException) {
                // Expected; the worker itself is deliberately still unwinding.
            }

            val replacementStarted = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            val replacement = async {
                runner.run("gaming", cohort = 61L) {
                    replacementStarted.complete(Unit)
                    releaseReplacement.await()
                    ResourceRefreshResult.Refreshed("new")
                }
            }
            replacementStarted.await()

            releaseOld.complete(Unit)
            releaseReplacement.complete(Unit)
            assertEquals(
                ResourceRefreshResult.Refreshed("new"),
                withTimeout(1_000) { replacement.await() },
            )
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun `cancelling one waiter does not cancel the shared trailing pull`() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = ConflatedResourceRefreshRunner(workerScope)
            val calls = AtomicInteger(0)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val trailingStarted = CompletableDeferred<Unit>()

            val refresh: suspend () -> ResourceRefreshResult = {
                when (calls.incrementAndGet()) {
                    1 -> {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    2 -> trailingStarted.complete(Unit)
                }
                ResourceRefreshResult.Refreshed("orders")
            }

            val first = async { runner.run("orders", refresh) }
            firstStarted.await()
            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                runner.run("orders", refresh)
            }
            val survivingWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                runner.run("orders", refresh)
            }
            cancelledWaiter.cancelAndJoin()

            releaseFirst.complete(Unit)
            first.await()
            trailingStarted.await()
            assertEquals(ResourceRefreshResult.Refreshed("orders"), survivingWaiter.await())
            assertEquals(2, calls.get())
        } finally {
            workerScope.cancel()
        }
    }
}

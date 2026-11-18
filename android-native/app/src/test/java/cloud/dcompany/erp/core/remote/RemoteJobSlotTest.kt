package cloud.dcompany.erp.core.remote

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

class RemoteJobSlotTest {
    @Test fun cancellationRetainsExactJobUntilRealCleanupAndRefusesPrematureReplacement() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val slot = RemoteJobSlot<String>(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = requireNotNull(slot.launch(scope, "a") {
                try { entered.complete(Unit); awaitCancellation() } finally { withContext(NonCancellable) { release.await(); assertNotNull(slot.snapshot("a")) } }
            })
            entered.await(); assertSame(old, slot.cancel("a")); assertFalse(old.isCompleted)
            assertSame(old, slot.snapshot("a")); assertNull(slot.launch(scope, "b") { error("must not start before old finally") })
            release.complete(Unit); old.join(); assertNull(slot.snapshot())
        } finally { scope.cancel() }
    }
    @Test fun staleOwnerCancellationCannotRemoveOrCancelBReplacement() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val slot = RemoteJobSlot<String>(); val first = requireNotNull(slot.launch(scope, "a") {})
            first.join(); val release = CompletableDeferred<Unit>()
            val second = requireNotNull(slot.launch(scope, "b") { release.await() })
            assertNull(slot.cancel("a")); assertSame(second, slot.snapshot("b")); assertFalse(second.isCancelled)
            release.complete(Unit); second.join(); assertNull(slot.snapshot())
        } finally { scope.cancel() }
    }
    @Test fun alreadyCancelledParentNeverRunsBodyOrRetainsReservation() = runBlocking<Unit> {
        val parent = SupervisorJob(); parent.cancel(); val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val slot = RemoteJobSlot<String>(); var body = 0
        val job = requireNotNull(slot.launch(scope, "a") { body++ })
        job.join(); assertEquals(0, body); assertTrue(job.isCancelled); assertNull(slot.snapshot())
    }
    @Test fun cancellationBeforeLazyStartReleasesOnlyThatReservation() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val slot = RemoteJobSlot<String>(); var body = 0
            val job = requireNotNull(slot.reserve(scope, "a") { body++ })
            assertSame(job, slot.snapshot("a")); job.cancelAndJoin(); job.start()
            assertEquals(0, body); assertNull(slot.snapshot())
            val b = requireNotNull(slot.launch(scope, "b") { body++ }); b.join(); assertEquals(1, body)
        } finally { scope.cancel() }
    }
    @Test fun allThreeRegisteredLazySlotsRefuseSecondEntrantsBeforeAnyStart() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            for (name in listOf("poll", "capture", "command")) {
                val slot = RemoteJobSlot<String>(); var bodies = 0
                val release = CompletableDeferred<Unit>()
                val first = requireNotNull(slot.reserve(scope, "a") { bodies++; release.await() })
                assertFalse(name, first.isActive); assertFalse(name, first.isCompleted)
                assertNull(name, slot.reserve(scope, "b") { error("unowned second $name must not start") })
                assertSame(first, slot.snapshot("a")); first.start(); assertEquals(1, bodies)
                release.complete(Unit); first.join(); assertNull(slot.snapshot())
            }
        } finally { scope.cancel() }
    }

    @Test fun synchronousCancellationCompletionRunsOutsideSlotMonitorAndCannotEraseNewerEntry() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val slot = RemoteJobSlot<String>(); var bodies = 0
            val monitor = slot.javaClass.getDeclaredField("lock").let { it.isAccessible = true; it.get(slot) }
            val old = requireNotNull(slot.reserve(scope, "a") { error("cancelled lazy body") })
            var replacement: Job? = null; var callbackOutsideMonitor = false
            old.invokeOnCompletion {
                callbackOutsideMonitor = !Thread.holdsLock(monitor)
                replacement = requireNotNull(slot.reserve(scope, "b") { bodies++ })
            }
            assertSame(old, slot.cancel("a")); old.join(); old.start()
            assertTrue("cancel must release the real slot monitor before synchronous completion", callbackOutsideMonitor)
            assertSame(replacement, slot.snapshot("b")); assertNull(slot.cancel("a"))
            old.cancel(); assertSame(replacement, slot.snapshot("b"))
            requireNotNull(replacement).start(); requireNotNull(replacement).join(); assertEquals(1, bodies)
        } finally { scope.cancel() }
    }

    @Test fun actualPollRestartSchedulerWaitsForFullOldFinallyThenCapturesCurrentB() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val slot = RemoteJobSlot<String>(); val entered = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>(); val restarted = CompletableDeferred<String>()
            val old = requireNotNull(slot.reserve(scope, "a") {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            })
            val currentOwner = java.util.concurrent.atomic.AtomicReference("a"); val requested = java.util.concurrent.atomic.AtomicBoolean(false)
            restartRemotePollAfterCompletion(old, scope, requested::get) {
                val captured = currentOwner.get()
                val next = slot.reserve(scope, captured) { restarted.complete(captured) }
                requested.set(next == null); next?.start()
            }
            old.start(); entered.await(); slot.cancel("a")
            currentOwner.set("b")
            val premature = slot.reserve(scope, currentOwner.get()) { error("B must wait for actual A finally") }
            requested.set(premature == null); assertNull(premature); assertSame(old, slot.snapshot("a"))
            assertFalse(old.isCompleted); assertFalse(restarted.isCompleted)
            cleanup.complete(Unit); old.join()
            assertEquals("b", withTimeout(3_000) { restarted.await() })
            assertNull(slot.snapshot("a")); assertFalse(requested.get())
        } finally { scope.cancel() }
    }

    @Test fun delayedOldCompletionCannotClearANewerReservedJob() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val slot = RemoteJobSlot<String>(); val old = requireNotNull(slot.reserve(scope, "a") {})
            val monitor = slot.javaClass.getDeclaredField("lock").let { it.isAccessible = true; it.get(slot) }
            val oldHandlersFinished = java.util.concurrent.CountDownLatch(1)
            old.invokeOnCompletion { oldHandlersFinished.countDown() }
            var replacement: Job? = null
            synchronized(monitor) {
                old.start()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (!old.isCompleted && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue("old completion must be waiting at the real slot monitor", old.isCompleted)
                assertEquals(1L, oldHandlersFinished.count)
                replacement = requireNotNull(slot.reserve(scope, "b") {})
                assertSame(replacement, slot.snapshot("b"))
            }
            assertTrue(oldHandlersFinished.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertSame(replacement, slot.snapshot("b"))
            requireNotNull(replacement).start(); requireNotNull(replacement).join(); assertNull(slot.snapshot())
        } finally { scope.cancel() }
    }

    @Test fun actualCoordinatorUsesSafePrimitiveForAllThreeRegistrationsAndSchedulesFreshPollRestart() {
        val path = listOf("src/main", "app/src/main", "android-native/app/src/main")
            .map { Paths.get(it, "java/cloud/dcompany/erp/core/remote/RemoteAssistanceCoordinator.kt") }
            .first(Files::isRegularFile)
        val source = Files.newBufferedReader(path).use { it.readText() }
        for (name in listOf("poll", "capture", "command")) {
            assertTrue(source.contains("private val ${name}Jobs = RemoteJobSlot<OriginalOperationBinding>()"))
            assertTrue(source.contains("${name}Jobs.reserve(scope,"))
            assertTrue(source.contains("${name}Jobs.cancel()"))
            assertFalse(source.contains("${name}Job?.isActive"))
            assertFalse(source.contains("${name}Job = null"))
        }
        assertTrue(source.contains("pollRestartRequested = job == null"))
        assertTrue(source.contains("restartRemotePollAfterCompletion(it, scope, { pollRestartRequested && verifiedScope && appForeground && online.value }, ::ensurePolling)"))
        assertTrue(source.contains("val heartbeatOrigin = originalOperation?.takeIf { it.isCurrent() } ?: return"))
        assertTrue(source.contains("originalCommit.withOriginal<Job?>(heartbeatOrigin)"))
    }

}

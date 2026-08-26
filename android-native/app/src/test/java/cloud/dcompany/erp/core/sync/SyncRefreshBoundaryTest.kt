package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncRefreshBoundaryTest {

    @Test
    fun `unexpected local refresh failure is published and does not escape collector`() = runBlocking {
        val original = IllegalStateException("database implementation detail")
        val logged = mutableListOf<Pair<String, Throwable>>()

        val result = runResourceRefresh(
            resource = "kitchen",
            pull = { throw original },
            logFailure = { resource, failure -> logged += resource to failure },
        )

        val failed = result as ResourceRefreshResult.Failed
        assertEquals("kitchen", failed.resource)
        assertTrue(failed.userMessage.contains("kitchen data"))
        assertTrue(failed.userMessage.contains("Saved data is still available"))
        assertTrue(failed.userMessage.contains("Try again"))
        assertFalse(failed.userMessage.contains("database implementation detail"))
        assertEquals("kitchen", logged.single().first)
        assertSame(original, logged.single().second)
    }

    @Test
    fun `api refresh failure keeps server reason and identifies resource`() = runBlocking {
        val logged = mutableListOf<Throwable>()

        val result = runResourceRefresh(
            resource = "tables",
            pull = { throw ApiException("connection unavailable.") },
            logFailure = { _, failure -> logged += failure },
        )

        assertEquals(
            "Could not refresh tables data: connection unavailable. " +
                "Saved data is still available. Try again; if it continues, ask a manager for help.",
            (result as ResourceRefreshResult.Failed).userMessage,
        )
        assertTrue(logged.single() is ApiException)
    }

    @Test
    fun `cancellation escapes without publishing a user failure`() = runBlocking {
        val logged = mutableListOf<Throwable>()

        try {
            runResourceRefresh(
                resource = "orders",
                pull = { throw CancellationException("scope stopped") },
                logFailure = { _, failure -> logged += failure },
            )
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // Expected: cancellation belongs to the owning coroutine scope.
        }

        assertTrue(logged.isEmpty())
    }

    @Test
    fun `later refresh still runs after an earlier resource failure`() = runBlocking {
        val refreshed = mutableListOf<String>()
        val failures = mutableListOf<ResourceRefreshResult.Failed>()

        val first = runResourceRefresh(
            resource = "kitchen",
            pull = { throw IllegalStateException("bad payload") },
            logFailure = { _, _ -> },
        )
        if (first is ResourceRefreshResult.Failed) failures += first
        val second = runResourceRefresh(
            resource = "tables",
            pull = { refreshed += "tables" },
            logFailure = { _, _ -> },
        )

        assertEquals(listOf("tables"), refreshed)
        assertEquals(1, failures.size)
        assertTrue(second is ResourceRefreshResult.Refreshed)
    }

    @Test
    fun `feedback remains scoped and a successful retry clears only that resource`() {
        val store = ResourceRefreshFeedbackStore()
        val kitchenMessage = "Could not refresh kitchen data."
        val tablesMessage = "Could not refresh tables data."

        store.record(ResourceRefreshResult.Failed("kitchen", kitchenMessage))
        store.record(ResourceRefreshResult.Failed("tables", tablesMessage))

        assertEquals(kitchenMessage, store.errors.value["kitchen"])
        assertEquals(tablesMessage, store.errors.value["tables"])

        store.record(ResourceRefreshResult.Refreshed("kitchen"))

        assertFalse(store.errors.value.containsKey("kitchen"))
        assertEquals(tablesMessage, store.errors.value["tables"])
    }

    @Test
    fun `same resource pulls cannot let an older response overwrite a newer one`() = runBlocking {
        assertOlderGetCannotOverwriteConfirmedWrite("kitchen")
    }

    @Test
    fun `older orders GET cannot land after newer outbox-confirmed cache`() = runBlocking {
        assertOlderGetCannotOverwriteConfirmedWrite("orders")
    }

    @Test
    fun `older shifts GET cannot land after newer outbox-confirmed cache`() = runBlocking {
        assertOlderGetCannotOverwriteConfirmedWrite("shifts")
    }

    @Test
    fun `cancelled resource waiter cannot mutate or poison later refreshes`() = runBlocking {
        val serialiser = ResourceRefreshSerialiser()
        val ownerStarted = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val waiterRequested = CompletableDeferred<Unit>()
        var cache = "initial"

        val owner = async(Dispatchers.Default) {
            serialiser.run("orders") {
                ownerStarted.complete(Unit)
                releaseOwner.await()
                cache = "owner"
            }
        }
        ownerStarted.await()
        // Unconfined starts immediately and runs until Mutex.withLock
        // suspends, making this a deterministic queued-waiter cancellation.
        val cancelledWaiter = async(Dispatchers.Unconfined) {
            waiterRequested.complete(Unit)
            serialiser.run("orders") {
                cache = "cancelled-waiter"
            }
        }
        waiterRequested.await()
        cancelledWaiter.cancelAndJoin()

        releaseOwner.complete(Unit)
        owner.await()
        serialiser.run("orders") { cache = "later-refresh" }

        assertEquals("later-refresh", cache)
    }

    private suspend fun assertOlderGetCannotOverwriteConfirmedWrite(resource: String) = coroutineScope {
        val serialiser = ResourceRefreshSerialiser()
        val getStarted = CompletableDeferred<Unit>()
        val releaseGetCommit = CompletableDeferred<Unit>()
        val outboxRequested = CompletableDeferred<Unit>()
        val outboxEntered = CompletableDeferred<Unit>()
        var cache = "initial"

        val olderGet = async(Dispatchers.Default) {
            serialiser.run(resource) {
                getStarted.complete(Unit)
                releaseGetCommit.await()
                cache = "older-get"
            }
        }
        getStarted.await()
        val newerOutboxConfirmation = async(Dispatchers.Default) {
            outboxRequested.complete(Unit)
            serialiser.run(resource) {
                outboxEntered.complete(Unit)
                cache = "outbox-confirmed"
            }
        }
        outboxRequested.await()

        val enteredBeforeGetCommitted = withTimeoutOrNull(200) {
            outboxEntered.await()
            true
        } ?: false
        assertFalse(enteredBeforeGetCommitted)

        releaseGetCommit.complete(Unit)
        olderGet.await()
        newerOutboxConfirmation.await()

        assertEquals("outbox-confirmed", cache)
    }
}

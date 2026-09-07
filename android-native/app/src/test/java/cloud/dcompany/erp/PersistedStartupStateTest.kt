package cloud.dcompany.erp

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedStartupStateTest {

    @Test
    fun `all persisted stores must finish before startup is ready`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val result = async {
            runBoundedPersistedStartupRestore(
                timeoutMillis = 1_000L,
                loaders = listOf(
                    {
                        firstStarted.complete(Unit)
                        release.await()
                    },
                    {
                        secondStarted.complete(Unit)
                        release.await()
                    },
                ),
            )
        }

        firstStarted.await()
        secondStarted.await()
        assertTrue("Startup must remain gated while any store is loading", !result.isCompleted)
        release.complete(Unit)
        assertEquals(PersistedStartupStateResult.Ready, result.await())
    }

    @Test
    fun `stalled persisted storage fails closed within its bound`() = runBlocking {
        val result = runBoundedPersistedStartupRestore(
            timeoutMillis = 100L,
            loaders = listOf({ awaitCancellation() }),
        )

        assertEquals(
            PersistedStartupStateResult.Unavailable(PersistedStartupFailure.TIMEOUT),
            result,
        )
    }

    @Test
    fun `storage failure is distinct from an empty signed-out state`() = runBlocking {
        val result = runBoundedPersistedStartupRestore(
            timeoutMillis = 1_000L,
            loaders = listOf({ throw IOException("encrypted store unavailable") }),
        )

        assertEquals(
            PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE),
            result,
        )
    }

    @Test
    fun `completed transient failure is cached until a retry attempt explicitly refreshes it`() =
        runBlocking {
            var loadCount = 0
            val restorer = PersistedStartupStateRestorer(
                scope = this,
                timeoutMillis = 1_000L,
                loaders = listOf({
                    loadCount += 1
                    if (loadCount == 1) throw IOException("temporary encrypted-store failure")
                }),
            )

            val unavailable = PersistedStartupStateResult.Unavailable(
                PersistedStartupFailure.STORAGE,
            )
            assertEquals(unavailable, restorer.await())
            assertEquals(unavailable, restorer.await())
            assertEquals(1, loadCount)

            assertEquals(PersistedStartupStateResult.Ready, restorer.await(retryFailed = true))
            assertEquals(2, loadCount)
        }

    @Test
    fun `work first attempt shares startup restore and later attempts retry a failure`() {
        assertEquals(false, retryFailedPersistedStartupForWorkAttempt(0))
        assertEquals(true, retryFailedPersistedStartupForWorkAttempt(1))
        assertEquals(true, retryFailedPersistedStartupForWorkAttempt(Int.MAX_VALUE))
    }
}

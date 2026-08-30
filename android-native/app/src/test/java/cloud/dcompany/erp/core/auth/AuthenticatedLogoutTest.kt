package cloud.dcompany.erp.core.auth

import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedLogoutTest {

    @Test
    fun `successful server revocation happens before local credential clear`() = runBlocking {
        val events = mutableListOf<String>()

        val result = bestEffortAuthenticatedLogout(
            timeoutMillis = 1_000L,
            revokeRemoteSession = { events += "remote" },
            clearLocalSession = { events += "local" },
        )

        assertEquals(RemoteLogoutResult.REVOKED, result)
        assertEquals(listOf("remote", "local"), events)
    }

    @Test
    fun `network failure cannot block local sign out`() = runBlocking {
        var localClears = 0

        val result = bestEffortAuthenticatedLogout(
            timeoutMillis = 1_000L,
            revokeRemoteSession = { throw IOException("offline") },
            clearLocalSession = { localClears += 1 },
        )

        assertEquals(RemoteLogoutResult.FAILED, result)
        assertEquals(1, localClears)
    }

    @Test
    fun `hung server call is bounded and still clears local session`() = runBlocking {
        val cleared = AtomicBoolean(false)

        val result = bestEffortAuthenticatedLogout(
            timeoutMillis = 25L,
            revokeRemoteSession = { awaitCancellation() },
            clearLocalSession = { cleared.set(true) },
        )

        assertEquals(RemoteLogoutResult.TIMED_OUT, result)
        assertTrue(cleared.get())
    }

    @Test
    fun `caller cancellation cannot interrupt local credential clear`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val remoteStarted = CompletableDeferred<Unit>()

        val signOut = launch {
            bestEffortAuthenticatedLogout(
                timeoutMillis = 50L,
                revokeRemoteSession = {
                    events += "remote-started"
                    remoteStarted.complete(Unit)
                    awaitCancellation()
                },
                clearLocalSession = { events += "local-cleared" },
            )
        }

        remoteStarted.await()
        signOut.cancelAndJoin()

        assertEquals(listOf("remote-started", "local-cleared"), events)
    }

    @Test
    fun `zero timeout skips remote revocation but still clears local session`() = runBlocking {
        val events = mutableListOf<String>()

        val result = bestEffortAuthenticatedLogout(
            timeoutMillis = 0L,
            revokeRemoteSession = { events += "remote" },
            clearLocalSession = { events += "local" },
        )

        assertEquals(RemoteLogoutResult.FAILED, result)
        assertEquals(listOf("local"), events)
    }

    @Test
    fun `negative timeout skips remote revocation but still clears local session`() = runBlocking {
        val events = mutableListOf<String>()

        val result = bestEffortAuthenticatedLogout(
            timeoutMillis = -1L,
            revokeRemoteSession = { events += "remote" },
            clearLocalSession = { events += "local" },
        )

        assertEquals(RemoteLogoutResult.FAILED, result)
        assertEquals(listOf("local"), events)
    }
}

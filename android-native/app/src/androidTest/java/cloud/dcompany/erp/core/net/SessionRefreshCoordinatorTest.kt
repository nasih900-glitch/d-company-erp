package cloud.dcompany.erp.core.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.auth.TokenStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRefreshCoordinatorTest {

    @Test
    fun logoutWhileRefreshIsInFlightCannotResurrectCredentials() = runBlocking {
        val store = cleanStore()
        store.save("old-access", "old-refresh")
        val lease = checkNotNull(store.refreshLease())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                tokens("rotated-access", "rotated-refresh")
            },
            onForcedLogout = {},
        )

        val result = async(Dispatchers.IO) { coordinator.refresh(lease) }
        await(entered)
        withContext(Dispatchers.IO) { store.clear() }
        release.countDown()

        assertNull(withTimeout(5_000) { result.await() })
        assertFalse(store.hasSession())
        val restarted = TokenStore(context())
        restarted.load()
        assertFalse(restarted.hasSession())

        // A deliberate login is not conditional on the old refresh lease.
        restarted.save("explicit-login-access", "explicit-login-refresh")
        assertEquals("explicit-login-access", restarted.accessToken())
        restarted.clear()
    }

    @Test
    fun concurrent401sShareExactlyOneRefreshAndBothUseItsResult() = runBlocking {
        val store = cleanStore()
        store.save("old-access", "old-refresh")
        val lease = checkNotNull(store.refreshLease())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val followerJoined = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                tokens("new-access", "new-refresh")
            },
            onForcedLogout = {},
            observer = SessionRefreshObserver { followerJoined.countDown() },
        )

        val leader = async(Dispatchers.IO) { coordinator.refresh(lease) }
        await(entered)
        val follower = async(Dispatchers.IO) { coordinator.refresh(lease) }
        await(followerJoined)
        release.countDown()

        assertEquals("new-access", withTimeout(5_000) { leader.await() })
        assertEquals("new-access", withTimeout(5_000) { follower.await() })
        assertEquals(1, calls.get())
        assertEquals("new-access", store.accessToken())
        assertEquals("new-refresh", store.refreshToken())
        store.clear()
    }

    @Test
    fun staleRefresh401CannotClearANewerExplicitLogin() = runBlocking {
        val store = cleanStore()
        store.save("old-access", "old-refresh")
        val oldLease = checkNotNull(store.refreshLease())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val forcedLogouts = AtomicInteger(0)
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw ApiException("old refresh rejected", status = 401)
            },
            onForcedLogout = { forcedLogouts.incrementAndGet() },
        )

        val staleResult = async(Dispatchers.IO) { coordinator.refresh(oldLease) }
        await(entered)
        // Represents a deliberate sign-in that finished while the old network
        // call was still in flight. It must remain authoritative.
        store.save("new-login-access", "new-login-refresh")
        release.countDown()

        assertNull(withTimeout(5_000) { staleResult.await() })
        assertEquals("new-login-access", store.accessToken())
        assertEquals("new-login-refresh", store.refreshToken())
        assertEquals(0, forcedLogouts.get())
        store.clear()
    }

    @Test
    fun definitiveRefreshRejectionClearsOnlyTheCurrentSession() {
        val store = cleanStore()
        store.save("expired-access", "rejected-refresh")
        val lease = checkNotNull(store.refreshLease())
        val forcedLogouts = AtomicInteger(0)
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = { throw ApiException("refresh rejected", status = 403) },
            onForcedLogout = { forcedLogouts.incrementAndGet() },
        )

        assertNull(coordinator.refresh(lease))
        assertFalse(store.hasSession())
        assertEquals(1, forcedLogouts.get())
        store.clear()
    }

    @Test
    fun serverRefreshFailurePropagatesItsStatusAndPreservesCredentials() = runBlocking {
        val store = cleanStore()
        store.save("offline-access", "still-valid-refresh")
        val lease = checkNotNull(store.refreshLease())
        val expected = ApiException(
            message = "refresh service unavailable",
            status = 503,
            code = "service_unavailable",
        )
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = { throw expected },
            onForcedLogout = { error("A transient failure must not force logout") },
        )

        val propagated = checkNotNull(
            runCatching { coordinator.refresh(lease) }.exceptionOrNull(),
        ) as ApiException

        assertTrue(propagated === expected)
        assertEquals(503, propagated.status)
        assertEquals("service_unavailable", propagated.code)
        assertTrue(propagated.isAmbiguous)
        assertEquals("offline-access", store.accessToken())
        assertEquals("still-valid-refresh", store.refreshToken())

        val restarted = TokenStore(context())
        restarted.load()
        assertEquals("offline-access", restarted.accessToken())
        assertEquals("still-valid-refresh", restarted.refreshToken())
        restarted.clear()
    }

    @Test
    fun networkRefreshFailureRemainsAmbiguousAndPreservesCredentials() = runBlocking {
        val store = cleanStore()
        store.save("cached-access", "cached-refresh")
        val lease = checkNotNull(store.refreshLease())
        val expected = ApiException(
            message = "cafe wifi unavailable",
            status = null,
            code = "network_error",
        )
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = { throw expected },
            onForcedLogout = { error("A network failure must not force logout") },
        )

        val propagated = checkNotNull(
            runCatching { coordinator.refresh(lease) }.exceptionOrNull(),
        ) as ApiException

        assertTrue(propagated === expected)
        assertNull(propagated.status)
        assertEquals("network_error", propagated.code)
        assertTrue(propagated.isAmbiguous)
        assertEquals("cached-access", store.accessToken())
        assertEquals("cached-refresh", store.refreshToken())

        val restarted = TokenStore(context())
        restarted.load()
        assertEquals("cached-access", restarted.accessToken())
        assertEquals("cached-refresh", restarted.refreshToken())
        restarted.clear()
    }

    private fun cleanStore(): TokenStore = TokenStore(context()).also { it.clear() }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun await(latch: CountDownLatch) {
        assertTrue(withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) })
    }

    private fun tokens(access: String, refresh: String) = TokenPair(
        accessToken = access,
        refreshToken = refresh,
        expiresIn = 900,
    )
}

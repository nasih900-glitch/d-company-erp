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
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedAuthInterceptorRefreshTest {

    @Test
    fun ordinaryAndRemote401sShareRefreshAndDelayed401UsesRotatedToken() = runBlocking {
        val store = cleanStore()
        store.save("old-access", "old-refresh")
        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val followerJoined = CountDownLatch(1)
        val refreshCalls = AtomicInteger(0)
        val forcedLogouts = AtomicInteger(0)
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                refreshCalls.incrementAndGet()
                refreshEntered.countDown()
                check(releaseRefresh.await(5, TimeUnit.SECONDS))
                tokens("rotated-access", "rotated-refresh")
            },
            onForcedLogout = { forcedLogouts.incrementAndGet() },
            observer = SessionRefreshObserver { followerJoined.countDown() },
        )
        val ordinary = ApiClient.AuthInterceptor(store, coordinator)
        val remote = ApiClient.AuthInterceptor(store, coordinator)

        val delayed401Entered = CountDownLatch(1)
        val releaseDelayed401 = CountDownLatch(1)
        val delayedChain = UnauthorizedThenSuccessChain(request("auth/me")) {
            delayed401Entered.countDown()
            check(releaseDelayed401.await(5, TimeUnit.SECONDS))
        }
        val ordinaryChain = UnauthorizedThenSuccessChain(request("shifts"))
        val remoteChain = UnauthorizedThenSuccessChain(
            request("remote-assistance/device/status"),
        )

        val delayed = async(Dispatchers.IO) {
            ordinary.intercept(delayedChain).use { response -> response.code }
        }
        await(delayed401Entered)

        val leader = async(Dispatchers.IO) {
            ordinary.intercept(ordinaryChain).use { response -> response.code }
        }
        await(refreshEntered)
        val follower = async(Dispatchers.IO) {
            remote.intercept(remoteChain).use { response -> response.code }
        }
        await(followerJoined)
        releaseRefresh.countDown()

        assertEquals(200, withTimeout(5_000) { leader.await() })
        assertEquals(200, withTimeout(5_000) { follower.await() })
        releaseDelayed401.countDown()
        assertEquals(200, withTimeout(5_000) { delayed.await() })

        assertEquals(1, refreshCalls.get())
        assertEquals(0, forcedLogouts.get())
        assertEquals("rotated-access", store.accessToken())
        assertEquals("rotated-refresh", store.refreshToken())
        for (chain in listOf(ordinaryChain, remoteChain, delayedChain)) {
            assertEquals(
                listOf("Bearer old-access", "Bearer rotated-access"),
                chain.authorizations,
            )
        }
        store.clear()
    }

    @Test
    fun inFlightOldAccount401CannotBorrowNewAccountAuthority() = runBlocking {
        val store = cleanStore()
        store.save("old-account-access", "old-account-refresh")
        val refreshCalls = AtomicInteger(0)
        val forcedLogouts = AtomicInteger(0)
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                refreshCalls.incrementAndGet()
                tokens("unexpected-access", "unexpected-refresh")
            },
            onForcedLogout = { forcedLogouts.incrementAndGet() },
        )
        val interceptor = ApiClient.AuthInterceptor(store, coordinator)
        val oldRequestEntered = CountDownLatch(1)
        val releaseOld401 = CountDownLatch(1)
        val chain = UnauthorizedThenSuccessChain(request("auth/me")) {
            oldRequestEntered.countDown()
            check(releaseOld401.await(5, TimeUnit.SECONDS))
        }

        val result = async(Dispatchers.IO) {
            interceptor.intercept(chain).use { response -> response.code }
        }
        await(oldRequestEntered)
        withContext(Dispatchers.IO) {
            store.save("new-account-access", "new-account-refresh")
        }
        releaseOld401.countDown()

        assertEquals(401, withTimeout(5_000) { result.await() })
        assertEquals(listOf("Bearer old-account-access"), chain.authorizations)
        assertFalse(chain.authorizations.contains("Bearer new-account-access"))
        assertEquals(0, refreshCalls.get())
        assertEquals(0, forcedLogouts.get())
        assertEquals("new-account-access", store.accessToken())
        assertEquals("new-account-refresh", store.refreshToken())
        store.clear()
    }

    private fun cleanStore(): TokenStore = TokenStore(context()).also { it.clear() }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun await(latch: CountDownLatch) {
        assertTrue(withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) })
    }

    private fun request(path: String): Request = Request.Builder()
        .url("https://example.test/api/v1/$path")
        .build()

    private fun tokens(access: String, refresh: String) = TokenPair(
        accessToken = access,
        refreshToken = refresh,
        expiresIn = 900,
    )

    private class UnauthorizedThenSuccessChain(
        private val initialRequest: Request,
        private val beforeUnauthorized: () -> Unit = {},
    ) : Interceptor.Chain {
        private val attemptsLock = Any()
        private var attempts = 0
        private val recordedAuthorizations = mutableListOf<String?>()

        val authorizations: List<String?>
            get() = synchronized(attemptsLock) { recordedAuthorizations.toList() }

        override fun request(): Request = initialRequest

        override fun proceed(request: Request): Response {
            val attempt = synchronized(attemptsLock) {
                attempts += 1
                check(attempts <= 2) { "Auth interceptor replayed more than once" }
                recordedAuthorizations += request.header("Authorization")
                attempts
            }
            if (attempt == 1) beforeUnauthorized()
            val code = if (attempt == 1) 401 else 200
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Unauthorized")
                .body("".toResponseBody())
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = error("The synthetic chain never exposes a real call")
        override fun connectTimeoutMillis(): Int = 5_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 5_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 5_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}

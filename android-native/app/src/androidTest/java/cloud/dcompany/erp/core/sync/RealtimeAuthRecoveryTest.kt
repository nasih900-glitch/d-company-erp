package cloud.dcompany.erp.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.SessionRefreshCoordinator
import cloud.dcompany.erp.core.net.SessionRefreshObserver
import cloud.dcompany.erp.core.net.TokenPair
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real callback wiring and encrypted TokenStore; only transport and retry time are controlled. */
@RunWith(AndroidJUnit4::class)
class RealtimeAuthRecoveryTest {
    private lateinit var store: TokenStore

    @Before
    fun prepare() {
        store = TokenStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clear()
        store.save("old-access", "old-refresh")
    }

    @After
    fun cleanUp() {
        store.clear()
    }

    @Test
    fun idle4401RefreshesOnceAndPublishesGapOnlyAfterReplacementAcknowledgement() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = coordinator {
            calls.incrementAndGet()
            pair()
        }
        Harness(store, coordinator).use { harness ->
            val first = harness.start()
            first.connected()
            first.closing(4401)
            first.closed(4401)
            first.failed()
            assertEquals(listOf(4401), first.closeCodes)
            harness.releaseRetry(1_000)
            val replacement = harness.nextOpened()
            assertEquals("new-access", replacement.presentedToken())
            assertEquals(1, calls.get())
            assertEquals("new-refresh", store.refreshToken())
            assertTrue(harness.events.tryReceive().isFailure)
            replacement.connected()
            assertEquals(RealtimeEvent.ReconnectedAfterGap, harness.nextEvent())
            replacement.connected()
            first.closing(4401)
            first.changed("orders")
            replacement.changed("gaming")
            assertEquals(RealtimeEvent.Changed("gaming"), harness.nextEvent())
            assertEquals(1, calls.get())
            assertTrue(harness.retries.tryReceive().isFailure)
            assertTrue(harness.events.tryReceive().isFailure)
        }
    }

    @Test
    fun rejectedInitialAuthenticationAlsoRefreshesWithoutEarlyGap() = runBlocking {
        val calls = AtomicInteger()
        Harness(store, coordinator { calls.incrementAndGet(); pair() }).use { harness ->
            val first = harness.start()
            first.closing(4401)
            harness.releaseRetry(1_000)
            val replacement = harness.nextOpened()
            assertEquals("new-access", replacement.presentedToken())
            assertTrue(harness.events.tryReceive().isFailure)
            replacement.connected()
            assertEquals(RealtimeEvent.ReconnectedAfterGap, harness.nextEvent())
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun websocketAndHttp401ShareTheSameRefreshFlight() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val follower = CountDownLatch(1)
        val calls = AtomicInteger()
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                pair()
            },
            onForcedLogout = { error("Valid refresh must not log out") },
            observer = SessionRefreshObserver { follower.countDown() },
        )
        Harness(store, coordinator).use { harness ->
            try {
                val first = harness.start()
                first.connected()
                first.closing(4401)
                harness.releaseRetry(1_000)
                await(entered)
                val chain = UnauthorizedChain()
                val http = async(Dispatchers.IO) {
                    ApiClient.AuthInterceptor(store, coordinator).intercept(chain).use { it.code }
                }
                await(follower)
                release.countDown()
                assertEquals(200, withTimeout(5_000) { http.await() })
                val replacement = harness.nextOpened()
                assertEquals("new-access", replacement.presentedToken())
                assertEquals(listOf("Bearer old-access", "Bearer new-access"), chain.authorizations)
                assertEquals(1, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun delayed4401ReusesAnAlreadyCompletedHttpRotation() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = coordinator { calls.incrementAndGet(); pair() }
        Harness(store, coordinator).use { harness ->
            val first = harness.start()
            first.connected()
            withContext(Dispatchers.IO) {
                ApiClient.AuthInterceptor(store, coordinator).intercept(UnauthorizedChain()).close()
            }
            first.closing(4401)
            harness.releaseRetry(1_000)
            assertEquals("new-access", harness.nextOpened().presentedToken())
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun logoutDuringRefreshCannotInstallTokensOrReopenTheSocket() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            pair()
        }
        Harness(store, coordinator).use { harness ->
            try {
                harness.start().closing(4401)
                harness.releaseRetry(1_000)
                await(entered)
                store.clear()
                harness.client.disconnect()
                release.countDown()
                harness.awaitRefreshCompletion()
                assertFalse(store.hasSession())
                harness.assertNoRecovery()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun disconnectDuringSuccessfulRefreshKeepsTokensButCannotReopenSocket() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(store, coordinator {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            pair()
        }, nonSuspendingRefresh = true).use { harness ->
            try {
                harness.start().closing(4401)
                harness.releaseRetry(1_000)
                await(entered)
                harness.client.disconnect()
                release.countDown()
                harness.awaitRefreshCompletion()
                assertEquals("new-access", store.accessToken())
                harness.assertNoRecovery()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun replacementDuringRefreshSurvivesLateOldSuccessAndCallbacks() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(store, coordinator {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            pair()
        }, nonSuspendingRefresh = true).use { harness ->
            try {
                val first = harness.start()
                first.closing(4401)
                harness.releaseRetry(1_000)
                await(entered)
                harness.client.disconnect()
                store.save("new-login-access", "new-login-refresh")
                harness.client.connect()
                val replacement = harness.nextOpened()
                replacement.connected()
                release.countDown()
                harness.awaitRefreshCompletion()
                first.closed(4401)
                first.failed()
                assertEquals("new-login-access", store.accessToken())
                assertEquals("new-login-refresh", store.refreshToken())
                harness.assertNoRecovery()
                replacement.changed("orders")
                assertEquals(RealtimeEvent.Changed("orders"), harness.nextEvent())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun explicitReloginWithIdenticalBearerCannotBeClearedOrReconnectedByOldRefresh() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val forcedLogouts = AtomicInteger()
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw ApiException("Old refresh rejected", status = 401)
            },
            onForcedLogout = { forcedLogouts.incrementAndGet() },
        )
        Harness(store, coordinator).use { harness ->
            try {
                val first = harness.start()
                first.closing(4401)
                harness.releaseRetry(1_000)
                await(entered)
                // Equality of bearer text must never substitute for login lineage.
                store.save("old-access", "explicit-new-login-refresh")
                release.countDown()
                harness.awaitRefreshCompletion()
                harness.assertNoRecovery()
                assertEquals("explicit-new-login-refresh", store.refreshToken())
                assertEquals(0, forcedLogouts.get())
                harness.client.connect()
                val replacement = harness.nextOpened()
                first.closing(4401)
                first.failed()
                replacement.connected()
                replacement.changed("orders")
                // An explicit connect after the gap still acknowledges recovery first.
                assertEquals(RealtimeEvent.ReconnectedAfterGap, harness.nextEvent())
                assertEquals(RealtimeEvent.Changed("orders"), harness.nextEvent())
                assertTrue(harness.retries.tryReceive().isFailure)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun newLoginWhileSocketOpensCannotAuthenticateTheOldConnection() = runBlocking {
        Harness(store, coordinator { error("Stale socket must not refresh") }).use { harness ->
            harness.client.connect()
            val first = harness.nextSocket()
            store.save("old-access", "different-login-refresh")
            first.open()
            // Even identical token text cannot cross the connection's login lease.
            assertTrue(first.messages.isEmpty())
            harness.assertNoRecovery()
        }
    }

    @Test
    fun transientRefreshFailuresPreserveSessionAndUseCappedBackoff() = runBlocking {
        val calls = AtomicInteger()
        Harness(store, coordinator {
            val attempt = calls.incrementAndGet()
            when {
                attempt == 2 -> throw ApiException("Temporarily unavailable", status = 503)
                attempt <= 7 -> throw IOException("Transport unavailable")
                else -> pair()
            }
        }).use { harness ->
            harness.start().closing(4401)
            for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
                harness.releaseRetry(delay)
                harness.awaitRefreshCompletion()
                assertEquals("old-access", store.accessToken())
                assertEquals("old-refresh", store.refreshToken())
                assertTrue(harness.sockets.tryReceive().isFailure)
                assertTrue(harness.events.tryReceive().isFailure)
            }
            harness.releaseRetry(30_000)
            val replacement = harness.nextOpened()
            assertEquals("new-access", replacement.presentedToken())
            assertEquals(8, calls.get())
            replacement.connected()
            assertEquals(RealtimeEvent.ReconnectedAfterGap, harness.nextEvent())
        }
    }

    @Test
    fun definitiveRefresh401StopsRecoveryAndClearsOnlyCurrentLogin() = runBlocking {
        assertDefinitiveRejection(401)
    }

    @Test
    fun definitiveRefresh403StopsRecoveryAndClearsOnlyCurrentLogin() = runBlocking {
        assertDefinitiveRejection(403)
    }

    @Test
    fun ordinaryCloseReconnectsWithoutRefreshAndOld4401CannotAffectReplacement() = runBlocking {
        val calls = AtomicInteger()
        Harness(store, coordinator { calls.incrementAndGet(); pair() }).use { harness ->
            val first = harness.start()
            first.connected()
            first.closing(1012)
            harness.releaseRetry(1_000)
            val replacement = harness.nextOpened()
            assertEquals("old-access", replacement.presentedToken())
            first.closing(4401)
            first.failed()
            replacement.connected()
            assertEquals(RealtimeEvent.ReconnectedAfterGap, harness.nextEvent())
            replacement.changed("gaming")
            assertEquals(RealtimeEvent.Changed("gaming"), harness.nextEvent())
            assertEquals(0, calls.get())
            assertTrue(harness.retries.tryReceive().isFailure)
        }
    }

    private suspend fun assertDefinitiveRejection(status: Int) {
        val forcedLogouts = AtomicInteger()
        val coordinator = SessionRefreshCoordinator(
            tokenStore = store,
            refreshCall = { throw ApiException("Rejected", status = status) },
            onForcedLogout = { forcedLogouts.incrementAndGet() },
        )
        Harness(store, coordinator).use { harness ->
            harness.start().closing(4401)
            harness.releaseRetry(1_000)
            harness.awaitRefreshCompletion()
            assertFalse(store.hasSession())
            assertEquals(1, forcedLogouts.get())
            harness.assertNoRecovery()
        }
    }

    private fun coordinator(refresh: (String) -> TokenPair) = SessionRefreshCoordinator(
        tokenStore = store,
        refreshCall = refresh,
        onForcedLogout = { error("Unexpected forced logout") },
    )

    private fun pair() = TokenPair("new-access", "new-refresh", expiresIn = 900)

    private suspend fun await(latch: CountDownLatch) {
        assertTrue(withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) })
    }

    private class Retry(val millis: Long, val release: CompletableDeferred<Unit> = CompletableDeferred())

    private class Harness(
        store: TokenStore,
        coordinator: SessionRefreshCoordinator,
        nonSuspendingRefresh: Boolean = false,
    ) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = Channel<FakeSocket>(Channel.UNLIMITED)
        val retries = Channel<Retry>(Channel.UNLIMITED)
        val events = Channel<RealtimeEvent>(Channel.UNLIMITED)
        private val refreshCompletions = Channel<Unit>(Channel.UNLIMITED)
        val client = RealtimeClient(
            tokens = store,
            scope = scope,
            monotonicMillis = { 0L },
            socketFactory = { request, listener ->
                FakeSocket(request, listener).also { check(sockets.trySend(it).isSuccess) }
            },
            refreshSession = { lease ->
                val refresh = {
                    try {
                        coordinator.refresh(lease)
                    } finally {
                        refreshCompletions.trySend(Unit)
                    }
                }
                // A canceled blocking operation can still return. These cases
                // verify the final ticket guard, independent of suspension cancellation.
                if (nonSuspendingRefresh) refresh() else withContext(Dispatchers.IO) { refresh() }
            },
            reconnectDelay = { millis ->
                val retry = Retry(millis)
                retries.send(retry)
                retry.release.await()
            },
        )

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.changes.collect { events.send(it) }
            }
        }

        suspend fun start(): FakeSocket {
            client.connect()
            return nextOpened()
        }

        suspend fun nextSocket(): FakeSocket = withTimeout(5_000) { sockets.receive() }
        suspend fun nextOpened(): FakeSocket = nextSocket().also { it.open() }
        suspend fun nextEvent(): RealtimeEvent = withTimeout(5_000) { events.receive() }

        suspend fun releaseRetry(expectedMillis: Long) {
            val retry = withTimeout(5_000) { retries.receive() }
            assertEquals(expectedMillis, retry.millis)
            retry.release.complete(Unit)
        }

        suspend fun awaitRefreshCompletion() {
            withTimeout(5_000) { refreshCompletions.receive() }
        }

        suspend fun assertNoRecovery() {
            assertNull(withTimeoutOrNull(250) { sockets.receive() })
            assertTrue(retries.tryReceive().isFailure)
            assertTrue(events.tryReceive().isFailure)
        }

        override fun close() {
            client.disconnect()
            scope.cancel()
        }
    }

    private class FakeSocket(
        private val initialRequest: Request,
        private val listener: WebSocketListener,
    ) : WebSocket {
        val messages = CopyOnWriteArrayList<String>()
        val closeCodes = CopyOnWriteArrayList<Int>()
        override fun request(): Request = initialRequest
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = messages.add(text)
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary frame")
        override fun close(code: Int, reason: String?): Boolean = closeCodes.add(code)
        override fun cancel() = Unit

        fun open() = listener.onOpen(this, Response.Builder()
            .request(initialRequest).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun connected() = listener.onMessage(this, "{\"type\":\"connected\"}")
        fun changed(resource: String) = listener.onMessage(this, "{\"type\":\"changed\",\"resource\":\"$resource\"}")
        fun closing(code: Int) = listener.onClosing(this, code, "server close")
        fun closed(code: Int) = listener.onClosed(this, code, "server close")
        fun failed() = listener.onFailure(this, IOException("closed"), null)
        fun presentedToken(): String = JSONObject(messages.first()).getString("token")
    }

    private class UnauthorizedChain : Interceptor.Chain {
        val authorizations = CopyOnWriteArrayList<String?>()
        private val initialRequest = Request.Builder().url("https://example.test/api/v1/auth/me").build()
        override fun request(): Request = initialRequest
        override fun proceed(request: Request): Response {
            authorizations += request.header("Authorization")
            val code = if (authorizations.size == 1) 401 else 200
            check(authorizations.size <= 2)
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Synthetic response").body("".toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = error("Synthetic chain")
        override fun connectTimeoutMillis(): Int = 5_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 5_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 5_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}

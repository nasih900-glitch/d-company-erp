package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.net.ApiClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StaffTimeWireFenceTest {
    @Test fun obsoleteAuthorityIsRefusedBeforeAnyRequestReachesTransport() {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .addInterceptor(ApiClient.GuardedEphemeralAuthorityInterceptor("synthetic-old-bearer", null) {
                    throw IOException("Synthetic authority expired")
                }).build()
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/api/v1/staff/time-summary")).build()).execute().close() }
            assertEquals(0, server.requestCount)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun capturedAuthorityReplacesCallerSuppliedActorAndTerminalHeaders() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val terminal = "33333333-3333-4333-8333-333333333333"
            var checks = 0
            val client = OkHttpClient.Builder().addInterceptor(
                ApiClient.GuardedEphemeralAuthorityInterceptor("synthetic-captured", terminal) { checks++ }).build()
            val request = Request.Builder().url(server.url("/api/v1/staff/time-summary"))
                .header("Authorization", "Bearer synthetic-new-actor")
                .header("X-Terminal-Id", "synthetic-wrong-terminal").build()
            client.newCall(request).execute().use { assertTrue(it.isSuccessful) }
            val received = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No local request")
            assertEquals("Bearer synthetic-captured", received.getHeader("Authorization"))
            assertEquals(terminal, received.getHeader("X-Terminal-Id")); assertEquals(1, checks)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun accountChangeWhileRequestWaitsAtPrewireFencePreventsDispatch() {
        MockWebServer().use { server ->
            server.start()
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val active = AtomicBoolean(true)
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(
                ApiClient.GuardedEphemeralAuthorityInterceptor("synthetic-originating", null) {
                    entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
                    if (!active.get()) throw IOException("Synthetic session changed")
                }).build()
            var failure: Throwable? = null
            val thread = Thread {
                try { client.newCall(Request.Builder().url(server.url("/api/v1/staff/time-summary")).build()).execute().close() }
                catch (e: Throwable) { failure = e }
            }
            thread.start()
            try { assertTrue(entered.await(2, TimeUnit.SECONDS)); active.set(false) }
            finally { release.countDown(); thread.join(3_000) }
            assertFalse(thread.isAlive); assertTrue(failure is IOException); assertEquals(0, server.requestCount)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun actualSingleAttemptClientNeverReplays503RetryAfterZero() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("{}"))
            val client = ApiClient.ephemeralAuthorityClient("synthetic-captured", singleAttempt = true)
            try {
                runCatching { client.newCall(Request.Builder().url(server.url("/api/v1/staff/my-attendance/clock-in"))
                    .post("{}".toRequestBody()).build()).execute().close() }
                assertEquals("No hidden transport replay is allowed", 1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun actualClientRevalidatesAfterConnectBeforeSendingAnyHeaders() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val active = AtomicBoolean(true)
            val client = ApiClient.ephemeralAuthorityClient("synthetic-captured", beforeDispatch = {
                if (!active.get()) throw IOException("Synthetic session changed during connect")
            }, singleAttempt = true).newBuilder().eventListener(object : EventListener() {
                override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { active.set(false) }
            }).build()
            try {
                assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/api/v1/staff/time-summary")).build()).execute().close() }
                assertEquals(0, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun existingDefaultEphemeralClientKeepsItsReviewedFollowUpBehaviour() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("{}"))
            val client = ApiClient.ephemeralAuthorityClient("synthetic-captured")
            try {
                client.newCall(Request.Builder().url(server.url("/api/v1/identity-read")).build()).execute().use { assertEquals(200, it.code) }
                assertEquals(2, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun oneAttemptFenceIsPerCallAndAllowsSeparateExplicitRequests() {
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
            var checks = 0
            val client = ApiClient.ephemeralAuthorityClient("synthetic-captured", beforeDispatch = { checks++ }, singleAttempt = true)
            try {
                repeat(2) { client.newCall(Request.Builder().url(server.url("/api/v1/staff/time-summary")).build()).execute().use { assertEquals(200, it.code) } }
                assertEquals(2, server.requestCount); assertEquals(4, checks)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

}

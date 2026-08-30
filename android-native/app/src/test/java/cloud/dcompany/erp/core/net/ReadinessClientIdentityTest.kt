package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessClientIdentityTest {
    @Test
    fun `readiness client attaches packaged native identity without operational interceptors`() {
        val client = buildReadinessClient()

        assertEquals(1, client.interceptors.size)
        assertTrue(client.interceptors.single() is ClientIdentityInterceptor)

        val chain = RecordingChain(
            Request.Builder().url("https://example.test/readyz").get().build(),
        )
        client.interceptors.single().intercept(chain).close()

        val request = chain.proceededRequest
        assertEquals(ANDROID_CLIENT_PLATFORM, request.header(CLIENT_PLATFORM_HEADER))
        assertEquals(BuildConfig.VERSION_CODE.toString(), request.header(CLIENT_VERSION_CODE_HEADER))
        assertEquals(
            BuildConfig.DISTRIBUTION_CHANNEL,
            request.header(CLIENT_DISTRIBUTION_CHANNEL_HEADER),
        )
        assertEquals(null, request.header("Authorization"))
        assertEquals(null, request.header(TERMINAL_ID_HEADER))
    }

    private class RecordingChain(
        private val initialRequest: Request,
    ) : Interceptor.Chain {
        lateinit var proceededRequest: Request
            private set

        override fun request(): Request = initialRequest

        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = error("The recording chain never exposes a real call")
        override fun connectTimeoutMillis(): Int = 5_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 5_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 5_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}

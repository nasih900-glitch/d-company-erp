package cloud.dcompany.erp.core.net

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PricingAuthorityInterceptorTest {
    @Test
    fun `normal ERP request receives only the current pricing capability`() {
        val chain = RecordingChain(
            request("menu/items").newBuilder()
                .header(PRICING_TOKEN_HEADER, "caller-supplied")
                .build(),
        )

        ApiClient.PricingTokenInterceptor(allowPricingAuthority = true) { "pricing-current" }
            .intercept(chain)
            .close()

        assertEquals("pricing-current", chain.proceededRequest.header(PRICING_TOKEN_HEADER))
    }

    @Test
    fun `remote request strips pricing capability even if caller or provider has one`() {
        val chain = RecordingChain(
            request("remote-assistance/device/heartbeat").newBuilder()
                .header(PRICING_TOKEN_HEADER, "caller-supplied")
                .build(),
        )

        ApiClient.PricingTokenInterceptor(allowPricingAuthority = false) { "pricing-current" }
            .intercept(chain)
            .close()

        assertNull(chain.proceededRequest.header(PRICING_TOKEN_HEADER))
    }

    private fun request(path: String): Request = Request.Builder()
        .url("https://example.test/api/v1/$path")
        .build()

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

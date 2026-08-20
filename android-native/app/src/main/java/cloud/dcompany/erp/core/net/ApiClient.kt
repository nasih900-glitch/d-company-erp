package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.TerminalStore
import cloud.dcompany.erp.core.auth.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A failure the caller can act on. `retryable` is the important bit: the UI
 * must never tell a cashier "payment failed" when the truth is "we never heard
 * back", because those demand opposite actions at the till.
 */
class ApiException(
    message: String,
    val status: Int? = null,
    val code: String? = null,
) : IOException(message) {

    /** No answer from the server: the request may or may not have committed. */
    val isAmbiguous: Boolean
        get() = status == null || status >= 500 || code == "idempotency_in_progress"

    /** The server decided, and said no. Nothing was written. */
    val isBusinessRule: Boolean get() = code == "business_rule"
}

object ApiClient {

    // Not private: report-snapshot caching (core/db/ReportSnapshots.kt) reuses
    // this exact instance to encode/decode cached bodies, so a cached row
    // round-trips through the same lenient rules (ignoreUnknownKeys,
    // coerceInputValues) as a live response instead of a second, potentially
    // diverging Json config.
    val json = Json {
        ignoreUnknownKeys = true   // the backend may add fields; never crash on them
        explicitNulls = false
        coerceInputValues = true
    }

    lateinit var api: ErpApi
        private set

    /**
     * Shared Retrofit. Each feature declares its own endpoint interface and
     * builds it with [create], instead of everything piling into one giant
     * ErpApi — that keeps unrelated features from colliding in the same file.
     */
    private lateinit var retrofit: Retrofit

    /** `val api = ApiClient.create<GamingApi>()` */
    inline fun <reified T> create(): T = createApi(T::class.java)

    fun <T> createApi(service: Class<T>): T = retrofit.create(service)

    private lateinit var tokens: TokenStore
    private lateinit var terminals: TerminalStore

    /** Set by the app when the server definitively rejects the session. */
    var onForcedLogout: (() -> Unit)? = null

    fun init(tokenStore: TokenStore, terminalStore: TerminalStore) {
        tokens = tokenStore
        terminals = terminalStore

        val client = OkHttpClient.Builder()
            // Cafe wifi is congested, not dead. These are deliberately generous:
            // a slow reply that eventually succeeds beats a fast failure that
            // sends a cashier back to the login screen with a customer waiting.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(TerminalInterceptor())
            .addInterceptor(AuthInterceptor())
            .addInterceptor(ErrorInterceptor(json))
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(ErpApi::class.java)
    }

    /**
     * Every POS write is refused by the backend without this header, and the
     * refusal is a business_rule 422 rather than an auth error — which is why
     * the first build's offline queue filled up with sales that could never be
     * delivered. Attached globally so no future endpoint can forget it.
     */
    private class TerminalInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val id = terminals.terminalId() ?: return chain.proceed(chain.request())
            return chain.proceed(
                chain.request().newBuilder().header("X-Terminal-Id", id).build(),
            )
        }
    }

    /**
     * Attaches the bearer token and, on a 401, refreshes once and replays the
     * original request.
     */
    private class AuthInterceptor : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val access = tokens.accessToken()
            var response = chain.proceed(signed(original, access))

            val isAuthRoute = original.url.encodedPath.let {
                it.endsWith("/auth/login") || it.endsWith("/auth/refresh")
            }
            if (response.code != 401 || isAuthRoute || tokens.refreshToken() == null) {
                return response
            }

            response.close()
            val newAccess = refreshSession() ?: return chain.proceed(signed(original, access))
            return chain.proceed(signed(original, newAccess))
        }

        private fun signed(request: Request, token: String?): Request =
            if (token == null) request
            else request.newBuilder().header("Authorization", "Bearer $token").build()

        /**
         * Returns the new access token, or null if the refresh did not
         * definitively succeed. Only a 401/403 clears the stored session —
         * a timeout or a 5xx leaves the refresh token in place so the next
         * attempt, once the link is back, restores the session silently.
         */
        private fun refreshSession(): String? {
            val refresh = tokens.refreshToken() ?: return null
            return try {
                val pair = kotlinx.coroutines.runBlocking { api.refresh(RefreshRequest(refresh)) }
                tokens.save(pair.accessToken, pair.refreshToken)
                pair.accessToken
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 403) {
                    tokens.clear()
                    onForcedLogout?.invoke()
                }
                null
            } catch (e: IOException) {
                // Never a reason to destroy a valid session.
                null
            }
        }
    }

    /**
     * Turns the backend's `{"error":{"code","message"}}` envelope into an
     * ApiException carrying the real message, so the UI can show "insufficient
     * stock" rather than "HTTP 422".
     */
    private class ErrorInterceptor(private val json: Json) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                throw ApiException(
                    "Could not reach the server. Check the connection and try again.",
                    status = null,
                    code = "network_error",
                )
            }
            if (response.isSuccessful) return response

            val body = response.body?.string().orEmpty()
            response.close()
            val envelope = runCatching {
                json.decodeFromString(ApiErrorEnvelope.serializer(), body)
            }.getOrNull()

            throw ApiException(
                message = envelope?.error?.message ?: "Request failed (HTTP ${response.code}).",
                status = response.code,
                code = envelope?.error?.code,
            )
        }
    }
}

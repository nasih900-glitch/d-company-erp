package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.PricingLock
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

internal const val TERMINAL_ID_HEADER = "X-Terminal-Id"

/**
 * Process-local terminal authority. Persisted TerminalStore is only a
 * candidate for the next login; it becomes request authority only after the
 * matching cache scope and server terminal list have both been validated.
 */
internal class ActiveTerminalHeaderContext {
    @Volatile private var activeTerminalId: String? = null

    fun activate(terminalId: String?) {
        activeTerminalId = terminalId?.trim()?.takeIf(String::isNotEmpty)
    }

    fun deactivate() {
        activeTerminalId = null
    }

    fun apply(request: Request): Request = request.withResolvedTerminal(activeTerminalId)
}

/**
 * Authentication, compatibility and terminal discovery establish which
 * account/branch/till is valid. Sending yesterday's till to those bootstrap
 * routes lets the tenant guard reject the very request needed to recover from
 * a deleted till or an account/branch switch.
 *
 * Everything else remains terminal-bound. In particular, POS writes still
 * carry the header and the backend remains the final enforcement point.
 */
internal fun Request.withResolvedTerminal(terminalId: String?): Request {
    val segments = url.pathSegments.filter(String::isNotBlank)
    fun containsRoute(prefix: List<String>): Boolean =
        segments.windowed(prefix.size).any { it == prefix }

    val bootstrapRoute =
        containsRoute(listOf("auth")) ||
            containsRoute(listOf("public")) ||
            containsRoute(listOf("settings", "terminals"))
    val builder = newBuilder()
    val verified = terminalId?.trim()?.takeIf(String::isNotEmpty)
    return if (bootstrapRoute || verified == null) {
        // Also strip a caller-supplied value: bootstrap identity must never be
        // influenced by stale terminal state from any request construction
        // path, and an inactive runtime scope authorises no terminal at all.
        builder.removeHeader(TERMINAL_ID_HEADER).build()
    } else {
        builder.header(TERMINAL_ID_HEADER, verified).build()
    }
}

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

    /**
     * A queued write must remain replayable across both uncertain transport
     * failures and a definitive old-client rejection. HTTP 426 proves nothing
     * committed, but permanently rejecting the row would destroy the updated
     * app's chance to send valid saved work.
     */
    val mustPreserveOutbox: Boolean get() = isAmbiguous || status == 426
}

object ApiClient {

    internal val backendReachability = BackendReachabilityTracker()

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
    private lateinit var refreshApi: ErpApi

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
    private val activeTerminalHeaders = ActiveTerminalHeaderContext()

    /** Set by the app when the server definitively rejects the session. */
    var onForcedLogout: (() -> Unit)? = null

    /** Set before any Activity/API caller starts; HTTP 426 must block globally. */
    @Volatile
    var onUpdateRequired: ((ClientUpdateNotice) -> Unit)? = null

    fun init(tokenStore: TokenStore, @Suppress("UNUSED_PARAMETER") terminalStore: TerminalStore) {
        backendReachability.reset()
        tokens = tokenStore
        // A value in TerminalStore is a login-resolution candidate, never
        // proof that this process currently owns that terminal.
        activeTerminalHeaders.deactivate()

        // Refresh must use a separate dispatcher. If five normal calls all
        // receive 401 together, their interceptor threads occupy OkHttp's
        // per-host slots; recursively enqueueing refresh on that same client
        // can deadlock forever. This client has no AuthInterceptor, so refresh
        // can neither recurse nor compete with the blocked original requests.
        val refreshClient = baseClientBuilder()
            .addInterceptor(ClientIdentityInterceptor())
            .addInterceptor(ErrorInterceptor(json))
            .build()
        refreshApi = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(refreshClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ErpApi::class.java)

        val client = baseClientBuilder()
            // Order matters here more than it looks: OkHttp interceptors
            // nest in add-order, so the LAST one added sits closest to the
            // real network call and is the FIRST to see the raw Response on
            // the way back. AuthInterceptor's whole 401-refresh-and-retry
            // logic depends on inspecting that raw response's status code —
            // if ErrorInterceptor were added after it, ErrorInterceptor
            // would throw ApiException on any non-2xx (including 401)
            // before AuthInterceptor's own `chain.proceed()` call ever
            // returns, so its refresh logic would never run at all (an
            // ApiException would propagate straight out instead). Fixed
            // during Phase 7's review after this ordering was traced and
            // confirmed broken with a real reproduction — it predates this
            // phase but lives in a file this phase touches, so it's fixed
            // here rather than left in place.
            .addInterceptor(ClientIdentityInterceptor())
            .addInterceptor(TerminalInterceptor())
            .addInterceptor(PricingTokenInterceptor())
            .addInterceptor(ErrorInterceptor(json))
            .addInterceptor(AuthInterceptor())
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(ErpApi::class.java)
    }

    internal fun activateTerminalScope(terminalId: String?) {
        activeTerminalHeaders.activate(terminalId)
    }

    internal fun deactivateTerminalScope() {
        activeTerminalHeaders.deactivate()
    }

    private fun baseClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        // Cafe wifi is congested, not dead. These are deliberately generous:
        // a slow reply that eventually succeeds beats a fast failure that
        // sends a cashier back to the login screen with a customer waiting.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    /** Attach a till to operational calls, never to identity/bootstrap calls. */
    private class TerminalInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return chain.proceed(activeTerminalHeaders.apply(chain.request()))
        }
    }

    /**
     * Attaches the bearer token and, on a 401, refreshes once and replays the
     * original request.
     */
    private class AuthInterceptor : Interceptor {

        private val refreshCoordinator = SessionRefreshCoordinator(
            tokenStore = tokens,
            refreshCall = { refresh ->
                kotlinx.coroutines.runBlocking { refreshApi.refresh(RefreshRequest(refresh)) }
            },
            onForcedLogout = { onForcedLogout?.invoke() },
        )

        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val lease = tokens.refreshLease()
            val response = chain.proceed(signed(original, lease?.accessToken))

            val isAuthRoute = original.url.encodedPath.let {
                it.endsWith("/auth/login") || it.endsWith("/auth/refresh")
            }
            if (response.code != 401 || isAuthRoute || lease == null) {
                return response
            }

            val newAccess = try {
                refreshCoordinator.refresh(lease)
            } catch (failure: Throwable) {
                response.close()
                throw failure
            } ?: return response
            response.close()
            return chain.proceed(signed(original, newAccess))
        }

        private fun signed(request: Request, token: String?): Request =
            if (token == null) request
            else request.newBuilder().header("Authorization", "Bearer $token").build()

    }

    /**
     * Attaches `X-Pricing-Token` to every request whenever a live unlock
     * exists — mirrors the web app's own axios interceptor (one global
     * attach point rather than every price-write call site remembering to
     * set the header itself). A request for an endpoint that doesn't care
     * about pricing just carries an extra, ignored header.
     */
    private class PricingTokenInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = PricingLock.currentToken(tokens.currentPricingSession())
                ?: return chain.proceed(chain.request())
            return chain.proceed(
                chain.request().newBuilder().header("X-Pricing-Token", token).build(),
            )
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
            } catch (e: ApiException) {
                // Inner interceptors may already have classified a dedicated
                // refresh response. Preserve its status/code so a transient
                // 5xx or network error can never be mistaken for auth loss.
                backendReachability.recordApiFailure(e)
                throw e
            } catch (e: IOException) {
                backendReachability.recordTransportFailure()
                throw ApiException(
                    "Could not reach the server. Check the connection and try again.",
                    status = null,
                    code = "network_error",
                )
            }
            backendReachability.recordHttpResponse()
            if (response.isSuccessful) return response

            val body = response.body?.string().orEmpty()
            response.close()
            val envelope = runCatching {
                json.decodeFromString(ApiErrorEnvelope.serializer(), body)
            }.getOrNull()

            if (
                response.request.header("X-Pricing-Token") != null &&
                response.code in setOf(401, 403) &&
                envelope?.error?.message?.contains("pricing", ignoreCase = true) == true
            ) {
                PricingLock.lock()
            }

            if (response.code == 426) {
                val details = envelope?.error?.details
                onUpdateRequired?.invoke(
                    ClientUpdateNotice(
                        message = envelope?.error?.message
                            ?: "This app version must be updated before continuing.",
                        updateUrl = details?.updateUrl,
                        currentVersionCode = details?.currentVersionCode ?: BuildConfig.VERSION_CODE,
                        minimumSupportedVersionCode = details?.minimumSupportedVersionCode,
                        latestVersionCode = details?.latestVersionCode,
                    ),
                )
            }

            throw ApiException(
                message = envelope?.error?.message
                    ?: fastApiValidationMessage(json, body)
                    ?: "Request failed (HTTP ${response.code}).",
                status = response.code,
                code = envelope?.error?.code,
            )
        }
    }
}

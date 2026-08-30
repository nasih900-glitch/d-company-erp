package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.diagnostics.ApiFailureObservation
import cloud.dcompany.erp.core.diagnostics.DiagnosticConnectivity
import cloud.dcompany.erp.core.diagnostics.DiagnosticsRuntime
import cloud.dcompany.erp.core.auth.PricingLock
import cloud.dcompany.erp.core.auth.TerminalStore
import cloud.dcompany.erp.core.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
internal const val CLIENT_COMPATIBILITY_POLICY_REVISION_HEADER =
    "X-Client-Compatibility-Policy-Revision"

/** A 426 body may be unavailable; preserve the monotonic header authority. */
internal fun resolvedCompatibilityPolicyRevision(
    bodyPolicyRevision: Int?,
    headerPolicyRevision: String?,
): Int {
    val body = bodyPolicyRevision?.coerceAtLeast(0) ?: 0
    val header = headerPolicyRevision
        ?.trim()
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
        ?.toIntOrNull()
        ?: 0
    return maxOf(body, header)
}

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
 * Applies a short-lived, caller-owned bearer without consulting or mutating
 * the process [TokenStore]. This is deliberately separate from
 * [ApiClient]'s normal AuthInterceptor: a protected owner may approve one
 * quarantined legacy action while the originating staff account and its Room
 * lease remain active. The bearer is never refreshed or persisted.
 */
internal fun Request.withEphemeralAuthority(
    accessToken: String?,
    terminalId: String?,
): Request {
    val scoped = withResolvedTerminal(terminalId)
    val builder = scoped.newBuilder().removeHeader("Authorization")
    val token = accessToken?.trim()?.takeIf(String::isNotEmpty)
    if (token != null) builder.header("Authorization", "Bearer $token")
    return builder.build()
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
    val diagnosticConflictEventId: String? = null,
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

/**
 * Build the deliberately minimal client used only by `/readyz` recovery
 * probes. Native-version enforcement applies to every Android request,
 * including unauthenticated readiness, so identity is the one application
 * interceptor this client must carry. Authentication, terminal authority and
 * ordinary error observation remain intentionally excluded.
 */
internal fun buildReadinessClient(
    versionCode: Int = BuildConfig.VERSION_CODE,
    distributionChannel: String = BuildConfig.DISTRIBUTION_CHANNEL,
): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .callTimeout(8, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .addInterceptor(ClientIdentityInterceptor(versionCode, distributionChannel))
    .build()

object ApiClient {

    internal val backendReachability = BackendReachabilityTracker()
    private val readinessClient by lazy {
        // Deliberately isolated from ErrorInterceptor: the connectivity
        // coordinator owns this proof and must not feed its own probe back
        // into the ordinary request-hint stream.
        buildReadinessClient()
    }

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

    /** A bounded, unauthenticated DB/Redis readiness proof for recovery only. */
    internal suspend fun probeBackendReadiness(): Boolean = withContext(Dispatchers.IO) {
        val url = BuildConfig.API_BASE_URL.toHttpUrl().resolve("/readyz")
            ?: return@withContext false
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cache-Control", "no-cache")
            .build()
        try {
            readinessClient.newCall(request).execute().use { response ->
                response.isSuccessful.also { ready ->
                    if (ready) backendReachability.recordReadinessProof()
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Creates an isolated, non-refreshing client for one transient authority
     * check. It never reads or writes [tokens], [activeTerminalHeaders], or
     * cache scope. Callers must keep the returned API and bearer in local
     * memory only and discard both when the operation completes.
     */
    internal inline fun <reified T> createEphemeralAuthorityApi(
        accessToken: String? = null,
        terminalId: String? = null,
    ): T = createEphemeralAuthorityApi(T::class.java, accessToken, terminalId)

    internal fun <T> createEphemeralAuthorityApi(
        service: Class<T>,
        accessToken: String? = null,
        terminalId: String? = null,
    ): T {
        val client = baseClientBuilder()
            .addInterceptor(ClientIdentityInterceptor())
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().withEphemeralAuthority(accessToken, terminalId),
                )
            }
            .addInterceptor(ErrorInterceptor(json))
            .build()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(service)
    }

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

        val client = authenticatedClientBuilder()
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

    /**
     * Feature-isolated authenticated client whose proof interceptor must run
     * once per physical network attempt. Remote device nonces cannot be
     * safely attached as an ordinary application interceptor because OkHttp
     * may transparently retry an exchange without re-running it.
     */
    internal fun <T> createApiWithNetworkProof(
        service: Class<T>,
        proofInterceptor: Interceptor,
    ): T {
        val client = remoteAuthenticatedClientBuilder()
            // Enrollment proof lives in the JSON body, so an opaque transport
            // retry would reuse its nonce. The coordinator owns retries and
            // rebuilds every proof; exact device endpoints never follow a
            // redirect to a different request target.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(proofInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(service)
    }

    private fun authenticatedClientBuilder(): OkHttpClient.Builder = baseClientBuilder()
        .addInterceptor(ClientIdentityInterceptor())
        .addInterceptor(TerminalInterceptor())
        .addInterceptor(
            PricingTokenInterceptor(allowPricingAuthority = true) {
                PricingLock.currentToken(tokens.currentPricingSession())
            },
        )
        .addInterceptor(ErrorInterceptor(json))
        .addInterceptor(AuthInterceptor())

    /** Remote support never receives the short-lived authority to mutate prices. */
    private fun remoteAuthenticatedClientBuilder(): OkHttpClient.Builder = baseClientBuilder()
        .addInterceptor(ClientIdentityInterceptor())
        .addInterceptor(TerminalInterceptor())
        .addInterceptor(
            PricingTokenInterceptor(allowPricingAuthority = false) {
                PricingLock.currentToken(tokens.currentPricingSession())
            },
        )
        .addInterceptor(ErrorInterceptor(json))
        .addInterceptor(AuthInterceptor())

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
            // Remote device enrollment carries its ECDSA nonce/signature in
            // the immutable JSON body. Replaying this same request here would
            // reuse that nonce. The refreshed bearer is retained, but the
            // coordinator must rebuild a fresh enrollment proof on its next
            // bounded attempt.
            if (!canReplayAfterBearerRefresh(original.url.encodedPath)) {
                return response
            }
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
    internal class PricingTokenInterceptor(
        private val allowPricingAuthority: Boolean,
        private val currentToken: () -> String?,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // Strip any caller-supplied value first. Only the normal ERP client
            // may reattach the current in-memory capability; remote support is
            // incapable of carrying it even if a future caller adds a header.
            val clean = chain.request().newBuilder()
                .removeHeader(PRICING_TOKEN_HEADER)
                .build()
            if (!allowPricingAuthority) return chain.proceed(clean)
            val token = currentToken() ?: return chain.proceed(clean)
            return chain.proceed(
                clean.newBuilder().header(PRICING_TOKEN_HEADER, token).build(),
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
                val explicitlyCancelled = chain.call().isCanceled()
                if (shouldPublishApiReachability(e, explicitlyCancelled)) {
                    backendReachability.recordApiFailure(e)
                }
                DiagnosticsRuntime.recordApiFailure(
                    ApiFailureObservation(
                        status = e.status,
                        serverCode = e.code,
                        encodedPath = diagnosticEncodedPath(chain.request().url.encodedPath),
                        connectivity = DiagnosticConnectivity.UNKNOWN,
                        explicitlyCancelled = explicitlyCancelled,
                    ),
                )
                throw e
            } catch (e: IOException) {
                // Retrofit cancels the OkHttp call when a bounded coroutine
                // check times out. Publishing that expected cancellation as a
                // server outage made the global status oscillate even on a
                // healthy connection.
                val explicitlyCancelled = chain.call().isCanceled()
                DiagnosticsRuntime.recordApiFailure(
                    ApiFailureObservation(
                        status = null,
                        serverCode = "network_error",
                        encodedPath = diagnosticEncodedPath(chain.request().url.encodedPath),
                        connectivity = DiagnosticConnectivity.UNKNOWN,
                        explicitlyCancelled = explicitlyCancelled,
                    ),
                )
                if (!shouldPublishTransportFailure(explicitlyCancelled)) {
                    throw e
                }
                backendReachability.recordTransportFailure()
                throw ApiException(
                    "Could not reach the server. Check the connection and try again.",
                    status = null,
                    code = "network_error",
                )
            }
            backendReachability.recordHttpResponse()
            if (response.isSuccessful) return response

            val compatibilityPolicyRevisionHeader =
                response.header(CLIENT_COMPATIBILITY_POLICY_REVISION_HEADER)
            val body = response.body?.string().orEmpty()
            response.close()
            val envelope = runCatching {
                json.decodeFromString(ApiErrorEnvelope.serializer(), body)
            }.getOrNull()

            if (
                response.request.header(PRICING_TOKEN_HEADER) != null &&
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
                        policyRevision = resolvedCompatibilityPolicyRevision(
                            bodyPolicyRevision = details?.policyRevision,
                            headerPolicyRevision = compatibilityPolicyRevisionHeader,
                        ),
                        latestVersionName = details?.latestVersionName,
                        releaseNotes = details?.releaseNotes,
                        apkSha256 = details?.apkSha256,
                        apkSizeBytes = details?.apkSizeBytes,
                        apkSigningCertSha256 = details?.apkSigningCertSha256,
                    ),
                )
            }

            val classified = ApiException(
                message = envelope?.error?.message
                    ?: fastApiValidationMessage(json, body)
                    ?: "Request failed (HTTP ${response.code}).",
                status = response.code,
                code = envelope?.error?.code,
                diagnosticConflictEventId = envelope?.error?.details?.clientEventId,
            )
            DiagnosticsRuntime.recordApiFailure(
                ApiFailureObservation(
                    status = classified.status,
                    serverCode = classified.code,
                    encodedPath = diagnosticEncodedPath(response.request.url.encodedPath),
                    connectivity = DiagnosticConnectivity.ONLINE,
                ),
            )
            throw classified
        }
    }
}

internal fun canReplayAfterBearerRefresh(encodedPath: String): Boolean =
    !encodedPath.endsWith("/remote-assistance/device/keys/enroll")

/** Never retain remote key/grant/session/command UUIDs in diagnostic observations. */
internal fun diagnosticEncodedPath(encodedPath: String): String =
    if (encodedPath.startsWith("/api/v1/remote-assistance/device/")) {
        REMOTE_DIAGNOSTIC_UUID.replace(encodedPath, "{id}")
    } else {
        encodedPath
    }

private val REMOTE_DIAGNOSTIC_UUID = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)

internal const val PRICING_TOKEN_HEADER = "X-Pricing-Token"

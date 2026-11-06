package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.net.AccountActionResponse
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.BuildConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.POST
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Captures the exit caller before any suspend; an unadopted cache is never made operational. */
internal class WorkspaceExitAuthority(
    val original: OriginalOperationBinding?,
    internal val login: SessionRefreshLease?,
    internal val originalCache: CacheScopeLease?,
    internal val originalGeneration: Long,
    private val currentLogin: () -> SessionRefreshLease?,
    private val accessFor: (SessionRefreshLease) -> String?,
    private val currentCache: () -> CacheScopeLease?,
    private val currentGeneration: () -> Long,
) {
    private fun sameLogin(): Boolean = if (login == null) currentLogin() == null else
        currentLogin()?.session?.lineage === login.session.lineage && accessFor(login) != null
    fun beforeDeactivation(): Boolean = sameLogin() && currentGeneration() == originalGeneration &&
        currentCache() == originalCache && (original?.isCurrent?.invoke() ?: (originalCache == null))
    fun afterDeactivation(): Boolean = sameLogin() && currentCache() == null &&
        currentGeneration() == originalGeneration + 1
    fun inactiveCacheUnchanged(): Boolean = currentCache() == null && currentGeneration() == originalGeneration + 1
    fun localSessionRemoved(): Boolean = currentLogin() == null && currentCache() == null &&
        currentGeneration() == originalGeneration + 1
    fun bearer(): String? = login?.let(accessFor)
    override fun toString() = "WorkspaceExitAuthority(<redacted>)"
}

/** Exact one-shot revocation after the clean-work/cache gate. It cannot authorize operational APIs. */
internal class WorkspaceLogoutGate(
    private val cap: HandoverCoordinator.WorkspaceExit,
    private val coordinator: HandoverCoordinator,
    private val authority: WorkspaceExitAuthority,
) : OperationDispatchGate {
    private val attempted = AtomicBoolean(false)
    override fun authorize(request: Request) {
        if (cap.kind != WorkspaceExitKind.LOGOUT || !cap.deactivated ||
            coordinator.state.value.workspaceExitKey != cap.key || coordinator.state.value.storageFault || !authority.afterDeactivation() ||
            request.method != "POST" || request.url.encodedPath != "/api/v1/auth/logout" || request.url.query != null ||
            request.tag(OperationDispatchContext::class.java) != null) {
            throw IOException("The original logout authority is unavailable.")
        }
    }
    override fun beforeAttempt(request: Request) {
        authorize(request)
        if (!attempted.compareAndSet(false, true)) throw IOException("The original logout was already attempted.")
    }
}

private interface CapturedLogoutApi {
    @POST("auth/logout") suspend fun logout(): AccountActionResponse
}

internal fun workspaceLogoutClient(authority: WorkspaceExitAuthority,
    cap: HandoverCoordinator.WorkspaceExit, coordinator: HandoverCoordinator): okhttp3.OkHttpClient =
    ApiClient.ephemeralAuthorityClient(authority.bearer(), terminalId = null,
        beforeDispatch = { check(authority.afterDeactivation()) }, singleAttempt = true,
        operationGate = WorkspaceLogoutGate(cap, coordinator, authority))

internal suspend fun logoutOriginalWorkspace(authority: WorkspaceExitAuthority,
    cap: HandoverCoordinator.WorkspaceExit, coordinator: HandoverCoordinator) {
    if (authority.bearer() == null) return
    Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL)
        .callFactory(workspaceLogoutClient(authority, cap, coordinator))
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build().create(CapturedLogoutApi::class.java).logout()
}

package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.ui.screens.gaming.GameSession
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

/** Strict raw branch proof: an empty/malformed claim must never become legacy company-wide authority. */
private fun selectorIdentity(token: String): OutboxOwnerIdentity? = runCatching {
    val identity = AccessTokenIdentityParser.parse(token) ?: return null
    val parts = token.split('.')
    require(parts.size == 3)
    val claims = ApiClient.json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString(throwOnInvalidSequence = true)) as JsonObject
    fun exactUuid(name: String, expected: String) {
        val raw = claims[name] as? JsonPrimitive
        require(raw?.isString == true && raw.content == expected)
        handoverUuid(expected)
    }
    exactUuid("sub", identity.userId); exactUuid("company_id", identity.companyId)
    val branch = claims["branch_id"]
    if (branch == null || branch == JsonNull) require(identity.branchId == null)
    else exactUuid("branch_id", requireNotNull(identity.branchId))
    identity
}.getOrNull()

/** Only the previously verified till-change selector can use this inactive-cache read authority. */
internal class WorkspaceSelectorGate(
    private val authority: WorkspaceExitAuthority,
    private val cap: HandoverCoordinator.WorkspaceExit,
    private val coordinator: HandoverCoordinator,
    val branchId: String,
    private val startup: () -> HandoverStartupStatus,
) : OperationDispatchGate {
    init { handoverUuid(branchId) }
    fun requireCurrent() {
        val original = authority.original
        val origin = original?.authority?.scope
        val cached = authority.originalCache
        val identity = authority.bearer()?.let(::selectorIdentity)
        // Without X-Terminal, the server uses exactly this JWT branch (null means company-wide).
        // A genuine legacy-null JWT is conservative company-wide even if the original cached branch is known.
        // Keep branch-wide/legacy NULL-terminal shift blockers visible; an old terminal header hides them.
        val state = coordinator.state.value
        if (cap.owner !== coordinator || cap.kind != WorkspaceExitKind.TERMINAL_CHANGE || !cap.deactivated ||
            cap.authority != original?.authority || original == null || cached == null || original.cacheLease != cached ||
            origin?.branchId != branchId || origin.terminalId == null ||
            cached.scope.userId != origin.actorId || cached.scope.companyId != origin.companyId ||
            cached.scope.terminalId != origin.terminalId || (cached.scope.branchId != null && cached.scope.branchId != branchId) ||
            identity?.userId != origin.actorId || identity.companyId != origin.companyId ||
            (identity.branchId != null && identity.branchId != cached.scope.branchId) ||
            !authority.afterDeactivation() || startup() != HandoverStartupStatus.Open || !state.initialized || state.storageFault ||
            state.workspaceExitKey != null || state.preparationAction != null || state.intent != null) {
            throw IOException("The original till selection changed. Sign in again before choosing a till.")
        }
    }
    override fun authorize(request: Request) {
        requireCurrent()
        val url = request.url
        val allowed = when (url.encodedPath) {
            "/api/v1/settings/terminals" -> url.encodedQuery == "branch_id=$branchId"
            "/api/v1/pos/shifts" -> url.encodedQuery == "only_open=true&limit=200"
            "/api/v1/gaming/sessions" -> url.encodedQuery == "unbilled_only=true&include_pos_linked_reviewable=false&limit=500"
            else -> false
        }
        if (!allowed || request.method != "GET" || request.header("X-Terminal-Id") != null ||
            request.tag(OperationDispatchContext::class.java) != null || request.tag(OwnerSupportDeliveryContext::class.java) != null) {
            throw IOException("Only the original till selection reads are available.")
        }
    }
}

internal interface WorkspaceSelectorApi {
    @GET("settings/terminals") suspend fun terminals(@Query("branch_id") branchId: String): List<Terminal>
    @GET("pos/shifts") suspend fun shifts(@Query("only_open") onlyOpen: Boolean = true,
        @Query("limit") limit: Int = 200): List<ShiftDetail>
    @GET("gaming/sessions") suspend fun sessions(@Query("unbilled_only") unbilledOnly: Boolean = true,
        @Query("include_pos_linked_reviewable") includePosLinkedReviewable: Boolean = false,
        @Query("limit") limit: Int = 500): List<GameSession>
}

/** No cache/Room capability is installed. Every decoded response is still bound to the original selector. */
internal class WorkspaceSelectorReads(private val gate: WorkspaceSelectorGate, private val api: WorkspaceSelectorApi) {
    val branchId: String get() = gate.branchId
    fun requireCurrent() = gate.requireCurrent()
    private suspend fun <T> read(block: suspend () -> T): T {
        gate.requireCurrent()
        val result = block()
        gate.requireCurrent()
        return result
    }
    suspend fun terminals(): List<Terminal> = read { api.terminals(branchId) }
    suspend fun shifts(): List<ShiftDetail> = read { api.shifts() }
    suspend fun sessions(): List<GameSession> = read { api.sessions() }
    override fun toString() = "WorkspaceSelectorReads(<redacted>)"
}

internal fun workspaceSelectorClient(authority: WorkspaceExitAuthority, gate: WorkspaceSelectorGate): okhttp3.OkHttpClient =
    ApiClient.ephemeralAuthorityClient(authority.bearer(), terminalId = null,
        beforeDispatch = gate::requireCurrent, singleAttempt = true, operationGate = gate)

internal fun workspaceSelectorReads(authority: WorkspaceExitAuthority, cap: HandoverCoordinator.WorkspaceExit,
    coordinator: HandoverCoordinator, branchId: String, startup: () -> HandoverStartupStatus): WorkspaceSelectorReads {
    val gate = WorkspaceSelectorGate(authority, cap, coordinator, branchId, startup)
    val api = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(workspaceSelectorClient(authority, gate))
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build().create(WorkspaceSelectorApi::class.java)
    return WorkspaceSelectorReads(gate, api)
}

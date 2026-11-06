package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.ui.screens.gaming.GamingApi
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path

@Serializable
internal data class IncomingTillState(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("shift_status") val shiftStatus: String,
    @SerialName("operator_user_id") val operatorUserId: String?,
    val revision: Int,
    val tracking: String,
    @SerialName("latest_handover") val latest: TillHandoverReceipt?,
    @SerialName("has_running_games") val hasRunningGames: Boolean,
) {
    fun verify(retained: HandoverIntent) {
        val receipt = requireNotNull(retained.receipt)
        check(retained.stage == HandoverStage.ACCEPTED && shiftId == receipt.shiftId && shiftStatus == "open" &&
            operatorUserId == receipt.incomingUserId && revision == receipt.toRevision && tracking == "recorded" &&
            latest?.verify(retained.action) == receipt)
    }

    fun verifyOwnerReturn(retained: HandoverIntent) {
        val accepted = requireNotNull(retained.receipt)
        val returned = requireNotNull(latest)
        handoverUuid(returned.id)
        handoverUuid(returned.initiatedBy)
        Instant.parse(returned.createdAt)
        check(retained.stage == HandoverStage.ACCEPTED && shiftId == accepted.shiftId && shiftStatus == "open" &&
            operatorUserId == accepted.initiatedBy && revision == accepted.toRevision + 1 && tracking == "recorded" &&
            returned.shiftId == accepted.shiftId && returned.branchId == accepted.branchId &&
            returned.terminalId == accepted.terminalId && returned.kind == "owner_recovery" &&
            returned.incomingUserId == accepted.initiatedBy && returned.previousOperator == accepted.incomingUserId &&
            returned.fromRevision == accepted.toRevision && returned.toRevision == revision &&
            returned.resolvesHandoverId == accepted.receiptId && returned.cashCountStatus == "NOT_COUNTED" &&
            returned.countedCashMinor == null && (returned.reason?.trim()?.length ?: 0) in 12..500)
    }

}

internal interface IncomingTillApi {
    @GET("pos/shifts/{id}/operator-state")
    suspend fun state(@Path("id") shiftId: String): IncomingTillState
}

/** Temporary B bearer, exact open shift and read-only hydration while ordinary admission stays closed. */
internal class AcceptedTillReadGate(
    private val receipt: AcceptedHandover,
    private val bearer: String,
    private val canReadGaming: Boolean,
    private val isCurrent: () -> Boolean,
) : OperationDispatchGate {
    override fun authorize(request: Request) {
        val path = request.url.encodedPath
        val query = request.url.encodedQuery
        val shift = (path == "/api/v1/pos/shifts/${receipt.shiftId}/operator-state" && query == null) ||
            (path == "/api/v1/pos/shifts" && query == "only_open=true&limit=200")
        val gaming = canReadGaming && when (path) {
            "/api/v1/gaming/stations", "/api/v1/gaming/packages" -> query == null
            "/api/v1/gaming/sessions" -> query == "unbilled_only=true&include_pos_linked_reviewable=false&limit=500"
            else -> query == null && gamingDetail.matches(path)
        }
        if (!isCurrent() || request.method != "GET" || !(shift || gaming) || request.body != null ||
            request.tag(OperationDispatchContext::class.java) != null ||
            request.tag(OwnerSupportDeliveryContext::class.java) != null) {
            throw IOException("The incoming operator's till read is unavailable.")
        }
    }

    override fun beforeAttempt(request: Request) {
        authorize(request)
        if (request.headers("Authorization") != listOf("Bearer $bearer") ||
            request.headers("X-Terminal-Id") != listOf(receipt.terminalId)) {
            throw IOException("The incoming operator or till header changed.")
        }
    }

    companion object {
        private val gamingDetail = Regex("/api/v1/gaming/sessions/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/(?:addons|participants)")
    }
}

internal class AcceptedTillReads(
    receipt: AcceptedHandover,
    bearer: String,
    canReadGaming: Boolean,
    isCurrent: () -> Boolean,
) {
    private val retrofit = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL)
        .callFactory(OperationTaggedCallFactory(ApiClient.ephemeralAuthorityClient(
            accessToken = bearer, terminalId = receipt.terminalId, singleAttempt = true,
            operationGate = AcceptedTillReadGate(receipt, bearer, canReadGaming, isCurrent),
        )))
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build()
    val till: IncomingTillApi = retrofit.create(IncomingTillApi::class.java)
    val shifts: ShiftApi = retrofit.create(ShiftApi::class.java)
    val gaming: GamingApi = retrofit.create(GamingApi::class.java)
}

package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Path
import retrofit2.http.DELETE
import retrofit2.http.Header

@Serializable
internal data class OriginalOperatorState(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("shift_status") val shiftStatus: String,
    @SerialName("operator_user_id") val operatorUserId: String?,
    val revision: Int,
    val tracking: String,
    @SerialName("own_active_checkout_order_ids") val ownActiveCheckoutOrderIds: List<String>,
) {
    fun requireOriginal(action: HandoverAction) {
        action.validate()
        requireOriginal(action.shiftId, action.origin.scope.actorId)
        check(revision == action.expectedRevision)
    }

    fun requireOriginal(expectedShift: String, actorId: String) {
        check(shiftId == expectedShift && shiftStatus == "open" && revision in 0 until Int.MAX_VALUE)
        check(if (tracking == "untracked") operatorUserId == null && revision == 0 else
            tracking == "recorded" && operatorUserId == actorId && revision >= 1)
        check(ownActiveCheckoutOrderIds.distinct().size == ownActiveCheckoutOrderIds.size)
        ownActiveCheckoutOrderIds.forEach(::handoverUuid)
    }
}
internal interface HandoverStateApi {
    @GET("auth/me") suspend fun me(): MeResponse
    @GET("pos/shifts/{id}/operator-state") suspend fun state(@Path("id") shiftId: String): OriginalOperatorState
    @DELETE("pos/orders/{id}/checkout-claim") suspend fun release(@Path("id") orderId: String, @Header("X-Checkout-Claim") token: String)
}


@Serializable
internal class TillHandoverWrite(
    @SerialName("incoming_user_id") val incomingUserId: String,
    val password: String,
    @SerialName("expected_revision") val expectedRevision: Int,
    val approved: Boolean,
) {
    override fun toString() = "TillHandoverWrite(<redacted>)"
}

/** The permit binds this immutable body instance to its retained non-secret action. */
internal class TillHandoverBody(val action: HandoverAction, password: String) : RequestBody() {
    private val body = HandoverJson.encodeToString(TillHandoverWrite.serializer(),
        TillHandoverWrite(action.incomingUserId, password, action.expectedRevision, approved = true))
        .toRequestBody("application/json".toMediaType())

    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    override fun toString() = "TillHandoverBody(<redacted>)"
}

@Serializable
internal data class TillHandoverReceipt(
    val id: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("initiated_by_user_id") val initiatedBy: String,
    @SerialName("previous_operator_user_id") val previousOperator: String?,
    @SerialName("incoming_user_id") val incomingUserId: String,
    @SerialName("from_revision") val fromRevision: Int,
    @SerialName("to_revision") val toRevision: Int,
    @SerialName("cash_count_status") val cashCountStatus: String,
    @SerialName("counted_cash_minor") val countedCashMinor: Long?,
    @SerialName("handover_kind") val kind: String,
    val reason: String?,
    @SerialName("resolves_handover_id") val resolvesHandoverId: String?,
    @SerialName("created_at") val createdAt: String,
) {
    fun verify(action: HandoverAction): AcceptedHandover {
        check(kind == "handover" && cashCountStatus == "NOT_COUNTED" && countedCashMinor == null &&
            reason == null && resolvesHandoverId == null)
        check(previousOperator == action.origin.scope.actorId.takeIf { action.expectedRevision != 0 })
        Instant.parse(createdAt)
        // The server does not echo these two fields. They are bound by the exact authenticated call.
        return AcceptedHandover(id, action.key, action.origin.scope.companyId, branchId, terminalId,
            shiftId, initiatedBy, incomingUserId, fromRevision, toRevision).also { it.validate(action) }
    }
}

@Serializable
internal data class TillOperatorCandidate(val id: String, val name: String)

@Serializable
internal data class TillOperatorCandidates(
    val items: List<TillOperatorCandidate>,
    @SerialName("next_cursor") val nextCursor: String?,
) {
    fun verify(actorId: String, afterId: String?) {
        check(items.size <= 50 && items.map { it.id }.distinct().size == items.size)
        items.forEach { person ->
            handoverUuid(person.id)
            check(person.id != actorId && person.name.isNotBlank() && person.name.length <= 200 &&
                (afterId == null || person.id > afterId))
        }
        nextCursor?.let {
            handoverUuid(it)
            check((afterId == null || it > afterId) && items.all { person -> person.id <= it })
        }
    }
}

internal interface TillHandoverApi {
    @GET("auth/me") suspend fun me(): MeResponse

    @GET("pos/shifts/{id}/operator-state")
    suspend fun state(@Path("id") shiftId: String): OriginalOperatorState

    @GET("pos/shifts/{id}/operator-candidates")
    suspend fun candidates(@Path("id") shiftId: String, @Query("after_id") afterId: String?): TillOperatorCandidates

    @POST("pos/shifts/{id}/operator-handovers")
    suspend fun send(@Path("id") shiftId: String, @Header("Idempotency-Key") actionKey: String,
        @Body body: RequestBody): TillHandoverReceipt

    @GET("pos/shifts/{id}/operator-handover-receipt")
    suspend fun receipt(@Path("id") shiftId: String, @Query("action_key") actionKey: String): TillHandoverReceipt
}


private val tillHandoverJson = Json(ApiClient.json) {
    explicitNulls = true
    coerceInputValues = false
}

internal fun buildTillHandoverApi(client: Call.Factory, baseUrl: String = BuildConfig.API_BASE_URL,
    boundRead: OriginalOperationBinding? = null): TillHandoverApi =
    Retrofit.Builder().baseUrl(baseUrl).callFactory(OperationTaggedCallFactory(client, boundRead))
        .addConverterFactory(tillHandoverJson.asConverterFactory("application/json".toMediaType()))
        .build().create(TillHandoverApi::class.java)

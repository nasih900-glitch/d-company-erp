package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.net.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Mirrors PosRefundRequestCreate; every money/staleness field is captured locally. */
@Serializable
data class PosRefundRequestBody(
    @SerialName("order_id") val orderId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("expected_paid_minor") val expectedPaidMinor: Long,
    @SerialName("expected_refundable_minor") val expectedRefundableMinor: Long,
    /** "cash" or "original". */
    val mode: String,
    @SerialName("manager_override_user_id") val managerOverrideUserId: String? = null,
    @SerialName("client_action_id") val clientActionId: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("provider_settled_at") val providerSettledAt: String? = null,
    val note: String? = null,
)

@Serializable
data class PosRefundHandoffBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("ready_to_handover") val readyToHandover: Boolean = true,
)

@Serializable
data class PosRefundCashSettlementBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("cash_handed_over") val cashHandedOver: Boolean = true,
    @SerialName("settled_at") val settledAt: String,
)

@Serializable
data class PosRefundWithdrawalBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("cash_not_handed_over") val cashNotHandedOver: Boolean = true,
    val reason: String,
    @SerialName("withdrawn_at") val withdrawnAt: String,
)

@Serializable
data class PosRefundRequestResult(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("reason_code") val reasonCode: String,
    val mode: String,
    @SerialName("settlement_method") val settlementMethod: String,
    val status: String,
    @SerialName("accepted_at") val acceptedAt: String,
    @SerialName("handoff_started_at") val handoffStartedAt: String? = null,
    @SerialName("settled_at") val settledAt: String? = null,
    @SerialName("withdrawn_at") val withdrawnAt: String? = null,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("receipt_no") val receiptNo: String? = null,
    @SerialName("refund_id") val refundId: String? = null,
    @SerialName("client_action_id") val clientActionId: String,
    @SerialName("customer_spend_reconciled") val customerSpendReconciled: Boolean? = null,
    val note: String? = null,
)

interface RefundsApi {

    @GET("pos/orders")
    suspend fun orders(@Query("status") status: String? = null): List<Order>

    @GET("pos/orders/{id}")
    suspend fun order(@Path("id") id: String): Order

    @POST("pos/refund-requests")
    suspend fun requestRefund(
        @Body body: PosRefundRequestBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/begin-cash-handoff")
    suspend fun beginCashHandoff(
        @Path("id") id: String,
        @Body body: PosRefundHandoffBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/settle-cash")
    suspend fun settleCash(
        @Path("id") id: String,
        @Body body: PosRefundCashSettlementBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/withdraw-cash")
    suspend fun withdrawCash(
        @Path("id") id: String,
        @Body body: PosRefundWithdrawalBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @GET("pos/refund-requests")
    suspend fun refundRequests(
        @Query("unresolved") unresolved: Boolean = true,
        @Query("shift_id") shiftId: String? = null,
        @Query("client_action_id") clientActionId: String? = null,
        @Query("limit") limit: Int = 200,
    ): List<PosRefundRequestResult>
}

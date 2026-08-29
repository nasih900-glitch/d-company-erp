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
    // Required affirmative confirmations must not have Kotlin defaults. The
    // production Json encoder omits values equal to their default, which would
    // silently remove these safety fields and make the backend reject the
    // staged money action with HTTP 422.
    @SerialName("ready_to_handover") val readyToHandover: Boolean,
)

@Serializable
data class PosRefundCashSettlementBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("cash_handed_over") val cashHandedOver: Boolean,
    @SerialName("settled_at") val settledAt: String,
)

@Serializable
data class PosRefundProviderPayoutStartBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("ready_to_start_provider_payout")
    val readyToStartProviderPayout: Boolean,
)

@Serializable
data class PosRefundProviderSettlementBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("provider_completed") val providerCompleted: Boolean,
    @SerialName("external_reference") val externalReference: String,
    @SerialName("provider_settled_at") val providerSettledAt: String,
)

@Serializable
data class PosRefundAccountingFinalizationBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
)

@Serializable
data class PosRefundWithdrawalBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("cash_not_handed_over") val cashNotHandedOver: Boolean,
    val reason: String,
    @SerialName("withdrawn_at") val withdrawnAt: String,
)

@Serializable
data class PosRefundProviderWithdrawalBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("provider_not_completed") val providerNotCompleted: Boolean,
    val reason: String,
    @SerialName("withdrawn_at") val withdrawnAt: String,
)

@Serializable
data class PosRefundCashHandoffResolutionBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("cash_not_handed_over") val cashNotHandedOver: Boolean,
    @SerialName("drawer_unchanged") val drawerUnchanged: Boolean,
    val reason: String,
    @SerialName("resolved_at") val resolvedAt: String,
)

@Serializable
data class PosRefundProviderPayoutResolutionBody(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("provider_not_completed") val providerNotCompleted: Boolean,
    @SerialName("provider_status") val providerStatus: String,
    @SerialName("verification_reference") val verificationReference: String,
    @SerialName("provider_checked_at") val providerCheckedAt: String,
    val reason: String,
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
    @SerialName("accepted_by") val acceptedBy: String? = null,
    @SerialName("accepted_by_name") val acceptedByName: String? = null,
    @SerialName("handoff_started_at") val handoffStartedAt: String? = null,
    @SerialName("handoff_started_by") val handoffStartedBy: String? = null,
    @SerialName("handoff_started_by_name") val handoffStartedByName: String? = null,
    @SerialName("cash_handed_over_at") val cashHandedOverAt: String? = null,
    @SerialName("cash_handed_over_recorded_at") val cashHandedOverRecordedAt: String? = null,
    @SerialName("cash_handed_over_by") val cashHandedOverBy: String? = null,
    @SerialName("cash_handed_over_by_name") val cashHandedOverByName: String? = null,
    @SerialName("provider_payout_started_at") val providerPayoutStartedAt: String? = null,
    @SerialName("provider_payout_started_by") val providerPayoutStartedBy: String? = null,
    @SerialName("provider_payout_started_by_name") val providerPayoutStartedByName: String? = null,
    @SerialName("provider_completed_at") val providerCompletedAt: String? = null,
    @SerialName("provider_completion_recorded_at") val providerCompletionRecordedAt: String? = null,
    @SerialName("provider_completed_by") val providerCompletedBy: String? = null,
    @SerialName("provider_completed_by_name") val providerCompletedByName: String? = null,
    @SerialName("settled_at") val settledAt: String? = null,
    @SerialName("settled_by") val settledBy: String? = null,
    @SerialName("settled_by_name") val settledByName: String? = null,
    @SerialName("client_occurred_at") val clientOccurredAt: String? = null,
    @SerialName("captured_time_reconciled") val capturedTimeReconciled: Boolean? = null,
    @SerialName("provider_evidence_reconciled") val providerEvidenceReconciled: Boolean? = null,
    @SerialName("withdrawn_at") val withdrawnAt: String? = null,
    @SerialName("withdrawn_by") val withdrawnBy: String? = null,
    @SerialName("withdrawn_by_name") val withdrawnByName: String? = null,
    @SerialName("provider_verification_status") val providerVerificationStatus: String? = null,
    @SerialName("provider_verification_reference") val providerVerificationReference: String? = null,
    @SerialName("provider_verified_at") val providerVerifiedAt: String? = null,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("receipt_no") val receiptNo: String? = null,
    @SerialName("refund_id") val refundId: String? = null,
    @SerialName("client_action_id") val clientActionId: String,
    @SerialName("customer_spend_reconciled") val customerSpendReconciled: Boolean? = null,
    @SerialName("loyalty_reconciliation_state") val loyaltyReconciliationState: String? = null,
    val note: String? = null,
)

interface RefundsApi {

    @GET("pos/orders")
    suspend fun orders(@Query("status") status: List<String>): List<Order>

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

    @POST("pos/refund-requests/{id}/finalize-cash")
    suspend fun finalizeCash(
        @Path("id") id: String,
        @Body body: PosRefundAccountingFinalizationBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/begin-provider-payout")
    suspend fun beginProviderPayout(
        @Path("id") id: String,
        @Body body: PosRefundProviderPayoutStartBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/settle-provider")
    suspend fun settleProvider(
        @Path("id") id: String,
        @Body body: PosRefundProviderSettlementBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/finalize-provider")
    suspend fun finalizeProvider(
        @Path("id") id: String,
        @Body body: PosRefundAccountingFinalizationBody,
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

    @POST("pos/refund-requests/{id}/withdraw-provider")
    suspend fun withdrawProvider(
        @Path("id") id: String,
        @Body body: PosRefundProviderWithdrawalBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/resolve-cash-handoff")
    suspend fun resolveCashHandoff(
        @Path("id") id: String,
        @Body body: PosRefundCashHandoffResolutionBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PosRefundRequestResult

    @POST("pos/refund-requests/{id}/resolve-provider-payout")
    suspend fun resolveProviderPayout(
        @Path("id") id: String,
        @Body body: PosRefundProviderPayoutResolutionBody,
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

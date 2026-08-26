package cloud.dcompany.erp.ui.screens.memberships

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

/**
 * Tier create/edit stays web-only (Settings → Memberships already has a
 * working UI) — this screen only ever reads tiers, never writes them.
 *
 * Subscribe carries an Idempotency-Key: starts_at/expires_at are computed
 * server-side fresh on every call, and the overlap check guards against a
 * *second* real subscription for the same customer, not a retry of the
 * same one — a retry without a stable key would silently mint a duplicate
 * membership term, same reasoning as Events' ticket sales. Cancel and refund
 * also carry stable keys: a lost response must replay successfully instead of
 * leaving a definitively-completed server action rejected in the local outbox.
 *
 * Writes are gated server-side by `tenant.protected_access`
 * (super_owner/co_owner only), and the screen mirrors that gate so ordinary
 * staff never see an action that will inevitably fail. Paid membership and
 * refund initiation also require a live backend connection; the outbox only
 * preserves the original identity across an ambiguous response/retry.
 */
interface MembershipsApi {

    @GET("memberships/tiers")
    suspend fun listTiers(): List<MembershipTier>

    @POST("memberships/subscribe")
    suspend fun subscribe(
        @Body body: SubscribeRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Subscription

    @POST("memberships/payment-requests")
    suspend fun preparePayment(
        @Body body: MembershipPaymentRequestCreate,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentTask

    @POST("memberships/payment-requests/{id}/begin-cash-collection")
    suspend fun beginCashCollection(
        @Path("id") id: String,
        @Body body: MembershipPaymentCashCollectionRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentTask

    @POST("memberships/payment-requests/{id}/begin-provider-action")
    suspend fun beginProviderAction(
        @Path("id") id: String,
        @Body body: MembershipPaymentProviderActionRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentTask

    @POST("memberships/payment-requests/{id}/settle")
    suspend fun settlePayment(
        @Path("id") id: String,
        @Body body: MembershipPaymentSettlementRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentTask

    @POST("memberships/payment-requests/{id}/finalize")
    suspend fun finalizePayment(
        @Path("id") id: String,
        @Body body: MembershipPaymentFinalizationRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentTask

    @POST("memberships/payment-requests/{id}/withdraw")
    suspend fun withdrawPayment(
        @Path("id") id: String,
        @Body body: MembershipPaymentWithdrawalRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentTask

    @GET("memberships/payment-requests")
    suspend fun paymentRequests(
        @Query("unresolved") unresolved: Boolean = false,
        @Query("shift_id") shiftId: String? = null,
        @Query("client_action_id") clientActionId: String? = null,
        /** Accepted by the concurrently hardened backend; older servers ignore it safely. */
        @Query("request_id") requestId: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<List<MembershipPaymentTask>>

    @POST("memberships/payment-attempts/resolve")
    suspend fun resolveLegacyPaymentAttempt(
        @Body body: MembershipPaymentAttemptResolutionRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipPaymentAttemptResolution

    /** Returns the customer's current *active* subscription, or a literal
     * JSON null if none — never a 404. */
    @GET("memberships/customer/{customer_id}")
    suspend fun getCustomerSubscription(@Path("customer_id") customerId: String): Subscription?

    @GET("memberships/customer/{customer_id}/history")
    suspend fun getCustomerMembershipHistory(
        @Path("customer_id") customerId: String,
    ): List<Subscription>

    @POST("memberships/{subscription_id}/cancel")
    suspend fun cancel(
        @Path("subscription_id") subscriptionId: String,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Subscription

    @POST("memberships/{subscription_id}/refund")
    suspend fun refund(
        @Path("subscription_id") subscriptionId: String,
        @Body body: MembershipRefundRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @POST("memberships/refunds/{refund_id}/begin-cash-handoff")
    suspend fun beginRefundCashHandoff(
        @Path("refund_id") refundId: String,
        @Body body: MembershipRefundCashHandoffRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @POST("memberships/refunds/{refund_id}/begin-provider-action")
    suspend fun beginRefundProviderAction(
        @Path("refund_id") refundId: String,
        @Body body: MembershipRefundProviderActionRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @POST("memberships/refunds/{refund_id}/settle-cash")
    suspend fun settleCashRefund(
        @Path("refund_id") refundId: String,
        @Body body: CashMembershipRefundSettlementRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @POST("memberships/refunds/{refund_id}/settle-provider")
    suspend fun settleProviderRefund(
        @Path("refund_id") refundId: String,
        @Body body: ProviderMembershipRefundSettlementRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @POST("memberships/refunds/{refund_id}/finalize")
    suspend fun finalizeRefund(
        @Path("refund_id") refundId: String,
        @Body body: MembershipRefundFinalizationRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @POST("memberships/refunds/{refund_id}/withdraw")
    suspend fun resolveRefund(
        @Path("refund_id") refundId: String,
        @Body body: MembershipRefundResolutionRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask

    @GET("memberships/refunds")
    suspend fun refundTasks(
        @Query("unresolved") unresolved: Boolean = true,
        @Query("shift_id") shiftId: String? = null,
        @Query("refund_id") refundId: String? = null,
        @Query("client_action_id") clientActionId: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<List<MembershipRefundTask>>

    @POST("memberships/refund-attempts/register")
    suspend fun registerRefundAttempt(
        @Body body: MembershipRefundAttemptRegistrationRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundAttemptRecovery

    @GET("memberships/refund-attempts")
    suspend fun refundAttempts(
        @Query("unresolved") unresolved: Boolean = true,
        @Query("source_shift_id") sourceShiftId: String? = null,
        @Query("recovery_id") recoveryId: String? = null,
        @Query("original_client_action_id") originalClientActionId: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<List<MembershipRefundAttemptRecovery>>

    @POST("memberships/refund-attempts/resolve")
    suspend fun resolveRefundAttempt(
        @Body body: MembershipRefundAttemptResolutionRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundAttemptResolution

    @POST("memberships/refunds/{refund_id}/withdraw-cash")
    suspend fun withdrawCashRefund(
        @Path("refund_id") refundId: String,
        @Body body: CashMembershipRefundWithdrawalRequest,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MembershipRefundTask
}

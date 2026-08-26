package cloud.dcompany.erp.ui.screens.memberships

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field names copied verbatim from backend/app/api/v1/memberships/router.py
 * (TierRead, SubscriptionRead, SubscribeRequest) and cross-checked against
 * the already-shipped web app's MembershipTierDTO/SubscriptionDTO
 * (frontend/src/lib/erp-api.ts) and the already-shipped iOS native app's
 * MembershipTierDTO/SubscribeRequest structs, which matched exactly.
 * `*_minor` fields are paise and stay a Long the whole way; discount/
 * multiplier fields are genuine fractions (0.10 = 10% off), so those stay
 * Double, matching Ingredient's own qty-vs-money split.
 */

@Serializable
data class MembershipTier(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("monthly_price_minor") val monthlyPriceMinor: Long,
    @SerialName("annual_price_minor") val annualPriceMinor: Long? = null,
    @SerialName("food_discount_pct") val foodDiscountPct: Double = 0.0,
    @SerialName("gaming_discount_pct") val gamingDiscountPct: Double = 0.0,
    @SerialName("hookah_discount_pct") val hookahDiscountPct: Double = 0.0,
    @SerialName("point_multiplier") val pointMultiplier: Double = 1.0,
    @SerialName("free_gaming_minutes_per_week") val freeGamingMinutesPerWeek: Int = 0,
    @SerialName("free_hookah_per_month") val freeHookahPerMonth: Int = 0,
    @SerialName("priority_booking") val priorityBooking: Boolean = false,
    val description: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Subscription(
    val id: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("tier_code") val tierCode: String,
    @SerialName("tier_name") val tierName: String,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("auto_renew") val autoRenew: Boolean,
    @SerialName("amount_paid_minor") val amountPaidMinor: Long,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_shift_id") val paymentShiftId: String? = null,
    @SerialName("payment_receipt_no") val paymentReceiptNo: String? = null,
    @SerialName("payment_paid_at") val paymentPaidAt: String? = null,
    @SerialName("payment_evidence_occurred_at") val paymentEvidenceOccurredAt: String? = null,
    @SerialName("payment_evidence_time_untrusted") val paymentEvidenceTimeUntrusted: Boolean = false,
    @SerialName("payment_provider_evidence_reconciled") val paymentProviderEvidenceReconciled: Boolean = true,
    @SerialName("refund_id") val refundId: String? = null,
    @SerialName("refund_status") val refundStatus: String? = null,
    @SerialName("refund_accepted_at") val refundAcceptedAt: String? = null,
    @SerialName("refunded_at") val refundedAt: String? = null,
    @SerialName("refund_method") val refundMethod: String? = null,
    @SerialName("refund_receipt_no") val refundReceiptNo: String? = null,
    @SerialName("refund_external_reference") val refundExternalReference: String? = null,
    @SerialName("refund_evidence_occurred_at") val refundEvidenceOccurredAt: String? = null,
    @SerialName("refund_evidence_time_untrusted") val refundEvidenceTimeUntrusted: Boolean = false,
    @SerialName("refund_provider_evidence_reconciled") val refundProviderEvidenceReconciled: Boolean = true,
    @SerialName("refund_customer_spend_reconciled") val refundCustomerSpendReconciled: Boolean = true,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class MembershipPaymentRequestCreate(
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("billing_cycle") val billingCycle: String = "monthly",
    @SerialName("paid_via") val paidVia: String,
    @SerialName("client_action_id") val clientActionId: String,
)

@Serializable
data class MembershipPaymentCashCollectionRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("ready_to_collect") val readyToCollect: Boolean = true,
)

@Serializable
data class MembershipPaymentProviderActionRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("ready_to_start") val readyToStart: Boolean = true,
)

@Serializable
data class MembershipPaymentSettlementRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("collected_at") val collectedAt: String,
    @SerialName("payment_received") val paymentReceived: Boolean = true,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
)

@Serializable
data class MembershipPaymentFinalizationRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
)

@Serializable
data class MembershipPaymentWithdrawalRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    val resolution: String,
    val reason: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("action_state_verified") val actionStateVerified: Boolean = false,
    @SerialName("provider_verification_status") val providerVerificationStatus: String? = null,
    @SerialName("provider_verification_reference") val providerVerificationReference: String? = null,
    @SerialName("provider_evidence_occurred_at") val providerEvidenceOccurredAt: String? = null,
    @SerialName("cash_return_confirmed") val cashReturnConfirmed: Boolean = false,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
)

@Serializable
data class MembershipPaymentTask(
    val id: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("paid_via") val paidVia: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String,
    @SerialName("tier_code") val tierCode: String,
    @SerialName("tier_name") val tierName: String,
    val status: String,
    @SerialName("accepted_at") val acceptedAt: String,
    @SerialName("prepared_by") val preparedBy: String,
    @SerialName("prepared_by_name") val preparedByName: String? = null,
    @SerialName("collection_started_at") val collectionStartedAt: String? = null,
    @SerialName("value_completed_at") val valueCompletedAt: String? = null,
    @SerialName("value_completed_by") val valueCompletedBy: String? = null,
    @SerialName("value_completed_by_name") val valueCompletedByName: String? = null,
    @SerialName("action_started_by") val actionStartedBy: String? = null,
    @SerialName("action_started_by_name") val actionStartedByName: String? = null,
    @SerialName("action_kind") val actionKind: String? = null,
    @SerialName("settled_at") val settledAt: String? = null,
    @SerialName("settled_by") val settledBy: String? = null,
    @SerialName("settled_by_name") val settledByName: String? = null,
    @SerialName("membership_id") val membershipId: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("receipt_no") val receiptNo: String? = null,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("evidence_occurred_at") val evidenceOccurredAt: String? = null,
    @SerialName("evidence_time_untrusted") val evidenceTimeUntrusted: Boolean = false,
    @SerialName("provider_evidence_reconciled") val providerEvidenceReconciled: Boolean = true,
    @SerialName("customer_spend_reconciled") val customerSpendReconciled: Boolean = false,
    val resolution: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("resolved_by_name") val resolvedByName: String? = null,
    @SerialName("action_state_verified") val actionStateVerified: Boolean = false,
    @SerialName("provider_verification_status") val providerVerificationStatus: String? = null,
    @SerialName("provider_verification_reference") val providerVerificationReference: String? = null,
    @SerialName("provider_checked_at") val providerCheckedAt: String? = null,
    @SerialName("cash_return_confirmed") val cashReturnConfirmed: Boolean = false,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
    @SerialName("client_action_id") val clientActionId: String,
)

@Serializable
data class MembershipPaymentAttemptResolutionRequest(
    @SerialName("original_client_action_id") val originalClientActionId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("paid_via") val paidVia: String,
    val resolution: String,
    val reason: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("provider_verification_status") val providerVerificationStatus: String? = null,
    @SerialName("provider_evidence_occurred_at") val providerEvidenceOccurredAt: String? = null,
    @SerialName("cash_return_confirmed") val cashReturnConfirmed: Boolean = false,
)

@Serializable
data class MembershipPaymentAttemptResolution(
    val id: String,
    @SerialName("original_client_action_id") val originalClientActionId: String,
    @SerialName("resolved_at") val resolvedAt: String,
)

@Serializable
data class SubscribeRequest(
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("collected_at") val collectedAt: String,
    @SerialName("billing_cycle") val billingCycle: String = "monthly",
    @SerialName("paid_via") val paidVia: String = "cash",
)

@Serializable
data class MembershipRefundRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    val method: String,
    val reason: String,
    @SerialName("settled_at") val settledAt: String? = null,
    @SerialName("external_reference") val externalReference: String? = null,
)

@Serializable
data class MembershipRefundCashHandoffRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("ready_to_handover") val readyToHandover: Boolean = true,
)

@Serializable
data class MembershipRefundProviderActionRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("ready_to_start") val readyToStart: Boolean = true,
)

@Serializable
data class ProviderMembershipRefundSettlementRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("settled_at") val settledAt: String,
    @SerialName("provider_refund_completed") val providerRefundCompleted: Boolean = true,
    @SerialName("external_reference") val externalReference: String,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
)

@Serializable
data class MembershipRefundFinalizationRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
)

@Serializable
data class MembershipRefundResolutionRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    val resolution: String,
    val reason: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("action_state_verified") val actionStateVerified: Boolean = false,
    @SerialName("provider_verification_status") val providerVerificationStatus: String? = null,
    @SerialName("provider_verification_reference") val providerVerificationReference: String? = null,
    @SerialName("provider_evidence_occurred_at") val providerEvidenceOccurredAt: String? = null,
    @SerialName("cash_return_confirmed") val cashReturnConfirmed: Boolean = false,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
)

@Serializable
data class MembershipRefundTask(
    val id: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("payment_id") val paymentId: String,
    @SerialName("shift_id") val shiftId: String,
    val method: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("accepted_at") val acceptedAt: String,
    val status: String,
    @SerialName("handoff_started_at") val handoffStartedAt: String? = null,
    @SerialName("payout_completed_at") val payoutCompletedAt: String? = null,
    @SerialName("payout_completed_by") val payoutCompletedBy: String? = null,
    @SerialName("payout_completed_by_name") val payoutCompletedByName: String? = null,
    @SerialName("accepted_by") val acceptedBy: String? = null,
    @SerialName("accepted_by_name") val acceptedByName: String? = null,
    @SerialName("action_started_by") val actionStartedBy: String? = null,
    @SerialName("action_started_by_name") val actionStartedByName: String? = null,
    @SerialName("action_kind") val actionKind: String? = null,
    @SerialName("settled_at") val settledAt: String? = null,
    @SerialName("settled_by") val settledBy: String? = null,
    @SerialName("settled_by_name") val settledByName: String? = null,
    val reason: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("receipt_no") val receiptNo: String? = null,
    @SerialName("entitlement_restored") val entitlementRestored: Boolean = false,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("tier_name") val tierName: String? = null,
    @SerialName("original_payment_receipt_no") val originalPaymentReceiptNo: String? = null,
    val resolution: String? = null,
    @SerialName("resolution_reason") val resolutionReason: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("resolved_by_name") val resolvedByName: String? = null,
    @SerialName("evidence_occurred_at") val evidenceOccurredAt: String? = null,
    @SerialName("evidence_time_untrusted") val evidenceTimeUntrusted: Boolean = false,
    @SerialName("provider_evidence_reconciled") val providerEvidenceReconciled: Boolean = true,
    @SerialName("customer_spend_reconciled") val customerSpendReconciled: Boolean = true,
    @SerialName("action_state_verified") val actionStateVerified: Boolean = false,
    @SerialName("provider_verification_status") val providerVerificationStatus: String? = null,
    @SerialName("provider_verification_reference") val providerVerificationReference: String? = null,
    @SerialName("provider_checked_at") val providerCheckedAt: String? = null,
    @SerialName("cash_return_confirmed") val cashReturnConfirmed: Boolean = false,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
)

@Serializable
data class MembershipRefundAttemptRegistrationRequest(
    @SerialName("original_client_action_id") val originalClientActionId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("payment_id") val paymentId: String,
    @SerialName("source_shift_id") val sourceShiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("paid_via") val paidVia: String,
    @SerialName("captured_at") val capturedAt: String,
)

@Serializable
data class MembershipRefundAttemptRecovery(
    val id: String,
    @SerialName("original_client_action_id") val originalClientActionId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("payment_id") val paymentId: String,
    @SerialName("source_shift_id") val sourceShiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("paid_via") val paidVia: String,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("captured_time_untrusted") val capturedTimeUntrusted: Boolean = false,
    @SerialName("registered_at") val registeredAt: String,
    @SerialName("registered_by") val registeredBy: String,
    @SerialName("registered_by_name") val registeredByName: String? = null,
    val status: String,
    @SerialName("resolution_id") val resolutionId: String? = null,
)

@Serializable
data class MembershipRefundAttemptResolutionRequest(
    @SerialName("original_client_action_id") val originalClientActionId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("payment_id") val paymentId: String,
    @SerialName("source_shift_id") val sourceShiftId: String,
    @SerialName("reconciliation_shift_id") val reconciliationShiftId: String? = null,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("paid_via") val paidVia: String,
    val outcome: String,
    val reason: String,
    @SerialName("provider_status") val providerStatus: String? = null,
    @SerialName("verification_reference") val verificationReference: String? = null,
    @SerialName("evidence_occurred_at") val evidenceOccurredAt: String? = null,
    @SerialName("cash_handover_confirmed") val cashHandoverConfirmed: Boolean = false,
)

@Serializable
data class MembershipRefundAttemptResolution(
    val id: String,
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("original_client_action_id") val originalClientActionId: String,
    @SerialName("financial_status") val financialStatus: String,
    @SerialName("refund_receipt_no") val refundReceiptNo: String? = null,
    @SerialName("customer_spend_reconciled") val customerSpendReconciled: Boolean = true,
)

@Serializable
data class CashMembershipRefundSettlementRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
    @SerialName("settled_at") val settledAt: String,
    @SerialName("cash_handed_over") val cashHandedOver: Boolean = true,
    @SerialName("action_takeover_confirmed") val actionTakeoverConfirmed: Boolean = false,
    @SerialName("action_takeover_reason") val actionTakeoverReason: String? = null,
)

@Serializable
data class CashMembershipRefundWithdrawalRequest(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("cash_not_handed_over") val cashNotHandedOver: Boolean = true,
    val reason: String,
)

@Serializable
data class MembershipRefundResult(
    val id: String,
    @SerialName("membership_id") val membershipId: String,
    @SerialName("payment_id") val paymentId: String,
    @SerialName("shift_id") val shiftId: String,
    val method: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("accepted_at") val acceptedAt: String,
    val status: String,
    @SerialName("settled_at") val settledAt: String? = null,
    val reason: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("receipt_no") val receiptNo: String? = null,
    @SerialName("entitlement_restored") val entitlementRestored: Boolean = false,
)

fun billingCycleLabel(cycle: String): String = if (cycle == "annual") "Annual" else "Monthly"

/** "10%", "10.5%" — trailing zeros trimmed. Mirrors Finance's Double.asSharePct(). */
fun Double.asDiscountPct(): String {
    val pct = this * 100
    val text = String.format(java.util.Locale.UK, "%.2f", pct).trimEnd('0').trimEnd('.')
    return "$text%"
}

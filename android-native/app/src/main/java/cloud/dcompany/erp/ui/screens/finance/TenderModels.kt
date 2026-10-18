package cloud.dcompany.erp.ui.screens.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Online-only, owner-attested corrections. Original financial evidence is immutable. */
@Serializable
data class TenderUnlockRequest(
    @SerialName("branch_id") val branchId: String,
    val scope: String,
    val password: String,
)

@Serializable
data class TenderUnlockResponse(
    val token: String,
    @SerialName("history_token") val historyToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class PaymentTenderPrepare(
    @SerialName("payment_id") val paymentId: String,
)

@Serializable
data class PaymentTenderCommit(
    @SerialName("payment_id") val paymentId: String,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    val reason: String,
    @SerialName("evidence_reference") val evidenceReference: String? = null,
)

@Serializable
data class DailyTenderPrepare(
    @SerialName("branch_id") val branchId: String,
    @SerialName("business_date") val businessDate: String,
)

@Serializable
data class DailyTenderCommit(
    @SerialName("branch_id") val branchId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    @SerialName("actual_cash_minor") val actualCashMinor: Long,
    @SerialName("actual_upi_minor") val actualUpiMinor: Long,
    val reason: String,
    @SerialName("evidence_reference") val evidenceReference: String? = null,
)

@Serializable
data class PaymentTenderPreview(
    @SerialName("payment_id") val paymentId: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("invoice_no") val invoiceNo: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("business_timezone") val businessTimezone: String,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("original_method") val originalMethod: String,
    @SerialName("effective_method") val effectiveMethod: String,
    @SerialName("before_cash_minor") val beforeCashMinor: Long,
    @SerialName("before_upi_minor") val beforeUpiMinor: Long,
    @SerialName("after_cash_minor") val afterCashMinor: Long,
    @SerialName("after_upi_minor") val afterUpiMinor: Long,
    @SerialName("total_change_minor") val totalChangeMinor: Long,
    @SerialName("evidence_kind") val evidenceKind: String,
    @SerialName("original_shift_record_unchanged") val originalShiftRecordUnchanged: Boolean,
)

@Serializable
data class DailyTenderPreview(
    @SerialName("branch_id") val branchId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("business_timezone") val businessTimezone: String,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    @SerialName("recorded_cash_minor") val recordedCashMinor: Long,
    @SerialName("recorded_upi_minor") val recordedUpiMinor: Long,
    @SerialName("recorded_total_minor") val recordedTotalMinor: Long,
    @SerialName("attestation_scope") val attestationScope: String,
    @SerialName("unchanged_other_rails") val unchangedOtherRails: Map<String, Long> = emptyMap(),
    @SerialName("recorded_all_rails_total_minor") val recordedAllRailsTotalMinor: Long,
    @SerialName("source_allocation") val sourceAllocation: String,
    @SerialName("physical_cash_count") val physicalCashCount: Boolean,
)

@Serializable
data class TenderReceipt(
    val id: String,
    @SerialName("receipt_reference") val receiptReference: String,
    val operation: String,
    @SerialName("financial_effect") val financialEffect: Boolean,
    @SerialName("company_id") val companyId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("business_timezone") val businessTimezone: String,
    @SerialName("recorded_by") val recordedBy: String,
    @SerialName("recorded_at") val recordedAt: String,
    val reason: String,
    @SerialName("evidence_kind") val evidenceKind: String,
    @SerialName("evidence_reference") val evidenceReference: String? = null,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("source_allocation") val sourceAllocation: String,
    @SerialName("total_difference_minor") val totalDifferenceMinor: Long,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    @SerialName("shift_id") val shiftId: String? = null,
    @SerialName("original_method") val originalMethod: String? = null,
    @SerialName("effective_method") val effectiveMethod: String? = null,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    @SerialName("original_tendered_minor") val originalTenderedMinor: Long? = null,
    @SerialName("original_change_minor") val originalChangeMinor: Long? = null,
    @SerialName("accounting_at") val accountingAt: String? = null,
    val kind: String? = null,
    @SerialName("before_cash_minor") val beforeCashMinor: Long? = null,
    @SerialName("before_upi_minor") val beforeUpiMinor: Long? = null,
    @SerialName("actual_cash_minor") val actualCashMinor: Long? = null,
    @SerialName("actual_upi_minor") val actualUpiMinor: Long? = null,
    @SerialName("cash_delta_minor") val cashDeltaMinor: Long? = null,
    @SerialName("upi_delta_minor") val upiDeltaMinor: Long? = null,
)


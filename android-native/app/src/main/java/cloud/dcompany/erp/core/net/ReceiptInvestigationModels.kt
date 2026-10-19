package cloud.dcompany.erp.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceiptParticipantInterval(
    val id: String,
    @SerialName("gaming_session_id") val gamingSessionId: String,
    @SerialName("participant_identity_id") val participantIdentityId: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("guest_name") val guestName: String? = null,
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("joined_play_elapsed_ms") val joinedPlayElapsedMs: Long,
    @SerialName("join_timing_source") val joinTimingSource: String,
    @SerialName("joined_by") val joinedBy: String,
    @SerialName("left_at") val leftAt: String? = null,
    @SerialName("left_play_elapsed_ms") val leftPlayElapsedMs: Long? = null,
    @SerialName("leave_timing_source") val leaveTimingSource: String? = null,
    @SerialName("left_by") val leftBy: String? = null,
    @SerialName("settlement_line_id") val settlementLineId: String? = null,
    @SerialName("settlement_id") val settlementId: String? = null,
    @SerialName("charge_basis") val chargeBasis: String,
)

@Serializable
data class ReceiptParticipantCharge(
    val id: String,
    @SerialName("gaming_session_id") val gamingSessionId: String,
    @SerialName("participant_identity_id") val participantIdentityId: String,
    @SerialName("settlement_id") val settlementId: String,
    @SerialName("play_elapsed_ms") val playElapsedMs: Long,
    @SerialName("interval_count") val intervalCount: Int,
    @SerialName("charge_minor") val chargeMinor: Long,
    val basis: String,
)

@Serializable
data class ReceiptAddonHistory(
    val id: String,
    @SerialName("gaming_session_id") val gamingSessionId: String,
    @SerialName("client_line_id") val clientLineId: String,
    @SerialName("menu_item_id") val menuItemId: String,
    @SerialName("menu_item_name_snapshot") val menuItemNameSnapshot: String,
    val qty: Int,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("line_total_minor") val lineTotalMinor: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("voided_by") val voidedBy: String? = null,
    @SerialName("void_reason") val voidReason: String? = null,
    val provenance: String,
)

@Serializable
data class ReceiptInvestigation(
    val receipt: CanonicalReceipt,
    @SerialName("participant_intervals") val participantIntervals: List<ReceiptParticipantInterval>,
    @SerialName("participant_charges") val participantCharges: List<ReceiptParticipantCharge>,
    val addons: List<ReceiptAddonHistory>,
    @SerialName("audit_source_references") val auditSourceReferences: List<String>,
    @SerialName("historical_evidence_note") val historicalEvidenceNote: String,
    @SerialName("invoice_date_basis") val invoiceDateBasis: String,
    @SerialName("shop_timezone") val shopTimezone: String? = null,
)


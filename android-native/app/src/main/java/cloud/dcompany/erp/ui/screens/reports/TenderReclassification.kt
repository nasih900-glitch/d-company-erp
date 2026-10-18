package cloud.dcompany.erp.ui.screens.reports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TenderReclassification(
    val id: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("business_timezone") val businessTimezone: String,
    val kind: String,
    @SerialName("cash_delta_minor") val cashDeltaMinor: Long,
    @SerialName("upi_delta_minor") val upiDeltaMinor: Long,
    @SerialName("total_difference_minor") val totalDifferenceMinor: Long,
    val basis: String,
    @SerialName("recorded_at") val recordedAt: String,
)


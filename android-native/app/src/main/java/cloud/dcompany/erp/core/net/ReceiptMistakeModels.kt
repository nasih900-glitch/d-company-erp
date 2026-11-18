package cloud.dcompany.erp.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReceiptMistakeReason(val label: String) {
    @SerialName("wrong_item") WrongItem("Wrong item"),
    @SerialName("wrong_payment_method") WrongPaymentMethod("Wrong payment method"),
    @SerialName("no_funds_received") NoFundsReceived("Payment recorded but no money received"),
    @SerialName("other") Other("Other"),
}

@Serializable
data class ReceiptMistakeRequest(val reason: ReceiptMistakeReason, val note: String? = null) {
    override fun toString() = "ReceiptMistakeRequest(reason=$reason, note=<redacted>)"
}

@Serializable
data class ReceiptMistakeAcknowledgement(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ReceiptMistakeReport(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("invoice_no") val invoiceNo: String,
    @SerialName("reporter_name") val reporterName: String,
    val reason: ReceiptMistakeReason,
    val note: String? = null,
)

@Serializable
data class ReceiptMistakeReportPage(
    val items: List<ReceiptMistakeReport>,
    @SerialName("next_before") val nextBefore: String? = null,
)

package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.PaymentBundleResult

/** Fail-closed response gate before a durable split-payment row becomes synced. */
fun requireAuthoritativeSplitSettlement(
    row: LocalHeldOrderPaymentEntity,
    plan: SplitPaymentPlan,
    result: PaymentBundleResult,
    finalOrder: Order,
) {
    check(row.amountMinor == row.expectedDueMinor && row.amountMinor == plan.totalMinor) {
        "The saved split-payment total does not match its verified bill balance."
    }
    check(result.orderId == row.targetOrderId && finalOrder.id == row.targetOrderId) {
        "The split-payment response belongs to a different order."
    }
    check(row.shiftId != null && result.shiftId == row.shiftId) {
        "The split-payment response belongs to a different shift."
    }
    check(result.totalAmountMinor == row.amountMinor) {
        "The split-payment response total does not match the saved settlement."
    }
    check(result.orderStatus == "paid" && finalOrder.status == "paid") {
        "The split-payment order was not authoritatively finalized as paid."
    }
    check(
        finalOrder.totalMinor == row.expectedTotalMinor &&
            finalOrder.paidMinor == finalOrder.totalMinor &&
            finalOrder.dueMinor == 0L
    ) {
        "The finalized order balance does not match the verified split-payment bill."
    }
    val invoiceNo = result.invoiceNo?.trim().orEmpty()
    val fiscalYear = result.fiscalYear?.trim().orEmpty()
    val invoiceIssuedAt = result.invoiceIssuedAt?.trim().orEmpty()
    check(
        invoiceNo.isNotEmpty() &&
            fiscalYear.isNotEmpty() &&
            invoiceIssuedAt.isNotEmpty() &&
            finalOrder.invoiceNo?.trim() == invoiceNo &&
            finalOrder.fiscalYear?.trim() == fiscalYear &&
            finalOrder.invoiceIssuedAt?.trim() == invoiceIssuedAt
    ) {
        "The split-payment invoice identity is incomplete or inconsistent."
    }

    check(result.payments.size == plan.legs.size) {
        "The split-payment response has an unexpected number of tender rails."
    }
    check(result.payments.map { it.id }.all(String::isNotBlank)) {
        "The split-payment response contains a payment without an identity."
    }
    check(result.payments.map { it.id }.distinct().size == result.payments.size) {
        "The split-payment response contains duplicate payment identities."
    }
    val expectedMethods = plan.legs.map(SplitPaymentLeg::method).toSet()
    val actualMethods = result.payments.map { it.method }
    check(
        actualMethods.distinct().size == actualMethods.size &&
            actualMethods.toSet() == expectedMethods
    ) {
        "The split-payment response contains duplicate or missing tender rails."
    }
    val paidAtValues = result.payments.map { it.paidAt.trim() }
    check(
        paidAtValues.all(String::isNotEmpty) &&
            paidAtValues.distinct() == listOf(invoiceIssuedAt)
    ) {
        "The split-payment legs do not share the authoritative invoice settlement time."
    }
    val expectedLegs = plan.legs.associateBy { it.method }
    result.payments.forEach { actual ->
        val expected = expectedLegs[actual.method]
            ?: error("The server returned an unexpected split-payment rail.")
        check(actual.amountMinor == expected.amountMinor)
        check(actual.tenderedMinor == expected.tenderedMinor)
        val expectedChange = expected.tenderedMinor?.minus(expected.amountMinor)
        check(actual.changeMinor == expectedChange)
    }

    check(result.paymentBreakdownMinor.keys == SplitPaymentPolicy.supportedMethods.toSet()) {
        "The split-payment accounting breakdown has unsupported or missing rails."
    }
    check(result.paymentBreakdownMinor.values.all { it >= 0L }) {
        "The split-payment accounting breakdown contains a negative amount."
    }
    val accounted = result.paymentBreakdownMinor.values.fold(0L, Math::addExact)
    check(accounted == finalOrder.totalMinor) {
        "The split-payment accounting breakdown does not match the invoice total."
    }
    plan.legs.forEach { leg ->
        check(requireNotNull(result.paymentBreakdownMinor[leg.method]) >= leg.amountMinor) {
            "The split-payment accounting breakdown omits a confirmed tender amount."
        }
    }
}

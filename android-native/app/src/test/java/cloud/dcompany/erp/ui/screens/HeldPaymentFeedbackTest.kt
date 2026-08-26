package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldPaymentFeedbackTest {

    @Test
    fun pendingPaymentNeverClaimsServerConfirmation() {
        assertNull(heldPaymentOutcomeNotice(payment(HeldOrderPaymentState.PENDING)))
        val duplicate = duplicateHeldPaymentNotice(payment(HeldOrderPaymentState.PENDING))
        assertTrue(duplicate.contains("awaiting server confirmation"))
        assertTrue(duplicate.contains("Do not collect again"))
    }

    @Test
    fun syncedPaymentReportsOnlyTheServerConfirmedAmount() {
        val row = payment(HeldOrderPaymentState.SYNCED)
        val message = heldPaymentOutcomeNotice(row)!!

        assertTrue(message.contains("confirmed by the server"))
        assertTrue(message.contains("₹125.00"))
        assertTrue(message.contains("No further collection"))
        assertTrue(duplicateHeldPaymentNotice(row).contains("already confirmed by the server"))
    }

    @Test
    fun rejectedPaymentKeepsDoubleCollectionWarningAndSafeRecoveryAction() {
        val row = payment(
            HeldOrderPaymentState.REJECTED,
            error = "Checkout total changed",
        )
        val outcome = heldPaymentOutcomeNotice(row)!!
        val duplicate = duplicateHeldPaymentNotice(row)

        assertTrue(outcome.contains("Checkout total changed"))
        assertTrue(outcome.contains("Do not collect again"))
        assertTrue(outcome.contains("Retry after fix"))
        assertTrue(duplicate.contains("manager reconciliation"))
        assertTrue(duplicate.contains("Do not collect again"))
    }

    @Test
    fun missingDuplicateRowStillFailsClosedAgainstASecondCollection() {
        val message = duplicateHeldPaymentNotice(null)
        assertTrue(message.contains("already saved"))
        assertTrue(message.contains("Do not collect again"))
    }

    private fun payment(state: String, error: String? = null) = LocalHeldOrderPaymentEntity(
        localId = "payment-1",
        targetOrderId = "order-1",
        method = "cash",
        amountMinor = 12_500,
        tenderedMinor = 15_000,
        expectedTotalMinor = 12_500,
        expectedDueMinor = 12_500,
        claimToken = null,
        claimExpiresAtMillis = null,
        claimOrderVersion = 7,
        createdAtMillis = 1_000,
        syncState = state,
        lastError = error,
    )
}

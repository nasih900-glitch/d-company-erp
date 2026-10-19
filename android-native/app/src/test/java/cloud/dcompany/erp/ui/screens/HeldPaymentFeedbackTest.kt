package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.checkout.SplitPaymentLeg
import cloud.dcompany.erp.core.checkout.SplitPaymentPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldPaymentFeedbackTest {

    @Test
    fun newHeldCheckoutClearsOnlyTheExactOwnedSuccessNotice() {
        val notice = MutableStateFlow<String?>(null)
        val owner = PosSuccessfulOutcomeNoticeOwner()
        val success = "Held-order payment confirmed by the server. Total ₹30.00. " +
            "No further collection is needed."

        owner.publish(owner.register("held-payment:old"), success, notice)
        owner.beginCheckout(notice)

        assertNull(notice.value)
    }

    @Test
    fun newHeldCheckoutDoesNotClearARefusalThatReplacedTheOwnedSuccess() {
        val notice = MutableStateFlow<String?>(null)
        val owner = PosSuccessfulOutcomeNoticeOwner()
        val success = "Held-order payment confirmed by the server. Total ₹30.00. " +
            "No further collection is needed."
        val refusal = "Held-order payment saved, but the server refused it. Do not collect again."
        val ticket = owner.register("held-payment:old")

        owner.publish(ticket, success, notice)
        notice.value = refusal
        owner.publish(ticket, success, notice)
        assertEquals(refusal, notice.value)
        owner.beginCheckout(notice)

        assertEquals(refusal, notice.value)
    }

    @Test
    fun lateOldOutcomePublishesARefusalButNotSuccessOverTheNewCheckout() {
        val notice = MutableStateFlow<String?>(null)
        val owner = PosSuccessfulOutcomeNoticeOwner()
        val oldTicket = owner.register("held-payment:old")
        owner.beginCheckout(notice)

        publishHeldPaymentOutcomeFeedback(
            outcome = payment(HeldOrderPaymentState.SYNCED),
            ticket = oldTicket,
            owner = owner,
            notice = notice,
        )
        assertNull(notice.value)

        publishHeldPaymentOutcomeFeedback(
            outcome = payment(
                HeldOrderPaymentState.REJECTED,
                error = "Checkout total changed",
            ),
            ticket = oldTicket,
            owner = owner,
            notice = notice,
        )
        assertTrue(notice.value.orEmpty().contains("Checkout total changed"))
        assertTrue(notice.value.orEmpty().contains("Do not collect again"))
    }

    @Test
    fun retryRegisteredDuringDirectPrepareLagCannotPublishSuccessAfterCheckoutFinishes() {
        val notice = MutableStateFlow<String?>(null)
        val owner = PosSuccessfulOutcomeNoticeOwner()
        // The durable prepare boundary is authoritative even before Room has
        // emitted the checkout into rendered state.
        val checkoutActiveDuringRegistration = hasActiveCheckoutForOutcomeNotice(
            heldCheckoutPrepared = false,
            directCheckoutPhaseLocalId = "new-checkout",
            directCheckoutRendered = false,
        )
        val oldTicket = owner.register(
            "direct-sale:old",
            successEligible = !checkoutActiveDuringRegistration,
        )

        // Closing that checkout later cannot retroactively make this retry's
        // success eligible to cover the next user context.
        assertTrue(checkoutActiveDuringRegistration)

        publishDirectSaleOutcomeFeedback(
            outcome = directOrder(SyncState.SYNCED),
            ticket = oldTicket,
            owner = owner,
            notice = notice,
        )
        assertNull(notice.value)

        publishDirectSaleOutcomeFeedback(
            outcome = directOrder(SyncState.REJECTED, "Shift was closed"),
            ticket = oldTicket,
            owner = owner,
            notice = notice,
        )
        assertTrue(notice.value.orEmpty().contains("Shift was closed"))
        assertTrue(notice.value.orEmpty().contains("Do not collect payment again"))
    }

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

    @Test
    fun splitMethodEnvelopeHasSafeReadableLabelsInPendingAndRecoveryRows() {
        val stored = SplitPaymentPolicy.encode(
            SplitPaymentPolicy.create(
                12_500,
                listOf(
                    SplitPaymentLeg("cash", 5_000, 10_000),
                    SplitPaymentLeg("upi", 7_500),
                ),
            ),
        )

        assertEquals("Split · Cash ₹50 + UPI ₹75", stored.paymentMethodLabel())
        assertEquals("Split payment", "split:v99|private-corrupt-data".paymentMethodLabel())
        val rejected = payment(HeldOrderPaymentState.REJECTED, "Network receipt mismatch")
            .copy(method = stored)
        assertTrue(heldPaymentOutcomeNotice(rejected).orEmpty().contains("Do not collect again"))
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

    private fun directOrder(state: String, error: String? = null) = LocalOrderEntity(
        localId = "sale-1",
        serverOrderId = "server-order-1",
        shiftId = "shift-1",
        type = "takeaway",
        estimateMinor = 3_000,
        serverTotalMinor = 3_000,
        paymentMethod = "cash",
        tenderedMinor = 5_000,
        createdAtMillis = 1_000,
        syncState = state,
        lastError = error,
    )
}

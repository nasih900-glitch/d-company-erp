package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosCheckoutPreparationFeedbackTest {

    @Test
    fun genericServerFailureNamesSafeRecoveryAndNoPaymentOutcome() {
        val message = directCheckoutPreparationNotice(
            ApiException("An unexpected error occurred.", status = 500),
        )

        assertFalse(message.contains("unexpected error", ignoreCase = true))
        assertTrue(message.contains("No payment was recorded"))
        assertTrue(message.contains("Try again"))
        assertTrue(message.contains("manager"))
    }

    @Test
    fun specificBusinessRuleRemainsActionable() {
        val message = directCheckoutPreparationNotice(
            ApiException(
                "This shift belongs to another terminal.",
                status = 409,
                code = "business_rule",
            ),
        )

        assertEquals("This shift belongs to another terminal.", message)
    }

    @Test
    fun localFailureStillExplainsWhatMustBeCorrected() {
        assertEquals(
            "The open shift changed while this order was being prepared.",
            directCheckoutPreparationNotice(
                IllegalStateException(
                    "The open shift changed while this order was being prepared.",
                ),
            ),
        )
    }
}

package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.Order
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundWireContractTest {

    @Test
    fun `order list retains authoritative refundable balance reservation and rails`() {
        val order = ApiClient.json.decodeFromString<Order>(
            """
            {
              "id":"order-1","invoice_no":"INV-1","status":"paid","type":"pos",
              "total_minor":25000,"paid_minor":25000,"refundable_minor":12500,
              "pending_refund_minor":5000,"payment_methods":["cash","upi"]
            }
            """.trimIndent(),
        )

        assertEquals(25_000L, order.paidMinor)
        assertEquals(12_500L, order.refundableMinor)
        assertEquals(5_000L, order.pendingRefundMinor)
        assertEquals(listOf("cash", "upi"), order.paymentMethods)
    }

    @Test
    fun `required refund safety confirmations are present on the wire`() {
        val providerStart = ApiClient.json.encodeToString(
            PosRefundProviderPayoutStartBody(
                shiftId = "shift-1",
                expectedAmountMinor = 17_800,
                readyToStartProviderPayout = true,
            ),
        )
        val cashStart = ApiClient.json.encodeToString(
            PosRefundHandoffBody(
                shiftId = "shift-1",
                expectedAmountMinor = 17_800,
                readyToHandover = true,
            ),
        )
        val providerComplete = ApiClient.json.encodeToString(
            PosRefundProviderSettlementBody(
                shiftId = "shift-1",
                expectedAmountMinor = 17_800,
                providerCompleted = true,
                externalReference = "provider-ref",
                providerSettledAt = "2026-08-27T20:50:00Z",
            ),
        )
        val cashComplete = ApiClient.json.encodeToString(
            PosRefundCashSettlementBody(
                shiftId = "shift-1",
                expectedAmountMinor = 17_800,
                cashHandedOver = true,
                settledAt = "2026-08-27T20:50:00Z",
            ),
        )

        assertTrue(providerStart.contains("\"ready_to_start_provider_payout\":true"))
        assertTrue(cashStart.contains("\"ready_to_handover\":true"))
        assertTrue(providerComplete.contains("\"provider_completed\":true"))
        assertTrue(cashComplete.contains("\"cash_handed_over\":true"))
    }

    @Test
    fun `required recovery confirmations are present on the wire`() {
        val cashRecovery = ApiClient.json.encodeToString(
            PosRefundCashHandoffResolutionBody(
                shiftId = "shift-1",
                expectedAmountMinor = 17_800,
                cashNotHandedOver = true,
                drawerUnchanged = true,
                reason = "Drawer checked and unchanged",
                resolvedAt = "2026-08-27T20:50:00Z",
            ),
        )
        val providerRecovery = ApiClient.json.encodeToString(
            PosRefundProviderPayoutResolutionBody(
                shiftId = "shift-1",
                expectedAmountMinor = 17_800,
                providerNotCompleted = true,
                providerStatus = "provider_declined",
                verificationReference = "provider-check-ref",
                providerCheckedAt = "2026-08-27T20:50:00Z",
                reason = "Provider confirms no payout",
            ),
        )

        assertTrue(cashRecovery.contains("\"cash_not_handed_over\":true"))
        assertTrue(cashRecovery.contains("\"drawer_unchanged\":true"))
        assertTrue(providerRecovery.contains("\"provider_not_completed\":true"))
        assertTrue(providerRecovery.contains("\"provider_status\":\"provider_declined\""))
        assertTrue(providerRecovery.contains("\"verification_reference\":\"provider-check-ref\""))
        assertTrue(providerRecovery.contains("\"provider_checked_at\":\"2026-08-27T20:50:00Z\""))
    }

    @Test
    fun `refund response retains actors and reconciliation evidence`() {
        val result = ApiClient.json.decodeFromString<PosRefundRequestResult>(
            """
            {
              "id":"request-1","order_id":"order-1","shift_id":"shift-1",
              "branch_id":"branch-1","terminal_id":"terminal-1","amount_minor":17800,
              "reason_code":"billing_error","mode":"original","settlement_method":"upi",
              "status":"settled","accepted_at":"2026-08-27T20:00:00Z",
              "accepted_by":"user-1","accepted_by_name":"Rafi",
              "provider_payout_started_at":"2026-08-27T20:05:00Z",
              "provider_payout_started_by":"user-2","provider_payout_started_by_name":"Nasih",
              "provider_completed_at":"2026-08-27T20:06:00Z",
              "provider_completed_by":"user-2","provider_completed_by_name":"Nasih",
              "settled_at":"2026-08-27T20:06:02Z","settled_by":"user-2",
              "settled_by_name":"Nasih","captured_time_reconciled":true,
              "provider_evidence_reconciled":false,"customer_spend_reconciled":true,
              "loyalty_reconciliation_state":"applied","client_action_id":"refund-action-1"
            }
            """.trimIndent(),
        )

        assertEquals("Rafi", result.acceptedByName)
        assertEquals("Nasih", result.providerPayoutStartedByName)
        assertEquals("Nasih", result.providerCompletedByName)
        assertEquals("Nasih", result.settledByName)
        assertEquals("applied", result.loyaltyReconciliationState)
        assertTrue(result.capturedTimeReconciled == true)
        assertFalse(result.providerEvidenceReconciled == true)
    }
}

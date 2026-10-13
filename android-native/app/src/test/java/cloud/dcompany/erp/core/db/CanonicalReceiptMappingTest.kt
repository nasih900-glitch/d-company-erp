package cloud.dcompany.erp.core.db

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CanonicalReceipt
import cloud.dcompany.erp.ui.screens.matchesCachedReceiptQuery
import cloud.dcompany.erp.ui.screens.ReceiptSearchSource
import cloud.dcompany.erp.ui.screens.ReceiptSearchUiState
import cloud.dcompany.erp.ui.screens.withRefreshedDetail
import cloud.dcompany.erp.ui.screens.refunds.toExactRefundOrder
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalReceiptMappingTest {
    @Test
    fun `canonical payload keeps exact line payment refund actor and gaming provenance`() {
        val receipt = ApiClient.json.decodeFromString<CanonicalReceipt>(receiptJson())

        val cached = receipt.toCacheEntity(fetchedAtMillis = 2_000)
        val restored = cached.decodedReceipt()

        assertNotNull(restored)
        assertEquals("1.250", restored!!.lines.single().qty)
        assertEquals("Sameer", restored.lines.single().voidedByName)
        assertEquals("Rafi", restored.payments.single().recordedByName)
        assertEquals(2_000L, restored.refundedMinor)
        assertEquals(1_000L, restored.pendingRefundMinor)
        assertEquals(11_500L, restored.refundableMinor)
        assertEquals(12L, restored.checkoutVersion)
        assertEquals(12_500L, restored.netCollectedMinor)
        assertEquals("Sameer", restored.refunds.single().approvedByName)
        assertEquals("PS5 Station 1", restored.gamingSessions.single().stationName)
        assertEquals("Nasih", restored.gamingSessions.single().sentToPosByName)
        assertEquals(2_000L, cached.fetchedAtMillis)

        val exactRefundOrder = restored.toExactRefundOrder()
        assertEquals(restored.orderId, exactRefundOrder.id)
        assertEquals(1_000L, exactRefundOrder.pendingRefundMinor)
        assertEquals(11_500L, exactRefundOrder.refundableMinor)
        assertEquals(12L, exactRefundOrder.checkoutVersion)
        assertEquals(listOf("upi"), exactRefundOrder.paymentMethods)
    }

    @Test
    fun `invalid canonical receipt time is rejected instead of corrupting sort order`() {
        val valid = ApiClient.json.decodeFromString<CanonicalReceipt>(receiptJson())

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(invoiceIssuedAt = "not-a-time").toCacheEntity()
        }
    }

    @Test
    fun `bounded offline receipt lookup uses exact identifiers and numeric suffix`() {
        val receipt = ApiClient.json.decodeFromString<CanonicalReceipt>(receiptJson())

        assertTrue(receipt.matchesCachedReceiptQuery("INV-1"))
        assertTrue(receipt.matchesCachedReceiptQuery("1"))
        assertTrue(receipt.matchesCachedReceiptQuery("order-1"))
        assertTrue(receipt.matchesCachedReceiptQuery("session-1"))
        assertFalse(receipt.matchesCachedReceiptQuery("01"))
        assertFalse(receipt.matchesCachedReceiptQuery("INV"))
    }

    @Test
    fun `exact refresh replaces the searched row without changing search scope`() {
        val original = ApiClient.json.decodeFromString<CanonicalReceipt>(receiptJson())
        val refreshed = original.copy(
            checkoutVersion = 13,
            pendingRefundMinor = 2_000,
            refundableMinor = 10_500,
        )
        val state = ReceiptSearchUiState(
            query = "00001",
            receipts = listOf(original),
            nextCursor = "scoped-cursor",
            hasMore = true,
            source = ReceiptSearchSource.ONLINE,
            detailLoading = true,
        )

        val result = state.withRefreshedDetail(refreshed)

        assertEquals("00001", result.query)
        assertEquals("scoped-cursor", result.nextCursor)
        assertTrue(result.hasMore)
        assertEquals(13L, result.receipts.single().checkoutVersion)
        assertEquals(refreshed, result.detail)
        assertFalse(result.detailLoading)
        assertEquals(null, result.detailError)
    }

    private fun receiptJson() =
        """
        {
          "order_id":"order-1","company_id":"company-1","branch_id":"branch-1",
          "terminal_id":"terminal-1","shift_id":"shift-1",
          "shift_opened_by":"user-3","shift_opened_by_name":"Rafi",
          "shift_opened_at":"2026-08-29T08:00:00Z","shift_closed_at":null,
          "opened_by":"user-3","opened_by_name":"Rafi","invoice_no":"INV-1",
          "fiscal_year":"2026-27","checkout_version":12,"status":"refunded","order_type":"gaming",
          "table_id":null,"subtotal_minor":15000,"discount_minor":500,
          "manual_discount_minor":500,"points_redeemed_minor":0,"cgst_minor":0,
          "sgst_minor":0,"igst_minor":0,"cess_minor":0,"tax_minor":0,
          "round_off_minor":0,"tip_minor":0,"total_minor":14500,"paid_minor":14500,
          "refunded_minor":2000,"pending_refund_minor":1000,"refundable_minor":11500,
          "net_collected_minor":12500,"customer_name":null,
          "customer_phone":null,"customer_gstin":null,"customer_address":null,
          "customer_state_code":null,"place_of_supply_state_code":null,
          "is_reverse_charge":false,"irn":null,"irn_ack_no":null,
          "irn_acknowledged_at":null,"e_invoice_qr":null,"notes":null,
          "opened_at":"2026-08-29T09:00:00Z","held_at":"2026-08-29T10:00:00Z",
          "closed_at":"2026-08-29T10:01:00Z","invoice_issued_at":"2026-08-29T10:01:00Z",
          "created_at":"2026-08-29T09:00:00Z","updated_at":"2026-08-29T10:02:00Z",
          "lines":[{
            "id":"line-1","menu_item_id":"item-1","menu_item_name":"Gaming time",
            "menu_item_type":"gaming","variant_id":null,"variant_snapshot":null,
            "modifiers":[],"qty":"1.250","unit_price_minor":14500,
            "line_total_minor":14500,"discount_minor":0,"hsn_or_sac":null,
            "tax_rate":"0.0000","taxable_value_minor":14500,"cgst_minor":0,
            "sgst_minor":0,"igst_minor":0,"cess_minor":0,"note":null,
            "voided_at":"2026-08-29T09:59:00Z","voided_by":"user-4",
            "voided_by_name":"Sameer","void_reason":"mistake",
            "created_at":"2026-08-29T10:00:00Z","updated_at":"2026-08-29T10:00:00Z"
          }],
          "payments":[{
            "id":"payment-1","shift_id":"shift-1","method":"upi","amount_minor":14500,
            "tendered_minor":null,"change_minor":null,"reference":"UPI-1",
            "paid_at":"2026-08-29T10:01:00Z","recorded_by":"user-3",
            "recorded_by_name":"Rafi","created_at":"2026-08-29T10:01:00Z"
          }],
          "refunds":[{
            "id":"refund-1","request_id":"request-1","company_id":"company-1",
            "branch_id":"branch-1","terminal_id":"terminal-1","settlement_shift_id":"shift-1",
            "approved_by":"user-4","approved_by_name":"Sameer",
            "manager_override_user_id":null,"manager_override_user_name":null,
            "reason_code":"customer_request","amount_minor":2000,"mode":"ordinary",
            "settlement_method":"upi","settled_at":"2026-08-29T10:02:00Z",
            "settled_by":"user-4","settled_by_name":"Sameer","external_reference":"REF-1",
            "provider_settled_at":null,"client_occurred_at":null,
            "captured_time_reconciled":null,"provider_evidence_reconciled":null,
            "settlement_idempotency_key":"key-1","receipt_no":"CRN-1",
            "receipt_fiscal_year":"2026-27","receipt_issued_at":"2026-08-29T10:02:00Z",
            "customer_spend_reconciled":true,"loyalty_reconciliation_state":"not_applicable",
            "note":null,"created_at":"2026-08-29T10:02:00Z","updated_at":"2026-08-29T10:02:00Z"
          }],
          "gaming_sessions":[{
            "id":"session-1","station_id":"station-1","station_code":"PS5-01",
            "station_name":"PS5 Station 1","station_type":"ps5","source_shift_id":"shift-1",
            "started_by":"user-3","started_by_name":"Rafi","stopped_by":"user-4",
            "stopped_by_name":"Sameer","sent_to_pos_by":"user-5","sent_to_pos_by_name":"Nasih",
            "started_at":"2026-08-29T09:00:00Z","stopped_at":"2026-08-29T10:00:00Z",
            "sent_to_pos_at":"2026-08-29T10:00:10Z","billing_mode":"open_ended",
            "rate_per_hour_minor":15000,"package_id":null,"package_price_minor_snapshot":null,
            "package_duration_minutes_snapshot":null,"package_variant_snapshot":null,
            "timer_minutes":null,"paused_minutes":0,"billable_minutes":60,"amount_minor":14500,
            "created_at":"2026-08-29T09:00:00Z","updated_at":"2026-08-29T10:00:10Z"
          }]
        }
        """.trimIndent()
}

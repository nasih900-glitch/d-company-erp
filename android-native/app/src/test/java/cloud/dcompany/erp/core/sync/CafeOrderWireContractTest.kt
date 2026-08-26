package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.tables.OrderLineBody
import cloud.dcompany.erp.ui.screens.tables.OrderLinesAppendBody
import cloud.dcompany.erp.ui.screens.tables.TableOrder
import cloud.dcompany.erp.ui.screens.tables.TableOrderCreateBody
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CafeOrderWireContractTest {

    @Test
    fun `round request uses stable client ids notes shift table and checkout version`() {
        val create = ApiClient.json.encodeToString(
            TableOrderCreateBody(
                type = "dine_in",
                shiftId = "shift-1",
                tableId = "table-1",
                lines = listOf(OrderLineBody("client-line-1", "menu-1", 2, "No salt")),
            ),
        )
        val append = ApiClient.json.encodeToString(
            OrderLinesAppendBody(
                expectedCheckoutVersion = 7,
                lines = listOf(OrderLineBody("client-line-2", "menu-2", 1, null)),
            ),
        )

        assertTrue(create.contains("\"shift_id\":\"shift-1\""))
        assertTrue(create.contains("\"table_id\":\"table-1\""))
        assertTrue(create.contains("\"client_line_id\":\"client-line-1\""))
        assertTrue(create.contains("\"note\":\"No salt\""))
        assertTrue(append.contains("\"expected_checkout_version\":7"))
        assertTrue(append.contains("\"client_line_id\":\"client-line-2\""))
        assertFalse(append.contains("\"note\""))
    }

    @Test
    fun `active bill response decodes rounds void evidence and kitchen acknowledgement`() {
        val decoded = ApiClient.json.decodeFromString<TableOrder>(
            """
            {
              "id":"order-1","status":"open","type":"dine_in","table_id":"table-1",
              "subtotal_minor":1250,"tax_minor":0,"total_minor":1250,
              "opened_at":"2026-08-26T10:00:00Z","checkout_version":8,
              "lines":[{
                "id":"line-1","client_line_id":"client-1","menu_item_id":"menu-1",
                "name":"Coffee","qty":1.0,"unit_price_minor":1000,"line_total_minor":1000,
                "note":"No sugar","kitchen_status":"cooking",
                "kitchen_released_at":"2026-08-26T10:00:01Z","kitchen_round_no":1
              }],
              "voided_lines":[{
                "id":"line-2","client_line_id":"client-2","menu_item_id":"menu-2",
                "name":"Fries","qty":1.0,"unit_price_minor":250,"line_total_minor":250,
                "kitchen_status":"queued","kitchen_released_at":"2026-08-26T10:02:00Z",
                "kitchen_round_no":2,"voided_at":"2026-08-26T10:03:00Z",
                "void_reason":"Guest changed order",
                "kitchen_void_acknowledged_at":"2026-08-26T10:04:00Z"
              }]
            }
            """.trimIndent(),
        )

        assertEquals(8L, decoded.checkoutVersion)
        assertEquals(1, decoded.lines.single().kitchenRoundNo)
        assertEquals("No sugar", decoded.lines.single().note)
        assertEquals("Guest changed order", decoded.voidedLines.single().voidReason)
        assertEquals(
            "2026-08-26T10:04:00Z",
            decoded.voidedLines.single().kitchenVoidAcknowledgedAt,
        )
    }

    @Test
    fun `sync failure copy distinguishes conflict ambiguity and local preparation failure`() {
        val conflict = plainCafeSyncFailure(ApiException("checkout version mismatch", 409))
        val ambiguous = plainCafeSyncFailure(ApiException("gateway timeout", 504))
        val local = plainCafeSyncFailure(IllegalStateException("missing confirmed version"))
        val preparation = plainCafeSyncFailure(
            CafeActionPreparationException("The saved action has no checkout version."),
        )

        assertTrue(conflict.contains("changed on another device"))
        assertTrue(conflict.contains("retry or discard"))
        assertTrue(ambiguous.contains("retry automatically"))
        assertTrue(ambiguous.contains("do not repeat"))
        assertTrue(local.contains("remains queued"))
        assertTrue(local.contains("do not repeat"))
        assertEquals("The saved action has no checkout version.", preparation)
    }
}

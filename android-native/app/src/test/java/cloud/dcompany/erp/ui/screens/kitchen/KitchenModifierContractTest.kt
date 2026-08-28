package cloud.dcompany.erp.ui.screens.kitchen

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class KitchenModifierContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `queue contract decodes variant modifiers and note`() {
        val ticket = json.decodeFromString<KitchenOrder>(
            """
            {
              "id": "order-1",
              "invoice_no": "D/MN/26-27/00006",
              "type": "dine_in",
              "kitchen_state": "received",
              "lines": [{
                "id": "line-1",
                "menu_item_id": "item-1",
                "name": "Cappuccino",
                "type": "drink",
                "qty": 1.0,
                "variant_snapshot": {
                  "variant_id": "variant-large",
                  "name": "Large",
                  "price_delta_minor": 3000,
                  "line_delta_minor": 3000
                },
                "modifiers": [{
                  "modifier_id": "oat",
                  "modifier_group_id": "milk",
                  "group_name": "Milk choice",
                  "name": "Oat milk",
                  "qty": 1,
                  "price_delta_minor": 2000,
                  "per_item_delta_minor": 2000,
                  "line_delta_minor": 2000
                }],
                "notes": "less hot",
                "released_at": "2026-08-27T10:00:00Z",
                "round_no": 1
              }]
            }
            """.trimIndent(),
        )

        val line = ticket.lines.single()
        assertEquals("Large", line.variantSnapshot?.name)
        assertEquals(listOf("Oat milk"), line.modifiers.map { it.name })
        assertEquals("less hot", line.notes)
        assertEquals(
            listOf("Large", "Oat milk"),
            kitchenOptionLabels(line.variantSnapshot, line.modifiers),
        )
    }
}

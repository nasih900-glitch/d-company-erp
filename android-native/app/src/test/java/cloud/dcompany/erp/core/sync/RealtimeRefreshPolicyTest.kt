package cloud.dcompany.erp.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRefreshPolicyTest {

    @Test
    fun `orders refreshes held orders and table occupancy`() {
        assertEquals(
            listOf("orders", "tables"),
            RealtimeRefreshPolicy.resourcesFor("orders"),
        )
    }

    @Test
    fun `kitchen transition refreshes KDS and authoritative table bill without broad drain`() {
        assertEquals(
            listOf("kitchen", "tables"),
            RealtimeRefreshPolicy.resourcesFor("kitchen"),
        )
        assertFalse(RealtimeRefreshPolicy.requiresBroadOutboxDrain("kitchen"))
    }

    @Test
    fun `gaming refreshes its floor and the held POS queue without tables`() {
        val resources = RealtimeRefreshPolicy.resourcesFor("gaming")

        assertEquals(listOf("gaming", "orders"), resources)
        assertFalse(resources.contains("tables"))
    }

    @Test
    fun `receipt change refreshes canonical history and existing order projections`() {
        assertEquals(
            listOf("receipts", "orders"),
            RealtimeRefreshPolicy.resourcesFor("receipts"),
        )
    }

    @Test
    fun `owner audit invalidation is ignored by Android without waking its outbox`() {
        assertEquals(emptyList<String>(), RealtimeRefreshPolicy.resourcesFor("audit"))
        assertFalse(RealtimeRefreshPolicy.requiresBroadOutboxDrain("audit"))
    }

    @Test
    fun `finance and inventory broadcasts keep their server resource boundary`() {
        assertEquals(listOf("finance"), RealtimeRefreshPolicy.resourcesFor("finance"))
        assertEquals(listOf("inventory"), RealtimeRefreshPolicy.resourcesFor("inventory"))
    }

    @Test
    fun `unrelated resources keep their existing narrow refresh`() {
        assertEquals(
            listOf("settings"),
            RealtimeRefreshPolicy.resourcesFor("settings"),
        )
        assertTrue(RealtimeRefreshPolicy.requiresBroadOutboxDrain("settings"))
    }
}

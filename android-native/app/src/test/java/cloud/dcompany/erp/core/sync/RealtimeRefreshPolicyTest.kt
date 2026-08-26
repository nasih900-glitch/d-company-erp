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
    fun `unrelated resources keep their existing narrow refresh`() {
        assertEquals(
            listOf("inventory"),
            RealtimeRefreshPolicy.resourcesFor("inventory"),
        )
        assertTrue(RealtimeRefreshPolicy.requiresBroadOutboxDrain("inventory"))
    }
}

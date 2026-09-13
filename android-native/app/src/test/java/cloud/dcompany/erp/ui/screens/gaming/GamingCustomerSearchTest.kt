package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.LocalCustomerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingCustomerSearchTest {
    private val cached = CustomerCacheEntity(
        id = "server-1", name = "Amina Rahman", phone = "9876543210", email = null,
        birthday = null, visitCount = 2, totalSpentMinor = 0, loyaltyPoints = 0,
        lifetimeGamingPointsEarned = 0, gamingRank = "Rookie", gamingRankFloor = 0,
        nextGamingRank = null, nextGamingRankFloor = null, pointsToNextGamingRank = null,
        lastVisitAt = null, notes = null,
    )

    @Test
    fun `searches scoped cached projection by name and phone`() {
        val rows = projectGamingCustomers(listOf(cached), emptyList())
        assertEquals("server-1", filterGamingCustomerOptions(rows, "amina").single().customerId)
        assertEquals("server-1", filterGamingCustomerOptions(rows, "76543").single().customerId)
    }

    @Test
    fun `formatted pending create is not selectable until it has a server id`() {
        val pending = LocalCustomerEntity(
            localId = "local-1", phone = "9000000000", name = "Pending guest",
            createdAtMillis = 1,
        )
        val rows = projectGamingCustomers(listOf(cached), listOf(pending))
        assertTrue(rows.none { it.phone == "9000000000" })
    }

    @Test
    fun `pending edit retains its stable server identity and overrides stale cache`() {
        val edit = LocalCustomerEntity(
            localId = "edit-1", serverId = "server-1", name = "Amina Updated",
            createdAtMillis = 1,
        )
        val rows = projectGamingCustomers(listOf(cached), listOf(edit))
        assertEquals(1, rows.size)
        assertEquals("server-1", rows.single().customerId)
        assertEquals("Amina Updated", rows.single().name)
        assertEquals("9876543210", rows.single().phone)
    }

    @Test
    fun `online result outside initial cache becomes searchable without replacing cache`() {
        val outsideFirstPage = cached.copy(
            id = "server-501", name = "Zoya Returning", phone = "9888888888",
        )
        val afterScopedUpsert = projectGamingCustomers(listOf(cached, outsideFirstPage), emptyList())
        assertEquals("server-501", filterGamingCustomerOptions(afterScopedUpsert, "Zoya").single().customerId)
        assertTrue(afterScopedUpsert.any { it.customerId == "server-1" })
    }

    @Test
    fun `offline search stays cache-only and stale online generations cannot commit`() {
        assertNull(onlineGamingCustomerQuery("Amina", online = false))
        assertNull(onlineGamingCustomerQuery("a", online = true))
        assertEquals("Amina", onlineGamingCustomerQuery(" Amina ", online = true))
        assertTrue(!isCurrentGamingCustomerSearch(4, 5))
        assertTrue(isCurrentGamingCustomerSearch(5, 5))
    }
}

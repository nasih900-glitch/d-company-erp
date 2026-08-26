package cloud.dcompany.erp.core.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingLockTest {

    private val ownerA = OutboxOwnerIdentity("user-a", "company", "branch")
    private val ownerB = OutboxOwnerIdentity("user-b", "company", "branch")

    @After
    fun reset() {
        PricingLock.lock()
    }

    @Test
    fun `unlock is usable only by the exact login lineage`() {
        val lineage = Any()
        val session = PricingSessionLease(lineage, ownerA)

        PricingLock.unlock("pricing-a", expiresInSeconds = 60, session = session)

        assertEquals("pricing-a", PricingLock.currentToken(PricingSessionLease(lineage, ownerA)))
        assertTrue(PricingLock.unlocked.value)

        assertNull(PricingLock.currentToken(PricingSessionLease(Any(), ownerA)))
        assertFalse(PricingLock.unlocked.value)
    }

    @Test
    fun `another employee cannot inherit an unlocked pricing token`() {
        val sessionA = PricingSessionLease(Any(), ownerA)
        PricingLock.unlock("pricing-a", expiresInSeconds = 60, session = sessionA)

        assertNull(PricingLock.currentToken(PricingSessionLease(Any(), ownerB)))
        assertNull(PricingLock.currentToken(sessionA))
    }

    @Test
    fun `missing session fails closed and clears the indicator`() {
        PricingLock.unlock(
            token = "pricing-a",
            expiresInSeconds = 60,
            session = PricingSessionLease(Any(), ownerA),
        )

        assertNull(PricingLock.currentToken(null))
        assertFalse(PricingLock.unlocked.value)
    }
}

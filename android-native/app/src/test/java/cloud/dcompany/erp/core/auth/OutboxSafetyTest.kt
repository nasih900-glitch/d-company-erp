package cloud.dcompany.erp.core.auth

import java.util.Base64
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxSafetyTest {

    private val ownerA = OutboxOwnerIdentity("user-a", "company-1", "branch-1")
    private val ownerB = OutboxOwnerIdentity("user-b", "company-1", "branch-1")

    @Test
    fun `clean outbox permits a different account`() {
        val result = OutboxOwnershipPolicy.decide(
            ownerA,
            ownerB,
            unresolvedCount = 0,
            purpose = OutboxOwnershipPolicy.Purpose.SIGN_IN,
        )

        assertTrue(result is OutboxGateResult.Allowed)
    }

    @Test
    fun `sync pauses during even a clean owner transition`() {
        val result = OutboxOwnershipPolicy.decide(
            ownerA,
            ownerB,
            unresolvedCount = 0,
            purpose = OutboxOwnershipPolicy.Purpose.SYNC,
        )

        assertTrue(result is OutboxGateResult.Blocked)
    }

    @Test
    fun `dirty outbox permits only its exact owner`() {
        val same = OutboxOwnershipPolicy.decide(
            ownerA,
            ownerA,
            unresolvedCount = 2,
            purpose = OutboxOwnershipPolicy.Purpose.SIGN_IN,
        )
        val differentUser = OutboxOwnershipPolicy.decide(
            ownerA,
            ownerB,
            unresolvedCount = 2,
            purpose = OutboxOwnershipPolicy.Purpose.SIGN_IN,
        )
        val differentBranch = OutboxOwnershipPolicy.decide(
            ownerA,
            ownerA.copy(branchId = "branch-2"),
            unresolvedCount = 2,
            purpose = OutboxOwnershipPolicy.Purpose.SYNC,
        )

        assertTrue(same is OutboxGateResult.Allowed)
        assertTrue(differentUser is OutboxGateResult.Blocked)
        assertTrue(differentBranch is OutboxGateResult.Blocked)
    }

    @Test
    fun `legacy dirty outbox without an owner is quarantined`() {
        val result = OutboxOwnershipPolicy.decide(
            owner = null,
            candidate = ownerA,
            unresolvedCount = 1,
            purpose = OutboxOwnershipPolicy.Purpose.SIGN_IN,
        )

        assertTrue(result is OutboxGateResult.Blocked)
        assertTrue((result as OutboxGateResult.Blocked).message.contains("cannot be verified"))
    }

    @Test
    fun `access token parser reads stable identity and preserves null branch`() {
        val token = jwt("""{"sub":"user-a","company_id":"company-1","branch_id":null}""")

        val parsed = AccessTokenIdentityParser.parse(token)

        assertEquals("user-a", parsed?.userId)
        assertEquals("company-1", parsed?.companyId)
        assertNull(parsed?.branchId)
    }

    @Test
    fun `access token parser fails closed on malformed token`() {
        assertNull(AccessTokenIdentityParser.parse("not-a-jwt"))
        assertNull(AccessTokenIdentityParser.parse(jwt("""{"sub":"user-a"}""")))
    }

    @Test
    fun `ended gaming session gives business action instead of false sync advice`() {
        val message = signOutBlockedMessage(
            snapshot("gaming_sessions", GamingSessionState.ENDED_UNBILLED),
        )

        assertTrue(message.contains("1 ended gaming session still needs a POS decision"))
        assertTrue(message.contains("Open Gaming"))
        assertTrue(message.contains("Send to POS"))
        assertTrue(message.contains("Cancel / void"))
        assertTrue(!message.contains("unsynced", ignoreCase = true))
        assertTrue(!message.contains("wait for Sync"))
    }

    @Test
    fun `pending and rejected writes retain sync recovery instructions`() {
        val message = signOutBlockedMessage(
            OutboxSnapshot(
                listOf(
                    UnresolvedOutboxGroup("pos_orders", "pending", 2),
                    UnresolvedOutboxGroup("table_orders", "rejected", 1),
                ),
            ),
        )

        assertTrue(message.contains("2 saved changes are waiting for server confirmation"))
        assertTrue(message.contains("1 saved change was rejected and needs recovery"))
        assertTrue(message.contains("Reconnect and wait for Sync to finish"))
        assertTrue(message.contains("retry or correct any rejected work"))
    }

    @Test
    fun `kitchen blockers name the visible recovery actions`() {
        val message = signOutBlockedMessage(
            OutboxSnapshot(
                listOf(
                    UnresolvedOutboxGroup("kitchen_advances", "pending", 2),
                    UnresolvedOutboxGroup("kitchen_advances", "rejected", 1),
                ),
            ),
        )

        assertTrue(message.contains("2 kitchen updates are waiting for server confirmation"))
        assertTrue(message.contains("1 kitchen update needs review"))
        assertTrue(message.contains("Open Kitchen"))
        assertTrue(message.contains("Sync now"))
        assertTrue(message.contains("Check again"))
        assertTrue(message.contains("remove the saved update only after verifying"))
        assertTrue(!message.contains("affected screen"))
    }

    @Test
    fun `kitchen cancellation acknowledgements use the KDS recovery path`() {
        val message = signOutBlockedMessage(
            OutboxSnapshot(
                listOf(
                    UnresolvedOutboxGroup("kitchen_cancellation_acks", "pending", 1),
                    UnresolvedOutboxGroup("kitchen_cancellation_acks", "rejected", 1),
                ),
            ),
        )

        assertTrue(message.contains("1 kitchen update is waiting for server confirmation"))
        assertTrue(message.contains("1 kitchen update needs review"))
        assertTrue(message.contains("Open Kitchen"))
        assertTrue(!message.contains("affected screen"))
    }

    @Test
    fun `mixed blockers include each exact recovery path`() {
        val message = signOutBlockedMessage(
            OutboxSnapshot(
                listOf(
                    UnresolvedOutboxGroup("gaming_sessions", GamingSessionState.ENDED_UNBILLED, 2),
                    UnresolvedOutboxGroup("refunds", RefundState.ACCEPTED_CASH_DUE, 1),
                    UnresolvedOutboxGroup("shifts", "close_pending", 1),
                ),
            ),
        )

        assertTrue(message.contains("2 ended gaming sessions"))
        assertTrue(message.contains("1 cash refund still needs a safe handover decision"))
        assertTrue(message.contains("Open Refunds"))
        assertTrue(message.contains("do not pay twice"))
        assertTrue(message.contains("1 saved change is waiting for server confirmation"))
    }

    private fun snapshot(resource: String, state: String, count: Int = 1) =
        OutboxSnapshot(listOf(UnresolvedOutboxGroup(resource, state, count)))

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("{}", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.encodeToByteArray()) }
    }
}

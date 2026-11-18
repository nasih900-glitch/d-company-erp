package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.net.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.Header
import retrofit2.http.POST

class PosRetainedMistakeContractTest {
    @Test
    fun `dedicated command carries claim stable key exact review and explicit attestation`() {
        val method = ErpApi::class.java.methods.single { it.name == "correctRetainedMistake" }
        assertEquals("pos/orders/{id}/retained-mistake-correction", method.getAnnotation(POST::class.java).value)
        val headers = method.parameterAnnotations.flatMap { annotations -> annotations.mapNotNull { (it as? Header)?.value } }
        assertTrue(headers.containsAll(listOf("X-Checkout-Claim", "Idempotency-Key")))
        val body = Json.encodeToString(RetainedMistakeCorrectionRequest("receipt", 4, "a".repeat(64), true, "Mistaken; not supplied"))
        assertTrue(body.contains("\"all_items_mistaken_not_supplied\":true"))
        assertTrue(body.contains("\"original_receipt_id\":\"receipt\""))
        assertTrue(body.contains("\"expected_checkout_version\":4"))
    }

    @Test
    fun `stable retry identity binds review reason and bill`() {
        val original = retainedMistakeActionKey("order", "a".repeat(64), "Mistaken; not supplied")
        assertEquals(original, retainedMistakeActionKey("order", "a".repeat(64), "  Mistaken; not supplied  "))
        assertNotEquals(original, retainedMistakeActionKey("other", "a".repeat(64), "Mistaken; not supplied"))
        assertNotEquals(original, retainedMistakeActionKey("order", "b".repeat(64), "Mistaken; not supplied"))
        assertNotEquals(original, retainedMistakeActionKey("order", "a".repeat(64), "Another reason"))
        assertTrue(original.length <= 160)
    }

    @Test
    fun `retained review preserves provenance and blocks discount including clear`() {
        val order = Order(id = "order", status = "held", type = "session", subtotalMinor = 8000,
            totalMinor = 8000, dueMinor = 8000, gamingVoidProvenance = GamingVoidProvenance("receipt", "session", true, true),
            lines = listOf(OrderLine(id = "line", name = "Cold can", qty = 1.0, unitPriceMinor = 8000, lineTotalMinor = 8000)))
        val review = authoritativeHeldOrderReview(order, "shift")
        assertEquals(order.gamingVoidProvenance, review.gamingVoidProvenance)
        for (amount in listOf(0L, 8000L)) assertThrows(IllegalArgumentException::class.java) { heldOrderDiscountMutation(review, amount) }
        assertEquals(0L, heldOrderDiscountMutation(review.copy(gamingVoidProvenance = null), 0).body.manualDiscountMinor)
    }

    @Test
    fun `correction needs owner identity plus all declared permissions`() {
        val permissions = listOf("pos.read", "pos.write", "pos.void", "gaming.write")
        fun access(roles: List<String>, granted: List<String>) = EffectivePermissions.from(MeResponse(
            userId = "actor", email = "actor@example.test", name = "Actor", roles = roles,
            companyId = "company", branchId = "branch", effectivePermissions = granted,
        )).posAccess()
        assertTrue(access(listOf("owner"), permissions).canCorrectRetainedMistakes)
        assertFalse(access(listOf("manager"), permissions).canCorrectRetainedMistakes)
        for (missing in permissions) assertFalse(access(listOf("owner"), permissions - missing).canCorrectRetainedMistakes)
    }
}

package cloud.dcompany.erp.ui.screens.audit

import cloud.dcompany.erp.core.net.ApiClient
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SemanticAuditPresentationTest {
    private fun entry(extra:String="")=ApiClient.json.decodeFromString<AuditEntry>("""{"id":1,"actor_user_id":"retained-actor","actor_name":"Current name","action":"recorded","entity_type":"Payment","entity_id":"payment","created_at":"2026-10-18T10:00:00Z"$extra}""")
    @Test fun semanticCodeLabelAmountAndMethodHaveDistinctPurpose() {
        val row=entry(""", "action_code":"POS-PAY","action_label":"Payment recorded","business_details":{"amount_minor":27000,"method":"upi"},"summary":"Payment recorded" """)
        assertEquals("POS-PAY · Payment recorded",auditEntryTitle(row))
        assertEquals("₹270.00 · UPI",auditBusinessSummary(row))
    }
    @Test fun otpRequesterNeverBecomesAuthenticatedStaffAndLegacyAttributionStaysExplicit() {
        assertEquals("OTP-approved requester · Current name",auditActor(entry().copy(actorKind="otp_approved_requester")))
        assertEquals("Recorded requester · Current name",auditActor(entry().copy(actorKind="requester_recorded")))
        assertEquals("Authenticated actor · Current name",auditActor(entry().copy(actorKind="authenticated")))
        assertEquals("Unverified legacy attribution · Current name",auditActor(entry()))
        assertEquals("Requester not verified",auditActor(entry().copy(actorUserId=null,actorName=null)))
    }
    @Test fun stockAndRoleFactsRemainReadableWithoutInventingMissingNames() {
        val stock=entry(""", "business_details":{"qty_delta":2,"base_unit":"can"}""")
        assertEquals("Item name not retained · 2 can",auditBusinessSummary(stock))
        val roles=entry(""", "business_details":{"before_roles":["staff"],"after_roles":["owner"]}""")
        assertEquals("Roles: Staff → Owner",auditBusinessSummary(roles))
    }
    @Test fun rawIdentitiesStayTechnicalButTypedBusinessChangesRemain() {
        val row=entry(""", "business_details":{"order_id":"uuid","source_fingerprint":"hash","amount_minor":27000},"before":{"status":"open","order_id":"old","auth_version":1},"after":{"status":"closed","order_id":"new","auth_version":2}""").copy(action="update")
        assertEquals(listOf("amount_minor"),auditBusinessFacts(row).map{it.first})
        assertEquals(listOf("status"),auditBusinessChanges(row).map{it.first})
        assertEquals("Quantity: 2",auditBusinessValue("item",Json.parseToJsonElement("""{"order_id":"private-id","qty":2}"""),ZoneId.of("UTC")))
    }
    @Test fun shopTimePlayingTimeAndNestedRedactionRetainTruth() {
        val zone=ZoneId.of("Asia/Kolkata")
        assertTrue(auditBusinessValue("joined_at",JsonPrimitive("2026-10-18T10:00:00Z"),zone).contains("15:30:00"))
        assertEquals("1 hr 2 min 3 sec playing time",auditBusinessValue("play_elapsed_ms",JsonPrimitive(3723000),zone))
        val raw=Json.parseToJsonElement("""{"nested":[{"password":"secret","qty":2}],"token":"hide","api_key":"sensitive-key"}""")
        val safe=redactAuditValue(raw).toString()
        assertFalse(safe.contains("secret")); assertFalse(safe.contains("hide"));assertFalse(safe.contains("sensitive-key"));assertTrue(safe.contains("redacted"));assertTrue(safe.contains("qty"))
    }
    @Test fun loginUnlockCreationAndDeletionSnapshotsNeverImplyNameOrRoleMutation() {
        for(action in listOf("login_success","login_failed","audit_unlock_success","audit_unlock_failed","create")) {
            val row=entry().copy(action=action,after=Json.parseToJsonElement("""{"name":"Retained staff","roles":["staff"]}""").jsonObject)
            assertTrue(auditBusinessChanges(row).isEmpty())
            assertEquals(setOf("name","roles"),auditRetainedSnapshots(row).map{it.second}.toSet())
            assertTrue(auditRetainedSnapshots(row).all{it.first=="Retained after action"})
        }
        val deleted=entry().copy(action="delete",before=Json.parseToJsonElement("""{"name":"Deleted staff"}""").jsonObject)
        assertTrue(auditBusinessChanges(deleted).isEmpty());assertEquals("Deleted staff",auditRetainedSnapshots(deleted).single().third.jsonPrimitive.content)
    }

    @Test fun explicitRolePairIsOneRealTransitionAndEmptyRolesAreKnownNotMissing() {
        val row=entry().copy(action="staff_access_changed",before=Json.parseToJsonElement("""{"before_roles":[]}""").jsonObject,after=Json.parseToJsonElement("""{"after_roles":["owner"]}""").jsonObject)
        val change=auditBusinessChanges(row).single();assertEquals("roles",change.first)
        assertEquals("No roles",auditBusinessValue("roles",change.second,ZoneId.of("UTC")))
        assertEquals("Owner",auditBusinessValue("roles",change.third,ZoneId.of("UTC")))
        assertTrue(auditRetainedSnapshots(row).isEmpty())
        val incomplete=row.copy(before=buildJsonObject{})
        assertTrue(auditBusinessChanges(incomplete).isEmpty());assertEquals("after_roles",auditRetainedSnapshots(incomplete).single().second)
    }

    @Test fun missingUpdateFieldIsUnknownEvidenceRatherThanAnInventedTransition() {
        val row=entry().copy(action="update",before=Json.parseToJsonElement("""{"status":"open"}""").jsonObject,after=Json.parseToJsonElement("""{"status":"closed","name":"Newly observed"}""").jsonObject)
        assertEquals(listOf("status"),auditBusinessChanges(row).map{it.first})
        assertEquals(listOf("name"),auditRetainedSnapshots(row).map{it.second})
        assertFalse(auditBusinessSummary(entry(""", "business_details":{"after_roles":["owner"]}""")).contains("→"))
        assertEquals("Stage / result not recorded",auditStageResult(entry()))
    }

}

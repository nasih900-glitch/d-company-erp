package cloud.dcompany.erp.ui.screens.audit

import cloud.dcompany.erp.core.net.ApiClient
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SemanticAuditPresentationTest {
    private fun entry(extra:String="")=ApiClient.json.decodeFromString<AuditEntry>("""{"id":1,"actor_user_id":"retained-actor","actor_name":"Current name","action":"recorded","entity_type":"Payment","entity_id":"payment","created_at":"2026-10-18T10:00:00Z"$extra}""")
    @Test fun participantVoidSeparatesOriginalChargeFromZeroDueAndRetainsAttendance() {
        val row = entry(""", "action_code":"GAM-VOID","business_details":{"voided_amount_minor":18000,"amount_after_void_minor":0,"original_billable_minutes":12,"added_player_interval_count":2,"attendance_retained":true}""")
            .copy(action = "gaming_session_voided")
        assertEquals("Voided ₹180.00 · Amount after void ₹0.00 · Attendance retained", auditBusinessSummary(row))
        assertEquals("Added-player attendance records", auditBusinessLabel("added_player_interval_count"))
        assertEquals("Original recorded billable minutes", auditBusinessLabel("original_billable_minutes"))
    }

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

    @Test fun knownFinanceEvidenceUsesFieldSpecificBusinessLabels() {
        val zone = ZoneId.of("UTC")
        val pairs = listOf(
            Triple("source_allocation", "unallocated_owner_attestation", "Owner-confirmed totals; not linked to an individual payment"),
            Triple("source_allocation", "identified_payment", "Linked to an identified payment"),
            Triple("evidence_kind", "owner_attestation", "Owner confirmation"),
            Triple("kind", "applied", "Applied"),
            Triple("kind", "unresolved", "Unresolved"),
        )
        for ((key, value, label) in pairs) {
            assertEquals(label, auditBusinessValue(key, JsonPrimitive(value), zone))
            assertEquals(value, auditBusinessValue("note", JsonPrimitive(value), zone))
        }
        assertEquals("Cash", auditBusinessLabel("cash_minor"))
        assertEquals("UPI", auditBusinessLabel("upi_minor"))
    }

    @Test fun unknownFinanceValuesStayVerbatimAndMissingEvidenceStaysMissing() {
        val zone = ZoneId.of("UTC")
        assertEquals("future_allocation", auditBusinessValue("source_allocation", JsonPrimitive("future_allocation"), zone))
        assertEquals("identified_payment", auditBusinessValue("evidence_kind", JsonPrimitive("identified_payment"), zone))
        assertEquals("future_result", auditBusinessValue("kind", JsonPrimitive("future_result"), zone))
        assertEquals("Not recorded", auditBusinessValue("source_allocation", JsonNull, zone))
        assertEquals("Not recorded", auditBusinessValue("evidence_kind", null, zone))
        assertEquals("Not recorded", auditBusinessValue("kind", JsonPrimitive(""), zone))
    }

    @Test fun businessFormattingLeavesRawFinanceEvidenceIntact() {
        val raw = Json.parseToJsonElement("""{"source_allocation":"unallocated_owner_attestation","evidence_kind":"owner_attestation","kind":"applied","before":{"cash_minor":3000,"upi_minor":0}}""")
        val original = raw.toString()
        assertTrue(auditBusinessValue("facts", raw, ZoneId.of("UTC")).contains("Owner confirmation"))
        assertEquals(original, raw.toString())
        assertEquals(original, redactAuditValue(raw).toString())
    }

    @Test fun updatePolicyFactsDescribeGlobalSupportedFloor() {
        val row=entry(""", "business_details":{"update_mode_before":"required","update_mode_after":"optional","policy_revision":6,"minimum_supported_build":54,"global_installations":true,"reason":"Reviewed supported builds"}""").copy(action="android_update_policy_changed",actionCode="UPD-POL")
        assertEquals("Optional updates · Minimum supported build 54 · All direct Android installations",auditBusinessSummary(row))
        assertEquals("Deployment safety floor",auditBusinessLabel("environment_safety_floor"))
    }

}

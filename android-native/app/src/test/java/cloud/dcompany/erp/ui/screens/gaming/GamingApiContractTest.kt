package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class GamingApiContractTest {

    @Test
    fun `cross-terminal handoff contract keeps the explicit target and provenance receipt`() {
        val encoded = ApiClient.json.encodeToString(
            SessionPosHandoffBody(targetShiftId = "target-shift"),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive("target-shift"), body["target_shift_id"])

        val result = ApiClient.json.decodeFromString<SessionPosHandoffResult>(
            """{
              "order_id":"order-1","amount_minor":15750,
              "source_shift_id":"gaming-shift","source_terminal_id":"gaming-terminal",
              "target_shift_id":"cafe-shift","target_terminal_id":"cafe-terminal",
              "already_linked":true
            }""".trimIndent(),
        )
        assertEquals("gaming-shift", result.sourceShiftId)
        assertEquals("cafe-shift", result.targetShiftId)
        assertTrue(result.alreadyLinked)
    }

    @Test
    fun `gaming API exposes the explicit target list and handoff routes`() {
        val listMethod = GamingApi::class.java.getDeclaredMethod(
            "posTargetShifts",
            String::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        val handoffMethod = GamingApi::class.java.getDeclaredMethod(
            "handoffToPos",
            String::class.java,
            SessionPosHandoffBody::class.java,
            kotlin.coroutines.Continuation::class.java,
        )

        assertEquals(
            "gaming/sessions/{id}/pos-target-shifts",
            listMethod.getAnnotation(GET::class.java)?.value,
        )
        assertEquals(
            "gaming/sessions/{id}/handoff-to-pos",
            handoffMethod.getAnnotation(POST::class.java)?.value,
        )
    }

    @Test
    fun `open timer extension keeps its required null concurrency snapshot on the wire`() {
        val encoded = ApiClient.json.encodeToString(
            SessionTimerExtendBody(expectedTimerMinutes = null, additionalMinutes = 30),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertTrue(body.containsKey("expected_timer_minutes"))
        assertEquals(JsonNull, body["expected_timer_minutes"])
        assertEquals(JsonPrimitive(30), body["additional_minutes"])
        assertFalse(body.containsKey("target_timer_minutes"))
    }

    @Test
    fun `package start sends immutable catalogue and station snapshots`() {
        val encoded = ApiClient.json.encodeToString(
            SessionStartBody(
                stationId = "station-1",
                shiftId = "shift-1",
                startedAt = "2026-08-27T12:34:56Z",
                packageId = "package-1",
                extraControllers = 2,
                expectedRatePerHourMinor = 15_000,
                expectedPackagePriceMinor = 20_000,
                expectedPackageDurationMinutes = 60,
                expectedPackageVariant = "dual",
            ),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive(15_000), body["expected_rate_per_hour_minor"])
        assertEquals(JsonPrimitive("2026-08-27T12:34:56Z"), body["started_at"])
        assertEquals(JsonPrimitive(20_000), body["expected_package_price_minor"])
        assertEquals(JsonPrimitive(60), body["expected_package_duration_minutes"])
        assertEquals(JsonPrimitive("dual"), body["expected_package_variant"])
    }

    @Test
    fun `billing repair preserves the required null expected amount`() {
        val encoded = ApiClient.json.encodeToString(
            SessionBillingRepairBody(
                expectedAmountMinor = JsonNull,
                amountMinor = 12_500,
                reason = "Verified from timer audit",
            ),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertTrue(body.containsKey("expected_amount_minor"))
        assertEquals(JsonNull, body["expected_amount_minor"])
        assertEquals(JsonPrimitive(12_500), body["amount_minor"])
    }

    @Test
    fun `paid extension sends exact package and session compare-and-set snapshots`() {
        val encoded = ApiClient.json.encodeToString(
            SessionPackageExtendBody(
                packageId = "extension-1",
                expectedPackagePriceMinor = 7_500,
                expectedPackageDurationMinutes = 30,
                expectedPackageVariant = "dual",
                expectedTimerMinutes = 60,
                expectedAmountMinor = 23_000,
            ),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive(7_500), body["expected_package_price_minor"])
        assertEquals(JsonPrimitive(30), body["expected_package_duration_minutes"])
        assertEquals(JsonPrimitive("dual"), body["expected_package_variant"])
        assertEquals(JsonPrimitive(60), body["expected_timer_minutes"])
        assertEquals(JsonPrimitive(23_000), body["expected_amount_minor"])
    }

    @Test
    fun `legacy package resolution keeps the retained action identity and evidence`() {
        val localActionId = "11111111-1111-4111-8111-111111111111"
        val encoded = ApiClient.json.encodeToString(
            LegacyGamingOutboxResolutionBody(
                localActionId = localActionId,
                stationId = "22222222-2222-4222-8222-222222222222",
                shiftId = "55555555-5555-4555-8555-555555555555",
                capturedStartedAt = "2026-08-27T12:00:00Z",
                capturedStoppedAt = "2026-08-27T13:00:00Z",
                packageId = "33333333-3333-4333-8333-333333333333",
                expectedRatePerHourMinor = null,
                resolution = "manual_bill_recorded",
                referenceOrderId = "44444444-4444-4444-8444-444444444444",
                reason = "Matched to the verified POS receipt",
            ),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive(localActionId), body["local_action_id"])
        assertEquals(
            JsonPrimitive("55555555-5555-4555-8555-555555555555"),
            body["shift_id"],
        )
        assertEquals(JsonPrimitive("2026-08-27T12:00:00Z"), body["captured_started_at"])
        assertEquals(JsonPrimitive("2026-08-27T13:00:00Z"), body["captured_stopped_at"])
        assertEquals(JsonPrimitive("manual_bill_recorded"), body["resolution"])
        assertEquals(null, body["expected_rate_per_hour_minor"])
        assertEquals(
            JsonPrimitive("44444444-4444-4444-8444-444444444444"),
            body["reference_order_id"],
        )
        assertEquals(
            "gaming-legacy-outbox-resolution:$localActionId",
            legacyResolutionIdempotencyKey(localActionId),
        )
    }


    @Test
    fun `legacy hourly recovery sends its exact displayed rate and no package`() {
        val encoded = ApiClient.json.encodeToString(
            LegacyGamingOutboxResolutionBody(
                localActionId = "11111111-1111-4111-8111-111111111111",
                stationId = "22222222-2222-4222-8222-222222222222",
                shiftId = "55555555-5555-4555-8555-555555555555",
                capturedStartedAt = "2026-08-27T12:00:00Z",
                capturedStoppedAt = "2026-08-27T12:30:00Z",
                packageId = null,
                expectedRatePerHourMinor = 15_000,
                resolution = "manual_bill_recorded",
                referenceOrderId = "44444444-4444-4444-8444-444444444444",
                reason = "Matched to the verified hourly POS receipt",
            ),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertEquals(null, body["package_id"])
        assertEquals(JsonPrimitive(15_000), body["expected_rate_per_hour_minor"])
    }

    @Test
    fun `accepted start recovery probe sends no order reference or invented package values`() {
        val encoded = ApiClient.json.encodeToString(
            LegacyGamingOutboxResolutionBody(
                localActionId = "11111111-1111-4111-8111-111111111111",
                stationId = "22222222-2222-4222-8222-222222222222",
                shiftId = "55555555-5555-4555-8555-555555555555",
                capturedStartedAt = "2026-08-27T12:00:00Z",
                capturedStoppedAt = "2026-08-27T12:30:00Z",
                packageId = "33333333-3333-4333-8333-333333333333",
                expectedRatePerHourMinor = null,
                resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                referenceOrderId = null,
                reason = "Recover the accepted Start and replay its retained Stop",
            ),
        )
        val body = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertEquals(
            JsonPrimitive(GamingLegacyResolution.SERVER_SESSION_RECOVERED),
            body["resolution"],
        )
        assertEquals(null, body["reference_order_id"])
        assertEquals(null, body["expected_rate_per_hour_minor"])
    }

    @Test
    fun `retired base package remains identifiable from locked session snapshots`() {
        val session = ApiClient.json.decodeFromString<GameSession>(
            """{
              "id":"session-1","station_id":"station-1","shift_id":"shift-1",
              "status":"active","start_at":"2026-08-27T12:00:00Z",
              "timer_minutes":60,"amount_minor":18000,"package_id":null,
              "billing_mode":"package","package_price_minor_snapshot":15000,
              "package_duration_minutes_snapshot":60,"package_variant_snapshot":"dual",
              "package_station_type_snapshot":"ps5","extra_controllers":1
            }""".trimIndent(),
        )

        assertTrue(session.isPackageBilling())
        assertTrue(session.hasLockedPackageExtensionSnapshot())
        assertEquals(null, session.packageId)
        assertEquals("dual", session.packageVariantSnapshot)
        assertEquals("ps5", session.packageStationTypeSnapshot)
    }

    @Test
    fun `legacy ambiguous billing mode decodes as a non-hourly fail-safe`() {
        val session = ApiClient.json.decodeFromString<GameSession>(
            """{
              "id":"session-legacy","station_id":"station-1","shift_id":"shift-1",
              "status":"ended","start_at":"2026-08-27T12:00:00Z",
              "end_at":"2026-08-27T13:00:00Z","timer_minutes":60,
              "amount_minor":15000,"billing_mode":"legacy_ambiguous"
            }""".trimIndent(),
        )

        assertTrue(session.hasUnverifiedLegacyBillingMode())
        assertTrue(session.isPackageBilling())
    }

    @Test
    fun `transfer request keeps the source station from the viewed session snapshot`() {
        val viewedAtA = ApiClient.json.decodeFromString<GameSession>(
            """{
              "id":"session-1","station_id":"station-a","shift_id":"shift-1",
              "status":"active","start_at":"2026-08-27T12:00:00Z",
              "billing_mode":"hourly"
            }""".trimIndent(),
        )
        val body = viewedAtA.transferBody("station-b")
        val encoded = ApiClient.json.encodeToString(body)
        val json = ApiClient.json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("station-a"), json["expected_source_station_id"])
        assertEquals(JsonPrimitive("station-b"), json["target_station_id"])

        // If another terminal already moved this server session A -> C, this
        // immutable expected A value makes the stale A -> B tap fail with 409
        // instead of silently moving C -> B.
        assertEquals("station-a", body.expectedSourceStationId)
    }
}

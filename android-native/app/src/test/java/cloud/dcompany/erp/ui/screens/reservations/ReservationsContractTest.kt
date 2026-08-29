package cloud.dcompany.erp.ui.screens.reservations

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import java.time.LocalDateTime
import java.time.ZoneId

class ReservationsContractTest {
    @Test
    fun `retrofit routes match the existing table and gaming backend contract`() {
        val methods = ReservationsApi::class.java.declaredMethods.associateBy { it.name }

        assertEquals(
            "tables/reservations",
            methods.getValue("tableReservations").getAnnotation(GET::class.java)?.value,
        )
        assertEquals(
            "tables/reservations",
            methods.getValue("createTableReservation").getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "tables/reservations/{id}/status",
            methods.getValue("updateTableReservationStatus").getAnnotation(PATCH::class.java)?.value,
        )
        assertEquals(
            "gaming/bookings",
            methods.getValue("gamingBookings").getAnnotation(GET::class.java)?.value,
        )
        assertEquals(
            "gaming/bookings",
            methods.getValue("createGamingBooking").getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "gaming/bookings/{id}/status",
            methods.getValue("updateGamingBookingStatus").getAnnotation(PATCH::class.java)?.value,
        )
    }

    @Test
    fun `table reservation response decodes exact backend field names`() {
        val row = ApiClient.json.decodeFromString<TableReservation>(
            """
            {
              "id":"reservation-1",
              "table_id":"table-1",
              "table_code":"T-01",
              "guest_name":"Aisha",
              "party_size":4,
              "contact":"9999999999",
              "starts_at":"2026-08-28T18:00:00Z",
              "ends_at":"2026-08-28T19:30:00Z",
              "notes":"Birthday",
              "status":"held",
              "created_at":"2026-08-27T12:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("T-01", row.tableCode)
        assertEquals("Aisha", row.guestName)
        assertEquals(4, row.partySize)
        assertEquals("held", row.status)
    }

    @Test
    fun `gaming booking keeps deposit as integer paise`() {
        val row = ApiClient.json.decodeFromString<GamingBooking>(
            """
            {
              "id":"booking-1",
              "station_id":"station-1",
              "station_code":"PS5-01",
              "starts_at":"2026-08-28T18:00:00Z",
              "ends_at":"2026-08-28T19:00:00Z",
              "guest_name":"Ravi",
              "contact":null,
              "party_size":2,
              "deposit_minor":12550,
              "status":"held",
              "created_at":"2026-08-27T12:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals(12_550L, row.depositMinor)
        assertEquals("PS5-01", row.stationCode)
    }

    @Test
    fun `table create body matches FastAPI and omits blank optionals`() {
        val body = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(
                TableReservationCreate(
                    tableId = "table-1",
                    guestName = "Aisha",
                    partySize = 4,
                    startsAt = "2026-08-28T18:00+05:30",
                    endsAt = "2026-08-28T19:30+05:30",
                ),
            ),
        ).jsonObject

        assertEquals(JsonPrimitive("table-1"), body["table_id"])
        assertEquals(JsonPrimitive("Aisha"), body["guest_name"])
        assertEquals(JsonPrimitive(4), body["party_size"])
        assertFalse(body.containsKey("contact"))
        assertFalse(body.containsKey("notes"))
    }

    @Test
    fun `gaming create body matches FastAPI money and time fields`() {
        val body = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(
                GamingBookingCreate(
                    stationId = "station-1",
                    startsAt = "2026-08-28T18:00+05:30",
                    endsAt = "2026-08-28T19:00+05:30",
                    guestName = "Ravi",
                    partySize = 2,
                    depositMinor = 12_550,
                ),
            ),
        ).jsonObject

        assertEquals(JsonPrimitive("station-1"), body["station_id"])
        assertEquals(JsonPrimitive(12_550), body["deposit_minor"])
        assertEquals(JsonPrimitive("2026-08-28T18:00+05:30"), body["starts_at"])
    }

    @Test
    fun `draft validation rejects bad time and required values without inventing limits`() {
        val start = LocalDateTime.of(2026, 8, 28, 18, 0)

        assertEquals(
            "Select a table.",
            reservationDraftError(null, "table", "Aisha", 2, "", start, start.plusHours(1)),
        )
        assertEquals(
            "Enter the guest's name.",
            reservationDraftError("table-1", "table", "  ", 2, "", start, start.plusHours(1)),
        )
        assertEquals(
            "Party size must be a whole number greater than 0.",
            reservationDraftError("table-1", "table", "Aisha", 0, "", start, start.plusHours(1)),
        )
        assertEquals(
            "End time must be after the start time.",
            reservationDraftError("table-1", "table", "Aisha", 2, "", start, start),
        )
        assertNull(
            reservationDraftError("table-1", "table", "Aisha", 2, "", start, start.plusHours(1)),
        )
    }

    @Test
    fun `status targets only allow the backend terminal transitions`() {
        assertEquals(setOf("seated", "no_show", "cancelled"), TABLE_RESERVATION_TARGETS)
        assertEquals(setOf("consumed", "no_show", "cancelled"), GAMING_BOOKING_TARGETS)
        assertTrue(reservationStatusIsPending("held"))
        assertFalse(reservationStatusIsPending("cancelled"))
    }

    @Test
    fun `local touch time becomes offset-aware wire time`() {
        val wire = LocalDateTime.of(2026, 8, 28, 18, 30)
            .toReservationWire(ZoneId.of("Asia/Kolkata"))

        assertEquals("2026-08-28T18:30+05:30", wire)
        assertEquals(
            "28 Aug 2026, 2:00 pm",
            formatReservationDateTime("2026-08-28T08:30:00Z", ZoneId.of("Asia/Kolkata")),
        )
    }

    @Test
    fun `ambiguous mutation failure tells staff to refresh before retrying`() {
        val message = reservationMutationError(
            ApiException("socket closed", status = null),
            "save this table reservation",
        )

        assertTrue(message.contains("Could not confirm"))
        assertTrue(message.contains("Refresh"))
        assertFalse(message.contains("socket"))
        assertTrue(reservationOfflineMessage().contains("were not saved"))
    }
}

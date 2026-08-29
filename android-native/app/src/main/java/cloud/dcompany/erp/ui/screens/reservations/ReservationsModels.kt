package cloud.dcompany.erp.ui.screens.reservations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exact wire contracts for the two reservation resources already exposed by
 * FastAPI and the web client. Reservation money is always paise (`*_minor`).
 *
 * Mutations deliberately stay out of Room/outbox. The backend does not expose
 * idempotency for these endpoints and a guessed local replay contract could
 * create a duplicate booking after a response-loss retry.
 */
@Serializable
data class TableReservation(
    val id: String,
    @SerialName("table_id") val tableId: String,
    @SerialName("table_code") val tableCode: String,
    @SerialName("guest_name") val guestName: String,
    @SerialName("party_size") val partySize: Int,
    val contact: String? = null,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val notes: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class GamingBooking(
    val id: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("station_code") val stationCode: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("guest_name") val guestName: String,
    val contact: String? = null,
    @SerialName("party_size") val partySize: Int,
    @SerialName("deposit_minor") val depositMinor: Long = 0,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ReservationTable(
    val id: String,
    val code: String,
    val seats: Int = 0,
    val status: String = "available",
)

@Serializable
data class ReservationStation(
    val id: String,
    val code: String,
    val name: String,
    val type: String,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class TableReservationCreate(
    @SerialName("table_id") val tableId: String,
    @SerialName("guest_name") val guestName: String,
    @SerialName("party_size") val partySize: Int,
    val contact: String? = null,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val notes: String? = null,
)

@Serializable
data class GamingBookingCreate(
    @SerialName("station_id") val stationId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("guest_name") val guestName: String,
    val contact: String? = null,
    @SerialName("party_size") val partySize: Int = 1,
    @SerialName("deposit_minor") val depositMinor: Long = 0,
)

@Serializable
data class ReservationStatusUpdate(val status: String)

@Serializable
data class ReservationCreateResult(val id: String, val status: String)

enum class ReservationTab { TABLES, GAMING }

internal val TABLE_RESERVATION_TARGETS = setOf("seated", "no_show", "cancelled")
internal val GAMING_BOOKING_TARGETS = setOf("consumed", "no_show", "cancelled")

internal fun reservationStatusLabel(status: String): String = when (status) {
    "held" -> "Awaiting guest"
    "seated" -> "Seated"
    "consumed" -> "Used"
    "no_show" -> "No-show"
    "cancelled" -> "Cancelled"
    else -> status.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

internal fun reservationStatusIsPending(status: String): Boolean = status == "held"

internal fun reservationDraftError(
    resourceId: String?,
    resourceLabel: String,
    guestName: String,
    partySize: Int?,
    contact: String,
    startsAt: LocalDateTime,
    endsAt: LocalDateTime,
    notes: String? = null,
    depositMinor: Long? = null,
): String? = when {
    resourceId.isNullOrBlank() -> "Select a $resourceLabel."
    guestName.trim().isEmpty() -> "Enter the guest's name."
    guestName.trim().length > 200 -> "Guest name must be 200 characters or fewer."
    partySize == null || partySize <= 0 -> "Party size must be a whole number greater than 0."
    contact.trim().length > 50 -> "Contact must be 50 characters or fewer."
    !endsAt.isAfter(startsAt) -> "End time must be after the start time."
    notes != null && notes.trim().length > 500 -> "Notes must be 500 characters or fewer."
    depositMinor != null && depositMinor < 0 -> "Deposit cannot be negative."
    else -> null
}

/** Convert a touch-picked local venue time to the offset-aware wire format. */
internal fun LocalDateTime.toReservationWire(zoneId: ZoneId = ZoneId.systemDefault()): String =
    atZone(zoneId).toOffsetDateTime().toString()

private val RESERVATION_TIME_FORMAT =
    DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.UK)

internal fun formatReservationDateTime(
    value: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(zoneId).format(RESERVATION_TIME_FORMAT)
}.getOrDefault(value)

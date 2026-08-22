package cloud.dcompany.erp.ui.screens.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field names copied verbatim from backend/app/api/v1/events/router.py
 * (EventRead, TicketRead, EventCreate, EventUpdate, TicketSell) and
 * cross-checked against the already-shipped iOS native app's EventDTO/
 * EventTicketDTO/EventCreateRequest/EventUpdateRequest structs, which
 * matched exactly. Every `*_minor` field is an integer count of paise and
 * stays a Long the whole way.
 */

@Serializable
data class Event(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("event_type") val eventType: String,
    val screen: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
    val capacity: Int,
    val sold: Int,
    val remaining: Int,
    @SerialName("base_ticket_price_minor") val baseTicketPriceMinor: Long,
    @SerialName("sac_code") val sacCode: String,
    @SerialName("tax_rate") val taxRate: Double,
    val status: String,
    @SerialName("poster_url") val posterUrl: String? = null,
)

@Serializable
data class EventTicket(
    val id: String,
    @SerialName("ticket_no") val ticketNo: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("event_name") val eventName: String,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    val seat: String? = null,
    @SerialName("price_paid_minor") val pricePaidMinor: Long,
    val status: String,
    @SerialName("checked_in_at") val checkedInAt: String? = null,
)

/**
 * Every field nullable — the shared Json config has explicitNulls=false, so
 * a null field is simply omitted from the request body, matching the
 * backend's `exclude_unset=True` partial-update semantics. This DTO is
 * reused for the full-details edit form (which always populates every
 * non-price field) and for the standalone status-transition action (which
 * populates only `status`) — see EventsViewModel's two call sites.
 *
 * KNOWN LIMITATION, disclosed not fixed: because a Kotlin null is dropped
 * rather than sent as JSON null, this DTO cannot explicitly CLEAR an
 * already-set optional field (description/endsAt/posterUrl) back to blank
 * — only the web app can do that today. Same class of gotcha already
 * recorded for Access Control's reset-to-default PATCH; low-stakes here
 * since none of these fields carry money or safety meaning.
 */
@Serializable
data class EventUpdate(
    val name: String? = null,
    val description: String? = null,
    @SerialName("event_type") val eventType: String? = null,
    val screen: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val capacity: Int? = null,
    @SerialName("base_ticket_price_minor") val baseTicketPriceMinor: Long? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class EventCreate(
    val name: String,
    val description: String? = null,
    @SerialName("event_type") val eventType: String,
    val screen: String = "Main Screen",
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
    val capacity: Int,
    @SerialName("base_ticket_price_minor") val baseTicketPriceMinor: Long,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("branch_id") val branchId: String? = null,
)

@Serializable
data class TicketSell(
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String? = null,
    val seat: String? = null,
    val qty: Int = 1,
    val note: String? = null,
)

/** Only the fields this screen needs; ignoreUnknownKeys drops the rest —
 * same local-copy-per-screen convention as Inventory/Finance's own Branch. */
@Serializable
data class Branch(
    val id: String,
    val name: String,
    val code: String? = null,
)

fun eventTypeLabel(type: String): String = when (type) {
    "football" -> "Football"
    "cricket" -> "Cricket"
    "movie" -> "Movie"
    "esports" -> "Esports"
    else -> "Other"
}

fun eventStatusLabel(status: String): String = when (status) {
    "scheduled" -> "Scheduled"
    "live" -> "Live"
    "ended" -> "Ended"
    "cancelled" -> "Cancelled"
    else -> status
}

fun ticketStatusLabel(status: String): String = when (status) {
    "sold" -> "Sold"
    "checked_in" -> "Checked in"
    "cancelled" -> "Cancelled"
    "refunded" -> "Refunded"
    "no_show" -> "No-show"
    else -> status
}

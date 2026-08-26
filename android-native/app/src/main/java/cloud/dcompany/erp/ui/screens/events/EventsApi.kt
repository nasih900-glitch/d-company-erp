package cloud.dcompany.erp.ui.screens.events

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Event create/edit/delete are online-only, direct writes — create always
 * needs a fresh X-Pricing-Token (base_ticket_price_minor is a required
 * create field, so an event can never be created without one), and edits
 * are rare/high-scrutiny, matching Finance's capital-entry-void precedent
 * (Phase 10) rather than being queued through an offline outbox. The
 * X-Pricing-Token header itself is never passed explicitly here —
 * ApiClient's shared PricingTokenInterceptor attaches it automatically
 * from PricingLock whenever a token is currently unlocked.
 *
 * Ticket sale carries an Idempotency-Key because ticket_no is computed
 * server-side fresh from the current sold count on every call — a retry
 * without a stable key would silently double-issue tickets and double-
 * consume capacity, same reasoning as Inventory's GRN/adjustment writes.
 * Check-in carries none: it mutates one existing row by id, and a retry on
 * an already-checked-in ticket just 4xxs, it never double-effects.
 */
interface EventsApi {

    @GET("events/all")
    suspend fun listAll(@Query("include_past") includePast: Boolean = true): List<Event>

    @POST("events")
    suspend fun createEvent(
        @Body body: EventCreate,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Event

    @PATCH("events/{id}")
    suspend fun updateEvent(@Path("id") id: String, @Body body: EventUpdate): Event

    @DELETE("events/{id}")
    suspend fun deleteEvent(@Path("id") id: String)

    @POST("events/{event_id}/tickets")
    suspend fun sellTickets(
        @Path("event_id") eventId: String,
        @Body body: TicketSell,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): List<EventTicket>

    @GET("events/{event_id}/tickets")
    suspend fun listTickets(@Path("event_id") eventId: String): List<EventTicket>

    @POST("events/{event_id}/tickets/{ticket_id}/check-in")
    suspend fun checkIn(
        @Path("event_id") eventId: String,
        @Path("ticket_id") ticketId: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): EventTicket

    /** Both create and edit need a branch picker. */
    @GET("events/branches")
    suspend fun branches(): List<Branch>
}

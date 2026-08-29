package cloud.dcompany.erp.ui.screens.reservations

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Reservations are direct, online-only writes. Neither backend route accepts
 * an Idempotency-Key today, so this interface must not be wired to a generic
 * retry/outbox mechanism until the server owns an idempotent replay contract.
 */
interface ReservationsApi {
    @GET("tables/reservations")
    suspend fun tableReservations(
        @Query("status") status: List<String>? = null,
        @Query("limit") limit: Int = 200,
    ): List<TableReservation>

    @GET("tables")
    suspend fun tables(): List<ReservationTable>

    @POST("tables/reservations")
    suspend fun createTableReservation(
        @Body body: TableReservationCreate,
    ): ReservationCreateResult

    @PATCH("tables/reservations/{id}/status")
    suspend fun updateTableReservationStatus(
        @Path("id") id: String,
        @Body body: ReservationStatusUpdate,
    ): TableReservation

    @GET("gaming/bookings")
    suspend fun gamingBookings(
        @Query("status") status: List<String>? = null,
        @Query("limit") limit: Int = 200,
    ): List<GamingBooking>

    @GET("gaming/stations")
    suspend fun stations(): List<ReservationStation>

    @POST("gaming/bookings")
    suspend fun createGamingBooking(
        @Body body: GamingBookingCreate,
    ): ReservationCreateResult

    @PATCH("gaming/bookings/{id}/status")
    suspend fun updateGamingBookingStatus(
        @Path("id") id: String,
        @Body body: ReservationStatusUpdate,
    ): GamingBooking
}

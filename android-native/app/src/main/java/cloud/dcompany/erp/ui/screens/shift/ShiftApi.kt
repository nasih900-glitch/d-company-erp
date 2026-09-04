package cloud.dcompany.erp.ui.screens.shift

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Field names copied from ShiftRead in backend/app/api/v1/pos/router.py. */
@Serializable
data class ShiftDetail(
    val id: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String? = null,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("opening_float_minor") val openingFloatMinor: Long = 0,
    // Null until the server has computed it; never coerce a null to zero here,
    // because "not yet known" and "nothing expected" mean different things to
    // someone counting a drawer.
    @SerialName("expected_minor") val expectedMinor: Long? = null,
    @SerialName("counted_minor") val countedMinor: Long? = null,
    @SerialName("variance_minor") val varianceMinor: Long? = null,
    @SerialName("pos_sales_minor") val posSalesMinor: Long = 0,
    // Added after the original ShiftRead contract. Absence means the server
    // cannot provide an audited breakdown; it must never be shown as zero.
    @SerialName("membership_sales_minor") val membershipSalesMinor: Long? = null,
    @SerialName("gross_collections_minor") val grossCollectionsMinor: Long? = null,
    @SerialName("cash_collections_minor") val cashCollectionsMinor: Long? = null,
    @SerialName("card_collections_minor") val cardCollectionsMinor: Long? = null,
    @SerialName("upi_collections_minor") val upiCollectionsMinor: Long? = null,
    @SerialName("other_collections_minor") val otherCollectionsMinor: Long? = null,
    @SerialName("settled_pos_refunds_minor") val settledPosRefundsMinor: Long? = null,
    @SerialName("settled_membership_refunds_minor") val settledMembershipRefundsMinor: Long? = null,
    @SerialName("total_refunds_minor") val totalRefundsMinor: Long? = null,
    @SerialName("net_collections_minor") val netCollectionsMinor: Long? = null,
    /** Backward-compatible alias; display as gross collections, not net sales. */
    @SerialName("total_sales_minor") val totalSalesMinor: Long = 0,
    @SerialName("opened_by") val openedByUserId: String? = null,
    @SerialName("opened_by_name") val openedByName: String? = null,
    @SerialName("opened_by_email") val openedByEmail: String? = null,
    @SerialName("closed_by") val closedByUserId: String? = null,
    @SerialName("closed_by_name") val closedByName: String? = null,
    @SerialName("closed_by_email") val closedByEmail: String? = null,
)

@Serializable
data class ShiftOpenBody(@SerialName("opening_float_minor") val openingFloatMinor: Long)

/**
 * `POST /pos/shifts/open` returns a bare `{"id": ..., "status": ...}` dict —
 * no `response_model` is declared for it in pos/router.py, unlike GET
 * /shifts (ShiftRead). Typing this call's result as `ShiftDetail` throws
 * MissingFieldException for branch_id/opened_at on every single open, since
 * neither is ever in this particular response.
 */
@Serializable
data class ShiftOpenResult(val id: String, val status: String)

@Serializable
data class ShiftCloseBody(@SerialName("counted_minor") val countedMinor: Long) {
    init {
        require(countedMinor >= 0L) { "A shift drawer count cannot be negative." }
    }
}

@Serializable
data class ShiftCloseResult(
    val id: String,
    val status: String,
    @SerialName("variance_minor") val varianceMinor: Long = 0,
    @SerialName("opened_by") val openedByUserId: String? = null,
    @SerialName("closed_by") val closedByUserId: String? = null,
    @SerialName("closed_by_was_opener") val closedByWasOpener: Boolean? = null,
)

interface ShiftApi {

    @GET("pos/shifts")
    suspend fun shifts(
        @Query("only_open") onlyOpen: Boolean,
        @Query("limit") limit: Int = 200,
    ): List<ShiftDetail>

    @POST("pos/shifts/open")
    suspend fun open(
        @Body body: ShiftOpenBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): ShiftOpenResult

    @POST("pos/shifts/{id}/close")
    suspend fun close(
        @Path("id") id: String,
        @Body body: ShiftCloseBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): ShiftCloseResult
}

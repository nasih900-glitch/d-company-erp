package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.LocalGamingPackageExtensionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Copied from StationRead / SessionRead in backend/app/api/v1/gaming/router.py. */
@Serializable
data class Station(
    val id: String,
    val code: String,
    val name: String,
    val type: String,
    @SerialName("rate_per_hour_minor") val ratePerHourMinor: Long,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class GamingPackage(
    val id: String,
    @SerialName("station_type") val stationType: String,
    val variant: String,
    val kind: String,
    val name: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("price_minor") val priceMinor: Long,
)

@Serializable
data class GameSession(
    val id: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("shift_id") val shiftId: String? = null,
    val status: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("timer_minutes") val timerMinutes: Int? = null,
    @SerialName("timer_ends_at") val timerEndsAt: String? = null,
    @SerialName("billable_minutes") val billableMinutes: Int? = null,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    @SerialName("rate_per_hour_minor") val ratePerHourMinor: Long? = null,
    @SerialName("package_id") val packageId: String? = null,
    @SerialName("billing_mode") val billingMode: String? = null,
    @SerialName("package_price_minor_snapshot") val packagePriceMinorSnapshot: Long? = null,
    @SerialName("package_duration_minutes_snapshot") val packageDurationMinutesSnapshot: Int? = null,
    @SerialName("package_variant_snapshot") val packageVariantSnapshot: String? = null,
    @SerialName("package_station_type_snapshot") val packageStationTypeSnapshot: String? = null,
    @SerialName("extra_controllers") val extraControllers: Int = 0,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    /** Local outbox metadata; absent on server DTOs. */
    val localState: String? = null,
    val lastError: String? = null,
    /** Local-only protected recovery evidence; absent from server DTOs. */
    val legacyOriginalCapturedStartAt: String? = null,
    val legacyOriginalCapturedStopAt: String? = null,
    val legacyResolution: String? = null,
    val legacyResolutionReason: String? = null,
    val legacyResolutionReferenceOrderId: String? = null,
    val legacyResolutionAttemptState: String? = null,
    val legacyResolutionError: String? = null,
)

@Serializable
data class SessionStartBody(
    @SerialName("station_id") val stationId: String,
    @SerialName("shift_id") val shiftId: String,
    /** Exact durable-capture timestamp; must match the offline provenance header. */
    @SerialName("started_at") val startedAt: String,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("timer_minutes") val timerMinutes: Int? = null,
    @SerialName("package_id") val packageId: String? = null,
    @SerialName("extra_controllers") val extraControllers: Int = 0,
    /** Tap-time snapshots; the server rejects a stale catalogue/rate instead of repricing silently. */
    @SerialName("expected_rate_per_hour_minor") val expectedRatePerHourMinor: Long,
    @SerialName("expected_package_price_minor") val expectedPackagePriceMinor: Long? = null,
    @SerialName("expected_package_duration_minutes") val expectedPackageDurationMinutes: Int? = null,
    @SerialName("expected_package_variant") val expectedPackageVariant: String? = null,
)

@Serializable
data class SessionStopBody(@SerialName("ended_at") val endedAt: String)

@Serializable
data class SessionTimerExtendBody(
    // ApiClient uses explicitNulls=false. JsonNull is intentionally a non-null
    // value here so an open timer sends the required JSON `null` snapshot
    // rather than silently dropping the concurrency field.
    @SerialName("expected_timer_minutes") val expectedTimerMinutes: JsonElement,
    @SerialName("additional_minutes") val additionalMinutes: Int,
) {
    constructor(expectedTimerMinutes: Int?, additionalMinutes: Int) : this(
        expectedTimerMinutes = expectedTimerMinutes?.let(::JsonPrimitive) ?: JsonNull,
        additionalMinutes = additionalMinutes,
    )
}

@Serializable
data class SessionTransferBody(
    @SerialName("target_station_id") val targetStationId: String,
    @SerialName("expected_source_station_id") val expectedSourceStationId: String,
)

@Serializable
data class SessionPackageExtendBody(
    @SerialName("package_id") val packageId: String,
    @SerialName("expected_package_price_minor") val expectedPackagePriceMinor: Long,
    @SerialName("expected_package_duration_minutes") val expectedPackageDurationMinutes: Int,
    @SerialName("expected_package_variant") val expectedPackageVariant: String,
    @SerialName("expected_timer_minutes") val expectedTimerMinutes: Int,
    @SerialName("expected_amount_minor") val expectedAmountMinor: Long,
)

/** One canonical mapping prevents UI recovery and background replay from diverging. */
internal fun LocalGamingPackageExtensionEntity.toPackageExtendBody() = SessionPackageExtendBody(
    packageId = packageId,
    expectedPackagePriceMinor = expectedPackagePriceMinor,
    expectedPackageDurationMinutes = expectedPackageDurationMinutes,
    expectedPackageVariant = expectedPackageVariant,
    expectedTimerMinutes = expectedSessionTimerMinutes,
    expectedAmountMinor = expectedSessionAmountMinor,
)

@Serializable
data class SessionBillingRepairBody(
    // BillingMissing means the expected authoritative amount is exactly null.
    // JsonNull keeps that precondition on the wire despite explicitNulls=false.
    @SerialName("expected_amount_minor") val expectedAmountMinor: JsonElement,
    @SerialName("amount_minor") val amountMinor: Long,
    val reason: String,
)

@Serializable
data class SessionPosResult(
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_minor") val amountMinor: Long,
)

@Serializable
data class SessionCancelBody(val reason: String)

@Serializable
data class SessionReconcileBody(
    @SerialName("target_shift_id") val targetShiftId: String,
    val reason: String,
)

@Serializable
data class SessionReconcileResult(
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("source_shift_id") val sourceShiftId: String,
    @SerialName("target_shift_id") val targetShiftId: String,
    @SerialName("already_linked") val alreadyLinked: Boolean,
)

@Serializable
data class LegacyGamingOutboxResolutionBody(
    @SerialName("local_action_id") val localActionId: String,
    @SerialName("station_id") val stationId: String,
    /** Exact server shift resolved from the retained local shift mapping. */
    @SerialName("shift_id") val shiftId: String?,
    @SerialName("captured_started_at") val capturedStartedAt: String,
    @SerialName("captured_stopped_at") val capturedStoppedAt: String?,
    @SerialName("package_id") val packageId: String?,
    /** Required only for hourly v27 recovery; package requests must omit it. */
    @SerialName("expected_rate_per_hour_minor") val expectedRatePerHourMinor: Long? = null,
    val resolution: String,
    @SerialName("reference_order_id") val referenceOrderId: String?,
    val reason: String,
)

@Serializable
data class LegacyGamingOutboxResolutionReceipt(
    @SerialName("receipt_id") val receiptId: Long,
    @SerialName("local_action_id") val localActionId: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("package_id") val packageId: String? = null,
    val resolution: String,
    @SerialName("reference_order_id") val referenceOrderId: String? = null,
    /** Present only when the server proves the original Start already committed. */
    @SerialName("server_session") val serverSession: GameSession? = null,
    @SerialName("resolved_at") val resolvedAt: String,
)

interface GamingApi {

    @GET("gaming/stations")
    suspend fun stations(): List<Station>

    @GET("gaming/packages")
    suspend fun packages(): List<GamingPackage>

    @GET("gaming/sessions")
    suspend fun sessions(
        // The Android screen only renders active or stopped-unbilled sessions.
        // Asking for exactly that set prevents a busy venue's paid history
        // from pushing an older unresolved blocker out of the default page.
        @Query("unbilled_only") unbilledOnly: Boolean = true,
        @Query("limit") limit: Int = 500,
    ): List<GameSession>

    /** Exact history lookup used when a paid session no longer belongs in the unbilled board. */
    @GET("gaming/sessions/{id}")
    suspend fun session(@Path("id") id: String): GameSession

    @POST("gaming/sessions/start")
    suspend fun start(
        @Body body: SessionStartBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    @POST("gaming/sessions/{id}/stop")
    suspend fun stop(
        @Path("id") id: String,
        @Body body: SessionStopBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    /** Compatibility path for migrated rows that predate an exact captured Stop timestamp. */
    @POST("gaming/sessions/{id}/stop")
    suspend fun stopLegacy(
        @Path("id") id: String,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    @POST("gaming/sessions/{id}/extend-timer")
    suspend fun extendTimer(
        @Path("id") id: String,
        @Body body: SessionTimerExtendBody,
        @Header("Idempotency-Key") key: String,
    ): GameSession

    @POST("gaming/sessions/{id}/transfer")
    suspend fun transfer(
        @Path("id") id: String,
        @Body body: SessionTransferBody,
        @Header("Idempotency-Key") key: String,
    ): GameSession

    @POST("gaming/sessions/{id}/extend")
    suspend fun extendWithPackage(
        @Path("id") id: String,
        @Body body: SessionPackageExtendBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    /** Protected-owner recovery for an ended legacy row whose computed amount is absent. */
    @POST("gaming/sessions/{id}/repair-billing")
    suspend fun repairBilling(
        @Path("id") id: String,
        @Body body: SessionBillingRepairBody,
        @Header("Idempotency-Key") key: String,
    ): GameSession

    /** Natural idempotency: cancelling an already-cancelled session returns it unchanged. */
    @POST("gaming/sessions/{id}/cancel")
    suspend fun cancel(
        @Path("id") id: String,
        @Body body: SessionCancelBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    /** Natural idempotency: the backend locks the session and reuses gs.order_id on retry. */
    @POST("gaming/sessions/{id}/send-to-pos")
    suspend fun sendToPos(
        @Path("id") id: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): SessionPosResult

    /** Protected-owner recovery for an ended session whose source shift closed. */
    @POST("gaming/sessions/{id}/reconcile-to-pos")
    suspend fun reconcileToPos(
        @Path("id") id: String,
        @Body body: SessionReconcileBody,
    ): SessionReconcileResult

    /**
     * Protected-owner audit receipt for retained evidence from a rejected start.
     * Android captures the exact body before sending and always reuses the
     * local action id in the idempotency key after an ambiguous response.
     */
    @POST("gaming/legacy-outbox-resolutions")
    suspend fun resolveLegacyOutbox(
        @Body body: LegacyGamingOutboxResolutionBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): LegacyGamingOutboxResolutionReceipt
}

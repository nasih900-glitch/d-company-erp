package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionAddonActionType
import cloud.dcompany.erp.core.db.GamingSessionAddonCacheEntity
import cloud.dcompany.erp.core.db.LocalGamingPackageExtensionEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionAddonActionEntity
import cloud.dcompany.erp.core.db.LocalModifierSelectionSnapshot
import cloud.dcompany.erp.core.db.encodeModifierSelections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
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

@Serializable
data class SessionAddonModifierBody(
    @SerialName("modifier_id") val modifierId: String,
    val qty: Int,
)

@Serializable
data class SessionAddonCreateBody(
    @SerialName("client_line_id") val clientLineId: String,
    @SerialName("menu_item_id") val menuItemId: String,
    @SerialName("variant_id") val variantId: String? = null,
    val modifiers: List<SessionAddonModifierBody> = emptyList(),
    val qty: Int,
    @SerialName("expected_unit_price_minor") val expectedUnitPriceMinor: Long,
    val note: String? = null,
)

@Serializable
data class SessionAddonVoidBody(val reason: String)

@Serializable
data class SessionAddon(
    val id: String,
    @SerialName("gaming_session_id") val gamingSessionId: String,
    @SerialName("client_line_id") val clientLineId: String,
    @SerialName("menu_item_id") val menuItemId: String,
    @SerialName("menu_item_name") val menuItemName: String,
    @SerialName("menu_item_type") val menuItemType: String,
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("variant_snapshot") val variantSnapshot: JsonElement? = null,
    val modifiers: JsonElement,
    val qty: Int,
    @SerialName("catalog_unit_price_minor") val catalogUnitPriceMinor: Long,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("line_total_minor") val lineTotalMinor: Long,
    @SerialName("discount_minor") val discountMinor: Long,
    @SerialName("hsn_or_sac") val hsnOrSac: String? = null,
    @SerialName("tax_rate") val taxRate: Double,
    @SerialName("taxable_value_minor") val taxableValueMinor: Long,
    @SerialName("cgst_minor") val cgstMinor: Long,
    @SerialName("sgst_minor") val sgstMinor: Long,
    @SerialName("igst_minor") val igstMinor: Long,
    @SerialName("cess_minor") val cessMinor: Long,
    val note: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_terminal_id") val createdTerminalId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("voided_by") val voidedBy: String? = null,
    @SerialName("void_reason") val voidReason: String? = null,
)

internal fun LocalGamingSessionAddonActionEntity.toSessionAddonCreateBody() =
    SessionAddonCreateBody(
        clientLineId = clientLineId,
        menuItemId = menuItemId,
        variantId = variantId,
        modifiers = requireLocalModifierSelections(modifierSelectionsJson).map {
            SessionAddonModifierBody(modifierId = it.modifierId, qty = it.qty)
        },
        qty = qty,
        expectedUnitPriceMinor = expectedUnitPriceMinor,
        note = note,
    )

internal fun SessionAddon.toCacheEntity() = GamingSessionAddonCacheEntity(
    id = id,
    gamingSessionId = gamingSessionId,
    clientLineId = clientLineId,
    menuItemId = menuItemId,
    menuItemName = menuItemName,
    menuItemType = menuItemType,
    variantId = variantId,
    variantSnapshotJson = variantSnapshot?.toString(),
    // The server receipt uses snake_case and contains richer pricing fields,
    // while the durable Android command uses LocalModifierSelectionSnapshot's
    // camelCase schema. Normalise exactly once at the network/cache boundary so
    // a later reason-Void can recreate and validate the immutable item identity
    // instead of silently decoding a valid server receipt as an empty list.
    modifiersJson = canonicalLocalModifierSelectionsJson(),
    qty = qty,
    catalogUnitPriceMinor = catalogUnitPriceMinor,
    unitPriceMinor = unitPriceMinor,
    lineTotalMinor = lineTotalMinor,
    discountMinor = discountMinor,
    hsnOrSac = hsnOrSac,
    taxRate = taxRate,
    taxableValueMinor = taxableValueMinor,
    cgstMinor = cgstMinor,
    sgstMinor = sgstMinor,
    igstMinor = igstMinor,
    cessMinor = cessMinor,
    note = note,
    createdBy = createdBy,
    createdTerminalId = createdTerminalId,
    createdAtMillis = Instant.parse(createdAt).toEpochMilli(),
    voidedAtMillis = voidedAt?.let { Instant.parse(it).toEpochMilli() },
    voidedBy = voidedBy,
    voidReason = voidReason,
)

private const val MAX_SESSION_ADDON_MODIFIERS = 50
private val gamingAddonSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * Convert the backend's immutable modifier receipt into Android's immutable
 * local-command snapshot. Any missing identity/quantity/price evidence fails
 * before the cache or outbox can be resolved; malformed financial receipts are
 * never treated as an item without modifiers.
 */
private fun SessionAddon.canonicalLocalModifierSelectionsJson(): String {
    val rows = modifiers as? JsonArray
        ?: throw IllegalArgumentException("The server add-on receipt had invalid modifier evidence.")
    require(rows.size <= MAX_SESSION_ADDON_MODIFIERS) {
        "The server add-on receipt had too many modifier rows."
    }
    val snapshots = rows.map { element ->
        val row = element as? JsonObject
            ?: throw IllegalArgumentException("The server add-on receipt had an invalid modifier row.")
        val modifierId = row.requiredNonBlankString("modifier_id")
        val modifierGroupId = row.requiredNonBlankString("modifier_group_id")
        val name = row.requiredNonBlankString("name")
        val priceDeltaMinor = row["price_delta_minor"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("The server add-on receipt omitted a modifier price.")
        val qty = row["qty"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in 1..100 }
            ?: throw IllegalArgumentException("The server add-on receipt had an invalid modifier quantity.")
        LocalModifierSelectionSnapshot(
            modifierId = modifierId,
            modifierGroupId = modifierGroupId,
            name = name,
            priceDeltaMinor = priceDeltaMinor,
            qty = qty,
        )
    }
    require(snapshots.map(LocalModifierSelectionSnapshot::modifierId).distinct().size == snapshots.size) {
        "The server add-on receipt repeated a modifier identity."
    }
    return encodeModifierSelections(snapshots)
}

private fun JsonObject.requiredNonBlankString(field: String): String =
    this[field]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("The server add-on receipt omitted $field.")

/** Strict counterpart to the legacy POS decoder, which intentionally defaults malformed old drafts to empty. */
private fun requireLocalModifierSelections(raw: String): List<LocalModifierSelectionSnapshot> {
    val decoded = runCatching {
        gamingAddonSnapshotJson.decodeFromString<List<LocalModifierSelectionSnapshot>>(raw)
    }.getOrElse {
        throw IllegalArgumentException(
            "The saved Gaming item has invalid modifier evidence and will not be replayed.",
            it,
        )
    }
    require(decoded.size <= MAX_SESSION_ADDON_MODIFIERS) {
        "The saved Gaming item has too many modifier rows."
    }
    require(decoded.all {
        it.modifierId.isNotBlank() && !it.modifierGroupId.isNullOrBlank() &&
            it.name.isNotBlank() && it.qty in 1..100
    }) { "The saved Gaming item has incomplete modifier evidence and will not be replayed." }
    require(decoded.map(LocalModifierSelectionSnapshot::modifierId).distinct().size == decoded.size) {
        "The saved Gaming item repeats a modifier identity and will not be replayed."
    }
    return decoded
}

/** Reject a response before it can resolve or overwrite another local command. */
private fun SessionAddon.modifierIdentity(): List<Pair<String, Int>>? {
    val rows = modifiers as? JsonArray ?: return null
    return rows.map { element ->
        val row = element as? JsonObject ?: return null
        val id = row["modifier_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val qty = row["qty"]?.jsonPrimitive?.intOrNull ?: return null
        id to qty
    }.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
}

internal fun sessionAddonReceiptError(
    action: LocalGamingSessionAddonActionEntity,
    receipt: SessionAddon,
): String? {
    val expectedModifiers = runCatching {
        requireLocalModifierSelections(action.modifierSelectionsJson)
    }.getOrElse {
        return "The saved Gaming item has invalid modifier evidence and cannot be confirmed."
    }
        .map { it.modifierId to it.qty }
        .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
    return when {
    receipt.id.isBlank() || receipt.gamingSessionId != action.serverSessionId ->
        "The server add-on receipt did not match the saved Gaming session."
    receipt.clientLineId != action.clientLineId || receipt.menuItemId != action.menuItemId ->
        "The server add-on receipt did not match the saved item identity."
    receipt.menuItemName.isBlank() || receipt.menuItemName.length > 200 ||
        receipt.menuItemType !in setOf("food", "drink", "dessert") ->
        "The server add-on receipt had invalid item display metadata."
    receipt.qty != action.qty || receipt.variantId != action.variantId ||
        receipt.note != action.note || receipt.modifierIdentity() != expectedModifiers ->
        "The server add-on receipt did not match the saved item snapshot."
    action.actionType == GamingSessionAddonActionType.ADD &&
        receipt.catalogUnitPriceMinor != action.expectedUnitPriceMinor ->
        "The server add-on receipt did not match the saved catalogue price."
    action.actionType == GamingSessionAddonActionType.ADD &&
        (receipt.createdBy != action.ownerUserId || receipt.createdTerminalId != action.terminalId) ->
        "The server add-on receipt belonged to a different employee or terminal."
    action.actionType == GamingSessionAddonActionType.ADD && receipt.voidedAt != null ->
        "The server returned a voided item for a saved Add action."
    action.actionType == GamingSessionAddonActionType.VOID &&
        (receipt.id != action.serverAddonId || receipt.voidedAt == null ||
            receipt.voidedBy != action.ownerUserId || receipt.voidReason != action.voidReason) ->
        "The server void receipt did not match the saved employee, reason, or item."
    else -> null
    }
}

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

/** An open POS shift on another terminal which can explicitly receive this bill. */
@Serializable
data class PosTargetShift(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("terminal_name") val terminalName: String,
    @SerialName("opened_by") val openedBy: String,
    @SerialName("opened_by_name") val openedByName: String,
    @SerialName("opened_at") val openedAt: String,
)

@Serializable
data class SessionPosHandoffBody(
    @SerialName("target_shift_id") val targetShiftId: String,
)

/**
 * The source fields prove Gaming provenance stayed on the originating drawer;
 * the target fields identify the held order's collection drawer.
 */
@Serializable
data class SessionPosHandoffResult(
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("source_shift_id") val sourceShiftId: String,
    @SerialName("source_terminal_id") val sourceTerminalId: String,
    @SerialName("target_shift_id") val targetShiftId: String,
    @SerialName("target_terminal_id") val targetTerminalId: String,
    @SerialName("already_linked") val alreadyLinked: Boolean,
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

    @GET("gaming/sessions/{id}/addons")
    suspend fun sessionAddons(@Path("id") id: String): List<SessionAddon>

    @POST("gaming/sessions/{id}/addons")
    suspend fun addSessionAddon(
        @Path("id") id: String,
        @Body body: SessionAddonCreateBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): SessionAddon

    @POST("gaming/sessions/{sessionId}/addons/{addonId}/void")
    suspend fun voidSessionAddon(
        @Path("sessionId") sessionId: String,
        @Path("addonId") addonId: String,
        @Body body: SessionAddonVoidBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): SessionAddon

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

    /** Live, permission-scoped destinations; an empty list never authorises fallback. */
    @GET("gaming/sessions/{id}/pos-target-shifts")
    suspend fun posTargetShifts(@Path("id") id: String): List<PosTargetShift>

    /**
     * Natural idempotency: retrying the same session/target returns the linked
     * order with already_linked=true instead of creating another held order.
     */
    @POST("gaming/sessions/{id}/handoff-to-pos")
    suspend fun handoffToPos(
        @Path("id") id: String,
        @Body body: SessionPosHandoffBody,
    ): SessionPosHandoffResult

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

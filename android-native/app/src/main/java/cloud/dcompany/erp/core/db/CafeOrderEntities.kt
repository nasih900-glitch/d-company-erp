package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import cloud.dcompany.erp.core.net.OrderModifierSnapshot
import cloud.dcompany.erp.core.net.OrderVariantSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A server-confirmed line snapshot used by Tables while offline.
 *
 * The cache is intentionally denormalised: the server remains authoritative,
 * every response replaces one complete bill atomically, and no local write is
 * made by editing this object. Durable local intent lives in
 * [LocalCafeActionEntity], not in the cache.
 */
@Serializable
data class CafeBillLineSnapshot(
    val id: String,
    @SerialName("client_line_id") val clientLineId: String? = null,
    @SerialName("menu_item_id") val menuItemId: String? = null,
    val name: String,
    val qty: Double,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("line_total_minor") val lineTotalMinor: Long,
    val note: String? = null,
    @SerialName("kitchen_status") val kitchenStatus: String = "queued",
    @SerialName("kitchen_released_at") val kitchenReleasedAt: String? = null,
    @SerialName("kitchen_round_no") val kitchenRoundNo: Int? = null,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("void_reason") val voidReason: String? = null,
    @SerialName("kitchen_void_acknowledged_at")
    val kitchenVoidAcknowledgedAt: String? = null,
    @SerialName("variant_snapshot") val variantSnapshot: OrderVariantSnapshot? = null,
    val modifiers: List<OrderModifierSnapshot> = emptyList(),
)

/** Complete active table bill returned by GET /pos/table-orders/active. */
@Entity(
    tableName = "cafe_bill_cache",
    indices = [
        Index(value = ["tableId"], unique = true),
        Index(value = ["status"]),
    ],
)
data class CafeBillCacheEntity(
    @PrimaryKey val orderId: String,
    val tableId: String,
    val status: String,
    val type: String,
    val sourceLabel: String?,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val totalMinor: Long,
    val openedAt: String,
    val heldAt: String?,
    val checkoutVersion: Long,
    val lines: List<CafeBillLineSnapshot>,
    val voidedLines: List<CafeBillLineSnapshot>,
)

@Serializable
data class CafeActionLine(
    /** Stable across every retry and mirrored to OrderLine.client_line_id. */
    val clientLineId: String,
    val menuItemId: String,
    val name: String,
    val qty: Int,
    val note: String? = null,
    /** UI estimate only; server pricing always wins after confirmation. */
    val estimateUnitMinor: Long,
    val variantId: String? = null,
    val variantName: String? = null,
    val variantPriceDeltaMinor: Long = 0,
    val modifiers: List<LocalModifierSelectionSnapshot> = emptyList(),
)

/** Exact pre-v24 line shape retained only for lossless migration/replay. */
@Serializable
data class LegacyCafeActionLine(
    @SerialName("menu_item_id") val menuItemId: String,
    val qty: Int,
)

/**
 * One typed payload covers the table mutation kinds without persisting opaque
 * Retrofit request JSON. Fields that do not apply to a kind remain empty.
 */
@Serializable
data class CafeActionPayload(
    val lines: List<CafeActionLine> = emptyList(),
    @SerialName("legacy_lines") val legacyLines: List<LegacyCafeActionLine> = emptyList(),
    val targetClientLineId: String? = null,
    val targetServerLineId: String? = null,
    val reason: String? = null,
)

object CafeActionKind {
    const val CREATE_ROUND = "create_round"
    const val APPEND_ROUND = "append_round"
    const val VOID_LINE = "void_line"
    const val VOID_ORDER = "void_order"
    const val SEND_TO_POS = "send_to_pos"

    /** Rows moved losslessly from the pre-v24 create-and-immediately-hold outbox. */
    const val LEGACY_CREATE_AND_SEND = "legacy_create_and_send"
}

object CafeActionState {
    const val PENDING = "pending"
    const val CONFLICT = "conflict"
    const val REJECTED = "rejected"
}

object LocalCafeBillState {
    const val OPEN = "open"
    const val HELD = "held"
}

/**
 * Local anchor for an action chain. It is short-lived once the chain is fully
 * confirmed, but survives process death and maps an offline-created bill onto
 * the eventual server order.
 */
@Entity(
    tableName = "local_cafe_bills",
    indices = [
        Index(value = ["tableId"], unique = true),
        Index(value = ["serverOrderId"], unique = true),
    ],
)
data class LocalCafeBillEntity(
    @PrimaryKey val localBillId: String,
    val serverOrderId: String? = null,
    val tableId: String,
    val tableCode: String,
    /** May be a LocalShiftEntity.localId until the shift open is confirmed. */
    val shiftId: String,
    val confirmedCheckoutVersion: Long? = null,
    val localStatus: String = LocalCafeBillState.OPEN,
    val createdAtMillis: Long,
)

/**
 * Ordered, append-only local intent. A successful action is deleted only after
 * the returned authoritative bill snapshot and version have committed in the
 * same Room transaction.
 */
@Entity(
    tableName = "local_cafe_actions",
    indices = [
        Index(value = ["localBillId", "sequence"], unique = true),
        Index(value = ["state"]),
        Index(value = ["dedupeKey"], unique = true),
    ],
)
data class LocalCafeActionEntity(
    @PrimaryKey val actionId: String,
    val localBillId: String,
    /** Assigned transactionally by CafeOrderDao; callers pass zero. */
    val sequence: Long = 0,
    val kind: String,
    val payload: CafeActionPayload,
    /**
     * Exact server version the first offline mutation was composed against.
     * Later actions in the same local chain use null and consume the version
     * returned by their predecessor, so no client guesses trigger increments.
     */
    val capturedCheckoutVersion: Long? = null,
    /** Semantic identity absorbs rapid taps before an API call is attempted. */
    val dedupeKey: String,
    val createdAtMillis: Long,
    val state: String = CafeActionState.PENDING,
    val lastError: String? = null,
)

private val cafeOrderJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

class CafeOrderConverters {
    @TypeConverter
    fun linesToJson(value: List<CafeBillLineSnapshot>): String = cafeOrderJson.encodeToString(value)

    @TypeConverter
    fun linesFromJson(value: String): List<CafeBillLineSnapshot> =
        runCatching { cafeOrderJson.decodeFromString<List<CafeBillLineSnapshot>>(value) }
            .getOrDefault(emptyList())

    @TypeConverter
    fun payloadToJson(value: CafeActionPayload): String = cafeOrderJson.encodeToString(value)

    @TypeConverter
    fun payloadFromJson(value: String): CafeActionPayload =
        runCatching { cafeOrderJson.decodeFromString<CafeActionPayload>(value) }
            .getOrDefault(CafeActionPayload())
}

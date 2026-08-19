package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reference data — read-only on the tablet, replaced wholesale on every pull. */
@Entity(tableName = "cafe_floors")
data class FloorEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val name: String,
)

@Entity(tableName = "cafe_tables")
data class CafeTableEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val code: String,
    val seats: Int,
    val shape: String,
    val x: Double,
    val y: Double,
    /**
     * Server-driven only — occupied/available is set as a side effect of
     * order creation/payment, never written locally. A table order captured
     * offline won't flip this until the next pull lands after it syncs,
     * same "eventually consistent" tradeoff as everywhere else offline.
     */
    val status: String,
)

@Serializable
data class LocalTableOrderLine(
    @SerialName("menu_item_id") val menuItemId: String,
    val qty: Int,
)

private val tableOrderJson = Json { ignoreUnknownKeys = true }

class TableOrderLineListConverter {
    @TypeConverter
    fun toJson(lines: List<LocalTableOrderLine>): String = tableOrderJson.encodeToString(lines)

    @TypeConverter
    fun fromJson(json: String): List<LocalTableOrderLine> =
        runCatching { tableOrderJson.decodeFromString<List<LocalTableOrderLine>>(json) }
            .getOrDefault(emptyList())
}

object TableOrderState {
    const val PENDING = "pending"
    const val REJECTED = "rejected"
}

/**
 * A table's order, captured on this tablet — create-then-send-to-POS as one
 * push unit rather than a two-state machine like Shift/Gaming: unlike open
 * vs. close, "send" always immediately follows "create" here, there's no
 * independent moment where only the create leg is wanted. If a retry finds
 * `orderId` already set, it skips straight to send — see
 * SyncEngine.pushTableOrderOne. Both `createOrder` (via its idempotency
 * key) and `sendToPos` (a bare status-transition PATCH the backend already
 * treats as a no-op once "held") are safe to repeat.
 *
 * `shiftId` may be a [LocalShiftEntity.localId] rather than a real server
 * shift id, resolved at push time exactly like every other resource.
 */
@Entity(tableName = "local_table_orders", indices = [Index("state")])
data class LocalTableOrderEntity(
    @PrimaryKey val localId: String,
    val orderId: String? = null,
    val tableId: String,
    val tableCode: String,
    val shiftId: String,
    val lines: List<LocalTableOrderLine>,
    val createdAtMillis: Long,
    val state: String = TableOrderState.PENDING,
    val lastError: String? = null,
)

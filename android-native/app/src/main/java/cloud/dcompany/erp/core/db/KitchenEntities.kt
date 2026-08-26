package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.ColumnInfo
import cloud.dcompany.erp.ui.screens.kitchen.KitchenLine
import cloud.dcompany.erp.ui.screens.kitchen.KitchenCancellation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val kitchenJson = Json { ignoreUnknownKeys = true }

/**
 * `lines` is read-only display data, never filtered or queried on its own —
 * a JSON text column is the pragmatic choice over a whole child table + join
 * for something that's only ever read back whole, exactly as it went in.
 */
class KitchenLineListConverter {
    @TypeConverter
    fun toJson(lines: List<KitchenLine>): String = kitchenJson.encodeToString(lines)

    @TypeConverter
    fun fromJson(json: String): List<KitchenLine> =
        runCatching { kitchenJson.decodeFromString<List<KitchenLine>>(json) }.getOrDefault(emptyList())
}

class KitchenCancellationListConverter {
    @TypeConverter
    fun toJson(rows: List<KitchenCancellation>): String = kitchenJson.encodeToString(rows)

    @TypeConverter
    fun fromJson(json: String): List<KitchenCancellation> =
        runCatching { kitchenJson.decodeFromString<List<KitchenCancellation>>(json) }
            .getOrDefault(emptyList())
}

/** The active board — wholesale-replaced on every pull, exactly like the menu cache. */
@Entity(tableName = "kitchen_order_cache")
data class KitchenOrderCacheEntity(
    @PrimaryKey val id: String,
    val invoiceNo: String? = null,
    val type: String,
    val tableCode: String? = null,
    val customerName: String? = null,
    val openedAt: String? = null,
    val kitchenState: String,
    val minutesWaiting: Int = 0,
    val lines: List<KitchenLine> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'")
    val pendingCancellations: List<KitchenCancellation> = emptyList(),
)

object KitchenAdvanceState {
    const val PENDING = "pending"
    /** A definitive refusal — a skip or a step backwards. Needs a human. */
    const val REJECTED = "rejected"
}

/**
 * One ticket advance captured on this tablet. Deliberately simpler than
 * every other outbox so far: `setState` is naturally idempotent (the server
 * treats resending the state a ticket is already in as a no-op — see
 * KitchenState's own doc comment), so there is no idempotency key to
 * generate, no start/stop pair, and nothing to resolve at push time. A
 * synced row is deleted outright rather than kept — the next pull already
 * reflects the truth, and there's no local history worth keeping once it
 * lands, unlike a sale or a shift.
 */
@Entity(tableName = "local_kitchen_advances", indices = [Index("orderId")])
data class LocalKitchenAdvanceEntity(
    @PrimaryKey val localId: String,
    val orderId: String,
    val targetState: String,
    val requestedAtMillis: Long,
    val state: String = KitchenAdvanceState.PENDING,
    val lastError: String? = null,
)

object KitchenCancellationAckState {
    const val PENDING = "pending"
    const val REJECTED = "rejected"
}

@Entity(
    tableName = "local_kitchen_cancellation_acks",
    indices = [
        Index(value = ["orderId", "lineId"], unique = true),
        Index(value = ["state"]),
    ],
)
data class LocalKitchenCancellationAckEntity(
    @PrimaryKey val localId: String,
    val orderId: String,
    val lineId: String,
    val requestedAtMillis: Long,
    val state: String = KitchenCancellationAckState.PENDING,
    val lastError: String? = null,
)

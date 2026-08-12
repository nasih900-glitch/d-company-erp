package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Reference data cached for offline use. These rows are replaced wholesale on
 * every successful sync — the server is authoritative and the tablet never
 * edits them locally, so there is nothing to merge.
 */
@Entity(tableName = "menu_items")
data class MenuItemEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val sku: String,
    val name: String,
    val basePriceMinor: Long,
    val taxRate: Double,
    val isAvailable: Boolean,
)

@Entity(tableName = "menu_categories")
data class MenuCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)

/**
 * A sale captured on this tablet.
 *
 * `serverOrderId` and `invoiceNo` stay null until the sale syncs. That is the
 * whole design constraint: a GST invoice number comes from a single atomic
 * per-branch counter in Postgres (OrderPricingService.allocate), so a tablet
 * that mints its own would eventually collide with another terminal and put
 * two different sales under one tax invoice number. Offline therefore produces
 * a *provisional receipt*, and the tax invoice number is assigned on sync.
 */
@Entity(
    tableName = "local_orders",
    indices = [Index("syncState"), Index("createdAtMillis")],
)
data class LocalOrderEntity(
    /** Client-generated. Also the idempotency key, so a replay is free. */
    @PrimaryKey val localId: String,
    val serverOrderId: String? = null,
    val invoiceNo: String? = null,
    val shiftId: String,
    val type: String,
    val customerName: String? = null,
    val customerPhone: String? = null,
    /** Cart-side estimate. The server recomputes the real total on sync. */
    val estimateMinor: Long,
    /** Set once the server has priced it; null while provisional. */
    val serverTotalMinor: Long? = null,
    val paymentMethod: String,
    val tenderedMinor: Long,
    val tipMinor: Long = 0,
    val createdAtMillis: Long,
    val syncState: String,
    val lastError: String? = null,
)

object SyncState {
    /** Captured on the tablet, not yet sent. */
    const val PENDING = "pending"
    /** Sent and confirmed by the server. */
    const val SYNCED = "synced"
    /**
     * The server refused it for a business reason (closed shift, deleted item,
     * price change). Needs a human, never an automatic retry — retrying a
     * deterministic refusal forever is how a queue silently stops working.
     */
    const val REJECTED = "rejected"
}

@Entity(tableName = "local_order_lines", indices = [Index("orderLocalId")])
data class LocalOrderLineEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val orderLocalId: String,
    val menuItemId: String,
    val name: String,
    val qty: Int,
    val unitPriceMinor: Long,
)

/** Last successful sync per collection, for showing staleness in the UI. */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val lastSyncMillis: Long,
)

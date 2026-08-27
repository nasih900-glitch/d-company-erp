package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val type: String,
    val basePriceMinor: Long,
    val taxRate: Double,
    val hsnCode: String?,
    val priceIncludesTax: Boolean,
    val isAvailable: Boolean,
    val description: String?,
)

@Entity(tableName = "menu_categories")
data class MenuCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)

/** Server-owned choices that alter one menu item's unit price. */
@Entity(tableName = "menu_variants", indices = [Index("menuItemId")])
data class MenuVariantEntity(
    @PrimaryKey val id: String,
    val menuItemId: String,
    val name: String,
    val priceDeltaMinor: Long,
    val sortOrder: Int,
    val isActive: Boolean,
)

/** Selection bounds belong to a group, not to each option independently. */
@Entity(tableName = "menu_modifier_groups", indices = [Index("menuItemId")])
data class MenuModifierGroupEntity(
    @PrimaryKey val id: String,
    val menuItemId: String,
    val name: String,
    val minSelect: Int,
    val maxSelect: Int,
    val sortOrder: Int,
    val isActive: Boolean,
)

/** Server-owned add-ons. Quantity is selected per cart unit and validated again server-side. */
@Entity(
    tableName = "menu_modifiers",
    indices = [Index("menuItemId"), Index("modifierGroupId")],
)
data class MenuModifierEntity(
    @PrimaryKey val id: String,
    val menuItemId: String,
    val modifierGroupId: String,
    val name: String,
    val priceDeltaMinor: Long,
    val maxQuantity: Int,
    val sortOrder: Int,
    val isActive: Boolean,
)

/**
 * A sale captured on this tablet.
 *
 * `serverOrderId` and `invoiceNo` stay null until the sale syncs. That is the
 * whole design constraint: a GST invoice number comes from a single atomic
 * per-branch counter in Postgres (OrderPricingService.allocate), so a tablet
 * that mints its own would eventually collide with another terminal and put
     * two different sales under one tax invoice number. Offline therefore produces
     * a *provisional sale record*, and the tax invoice number is assigned on sync.
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
    val orderNote: String? = null,
    /** Cashier-entered reduction, applied and revalidated by the server before online collection. */
    @ColumnInfo(defaultValue = "0") val manualDiscountMinor: Long = 0,
    /** Immutable optimistic version used by the idempotent discount request across retries. */
    val discountRequestVersion: Long? = null,
    /** Monotonic editable-cart revision used by payment confirmation CAS. */
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    /** Cart-side estimate. The server recomputes the real total on sync. */
    val estimateMinor: Long,
    /** Exact provisional balance staff confirmed offline; never inferred again from a mutable cart. */
    val capturedAmountMinor: Long? = null,
    /** Set once the server has priced it; null while provisional. */
    val serverTotalMinor: Long? = null,
    val serverSubtotalMinor: Long? = null,
    val serverDiscountMinor: Long? = null,
    val serverTaxMinor: Long? = null,
    val serverRoundOffMinor: Long? = null,
    val serverDueMinor: Long? = null,
    /** Short-lived checkout lease for an online, server-priced direct bill. */
    val checkoutClaimToken: String? = null,
    val checkoutClaimExpiresAtMillis: Long? = null,
    val checkoutVersion: Long? = null,
    val paymentMethod: String = "",
    val tenderedMinor: Long = 0,
    val tipMinor: Long = 0,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "0") val updatedAtMillis: Long = createdAtMillis,
    val syncState: String,
    val lastError: String? = null,
)

object SyncState {
    /** Editable cart persisted before any network or money boundary. */
    const val DRAFT = "draft"
    /** Stable idempotency identity is being used to create/recover the server bill. */
    const val PREPARING = "preparing"
    /** Exact server totals and checkout claim are ready; no payment is confirmed yet. */
    const val AWAITING_PAYMENT = "awaiting_payment"
    /** Captured on the tablet, not yet sent. */
    const val PENDING = "pending"
    /** Sent and confirmed by the server. */
    const val SYNCED = "synced"
    /**
     * The server refused it for a business reason (closed shift, deleted item,
     * price change). Needs a human, never an automatic retry — retrying a
     * deterministic refusal forever is how a queue silently stops working.
     * Once the cause is fixed, a human may explicitly move this same row back
     * to pending; its unchanged localId keeps the replay idempotent.
     */
    const val REJECTED = "rejected"
}

@Entity(
    tableName = "local_order_lines",
    indices = [Index("orderLocalId"), Index("clientLineId")],
)
data class LocalOrderLineEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val orderLocalId: String,
    val menuItemId: String,
    /** Stable across draft edits/restarts and forwarded to the backend for idempotent line identity. */
    val clientLineId: String? = null,
    val name: String,
    val qty: Int,
    val variantId: String? = null,
    val variantName: String? = null,
    @ColumnInfo(defaultValue = "0") val variantPriceDeltaMinor: Long = 0,
    /** JSON array of immutable modifier id/name/price/qty snapshots. */
    @ColumnInfo(defaultValue = "'[]'") val modifierSelectionsJson: String = "[]",
    val note: String? = null,
    val unitPriceMinor: Long,
)

@Serializable
data class LocalModifierSelectionSnapshot(
    val modifierId: String,
    val modifierGroupId: String? = null,
    val name: String,
    val priceDeltaMinor: Long,
    val qty: Int,
)

private val posLineSnapshotJson = Json { ignoreUnknownKeys = true }

fun encodeModifierSelections(rows: List<LocalModifierSelectionSnapshot>): String =
    posLineSnapshotJson.encodeToString(rows)

fun decodeModifierSelections(raw: String): List<LocalModifierSelectionSnapshot> =
    runCatching { posLineSnapshotJson.decodeFromString<List<LocalModifierSelectionSnapshot>>(raw) }
        .getOrDefault(emptyList())

/** Last successful sync per collection, for showing staleness in the UI. */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val lastSyncMillis: Long,
)

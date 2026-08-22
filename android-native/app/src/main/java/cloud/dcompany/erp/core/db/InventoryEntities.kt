package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ------------------------------------------------------------------ ingredients

/** Wholesale-replaced read cache — same shape as StaffCacheEntity. */
@Entity(tableName = "ingredient_cache")
data class IngredientCacheEntity(
    @PrimaryKey val id: String,
    val sku: String,
    val name: String,
    val baseUnit: String,
    val currentQty: Double,
    val reorderThreshold: Double,
    val reorderQty: Double,
    val avgCostMinor: Long,
)

object IngredientWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * One row per in-flight ingredient write — create, edit, and delete, same
 * three-way shape as LocalStaffEntity (`serverId == null` = still-unsynced
 * create; `pendingDelete` folds a soft-delete into the same row rather than a
 * second table). Unlike Staff, ingredients genuinely can be created offline —
 * there's no OTP-style live round trip blocking it — so `serverId` really can
 * be null here, matching LocalCustomerEntity's create/edit duality instead.
 *
 * `sku` is only ever populated on a create row: it's the one field the UI
 * locks after creation (`enabled = editing == null` in IngredientDialog), so
 * an edit row never needs to carry it.
 *
 * `DELETE /inventory/ingredients/{id}` 404s on an already-deleted ingredient
 * rather than no-op'ing (same shape as Staff's delete_user) — SyncEngine.
 * pushIngredientOne treats a 404 on the delete path as already-applied, not
 * a rejection, for the same reason documented on LocalStaffEntity.
 */
@Entity(tableName = "local_ingredients", indices = [Index("state"), Index("serverId")])
data class LocalIngredientEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val sku: String? = null,
    val name: String? = null,
    val baseUnit: String? = null,
    val reorderThreshold: Double? = null,
    val reorderQty: Double? = null,
    val pendingDelete: Boolean = false,
    val createdAtMillis: Long,
    val state: String = IngredientWriteState.PENDING,
    val lastError: String? = null,
    val version: Long = 0,
)

// --------------------------------------------------------------------- suppliers

@Entity(tableName = "supplier_cache")
data class SupplierCacheEntity(
    @PrimaryKey val id: String,
    val name: String,
    val contact: String?,
    val gstin: String?,
    val paymentTerms: String?,
)

object SupplierWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/** Same create/edit/pendingDelete shape as LocalIngredientEntity. */
@Entity(tableName = "local_suppliers", indices = [Index("state"), Index("serverId")])
data class LocalSupplierEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val name: String? = null,
    val contact: String? = null,
    val gstin: String? = null,
    val paymentTerms: String? = null,
    val pendingDelete: Boolean = false,
    val createdAtMillis: Long,
    val state: String = SupplierWriteState.PENDING,
    val lastError: String? = null,
    val version: Long = 0,
)

// ----------------------------------------------------------------------- batches

/**
 * Read-only, demand-loaded per ingredient (matches the existing screen's own
 * `loadBatches(ingredientId)` call shape) — never written to directly, only
 * ever replaced wholesale for one ingredient at a time when its detail panel
 * is opened, so a FIFO batch list stays visible offline instead of blanking.
 */
@Entity(tableName = "batch_cache", indices = [Index("ingredientId")])
data class BatchCacheEntity(
    @PrimaryKey val id: String,
    val ingredientId: String,
    val receivedAt: String,
    val expiresAt: String?,
    val qtyOnHand: Double,
    val costPerUnitMinor: Long,
    val lotCode: String?,
)

// --------------------------------------------------------------------------- GRN

/**
 * Shape D (header + lines, insert-only, never edited) — mirrors
 * LocalOrderEntity/LocalOrderLineEntity/OrderDao.capture() exactly. A GRN is
 * a historical receiving event, not a record anyone comes back to amend, so
 * unlike ingredients/suppliers there is no edit leg to design for.
 *
 * `branchId`/`supplierId`/each line's `ingredientId` must already be a real
 * server id by the time this is captured — the GRN dialog's own pickers
 * exclude any not-yet-synced (`local:`-prefixed) branch/supplier/ingredient,
 * the same dependency-sidestep Menu uses for item-create's category picker.
 * There is no id-resolution machinery here because there is nothing for it
 * to resolve.
 */
@Entity(tableName = "local_grns")
data class LocalGrnEntity(
    @PrimaryKey val localId: String,
    val branchId: String,
    val supplierId: String,
    val supplierInvoiceNo: String?,
    val notes: String?,
    val createdAtMillis: Long,
    val syncState: String = SyncState.PENDING,
    val lastError: String? = null,
)

@Entity(tableName = "local_grn_lines", indices = [Index("grnLocalId")])
data class LocalGrnLineEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val grnLocalId: String,
    val ingredientId: String,
    val qty: Double,
    val unitCostMinor: Long,
    val expiresAt: String?,
    val lotCode: String?,
)

// --------------------------------------------------------------------- adjustments

/**
 * Single-row, insert-only, never edited — a stock adjustment is a standalone
 * historical event exactly like a GRN header, just with no lines (one
 * ingredient, one delta, one movement). Same dependency-sidestep applies:
 * `ingredientId`/`branchId` must already be real server ids.
 */
@Entity(tableName = "local_adjustments")
data class LocalAdjustmentEntity(
    @PrimaryKey val localId: String,
    val ingredientId: String,
    val branchId: String,
    val qtyDelta: Double,
    val type: String,
    val note: String?,
    val createdAtMillis: Long,
    val syncState: String = SyncState.PENDING,
    val lastError: String? = null,
)

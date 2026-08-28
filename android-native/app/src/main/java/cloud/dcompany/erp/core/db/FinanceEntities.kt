package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Expenses, assets and partner capital entries are all immutable ledger
 * writes — create-only on the tablet. Corrections are separate authorised,
 * evidence-preserving actions; assets currently have no edit/delete endpoint.
 * That means every local outbox row here uses the insert-only
 * Shape D shape (mirrors LocalGrnEntity/LocalAdjustmentEntity — a plain
 * `syncState`, no `serverId`-null-vs-set duality, no `pendingDelete` flag,
 * no CAS `version`), not the master-data create/edit/delete shape
 * LocalIngredientEntity/LocalSupplierEntity use.
 */

// ------------------------------------------------------------------ expenses

/** Wholesale-replaced read cache — same shape as IngredientCacheEntity. */
@Entity(tableName = "expense_cache")
data class ExpenseCacheEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val categoryId: String,
    val supplierId: String?,
    val amountMinor: Long,
    val paidVia: String,
    val paidAt: String,
    val vendorName: String?,
    val invoiceNo: String?,
    val note: String?,
)

@Entity(tableName = "local_expenses")
data class LocalExpenseEntity(
    @PrimaryKey val localId: String,
    val branchId: String,
    val categoryId: String,
    val supplierId: String?,
    val amountMinor: Long,
    val paidVia: String,
    val paidAt: String,
    val vendorName: String?,
    val invoiceNo: String?,
    val note: String?,
    val createdAtMillis: Long,
    val syncState: String = SyncState.PENDING,
    val lastError: String? = null,
)

// --------------------------------------------------------------------- assets

@Entity(tableName = "asset_cache")
data class AssetCacheEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val name: String,
    val type: String,
    val purchaseMinor: Long,
    val purchaseDate: String,
    val usefulLifeMonths: Int,
    val salvageMinor: Long,
    val depreciationMethod: String,
    val notes: String?,
    // Recomputed server-side "as of now" on every read — never stored, so a
    // cached value here is only ever a snapshot from the last successful
    // pull, same trust level as an ingredient's current_qty.
    val accumulatedDepreciationMinor: Long,
    val bookValueMinor: Long,
)

@Entity(tableName = "local_assets")
data class LocalAssetEntity(
    @PrimaryKey val localId: String,
    val branchId: String,
    val name: String,
    val type: String,
    val purchaseMinor: Long,
    val purchaseDate: String,
    val usefulLifeMonths: Int,
    val salvageMinor: Long,
    val notes: String?,
    val createdAtMillis: Long,
    val syncState: String = SyncState.PENDING,
    val lastError: String? = null,
)

// ------------------------------------------------------------- capital entries

/**
 * Per-partner cache — same per-parent wholesale-replace shape as
 * BatchCacheEntity, pulled on demand when a partner's capital history is
 * opened, not as part of every sync().
 */
@Entity(tableName = "capital_entry_cache", indices = [Index("partnerId")])
data class CapitalEntryCacheEntity(
    @PrimaryKey val id: String,
    val partnerId: String,
    val type: String,
    val amountMinor: Long,
    val effectiveAt: String,
    val settlementAccount: String,
    val sourceRef: String?,
    val note: String?,
    val createdByName: String?,
    val createdAt: String,
    val voidedAt: String?,
    val voidReason: String?,
    val isVoided: Boolean,
)

/**
 * `sourceRef` is non-null and required here (unlike the cache row's nullable
 * mirror of the backend's optional column) — CapitalEntryCreate requires it
 * client-side too, matching the web form's own validation.
 */
@Entity(tableName = "local_capital_entries")
data class LocalCapitalEntryEntity(
    @PrimaryKey val localId: String,
    val partnerId: String,
    val type: String,
    val amountMinor: Long,
    val effectiveAt: String,
    val settlementAccount: String,
    val sourceRef: String,
    val note: String?,
    val createdAtMillis: Long,
    val syncState: String = SyncState.PENDING,
    val lastError: String? = null,
)

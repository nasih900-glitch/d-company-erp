package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
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
    val receiptCount: Int = 0,
    val receiptStatus: String = "pending",
    val shiftId: String? = null,
    val createdBy: String? = null,
    val isVoided: Boolean = false,
    val sourceShiftStatus: String? = null,
    val isCorrected: Boolean = false,
    val correctionReason: String? = null,
    val correctionAt: String? = null,
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
    /** Exact server shift selected for a cash paid-out. Null for every non-cash rail. */
    val shiftId: String? = null,
    /**
     * Origin of an unresolved cash row captured by the signed Code21 app before
     * cash expenses carried an explicit shift id. Migration 49 -> 50 sets this
     * only on preserved pending/rejected legacy rows. New writes leave it null
     * and use [shiftId].
     */
    val legacyOriginVersionCode: Int? = null,
    val serverId: String? = null,
    val syncState: String = SyncState.PENDING,
    val lastError: String? = null,
)

/**
 * Private receipt evidence captured with an expense. The bytes live in bounded
 * child rows in Room so process death, an app update, and a network outage
 * cannot detach the receipt from the immutable local expense action. The sync
 * worker clears those bytes after the server acknowledges the upload.
 */
@Entity(
    tableName = "local_expense_receipts",
    foreignKeys = [
        ForeignKey(
            entity = LocalExpenseEntity::class,
            parentColumns = ["localId"],
            childColumns = ["expenseLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("expenseLocalId"),
        Index(value = ["expenseLocalId", "contentSha256"], unique = true),
        Index("syncState"),
    ],
)
data class LocalExpenseReceiptEntity(
    @PrimaryKey val localId: String,
    val expenseLocalId: String,
    val filename: String,
    val contentType: String,
    val source: String,
    val byteSize: Int,
    val contentSha256: String,
    val createdAtMillis: Long,
    val syncState: String = SyncState.PENDING,
    val serverReceiptId: String? = null,
    val lastError: String? = null,
)

/** Bounded payload rows avoid Android's CursorWindow per-row BLOB ceiling for
 * valid receipts near the 10 MiB API limit. They are inserted and removed in
 * the same Room transactions as their receipt metadata. */
@Entity(
    tableName = "local_expense_receipt_chunks",
    primaryKeys = ["receiptLocalId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = LocalExpenseReceiptEntity::class,
            parentColumns = ["localId"],
            childColumns = ["receiptLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("receiptLocalId")],
)
data class LocalExpenseReceiptChunkEntity(
    val receiptLocalId: String,
    val chunkIndex: Int,
    val content: ByteArray,
)

/** Receipt plus the immutable branch/accounting parent fields needed to keep
 * the pending UI branch-scoped even after the expense header has synced. */
data class LocalExpenseReceiptWithExpense(
    @Embedded val receipt: LocalExpenseReceiptEntity,
    val expenseBranchId: String,
    val expenseServerId: String?,
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

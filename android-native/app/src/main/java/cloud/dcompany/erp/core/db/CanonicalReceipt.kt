package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CanonicalReceipt
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

const val RECEIPT_HISTORY_PAGE_SIZE = 50

/** Pagination state for the most recent server snapshot. Older cached rows remain readable offline. */
@Entity(tableName = "canonical_receipt_sync_state")
data class CanonicalReceiptSyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    /** Number of newest receipts whose server window has been fetched and is kept fresh. */
    val loadedCount: Int = 0,
    val fetchedAtMillis: Long,
    val unavailableMessage: String? = null,
) {
    init {
        require(id == SINGLETON_ID) { "Receipt sync state must use its singleton identity." }
        require(loadedCount >= 0) { "Receipt loaded count cannot be negative." }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}

/**
 * Replaceable cross-device receipt projection. This is intentionally not the
 * local PosReceiptEntity: local evidence is append-only and drives immediate
 * cashier acknowledgement/printing, while this cache may be refreshed from
 * any web or Android sale made in the same company and branch.
 */
@Entity(
    tableName = "canonical_pos_receipts",
    indices = [
        Index(value = ["companyId", "branchId", "invoiceIssuedAtMillis"]),
        Index("invoiceNo"),
    ],
)
data class CanonicalReceiptEntity(
    @PrimaryKey val orderId: String,
    val companyId: String,
    val branchId: String,
    val terminalId: String,
    val invoiceNo: String,
    val status: String,
    val orderType: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val invoiceIssuedAt: String,
    val invoiceIssuedAtMillis: Long,
    /** Complete canonical server response, including line and actor provenance. */
    val payloadJson: String,
    val fetchedAtMillis: Long,
)

@Dao
interface CanonicalReceiptDao {
    @Query(
        "SELECT * FROM canonical_pos_receipts " +
            "WHERE companyId = :companyId AND branchId = :branchId " +
            "ORDER BY invoiceIssuedAtMillis DESC, orderId DESC LIMIT :limit",
    )
    fun observeRecent(
        companyId: String,
        branchId: String,
        limit: Int,
    ): Flow<List<CanonicalReceiptEntity>>

    @Query(
        "SELECT * FROM canonical_pos_receipts " +
            "WHERE orderId = :orderId AND companyId = :companyId AND branchId = :branchId LIMIT 1",
    )
    suspend fun byOrderId(
        orderId: String,
        companyId: String,
        branchId: String,
    ): CanonicalReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(receipts: List<CanonicalReceiptEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: CanonicalReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncState(state: CanonicalReceiptSyncStateEntity)

    @Query("SELECT * FROM canonical_receipt_sync_state WHERE id = 1 LIMIT 1")
    fun observeSyncState(): Flow<CanonicalReceiptSyncStateEntity?>

    @Query("SELECT * FROM canonical_receipt_sync_state WHERE id = 1 LIMIT 1")
    suspend fun syncState(): CanonicalReceiptSyncStateEntity?

    @Transaction
    suspend fun storePage(
        receipts: List<CanonicalReceiptEntity>,
        syncState: CanonicalReceiptSyncStateEntity,
        expectedCompanyId: String,
        expectedBranchId: String,
    ) {
        require(
            receipts.all {
                it.companyId == expectedCompanyId && it.branchId == expectedBranchId
            },
        ) { "Receipt page belongs to another company or branch." }
        upsertAll(receipts)
        putSyncState(syncState)
    }
}

fun CanonicalReceipt.toCacheEntity(
    fetchedAtMillis: Long = System.currentTimeMillis(),
): CanonicalReceiptEntity {
    require(orderId.isNotBlank()) { "Receipt order identity is missing." }
    require(companyId.isNotBlank() && branchId.isNotBlank()) {
        "Receipt company or branch identity is missing."
    }
    val issuedAtMillis = runCatching { Instant.parse(invoiceIssuedAt).toEpochMilli() }
        .getOrElse { throw IllegalArgumentException("Receipt issue time is invalid.", it) }
    return CanonicalReceiptEntity(
        orderId = orderId,
        companyId = companyId,
        branchId = branchId,
        terminalId = terminalId,
        invoiceNo = invoiceNo,
        status = status,
        orderType = orderType,
        totalMinor = totalMinor,
        paidMinor = paidMinor,
        invoiceIssuedAt = invoiceIssuedAt,
        invoiceIssuedAtMillis = issuedAtMillis,
        payloadJson = ApiClient.json.encodeToString(this),
        fetchedAtMillis = fetchedAtMillis,
    )
}

fun CanonicalReceiptEntity.decodedReceipt(): CanonicalReceipt? = runCatching {
    ApiClient.json.decodeFromString<CanonicalReceipt>(payloadJson)
}.getOrNull()

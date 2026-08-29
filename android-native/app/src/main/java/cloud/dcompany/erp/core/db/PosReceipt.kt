package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.PaymentResult
import cloud.dcompany.erp.core.net.ZeroTotalFinalizationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object PosReceiptSource {
    const val DIRECT = "direct_pos"
    const val HELD = "held_order"
    const val OFFLINE_DIRECT = "offline_direct"
    const val ZERO_DIRECT = "zero_direct"
    const val ZERO_HELD = "zero_held"
}

/**
 * Immutable local receipt evidence. The backend remains authoritative, but a
 * cashier must still be able to reopen the exact bill after the paid order has
 * left the held queue or the direct cart has been cleared.
 */
@Entity(
    tableName = "pos_receipts",
    indices = [
        Index("orderId"),
        Index("paymentId"),
        Index("createdAtMillis"),
        Index("acknowledgedAtMillis"),
    ],
)
data class PosReceiptEntity(
    @PrimaryKey val receiptId: String,
    val orderId: String,
    val paymentId: String?,
    val shiftId: String?,
    val sourceKind: String,
    val sourceLabel: String?,
    val customerName: String?,
    val customerPhone: String?,
    val orderNote: String?,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val taxMinor: Long,
    val roundOffMinor: Long,
    val totalMinor: Long,
    /** Balance shown immediately before this settlement was confirmed. */
    val dueBeforePaymentMinor: Long,
    val method: String,
    val amountMinor: Long,
    val billAmountMinor: Long,
    val tipMinor: Long,
    val tenderedMinor: Long?,
    val changeMinor: Long?,
    val refExternal: String?,
    val paidAt: String?,
    val orderStatus: String,
    val invoiceNo: String?,
    val fiscalYear: String?,
    val invoiceIssuedAt: String?,
    /** JSON-encoded immutable server line snapshots, including variants/modifiers/tax. */
    val linesJson: String,
    val createdAtMillis: Long,
    val acknowledgedAtMillis: Long? = null,
)

@Dao
interface PosReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: PosReceiptEntity): Long

    @Query("SELECT * FROM pos_receipts WHERE receiptId = :receiptId LIMIT 1")
    suspend fun byId(receiptId: String): PosReceiptEntity?

    @Query("SELECT * FROM pos_receipts ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<PosReceiptEntity>>

    @Query(
        "SELECT * FROM pos_receipts WHERE acknowledgedAtMillis IS NULL " +
            "ORDER BY createdAtMillis ASC LIMIT 1",
    )
    fun observeOldestUnacknowledged(): Flow<PosReceiptEntity?>

    @Query(
        "UPDATE pos_receipts SET acknowledgedAtMillis = :acknowledgedAtMillis " +
            "WHERE receiptId = :receiptId AND acknowledgedAtMillis IS NULL",
    )
    suspend fun acknowledge(receiptId: String, acknowledgedAtMillis: Long): Int

    @Query(
        "UPDATE local_held_order_payments SET syncState = 'synced', lastError = NULL, " +
            "claimToken = NULL WHERE localId = :localPaymentId",
    )
    suspend fun markSettlementSynced(localPaymentId: String): Int

    @Query(
        "UPDATE local_orders SET syncState = 'synced', serverOrderId = :serverOrderId, " +
            "invoiceNo = :invoiceNo, serverTotalMinor = :totalMinor, lastError = NULL " +
            "WHERE localId = :localOrderId",
    )
    suspend fun markLocalSaleSynced(
        localOrderId: String,
        serverOrderId: String,
        invoiceNo: String?,
        totalMinor: Long,
    ): Int

    @Transaction
    suspend fun store(receipt: PosReceiptEntity) {
        val inserted = insert(receipt)
        if (inserted == -1L) {
            val existing = byId(receipt.receiptId)
            check(
                existing?.orderId == receipt.orderId && existing.paymentId == receipt.paymentId,
            ) { "Receipt identity collision for ${receipt.receiptId}" }
        }
    }

    @Transaction
    suspend fun storeAndMarkSettlementSynced(
        receipt: PosReceiptEntity,
        localPaymentId: String,
    ) {
        store(receipt)
        check(markSettlementSynced(localPaymentId) == 1) {
            "The receipt was saved but its local settlement row was not found."
        }
    }

    @Transaction
    suspend fun storeAndMarkLocalSaleSynced(
        receipt: PosReceiptEntity,
        localOrderId: String,
        serverOrderId: String,
        invoiceNo: String?,
        totalMinor: Long,
    ) {
        store(receipt)
        check(
            markLocalSaleSynced(
                localOrderId = localOrderId,
                serverOrderId = serverOrderId,
                invoiceNo = invoiceNo,
                totalMinor = totalMinor,
            ) == 1,
        ) { "The receipt was saved but its local sale row was not found." }
    }
}

private val POS_RECEIPT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

fun PosReceiptEntity.decodedLines() = runCatching {
    POS_RECEIPT_JSON.decodeFromString<List<cloud.dcompany.erp.core.net.OrderLine>>(linesJson)
}.getOrDefault(emptyList())

fun paymentReceipt(
    order: Order,
    payment: PaymentResult,
    sourceKind: String,
    sourceLabel: String? = null,
    createdAtMillis: Long = System.currentTimeMillis(),
): PosReceiptEntity = PosReceiptEntity(
    receiptId = payment.id,
    orderId = payment.orderId.ifBlank { order.id },
    paymentId = payment.id,
    shiftId = payment.shiftId.takeIf(String::isNotBlank),
    sourceKind = sourceKind,
    sourceLabel = sourceLabel ?: order.sourceLabel,
    customerName = order.customerName,
    customerPhone = order.customerPhone,
    orderNote = order.notes,
    subtotalMinor = order.subtotalMinor,
    discountMinor = order.discountMinor,
    taxMinor = order.taxMinor,
    roundOffMinor = order.roundOffMinor,
    totalMinor = order.totalMinor,
    dueBeforePaymentMinor = payment.billAmountMinor,
    method = payment.method,
    amountMinor = payment.amountMinor,
    billAmountMinor = payment.billAmountMinor,
    tipMinor = payment.tipMinor,
    tenderedMinor = payment.tenderedMinor,
    changeMinor = payment.changeMinor,
    refExternal = payment.refExternal,
    paidAt = payment.paidAt,
    orderStatus = payment.orderStatus ?: order.status,
    invoiceNo = payment.invoiceNo ?: order.invoiceNo,
    fiscalYear = payment.fiscalYear,
    invoiceIssuedAt = payment.invoiceIssuedAt,
    linesJson = POS_RECEIPT_JSON.encodeToString(order.lines),
    createdAtMillis = createdAtMillis,
)

fun zeroTotalReceipt(
    order: Order,
    result: ZeroTotalFinalizationResult,
    sourceKind: String,
    sourceLabel: String? = null,
    shiftId: String? = null,
    createdAtMillis: Long = System.currentTimeMillis(),
): PosReceiptEntity = PosReceiptEntity(
    receiptId = "zero:${result.orderId}:${result.invoiceNo.orEmpty()}",
    orderId = result.orderId,
    paymentId = null,
    shiftId = shiftId,
    sourceKind = sourceKind,
    sourceLabel = sourceLabel ?: order.sourceLabel,
    customerName = order.customerName,
    customerPhone = order.customerPhone,
    orderNote = order.notes,
    subtotalMinor = order.subtotalMinor,
    discountMinor = order.discountMinor,
    taxMinor = order.taxMinor,
    roundOffMinor = order.roundOffMinor,
    totalMinor = order.totalMinor,
    dueBeforePaymentMinor = 0,
    method = "benefit",
    amountMinor = 0,
    billAmountMinor = 0,
    tipMinor = 0,
    tenderedMinor = null,
    changeMinor = null,
    refExternal = null,
    paidAt = result.invoiceIssuedAt,
    orderStatus = result.orderStatus,
    invoiceNo = result.invoiceNo ?: order.invoiceNo,
    fiscalYear = result.fiscalYear,
    invoiceIssuedAt = result.invoiceIssuedAt,
    linesJson = POS_RECEIPT_JSON.encodeToString(order.lines),
    createdAtMillis = createdAtMillis,
)

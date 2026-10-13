package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Replaceable, tenant-scoped server projection. Rules stay as canonical JSON
 * so an older app never silently fills newly added economic fields. */
@Entity(tableName = "loyalty_program_cache")
data class LoyaltyProgramCacheEntity(
    @PrimaryKey val companyId: String,
    val revision: Long,
    val rulesJson: String,
    val publishedAt: String,
    val loadedAtMillis: Long,
)

/** Exact request captured before a points mutation crosses the network.
 * Nullable programRevision identifies a byte-for-byte legacy body; it must
 * never be filled from a newer cached program during retry. */
@Entity(
    tableName = "local_points_redemption_actions",
    indices = [Index("state"), Index("shiftId"), Index("orderId")],
)
data class LocalPointsRedemptionActionEntity(
    @PrimaryKey val actionId: String,
    val ownerCompanyId: String,
    val ownerUserId: String,
    val shiftId: String,
    val orderId: String,
    val localOrderId: String,
    val actionKind: String,
    val points: Int?,
    val rewardKey: String?,
    val programRevision: Long?,
    val expectedCheckoutVersion: Long,
    val state: String,
    val createdAtMillis: Long,
    val lastError: String? = null,
)

object LoyaltyActionState {
    const val PENDING = "pending"
    const val AMBIGUOUS = "ambiguous"
    const val REJECTED = "rejected"
    const val SYNCED = "synced"
}

@Dao
interface LoyaltyDao {
    @Query("SELECT * FROM loyalty_program_cache WHERE companyId = :companyId LIMIT 1")
    fun observeProgram(companyId: String): Flow<LoyaltyProgramCacheEntity?>

    @Query("SELECT * FROM loyalty_program_cache WHERE companyId = :companyId LIMIT 1")
    suspend fun program(companyId: String): LoyaltyProgramCacheEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgramIfAbsent(program: LoyaltyProgramCacheEntity): Long

    @Query(
        "UPDATE loyalty_program_cache SET revision = :revision, rulesJson = :rulesJson, " +
            "publishedAt = :publishedAt, loadedAtMillis = :loadedAtMillis " +
            "WHERE companyId = :companyId AND revision <= :revision",
    )
    suspend fun updateProgramIfNotNewer(
        companyId: String,
        revision: Long,
        rulesJson: String,
        publishedAt: String,
        loadedAtMillis: Long,
    ): Int

    @Transaction
    suspend fun acceptProgramMonotonically(program: LoyaltyProgramCacheEntity) {
        if (insertProgramIfAbsent(program) == -1L) {
            updateProgramIfNotNewer(
                companyId = program.companyId,
                revision = program.revision,
                rulesJson = program.rulesJson,
                publishedAt = program.publishedAt,
                loadedAtMillis = program.loadedAtMillis,
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAction(action: LocalPointsRedemptionActionEntity)

    @Query(
        "SELECT * FROM local_points_redemption_actions WHERE orderId = :orderId " +
            "AND state != 'synced' ORDER BY createdAtMillis DESC LIMIT 1",
    )
    suspend fun unresolvedForOrder(orderId: String): LocalPointsRedemptionActionEntity?

    @Query("SELECT * FROM local_points_redemption_actions WHERE actionId = :actionId LIMIT 1")
    suspend fun action(actionId: String): LocalPointsRedemptionActionEntity?

    @Query(
        "UPDATE local_points_redemption_actions SET state = :state, lastError = :error " +
            "WHERE actionId = :actionId",
    )
    suspend fun markAction(actionId: String, state: String, error: String?): Int

    /** A definitive server refusal can retire only after its canonical bill
     * has been checkpointed in the same database transaction. Ambiguous
     * actions deliberately cannot pass this conditional transition. */
    @Query(
        "UPDATE local_points_redemption_actions SET state = 'synced' " +
            "WHERE actionId = :actionId AND state = 'rejected' " +
            "AND ownerCompanyId = :ownerCompanyId AND ownerUserId = :ownerUserId " +
            "AND localOrderId = :localOrderId AND orderId = :orderId",
    )
    suspend fun retireRejectedAfterCanonicalCheckpoint(
        actionId: String,
        ownerCompanyId: String,
        ownerUserId: String,
        localOrderId: String,
        orderId: String,
    ): Int

    /** Exact idempotent replay plus a fresh terminal canonical read can retire
     * an uncertain action, but never one from another workspace or bill. */
    @Query(
        "UPDATE local_points_redemption_actions SET state = 'synced', lastError = NULL " +
            "WHERE actionId = :actionId AND state IN ('pending','ambiguous') " +
            "AND ownerCompanyId = :ownerCompanyId AND ownerUserId = :ownerUserId " +
            "AND localOrderId = :localOrderId AND orderId = :orderId",
    )
    suspend fun retireReconciledAfterCanonicalCheckpoint(
        actionId: String,
        ownerCompanyId: String,
        ownerUserId: String,
        localOrderId: String,
        orderId: String,
    ): Int
}

package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuDao {

    @Query("SELECT * FROM menu_items WHERE isAvailable = 1 ORDER BY name")
    fun observeItems(): Flow<List<MenuItemEntity>>

    /** Unlike [observeItems], includes unavailable items — the Menu admin
     * screen needs to show (and re-enable) a "sold out" item, unlike POS. */
    @Query("SELECT * FROM menu_items ORDER BY name")
    fun observeAllItems(): Flow<List<MenuItemEntity>>

    @Query("SELECT * FROM menu_categories ORDER BY sortOrder, name")
    fun observeCategories(): Flow<List<MenuCategoryEntity>>

    @Query("SELECT COUNT(*) FROM menu_items")
    suspend fun itemCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<MenuItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<MenuCategoryEntity>)

    @Query("DELETE FROM menu_items WHERE id NOT IN (:keepIds)")
    suspend fun deleteItemsNotIn(keepIds: List<String>)

    @Query("DELETE FROM menu_categories WHERE id NOT IN (:keepIds)")
    suspend fun deleteCategoriesNotIn(keepIds: List<String>)

    /**
     * Replaces the cached menu in one transaction. Deleting first and
     * inserting after would leave the till with an empty menu if the process
     * died in between; this way a reader always sees a complete menu.
     */
    @Transaction
    suspend fun replaceMenu(items: List<MenuItemEntity>, categories: List<MenuCategoryEntity>) {
        upsertCategories(categories)
        upsertItems(items)
        // Withdrawn items must disappear, or staff keep selling something the
        // kitchen no longer has.
        deleteCategoriesNotIn(categories.map { it.id }.ifEmpty { listOf("") })
        deleteItemsNotIn(items.map { it.id }.ifEmpty { listOf("") })
    }
}

@Dao
interface OrderDao {

    @Insert
    suspend fun insertOrder(order: LocalOrderEntity)

    @Insert
    suspend fun insertLines(lines: List<LocalOrderLineEntity>)

    @Transaction
    suspend fun capture(order: LocalOrderEntity, lines: List<LocalOrderLineEntity>) {
        insertOrder(order)
        insertLines(lines)
    }

    @Query("SELECT * FROM local_orders WHERE syncState = :state ORDER BY createdAtMillis ASC")
    suspend fun byState(state: String = SyncState.PENDING): List<LocalOrderEntity>

    @Query("SELECT * FROM local_order_lines WHERE orderLocalId = :localId")
    suspend fun linesFor(localId: String): List<LocalOrderLineEntity>

    @Query("SELECT * FROM local_orders ORDER BY createdAtMillis DESC LIMIT 100")
    fun observeRecent(): Flow<List<LocalOrderEntity>>

    /**
     * Definitive server refusals are deliberately excluded from the automatic
     * outbox drain. Keep the complete list observable so the till can show the
     * exact captured payment that needs a human decision instead of reducing
     * it to an anonymous counter.
     */
    @Query("SELECT * FROM local_orders WHERE syncState = 'rejected' ORDER BY createdAtMillis DESC")
    fun observeRejected(): Flow<List<LocalOrderEntity>>

    /** Observe one captured sale until the server either confirms or refuses it. */
    @Query("SELECT * FROM local_orders WHERE localId = :localId LIMIT 1")
    fun observeByLocalId(localId: String): Flow<LocalOrderEntity?>

    @Query("SELECT COUNT(*) FROM local_orders WHERE syncState = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_orders WHERE syncState = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    @Query(
        """UPDATE local_orders
           SET syncState = :state, serverOrderId = :serverId,
               invoiceNo = :invoiceNo, serverTotalMinor = :totalMinor, lastError = NULL
           WHERE localId = :localId""",
    )
    suspend fun markSynced(
        localId: String,
        state: String = SyncState.SYNCED,
        serverId: String?,
        invoiceNo: String?,
        totalMinor: Long?,
    )

    @Query("UPDATE local_orders SET syncState = :state, lastError = :error WHERE localId = :localId")
    suspend fun markRejected(
        localId: String,
        error: String,
        state: String = SyncState.REJECTED,
    )

    /**
     * Human-authorised replay after the refusal's cause has been fixed.
     *
     * This mutates the original row rather than cloning it: [localId] is also
     * the stable order/payment idempotency identity used by SyncEngine. The
     * state guard makes double taps and stale UI actions no-ops.
     */
    @Query(
        """UPDATE local_orders
           SET syncState = 'pending', lastError = NULL
           WHERE localId = :localId AND syncState = 'rejected'""",
    )
    suspend fun retryRejected(localId: String): Int
}

@Dao
interface SyncMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: SyncMetaEntity)

    @Query("SELECT * FROM sync_meta WHERE key = :key")
    fun observe(key: String): Flow<SyncMetaEntity?>
}

@Database(
    entities = [
        MenuItemEntity::class,
        MenuCategoryEntity::class,
        LocalOrderEntity::class,
        LocalOrderLineEntity::class,
        SyncMetaEntity::class,
        LocalShiftEntity::class,
        GamingStationEntity::class,
        GamingSessionCacheEntity::class,
        LocalGamingSessionEntity::class,
        KitchenOrderCacheEntity::class,
        LocalKitchenAdvanceEntity::class,
        FloorEntity::class,
        CafeTableEntity::class,
        LocalTableOrderEntity::class,
        RefundOrderCacheEntity::class,
        LocalRefundEntity::class,
        ReportSnapshotEntity::class,
        CustomerCacheEntity::class,
        LocalCustomerEntity::class,
        LocalMenuCategoryEntity::class,
        LocalMenuItemEntity::class,
        StaffCacheEntity::class,
        LocalStaffEntity::class,
        OnShiftEntity::class,
        IngredientCacheEntity::class,
        LocalIngredientEntity::class,
        SupplierCacheEntity::class,
        LocalSupplierEntity::class,
        BatchCacheEntity::class,
        LocalGrnEntity::class,
        LocalGrnLineEntity::class,
        LocalAdjustmentEntity::class,
        ExpenseCacheEntity::class,
        LocalExpenseEntity::class,
        AssetCacheEntity::class,
        LocalAssetEntity::class,
        CapitalEntryCacheEntity::class,
        LocalCapitalEntryEntity::class,
        EventCacheEntity::class,
        EventTicketCacheEntity::class,
        LocalTicketSaleEntity::class,
        LocalCheckInEntity::class,
        MembershipTierCacheEntity::class,
        CustomerMembershipCacheEntity::class,
        CustomerMembershipHistoryCacheEntity::class,
        LocalSubscriptionEntity::class,
        LocalMembershipCancellationEntity::class,
        LocalMembershipRefundEntity::class,
        MembershipPaymentTaskCacheEntity::class,
        LocalMembershipPaymentActionEntity::class,
        MembershipRefundTaskCacheEntity::class,
        LocalMembershipRefundActionEntity::class,
        MembershipRefundAttemptCacheEntity::class,
        CompanyCacheEntity::class,
        LocalCompanyEditEntity::class,
        BranchCacheEntity::class,
        LocalBranchEntity::class,
        TerminalCacheEntity::class,
        LocalTerminalEntity::class,
        HeldOrderCacheEntity::class,
        LocalHeldOrderPaymentEntity::class,
        ServerOpenShiftEntity::class,
        ShiftHistoryCacheEntity::class,
        CafeBillCacheEntity::class,
        LocalCafeBillEntity::class,
        LocalCafeActionEntity::class,
        LocalKitchenCancellationAckEntity::class,
    ],
    version = 26,
    exportSchema = true,
)
@TypeConverters(
    KitchenLineListConverter::class,
    KitchenCancellationListConverter::class,
    TableOrderLineListConverter::class,
    CafeOrderConverters::class,
)
abstract class ErpDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun menuWriteDao(): MenuWriteDao
    abstract fun orderDao(): OrderDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun shiftDao(): ShiftDao
    abstract fun gamingDao(): GamingDao
    abstract fun kitchenDao(): KitchenDao
    abstract fun tablesDao(): TablesDao
    abstract fun cafeOrderDao(): CafeOrderDao
    abstract fun refundDao(): RefundDao
    abstract fun reportSnapshotDao(): ReportSnapshotDao
    abstract fun customerDao(): CustomerDao
    abstract fun staffDao(): StaffDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun financeDao(): FinanceDao
    abstract fun eventDao(): EventDao
    abstract fun membershipDao(): MembershipDao
    abstract fun membershipPaymentDao(): MembershipPaymentDao
    abstract fun membershipRefundMoneyDao(): MembershipRefundMoneyDao
    abstract fun settingsDao(): SettingsDao
    abstract fun heldOrderDao(): HeldOrderDao
    abstract fun outboxSafetyDao(): OutboxSafetyDao
    abstract fun shiftCloseSafetyDao(): ShiftCloseSafetyDao
    abstract fun cacheIsolationDao(): CacheIsolationDao
}

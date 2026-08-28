package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
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

    @Query("SELECT * FROM menu_variants ORDER BY menuItemId, sortOrder, name")
    fun observeVariants(): Flow<List<MenuVariantEntity>>

    @Query("SELECT * FROM menu_modifier_groups ORDER BY menuItemId, sortOrder, name")
    fun observeModifierGroups(): Flow<List<MenuModifierGroupEntity>>

    @Query("SELECT * FROM menu_modifiers ORDER BY menuItemId, sortOrder, name")
    fun observeModifiers(): Flow<List<MenuModifierEntity>>

    @Query("SELECT COUNT(*) FROM menu_items")
    suspend fun itemCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<MenuItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<MenuCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariants(variants: List<MenuVariantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModifierGroups(groups: List<MenuModifierGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModifiers(modifiers: List<MenuModifierEntity>)

    @Query("DELETE FROM menu_items WHERE id NOT IN (:keepIds)")
    suspend fun deleteItemsNotIn(keepIds: List<String>)

    @Query("DELETE FROM menu_categories WHERE id NOT IN (:keepIds)")
    suspend fun deleteCategoriesNotIn(keepIds: List<String>)

    @Query("DELETE FROM menu_variants WHERE id NOT IN (:keepIds)")
    suspend fun deleteVariantsNotIn(keepIds: List<String>)

    @Query("DELETE FROM menu_modifier_groups WHERE id NOT IN (:keepIds)")
    suspend fun deleteModifierGroupsNotIn(keepIds: List<String>)

    @Query("DELETE FROM menu_modifiers WHERE id NOT IN (:keepIds)")
    suspend fun deleteModifiersNotIn(keepIds: List<String>)

    /**
     * Replaces the cached menu in one transaction. Deleting first and
     * inserting after would leave the till with an empty menu if the process
     * died in between; this way a reader always sees a complete menu.
     */
    @Transaction
    suspend fun replaceMenu(
        items: List<MenuItemEntity>,
        categories: List<MenuCategoryEntity>,
        variants: List<MenuVariantEntity> = emptyList(),
        modifierGroups: List<MenuModifierGroupEntity> = emptyList(),
        modifiers: List<MenuModifierEntity> = emptyList(),
    ) {
        upsertCategories(categories)
        upsertItems(items)
        upsertVariants(variants)
        upsertModifierGroups(modifierGroups)
        upsertModifiers(modifiers)
        // Withdrawn items must disappear, or staff keep selling something the
        // kitchen no longer has.
        deleteCategoriesNotIn(categories.map { it.id }.ifEmpty { listOf("") })
        deleteItemsNotIn(items.map { it.id }.ifEmpty { listOf("") })
        deleteVariantsNotIn(variants.map { it.id }.ifEmpty { listOf("") })
        deleteModifierGroupsNotIn(modifierGroups.map { it.id }.ifEmpty { listOf("") })
        deleteModifiersNotIn(modifiers.map { it.id }.ifEmpty { listOf("") })
    }
}

data class LocalOrderWithLines(
    @Embedded val order: LocalOrderEntity,
    @Relation(parentColumn = "localId", entityColumn = "orderLocalId")
    val lines: List<LocalOrderLineEntity>,
)

@Dao
interface OrderDao {

    @Insert
    suspend fun insertOrder(order: LocalOrderEntity)

    @Insert
    suspend fun insertLines(lines: List<LocalOrderLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: LocalOrderEntity)

    @Query("DELETE FROM local_order_lines WHERE orderLocalId = :localId")
    suspend fun deleteLines(localId: String)

    @Query("DELETE FROM local_orders WHERE localId = :localId")
    suspend fun deleteOrder(localId: String)

    @Query("SELECT syncState FROM local_orders WHERE localId = :localId LIMIT 1")
    suspend fun stateFor(localId: String): String?

    @Transaction
    suspend fun capture(order: LocalOrderEntity, lines: List<LocalOrderLineEntity>) {
        insertOrder(order)
        insertLines(lines)
    }

    /** One durable cart/preparation row per scoped tablet workspace. */
    @Transaction
    suspend fun saveDraft(order: LocalOrderEntity, lines: List<LocalOrderLineEntity>) {
        val existingState = stateFor(order.localId)
        check(existingState == null || existingState == SyncState.DRAFT) {
            "A captured or prepared POS order cannot be overwritten by an older cart edit."
        }
        check(order.syncState == SyncState.DRAFT) {
            "saveDraft accepts editable drafts only."
        }
        upsertOrder(order)
        deleteLines(order.localId)
        insertLines(lines)
    }

    @Transaction
    suspend fun deleteDraft(localId: String) {
        deleteLines(localId)
        deleteOrder(localId)
    }

    @Transaction
    @Query(
        "SELECT * FROM local_orders WHERE syncState IN " +
            "('draft', 'preparing', 'awaiting_payment') ORDER BY updatedAtMillis DESC LIMIT 1",
    )
    fun observeActiveDraft(): Flow<LocalOrderWithLines?>

    @Transaction
    @Query(
        "SELECT * FROM local_orders WHERE syncState IN " +
            "('draft', 'preparing', 'awaiting_payment') ORDER BY updatedAtMillis DESC LIMIT 1",
    )
    suspend fun activeDraft(): LocalOrderWithLines?

    @Transaction
    @Query("SELECT * FROM local_orders WHERE localId = :localId LIMIT 1")
    suspend fun withLines(localId: String): LocalOrderWithLines?

    @Query(
        "UPDATE local_orders SET syncState = :state, lastError = :error, " +
            "updatedAtMillis = :updatedAtMillis WHERE localId = :localId",
    )
    suspend fun updateDraftState(
        localId: String,
        state: String,
        updatedAtMillis: Long,
        error: String? = null,
    ): Int

    /**
     * Persist the server identity immediately after idempotent creation. If the
     * app dies while applying customer/discount metadata, recovery reads this
     * identity instead of risking a second logical bill.
     */
    @Query(
        "UPDATE local_orders SET serverOrderId = :serverOrderId, shiftId = :serverShiftId, " +
            "serverSubtotalMinor = :subtotalMinor, serverDiscountMinor = :discountMinor, " +
            "serverPointsRedeemedMinor = :pointsRedeemedMinor, " +
            "serverPointsRedeemed = :pointsRedeemed, " +
            "serverTaxMinor = :taxMinor, serverRoundOffMinor = :roundOffMinor, " +
            "serverTotalMinor = :totalMinor, serverDueMinor = :dueMinor, " +
            "checkoutVersion = :checkoutVersion, syncState = 'preparing', lastError = NULL, " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE localId = :localId AND syncState IN ('preparing', 'awaiting_payment')",
    )
    suspend fun checkpointServerDraft(
        localId: String,
        serverOrderId: String,
        serverShiftId: String,
        subtotalMinor: Long,
        discountMinor: Long,
        pointsRedeemedMinor: Long,
        pointsRedeemed: Int,
        taxMinor: Long,
        roundOffMinor: Long,
        totalMinor: Long,
        dueMinor: Long,
        checkoutVersion: Long,
        updatedAtMillis: Long,
    ): Int

    /**
     * Freeze the optimistic version used by the first manual-discount request.
     * An ambiguous retry must replay the exact same body and idempotency key;
     * replacing this value with a later checkout version would make the server
     * correctly reject the replay as a different request.
     */
    @Query(
        "UPDATE local_orders SET discountRequestVersion = " +
            "COALESCE(discountRequestVersion, :requestVersion), updatedAtMillis = :updatedAtMillis " +
            "WHERE localId = :localId AND syncState IN ('preparing', 'awaiting_payment')",
    )
    suspend fun preserveDiscountRequestVersion(
        localId: String,
        requestVersion: Long,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_orders SET serverOrderId = :serverOrderId, serverTotalMinor = :totalMinor, " +
            "serverSubtotalMinor = :subtotalMinor, serverDiscountMinor = :discountMinor, " +
            "serverPointsRedeemedMinor = :pointsRedeemedMinor, " +
            "serverPointsRedeemed = :pointsRedeemed, " +
            "serverTaxMinor = :taxMinor, serverRoundOffMinor = :roundOffMinor, " +
            "serverDueMinor = :dueMinor, checkoutClaimToken = :claimToken, " +
            "checkoutClaimExpiresAtMillis = :claimExpiresAtMillis, checkoutVersion = :checkoutVersion, " +
            "syncState = 'awaiting_payment', lastError = NULL, updatedAtMillis = :updatedAtMillis " +
            "WHERE localId = :localId AND syncState IN ('preparing', 'awaiting_payment')",
    )
    suspend fun markDraftPrepared(
        localId: String,
        serverOrderId: String,
        subtotalMinor: Long,
        discountMinor: Long,
        pointsRedeemedMinor: Long,
        pointsRedeemed: Int,
        taxMinor: Long,
        roundOffMinor: Long,
        totalMinor: Long,
        dueMinor: Long,
        claimToken: String?,
        claimExpiresAtMillis: Long,
        checkoutVersion: Long,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_orders SET paymentMethod = :method, tenderedMinor = :tenderedMinor, " +
            "capturedAmountMinor = :expectedDueMinor, syncState = 'pending', " +
            "lastError = NULL, updatedAtMillis = :updatedAtMillis " +
            "WHERE localId = :localId AND syncState = 'draft' " +
            "AND revision = :expectedRevision " +
            "AND (estimateMinor - manualDiscountMinor) = :expectedDueMinor " +
            "AND capturedAmountMinor IS NULL",
    )
    suspend fun captureOfflineDraft(
        localId: String,
        expectedRevision: Long,
        expectedDueMinor: Long,
        method: String,
        tenderedMinor: Long,
        updatedAtMillis: Long,
    ): Int

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

    @Query("SELECT * FROM sync_meta WHERE key = :key LIMIT 1")
    suspend fun get(key: String): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta WHERE key = :key")
    fun observe(key: String): Flow<SyncMetaEntity?>

    @Query("DELETE FROM sync_meta WHERE key = :key")
    suspend fun delete(key: String): Int
}

@Database(
    entities = [
        MenuItemEntity::class,
        MenuCategoryEntity::class,
        MenuVariantEntity::class,
        MenuModifierGroupEntity::class,
        MenuModifierEntity::class,
        LocalOrderEntity::class,
        LocalOrderLineEntity::class,
        SyncMetaEntity::class,
        LocalShiftEntity::class,
        GamingStationEntity::class,
        GamingPackageCacheEntity::class,
        GamingSessionCacheEntity::class,
        LocalGamingSessionEntity::class,
        LocalGamingPackageExtensionEntity::class,
        KitchenOrderCacheEntity::class,
        LocalKitchenAdvanceEntity::class,
        FloorEntity::class,
        CafeTableEntity::class,
        LocalTableOrderEntity::class,
        RefundOrderCacheEntity::class,
        LocalRefundEntity::class,
        ReportSnapshotEntity::class,
        CustomerCacheEntity::class,
        CustomerOrderHistoryEntity::class,
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
        PosReceiptEntity::class,
        LocalBugReportEntity::class,
        LocalBugReportAttachmentEntity::class,
    ],
    version = 38,
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
    abstract fun posReceiptDao(): PosReceiptDao
    abstract fun outboxSafetyDao(): OutboxSafetyDao
    abstract fun shiftCloseSafetyDao(): ShiftCloseSafetyDao
    abstract fun cacheIsolationDao(): CacheIsolationDao
    abstract fun bugReportDao(): BugReportDao
}

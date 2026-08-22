package cloud.dcompany.erp.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every migration here is additive-only (CREATE TABLE, no ALTER/DROP against
 * existing rows) — this database holds captured sales and shift state that
 * exist nowhere else until synced, and `ErpDatabase` deliberately has no
 * destructive fallback. A migration that loses data on a device already in
 * the field is not recoverable the way a server-side mistake would be.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_shifts` (
                `localId` TEXT NOT NULL,
                `serverShiftId` TEXT,
                `openingFloatMinor` INTEGER NOT NULL,
                `openedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `countedMinor` INTEGER,
                `closedAtMillis` INTEGER,
                `varianceMinor` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_shifts_state` ON `local_shifts` (`state`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_shifts_openedAtMillis` ON `local_shifts` (`openedAtMillis`)",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `gaming_stations` (
                `id` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `ratePerHourMinor` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `gaming_session_cache` (
                `id` TEXT NOT NULL,
                `stationId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `startAtMillis` INTEGER NOT NULL,
                `endAtMillis` INTEGER,
                `timerMinutes` INTEGER,
                `timerEndsAtMillis` INTEGER,
                `billableMinutes` INTEGER,
                `amountMinor` INTEGER,
                `customerName` TEXT,
                `customerPhone` TEXT,
                `orderId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_gaming_sessions` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `stationId` TEXT NOT NULL,
                `shiftId` TEXT,
                `customerPhone` TEXT,
                `timerMinutes` INTEGER,
                `startedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `endAtMillis` INTEGER,
                `timerEndsAtMillis` INTEGER,
                `billableMinutes` INTEGER,
                `amountMinor` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_gaming_sessions_state` ON `local_gaming_sessions` (`state`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `kitchen_order_cache` (
                `id` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `type` TEXT NOT NULL,
                `tableCode` TEXT,
                `customerName` TEXT,
                `openedAt` TEXT,
                `kitchenState` TEXT NOT NULL,
                `minutesWaiting` INTEGER NOT NULL,
                `lines` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_kitchen_advances` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT NOT NULL,
                `targetState` TEXT NOT NULL,
                `requestedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_kitchen_advances_orderId` ON `local_kitchen_advances` (`orderId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cafe_floors` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cafe_tables` (
                `id` TEXT NOT NULL,
                `floorId` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `seats` INTEGER NOT NULL,
                `shape` TEXT NOT NULL,
                `x` REAL NOT NULL,
                `y` REAL NOT NULL,
                `status` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_table_orders` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT,
                `tableId` TEXT NOT NULL,
                `tableCode` TEXT NOT NULL,
                `shiftId` TEXT NOT NULL,
                `lines` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_table_orders_state` ON `local_table_orders` (`state`)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `refund_order_cache` (
                `id` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `status` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `totalMinor` INTEGER NOT NULL,
                `paidMinor` INTEGER NOT NULL,
                `refundableMinor` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_refunds` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `reasonCode` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `settlementMethod` TEXT,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_refunds_state` ON `local_refunds` (`state`)",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `report_snapshots` (
                `key` TEXT NOT NULL,
                `jsonBody` TEXT NOT NULL,
                `fetchedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`key`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `customer_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT,
                `phone` TEXT NOT NULL,
                `email` TEXT,
                `birthday` TEXT,
                `visitCount` INTEGER NOT NULL,
                `totalSpentMinor` INTEGER NOT NULL,
                `loyaltyPoints` INTEGER NOT NULL,
                `lifetimeGamingPointsEarned` INTEGER NOT NULL,
                `gamingRank` TEXT NOT NULL,
                `gamingRankFloor` INTEGER NOT NULL,
                `nextGamingRank` TEXT,
                `nextGamingRankFloor` INTEGER,
                `pointsToNextGamingRank` INTEGER,
                `lastVisitAt` TEXT,
                `notes` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_customers` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `phone` TEXT,
                `name` TEXT,
                `email` TEXT,
                `birthday` TEXT,
                `notes` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_customers_state` ON `local_customers` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_customers_serverId` ON `local_customers` (`serverId`)",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Widen the existing read-cache table — it's wholesale-replaced on
        // every sync, so the placeholder defaults below only need to be
        // schema-valid; real values land on the very next pullMenu().
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `type` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `hsnCode` TEXT")
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `priceIncludesTax` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `description` TEXT")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_menu_categories` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `name` TEXT,
                `sortOrder` INTEGER,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_menu_categories_state` ON `local_menu_categories` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_menu_categories_serverId` ON `local_menu_categories` (`serverId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_menu_items` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `categoryId` TEXT,
                `name` TEXT,
                `description` TEXT,
                `isAvailable` INTEGER,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_menu_items_state` ON `local_menu_items` (`state`)",
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `staff_cache` (
                `id` TEXT NOT NULL,
                `email` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `phone` TEXT,
                `status` TEXT NOT NULL,
                `rolesCsv` TEXT NOT NULL,
                `lastLoginAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_staff` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `name` TEXT,
                `phone` TEXT,
                `status` TEXT,
                `roleCode` TEXT,
                `pendingDelete` INTEGER NOT NULL DEFAULT 0,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_staff_state` ON `local_staff` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_staff_serverId` ON `local_staff` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `on_shift_cache` (
                `attendanceId` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `userName` TEXT,
                `userEmail` TEXT,
                `branchId` TEXT NOT NULL,
                `branchName` TEXT,
                `clockInAt` TEXT NOT NULL,
                PRIMARY KEY(`attendanceId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ingredient_cache` (
                `id` TEXT NOT NULL,
                `sku` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `baseUnit` TEXT NOT NULL,
                `currentQty` REAL NOT NULL,
                `reorderThreshold` REAL NOT NULL,
                `reorderQty` REAL NOT NULL,
                `avgCostMinor` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_ingredients` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `sku` TEXT,
                `name` TEXT,
                `baseUnit` TEXT,
                `reorderThreshold` REAL,
                `reorderQty` REAL,
                `pendingDelete` INTEGER NOT NULL DEFAULT 0,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_ingredients_state` ON `local_ingredients` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_ingredients_serverId` ON `local_ingredients` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `supplier_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `contact` TEXT,
                `gstin` TEXT,
                `paymentTerms` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_suppliers` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `name` TEXT,
                `contact` TEXT,
                `gstin` TEXT,
                `paymentTerms` TEXT,
                `pendingDelete` INTEGER NOT NULL DEFAULT 0,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_suppliers_state` ON `local_suppliers` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_suppliers_serverId` ON `local_suppliers` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `batch_cache` (
                `id` TEXT NOT NULL,
                `ingredientId` TEXT NOT NULL,
                `receivedAt` TEXT NOT NULL,
                `expiresAt` TEXT,
                `qtyOnHand` REAL NOT NULL,
                `costPerUnitMinor` INTEGER NOT NULL,
                `lotCode` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_batch_cache_ingredientId` ON `batch_cache` (`ingredientId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_grns` (
                `localId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `supplierId` TEXT NOT NULL,
                `supplierInvoiceNo` TEXT,
                `notes` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_grn_lines` (
                `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `grnLocalId` TEXT NOT NULL,
                `ingredientId` TEXT NOT NULL,
                `qty` REAL NOT NULL,
                `unitCostMinor` INTEGER NOT NULL,
                `expiresAt` TEXT,
                `lotCode` TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_grn_lines_grnLocalId` ON `local_grn_lines` (`grnLocalId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_adjustments` (
                `localId` TEXT NOT NULL,
                `ingredientId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `qtyDelta` REAL NOT NULL,
                `type` TEXT NOT NULL,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
    MIGRATION_8_9,
)

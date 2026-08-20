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

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

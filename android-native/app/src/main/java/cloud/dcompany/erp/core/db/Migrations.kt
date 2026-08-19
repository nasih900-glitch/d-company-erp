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

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

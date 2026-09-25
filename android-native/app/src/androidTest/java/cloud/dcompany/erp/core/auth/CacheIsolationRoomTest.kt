package cloud.dcompany.erp.core.auth

import android.database.Cursor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.GamingSessionActionType
import cloud.dcompany.erp.core.db.LocalGamingSessionActionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheIsolationRoomTest {

    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun cleanScopePurgeClearsEveryServerAndLocalTable() = runBlocking {
        val sqlite = db.openHelper.writableDatabase
        val applicationTables = buildSet {
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'room_%' AND name NOT LIKE 'sqlite_%' " +
                    "AND name != 'android_metadata'",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            "Every Room table must be classified as server-derived or durable local work",
            SERVER_DERIVED_CACHE_TABLES.toSet() + LOCAL_DURABLE_TABLES,
            applicationTables,
        )
        val childTables = listOf(
            "local_expense_receipts",
            "local_expense_receipt_chunks",
            "local_bug_report_attachments",
        )
        val insertionOrder = ALL_SCOPE_TABLES.filterNot { it in childTables } + childTables
        insertionOrder.forEach { table ->
            insertSyntheticRow(table)
        }

        val purger = RoomScopeDataPurger(db)
        assertFalse(purger.hasUnresolvedWork())
        assertTrue(purger.purgeIfClean())

        ALL_SCOPE_TABLES.forEach { table ->
            sqlite.query("SELECT COUNT(*) FROM `${table}`").use { cursor ->
                cursor.moveToFirst()
                assertEquals("scope table $table was not cleared", 0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun unresolvedOutboxRefusesPurgeAndLeavesAllRowsUntouched() = runBlocking {
        insertSyntheticRow("menu_items")
        insertSyntheticRow("local_orders", unresolvedState = "pending")
        val menuBefore = dumpTable("menu_items")
        val orderBefore = dumpTable("local_orders")

        val purger = RoomScopeDataPurger(db)
        assertTrue(purger.hasUnresolvedWork())
        assertFalse(purger.purgeIfClean())

        assertEquals(menuBefore, dumpTable("menu_items"))
        assertEquals(orderBefore, dumpTable("local_orders"))
    }

    @Test
    fun pendingExpenseReceiptBlocksPurgeAfterExpenseHeaderIsSynced() = runBlocking {
        insertSyntheticRow("local_expenses")
        insertSyntheticRow("local_expense_receipts", unresolvedState = "pending")
        val retained = dumpTable("local_expense_receipts")
        val purger = RoomScopeDataPurger(db)

        assertTrue(purger.hasUnresolvedWork())
        assertFalse(purger.purgeIfClean())
        assertEquals(retained, dumpTable("local_expense_receipts"))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE local_expense_receipts SET syncState = 'synced'",
        )
        assertFalse(purger.hasUnresolvedWork())
        assertTrue(purger.purgeIfClean())
    }

    @Test
    fun pendingCashExpenseBlocksPurgeAndRetainsSelectedShiftIdentity() = runBlocking {
        insertSyntheticRow("local_expenses", unresolvedState = "pending")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE local_expenses SET paidVia = 'cash', shiftId = ?",
            arrayOf("6d330c0a-d038-40d8-befb-b9fd21898fec"),
        )
        val before = dumpTable("local_expenses")
        val purger = RoomScopeDataPurger(db)

        assertTrue(purger.hasUnresolvedWork())
        assertFalse(purger.purgeIfClean())
        assertEquals(before, dumpTable("local_expenses"))
        db.openHelper.writableDatabase.query(
            "SELECT shiftId FROM local_expenses",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("6d330c0a-d038-40d8-befb-b9fd21898fec", cursor.getString(0))
        }
    }

    @Test
    fun unscopedRejectedGamingExtensionBlocksAccountPurgeUntilRetainedResolution() = runBlocking {
        insertSyntheticRow(
            "local_gaming_package_extensions",
            unresolvedState = "rejected",
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE local_gaming_package_extensions SET shiftId = NULL, lastError = ?",
            arrayOf("Legacy shift provenance is unavailable"),
        )
        val retained = dumpTable("local_gaming_package_extensions")
        val purger = RoomScopeDataPurger(db)

        assertTrue(purger.hasUnresolvedWork())
        assertFalse(purger.purgeIfClean())
        assertEquals(retained, dumpTable("local_gaming_package_extensions"))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE local_gaming_package_extensions SET state = 'discarded', " +
                "resolvedAtMillis = 12345, resolutionReason = 'Server proof retained'",
        )
        assertFalse(purger.hasUnresolvedWork())
        assertTrue(purger.purgeIfClean())
        assertTrue(dumpTable("local_gaming_package_extensions").isEmpty())
    }

    @Test
    fun scopeMarkerSurvivesComponentRecreationWithAllFourIdentityParts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CacheScope("employee", "company", "branch", "terminal")
        val first = SharedPreferencesCacheScopeMarker(context)
        first.clear()

        try {
            assertEquals(true, first.remember(scope))
            assertEquals(scope, SharedPreferencesCacheScopeMarker(context).current())
        } finally {
            first.clear()
        }
    }

    @Test
    fun offlineJoinBlocksOtherStaffWithSyncGuidanceUntilOriginalStaffDrainsIt() = runBlocking {
        val staffA = CacheScope("staff-a", "company-1", "branch-1", "terminal-1")
        val staffB = staffA.copy(userId = "staff-b")
        val marker = object : CacheScopeMarker {
            var stored: CacheScope? = null
            override fun current(): CacheScope? = stored
            override fun remember(scope: CacheScope): Boolean {
                stored = scope
                return true
            }
            override fun clear(): Boolean {
                stored = null
                return true
            }
        }
        val coordinator = CacheIsolationCoordinator(RoomScopeDataPurger(db), marker)
        assertEquals(CacheScopeActivation.PURGED, coordinator.activateValidated(staffA))
        db.gamingDao().captureSessionAction(LocalGamingSessionActionEntity(
            actionId = "join-a",
            sessionKey = "server-session-1",
            actionType = GamingSessionActionType.PARTICIPANT_JOIN,
            ownerCompanyId = staffA.companyId,
            ownerUserId = staffA.userId,
            branchId = requireNotNull(staffA.branchId),
            terminalId = requireNotNull(staffA.terminalId),
            serverSessionId = "server-session-1",
            shiftId = "shift-1",
            occurredAtMillis = 1_000,
            playElapsedMs = 0,
            expectedPauseVersion = 0,
            expectedParticipantRevision = 0,
            expectedBillingRevision = 0,
            customerName = "Amina",
        ))
        coordinator.deactivate()

        val blocked = assertThrows(CacheScopeException::class.java) {
            runBlocking { coordinator.activateValidated(staffB) }
        }
        assertTrue(blocked.message.orEmpty().contains("original staff member"))
        assertTrue(blocked.message.orEmpty().contains("finish Sync"))
        assertEquals(staffA, marker.current())
        assertEquals("pending", db.gamingDao().sessionAction("join-a")?.state)

        assertEquals(CacheScopeActivation.RETAINED, coordinator.activateValidated(staffA))
        assertEquals(1, db.gamingDao().markSessionActionConfirmed("join-a", 2_000, "participant-1"))
        coordinator.deactivate()
        assertEquals(CacheScopeActivation.PURGED, coordinator.activateValidated(staffB))
        assertEquals(staffB, marker.current())
        assertEquals(null, db.gamingDao().sessionAction("join-a"))
    }

    /** Supplying one deterministic typed value for every column lets this test
     * cover new tables without coupling itself to dozens of entity constructors.
     * Support attachments intentionally use their synthetic parent's composite
     * identity so Room's ownership FK remains enforced during the purge test. */
    private fun insertSyntheticRow(table: String, unresolvedState: String? = null) {
        val sqlite = db.openHelper.writableDatabase
        val columns = mutableListOf<Pair<String, String>>()
        sqlite.query("PRAGMA table_info(`${table}`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex) to cursor.getString(typeIndex)
            }
        }
        check(columns.isNotEmpty()) { "Room table $table does not exist" }
        val names = columns.joinToString(",") { "`${it.first}`" }
        val placeholders = columns.joinToString(",") { "?" }
        val values = columns.mapIndexed { index, (name, type) ->
            when {
                table == "local_bug_report_attachments" && name == "reportLocalId" -> "value-0"
                table == "local_bug_report_attachments" && name == "ownerCompanyId" -> "value-1"
                table == "local_bug_report_attachments" && name == "ownerUserId" -> "value-2"
                table == "local_expense_receipts" && name == "expenseLocalId" -> "value-0"
                table == "local_expense_receipt_chunks" && name == "receiptLocalId" -> "value-0"
                table.startsWith("local_") && name in setOf("state", "syncState") ->
                    unresolvedState ?: cleanLocalState(table)
                type.contains("INT", ignoreCase = true) -> index + 1
                type.contains("REAL", ignoreCase = true) ||
                    type.contains("FLOA", ignoreCase = true) ||
                    type.contains("DOUB", ignoreCase = true) -> index + 0.5
                type.contains("BLOB", ignoreCase = true) -> byteArrayOf(index.toByte())
                else -> "value-$index"
            }
        }.toTypedArray()
        sqlite.execSQL("INSERT INTO `${table}` ($names) VALUES ($placeholders)", values)
    }

    private fun cleanLocalState(table: String): String = when (table) {
        "local_shifts" -> "closed"
        "local_gaming_sessions" -> "sent"
        "local_gaming_package_extensions" -> "confirmed"
        "local_gaming_session_actions" -> "confirmed"
        "local_gaming_session_addon_actions" -> "confirmed"
        "local_refunds" -> "settled"
        else -> "synced"
    }

    private fun dumpTable(table: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        db.openHelper.writableDatabase.query("SELECT * FROM `${table}` ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.columnNames.indices.map { index -> cursor.encodedValue(index) }
            }
        }
        return rows
    }

    private fun Cursor.encodedValue(index: Int): String = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_INTEGER -> "integer:${getLong(index)}"
        Cursor.FIELD_TYPE_FLOAT -> "float:${getDouble(index)}"
        Cursor.FIELD_TYPE_BLOB -> "blob:${getBlob(index).joinToString(",")}"
        else -> "string:${getString(index)}"
    }
}

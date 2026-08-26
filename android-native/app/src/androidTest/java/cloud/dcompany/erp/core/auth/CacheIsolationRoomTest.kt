package cloud.dcompany.erp.core.auth

import android.database.Cursor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.db.ErpDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        ALL_SCOPE_TABLES.forEach { table ->
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

    /** Room has no foreign keys between these cache/outbox entities. Supplying
     * one deterministic typed value for every column lets this test cover new
     * tables without coupling itself to dozens of entity constructors. */
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

package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeReportSnapshotDaoTest {
    private lateinit var db: ErpDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ErpDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun latestHomeLookupAndScopedDeleteNeverTouchUnrelatedReportRows() = runBlocking {
        val dao = db.reportSnapshotDao()
        dao.put(ReportSnapshotEntity("daily:2026-10-27", "daily", 50L))
        dao.put(ReportSnapshotEntity("home:v1:branch-a:2026-10-26", "old", 100L))
        dao.put(ReportSnapshotEntity("home:v1:branch-a:2026-10-27", "today", 200L))
        dao.put(ReportSnapshotEntity("home:v1:branch-ab:2026-10-27", "other", 300L))

        val latest = dao.latestWithPrefix("home:v1:branch-a:")
        assertEquals("home:v1:branch-a:2026-10-27", latest?.key)
        assertEquals(2, dao.deleteWithPrefix("home:v1:branch-a:"))
        assertNull(dao.get("home:v1:branch-a:2026-10-27"))
        assertNull(dao.get("home:v1:branch-a:2026-10-26"))
        assertEquals("daily", dao.get("daily:2026-10-27")?.jsonBody)
        assertEquals("other", dao.get("home:v1:branch-ab:2026-10-27")?.jsonBody)
    }

}

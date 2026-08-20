package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * A generic cache for pure-read, aggregate screens (Reports, Analytics,
 * Insights, and eventually Accounting) that don't fit a per-row entity —
 * there's no "list of things" to sync, just one JSON blob per (report type +
 * resolved period) key, e.g. "daily:2026-08-20" or "monthly:2026-08". The raw
 * body is stored as-is and decoded back into whatever @Serializable type the
 * caller asks for, so this one table serves every report shape without a new
 * entity per report. `fetchedAtMillis` exists so a screen can always show how
 * old what it's displaying is — a stale P&L must never look current.
 */
@Entity(tableName = "report_snapshots")
data class ReportSnapshotEntity(
    @PrimaryKey val key: String,
    val jsonBody: String,
    val fetchedAtMillis: Long,
)

@Dao
interface ReportSnapshotDao {
    @Query("SELECT * FROM report_snapshots WHERE `key` = :key")
    suspend fun get(key: String): ReportSnapshotEntity?

    @Query("SELECT * FROM report_snapshots WHERE `key` = :key")
    fun observe(key: String): Flow<ReportSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ReportSnapshotEntity)
}

/**
 * Decodes the cached body for [key] as [T], or null if there's no cached row,
 * it fails to decode (a shape change between app versions, say), or the Room
 * read itself throws (disk I/O error) — every failure here is deliberately
 * folded into "no cache available" rather than left to escape uncaught. This
 * is called before the network attempt even starts in every caller, so an
 * unguarded exception here would crash the whole screen on nothing more than
 * a cache lookup — exactly the bug class SyncEngine.refresh() already had
 * fixed once (see its own doc comment). CancellationException is deliberately
 * re-thrown, not swallowed: a cancelled coroutine (e.g. a fast period switch)
 * must still actually stop, not silently continue past the cancellation.
 */
suspend inline fun <reified T> ReportSnapshotDao.cached(key: String): Pair<T, Long>? {
    return try {
        val row = get(key) ?: return null
        ApiClient.json.decodeFromString<T>(row.jsonBody) to row.fetchedAtMillis
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * Best-effort. A failed cache write (disk full, a value that can't be
 * encoded) must never turn an otherwise-successful network fetch into a
 * "could not load" error for the caller — the fresh data it already has in
 * hand is still shown either way, it just won't be there next time the
 * screen opens.
 */
suspend inline fun <reified T> ReportSnapshotDao.store(key: String, value: T) {
    try {
        put(ReportSnapshotEntity(key, ApiClient.json.encodeToString(value), System.currentTimeMillis()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Swallowed deliberately — see doc comment above.
    }
}

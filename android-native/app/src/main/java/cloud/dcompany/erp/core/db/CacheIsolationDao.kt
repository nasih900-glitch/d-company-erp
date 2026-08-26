package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface CacheIsolationDao {
    @RawQuery
    suspend fun delete(query: SupportSQLiteQuery): Int
}

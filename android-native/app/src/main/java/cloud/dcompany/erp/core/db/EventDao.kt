package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    // -------------------------------------------------------------- event cache
    @Query("SELECT * FROM event_cache ORDER BY startsAt ASC")
    fun observeEventCache(): Flow<List<EventCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEventCache(rows: List<EventCacheEntity>)

    @Query("DELETE FROM event_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteEventCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceEventCache(rows: List<EventCacheEntity>) {
        upsertEventCache(rows)
        deleteEventCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // ------------------------------------------------------- event ticket cache
    @Query("SELECT * FROM event_ticket_cache WHERE eventId = :eventId ORDER BY ticketNo ASC")
    fun observeTicketsFor(eventId: String): Flow<List<EventTicketCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTicketCache(rows: List<EventTicketCacheEntity>)

    @Query("DELETE FROM event_ticket_cache WHERE eventId = :eventId AND id NOT IN (:keepIds)")
    suspend fun deleteTicketCacheNotIn(eventId: String, keepIds: List<String>)

    @Transaction
    suspend fun replaceTicketsFor(eventId: String, rows: List<EventTicketCacheEntity>) {
        upsertTicketCache(rows)
        deleteTicketCacheNotIn(eventId, rows.map { it.id }.ifEmpty { listOf("") })
    }

    // --------------------------------------------------------- local ticket sales
    @Insert
    suspend fun insertLocalTicketSale(row: LocalTicketSaleEntity)

    @Query("SELECT * FROM local_ticket_sales WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableTicketSales(): List<LocalTicketSaleEntity>

    @Query("SELECT * FROM local_ticket_sales WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalTicketSales(): Flow<List<LocalTicketSaleEntity>>

    @Query("SELECT COUNT(*) FROM local_ticket_sales WHERE syncState = 'rejected'")
    fun observeRejectedTicketSaleCount(): Flow<Int>

    @Query("UPDATE local_ticket_sales SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markTicketSaleSynced(localId: String)

    @Query("UPDATE local_ticket_sales SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markTicketSaleRejected(localId: String, error: String)

    /** A rejected sale is parked, not auto-retried — same reasoning as CustomersViewModel.retrySync. */
    @Query("UPDATE local_ticket_sales SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryTicketSale(localId: String)

    // ----------------------------------------------------------------- check-ins
    @Insert
    suspend fun insertLocalCheckIn(row: LocalCheckInEntity)

    @Query("SELECT * FROM local_check_ins WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableCheckIns(): List<LocalCheckInEntity>

    @Query("SELECT * FROM local_check_ins WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalCheckIns(): Flow<List<LocalCheckInEntity>>

    @Query("SELECT COUNT(*) FROM local_check_ins WHERE syncState = 'rejected'")
    fun observeRejectedCheckInCount(): Flow<Int>

    @Query("UPDATE local_check_ins SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markCheckInSynced(localId: String)

    @Query("UPDATE local_check_ins SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCheckInRejected(localId: String, error: String)

    @Query("UPDATE local_check_ins SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryCheckIn(localId: String)

    /** Guards against queueing the same ticket's check-in twice before the first sync — cosmetic
     * only (a duplicate push is server-safe, just produces a confusing rejected row). */
    @Query("SELECT * FROM local_check_ins WHERE ticketId = :ticketId AND syncState != 'synced' LIMIT 1")
    suspend fun pendingCheckInForTicket(ticketId: String): LocalCheckInEntity?
}

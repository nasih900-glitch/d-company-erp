package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ShiftState {
    /** Opened on this tablet, open-leg not yet confirmed by the server. */
    const val OPEN_PENDING = "open_pending"
    /** Open-leg confirmed; billing can use `serverShiftId` directly. */
    const val OPEN_SYNCED = "open_synced"
    /** Close requested — regardless of whether the open leg has synced yet. */
    const val CLOSE_PENDING = "close_pending"
    /** Both legs confirmed by the server. */
    const val CLOSED = "closed"
    /** The server gave a definitive refusal on either leg. Needs a human. */
    const val REJECTED = "rejected"
}

/**
 * A shift's lifecycle on this tablet, open and close together.
 *
 * Deliberately one row for the whole lifecycle, not two outbox entries (one
 * for open, one for close): a shift can be closed before its open has synced
 * (staff open and close within the same offline stretch), and the close leg
 * has nothing to reference until `serverShiftId` exists. Keeping both legs on
 * one row lets `SyncEngine` resolve that dependency by checking
 * `serverShiftId == null` rather than needing a second table and a join.
 *
 * `state` reflects user intent (has a close been requested?), independent of
 * `serverShiftId` (has the open leg actually synced?) — see SyncEngine.pushShifts.
 */
@Entity(tableName = "local_shifts", indices = [Index("state"), Index("openedAtMillis")])
data class LocalShiftEntity(
    /** Client-generated. Also the open-leg idempotency key, so a replay is free. */
    @PrimaryKey val localId: String,
    val serverShiftId: String? = null,
    val openingFloatMinor: Long,
    val openedAtMillis: Long,
    val state: String,
    val countedMinor: Long? = null,
    val closedAtMillis: Long? = null,
    val varianceMinor: Long? = null,
    val lastError: String? = null,
)

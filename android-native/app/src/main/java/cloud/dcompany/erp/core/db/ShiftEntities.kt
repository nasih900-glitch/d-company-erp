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
    /** Open never existed on the server; local dependent work must not use it. */
    const val OPEN_REJECTED = "open_rejected"
    /** Rejected local open was reconciled to the terminal's real server shift. */
    const val OPEN_SUPERSEDED = "open_superseded"
    /**
     * A refused local open was cleared only after a live, scoped server read
     * proved that no shift was open and no captured work referenced it.  The
     * row is retained as a local recovery record; it is never hard-deleted.
     */
    const val OPEN_DISCARDED = "open_discarded"
    /** Close was refused, but the authoritative server shift remains open. */
    const val CLOSE_REJECTED = "close_rejected"
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
 * `serverShiftId` (has the open leg actually synced?) — see
 * SyncEngine.pushShiftOpens.
 */
@Entity(
    tableName = "local_shifts",
    indices = [Index("state"), Index("openedAtMillis"), Index("terminalId")],
)
data class LocalShiftEntity(
    /** Client-generated. Also the open-leg idempotency key, so a replay is free. */
    @PrimaryKey val localId: String,
    val serverShiftId: String? = null,
    /**
     * Captured with every v16+ local open/close intent. Older rows remain null
     * and are treated as belonging to this physical tablet's cached terminal,
     * which preserves an upgrade-time offline outbox without guessing a new id.
     */
    val terminalId: String? = null,
    val branchId: String? = null,
    val openingFloatMinor: Long,
    val openedAtMillis: Long,
    /**
     * Captured when this tablet opens a shift, or copied from the authoritative
     * server cache when an adopted shift is queued for close. Nullable only for
     * rows created by app versions before the v16 migration.
     */
    val openedByUserId: String? = null,
    val openedByName: String? = null,
    val openedByEmail: String? = null,
    val state: String,
    val countedMinor: Long? = null,
    val closedAtMillis: Long? = null,
    val varianceMinor: Long? = null,
    val lastError: String? = null,
    /**
     * Durable UI receipt for a close transition. Existing historical closes
     * migrate to false, so a cold start cannot announce an old shift as a new
     * success. A locally requested or remotely reconciled close sets this to
     * true until staff explicitly acknowledge the result.
     */
    val closeResultPending: Boolean = false,
)

/**
 * Last successfully observed server-open shift for one physical terminal.
 *
 * This is deliberately separate from [LocalShiftEntity]. The local table is
 * an outbox/lifecycle overlay and must retain an unresolved open or close leg
 * byte-for-byte until that write is reconciled. A server pull can replace this
 * cache freely without rewriting or erasing those local intentions.
 */
@Entity(
    tableName = "server_open_shift_cache",
    indices = [Index(value = ["serverShiftId"], unique = true), Index("branchId")],
)
data class ServerOpenShiftEntity(
    /** One open shift at most per terminal; replacing this row is authoritative. */
    @PrimaryKey val terminalId: String,
    val serverShiftId: String,
    val branchId: String,
    val status: String,
    val openingFloatMinor: Long,
    val expectedMinor: Long? = null,
    val posCollectionsMinor: Long? = null,
    val membershipCollectionsMinor: Long? = null,
    val grossCollectionsMinor: Long? = null,
    val cashCollectionsMinor: Long? = null,
    val cardCollectionsMinor: Long? = null,
    val upiCollectionsMinor: Long? = null,
    val otherCollectionsMinor: Long? = null,
    val settledPosRefundsMinor: Long? = null,
    val settledMembershipRefundsMinor: Long? = null,
    val totalRefundsMinor: Long? = null,
    val netCollectionsMinor: Long? = null,
    val openedAtMillis: Long,
    val openedByUserId: String? = null,
    val openedByName: String? = null,
    val openedByEmail: String? = null,
    val verifiedAtMillis: Long,
)

/**
 * Server-derived history for the selected logical terminal.
 *
 * This is separate from [LocalShiftEntity]: a pull may replace this cache,
 * while a local open/close intent must survive until its own idempotent write
 * is resolved.  Opener identity comes from the backend so shifts opened by a
 * different account or device remain attributable.
 */
@Entity(
    tableName = "shift_history_cache",
    indices = [
        Index(value = ["terminalId", "openedAtMillis"]),
        Index("status"),
    ],
)
data class ShiftHistoryCacheEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val terminalId: String,
    val status: String,
    val openedAtMillis: Long,
    val closedAtMillis: Long?,
    val openingFloatMinor: Long,
    val expectedMinor: Long?,
    val countedMinor: Long?,
    val varianceMinor: Long?,
    val posSalesMinor: Long,
    val membershipSalesMinor: Long?,
    val grossCollectionsMinor: Long?,
    val cashCollectionsMinor: Long?,
    val cardCollectionsMinor: Long?,
    val upiCollectionsMinor: Long?,
    val otherCollectionsMinor: Long?,
    val settledPosRefundsMinor: Long?,
    val settledMembershipRefundsMinor: Long?,
    val totalRefundsMinor: Long?,
    val netCollectionsMinor: Long?,
    val openedByUserId: String?,
    val openedByName: String?,
    val openedByEmail: String?,
    val closedByUserId: String?,
    val closedByName: String?,
    val closedByEmail: String?,
    val fetchedAtMillis: Long,
)

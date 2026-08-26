package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Reference data — read-only on the tablet, replaced wholesale on every pull. */
@Entity(tableName = "gaming_stations")
data class GamingStationEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val type: String,
    val ratePerHourMinor: Long,
    val isActive: Boolean,
)

/**
 * Every session the server knows about, for every terminal — not just this
 * one. Sessions are a shared floor view (another tablet's start must show
 * here as "occupied"), so this is wholesale-replaced on every pull exactly
 * like the menu cache, never edited locally. This device's own in-flight
 * start/stop lives in [LocalGamingSessionEntity] until it syncs, at which
 * point it appears here too — read-side code merges the two by id so a
 * session is never shown twice (see GamingViewModel).
 */
@Entity(tableName = "gaming_session_cache")
data class GamingSessionCacheEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    val status: String,
    val startAtMillis: Long,
    val endAtMillis: Long? = null,
    val timerMinutes: Int? = null,
    val timerEndsAtMillis: Long? = null,
    val billableMinutes: Int? = null,
    val amountMinor: Long? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val orderId: String? = null,
)

object GamingSessionState {
    /** Started on this tablet, not yet confirmed by the server. */
    const val START_PENDING = "start_pending"
    /** Start confirmed; `serverId` is real. */
    const val START_SYNCED = "start_synced"
    /** Stop requested — regardless of whether the start leg has synced yet. */
    const val STOP_PENDING = "stop_pending"
    /** Stop confirmed; amount is known, but no POS order exists yet. */
    const val ENDED_UNBILLED = "ended_unbilled"
    /** User explicitly asked to create the held POS order. */
    const val SEND_PENDING = "send_pending"
    /** Held POS order confirmed and stored in [LocalGamingSessionEntity.orderId]. */
    const val SENT = "sent"
    /** Server-confirmed void with an auditable reason; no POS order may exist. */
    const val CANCELLED = "cancelled"
    /** Leg-specific refusals keep the recovery action honest. */
    const val START_REJECTED = "start_rejected"
    const val STOP_REJECTED = "stop_rejected"
    const val SEND_REJECTED = "send_rejected"
}

/** Terminal server outcomes that can safely supersede a stale local overlay. */
internal enum class GamingServerReconciliation {
    NONE,
    START_SYNCED,
    ENDED_UNBILLED,
    SENT,
    CANCELLED,
}

/**
 * Decides whether a pulled server row proves that this tablet's older local
 * lifecycle has already advanced elsewhere. Pending/rejected send legs remain
 * local unless the server supplies an order id; an ordinary ended snapshot is
 * not enough to claim that a handoff succeeded.
 */
internal fun gamingServerReconciliation(
    localState: String,
    serverStatus: String,
    serverOrderId: String?,
): GamingServerReconciliation = when {
    serverOrderId != null -> GamingServerReconciliation.SENT
    serverStatus == "cancelled" -> GamingServerReconciliation.CANCELLED
    serverStatus == "ended" && localState in setOf(
        GamingSessionState.START_PENDING,
        GamingSessionState.START_SYNCED,
        GamingSessionState.STOP_PENDING,
        GamingSessionState.STOP_REJECTED,
    ) -> GamingServerReconciliation.ENDED_UNBILLED
    serverStatus in setOf("active", "paused") &&
        localState == GamingSessionState.START_PENDING -> GamingServerReconciliation.START_SYNCED
    else -> GamingServerReconciliation.NONE
}

/**
 * Versions through database 13 used one generic `rejected` state for every
 * gaming outbox failure. Once start, stop and Send-to-POS gained different
 * recovery actions, leaving that value in place made the row disappear from
 * both the rejected counter and every retry query.
 *
 * The surviving columns identify the failed leg without replaying anything:
 * no server id means start never completed; an ended/billed snapshot means
 * stop completed and the POS handoff failed; otherwise the server id proves
 * this is a failed stop. An order id is stronger evidence than the stale
 * state and means the handoff already completed. The statement is deliberately
 * idempotent so both the Room migration and the runtime safety net can use it.
 */
const val RECOVER_LEGACY_GAMING_REJECTIONS_SQL =
    "UPDATE `local_gaming_sessions` SET `state` = CASE " +
        "WHEN `orderId` IS NOT NULL THEN 'sent' " +
        "WHEN `serverId` IS NULL THEN 'start_rejected' " +
        "WHEN `status` = 'ended' OR `endAtMillis` IS NOT NULL " +
        "OR `billableMinutes` IS NOT NULL OR `amountMinor` IS NOT NULL THEN 'send_rejected' " +
        "ELSE 'stop_rejected' END, `status` = CASE " +
        "WHEN `orderId` IS NOT NULL THEN 'ended' " +
        "WHEN `serverId` IS NULL THEN 'start_failed' " +
        "WHEN `status` = 'ended' OR `endAtMillis` IS NOT NULL " +
        "OR `billableMinutes` IS NOT NULL OR `amountMinor` IS NOT NULL THEN 'ended' " +
        "ELSE 'active' END WHERE `state` = 'rejected'"

/**
 * A gaming session action captured on this tablet — either the whole
 * lifecycle of a session THIS device started (start then stop, same
 * one-row-both-legs shape as [LocalShiftEntity] and for the same reason: a
 * session can be stopped before its start has synced, and the stop leg has
 * nothing to reference until `serverId` exists), or just a stop action
 * against a session started on another terminal, which this device only
 * ever sees via [GamingSessionCacheEntity] — that row is inserted with
 * `serverId` already set and `state = stop_pending` directly, so the start
 * branch in SyncEngine.pushGamingSessionOne (gated on `serverId == null`)
 * never runs for it.
 *
 * `shiftId` may itself be a [LocalShiftEntity.localId] rather than a real
 * server shift id, if the session was started against a shift that was
 * also opened offline — SyncEngine resolves it at push time the same way
 * it already does for `LocalOrderEntity.shiftId`. Null for a stop-only row,
 * since stopping a session doesn't need its shift at all and a session
 * this device didn't start doesn't carry one over the wire in the first
 * place (`GameSession` has no shift_id field).
 */
@Entity(tableName = "local_gaming_sessions", indices = [Index("state")])
data class LocalGamingSessionEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val stationId: String,
    val shiftId: String? = null,
    val customerPhone: String? = null,
    val timerMinutes: Int? = null,
    val startedAtMillis: Long,
    val state: String,
    // Best-known snapshot for immediate UI feedback — filled from the
    // server's own response at start/stop, not computed locally.
    val status: String = "active",
    val endAtMillis: Long? = null,
    val timerEndsAtMillis: Long? = null,
    val billableMinutes: Int? = null,
    val amountMinor: Long? = null,
    val orderId: String? = null,
    val lastError: String? = null,
)

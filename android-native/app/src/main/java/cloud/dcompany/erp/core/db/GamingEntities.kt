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

/** Active base/extension packages cached for offline-safe session capture. */
@Entity(tableName = "gaming_package_cache")
data class GamingPackageCacheEntity(
    @PrimaryKey val id: String,
    val stationType: String,
    val variant: String,
    val kind: String,
    val name: String,
    val durationMinutes: Int,
    val priceMinor: Long,
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
    /** The opening shift owns terminal-bound stop/send/cancel actions. */
    val shiftId: String? = null,
    val status: String,
    val startAtMillis: Long,
    val endAtMillis: Long? = null,
    val timerMinutes: Int? = null,
    val timerEndsAtMillis: Long? = null,
    val billableMinutes: Int? = null,
    val amountMinor: Long? = null,
    /** Server-locked billing snapshot; never substitute the station's current price. */
    val ratePerHourMinor: Long? = null,
    val packageId: String? = null,
    /** Package identity may retire; these server-locked facts remain authoritative. */
    val billingMode: String? = null,
    val packagePriceMinorSnapshot: Long? = null,
    val packageDurationMinutesSnapshot: Int? = null,
    val packageVariantSnapshot: String? = null,
    val packageStationTypeSnapshot: String? = null,
    val extraControllers: Int = 0,
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
    /** A pre-v28 package outbox was resolved without replaying untrusted pricing facts. */
    const val LEGACY_RESOLVED = "legacy_resolved"
}

object GamingLegacyResolution {
    const val MANUAL_BILL_RECORDED = "manual_bill_recorded"
    const val CONFIRMED_NO_PLAY = "confirmed_no_play"
    /** Server proved the quarantined client Start had already committed. */
    const val SERVER_SESSION_RECOVERED = "server_session_recovered"
}

object GamingLegacyResolutionAttemptState {
    const val PENDING = "pending"
    const val AMBIGUOUS = "ambiguous"
    const val REJECTED = "rejected"
    const val RESOLVED = "resolved"
}

enum class RecoveredLegacyServerDisposition {
    /** Authoritative state is safe to expose and the client-only blocker can close. */
    RESOLVE_LOCAL,
    /** Preserve/replay the exact captured Stop on the same local action. */
    RESTORE_CAPTURED_STOP,
    /** Receipt is real, but billing chronology remains unsafe for ordinary staff actions. */
    RETAIN_BILLING_REVIEW,
}

/**
 * Version 27 did not retain package catalogue facts at tap time. Reconstructing
 * them later from a mutable cache can silently reprice an unresolved financial
 * start, so migration quarantines that evidence instead of replaying it.
 */
const val LEGACY_PACKAGE_START_REVIEW_ERROR =
    "This pre-upgrade package start has no trustworthy tap-time price, duration, or variant. " +
        "It will not be replayed automatically because the tablet cannot prove whether an earlier Start reached the server. " +
        "A protected owner must review the captured start/stop evidence before resolving this shift."

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
 * it already does for `LocalOrderEntity.shiftId`. New server snapshots carry
 * the source shift as well, allowing Android to fail closed on a foreign
 * terminal. Null remains valid only for legacy cached rows.
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
    /** Server-locked billing snapshot for trustworthy offline presentation. */
    val ratePerHourMinor: Long? = null,
    val packageId: String? = null,
    /** Immutable package facts captured with an offline start request. */
    val packagePriceMinor: Long? = null,
    val packageDurationMinutes: Int? = null,
    val packageVariant: String? = null,
    /** Explicit server billing mode and locked station type survive package retirement. */
    val billingMode: String? = null,
    val packageStationTypeSnapshot: String? = null,
    val extraControllers: Int = 0,
    val orderId: String? = null,
    val lastError: String? = null,
    /**
     * Immutable v27 capture evidence. A recovered server Start can have a
     * later authoritative receipt-time start, and its queued Stop may need to
     * be clamped to that instant before replay. Keep both original tablet
     * timestamps separately so the operational overlay can use authoritative
     * chronology without rewriting the protected-owner audit evidence.
     */
    val legacyOriginalCapturedStartAtMillis: Long? = null,
    val legacyOriginalCapturedStopAtMillis: Long? = null,
    /**
     * Durable protected-owner resolution for a pre-v28 package start whose
     * original price/duration/variant cannot be reconstructed safely.
     *
     * The request fields are captured before the audit API is called. A lost
     * response therefore retries the exact body with [localId] as its stable
     * idempotency identity instead of allowing a second, conflicting choice.
     */
    val legacyResolution: String? = null,
    val legacyResolutionReason: String? = null,
    val legacyResolutionReferenceOrderId: String? = null,
    val legacyResolutionAttemptState: String? = null,
    val legacyResolutionError: String? = null,
    val legacyResolutionCapturedAtMillis: Long? = null,
    val legacyResolvedAtMillis: Long? = null,
    val legacyResolvedByUserId: String? = null,
    val legacyResolutionReceiptId: Long? = null,
)

object GamingPackageExtensionState {
    /** Captured durably and ready for a first network attempt. */
    const val PENDING = "pending"
    /** A request left the device but its result was not observed; replay only with the same action id. */
    const val AMBIGUOUS = "ambiguous"
    /** The server confirmed the package extension for this immutable action id. */
    const val CONFIRMED = "confirmed"
    /** The server definitively refused the captured request; staff action is required before retry. */
    const val REJECTED = "rejected"
    /** A deterministic refusal acknowledged by staff after an authoritative refresh. */
    const val DISCARDED = "discarded"
}

/**
 * Durable money-affecting package-extension request.
 *
 * [actionId] is both the immutable local primary key and the HTTP
 * Idempotency-Key. The expected package/session facts are captured at tap
 * time, so retrying an ambiguous request can never silently buy a newer price
 * or extend a changed session snapshot. Confirmed rows are retained as local
 * evidence and do not block a later, distinct extension.
 */
@Entity(
    tableName = "local_gaming_package_extensions",
    indices = [Index("state"), Index("serverSessionId"), Index("shiftId")],
)
data class LocalGamingPackageExtensionEntity(
    @PrimaryKey val actionId: String,
    val serverSessionId: String,
    val localSessionId: String? = null,
    /** Exact shift provenance for the close gate; null is treated as unresolved legacy work. */
    val shiftId: String? = null,
    val packageId: String,
    val expectedPackagePriceMinor: Long,
    val expectedPackageDurationMinutes: Int,
    val expectedPackageVariant: String,
    /** Server CAS snapshot captured at the same instant as the package choice. */
    val expectedSessionTimerMinutes: Int,
    val expectedSessionAmountMinor: Long,
    val createdAtMillis: Long,
    val state: String = GamingPackageExtensionState.PENDING,
    val lastError: String? = null,
    val resolvedAtMillis: Long? = null,
    val resolutionReason: String? = null,
)

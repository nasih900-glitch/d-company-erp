package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionState
import java.time.Instant

/** New totals already include legacy paused_minutes; never subtract both. */
internal fun GameSession.completedPauseMillis(): Long =
    (pausedDurationMs ?: (pausedMinutes.toLong() * 60_000L)).also {
        require(it >= 0L) { "Invalid server paused duration" }
    }

/** All estimates use authoritative pause instants, never the time this screen opened. */
internal fun sessionPlayElapsedMillis(session: GameSession, nowMillis: Long): Long? = runCatching {
    val start = Instant.parse(session.startAt).toEpochMilli()
    val capturedEnd = if (session.status in setOf("stopping", "ended")) {
        session.endAt?.let { Instant.parse(it).toEpochMilli() } ?: nowMillis
    } else nowMillis
    val pauseAt = session.pausedAt?.let { Instant.parse(it).toEpochMilli() }
    if (session.status == "paused" && pauseAt == null) return null
    val effectiveEnd = pauseAt?.let { minOf(capturedEnd, it) } ?: capturedEnd
    Math.subtractExact(Math.subtractExact(effectiveEnd, start), session.completedPauseMillis())
        .coerceAtLeast(0L)
}.getOrNull()

internal fun pauseActionError(session: GameSession, pause: Boolean, reason: String): String? = when {
    pause && !session.pauseAvailable ->
        "Pause is not enabled for this shop yet. The owner must update all tablets before enabling it."
    session.pauseVersion == null || session.pauseVersion < 0 ->
        "This session's pause controls have not been verified. Refresh Gaming after the server update."
    session.localState != null && session.localState != GamingSessionState.START_SYNCED ->
        "Finish the saved session action before pausing or resuming. Refresh Gaming and review its status."
    session.status != (if (pause) "active" else "paused") ->
        "This session has changed. Refresh Gaming before ${if (pause) "pausing" else "resuming"} it."
    !pause && session.pausedAt == null ->
        "This older paused session has no verified pause time. Ask the owner to reconcile it before resuming."
    reason.trim().length !in 3..500 -> "Enter a pause reason between 3 and 500 characters."
    else -> null
}

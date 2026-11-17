package cloud.dcompany.erp.ui

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Null in production; native accessibility tests can deterministically exercise the fallback. */
internal val LocalExecutiveRailTouchExplorationOverride = compositionLocalOf<Boolean?> { null }

internal data class ExecutiveRailScrubPolicy(
    val boundaryHysteresisPx: Float,
    val edgeZonePx: Float,
    val maximumEdgeStepPx: Float,
) {
    init {
        require(boundaryHysteresisPx >= 0f)
        require(edgeZonePx > 0f)
        require(maximumEdgeStepPx > 0f)
    }
}

internal enum class ExecutiveRailPressDecision { Pending, Claim, Reject }

/** Vertical motion owns the rail on slop; horizontal motion remains with the hide gesture. */
internal fun executiveRailPressDecision(
    deltaX: Float,
    deltaY: Float,
    touchSlopPx: Float,
    elapsedMillis: Long,
    longPressTimeoutMillis: Long,
    insideViewport: Boolean,
): ExecutiveRailPressDecision {
    if (!insideViewport) return ExecutiveRailPressDecision.Reject
    val horizontal = abs(deltaX)
    val vertical = abs(deltaY)
    if (horizontal <= touchSlopPx && vertical <= touchSlopPx) {
        return if (elapsedMillis >= longPressTimeoutMillis) ExecutiveRailPressDecision.Claim
        else ExecutiveRailPressDecision.Pending
    }
    return if (vertical > touchSlopPx && vertical >= horizontal * 1.2f) {
        ExecutiveRailPressDecision.Claim
    } else {
        // Do not reinterpret a horizontal or ambiguous stream as a later long press.
        ExecutiveRailPressDecision.Reject
    }
}

internal enum class ExecutiveRailScrubPhase {
    Pressing,
    Scrubbing,
    Ended,
}

internal enum class ExecutiveRailScrubTraceKind {
    CLAIMED,
    OWNER_SNAPSHOT_OBSERVED,
    CANDIDATE_STARTED,
    CANDIDATE_RESET,
    TARGET_BOUNDS_MISS,
    AUTO_SCROLL_RESET,
    COMMIT_REQUESTED,
    REQUEST_ACCEPTED,
    REQUEST_REJECTED,
    STOPPED,
}

internal enum class ExecutiveRailScrubStopReason {
    OWNER_ROUTE_MISMATCH,
    REQUEST_REJECTED,
    UNEXPECTED_ACKNOWLEDGEMENT,
    MULTIPLE_POINTERS,
    POINTER_MISSING,
    POINTER_CONSUMED,
    PRECLAIM_ROUTE_CHANGED,
    POINTER_UP,
    PRECLAIM_CONSUMED,
    PRECLAIM_MOVED,
    VIEWPORT_MISSING,
    POINTER_EXITED,
    MODEL_OR_EFFECT_DISPOSED,
}

internal data class ExecutiveRailScrubTraceEvent(
    val sequence: Int,
    val uptimeMillis: Long,
    val kind: ExecutiveRailScrubTraceKind,
    val phase: ExecutiveRailScrubPhase,
    val candidate: Destination?,
    val proposedTarget: Destination?,
    val localCommitted: Destination,
    val expectedRevision: Long,
    val observedDestination: Destination?,
    val observedRevision: Long?,
    val rejection: WorkspaceRouteRejection?,
    val scrollDeltaPx: Float,
    val stopReason: ExecutiveRailScrubStopReason?,
)

internal typealias ExecutiveRailScrubTraceSink = (ExecutiveRailScrubTraceEvent) -> Unit

/** Pure live-scrub state. Navigation authority remains outside gesture policy. */
internal data class ExecutiveRailScrubState(
    val phase: ExecutiveRailScrubPhase,
    val modelKey: List<Destination>,
    val origin: Destination,
    val activationSnapshot: WorkspaceRouteSnapshot,
    val lastAcknowledged: WorkspaceRouteSnapshot,
    val candidate: Destination? = null,
    val leftOrigin: Boolean = false,
) {
    val lastCommitted: Destination get() = lastAcknowledged.destination
}

internal data class ExecutiveRailScrubUpdate(
    val state: ExecutiveRailScrubState,
    val navigateTo: Destination? = null,
)

internal data class ExecutiveRailTargetBounds(
    val destination: Destination,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left)
        require(bottom >= top)
    }

    fun contains(x: Float, y: Float, inset: Float = 0f): Boolean {
        val horizontalInset = inset.coerceAtMost((right - left) / 2f)
        val verticalInset = inset.coerceAtMost((bottom - top) / 2f)
        return x >= left + horizontalInset && x <= right - horizontalInset &&
            y >= top + verticalInset && y <= bottom - verticalInset
    }

    fun containsExpanded(x: Float, y: Float, expansion: Float): Boolean =
        x >= left - expansion && x <= right + expansion &&
            y >= top - expansion && y <= bottom + expansion
}

internal fun beginExecutiveRailPress(
    origin: Destination,
    authoritySnapshot: WorkspaceRouteSnapshot,
    modelKey: List<Destination>,
): ExecutiveRailScrubState {
    require(origin in modelKey)
    return ExecutiveRailScrubState(
        phase = ExecutiveRailScrubPhase.Pressing,
        modelKey = modelKey,
        origin = origin,
        activationSnapshot = authoritySnapshot,
        lastAcknowledged = authoritySnapshot,
    )
}

internal fun claimExecutiveRailScrub(
    state: ExecutiveRailScrubState,
): ExecutiveRailScrubState = state.copy(phase = ExecutiveRailScrubPhase.Scrubbing)

internal fun cancelExecutiveRailScrub(
    state: ExecutiveRailScrubState,
): ExecutiveRailScrubState = state.copy(
    phase = ExecutiveRailScrubPhase.Ended,
    candidate = null,
)

internal fun executiveRailObservedSnapshotBelongsToStream(
    state: ExecutiveRailScrubState,
    observedSnapshot: WorkspaceRouteSnapshot,
): Boolean = observedSnapshot == state.lastAcknowledged

internal fun acknowledgeExecutiveRailScrub(
    state: ExecutiveRailScrubState,
    proposedTarget: Destination,
    accepted: WorkspaceRouteSnapshot,
): ExecutiveRailScrubState? {
    if (accepted.destination != proposedTarget ||
        accepted.revision != state.lastAcknowledged.revision + 1L
    ) {
        return null
    }
    return state.copy(
        lastAcknowledged = accepted,
        candidate = proposedTarget,
    )
}

internal fun updateExecutiveRailScrubTarget(
    state: ExecutiveRailScrubState,
    target: Destination?,
    enteredByPointer: Boolean,
): ExecutiveRailScrubUpdate {
    if (state.phase != ExecutiveRailScrubPhase.Scrubbing) return ExecutiveRailScrubUpdate(state)
    val leftOrigin = state.leftOrigin || (target != null && target != state.origin)
    val candidate = target?.takeIf { it in state.modelKey && (leftOrigin || it != state.origin) }
    val next = if (candidate == state.candidate && leftOrigin == state.leftOrigin) state
        else state.copy(candidate = candidate, leftOrigin = leftOrigin)
    return ExecutiveRailScrubUpdate(
        state = next,
        navigateTo = candidate?.takeIf { enteredByPointer && it != state.lastCommitted },
    )
}

internal fun executiveRailTargetAt(
    x: Float,
    y: Float,
    targets: List<ExecutiveRailTargetBounds>,
    currentCandidate: Destination?,
    hysteresisPx: Float,
): Destination? {
    targets.firstOrNull { target ->
        target.destination == currentCandidate && target.containsExpanded(x, y, hysteresisPx)
    }?.let { return it.destination }
    return targets.firstOrNull { it.contains(x, y, inset = hysteresisPx) }?.destination
}

internal fun executiveRailEdgeScrollDelta(
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    policy: ExecutiveRailScrubPolicy,
): Float {
    if (canScrollBackward && pointerY < viewportTop + policy.edgeZonePx) {
        val penetration = ((viewportTop + policy.edgeZonePx - pointerY) / policy.edgeZonePx)
            .coerceIn(0f, 1f)
        return -max(1f, policy.maximumEdgeStepPx * penetration)
    }
    if (canScrollForward && pointerY > viewportBottom - policy.edgeZonePx) {
        val penetration = ((pointerY - (viewportBottom - policy.edgeZonePx)) / policy.edgeZonePx)
            .coerceIn(0f, 1f)
        return max(1f, policy.maximumEdgeStepPx * penetration)
    }
    return 0f
}

internal fun executiveRailScrubEnabled(touchExplorationEnabled: Boolean): Boolean =
    !touchExplorationEnabled

private data class ActiveExecutiveRailScrub(
    var state: ExecutiveRailScrubState,
    var pointerInRoot: Offset,
    var waitingForInterior: Boolean = false,
)

/**
 * Owns input only inside the wide rail viewport. Vertical motion claims on touch slop, while
 * ordinary taps and horizontal hide gestures remain unconsumed. A stationary long press also
 * activates the same stream. Entering a target proposes its route on that MOVE; the owner must
 * acknowledge the exact revision before the gesture advances its local state.
 */
internal fun Modifier.executiveRailLiveScrub(
    enabled: Boolean,
    modelKey: List<Destination>,
    policy: ExecutiveRailScrubPolicy,
    scrollState: ScrollState,
    viewportBoundsInRoot: () -> Rect?,
    targetBoundsInRoot: () -> List<ExecutiveRailTargetBounds>,
    routeAuthority: ExecutiveRailRouteAuthority,
    onRouteBeforeCommit: (Destination) -> Unit,
    onRouteAfterCommit: (Destination) -> Unit,
    onCandidate: (Destination?) -> Unit,
    trace: ExecutiveRailScrubTraceSink? = null,
): Modifier {
    if (!enabled || modelKey.isEmpty()) return this
    return pointerInput(modelKey, enabled, routeAuthority) {
        val horizontalExitTolerance = policy.boundaryHysteresisPx * 3f
        coroutineScope {
            val gestureScope = this
            var active: ActiveExecutiveRailScrub? = null
            var liveFollower: Job? = null
            var traceSequence = 0
            var displayedCandidate: Destination? = null
            fun publishCandidate(candidate: Destination?) {
                if (candidate == displayedCandidate) return
                displayedCandidate = candidate
                onCandidate(candidate)
            }
            fun emitTrace(
                kind: ExecutiveRailScrubTraceKind,
                state: ExecutiveRailScrubState,
                nowMillis: Long,
                proposedTarget: Destination? = null,
                expectedRevision: Long = state.lastAcknowledged.revision,
                observedSnapshot: WorkspaceRouteSnapshot? = null,
                rejection: WorkspaceRouteRejection? = null,
                scrollDeltaPx: Float = 0f,
                stopReason: ExecutiveRailScrubStopReason? = null,
            ) {
                trace?.invoke(
                    ExecutiveRailScrubTraceEvent(
                        sequence = traceSequence++,
                        uptimeMillis = nowMillis,
                        kind = kind,
                        phase = state.phase,
                        candidate = state.candidate,
                        proposedTarget = proposedTarget,
                        localCommitted = state.lastCommitted,
                        expectedRevision = expectedRevision,
                        observedDestination = observedSnapshot?.destination,
                        observedRevision = observedSnapshot?.revision,
                        rejection = rejection,
                        scrollDeltaPx = scrollDeltaPx,
                        stopReason = stopReason,
                    ),
                )
            }
            fun stopActiveScrub(
                reason: ExecutiveRailScrubStopReason,
                nowMillis: Long = SystemClock.uptimeMillis(),
            ) {
                active?.state?.let { state ->
                    emitTrace(
                        kind = ExecutiveRailScrubTraceKind.STOPPED,
                        state = state,
                        nowMillis = nowMillis,
                        stopReason = reason,
                    )
                }
                active = null
                liveFollower?.cancel()
                liveFollower = null
                publishCandidate(null)
            }
            fun moveToTarget(
                session: ActiveExecutiveRailScrub,
                previousPointer: Offset,
                nowMillis: Long,
            ) {
                val previousCandidate = session.state.candidate
                val targets = targetBoundsInRoot()
                fun targetAt(position: Offset) = executiveRailTargetAt(
                    x = position.x,
                    y = position.y,
                    targets = targets,
                    currentCandidate = previousCandidate,
                    hysteresisPx = policy.boundaryHysteresisPx,
                )
                val target = targetAt(session.pointerInRoot)
                val entered = session.waitingForInterior ||
                    (target != null && target != session.state.lastCommitted && targetAt(previousPointer) != target)
                session.waitingForInterior = false
                val update = updateExecutiveRailScrubTarget(session.state, target, enteredByPointer = entered)
                session.state = update.state
                val next = update.state.candidate
                if (next != previousCandidate) {
                    emitTrace(
                        kind = if (next == null) ExecutiveRailScrubTraceKind.CANDIDATE_RESET
                            else ExecutiveRailScrubTraceKind.CANDIDATE_STARTED,
                        state = update.state,
                        nowMillis = nowMillis,
                        proposedTarget = target,
                    )
                }
                if (target == null) emitTrace(
                    kind = ExecutiveRailScrubTraceKind.TARGET_BOUNDS_MISS,
                    state = update.state,
                    nowMillis = nowMillis,
                )
                publishCandidate(next)
                val destination = update.navigateTo ?: return
                val expected = session.state.lastAcknowledged
                emitTrace(ExecutiveRailScrubTraceKind.COMMIT_REQUESTED, session.state, nowMillis,
                    proposedTarget = destination)
                when (val result = routeAuthority.request(
                    target = destination,
                    expected = expected,
                    beforeCommit = { onRouteBeforeCommit(destination) },
                    afterCommit = { onRouteAfterCommit(destination) },
                )) {
                    is WorkspaceRouteRequestResult.Accepted -> {
                        val acknowledged = acknowledgeExecutiveRailScrub(session.state, destination, result.snapshot)
                        if (acknowledged == null) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.UNEXPECTED_ACKNOWLEDGEMENT, nowMillis)
                        } else {
                            session.state = acknowledged
                            emitTrace(ExecutiveRailScrubTraceKind.REQUEST_ACCEPTED, acknowledged, nowMillis,
                                proposedTarget = destination, expectedRevision = expected.revision,
                                observedSnapshot = result.snapshot)
                        }
                    }
                    is WorkspaceRouteRequestResult.Rejected -> {
                        emitTrace(ExecutiveRailScrubTraceKind.REQUEST_REJECTED, session.state, nowMillis,
                            proposedTarget = destination, observedSnapshot = result.current, rejection = result.reason)
                        stopActiveScrub(ExecutiveRailScrubStopReason.REQUEST_REJECTED, nowMillis)
                    }
                }
            }
            fun edgeScrollDelta(session: ActiveExecutiveRailScrub, viewport: Rect): Float =
                executiveRailEdgeScrollDelta(
                    pointerY = session.pointerInRoot.y,
                    viewportTop = viewport.top,
                    viewportBottom = viewport.bottom,
                    canScrollBackward = scrollState.canScrollBackward,
                    canScrollForward = scrollState.canScrollForward,
                    policy = policy,
                )

            fun startEdgeScroll() {
                if (liveFollower?.isActive == true) return
                liveFollower = gestureScope.launch {
                    while (isActive) {
                        delay(16L)
                        val session = active ?: break
                        val viewport = viewportBoundsInRoot()
                        if (viewport == null) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.VIEWPORT_MISSING)
                            break
                        }
                        val observed = routeAuthority.snapshot()
                        if (!executiveRailObservedSnapshotBelongsToStream(session.state, observed)) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.OWNER_ROUTE_MISMATCH)
                            break
                        }
                        val delta = edgeScrollDelta(session, viewport)
                        if (delta == 0f) break
                        val scrolled = scrollState.scrollBy(delta)
                        if (scrolled != 0f && active === session) {
                            emitTrace(ExecutiveRailScrubTraceKind.AUTO_SCROLL_RESET, session.state,
                                SystemClock.uptimeMillis(), scrollDeltaPx = scrolled)
                        }
                    }
                }
            }
            fun handleMove(session: ActiveExecutiveRailScrub, viewport: Rect, previous: Offset, current: Offset) {
                session.pointerInRoot = current
                if (edgeScrollDelta(session, viewport) != 0f) {
                    session.waitingForInterior = true
                    session.state = updateExecutiveRailScrubTarget(session.state, null, enteredByPointer = false).state
                    publishCandidate(null)
                    startEdgeScroll()
                    return
                }
                val inEdgeZone = current.y < viewport.top + policy.edgeZonePx ||
                    current.y > viewport.bottom - policy.edgeZonePx
                if (previous != current && !(session.waitingForInterior && inEdgeZone)) {
                    moveToTarget(session, previous, SystemClock.uptimeMillis())
                }
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val viewport = viewportBoundsInRoot() ?: return@awaitEachGesture
                    val downInRoot = Offset(viewport.left + down.position.x, viewport.top + down.position.y)
                    val origin = executiveRailTargetAt(
                        x = downInRoot.x,
                        y = downInRoot.y,
                        targets = targetBoundsInRoot(),
                        currentCandidate = null,
                        hysteresisPx = 0f,
                    ) ?: return@awaitEachGesture
                    val activationSnapshot = routeAuthority.snapshot()
                    var pressing = beginExecutiveRailPress(origin, activationSnapshot, modelKey)
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size != 1) {
                            if (claimed) event.changes.forEach { it.consume() }
                            stopActiveScrub(ExecutiveRailScrubStopReason.MULTIPLE_POINTERS)
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.POINTER_MISSING)
                            break
                        }
                        if (claimed && change.isConsumed) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.POINTER_CONSUMED)
                            break
                        }
                        val observedSnapshot = routeAuthority.snapshot()
                        if (trace != null && claimed) active?.state?.let { state ->
                            emitTrace(ExecutiveRailScrubTraceKind.OWNER_SNAPSHOT_OBSERVED, state,
                                SystemClock.uptimeMillis(), observedSnapshot = observedSnapshot)
                        }
                        val allowedObserved = if (claimed) {
                            val session = active?.state
                            session != null && executiveRailObservedSnapshotBelongsToStream(
                                state = session,
                                observedSnapshot = observedSnapshot,
                            )
                        } else {
                            observedSnapshot == activationSnapshot
                        }
                        if (!allowedObserved) {
                            if (claimed) change.consume()
                            stopActiveScrub(
                                if (claimed) ExecutiveRailScrubStopReason.OWNER_ROUTE_MISMATCH
                                else ExecutiveRailScrubStopReason.PRECLAIM_ROUTE_CHANGED,
                            )
                            break
                        }
                        if (change.changedToUpIgnoreConsumed()) {
                            if (claimed) change.consume()
                            stopActiveScrub(ExecutiveRailScrubStopReason.POINTER_UP)
                            break
                        }

                        val latestViewport = viewportBoundsInRoot()
                        if (latestViewport == null) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.VIEWPORT_MISSING)
                            break
                        }
                        val inRoot = Offset(
                            latestViewport.left + change.position.x,
                            latestViewport.top + change.position.y,
                        )
                        if (!claimed) {
                            val delta = change.position - down.position
                            val decision = executiveRailPressDecision(
                                deltaX = delta.x,
                                deltaY = delta.y,
                                touchSlopPx = viewConfiguration.touchSlop,
                                elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                                longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
                                insideViewport = latestViewport.contains(inRoot),
                            )
                            if (change.isConsumed || decision == ExecutiveRailPressDecision.Reject) {
                                emitTrace(
                                    kind = ExecutiveRailScrubTraceKind.STOPPED,
                                    state = pressing,
                                    nowMillis = SystemClock.uptimeMillis(),
                                    stopReason = if (change.isConsumed) {
                                        ExecutiveRailScrubStopReason.PRECLAIM_CONSUMED
                                    } else ExecutiveRailScrubStopReason.PRECLAIM_MOVED,
                                )
                                break
                            }
                            if (decision == ExecutiveRailPressDecision.Pending) continue
                            claimed = true
                            pressing = claimExecutiveRailScrub(pressing)
                            val session = ActiveExecutiveRailScrub(pressing, inRoot)
                            active = session
                            emitTrace(
                                kind = ExecutiveRailScrubTraceKind.CLAIMED,
                                state = pressing,
                                nowMillis = SystemClock.uptimeMillis(),
                            )
                            change.consume()
                            handleMove(session, latestViewport,
                                Offset(latestViewport.left + down.position.x, latestViewport.top + down.position.y), inRoot)
                            continue
                        }

                        change.consume()
                        if (inRoot.x < latestViewport.left - horizontalExitTolerance ||
                            inRoot.x > latestViewport.right + horizontalExitTolerance
                        ) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.POINTER_EXITED)
                            break
                        }
                        active?.let { session ->
                            val previous = Offset(
                                latestViewport.left + change.previousPosition.x,
                                latestViewport.top + change.previousPosition.y,
                            )
                            handleMove(session, latestViewport, previous, inRoot)
                        }
                    }
                }
            } finally {
                stopActiveScrub(ExecutiveRailScrubStopReason.MODEL_OR_EFFECT_DISPOSED)
            }
        }
    }
}

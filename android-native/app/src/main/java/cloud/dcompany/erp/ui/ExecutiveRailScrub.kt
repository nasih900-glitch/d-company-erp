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

internal const val ExecutiveRailTargetDwellMillis = 190L

/** Null in production; native accessibility tests can deterministically exercise the fallback. */
internal val LocalExecutiveRailTouchExplorationOverride = compositionLocalOf<Boolean?> { null }

internal data class ExecutiveRailScrubPolicy(
    val targetDwellMillis: Long = ExecutiveRailTargetDwellMillis,
    val boundaryHysteresisPx: Float,
    val edgeZonePx: Float,
    val maximumEdgeStepPx: Float,
) {
    init {
        require(targetDwellMillis in 160L..220L) {
            "Rail target dwell must remain deliberate without making live following sluggish"
        }
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
    val candidateSinceMillis: Long? = null,
    val leftOrigin: Boolean = false,
    val requireStableTargetAfterScroll: Boolean = false,
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
    candidateSinceMillis = null,
)

internal fun executiveRailObservedSnapshotBelongsToStream(
    state: ExecutiveRailScrubState,
    observedSnapshot: WorkspaceRouteSnapshot,
): Boolean = observedSnapshot == state.lastAcknowledged

internal fun acknowledgeExecutiveRailScrub(
    state: ExecutiveRailScrubState,
    proposedTarget: Destination,
    accepted: WorkspaceRouteSnapshot,
    nowMillis: Long,
): ExecutiveRailScrubState? {
    if (accepted.destination != proposedTarget ||
        accepted.revision != state.lastAcknowledged.revision + 1L
    ) {
        return null
    }
    return state.copy(
        lastAcknowledged = accepted,
        candidate = proposedTarget,
        candidateSinceMillis = nowMillis,
    )
}

internal fun updateExecutiveRailScrubTarget(
    state: ExecutiveRailScrubState,
    target: Destination?,
    nowMillis: Long,
    policy: ExecutiveRailScrubPolicy,
    autoScrollMoved: Boolean = false,
): ExecutiveRailScrubUpdate {
    if (state.phase != ExecutiveRailScrubPhase.Scrubbing) return ExecutiveRailScrubUpdate(state)
    if (autoScrollMoved) {
        return ExecutiveRailScrubUpdate(
            state.copy(
                candidate = null,
                candidateSinceMillis = null,
                requireStableTargetAfterScroll = true,
            ),
        )
    }

    val leftOrigin = state.leftOrigin || (target != null && target != state.origin)
    if (target == null || target !in state.modelKey || (!leftOrigin && target == state.origin)) {
        return ExecutiveRailScrubUpdate(
            state.copy(
                candidate = null,
                candidateSinceMillis = null,
                leftOrigin = leftOrigin,
            ),
        )
    }

    if (state.requireStableTargetAfterScroll || state.candidate != target) {
        return ExecutiveRailScrubUpdate(
            state.copy(
                candidate = target,
                candidateSinceMillis = nowMillis,
                leftOrigin = leftOrigin,
                requireStableTargetAfterScroll = false,
            ),
        )
    }

    val candidateSince = state.candidateSinceMillis ?: nowMillis
    if (target == state.lastAcknowledged.destination ||
        nowMillis - candidateSince < policy.targetDwellMillis
    ) {
        return ExecutiveRailScrubUpdate(state.copy(leftOrigin = leftOrigin))
    }

    return ExecutiveRailScrubUpdate(
        state = state.copy(leftOrigin = leftOrigin),
        navigateTo = target,
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
)

/**
 * Owns input only inside the wide rail viewport. Vertical motion claims on touch slop, while
 * ordinary taps and horizontal hide gestures remain unconsumed. A stationary long press also
 * activates the same stream. Dwell only proposes a route; the owner must acknowledge an exact revision
 * before the gesture advances its local state.
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
    onCandidateDescription: (String?) -> Unit,
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
                onCandidateDescription(null)
            }
            fun startLiveFollower() {
                liveFollower?.cancel()
                liveFollower = gestureScope.launch {
                    while (isActive) {
                        delay(16L)
                        val session = active ?: break
                        val nowMillis = SystemClock.uptimeMillis()
                        val observedSnapshot = routeAuthority.snapshot()
                        emitTrace(
                            kind = ExecutiveRailScrubTraceKind.OWNER_SNAPSHOT_OBSERVED,
                            state = session.state,
                            nowMillis = nowMillis,
                            observedSnapshot = observedSnapshot,
                        )
                        val currentBelongsToThisStream = executiveRailObservedSnapshotBelongsToStream(
                            state = session.state,
                            observedSnapshot = observedSnapshot,
                        )
                        if (!currentBelongsToThisStream) {
                            stopActiveScrub(
                                ExecutiveRailScrubStopReason.OWNER_ROUTE_MISMATCH,
                                nowMillis,
                            )
                            continue
                        }
                        val viewport = viewportBoundsInRoot() ?: continue
                        val edgeDelta = executiveRailEdgeScrollDelta(
                            pointerY = session.pointerInRoot.y,
                            viewportTop = viewport.top,
                            viewportBottom = viewport.bottom,
                            canScrollBackward = scrollState.canScrollBackward,
                            canScrollForward = scrollState.canScrollForward,
                            policy = policy,
                        )
                        val actualScroll = if (edgeDelta != 0f) scrollState.scrollBy(edgeDelta) else 0f
                        if (actualScroll != 0f) {
                            emitTrace(
                                kind = ExecutiveRailScrubTraceKind.AUTO_SCROLL_RESET,
                                state = session.state,
                                nowMillis = nowMillis,
                                scrollDeltaPx = actualScroll,
                            )
                            session.state = updateExecutiveRailScrubTarget(
                                state = session.state,
                                target = null,
                                nowMillis = nowMillis,
                                policy = policy,
                                autoScrollMoved = true,
                            ).state
                            onCandidateDescription(null)
                            continue
                        }

                        val target = executiveRailTargetAt(
                            x = session.pointerInRoot.x,
                            y = session.pointerInRoot.y,
                            targets = targetBoundsInRoot(),
                            currentCandidate = session.state.candidate,
                            hysteresisPx = policy.boundaryHysteresisPx,
                        )
                        val update = updateExecutiveRailScrubTarget(
                            state = session.state,
                            target = target,
                            nowMillis = nowMillis,
                            policy = policy,
                        )
                        if (target == null) {
                            if (session.state.candidate != null && update.state.candidate == null) {
                                emitTrace(
                                    kind = ExecutiveRailScrubTraceKind.CANDIDATE_RESET,
                                    state = update.state,
                                    nowMillis = nowMillis,
                                )
                            }
                            emitTrace(
                                kind = ExecutiveRailScrubTraceKind.TARGET_BOUNDS_MISS,
                                state = session.state,
                                nowMillis = nowMillis,
                            )
                        } else if (update.state.candidate != session.state.candidate) {
                            emitTrace(
                                kind = if (update.state.candidate == null) {
                                    ExecutiveRailScrubTraceKind.CANDIDATE_RESET
                                } else {
                                    ExecutiveRailScrubTraceKind.CANDIDATE_STARTED
                                },
                                state = update.state,
                                nowMillis = nowMillis,
                                proposedTarget = target,
                            )
                        }
                        session.state = update.state
                        onCandidateDescription(
                            update.state.candidate?.let { "Hold on ${executiveRailVisibleLabel(it)} to open" },
                        )
                        update.navigateTo?.let { targetDestination ->
                            emitTrace(
                                kind = ExecutiveRailScrubTraceKind.COMMIT_REQUESTED,
                                state = update.state,
                                nowMillis = nowMillis,
                                proposedTarget = targetDestination,
                            )
                            val expected = session.state.lastAcknowledged
                            when (
                                val result = routeAuthority.request(
                                    target = targetDestination,
                                    expected = expected,
                                    beforeCommit = { onRouteBeforeCommit(targetDestination) },
                                    afterCommit = { onRouteAfterCommit(targetDestination) },
                                )
                            ) {
                                is WorkspaceRouteRequestResult.Accepted -> {
                                    val acknowledged = acknowledgeExecutiveRailScrub(
                                        state = session.state,
                                        proposedTarget = targetDestination,
                                        accepted = result.snapshot,
                                        nowMillis = nowMillis,
                                    )
                                    if (acknowledged == null) {
                                        stopActiveScrub(
                                            ExecutiveRailScrubStopReason.UNEXPECTED_ACKNOWLEDGEMENT,
                                            nowMillis,
                                        )
                                    } else {
                                        session.state = acknowledged
                                        emitTrace(
                                            kind = ExecutiveRailScrubTraceKind.REQUEST_ACCEPTED,
                                            state = acknowledged,
                                            nowMillis = nowMillis,
                                            proposedTarget = targetDestination,
                                            expectedRevision = expected.revision,
                                            observedSnapshot = result.snapshot,
                                        )
                                    }
                                }

                                is WorkspaceRouteRequestResult.Rejected -> {
                                    emitTrace(
                                        kind = ExecutiveRailScrubTraceKind.REQUEST_REJECTED,
                                        state = session.state,
                                        nowMillis = nowMillis,
                                        proposedTarget = targetDestination,
                                        expectedRevision = expected.revision,
                                        observedSnapshot = result.current,
                                        rejection = result.reason,
                                    )
                                    stopActiveScrub(
                                        ExecutiveRailScrubStopReason.REQUEST_REJECTED,
                                        nowMillis,
                                    )
                                }
                            }
                        }
                    }
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
                        val observedSnapshot = routeAuthority.snapshot()
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

                        val latestViewport = viewportBoundsInRoot() ?: break
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
                            active = ActiveExecutiveRailScrub(pressing, inRoot)
                            emitTrace(
                                kind = ExecutiveRailScrubTraceKind.CLAIMED,
                                state = pressing,
                                nowMillis = SystemClock.uptimeMillis(),
                            )
                            startLiveFollower()
                            change.consume()
                            continue
                        }

                        change.consume()
                        if (inRoot.x < latestViewport.left - horizontalExitTolerance ||
                            inRoot.x > latestViewport.right + horizontalExitTolerance
                        ) {
                            stopActiveScrub(ExecutiveRailScrubStopReason.POINTER_EXITED)
                            break
                        }
                        active?.pointerInRoot = inRoot
                    }
                }
            } finally {
                stopActiveScrub(ExecutiveRailScrubStopReason.MODEL_OR_EFFECT_DISPOSED)
            }
        }
    }
}

package cloud.dcompany.erp.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

internal enum class ExecutiveRailVisibilityGestureDirection {
    HideToLeft,
    ShowToRight,
}

internal enum class ExecutiveRailVisibilityGestureDecision {
    Pending,
    Claim,
    Reject,
}

internal fun executiveRailVisibilityGestureDecision(
    deltaX: Float,
    deltaY: Float,
    touchSlopPx: Float,
    direction: ExecutiveRailVisibilityGestureDirection,
    horizontalDominance: Float = 1.2f,
): ExecutiveRailVisibilityGestureDecision {
    require(touchSlopPx >= 0f)
    require(horizontalDominance >= 1f)
    if (abs(deltaX) <= touchSlopPx && abs(deltaY) <= touchSlopPx) {
        return ExecutiveRailVisibilityGestureDecision.Pending
    }
    val intendedDirection = when (direction) {
        ExecutiveRailVisibilityGestureDirection.HideToLeft -> deltaX < 0f
        ExecutiveRailVisibilityGestureDirection.ShowToRight -> deltaX > 0f
    }
    if (abs(deltaY) >= abs(deltaX) * horizontalDominance) {
        return ExecutiveRailVisibilityGestureDecision.Reject
    }
    if (intendedDirection && abs(deltaX) >= abs(deltaY) * horizontalDominance) {
        return ExecutiveRailVisibilityGestureDecision.Claim
    }
    if (!intendedDirection && abs(deltaX) >= abs(deltaY)) {
        return ExecutiveRailVisibilityGestureDecision.Reject
    }
    return ExecutiveRailVisibilityGestureDecision.Pending
}

internal fun executiveRailVisibilityGestureCommits(
    deltaX: Float,
    deltaY: Float,
    commitDistancePx: Float,
    direction: ExecutiveRailVisibilityGestureDirection,
    horizontalDominance: Float = 1.2f,
): Boolean {
    require(commitDistancePx > 0f)
    require(horizontalDominance >= 1f)
    val intendedDirection = when (direction) {
        ExecutiveRailVisibilityGestureDirection.HideToLeft -> deltaX <= -commitDistancePx
        ExecutiveRailVisibilityGestureDirection.ShowToRight -> deltaX >= commitDistancePx
    }
    return intendedDirection && abs(deltaX) >= abs(deltaY) * horizontalDominance
}

/**
 * Claims only a deliberate horizontal gesture. Until that claim, pointer changes remain available
 * to the rail's vertical scroll/live-scrub chain and to workspace content such as the gaming dial.
 */
internal fun Modifier.executiveRailVisibilitySwipe(
    enabled: Boolean,
    direction: ExecutiveRailVisibilityGestureDirection,
    commitDistancePx: Float,
    edgeStartWidthPx: Float? = null,
    onCommit: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(direction, commitDistancePx, edgeStartWidthPx) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            if (edgeStartWidthPx != null && down.position.x > edgeStartWidthPx) {
                return@awaitEachGesture
            }
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size != 1) {
                    if (claimed) event.changes.forEach { it.consume() }
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (change.changedToUpIgnoreConsumed()) {
                    if (claimed) {
                        change.consume()
                        if (
                            executiveRailVisibilityGestureCommits(
                                deltaX = delta.x,
                                deltaY = delta.y,
                                commitDistancePx = commitDistancePx,
                                direction = direction,
                            )
                        ) {
                            onCommit()
                        }
                    }
                    break
                }
                if (!claimed) {
                    if (change.isConsumed) break
                    when (
                        executiveRailVisibilityGestureDecision(
                            deltaX = delta.x,
                            deltaY = delta.y,
                            touchSlopPx = viewConfiguration.touchSlop,
                            direction = direction,
                        )
                    ) {
                        ExecutiveRailVisibilityGestureDecision.Pending -> continue
                        ExecutiveRailVisibilityGestureDecision.Reject -> break
                        ExecutiveRailVisibilityGestureDecision.Claim -> claimed = true
                    }
                }
                change.consume()
            }
        }
    }
}

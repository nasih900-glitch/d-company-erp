package cloud.dcompany.erp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/** Only the graphics layer observes intermediate positions; layout uses the committed flag. */
internal class ExecutiveRailMotion(initiallyOpen: Boolean, private val animationScope: CoroutineScope) {
    var position by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set
    var mounted by mutableStateOf(initiallyOpen)
        private set
    var settledOpen by mutableStateOf(initiallyOpen)
        private set
    private var animation: Job? = null
    private var dragOrigin = position

    fun startDrag() {
        animation?.cancel()
        dragOrigin = position
        mounted = true
        settledOpen = false
    }

    fun dragBy(displacementFraction: Float) {
        position = (dragOrigin + displacementFraction).coerceIn(0f, 1f)
    }

    fun settle(open: Boolean) {
        animation?.cancel()
        val target = if (open) 1f else 0f
        if (position == target) {
            mounted = open
            settledOpen = open
            return
        }
        mounted = true
        settledOpen = false
        animation = animationScope.launch {
            animate(position, target, animationSpec = tween(200, easing = FastOutSlowInEasing)) { value, _ ->
                position = value
            }
            mounted = open
            settledOpen = open
        }
    }
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
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: (Boolean) -> Unit,
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
            var committed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) {
                        if (claimed) event.changes.forEach { it.consume() }
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    val delta = change.position - down.position
                    if (change.changedToUpIgnoreConsumed()) {
                        if (claimed) {
                            change.consume()
                            onDrag(delta.x)
                            committed = executiveRailVisibilityGestureCommits(
                                deltaX = delta.x,
                                deltaY = delta.y,
                                commitDistancePx = commitDistancePx,
                                direction = direction,
                            )
                        }
                        break
                    }
                    if (!claimed) {
                        when (
                            executiveRailVisibilityGestureDecision(
                                deltaX = delta.x,
                                deltaY = delta.y,
                                touchSlopPx = viewConfiguration.touchSlop,
                                direction = direction,
                            )
                        ) {
                            ExecutiveRailVisibilityGestureDecision.Pending -> {
                                // A child can claim long press or slop later in this same event.
                                val final = awaitPointerEvent(PointerEventPass.Final)
                                if (final.changes.any { it.id == down.id && it.isConsumed }) break
                                continue
                            }
                            ExecutiveRailVisibilityGestureDecision.Reject -> break
                            ExecutiveRailVisibilityGestureDecision.Claim -> {
                                claimed = true
                                onStart()
                            }
                        }
                    }
                    change.consume()
                    onDrag(delta.x)
                }
            } finally {
                if (claimed) onEnd(committed)
            }
        }
    }
}

package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Motion
import cloud.dcompany.erp.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class GamingDurationChoice(
    val packageId: String,
    val minutes: Int,
    val totalMinor: Long,
)

/**
 * Text shown inside the permanent gaming control dial after a session starts.
 * The dial face, selected package arc and marker remain unchanged; session state
 * is communicated only through this centre readout.
 */
internal data class GamingDurationDialReadout(
    val primary: String,
    val label: String,
    val detail: String? = null,
)

/** A touch dial selects a published package; it never calculates a new tariff. */
@Composable
internal fun GamingDurationDial(
    choices: List<GamingDurationChoice>,
    selectedPackageId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 280.dp,
    showChoiceChips: Boolean = true,
    segmentTick: (() -> Unit)? = null,
    gestureContextKey: Any? = null,
    readout: GamingDurationDialReadout? = null,
    remainingFraction: Float? = null,
) {
    if (choices.isEmpty()) return
    val options = choices.sortedWith(compareBy(GamingDurationChoice::minutes, GamingDurationChoice::packageId))
    val selectedIndex = options.indexOfFirst { it.packageId == selectedPackageId }
        .takeIf { it >= 0 } ?: 0
    val selected = options[selectedIndex]
    var observedSelection by remember(options, gestureContextKey) {
        mutableStateOf(selected.packageId)
    }
    var lastPublishedSelection by remember(options, gestureContextKey) {
        mutableStateOf(selected.packageId)
    }
    var liveDragAngle by remember(options, gestureContextKey) { mutableStateOf<Float?>(null) }
    var gestureRevision by remember(options, gestureContextKey) { mutableStateOf(0) }
    SideEffect {
        if (observedSelection != selected.packageId) {
            observedSelection = selected.packageId
            if (lastPublishedSelection != selected.packageId) {
                lastPublishedSelection = selected.packageId
                liveDragAngle = null
                gestureRevision++
            }
        }
    }
    val compactMarkers = dialSize < 270.dp
    val compactCentre = dialSize < 200.dp
    val targetAngle = durationDialStopAngles(options.size)[selectedIndex]
    val animatedAngle by animateFloatAsState(
        targetValue = liveDragAngle ?: targetAngle,
        animationSpec = if (liveDragAngle != null) snap()
            else tween(durationMillis = Motion.medium, easing = Motion.emphasized),
        label = "Gaming duration selection",
    )
    val displayedAngle = liveDragAngle ?: animatedAngle
    val currentRequestedSelection by rememberUpdatedState(selectedPackageId)
    val currentDisplayedSelection by rememberUpdatedState(selected.packageId)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentReadout by rememberUpdatedState(readout)
    val view = LocalView.current
    val platformSegmentTick: () -> Unit = remember(view) {
        { ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_TICK) }
    }
    val currentSegmentTick by rememberUpdatedState(segmentTick ?: platformSegmentTick)
    val dialAccent = MaterialTheme.colorScheme.onSurface
    val dialAccentSoft = MaterialTheme.colorScheme.onSurfaceVariant
    val dialCentre = MaterialTheme.colorScheme.surface
    // Use the theme ink rather than a pale border token for the primary track.
    // The track remains muted, while the selected duration stays unmistakably
    // black in Day mode and white in Night mode.
    val dialTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    val focusRing = MaterialTheme.colorScheme.primary
    fun publishUserSelection(
        packageId: String,
        repeatSameSelection: Boolean,
    ) {
        if (currentReadout != null) return
        val changed = packageId != lastPublishedSelection
        if (changed) {
            lastPublishedSelection = packageId
            currentSegmentTick()
        }
        if (changed || repeatSameSelection) currentOnSelect(packageId)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val dialSemantics = if (readout == null) {
            Modifier.semantics {
                contentDescription = "Session duration dial"
                stateDescription = "${selected.minutes} minutes, ${selected.totalMinor.asRupees()} total"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = selectedIndex.toFloat(),
                    range = 0f..options.lastIndex.toFloat(),
                    steps = (options.size - 2).coerceAtLeast(0),
                )
                setProgress { requested ->
                    val index = requested.roundToInt().coerceIn(options.indices)
                    publishUserSelection(options[index].packageId, repeatSameSelection = true)
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction("Previous duration") {
                        val previous = (selectedIndex - 1).coerceAtLeast(0)
                        if (previous == selectedIndex) false else {
                            publishUserSelection(options[previous].packageId, repeatSameSelection = true)
                            true
                        }
                    },
                    CustomAccessibilityAction("Next duration") {
                        val next = (selectedIndex + 1).coerceAtMost(options.lastIndex)
                        if (next == selectedIndex) false else {
                            publishUserSelection(options[next].packageId, repeatSameSelection = true)
                            true
                        }
                    },
                )
            }.pointerInput(options, gestureContextKey) {
                fun selectAt(progress: Float, dragging: Boolean, allowNormalization: Boolean = false) {
                    val previous = options.indexOfFirst { it.packageId == lastPublishedSelection }
                    val index = durationDialIndexAtProgress(
                        progress, options.size,
                        previousIndex = previous.takeIf { dragging && it >= 0 },
                    )
                    val id = options[index].packageId
                    val needsSelectionNormalization =
                        allowNormalization &&
                        currentRequestedSelection != currentDisplayedSelection &&
                            id == currentDisplayedSelection
                    publishUserSelection(
                        packageId = id,
                        repeatSameSelection = needsSelectionNormalization,
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downAngle = durationDialAngleOnRing(down.position, size)
                    if (downAngle == null) return@awaitEachGesture
                    val startedAtRevision = gestureRevision
                    var dragging = false
                    val currentIndex = options.indexOfFirst { it.packageId == currentDisplayedSelection }
                        .coerceAtLeast(0)
                    var progress = durationDialProgressAtAngle(
                        downAngle,
                        durationDialStopAngles(options.size)[currentIndex] - DURATION_DIAL_START,
                    )
                    var previousAngle: Float = downAngle
                    liveDragAngle = DURATION_DIAL_START + progress
                    try {
                        while (true) {
                            if (startedAtRevision != gestureRevision) break
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (startedAtRevision != gestureRevision) break
                            if (change.isConsumed && !dragging) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            val moved = hypot(dx, dy)
                            val angle = durationDialAngle(change.position, size)
                            if (!change.pressed) {
                                if (angle != null && (dragging || moved < viewConfiguration.touchSlop)) {
                                    progress = if (dragging) {
                                        durationDialDragProgress(progress, previousAngle, angle)
                                    } else durationDialProgressAtAngle(angle, progress)
                                    liveDragAngle = DURATION_DIAL_START + progress
                                    selectAt(progress, dragging, allowNormalization = !dragging)
                                }
                                break
                            }
                            if (!dragging && moved >= viewConfiguration.touchSlop) dragging = true
                            if (dragging && angle != null) {
                                progress = durationDialDragProgress(progress, previousAngle, angle)
                                previousAngle = angle
                                liveDragAngle = DURATION_DIAL_START + progress
                                selectAt(progress, dragging = true)
                                change.consume()
                            }
                        }
                    } finally {
                        liveDragAngle = null
                    }
                }
            }
        } else {
            Modifier.semantics {
                contentDescription = listOfNotNull(readout.label, readout.primary, readout.detail).joinToString(". ")
                stateDescription = listOfNotNull(readout.label, readout.primary, readout.detail)
                    .joinToString(". ")
            }
        }
        Box(
            modifier = Modifier
                .size(dialSize)
                .testTag("gaming-control-ring")
                .then(dialSemantics),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier.size(dialSize)
                    .testTag("gaming-control-ring-geometry")
                    .semantics {
                        contentDescription = "Gaming duration ring. The booking marker stays fixed while the inner trace shows remaining time."
                    },
            ) {
                val vividGold = dialAccent
                val paleGold = dialAccentSoft
                val radius = size.minDimension * 0.40f
                val center = Offset(size.width / 2f, size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val diameter = radius * 2f
                val tickOuter = radius - 12.dp.toPx()

                drawCircle(
                    color = dialCentre,
                    radius = radius - 6.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    dialTrack.copy(alpha = 0.52f),
                    radius = radius + 8.dp.toPx(), center = center,
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    dialTrack.copy(alpha = 0.34f),
                    radius = radius - 23.dp.toPx(), center = center,
                    style = Stroke(1.dp.toPx()),
                )
                repeat(96) { index ->
                    val angle = Math.toRadians((index * 360f / 96f).toDouble())
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val inner = tickOuter - (if (index % 8 == 0) 9.dp.toPx() else 4.dp.toPx())
                    drawLine(
                        color = dialAccentSoft.copy(alpha = if (index % 8 == 0) 0.58f else 0.28f),
                        start = center + direction * inner,
                        end = center + direction * tickOuter,
                        strokeWidth = if (index % 8 == 0) 1.1.dp.toPx() else 0.7.dp.toPx(),
                    )
                }
                drawCircle(
                    dialTrack,
                    radius = radius,
                    center = center,
                    style = Stroke(2.dp.toPx()),
                )
                // The first published package is a real duration, not zero
                // progress. Draw its proportional share of the full ring while
                // retaining the existing, well-tested interaction endpoints.
                // The arc always ends at the selection marker, and the same
                // selected package is frozen after Start.
                val maximumMinutes = options.maxOf { it.minutes }.coerceAtLeast(1)
                val selectedSweep = (360f * selected.minutes / maximumMinutes.toFloat())
                    .coerceIn(1f, 360f)
                drawArc(
                    color = vividGold,
                    startAngle = displayedAngle - selectedSweep,
                    sweepAngle = selectedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                val radians = Math.toRadians(displayedAngle.toDouble())
                val knob = Offset(
                    center.x + radius * cos(radians).toFloat(),
                    center.y + radius * sin(radians).toFloat(),
                )
                drawCircle(dialCentre, radius = 8.dp.toPx(), center = knob)
                drawCircle(paleGold, radius = 6.dp.toPx(), center = knob)
                drawCircle(
                    vividGold, radius = 8.dp.toPx(), center = knob,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
            remainingFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.let { remaining ->
                Canvas(
                    modifier = Modifier.size(dialSize)
                        .testTag("gaming-control-ring-remaining")
                        .semantics {
                            contentDescription = "Remaining booked time trace"
                        },
                ) {
                    val radius = size.minDimension * 0.40f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    val diameter = radius * 2f
                    drawArc(
                        color = dialAccentSoft.copy(alpha = 0.72f),
                        startAngle = -90f,
                        sweepAngle = 360f * remaining,
                        useCenter = false,
                        topLeft = Offset(topLeft.x + 12.dp.toPx(), topLeft.y + 12.dp.toPx()),
                        size = Size(diameter - 24.dp.toPx(), diameter - 24.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            if (options.size == 2) {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .padding(top = if (compactMarkers) 0.dp else 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    options.forEachIndexed { index, option ->
                        val interaction = remember(option.packageId) { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        val focused by interaction.collectIsFocusedAsState()
                        val pressed by interaction.collectIsPressedAsState()
                        Box(
                            Modifier.weight(1f),
                            contentAlignment = if (index == 0) Alignment.CenterStart else Alignment.CenterEnd,
                        ) {
                            Text(
                                if (compactMarkers) "${option.minutes} min"
                                else "${option.minutes} min\n${option.totalMinor.asRupees()}",
                                // Size to the wider explicit line so enlarged
                                // money text remains whole. The parent half of
                                // the dial still bounds the marker and keeps it
                                // clear of the opposite endpoint.
                                modifier = Modifier.width(IntrinsicSize.Max).sizeIn(
                                    minWidth = if (compactMarkers) 48.dp else 64.dp,
                                    minHeight = 48.dp,
                                )
                                    .drawBehind {
                                        if (readout == null && (hovered || focused || pressed)) {
                                            drawLine(
                                                color = dialAccent,
                                                start = Offset(0f, size.height - 2.dp.toPx()),
                                                end = Offset(size.width, size.height - 2.dp.toPx()),
                                                strokeWidth = 2.dp.toPx(),
                                            )
                                        }
                                        if (focused) {
                                            drawRoundRect(
                                                color = focusRing,
                                                style = Stroke(width = 1.dp.toPx()),
                                            )
                                        }
                                    }
                                    .then(if (readout == null) {
                                        Modifier.hoverable(interactionSource = interaction)
                                            .clickable(
                                                interactionSource = interaction,
                                                indication = null,
                                                role = Role.Button,
                                            ) {
                                                publishUserSelection(
                                                    option.packageId,
                                                    repeatSameSelection = true,
                                                )
                                            }
                                            .semantics {
                                                contentDescription = "Choose ${option.minutes} minutes, ${option.totalMinor.asRupees()}"
                                                this.selected = option.packageId == selected.packageId
                                            }
                                    } else Modifier),
                                color = if (option.packageId == selected.packageId) {
                                    MaterialTheme.colorScheme.onSurface
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = if (compactMarkers) MaterialTheme.typography.labelMedium
                                    else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                            )
                        }
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    readout?.label ?: "DURATION",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    readout?.primary ?: "${selected.minutes} min",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = if (compactCentre) MaterialTheme.typography.headlineLarge
                        else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                HorizontalDivider(
                    modifier = Modifier.width(dialSize * 0.42f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                )
                Text(
                    readout?.detail ?: selected.totalMinor.asRupees(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        if (showChoiceChips && readout == null) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                options.chunked(2).forEach { rowOptions ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        rowOptions.forEach { option ->
                            FilterChip(
                                selected = option.packageId == selectedPackageId,
                                onClick = {
                                    publishUserSelection(
                                        option.packageId,
                                        repeatSameSelection = true,
                                    )
                                },
                                label = { Text("${option.minutes} min · ${option.totalMinor.asRupees()}") },
                                colors = gamingPackageChipColors(),
                                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                            )
                        }
                        repeat(2 - rowOptions.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

internal fun durationDialStopAngles(count: Int): List<Float> {
    require(count > 0)
    if (count == 1) return listOf(270f)
    return List(count) { index -> DURATION_DIAL_START + 360f * index / (count - 1) }
}

internal const val DURATION_DIAL_START = 135f

/** A turn is bounded: reaching the maximum never wraps back to the cheapest package. */
internal fun durationDialDragProgress(progress: Float, previousAngle: Float, angle: Float): Float {
    val delta = ((angle - previousAngle + 540f) % 360f) - 180f
    return (progress + delta).coerceIn(0f, 360f)
}

internal fun durationDialProgressAtAngle(angle: Float, selectedProgress: Float = 0f): Float {
    val progress = (angle - DURATION_DIAL_START + 360f) % 360f
    // The minimum and maximum meet on the ring. Keep the selected endpoint
    // when grabbing its handle, then use unwrapped movement for the whole gesture.
    if (progress < 8f || progress > 352f) return if (selectedProgress > 180f) 360f else 0f
    return progress
}

internal fun durationDialIndexAtProgress(progress: Float, count: Int, previousIndex: Int? = null): Int {
    require(count > 0)
    if (count == 1) return 0
    val step = 360f / (count - 1)
    val candidate = (progress.coerceIn(0f, 360f) / step).roundToInt().coerceIn(0, count - 1)
    if (previousIndex == null || previousIndex !in 0 until count || candidate == previousIndex) return candidate
    val boundary = step * (candidate + previousIndex) / 2f
    val tolerance = minOf(5f, step / 8f)
    return when {
        candidate > previousIndex && progress <= boundary + tolerance -> previousIndex
        candidate < previousIndex && progress >= boundary - tolerance -> previousIndex
        else -> candidate
    }
}

internal fun closestDurationDialStop(position: Offset, size: IntSize, count: Int): Int? {
    val angle = durationDialAngleOnRing(position, size) ?: return null
    return nearestDurationDialStop(angle, count)
}

internal fun durationDialAngleOnRing(position: Offset, size: IntSize): Float? {
    if (size.width <= 0 || size.height <= 0) return null
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val dx = position.x - centerX
    val dy = position.y - centerY
    val radius = size.width.coerceAtMost(size.height) * 0.40f
    if (abs(hypot(dx, dy) - radius) > size.width.coerceAtMost(size.height) * 0.12f) {
        return null
    }
    return durationDialAngle(position, size)
}

internal fun durationDialAngle(position: Offset, size: IntSize): Float? {
    if (size.width <= 0 || size.height <= 0) return null
    val dx = position.x - size.width / 2f
    val dy = position.y - size.height / 2f
    if (dx == 0f && dy == 0f) return null
    return (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
}

internal fun nearestDurationDialStop(angle: Float, count: Int): Int {
    return durationDialIndexAtProgress(durationDialProgressAtAngle(angle), count)
}

internal fun circularAngleDistance(first: Float, second: Float): Float {
    val difference = abs((first - second) % 360f)
    return minOf(difference, 360f - difference)
}

internal fun durationDialSweep(angle: Float, startAngle: Float = DURATION_DIAL_START): Float =
    (angle - startAngle).coerceIn(0f, 360f)

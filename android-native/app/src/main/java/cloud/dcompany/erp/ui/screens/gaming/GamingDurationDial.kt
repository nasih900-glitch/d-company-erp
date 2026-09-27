package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
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

/** A touch dial selects a published package; it never calculates a new tariff. */
@Composable
internal fun GamingDurationDial(
    choices: List<GamingDurationChoice>,
    selectedPackageId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (choices.isEmpty()) return
    val options = choices.sortedWith(compareBy(GamingDurationChoice::minutes, GamingDurationChoice::packageId))
    val selectedIndex = options.indexOfFirst { it.packageId == selectedPackageId }
        .takeIf { it >= 0 } ?: 0
    val selected = options[selectedIndex]
    val targetAngle = durationDialStopAngles(options.size)[selectedIndex]
    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = tween(durationMillis = Motion.medium, easing = Motion.emphasized),
        label = "Gaming duration selection",
    )
    val currentSelection by rememberUpdatedState(selectedPackageId)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(208.dp)
                .semantics {
                    contentDescription = "Session duration dial"
                    stateDescription = "${selected.minutes} minutes, ${selected.totalMinor.asRupees()} total"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = selectedIndex.toFloat(),
                        range = 0f..options.lastIndex.toFloat(),
                        steps = (options.size - 2).coerceAtLeast(0),
                    )
                    setProgress { requested ->
                        val index = requested.roundToInt().coerceIn(options.indices)
                        currentOnSelect(options[index].packageId)
                        true
                    }
                }
                .pointerInput(options.map(GamingDurationChoice::packageId)) {
                    fun selectAt(position: Offset) {
                        val index = closestDurationDialStop(position, size, options.size) ?: return
                        val id = options[index].packageId
                        if (id != currentSelection) currentOnSelect(id)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (closestDurationDialStop(down.position, size, options.size) == null) {
                            return@awaitEachGesture
                        }
                        var dragging = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.isConsumed && !dragging) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            val moved = hypot(dx, dy)
                            if (!change.pressed) {
                                if (dragging || moved < viewConfiguration.touchSlop) {
                                    selectAt(change.position)
                                }
                                break
                            }
                            if (!dragging && moved >= viewConfiguration.touchSlop) {
                                if (abs(dx) <= abs(dy)) break
                                dragging = true
                            }
                            if (dragging) {
                                selectAt(change.position)
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(208.dp)) {
                val stroke = 10.dp.toPx()
                val radius = size.minDimension * 0.38f
                val center = Offset(size.width / 2f, size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val diameter = radius * 2f
                drawCircle(
                    Brand.Border.copy(alpha = 0.24f),
                    radius = radius,
                    center = center,
                    style = Stroke(stroke),
                )
                drawArc(
                    color = Brand.Border,
                    startAngle = 210f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Brand.GoldBright,
                    startAngle = 210f,
                    sweepAngle = animatedAngle - 210f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val radians = Math.toRadians(animatedAngle.toDouble())
                val knob = Offset(
                    center.x + radius * cos(radians).toFloat(),
                    center.y + radius * sin(radians).toFloat(),
                )
                drawCircle(Brand.Gold.copy(alpha = 0.20f), radius = 19.dp.toPx(), center = knob)
                drawCircle(Brand.GoldBright, radius = 11.dp.toPx(), center = knob)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${selected.minutes} min",
                    color = Brand.GoldBright,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Session price",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    selected.totalMinor.asRupees(),
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(options, key = GamingDurationChoice::packageId) { option ->
                FilterChip(
                    selected = option.packageId == selectedPackageId,
                    onClick = { onSelect(option.packageId) },
                    label = { Text("${option.minutes} min · ${option.totalMinor.asRupees()} total") },
                    colors = gamingPackageChipColors(),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        }
    }
}

internal fun durationDialStopAngles(count: Int): List<Float> {
    require(count > 0)
    if (count == 1) return listOf(270f)
    return List(count) { index -> 210f + 120f * index / (count - 1) }
}

internal fun closestDurationDialStop(position: Offset, size: IntSize, count: Int): Int? {
    if (count <= 0 || size.width <= 0 || size.height <= 0) return null
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val dx = position.x - centerX
    val dy = position.y - centerY
    val radius = size.width.coerceAtMost(size.height) * 0.38f
    if (abs(hypot(dx, dy) - radius) > size.width.coerceAtMost(size.height) * 0.12f) {
        return null
    }
    val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
    if (angle !in 190f..350f) return null
    val stops = durationDialStopAngles(count)
    return stops.indices.minBy { index ->
        val difference = abs(angle - stops[index])
        minOf(difference, 360f - difference)
    }
}

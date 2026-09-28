package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    dialSize: Dp = 280.dp,
    showChoiceChips: Boolean = true,
) {
    if (choices.isEmpty()) return
    val options = choices.sortedWith(compareBy(GamingDurationChoice::minutes, GamingDurationChoice::packageId))
    val selectedIndex = options.indexOfFirst { it.packageId == selectedPackageId }
        .takeIf { it >= 0 } ?: 0
    val selected = options[selectedIndex]
    val compactMarkers = dialSize < 270.dp
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
                .size(dialSize)
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
            Canvas(modifier = Modifier.size(dialSize)) {
                val vividGold = Color(0xFFFFD76D)
                val paleGold = Color(0xFFFFF0AE)
                val stroke = 14.dp.toPx()
                val radius = size.minDimension * 0.40f
                val center = Offset(size.width / 2f, size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val diameter = radius * 2f
                val tickOuter = radius - 12.dp.toPx()

                // Static depth stays behind the controls; only the selected arc
                // animates, so an idle board does not continuously redraw.
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.72f to Color.Transparent,
                            0.88f to vividGold.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                        ),
                        center = center,
                        radius = radius + 32.dp.toPx(),
                    ),
                    radius = radius + 32.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF0D1A20), Color(0xFF050E14)),
                        center = Offset(center.x - radius * 0.22f, center.y - radius * 0.28f),
                        radius = radius * 1.45f,
                    ),
                    radius = radius - 6.dp.toPx(),
                    center = center,
                )
                // Fine instrument marks and concentric outlines keep the dial
                // legible against the dark centre at every published duration.
                drawCircle(
                    vividGold.copy(alpha = 0.14f),
                    radius = radius + 14.dp.toPx(), center = center,
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    vividGold.copy(alpha = 0.09f),
                    radius = radius - 23.dp.toPx(), center = center,
                    style = Stroke(1.dp.toPx()),
                )
                repeat(96) { index ->
                    val angle = Math.toRadians((index * 360f / 96f).toDouble())
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val inner = tickOuter - (if (index % 8 == 0) 9.dp.toPx() else 4.dp.toPx())
                    drawLine(
                        color = vividGold.copy(alpha = if (index % 8 == 0) 0.30f else 0.12f),
                        start = center + direction * inner,
                        end = center + direction * tickOuter,
                        strokeWidth = if (index % 8 == 0) 1.1.dp.toPx() else 0.7.dp.toPx(),
                    )
                }
                drawCircle(
                    Color(0xFF1A272C),
                    radius = radius,
                    center = center,
                    style = Stroke(5.dp.toPx()),
                )
                drawArc(
                    color = vividGold.copy(alpha = 0.13f),
                    startAngle = 170f,
                    sweepAngle = (animatedAngle - 170f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = 30.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = vividGold.copy(alpha = 0.29f),
                    startAngle = 170f,
                    sweepAngle = (animatedAngle - 170f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = 21.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(vividGold, paleGold, vividGold),
                        start = Offset(center.x - radius, center.y + radius),
                        end = Offset(center.x + radius, center.y - radius),
                    ),
                    startAngle = 170f,
                    sweepAngle = (animatedAngle - 170f).coerceAtLeast(0f),
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
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(vividGold.copy(alpha = 0.55f), Color.Transparent),
                        center = knob, radius = 34.dp.toPx(),
                    ),
                    radius = 34.dp.toPx(), center = knob,
                )
                drawCircle(vividGold.copy(alpha = 0.50f), radius = 17.dp.toPx(), center = knob)
                drawCircle(paleGold, radius = 13.dp.toPx(), center = knob)
                drawCircle(
                    Color.White.copy(alpha = 0.9f), radius = 13.dp.toPx(), center = knob,
                    style = Stroke(1.2.dp.toPx()),
                )
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
                                modifier = Modifier.sizeIn(minWidth = 76.dp, minHeight = 48.dp)
                                    .hoverable(interactionSource = interaction)
                                    .drawBehind {
                                        if (hovered || focused || pressed) {
                                            drawLine(
                                                color = Brand.GoldBright,
                                                start = Offset(0f, size.height - 2.dp.toPx()),
                                                end = Offset(size.width, size.height - 2.dp.toPx()),
                                                strokeWidth = 2.dp.toPx(),
                                            )
                                        }
                                        if (focused) {
                                            drawRoundRect(
                                                color = Brand.FocusRing,
                                                style = Stroke(width = 1.dp.toPx()),
                                            )
                                        }
                                    }
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        role = Role.Button,
                                    ) { onSelect(option.packageId) }
                                    .semantics {
                                        contentDescription = "Choose ${option.minutes} minutes, ${option.totalMinor.asRupees()}"
                                        this.selected = option.packageId == selected.packageId
                                    },
                                color = if (option.packageId == selected.packageId) {
                                    Color(0xFFFFD76D)
                                } else Brand.ForegroundMuted,
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
                    "${selected.minutes} min",
                    color = Color(0xFFFFD76D),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(
                    modifier = Modifier.width(dialSize * 0.42f),
                    color = Brand.Gold.copy(alpha = 0.35f),
                )
                Text(
                    "Session price",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    selected.totalMinor.asRupees(),
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
        }
        if (showChoiceChips) {
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
                                onClick = { onSelect(option.packageId) },
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
    return List(count) { index -> 240f + 90f * index / (count - 1) }
}

internal fun closestDurationDialStop(position: Offset, size: IntSize, count: Int): Int? {
    if (count <= 0 || size.width <= 0 || size.height <= 0) return null
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val dx = position.x - centerX
    val dy = position.y - centerY
    val radius = size.width.coerceAtMost(size.height) * 0.40f
    if (abs(hypot(dx, dy) - radius) > size.width.coerceAtMost(size.height) * 0.12f) {
        return null
    }
    val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
    if (angle !in 160f..350f) return null
    val stops = durationDialStopAngles(count)
    return stops.indices.minBy { index ->
        val difference = abs(angle - stops[index])
        minOf(difference, 360f - difference)
    }
}

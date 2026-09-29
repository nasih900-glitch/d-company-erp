package cloud.dcompany.erp.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A static, low-contrast light treatment shared by the four daily workspaces. */
fun Modifier.controlDeckBackdrop(): Modifier = drawWithCache {
    val gold = Color(0xFFFFCE6B)
    val base = Brush.verticalGradient(
        listOf(Color(0xFF040B10), Color(0xFF07141C), Color(0xFF030A0F)),
    )
    val lightCenter = Offset(size.width * 0.38f, size.height * 0.12f)
    val lightRadius = size.width * 0.58f
    val light = Brush.radialGradient(
        colors = listOf(gold.copy(alpha = 0.10f), Color.Transparent),
        center = lightCenter,
        radius = lightRadius,
    )
    val controlLightCenter = Offset(size.width * 0.76f, size.height * 0.54f)
    val controlLightRadius = size.width * 0.42f
    val controlLight = Brush.radialGradient(
        colors = listOf(gold.copy(alpha = 0.065f), Color.Transparent),
        center = controlLightCenter,
        radius = controlLightRadius,
    )
    val streak = Brush.horizontalGradient(
        listOf(Color.Transparent, gold.copy(alpha = 0.14f), Color.Transparent),
    )
    val paths = List(3) { index ->
        val y = size.height * (0.54f + index * 0.035f)
        Path().apply {
            moveTo(-size.width * 0.08f, y)
            cubicTo(
                size.width * 0.23f, y - size.height * 0.12f,
                size.width * 0.36f, y + size.height * 0.08f,
                size.width * 0.58f, y - size.height * 0.04f,
            )
            cubicTo(
                size.width * 0.78f, y - size.height * 0.13f,
                size.width * 0.90f, y + size.height * 0.01f,
                size.width * 1.08f, y - size.height * 0.08f,
            )
        }
    }
    val strokes = List(3) { index ->
        Stroke(
            width = if (index == 0) 1.5.dp.toPx() else 0.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    onDrawBehind {
        drawRect(base)
        drawCircle(brush = light, radius = lightRadius, center = lightCenter)
        drawCircle(
            brush = controlLight,
            radius = controlLightRadius,
            center = controlLightCenter,
        )
        paths.forEachIndexed { index, path ->
            drawPath(path, brush = streak, style = strokes[index])
        }
    }
}

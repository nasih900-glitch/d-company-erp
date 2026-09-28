package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Static, low-contrast artwork behind the daily screens; no idle animation. */
@Composable
fun GoldAtmosphere(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val gold = Color(0xFFF3C963)
        val width = size.width
        val height = size.height
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(gold.copy(alpha = 0.055f), Color.Transparent),
                center = Offset(width * 0.58f, height * 0.43f),
                radius = width * 0.64f,
            ),
        )
        val trail = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                gold.copy(alpha = 0.22f),
                gold.copy(alpha = 0.13f),
                Color.Transparent,
            ),
            startX = 0f,
            endX = width,
        )
        repeat(3) { index ->
            val y = height * 0.58f + index * 17.dp.toPx()
            val path = Path().apply {
                moveTo(-width * 0.08f, y)
                cubicTo(width * 0.16f, y - 51.dp.toPx(),
                    width * 0.29f, y + 35.dp.toPx(), width * 0.48f, y - 18.dp.toPx())
                cubicTo(width * 0.70f, y - 48.dp.toPx(),
                    width * 0.85f, y + 26.dp.toPx(), width * 1.08f, y - 8.dp.toPx())
            }
            drawPath(
                path = path,
                brush = trail,
                style = Stroke(
                    width = if (index == 0) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
}

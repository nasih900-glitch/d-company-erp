package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.dcompany.erp.R
import kotlin.math.roundToInt

internal const val SplashLoungeHdR1Sha256 =
    "1a27f92dd5d921b290c9c430e8a1a100cc0a49a5fe4e7cd9bd56d62da0177684"

internal data class SplashSceneSourceCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Center-crops the selected 16:9 scene without stretching it. The controller
 * remains the focal point as the app moves between tablet and compact windows.
 */
internal fun splashSceneSourceCrop(
    destinationWidthPx: Int,
    destinationHeightPx: Int,
): SplashSceneSourceCrop {
    require(destinationWidthPx > 0 && destinationHeightPx > 0) {
        "Destination dimensions must be positive"
    }
    val sourceWidth = 1_672
    val sourceHeight = 941
    val destinationRatio = destinationWidthPx.toDouble() / destinationHeightPx.toDouble()
    val sourceRatio = sourceWidth.toDouble() / sourceHeight.toDouble()
    val cropWidth: Int
    val cropHeight: Int
    if (destinationRatio >= sourceRatio) {
        cropWidth = sourceWidth
        cropHeight = (sourceWidth / destinationRatio).roundToInt().coerceIn(1, sourceHeight)
    } else {
        cropHeight = sourceHeight
        cropWidth = (sourceHeight * destinationRatio).roundToInt().coerceIn(1, sourceWidth)
    }
    return SplashSceneSourceCrop(
        left = (sourceWidth - cropWidth) / 2,
        top = (sourceHeight - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

@Composable
internal fun SplashLoungeScene(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap = ImageBitmap.imageResource(R.drawable.splash_lounge_hd_r1)
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier.then(semantics)) {
        drawSplashLounge(bitmap)
    }
}

private fun DrawScope.drawSplashLounge(bitmap: ImageBitmap) {
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    if (width <= 0 || height <= 0) return
    val crop = splashSceneSourceCrop(width, height)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(crop.left, crop.top),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
    )
    drawRect(Color.Black.copy(alpha = 0.18f))
}

@Composable
internal fun ExecutiveEntrySplash(
    statusText: String = "Preparing your workspace",
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().background(Color(0xFF0E061F)).testTag("executive-entry-splash"),
    ) {
        SplashLoungeScene(
            Modifier.fillMaxSize().testTag("entry-splash-scene"),
            contentDescription = "D Company gaming lounge splash artwork",
        )
        val fontScale = LocalDensity.current.fontScale
        val compactHeight = maxHeight < 520.dp
        val markSize = if (compactHeight) 54.dp else 88.dp
        val nameLineHeight = if (compactHeight) 28.sp else 40.sp
        val taglineLineHeight = if (compactHeight) 16.sp else 20.sp
        val nameCenter = if (compactHeight) 0.465f else 0.47f
        val taglineCenter = if (compactHeight) 0.558f else 0.54f

        Icon(
            painter = painterResource(R.drawable.login_logo_d),
            contentDescription = "D Company logo",
            tint = Color.Unspecified,
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (maxHeight * 0.33f) - (markSize / 2))
                .size(markSize)
                .testTag("entry-splash-brand-mark"),
        )
        Text(
            text = "D COMPANY",
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (maxHeight * nameCenter) - ((nameLineHeight.value * fontScale / 2f).dp))
                .fillMaxWidth(0.84f)
                .testTag("entry-splash-brand-name"),
            color = Color(0xFFF7F2FA),
            style = if (compactHeight) {
                MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = nameLineHeight)
            } else {
                MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp, lineHeight = nameLineHeight)
            },
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (compactHeight) 4.sp else 6.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = "GAMING LOUNGE",
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (maxHeight * taglineCenter) - ((taglineLineHeight.value * fontScale / 2f).dp))
                .fillMaxWidth(0.72f)
                .testTag("entry-splash-brand-tagline"),
            color = Color(0xFFEBC577),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = if (compactHeight) 12.sp else 16.sp,
                lineHeight = taglineLineHeight,
            ),
            letterSpacing = if (compactHeight) 2.4.sp else 4.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = statusText,
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth(0.88f)
                .padding(bottom = maxHeight * 0.045f)
                .testTag("entry-status"),
            color = Color(0xFFE9E4ED),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Clip,
        )
    }
}

package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.dcompany.erp.R
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import kotlin.math.roundToInt

internal const val AuthorizedLoungeAssetSha256 =
    "3a87906eca80feba8a22f051e383d3aa582dd6e34586e32d5cb7e2320c56a572"

internal data class LoungeSourceCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Crops only the approved lower 480px of the original 960x1280 image. Center
 * cropping happens inside that safe region, so the television is never read.
 */
internal fun authorizedLoungeSourceCrop(
    destinationWidthPx: Int,
    destinationHeightPx: Int,
): LoungeSourceCrop {
    require(destinationWidthPx > 0 && destinationHeightPx > 0) {
        "Destination dimensions must be positive"
    }
    val safeLeft = 0
    val safeTop = 800
    val safeWidth = 960
    val safeHeight = 480
    val destinationRatio = destinationWidthPx.toDouble() / destinationHeightPx.toDouble()
    val safeRatio = safeWidth.toDouble() / safeHeight.toDouble()
    val cropWidth: Int
    val cropHeight: Int
    if (destinationRatio >= safeRatio) {
        cropWidth = safeWidth
        cropHeight = (safeWidth / destinationRatio).roundToInt().coerceIn(1, safeHeight)
    } else {
        cropHeight = safeHeight
        cropWidth = (safeHeight * destinationRatio).roundToInt().coerceIn(1, safeWidth)
    }
    return LoungeSourceCrop(
        left = safeLeft + (safeWidth - cropWidth) / 2,
        top = safeTop + (safeHeight - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

@Composable
internal fun AuthorizedLoungePhoto(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap = ImageBitmap.imageResource(R.drawable.authorized_lounge_original)
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier.then(semantics)) {
        drawAuthorizedLounge(bitmap)
    }
}

private fun DrawScope.drawAuthorizedLounge(bitmap: ImageBitmap) {
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    if (width <= 0 || height <= 0) return
    val crop = authorizedLoungeSourceCrop(width, height)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(crop.left, crop.top),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
    )
}

@Composable
internal fun ExecutiveEntrySplash(
    statusText: String = "Preparing your workspace",
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag("executive-entry-splash")) {
        AuthorizedLoungePhoto(Modifier.fillMaxSize().testTag("entry-lounge-photo"))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f)))
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DCompanyBrandMark(size = 88.dp, contentDescription = "D Company logo")
            Spacer(Modifier.height(18.dp))
            Text(
                "D COMPANY",
                color = Color(0xFFF3F5F7),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            )
            Text(
                "GAMING LOUNGE",
                color = Color(0xFFEBC577),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.4.sp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                statusText,
                modifier = Modifier.testTag("entry-status"),
                color = Color(0xFFAAB5C5),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

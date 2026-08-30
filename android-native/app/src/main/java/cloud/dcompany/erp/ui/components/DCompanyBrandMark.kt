package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import cloud.dcompany.erp.R

/**
 * The adaptive foreground has transparent safe-zone padding around a square
 * source plate. Zooming that safe zone into a circular clip preserves the
 * existing artwork while preventing the plate from rendering as a black box.
 */
@Composable
internal fun DCompanyBrandMark(
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clip(CircleShape)) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.5f
                scaleY = 1.5f
            },
        )
    }
}

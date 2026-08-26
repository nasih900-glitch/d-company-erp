package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.ui.theme.Brand

@Composable
fun ViewOnlyNotice(message: String = VIEW_ONLY_MESSAGE) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.SurfaceRaised)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Brand.Gold,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

package cloud.dcompany.erp.ui.components

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
fun ViewOnlyNotice(message: String = VIEW_ONLY_MESSAGE) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.operationalInformation,
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
fun HelpScreen(
    pendingRequestCount: Int,
    onReportProblem: () -> Unit,
    onOpenMyRequests: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background).padding(Spacing.lgPlus),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        OperationalBanner(
            title = "Help is available from every workflow",
            detail = "Tell the owner what failed or where you are stuck. The request is saved on this tablet first and sends safely when the connection is available.",
            tone = UiTone.Information,
            icon = Icons.AutoMirrored.Filled.HelpOutline,
        )
        SectionCard(
            title = "Get help",
            subtitle = "No passwords, payment details, customer data or screenshots are attached automatically.",
            icon = Icons.Filled.ReportProblem,
            elevated = true,
        ) {
            Text(
                "Describe what you expected, what happened and whether you can continue working. You can deliberately attach a reviewed image when it is useful.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ErpButton(
                    text = "Report a problem",
                    onClick = onReportProblem,
                    leadingIcon = Icons.Filled.ReportProblem,
                    modifier = Modifier.weight(1f),
                )
                ErpButton(
                    text = if (pendingRequestCount > 0) {
                        "My requests ($pendingRequestCount waiting)"
                    } else {
                        "My help requests"
                    },
                    onClick = onOpenMyRequests,
                    leadingIcon = Icons.Filled.History,
                    intent = ActionIntent.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

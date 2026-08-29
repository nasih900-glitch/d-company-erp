package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SupportInboxUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val reports: List<BugReportInboxItem> = emptyList(),
    val total: Int = 0,
    val selected: BugReportInboxItem? = null,
)

internal class SupportInboxViewModel(
    private val api: SupportInboxApi = ApiClient.create(),
) : ViewModel() {
    private val _state = MutableStateFlow(SupportInboxUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading && _state.value.reports.isNotEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val page = api.inbox(limit = 50)
                _state.value = _state.value.copy(
                    loading = false,
                    reports = page.items,
                    total = page.total,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = failure.supportInboxReadable(),
                )
            }
        }
    }

    fun select(report: BugReportInboxItem?) {
        _state.value = _state.value.copy(selected = report)
    }
}

@Composable
internal fun SupportInboxScreen(vm: SupportInboxViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Brand.Background).padding(Spacing.lgPlus),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionCard(
            title = "Staff support requests",
            subtitle = "Private, company-scoped requests from the web and Android apps.",
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            action = {
                ErpButton(
                    text = "Refresh",
                    onClick = vm::refresh,
                    enabled = !state.loading,
                    busy = state.loading && state.reports.isNotEmpty(),
                    leadingIcon = Icons.Filled.Refresh,
                    intent = ActionIntent.Secondary,
                )
            },
        ) {
            Text(
                if (state.total == 1) "1 request" else "${state.total} requests",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when {
            state.loading && state.reports.isEmpty() -> Column(
                Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = Brand.Gold)
                Text(
                    "Loading the support inbox…",
                    modifier = Modifier.padding(top = Spacing.md),
                    color = Brand.ForegroundMuted,
                )
            }
            state.error != null && state.reports.isEmpty() -> DesignedEmptyState(
                title = "Could not open Support Inbox",
                body = requireNotNull(state.error),
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                tone = UiTone.Warning,
                primaryLabel = "Try again",
                onPrimary = vm::refresh,
                modifier = Modifier.weight(1f),
            )
            state.reports.isEmpty() -> DesignedEmptyState(
                title = "No support requests",
                body = "New staff help requests will appear here after they reach the server.",
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                tone = UiTone.Information,
                primaryLabel = "Refresh",
                onPrimary = vm::refresh,
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.error?.let { message ->
                    item("refresh-error") {
                        Text(message, color = Brand.Warning, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(state.reports, key = BugReportInboxItem::id) { report ->
                    SupportInboxRow(report = report, onClick = { vm.select(report) })
                }
            }
        }
    }

    state.selected?.let { report ->
        SupportInboxDetail(report = report, onDismiss = { vm.select(null) })
    }
}

@Composable
private fun SupportInboxRow(report: BugReportInboxItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Brand.Surface,
        shape = Radius.shapeMd,
        border = androidx.compose.foundation.BorderStroke(1.dp, Brand.BorderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    report.title,
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${report.reporter.name} · ${report.clientContext.currentScreen ?: "Unknown screen"} · ${report.createdAt}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OperationalStatusBadge(report.severity, severityTone(report.severity))
            OperationalStatusBadge(report.status.replace('_', ' '), statusTone(report.status))
        }
    }
}

@Composable
private fun SupportInboxDetail(report: BugReportInboxItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text(report.title, color = Brand.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(report.description, color = Brand.Foreground)
                Text(
                    "Reported by ${report.reporter.name} (${report.reporter.email})",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "Screen: ${report.clientContext.currentScreen ?: "Not supplied"} · " +
                        "Device: ${report.clientContext.deviceModel ?: "Not supplied"} · " +
                        "Connection: ${report.clientContext.connectivity}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Use the protected web ERP inbox to reply, change status or inspect an intentionally attached image.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun severityTone(value: String): UiTone = when (value.lowercase()) {
    "critical" -> UiTone.Danger
    "high" -> UiTone.Warning
    "medium" -> UiTone.Information
    else -> UiTone.Neutral
}

private fun statusTone(value: String): UiTone = when (value.lowercase()) {
    "resolved", "closed" -> UiTone.Success
    "in_progress", "acknowledged" -> UiTone.Information
    "open" -> UiTone.Warning
    else -> UiTone.Neutral
}

private fun Throwable.supportInboxReadable(): String {
    val api = this as? ApiException
    return when (api?.status) {
        401 -> "Your session expired. Sign in again before opening the owner inbox."
        403 -> "This account is not authorised for the protected Support Inbox."
        404 -> "The connected ERP server does not provide the Support Inbox yet."
        else -> "The owner inbox could not be refreshed. Check the connection and try again."
    }
}

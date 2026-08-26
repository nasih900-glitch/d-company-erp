package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import cloud.dcompany.erp.core.net.BackendReachability
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Spacing

enum class SyncAvailabilityProblem {
    NONE,
    NO_NETWORK,
    SERVER_UNREACHABLE,
}

internal data class SyncAvailabilityCopy(
    val title: String,
    val detail: String,
)

internal fun syncAvailabilityProblem(
    networkValidated: Boolean,
    backendReachability: BackendReachability,
): SyncAvailabilityProblem = when {
    !networkValidated -> SyncAvailabilityProblem.NO_NETWORK
    backendReachability == BackendReachability.UNREACHABLE ->
        SyncAvailabilityProblem.SERVER_UNREACHABLE
    else -> SyncAvailabilityProblem.NONE
}

internal fun syncAvailabilityCopy(problem: SyncAvailabilityProblem): SyncAvailabilityCopy? = when (problem) {
    SyncAvailabilityProblem.NONE -> null
    SyncAvailabilityProblem.NO_NETWORK -> SyncAvailabilityCopy(
        title = "Offline — no internet connection",
        detail = "You are viewing saved data. Any offline-capable change is saved locally, " +
            "shown as waiting to sync, and sent automatically when the connection returns.",
    )
    SyncAvailabilityProblem.SERVER_UNREACHABLE -> SyncAvailabilityCopy(
        title = "ERP server unavailable",
        detail = "Internet is connected, but the ERP server cannot be reached. You are viewing " +
            "saved data. Any offline-capable change is saved locally, shown as waiting to sync, " +
            "and sent automatically after the server reconnects.",
    )
}

/** App-wide, non-dismissible operational state; it clears only after a proven HTTP response. */
@Composable
internal fun SyncAvailabilityBanner(problem: SyncAvailabilityProblem) {
    val copy = syncAvailabilityCopy(problem) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.GoldMuted)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = Brand.Background,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                copy.title,
                color = Brand.Background,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                copy.detail,
                color = Brand.Background,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

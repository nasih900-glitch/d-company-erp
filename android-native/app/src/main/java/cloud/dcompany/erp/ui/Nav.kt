package cloud.dcompany.erp.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.Brand

/**
 * A permanent side rail rather than a bottom bar: this runs on a landscape
 * tablet on a stand, where a bottom bar wastes the wide axis and puts targets
 * under the cashier's wrist.
 */
enum class Destination(val label: String) {
    Pos("POS"),
    Gaming("Gaming"),
    Tables("Tables"),
    Kitchen("Kitchen"),
    Shift("Shift"),
    Customers("Customers"),
    Menu("Menu"),
    Staff("Staff"),
    Inventory("Stock"),
    Reports("Reports"),
    Analytics("Analytics"),
    Finance("Finance"),
    Refunds("Refunds"),
    AccessControl("Access Control"),
    Settings("Settings"),
}

@Composable
fun WorkspaceScaffold(
    header: @Composable () -> Unit,
    // Defaults to every destination — callers that need to hide one (e.g.
    // Access Control from anyone without audit access) pass a filtered list.
    destinations: List<Destination> = Destination.entries,
    content: @Composable (Destination) -> Unit,
) {
    // Saveable so the selected tab survives a process death, not just a
    // recomposition — staff should come back to where they were.
    var current by rememberSaveable { mutableStateOf(Destination.Pos) }
    // `destinations` is a real access boundary (Access Control disappears
    // the moment auditAccess is false), not just a display preference — a
    // `current` restored from saved state, or left over from before this
    // account's access changed mid-session, must not go on rendering a
    // destination the rail no longer shows.
    if (current !in destinations) {
        current = destinations.firstOrNull() ?: Destination.Pos
    }

    Column(Modifier.fillMaxSize()) {
        header()
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(120.dp).fillMaxHeight().background(Brand.Surface)
                    .verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                destinations.forEach { dest ->
                    val selected = dest == current
                    Box(
                        // fillMaxSize(0f) collapsed these to zero height, so the
                        // whole rail rendered invisible in the trial run.
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Brand.Gold else Brand.SurfaceRaised)
                            .clickable { current = dest }
                            .padding(vertical = 14.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            dest.label,
                            color = if (selected) Brand.Background else Brand.Foreground,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize()) { content(current) }
        }
    }
}

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
    Inventory("Stock"),
    Reports("Reports"),
    Finance("Finance"),
    Settings("Settings"),
}

@Composable
fun WorkspaceScaffold(
    header: @Composable () -> Unit,
    content: @Composable (Destination) -> Unit,
) {
    // Saveable so the selected tab survives a process death, not just a
    // recomposition — staff should come back to where they were.
    var current by rememberSaveable { mutableStateOf(Destination.Pos) }

    Column(Modifier.fillMaxSize()) {
        header()
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(120.dp).fillMaxHeight().background(Brand.Surface)
                    .verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Destination.entries.forEach { dest ->
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

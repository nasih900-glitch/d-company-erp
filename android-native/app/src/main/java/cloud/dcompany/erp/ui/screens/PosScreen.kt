package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.net.MenuItem
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand

@Composable
fun PosScreen(
    state: PosUiState,
    onAdd: (MenuItem) -> Unit,
    onRemove: (MenuItem) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClearCart: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Brand.Gold)
        }

        state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Try again") }
            }
        }

        else -> Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(12.dp)) {
                CategoryStrip(state, onSelectCategory)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    // Adaptive rather than a fixed count so the same build is
                    // usable on a 8" tablet and a 12" one without a rewrite.
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.visibleItems, key = { it.id }) { item ->
                        MenuTile(item) { onAdd(item) }
                    }
                }
            }

            CartPanel(state, onAdd, onRemove, onClearCart)
        }
    }
}

@Composable
private fun CategoryStrip(state: PosUiState, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = state.selectedCategoryId == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                ),
            )
        }
        items(state.categories, key = { it.id }) { category ->
            FilterChip(
                selected = state.selectedCategoryId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                ),
            )
        }
    }
}

@Composable
private fun MenuTile(item: MenuItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.SurfaceRaised)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.Foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.basePriceMinor.asRupees(),
            style = MaterialTheme.typography.labelLarge,
            color = Brand.Gold,
        )
    }
}

@Composable
private fun CartPanel(
    state: PosUiState,
    onAdd: (MenuItem) -> Unit,
    onRemove: (MenuItem) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxSize()
            .background(Brand.Surface)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cart", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            if (state.cart.isNotEmpty()) {
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (state.cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("Tap an item to start", color = Brand.ForegroundMuted)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.cart, key = { it.item.id }) { line ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                line.item.name,
                                color = Brand.Foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (line.item.basePriceMinor * line.qty).asRupees(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.ForegroundMuted,
                            )
                        }
                        QtyButton("−") { onRemove(line.item) }
                        Text(
                            "${line.qty}",
                            modifier = Modifier.padding(horizontal = 10.dp),
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                        QtyButton("+") { onAdd(line.item) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Estimate", color = Brand.ForegroundMuted)
            Text(
                state.estimateMinor.asRupees(),
                color = Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Server calculates the final bill",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { },
            enabled = state.cart.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Prepare bill · ${state.cartCount} item${if (state.cartCount == 1) "" else "s"}")
        }
    }
}

@Composable
private fun QtyButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.SurfaceRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Brand.Gold, fontWeight = FontWeight.Bold)
    }
}

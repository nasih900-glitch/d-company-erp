package cloud.dcompany.erp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Motion
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

/**
 * A permanent side rail rather than a bottom bar: this runs on a landscape
 * tablet on a stand, where a bottom bar wastes the wide axis and puts targets
 * under the cashier's wrist.
 */
enum class Destination(val label: String, val icon: ImageVector) {
    Pos("POS", Icons.Filled.PointOfSale),
    Gaming("Gaming", Icons.Filled.SportsEsports),
    Tables("Tables", Icons.Filled.TableRestaurant),
    Kitchen("Kitchen", Icons.Filled.Restaurant),
    Shift("Shift", Icons.Filled.Schedule),
    Customers("Customers", Icons.Filled.People),
    Menu("Menu", Icons.Filled.Keyboard),
    Staff("Staff", Icons.Filled.Groups),
    Inventory("Stock", Icons.Filled.Inventory2),
    Reports("Reports", Icons.Filled.Assessment),
    Analytics("Analytics", Icons.Filled.Analytics),
    Finance("Finance", Icons.Filled.AttachMoney),
    Events("Events", Icons.Filled.Celebration),
    Memberships("Memberships", Icons.Filled.CardMembership),
    Refunds("Refunds", Icons.AutoMirrored.Filled.Undo),
    AuditLog("Audit Log", Icons.Filled.History),
    AccessControl("Access Control", Icons.Filled.AdminPanelSettings),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun WorkspaceScaffold(
    header: @Composable () -> Unit,
    // Defaults to every destination — callers that need to hide one (e.g.
    // Access Control from anyone without audit access) pass a filtered list.
    destinations: List<Destination> = Destination.entries,
    currentDestination: Destination,
    onDestinationChanged: (Destination) -> Unit = {},
    content: @Composable (Destination, navigateTo: (Destination) -> Unit) -> Unit,
) {
    // `destinations` is a real access boundary (Access Control disappears
    // the moment auditAccess is false), not just a display preference — a
    // `current` restored from saved state, or left over from before this
    // account's access changed mid-session, must not go on rendering a
    // destination the rail no longer shows.
    val current = currentDestination.takeIf { it in destinations }
        ?: destinations.firstOrNull()
        ?: Destination.Pos
    LaunchedEffect(currentDestination, current) {
        if (currentDestination != current) onDestinationChanged(current)
    }

    Column(Modifier.fillMaxSize()) {
        header()
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(124.dp).fillMaxHeight()
                    .background(Brand.surfaceFade())
                    .verticalScroll(rememberScrollState()).padding(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                destinations.forEach { dest ->
                    NavRailItem(dest, selected = dest == current) { onDestinationChanged(dest) }
                }
            }
            Box(Modifier.fillMaxSize()) {
                // A quick crossfade beats an instant cut between destinations —
                // cheap to add (AnimatedContent, no bespoke transition math) and
                // it's the single change that makes every tab switch in the app
                // feel considered rather than jarring.
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        (fadeIn(tween(Motion.medium, easing = Motion.emphasized)))
                            .togetherWith(fadeOut(tween(Motion.fast)))
                    },
                    label = "destinationCrossfade",
                ) { dest ->
                    content(dest) { target ->
                        if (target in destinations) onDestinationChanged(target)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRailItem(dest: Destination, selected: Boolean, onClick: () -> Unit) {
    // Built directly rather than through the shared Panel primitive — Panel's
    // padding/elevation is tuned for content cards, not a compact nav row.
    val interaction = remember { MutableInteractionSource() }
    val background = if (selected) Brand.GoldSheen else Brand.surfaceFade(Brand.SurfaceRaised, Brand.SurfaceRaised)
    Column(
        Modifier.fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(background)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            dest.icon,
            // The adjacent text supplies the tab label. Repeating it on the
            // decorative icon makes TalkBack announce each destination twice.
            contentDescription = null,
            tint = if (selected) Brand.Background else Brand.ForegroundMuted,
            modifier = Modifier.size(22.dp),
        )
        Text(
            dest.label,
            color = if (selected) Brand.Background else Brand.Foreground,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
    }
}

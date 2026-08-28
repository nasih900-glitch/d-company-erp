package cloud.dcompany.erp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.R
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.core.sync.outboxWorkVisibleLabel
import cloud.dcompany.erp.ui.components.SyncAvailabilityBanner
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

/** Permission-filtered destinations. The shell never manufactures routes that
 * the authenticated profile cannot access. */
enum class Destination(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    Pos("POS", "Take orders and payments", Icons.Filled.PointOfSale),
    Gaming("Gaming", "Manage stations and sessions", Icons.Filled.SportsEsports),
    Tables("Tables", "Open and manage table orders", Icons.Filled.TableRestaurant),
    Reservations("Reservations", "Manage table and gaming bookings", Icons.Filled.CalendarMonth),
    Kitchen("Kitchen", "Prepare and complete kitchen tickets", Icons.Filled.Restaurant),
    Shift("Shift", "Open, review and close today's shift", Icons.Filled.Schedule),
    Customers("Customers", "Find customers and loyalty history", Icons.Filled.People),
    Menu("Menu", "Manage categories, items and pricing", Icons.Filled.Keyboard),
    Staff("Staff", "Manage employees and attendance", Icons.Filled.Groups),
    Inventory("Stock", "Receive, count and adjust stock", Icons.Filled.Inventory2),
    Reports("Reports", "Review operational reports", Icons.Filled.Assessment),
    Analytics("Analytics", "Explore performance trends", Icons.Filled.Analytics),
    Finance("Finance", "Track business finances", Icons.Filled.AttachMoney),
    Events("Events", "Manage events and ticketing", Icons.Filled.Celebration),
    Memberships("Memberships", "Manage plans and member credit", Icons.Filled.CardMembership),
    Refunds("Refunds", "Review and process refunds", Icons.AutoMirrored.Filled.Undo),
    AuditLog("Audit Log", "Review protected activity history", Icons.Filled.History),
    AccessControl("Access Control", "Manage roles and permissions", Icons.Filled.AdminPanelSettings),
    Settings("Settings", "Account, branch and device settings", Icons.Filled.Settings),
}

/**
 * Responsive, touch-first workspace shell. The permanent rail remains fast on
 * a tablet stand, but collapses below laptop width so portrait tablets keep
 * enough room for the active workflow.
 */
@Composable
fun WorkspaceScaffold(
    destinations: List<Destination> = Destination.entries,
    currentDestination: Destination,
    employeeName: String,
    locationLabel: String,
    connectivityProblem: SyncAvailabilityProblem,
    outboxWorkStatus: OutboxWorkStatus,
    syncing: Boolean,
    pendingSupportCount: Int = 0,
    canChangeTill: Boolean,
    onOpenSupport: () -> Unit,
    onChangeTill: () -> Unit,
    onSignOut: () -> Unit,
    onDestinationChanged: (Destination) -> Unit = {},
    content: @Composable (Destination, navigateTo: (Destination) -> Unit) -> Unit,
) {
    val current = currentDestination.takeIf { it in destinations }
        ?: destinations.firstOrNull()
        ?: Destination.Settings
    LaunchedEffect(currentDestination, current) {
        if (currentDestination != current) onDestinationChanged(current)
    }

    var commandOpen by remember { mutableStateOf(false) }
    val destinationStateHolder = rememberSaveableStateHolder()

    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Background)) {
        val compact = maxWidth < 1_000.dp
        val narrow = maxWidth < 760.dp
        val railWidth = when {
            narrow -> 76.dp
            compact -> 88.dp
            else -> 184.dp
        }

        Row(Modifier.fillMaxSize()) {
            WorkspaceSidebar(
                destinations = destinations,
                current = current,
                expanded = !compact,
                narrow = narrow,
                employeeName = employeeName,
                locationLabel = locationLabel,
                modifier = Modifier.width(railWidth).fillMaxHeight(),
                onSelect = onDestinationChanged,
            )

            Column(Modifier.weight(1f).fillMaxHeight()) {
                WorkspaceHeader(
                    destination = current,
                    employeeName = employeeName,
                    locationLabel = locationLabel,
                    connectivityProblem = connectivityProblem,
                    outboxWorkStatus = outboxWorkStatus,
                    syncing = syncing,
                    pendingSupportCount = pendingSupportCount,
                    compact = compact,
                    canChangeTill = canChangeTill,
                    onOpenCommand = { commandOpen = true },
                    onOpenSupport = onOpenSupport,
                    onChangeTill = onChangeTill,
                    onSignOut = onSignOut,
                )
                SyncAvailabilityBanner(connectivityProblem)
                Box(Modifier.fillMaxSize()) {
                    // A full-screen crossfade renders both destination trees into
                    // overlapping layers. On the target 2560 x 1600 tablet that
                    // turns every sidebar tap into several expensive 4 MP frames,
                    // delaying both the visual response and the newly opened
                    // screen's first interaction. Route changes are operational
                    // navigation, so swap the content immediately and reserve
                    // motion for small, local state changes.
                    destinationStateHolder.SaveableStateProvider(current.name) {
                        content(current) { target ->
                            if (target in destinations) onDestinationChanged(target)
                        }
                    }
                }
            }
        }
    }

    if (commandOpen) {
        DestinationCommandDialog(
            destinations = destinations,
            onDismiss = { commandOpen = false },
            onSelect = {
                commandOpen = false
                onDestinationChanged(it)
            },
        )
    }
}

@Composable
private fun WorkspaceSidebar(
    destinations: List<Destination>,
    current: Destination,
    expanded: Boolean,
    narrow: Boolean,
    employeeName: String,
    locationLabel: String,
    modifier: Modifier = Modifier,
    onSelect: (Destination) -> Unit,
) {
    val mainDestinations = destinations.filterNot { it == Destination.Settings }
    val settingsAvailable = Destination.Settings in destinations

    Column(
        modifier
            .background(Brand.BackgroundSecondary)
            .border(width = 1.dp, color = Brand.BorderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (expanded) 76.dp else 68.dp)
                .padding(horizontal = if (expanded) Spacing.md else Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            Image(
                // Adaptive launcher XML is not a supported Compose painter
                // resource and crashes on API 26+. The foreground asset is
                // the same real brand artwork, supplied as density-aware PNG.
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = "D Company",
                modifier = Modifier.size(if (expanded) 54.dp else 50.dp).clip(CircleShape),
            )
            if (expanded) {
                Spacer(Modifier.width(Spacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "D COMPANY",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        "PLAY. EAT. CONNECT.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            letterSpacing = 0.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = Brand.BorderSubtle)

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.sm,
                vertical = Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            items(mainDestinations, key = Destination::name) { destination ->
                WorkspaceNavItem(
                    destination = destination,
                    selected = destination == current,
                    expanded = expanded,
                    onClick = { onSelect(destination) },
                )
            }
        }

        if (settingsAvailable) {
            HorizontalDivider(color = Brand.BorderSubtle, modifier = Modifier.padding(horizontal = Spacing.sm))
            WorkspaceNavItem(
                destination = Destination.Settings,
                selected = current == Destination.Settings,
                expanded = expanded,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                onClick = { onSelect(Destination.Settings) },
            )
        }

        WorkspaceIdentity(
            employeeName = employeeName,
            locationLabel = locationLabel,
            expanded = expanded,
            narrow = narrow,
        )
    }
}

@Composable
private fun WorkspaceNavItem(
    destination: Destination,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val selectedBackground = Brand.Gold.copy(alpha = 0.14f)
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(Radius.shapeSm)
            .background(if (selected) selectedBackground else Color.Transparent)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "${destination.label}. ${destination.description}"
                this.selected = selected
            },
    ) {
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(3.dp).height(26.dp)
                    .clip(Radius.shapePill).background(Brand.Gold),
            )
        }
        if (expanded) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = if (selected) Brand.Gold else Brand.ForegroundMuted,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    destination.label,
                    color = if (selected) Brand.Foreground else Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val compactLabel = if (destination == Destination.AccessControl) "Access" else destination.label
            Column(
                Modifier.fillMaxSize().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = if (selected) Brand.Gold else Brand.ForegroundMuted,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    compactLabel,
                    color = if (selected) Brand.Foreground else Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceIdentity(
    employeeName: String,
    locationLabel: String,
    expanded: Boolean,
    narrow: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm)
            .clip(Radius.shapeMd).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
            .padding(if (expanded) Spacing.md else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Box(
            Modifier.size(if (narrow) 32.dp else 36.dp).clip(CircleShape)
                .background(Brand.SurfaceHover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Brand.Gold, modifier = Modifier.size(20.dp))
        }
        if (expanded) {
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    employeeName,
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    locationLabel,
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    color = Brand.Disabled,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    destination: Destination,
    employeeName: String,
    locationLabel: String,
    connectivityProblem: SyncAvailabilityProblem,
    outboxWorkStatus: OutboxWorkStatus,
    syncing: Boolean,
    pendingSupportCount: Int,
    compact: Boolean,
    canChangeTill: Boolean,
    onOpenCommand: () -> Unit,
    onOpenSupport: () -> Unit,
    onChangeTill: () -> Unit,
    onSignOut: () -> Unit,
) {
    var accountMenuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(76.dp).background(Brand.BackgroundSecondary)
            .border(width = 1.dp, color = Brand.BorderSubtle)
            .padding(horizontal = if (compact) Spacing.lg else Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                destination.label,
                color = Brand.Foreground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                destination.description,
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!compact) {
            Row(
                Modifier.widthIn(min = 280.dp, max = 380.dp).heightIn(min = 48.dp)
                    .clip(Radius.shapeMd).background(Brand.Surface)
                    .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                    .clickable(onClick = onOpenCommand)
                    .semantics { role = Role.Button; contentDescription = "Find a module" }
                    .padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Brand.ForegroundFaint, modifier = Modifier.size(20.dp))
                Text(
                    "Find a module…",
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            IconButton(onClick = onOpenCommand, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Search, contentDescription = "Find a module", tint = Brand.ForegroundMuted)
            }
        }

        ConnectivityStatus(connectivityProblem, showDetail = !compact)
        OutboxWorkStatusPill(
            status = outboxWorkStatus,
            syncing = syncing,
            showDetail = !compact,
        )

        Surface(
            color = Brand.Surface,
            shape = Radius.shapePill,
            border = androidx.compose.foundation.BorderStroke(1.dp, Brand.BorderSubtle),
            modifier = Modifier.heightIn(min = 48.dp)
                .clickable(onClick = onOpenSupport)
                .semantics {
                    role = Role.Button
                    contentDescription = if (pendingSupportCount > 0) {
                        "Help and support. $pendingSupportCount saved request${if (pendingSupportCount == 1) "" else "s"} waiting"
                    } else {
                        "Help and support"
                    }
                },
        ) {
            Row(
                Modifier.padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = Brand.ForegroundMuted,
                    modifier = Modifier.size(21.dp),
                )
                if (!compact) {
                    Text(
                        "Help",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (pendingSupportCount > 0) {
                    Text(
                        pendingSupportCount.coerceAtMost(99).toString(),
                        color = Brand.Gold,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Box {
            Row(
                Modifier.height(48.dp).clip(Radius.shapeMd)
                    .clickable { accountMenuOpen = true }
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Brand.SurfaceHover),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = Brand.Gold, modifier = Modifier.size(20.dp))
                }
                if (!compact) {
                    Column(Modifier.widthIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            employeeName,
                            color = Brand.Foreground,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            locationLabel,
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(Icons.Filled.MoreVert, contentDescription = "Account actions", tint = Brand.ForegroundMuted)
            }
            DropdownMenu(
                expanded = accountMenuOpen,
                onDismissRequest = { accountMenuOpen = false },
                containerColor = Brand.SurfaceOverlay,
            ) {
                if (canChangeTill) {
                    DropdownMenuItem(
                        text = { Text("Change till") },
                        leadingIcon = { Icon(Icons.Filled.Storefront, contentDescription = null) },
                        onClick = { accountMenuOpen = false; onChangeTill() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    onClick = { accountMenuOpen = false; onSignOut() },
                )
            }
        }
    }
}

@Composable
private fun ConnectivityStatus(problem: SyncAvailabilityProblem, showDetail: Boolean) {
    val (label, color) = when (problem) {
        SyncAvailabilityProblem.NONE -> "Online" to Brand.Good
        SyncAvailabilityProblem.NO_NETWORK -> "Offline" to Brand.Warning
        SyncAvailabilityProblem.SERVER_UNREACHABLE -> "Server issue" to Brand.Danger
    }
    Row(
        Modifier.height(44.dp).clip(Radius.shapePill).background(Brand.Surface)
            .semantics { contentDescription = "Connection status: $label" }
            .padding(horizontal = if (showDetail) Spacing.md else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        if (showDetail) {
            Text(label, color = Brand.Foreground, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun OutboxWorkStatusPill(
    status: OutboxWorkStatus,
    syncing: Boolean,
    showDetail: Boolean,
) {
    if (status.isClear && !syncing) return

    val color = when {
        status.actionRequiredCount > 0 -> Brand.Danger
        syncing -> Brand.Information
        status.retryableCount > 0 -> Brand.Warning
        else -> Brand.Information
    }
    val visibleLabel = outboxWorkVisibleLabel(status, syncing, showDetail) ?: return
    val accessibilityDetail = buildList {
        if (status.actionRequiredCount > 0) add("${status.actionRequiredCount} need review")
        if (status.retryableCount > 0) add("${status.retryableCount} waiting to sync")
        if (status.savedDraftCount > 0) add("${status.savedDraftCount} saved drafts")
        if (syncing) add("sync in progress")
    }.joinToString(", ")

    Row(
        Modifier.height(44.dp).clip(Radius.shapePill)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.34f), Radius.shapePill)
            .semantics { contentDescription = "Saved work status: $accessibilityDetail" }
            .padding(horizontal = if (showDetail) Spacing.md else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            visibleLabel,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DestinationCommandDialog(
    destinations: List<Destination>,
    onDismiss: () -> Unit,
    onSelect: (Destination) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val normalized = query.trim()
    val matches = remember(destinations, normalized) {
        if (normalized.isEmpty()) destinations
        else destinations.filter {
            it.label.contains(normalized, ignoreCase = true) ||
                it.description.contains(normalized, ignoreCase = true)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text("Go to a module", color = Brand.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search accessible modules") },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                if (matches.isEmpty()) {
                    Text(
                        "No accessible module matches \"$normalized\".",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = Spacing.xl),
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(matches, key = Destination::name) { destination ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clip(Radius.shapeMd).clickable { onSelect(destination) }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Icon(destination.icon, contentDescription = null, tint = Brand.ForegroundMuted)
                                Column(Modifier.weight(1f)) {
                                    Text(destination.label, color = Brand.Foreground, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        destination.description,
                                        color = Brand.ForegroundMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

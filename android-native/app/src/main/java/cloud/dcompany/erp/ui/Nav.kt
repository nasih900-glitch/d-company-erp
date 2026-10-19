package cloud.dcompany.erp.ui

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.core.sync.outboxWorkVisibleLabel
import cloud.dcompany.erp.ui.components.AppearanceControl
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.components.syncAvailabilityCopy
import cloud.dcompany.erp.ui.components.syncAvailabilityDialogTitle
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.next
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.remote.RemoteAssistanceActiveBanner
import cloud.dcompany.erp.ui.remote.RemoteAssistanceRequestWaitingBanner

/** Permission-filtered destinations. The shell never manufactures routes that
 * the authenticated profile cannot access. */
enum class Destination(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    Dashboard("Dashboard", "See today's operating overview", Icons.Filled.Analytics),
    Pos("POS", "Take orders and payments", Icons.Filled.PointOfSale),
    Gaming("Gaming", "Manage stations and sessions", Icons.Filled.SportsEsports),
    Tables("Tables", "Open and manage table orders", Icons.Filled.TableRestaurant),
    Reservations("Reservations", "Manage table and gaming bookings", Icons.Filled.CalendarMonth),
    Kitchen("Kitchen", "Prepare and complete kitchen tickets", Icons.Filled.Restaurant),
    Shift("Shift", "Open, review and close today's shift", Icons.Filled.Schedule),
    Customers("Customers", "Find customers and loyalty history", Icons.Filled.People),
    Menu("Products", "Manage products, categories and pricing", Icons.Filled.Keyboard),
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
    SupportInbox("Support Inbox", "Review staff help requests", Icons.AutoMirrored.Filled.HelpOutline),
    Help("Help", "Report a problem or ask what to do next", Icons.AutoMirrored.Filled.HelpOutline),
}

/**
 * Responsive workspace shell. Daily Gaming, POS, Shift and Customers screens
 * get a full-width control deck and permission-filtered bottom navigation;
 * the other modules retain their compact or expanded sidebar.
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
    remoteSupportRequestWaiting: Boolean = false,
    remoteSupportActive: Boolean = false,
    remoteSupportOnline: Boolean = true,
    remoteSupportPrivacyProtected: Boolean = true,
    remoteSupportLastCommandLabel: String? = null,
    canChangeTill: Boolean,
    appearancePreference: AppearancePreference = AppearancePreference.System,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit = {},
    onOpenSupport: () -> Unit,
    onChangeTill: () -> Unit,
    onSignOut: () -> Unit,
    onReviewRemoteSupportRequest: () -> Unit = {},
    onStopRemoteSupport: () -> Unit = {},
    onDestinationChanged: (Destination) -> Unit = {},
    content: @Composable (Destination, navigateTo: (Destination) -> Unit) -> Unit,
) {
    val current = resolveWorkspaceDestination(currentDestination, destinations)
    LaunchedEffect(currentDestination, current) {
        if (currentDestination != current) onDestinationChanged(current)
    }

    var commandMode by remember { mutableStateOf<String?>(null) }
    val destinationStateHolder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Every module shares the same header and bottom dock. Module state is
        // retained by the saveable-state holder while route changes are instant.
        val compact = maxWidth <= 1_280.dp
        val contentPane: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
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
                    appearancePreference = appearancePreference,
                    onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                    onOpenCommand = { commandMode = "search" },
                    onOpenSupport = onOpenSupport,
                    onChangeTill = onChangeTill,
                    onSignOut = onSignOut,
                )
                RemoteAssistanceRequestWaitingBanner(
                    visible = remoteSupportRequestWaiting && current != Destination.Help,
                    onReview = onReviewRemoteSupportRequest,
                )
                RemoteAssistanceActiveBanner(
                    active = remoteSupportActive,
                    online = remoteSupportOnline,
                    privacyProtected = remoteSupportPrivacyProtected,
                    lastCommandLabel = remoteSupportLastCommandLabel,
                    onStop = onStopRemoteSupport,
                )
                Box(Modifier.fillMaxSize()) {
                    destinationStateHolder.SaveableStateProvider(current.name) {
                        content(current) { target ->
                            if (target in destinations) onDestinationChanged(target)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            contentPane(Modifier.weight(1f).fillMaxWidth())
            ControlDeckBottomNavigation(
                destinations = destinations,
                current = current,
                onSelect = onDestinationChanged,
                onOpenMore = { commandMode = "more" },
            )
        }
    }

    commandMode?.let { mode ->
        DestinationCommandDialog(
            destinations = destinations,
            searchEnabled = mode == "search",
            onDismiss = { commandMode = null },
            onSelect = {
                commandMode = null
                onDestinationChanged(it)
            },
        )
    }
}

/** The four daily workflows stay one tap away; all permitted modules remain in More. */
@Composable
private fun ControlDeckBottomNavigation(
    destinations: List<Destination>,
    current: Destination,
    onSelect: (Destination) -> Unit,
    onOpenMore: () -> Unit,
) {
    val primary = listOf(
        Destination.Gaming,
        Destination.Pos,
        Destination.Shift,
        Destination.Customers,
    ).filter(destinations::contains)
    val moreSelected = current !in primary
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
            .testTag("workspace-bottom-dock"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        primary.forEach { destination ->
            val selected = destination == current
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = selected, role = Role.Tab) { onSelect(destination) }
                    .semantics {
                        contentDescription = "${destination.label}. ${destination.description}"
                        this.selected = selected
                    }
                    .padding(horizontal = Spacing.sm, vertical = 8.dp)
                    .clip(Radius.shapePill)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else Color.Transparent,
                    )
                    .padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    destination.label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .selectable(selected = moreSelected, role = Role.Tab, onClick = onOpenMore)
                .testTag("workspace-more-modules")
                .semantics {
                    contentDescription = if (moreSelected) {
                        "More modules. ${current.label} selected"
                    } else {
                        "More modules"
                    }
                    this.selected = moreSelected
                }
                .padding(horizontal = Spacing.sm, vertical = 8.dp)
                .clip(Radius.shapePill)
                .background(
                    if (moreSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else Color.Transparent,
                )
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = if (moreSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "More",
                color = if (moreSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (moreSelected) FontWeight.Bold else FontWeight.Medium,
            )
        }
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .padding(horizontal = if (expanded) Spacing.md else Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            DCompanyBrandMark(
                // The shared mark keeps the adaptive PNG path safe on API 26+
                // while cropping its square source plate into a crisp circle.
                size = if (expanded) 48.dp else 44.dp,
                contentDescription = "D Company",
            )
            if (expanded) {
                Spacer(Modifier.width(Spacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "D COMPANY",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        "OPERATIONS",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = Spacing.sm))
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
    val selectedBackground = MaterialTheme.colorScheme.secondaryContainer
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
                    .clip(Radius.shapePill).background(MaterialTheme.colorScheme.primary),
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
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    destination.label,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    compactLabel,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
            .clip(Radius.shapeMd).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd)
            .padding(if (expanded) Spacing.md else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Box(
            Modifier.size(if (narrow) 32.dp else 36.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        if (expanded) {
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    employeeName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    locationLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    appearancePreference: AppearancePreference,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit,
    onOpenCommand: () -> Unit,
    onOpenSupport: () -> Unit,
    onChangeTill: () -> Unit,
    onSignOut: () -> Unit,
) {
    var accountMenuOpen by remember { mutableStateOf(false) }
    val controlDeckDestination = destination in setOf(
        Destination.Gaming,
        Destination.Pos,
        Destination.Shift,
        Destination.Customers,
    )

    Row(
        Modifier.fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            .testTag("workspace-header")
            .padding(horizontal = if (compact) Spacing.md else Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controlDeckDestination) {
                DCompanyBrandMark(size = 32.dp, contentDescription = "D Company")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (controlDeckDestination) "D COMPANY" else destination.label,
                    color = if (controlDeckDestination) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (controlDeckDestination) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = if (controlDeckDestination) 1.sp else 0.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (controlDeckDestination) "GAMING LOUNGE" else destination.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!compact) {
            Row(
                Modifier.widthIn(min = 280.dp, max = 380.dp).heightIn(min = 48.dp)
                    .clip(Radius.shapeMd).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, Radius.shapeMd)
                    .clickable(onClick = onOpenCommand)
                    .semantics { role = Role.Button; contentDescription = "Find a module" }
                    .padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    "Find a module…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            HeaderIconAction(
                icon = Icons.Filled.Search,
                accessibilityLabel = "Find a module",
                onClick = onOpenCommand,
            )
        }

        ConnectivityStatus(
            problem = connectivityProblem,
            expanded = !compact,
            onOpenSupport = onOpenSupport,
        )
        OutboxWorkStatusPill(
            status = outboxWorkStatus,
            syncing = syncing,
            showDetail = !compact || LocalConfiguration.current.screenWidthDp >= 900,
        )

        AppearanceControl(
            preference = appearancePreference,
            onChange = onAppearancePreferenceChanged,
            modifier = Modifier.testTag("workspace-day-night-toggle"),
        )

        val supportDescription = if (pendingSupportCount > 0) {
            "Help and support. $pendingSupportCount saved request${if (pendingSupportCount == 1) "" else "s"} waiting"
        } else {
            "Help and support"
        }
        if (compact) {
            HeaderIconAction(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                accessibilityLabel = supportDescription,
                onClick = onOpenSupport,
                badgeCount = pendingSupportCount,
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = Radius.shapePill,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.heightIn(min = 48.dp)
                    .clickable(onClick = onOpenSupport)
                    .semantics {
                        role = Role.Button
                        contentDescription = supportDescription
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        "Help",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (pendingSupportCount > 0) {
                        Text(
                            pendingSupportCount.coerceAtMost(99).toString(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Box {
            if (compact) {
                HeaderIconAction(
                    icon = Icons.Filled.Person,
                    accessibilityLabel = "Account actions",
                    onClick = { accountMenuOpen = true },
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = Radius.shapeMd,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(48.dp)
                        .clickable { accountMenuOpen = true }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Account actions"
                        },
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        }
                        Column(Modifier.widthIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                employeeName,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                locationLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DropdownMenu(
                expanded = accountMenuOpen,
                onDismissRequest = { accountMenuOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = {
                        Text("Appearance · ${appearancePreference.name.lowercase().replaceFirstChar(Char::uppercase)}")
                    },
                    leadingIcon = { Icon(Icons.Filled.Brightness6, contentDescription = null) },
                    onClick = {
                        accountMenuOpen = false
                        onAppearancePreferenceChanged(appearancePreference.next())
                    },
                )
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
private fun HeaderIconAction(
    icon: ImageVector,
    accessibilityLabel: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    badgeCount: Int = 0,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = Radius.shapeMd,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(48.dp)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = accessibilityLabel
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
            if (badgeCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp)
                        .size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badgeCount.coerceAtMost(99).toString(),
                        color = MaterialTheme.colorScheme.background,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectivityStatus(
    problem: SyncAvailabilityProblem,
    expanded: Boolean,
    onOpenSupport: () -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    val (label, color, icon) = when (problem) {
        SyncAvailabilityProblem.NONE -> Triple("Online", MaterialTheme.colorScheme.operationalGood, Icons.Filled.CloudDone)
        SyncAvailabilityProblem.VERIFYING -> Triple("Checking", MaterialTheme.colorScheme.operationalInformation, Icons.Filled.Sync)
        SyncAvailabilityProblem.NO_NETWORK -> Triple("No internet", MaterialTheme.colorScheme.operationalWarning, Icons.Filled.CloudOff)
        SyncAvailabilityProblem.SERVER_UNREACHABLE -> Triple("Server issue", MaterialTheme.colorScheme.operationalDanger, Icons.Filled.Error)
        SyncAvailabilityProblem.RECOVERING -> Triple("Restoring", MaterialTheme.colorScheme.operationalInformation, Icons.Filled.Sync)
    }
    val background = if (problem == SyncAvailabilityProblem.NONE) {
        MaterialTheme.colorScheme.surface
    } else {
        color.copy(alpha = 0.12f)
    }
    val border = if (problem == SyncAvailabilityProblem.NONE) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        color.copy(alpha = 0.42f)
    }

    Surface(
        color = background,
        shape = Radius.shapePill,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            // Width is fixed for each breakpoint. A status transition changes
            // neither the header geometry nor the active workflow below it.
            .width(if (expanded) 136.dp else 124.dp)
            .height(48.dp)
            .clickable { detailsOpen = true }
            .semantics {
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
                stateDescription = label
                contentDescription = "Connection status: $label. Open connection details"
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(19.dp),
            )
            Text(
                label,
                color = if (problem == SyncAvailabilityProblem.NONE) MaterialTheme.colorScheme.onSurfaceVariant else color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (problem == SyncAvailabilityProblem.NONE) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (detailsOpen) {
        val copy = syncAvailabilityCopy(problem)
        val title = syncAvailabilityDialogTitle(problem)
        val detail = copy?.detail
            ?: "This tablet is connected to D Company ERP. Saved work can synchronise normally."
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = Radius.shapeLg,
            icon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                )
            },
            title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (problem != SyncAvailabilityProblem.NONE) {
                    TextButton(
                        onClick = {
                            detailsOpen = false
                            onOpenSupport()
                        },
                    ) {
                        Text("Report issue", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailsOpen = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun OutboxWorkStatusPill(
    status: OutboxWorkStatus,
    syncing: Boolean,
    showDetail: Boolean,
) {
    val visibleLabel = outboxWorkVisibleLabel(status, syncing, showDetail)
    Box(
        modifier = Modifier
            // Keep this slot present even when there is no saved work. A queue
            // transition must update content, not move Help/account controls
            // across the tablet header.
            .width(if (showDetail) 192.dp else 64.dp)
            .height(48.dp)
            .testTag("outbox-work-status-slot"),
        contentAlignment = Alignment.Center,
    ) {
        if (visibleLabel != null) {
            val color = when {
                status.protectedReviewUnknown || status.protectedReviewCount > 0 || status.actionRequiredCount > 0 -> MaterialTheme.colorScheme.operationalDanger
                syncing -> MaterialTheme.colorScheme.operationalInformation
                status.retryableCount > 0 -> MaterialTheme.colorScheme.operationalWarning
                else -> MaterialTheme.colorScheme.operationalInformation
            }
            val accessibilityDetail = buildList {
                if (status.protectedReviewUnknown) add("protected recovery storage unknown; owner review required")
                else if (status.protectedReviewCount > 0) add("${status.protectedReviewCount} protected requests need explicit owner recovery, not automatic Sync")
                if (status.actionRequiredCount > 0) add("${status.actionRequiredCount} need review")
                if (status.retryableCount > 0) add("${status.retryableCount} waiting to sync")
                if (status.savedDraftCount > 0) add("${status.savedDraftCount} saved drafts")
                if (syncing) add("sync in progress")
            }.joinToString(", ")

            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(Radius.shapePill)
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DestinationCommandDialog(
    destinations: List<Destination>,
    searchEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Destination) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim()
    val matches = remember(destinations, normalized) {
        if (normalized.isEmpty()) destinations
        else destinations.filter {
            it.label.contains(normalized, ignoreCase = true) ||
                it.description.contains(normalized, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).imePadding()
            .testTag(if (searchEnabled) "workspace-module-search" else "workspace-more-panel"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        title = {
            Text(
                if (searchEnabled) "Find a module" else "More modules",
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (searchEnabled) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Search accessible modules") },
                        singleLine = true,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("workspace-module-search-input"),
                    )
                }
                if (matches.isEmpty()) {
                    Text(
                        "No accessible module matches \"$normalized\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    .testTag("workspace-destination-${destination.name}")
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Icon(destination.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(destination.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        destination.description,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

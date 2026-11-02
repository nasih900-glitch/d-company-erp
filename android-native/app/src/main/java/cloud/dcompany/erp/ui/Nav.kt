package cloud.dcompany.erp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TableRestaurant
import cloud.dcompany.erp.ui.components.LiquidAlertDialog as AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.core.sync.outboxWorkVisibleLabel
import cloud.dcompany.erp.ui.components.AppearanceControl
import cloud.dcompany.erp.ui.components.LiquidDropdownMenu
import cloud.dcompany.erp.ui.components.liquidWorkspaceSurface
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
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundRenderer
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.workspacePresentationAccent
import cloud.dcompany.erp.ui.theme.workspaceGlass
import cloud.dcompany.erp.ui.theme.workspaceGlassRoleStyle
import cloud.dcompany.erp.ui.theme.workspaceBackgroundSlot
import cloud.dcompany.erp.ui.remote.RemoteAssistanceActiveBanner
import cloud.dcompany.erp.ui.remote.RemoteAssistanceRequestWaitingBanner
import cloud.dcompany.erp.ui.screens.home.ExecutiveHomeShellLayout
import cloud.dcompany.erp.ui.screens.home.ExecutiveHomeType
import cloud.dcompany.erp.ui.screens.home.LocalExecutiveHomeShellLayout
import cloud.dcompany.erp.ui.screens.home.executiveHomePalette
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

private val ExecutiveRailOrder = listOf(
    Destination.Dashboard,
    Destination.Gaming,
    Destination.Pos,
    Destination.Shift,
    Destination.Customers,
    Destination.Inventory,
    Destination.Reports,
)

internal fun executiveRailDestinations(destinations: List<Destination>): List<Destination> =
    ExecutiveRailOrder.filter(destinations::contains)

internal fun executiveRailVisibleLabel(destination: Destination): String = when (destination) {
    Destination.Dashboard -> "Home"
    Destination.Inventory -> "Inventory"
    else -> destination.label
}

internal fun usesExecutiveNavigationRail(width: Dp, height: Dp): Boolean =
    width >= 960.dp && width > height

internal val ExecutiveSidebarWidth = 184.dp
internal val ExecutiveUtilityHeight = 56.dp

internal data class WorkspaceHeaderLayoutPolicy(
    val searchWidth: Dp,
    val showOutboxDetail: Boolean,
)

internal fun workspaceHeaderLayoutPolicy(
    availableWidth: Dp,
    hasOutboxWork: Boolean,
): WorkspaceHeaderLayoutPolicy = WorkspaceHeaderLayoutPolicy(
    searchWidth = 264.dp,
    showOutboxDetail = hasOutboxWork && availableWidth >= 1_104.dp,
)

internal fun homeAppearanceControlWidth(fontScale: Float): Dp =
    if (fontScale > 1.15f) 136.dp else 112.dp

internal fun homeDateTimeWidth(fontScale: Float): Dp =
    if (fontScale > 1.15f) 208.dp else 184.dp

internal fun homeTimeTextWidth(fontScale: Float): Dp =
    if (fontScale > 1.15f) 76.dp else 62.dp

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * Responsive workspace shell. Landscape tablet workspaces use the shared
 * Executive Dark rail; compact and portrait windows retain the bottom dock.
 */
@Composable
internal fun WorkspaceScaffold(
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
    railRouteAuthority: ExecutiveRailRouteAuthority? = null,
    onRailDestinationBeforeCommit: (Destination) -> Unit = {},
    onRailDestinationAccepted: (Destination) -> Unit = {},
    backgroundState: WorkspaceBackgroundUiState = WorkspaceBackgroundUiState(),
    railScrubTrace: ExecutiveRailScrubTraceSink? = null,
    commandDialogRootObserver: ((View) -> Unit)? = null,
    commandDialogSceneObserver: ((WorkspaceGlassScene?) -> Unit)? = null,
    overlays: @Composable () -> Unit = {},
    content: @Composable (Destination, navigateTo: (Destination) -> Unit) -> Unit,
) {
    val currentOverlays by rememberUpdatedState(overlays)
    // Move the same overlay composition between scene hosts during resizing;
    // drafts, focus and capture-privacy leases must not be recreated.
    val sceneOverlays = remember { movableContentOf { currentOverlays() } }
    val current = resolveWorkspaceDestination(currentDestination, destinations)
    LaunchedEffect(currentDestination, current) {
        if (currentDestination != current) onDestinationChanged(current)
    }

    var commandMode by rememberSaveable { mutableStateOf<String?>(null) }
    var commandQuery by rememberSaveable { mutableStateOf("") }
    var navigationRailVisible by rememberSaveable { mutableStateOf(true) }
    val platformTouchExplorationEnabled = rememberTouchExplorationEnabled()
    val touchExplorationEnabled = LocalExecutiveRailTouchExplorationOverride.current
        ?: platformTouchExplorationEnabled
    val destinationStateHolder = rememberSaveableStateHolder()
    val navigateAuthorized: (Destination) -> Unit = { target ->
        if (target in destinations) onDestinationChanged(target)
    }
    val openCommand: (String) -> Unit = { mode ->
        commandQuery = ""
        commandMode = mode
    }
    val commandDialog: @Composable () -> Unit = {
        commandMode?.let { mode ->
            val commandScene = LocalWorkspaceGlassScene.current
            SideEffect { commandDialogSceneObserver?.invoke(commandScene) }
            val commandDestinations = if (mode == "more-wide") {
                val primary = executiveRailDestinations(destinations).toSet() + Destination.Settings
                destinations.filterNot(primary::contains)
            } else {
                destinations
            }
            DestinationCommandDialog(
                destinations = commandDestinations,
                searchEnabled = mode == "search",
                query = commandQuery,
                onQueryChange = { commandQuery = it },
                onRootObserved = commandDialogRootObserver,
                onDismiss = {
                    commandMode = null
                    commandQuery = ""
                },
                onSelect = {
                    commandMode = null
                    commandQuery = ""
                    navigateAuthorized(it)
                },
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Every module shares the same header and navigation shell. Module state
        // is retained by the saveable-state holder while route changes are instant.
        val showNavigationRail = usesExecutiveNavigationRail(maxWidth, maxHeight)
        val compact = !showNavigationRail
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
                    onOpenCommand = { openCommand("search") },
                    onOpenSupport = onOpenSupport,
                    onChangeTill = onChangeTill,
                    onSignOut = onSignOut,
                    homeFinish = current == Destination.Dashboard,
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
                        content(current, navigateAuthorized)
                    }
                }
            }
        }
        if (showNavigationRail) {
            val finish = executiveHomePalette()
            val density = LocalDensity.current
            val framePadding = 8.dp
            val framedWidth = maxWidth - framePadding * 2
            val railWidth by animateDpAsState(
                targetValue = if (navigationRailVisible) ExecutiveSidebarWidth else 0.dp,
                label = "workspace-navigation-rail-width",
            )
            val railVisibilityCommitDistancePx = with(density) { 56.dp.toPx() }
            val railRevealEdgeWidthPx = with(density) { 28.dp.toPx() }
            val railRevealExclusionHeightPx = with(density) { 160.dp.toPx() }
            // Gesture ownership starts at the window edge, outside the decorative8dp frame.
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (!navigationRailVisible && !touchExplorationEnabled) {
                            Modifier.systemGestureExclusion { coordinates ->
                                val bandHeight = railRevealExclusionHeightPx.coerceAtMost(
                                    coordinates.size.height.toFloat(),
                                )
                                val bandTop =
                                    (coordinates.size.height.toFloat() - bandHeight) / 2f
                                Rect(
                                    left = 0f,
                                    top = bandTop,
                                    right = railRevealEdgeWidthPx,
                                    bottom = bandTop + bandHeight,
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .executiveRailVisibilitySwipe(
                        enabled = !navigationRailVisible && !touchExplorationEnabled,
                        direction = ExecutiveRailVisibilityGestureDirection.ShowToRight,
                        commitDistancePx = railVisibilityCommitDistancePx,
                        edgeStartWidthPx = railRevealEdgeWidthPx,
                        onCommit = { navigationRailVisible = true },
                    )
                    .testTag("workspace-wide-gesture-host"),
            ) {
                Box(
                    Modifier.fillMaxSize().padding(framePadding)
                        .clip(Radius.shapeLg)
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, finish.outline, Radius.shapeLg)
                        .testTag("executive-home-frame"),
                ) {
                    WorkspaceSceneHost(
                        backgroundState = backgroundState,
                        destination = current,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                      Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            Column(
                                Modifier.fillMaxWidth().padding(start = railWidth)
                                    .testTag("workspace-wide-top-stack"),
                            ) {
                                WorkspaceHeader(
                                    destination = current,
                                    employeeName = employeeName,
                                    locationLabel = locationLabel,
                                    connectivityProblem = connectivityProblem,
                                    outboxWorkStatus = outboxWorkStatus,
                                    syncing = syncing,
                                    pendingSupportCount = pendingSupportCount,
                                    compact = false,
                                    canChangeTill = canChangeTill,
                                    appearancePreference = appearancePreference,
                                    onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                                    onOpenCommand = { openCommand("search") },
                                    onOpenSupport = onOpenSupport,
                                    onChangeTill = onChangeTill,
                                    onSignOut = onSignOut,
                                    homeFinish = true,
                                    leadingContent = if (!navigationRailVisible) {
                                        { WorkspaceRailRevealButton { navigationRailVisible = true } }
                                    } else {
                                        null
                                    },
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
                            }
                        },
                      ) { innerPadding ->
                        val topBarHeight = innerPadding.calculateTopPadding()
                        val noticeHeight = (topBarHeight - ExecutiveUtilityHeight).coerceAtLeast(0.dp)
                        val shellLayout = ExecutiveHomeShellLayout(
                            enabled = true,
                            railWidth = railWidth,
                            utilityHeight = ExecutiveUtilityHeight,
                            noticeHeight = noticeHeight,
                        )
                        Box(
                            Modifier.fillMaxSize()
                                .testTag("workspace-wide-body"),
                        ) {
                            CompositionLocalProvider(LocalExecutiveHomeShellLayout provides shellLayout) {
                                destinationStateHolder.SaveableStateProvider(current.name) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .then(
                                                Modifier.padding(
                                                    start = railWidth,
                                                    top = if (current == Destination.Dashboard) 0.dp else topBarHeight,
                                                ),
                                            )
                                            .testTag("workspace-route-content"),
                                    ) {
                                        content(current, navigateAuthorized)
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = navigationRailVisible,
                                modifier = Modifier.align(Alignment.CenterStart),
                                enter = slideInHorizontally { -it } + fadeIn(),
                                exit = slideOutHorizontally { -it } + fadeOut(),
                            ) {
                                ExecutiveNavigationRail(
                                    destinations = destinations,
                                    current = current,
                                    onSelect = navigateAuthorized,
                                    onOpenMore = { openCommand("more-wide") },
                                    modifier = Modifier.width(ExecutiveSidebarWidth).fillMaxHeight(),
                                    homeFinish = true,
                                    showMore = true,
                                    railRouteAuthority = railRouteAuthority,
                                    onRailDestinationBeforeCommit = onRailDestinationBeforeCommit,
                                    onRailDestinationAccepted = onRailDestinationAccepted,
                                    railScrubTrace = railScrubTrace,
                                    visibilityGesturesEnabled = !touchExplorationEnabled,
                                    touchExplorationEnabled = touchExplorationEnabled,
                                    onHide = { navigationRailVisible = false },
                                )
                            }
                        }
                      }
                      if (!navigationRailVisible && !touchExplorationEnabled) {
                          Box(
                              Modifier.align(Alignment.CenterStart)
                                  .width(12.dp).height(32.dp)
                                  .workspaceGlass(
                                      LocalWorkspaceGlassScene.current,
                                      WorkspaceGlassRole.CONTROL,
                                      MaterialTheme.colorScheme.background.luminance() > 0.5f,
                                      Radius.shapeMd,
                                  )
                                  .semantics { contentDescription = "Swipe right to show sidebar" }
                                  .testTag("workspace-rail-reveal-hint"),
                          ) {
                              Box(
                                  Modifier.align(Alignment.Center).width(3.dp).height(18.dp)
                                      .background(finish.gold.copy(alpha = 0.8f), CircleShape),
                              )
                          }
                      }
                      commandDialog()
                      sceneOverlays()
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                WorkspaceSceneHost(
                    backgroundState = backgroundState,
                    destination = current,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        contentPane(Modifier.weight(1f).fillMaxWidth())
                        ControlDeckBottomNavigation(
                            destinations = destinations,
                            current = current,
                            onSelect = navigateAuthorized,
                            onOpenMore = { openCommand("more") },
                        )
                    }
                    commandDialog()
                    sceneOverlays()
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSceneHost(
    backgroundState: WorkspaceBackgroundUiState,
    destination: Destination,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (backgroundState.initializing) {
        Box(
            modifier.background(MaterialTheme.colorScheme.background)
                .testTag("workspace-background-initializing"),
        ) {
            content()
        }
    } else {
        WorkspaceBackgroundRenderer(
            image = backgroundState.image(workspaceBackgroundSlot(destination)),
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun ExecutiveNavigationRail(
    destinations: List<Destination>,
    current: Destination,
    onSelect: (Destination) -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier.width(ExecutiveSidebarWidth).fillMaxHeight(),
    homeFinish: Boolean = false,
    showMore: Boolean = true,
    railRouteAuthority: ExecutiveRailRouteAuthority? = null,
    onRailDestinationBeforeCommit: (Destination) -> Unit = {},
    onRailDestinationAccepted: (Destination) -> Unit = {},
    railScrubTrace: ExecutiveRailScrubTraceSink? = null,
    visibilityGesturesEnabled: Boolean = true,
    touchExplorationEnabled: Boolean = false,
    onHide: () -> Unit = {},
) {
    val finish = executiveHomePalette()
    val primary = executiveRailDestinations(destinations)
    val settingsAvailable = Destination.Settings in destinations
    val additional = destinations.filterNot { it in primary || it == Destination.Settings }
    val moreSelected = current in additional
    val directTargets = remember(primary, settingsAvailable) {
        primary + if (settingsAvailable) listOf(Destination.Settings) else emptyList()
    }
    val targetBounds = remember { mutableMapOf<Destination, Rect>() }
    val viewportBounds = remember { arrayOfNulls<Rect>(1) }
    val railScrollState = rememberScrollState()
    val density = LocalDensity.current
    val scrubPolicy = remember(density) {
        ExecutiveRailScrubPolicy(
            boundaryHysteresisPx = with(density) { 5.dp.toPx() },
            edgeZonePx = with(density) { 30.dp.toPx() },
            maximumEdgeStepPx = with(density) { 8.dp.toPx() },
        )
    }
    val onRailDestinationBeforeCommitState = rememberUpdatedState(onRailDestinationBeforeCommit)
    val onRailDestinationAcceptedState = rememberUpdatedState(onRailDestinationAccepted)
    var scrubDescription by remember { mutableStateOf<String?>(null) }
    Box(
        modifier.executiveRailVisibilitySwipe(
            enabled = visibilityGesturesEnabled,
            direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
            commitDistancePx = with(density) { 56.dp.toPx() },
            onCommit = onHide,
        ).testTag("workspace-navigation-rail"),
    ) {
        val scene = LocalWorkspaceGlassScene.current
        val day = MaterialTheme.colorScheme.background.luminance() > 0.5f
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (homeFinish) {
                        Modifier.workspaceGlass(
                            scene,
                            WorkspaceGlassRole.RAIL,
                            day,
                            androidx.compose.ui.graphics.RectangleShape,
                        )
                    } else Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                )
                .then(
                    if (homeFinish) Modifier else Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                .testTag("workspace-navigation-rail-surface"),
        )
        Column(
            Modifier.fillMaxSize()
                .testTag("workspace-navigation-rail-controls"),
        ) {
            Row(
                Modifier.fillMaxWidth().height(if (homeFinish) 56.dp else 64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DCompanyBrandMark(
                    size = 34.dp,
                    contentDescription = "D Company",
                    modifier = Modifier.testTag("workspace-rail-brand-mark"),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "D COMPANY",
                        modifier = Modifier.fillMaxWidth().testTag("workspace-rail-brand-company"),
                        color = if (homeFinish) finish.text else MaterialTheme.colorScheme.onSurface,
                        style = if (homeFinish) ExecutiveHomeType.Utility else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                    )
                    Text(
                        "GAMING LOUNGE",
                        modifier = Modifier.fillMaxWidth().testTag("workspace-rail-brand-lounge"),
                        color = if (homeFinish) finish.gold else MaterialTheme.colorScheme.primary,
                        style = if (homeFinish) ExecutiveHomeType.ShortcutMeta
                        else MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        letterSpacing = 0.7.sp,
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider(
                color = if (homeFinish) finish.outline.copy(alpha = .42f)
                else MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned { viewportBounds[0] = it.boundsInRoot() }
                    .testTag("workspace-navigation-rail-scrub-viewport")
                    .then(
                        if (railRouteAuthority == null) {
                            Modifier
                        } else {
                            Modifier.executiveRailLiveScrub(
                                enabled = executiveRailScrubEnabled(touchExplorationEnabled),
                                modelKey = directTargets,
                                policy = scrubPolicy,
                                scrollState = railScrollState,
                                viewportBoundsInRoot = { viewportBounds[0] },
                                targetBoundsInRoot = {
                                    directTargets.mapNotNull { destination ->
                                        targetBounds[destination]?.let { bounds ->
                                            ExecutiveRailTargetBounds(
                                                destination = destination,
                                                left = bounds.left,
                                                top = bounds.top,
                                                right = bounds.right,
                                                bottom = bounds.bottom,
                                            )
                                        }
                                    }
                                },
                                routeAuthority = railRouteAuthority,
                                onRouteBeforeCommit = {
                                    onRailDestinationBeforeCommitState.value(it)
                                },
                                onRouteAfterCommit = {
                                    onRailDestinationAcceptedState.value(it)
                                },
                                onCandidateDescription = { scrubDescription = it },
                                trace = railScrubTrace,
                            )
                        },
                    )
                    .semantics {
                        stateDescription = scrubDescription ?: "Navigation rail"
                    }
                    .verticalScroll(railScrollState)
                    .padding(
                        horizontal = if (homeFinish) 10.dp else 8.dp,
                        vertical = if (homeFinish) 3.dp else 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (homeFinish) 0.dp else 4.dp),
            ) {
                primary.forEach { destination ->
                    ExecutiveRailItem(
                        icon = if (homeFinish && destination == Destination.Dashboard) {
                            Icons.Filled.Home
                        } else {
                            destination.icon
                        },
                        label = executiveRailVisibleLabel(destination),
                        description = destination.description,
                        selected = destination == current,
                        testTag = "workspace-rail-${destination.name}",
                        onClick = { onSelect(destination) },
                        homeFinish = homeFinish,
                        onBoundsChanged = { targetBounds[destination] = it },
                    )
                }
                if (settingsAvailable) {
                    ExecutiveRailItem(
                        icon = Destination.Settings.icon,
                        label = Destination.Settings.label,
                        description = Destination.Settings.description,
                        selected = current == Destination.Settings,
                        testTag = "workspace-rail-Settings",
                        onClick = { onSelect(Destination.Settings) },
                        homeFinish = homeFinish,
                        onBoundsChanged = { targetBounds[Destination.Settings] = it },
                    )
                }
                if (showMore && additional.isNotEmpty()) {
                    ExecutiveRailItem(
                        icon = Icons.Filled.MoreVert,
                        label = "More",
                        description = if (moreSelected) "${current.label} selected" else "Open more modules",
                        selected = moreSelected,
                        testTag = "workspace-more-modules",
                        onClick = onOpenMore,
                        homeFinish = homeFinish,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                IconButton(
                    onClick = onHide,
                    modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)
                        .testTag("workspace-rail-hide"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Hide navigation sidebar",
                        tint = if (homeFinish) finish.textMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecutiveRailItem(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    homeFinish: Boolean = false,
    onBoundsChanged: ((Rect) -> Unit)? = null,
) {
    val finish = executiveHomePalette()
    val day = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val selectedGlass = workspaceGlassRoleStyle(WorkspaceGlassRole.SELECTED_OUTLINE, day)
    Box(
        Modifier.fillMaxWidth().height(if (homeFinish) 48.dp else 52.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(testTag)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged?.invoke(coordinates.boundsInRoot())
            }
            .semantics {
                contentDescription = "$label. $description"
                this.selected = selected
            },
    ) {
        Box(
            Modifier.fillMaxSize()
                .padding(horizontal = if (homeFinish) 4.dp else 8.dp, vertical = 4.dp)
                .clip(Radius.shapeSm)
                .then(
                    when {
                        selected && homeFinish -> Modifier.workspaceGlass(
                            LocalWorkspaceGlassScene.current,
                            WorkspaceGlassRole.SELECTED_OUTLINE,
                            day,
                            Radius.shapeSm,
                        )
                        selected -> Modifier.background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.76f),
                        )
                        else -> Modifier.background(Color.Transparent)
                    },
                )
                .testTag("$testTag-decoration"),
        ) {
            if (selected && homeFinish) {
                Box(
                    Modifier.align(Alignment.CenterStart).width(48.dp).fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(finish.gold.copy(alpha = if (day) 0.08f else 0.10f), Color.Transparent),
                            ),
                        ),
                )
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 1.dp)
                        .width(2.dp).height(22.dp)
                        .background(finish.gold.copy(alpha = 0.72f), Radius.shapePill),
                )
            }
            if (selected && !homeFinish) {
                Box(
                    Modifier.align(Alignment.CenterStart).width(3.dp).height(30.dp)
                        .clip(Radius.shapePill)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Row(
                Modifier.fillMaxSize().padding(start = if (homeFinish) 14.dp else 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (homeFinish && selected) selectedGlass.iconContent
                    else if (homeFinish) finish.textMuted
                    else if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (homeFinish) 19.dp else 21.dp),
                )
                Text(
                    label,
                    color = if (homeFinish && selected) selectedGlass.primaryContent
                    else if (homeFinish) finish.textMuted
                    else if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (homeFinish) ExecutiveHomeType.Rail else MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("$testTag-label"),
                )
            }
        }
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
    val density = LocalDensity.current
    val scaledLabelLineHeight = with(density) {
        MaterialTheme.typography.labelLarge.lineHeight.toDp()
    }
    // The 8dp reserve keeps scaled glyph metrics and pixel rounding from being
    // constrained after the icon, label line height, gap, and item padding.
    val dockHeight = maxOf(
        68.dp,
        22.dp + 2.dp + scaledLabelLineHeight + 16.dp + 8.dp,
    )
    val primary = listOf(
        Destination.Gaming,
        Destination.Pos,
        Destination.Shift,
        Destination.Customers,
    ).filter(destinations::contains)
    val moreSelected = current !in primary
    val scene = LocalWorkspaceGlassScene.current
    val day = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val glassStyle = workspaceGlassRoleStyle(WorkspaceGlassRole.RAIL, day)
    val selectedContent = if (scene != null) glassStyle.primaryContent
        else MaterialTheme.colorScheme.onPrimaryContainer
    val normalContent = if (scene != null) glassStyle.secondaryContent
        else MaterialTheme.colorScheme.onSurfaceVariant
    val selectionAccent = workspacePresentationAccent()
    Row(
        Modifier.fillMaxWidth().height(dockHeight)
            .then(
                if (scene != null) Modifier.workspaceGlass(
                    scene,
                    WorkspaceGlassRole.RAIL,
                    day,
                    androidx.compose.ui.graphics.RectangleShape,
                ) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            )
            .testTag("workspace-bottom-dock"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        primary.forEach { destination ->
            val selected = destination == current
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = selected, role = Role.Tab) { onSelect(destination) }
                    .testTag("workspace-dock-${destination.name}")
                    .semantics {
                        contentDescription = "${destination.label}. ${destination.description}"
                        this.selected = selected
                    }
                    .padding(horizontal = Spacing.sm, vertical = 8.dp)
                    .clip(Radius.shapePill)
                    .background(
                        if (selected) selectionAccent.copy(alpha = 0.16f)
                        else Color.Transparent,
                    )
                    .padding(horizontal = Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = if (selected) selectedContent else normalContent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    destination.label,
                    color = if (selected) selectedContent else normalContent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("workspace-dock-label-${destination.name}"),
                )
            }
        }
        Column(
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
                    if (moreSelected) selectionAccent.copy(alpha = 0.16f)
                    else Color.Transparent,
                )
                .padding(horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = if (moreSelected) selectedContent else normalContent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "More",
                color = if (moreSelected) selectedContent else normalContent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (moreSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("workspace-dock-label-More"),
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
    homeFinish: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val finish = executiveHomePalette()
    val scene = LocalWorkspaceGlassScene.current
    val day = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val compactGlass = compact && scene != null
    BoxWithConstraints(
            Modifier.fillMaxWidth().height(ExecutiveUtilityHeight)
            .then(
                when {
                    homeFinish && !compact -> Modifier
                    homeFinish || compactGlass -> Modifier.workspaceGlass(
                        scene,
                        WorkspaceGlassRole.UTILITY,
                        MaterialTheme.colorScheme.background.luminance() > 0.5f,
                        androidx.compose.ui.graphics.RectangleShape,
                    )
                    else -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                },
            )
            .then(
                if ((homeFinish && !compact) || compactGlass) {
                    Modifier
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = if (homeFinish) finish.innerHighlight else MaterialTheme.colorScheme.outlineVariant,
                    )
                },
            )
            .testTag("workspace-header"),
    ) {
        val wideUtility = !compact && maxWidth >= 920.dp
        val outboxVisible = !outboxWorkStatus.isClear || syncing
        val wideLayout = workspaceHeaderLayoutPolicy(maxWidth, outboxVisible)
        val supportDescription = if (pendingSupportCount > 0) {
            "Help and support. $pendingSupportCount saved request${if (pendingSupportCount == 1) "" else "s"} waiting"
        } else {
            "Help and support"
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (wideUtility) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wideUtility) 8.dp else 4.dp),
        ) {
            if (wideUtility && leadingContent != null) {
                Box(
                    Modifier.width(56.dp).fillMaxHeight()
                        .testTag("workspace-header-leading-slot"),
                    contentAlignment = Alignment.Center,
                ) {
                    leadingContent()
                }
            }
            if (wideUtility) {
                Row(
                    Modifier.width(wideLayout.searchWidth).height(48.dp)
                        .clip(Radius.shapeMd).then(
                            if (homeFinish) Modifier.workspaceGlass(
                                LocalWorkspaceGlassScene.current,
                                WorkspaceGlassRole.CONTROL,
                                MaterialTheme.colorScheme.background.luminance() > 0.5f,
                                Radius.shapeMd,
                            ) else Modifier.background(MaterialTheme.colorScheme.surface),
                        )
                        .then(
                            if (homeFinish) Modifier else Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                Radius.shapeMd,
                            ),
                        )
                        .testTag("workspace-header-search")
                        .clickable(onClick = onOpenCommand)
                        .semantics { role = Role.Button; contentDescription = "Find a module" }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = if (homeFinish) finish.textMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        if (homeFinish) "Search…" else "Find a module…",
                        color = if (homeFinish) finish.textMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (homeFinish) ExecutiveHomeType.Utility else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Row(
                    Modifier.weight(1f).testTag("workspace-header-identity"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        destination.label,
                        color = if (compactGlass) {
                            workspaceGlassRoleStyle(WorkspaceGlassRole.UTILITY, day).primaryContent
                        } else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("workspace-header-destination-label"),
                    )
                }
                HeaderIconAction(
                    icon = Icons.Filled.Search,
                    accessibilityLabel = "Find a module",
                    onClick = onOpenCommand,
                )
            }

            ConnectivityStatus(
                problem = connectivityProblem,
                expanded = wideUtility,
                narrow = !wideUtility,
                onOpenSupport = onOpenSupport,
                homeFinish = homeFinish,
                compactGlass = compactGlass,
            )
            if (outboxVisible) {
                OutboxWorkStatusPill(
                    status = outboxWorkStatus,
                    syncing = syncing,
                    showDetail = wideUtility && wideLayout.showOutboxDetail,
                    homeFinish = homeFinish,
                    compactGlass = compactGlass,
                )
            }
            if (homeFinish) {
                HomeAppearanceControl(
                    preference = appearancePreference,
                    onChange = onAppearancePreferenceChanged,
                    compactGlass = compactGlass,
                )
            } else {
                CompositionLocalProvider(
                    LocalContentColor provides if (compactGlass) {
                        workspaceGlassRoleStyle(WorkspaceGlassRole.CONTROL, day).primaryContent
                    } else LocalContentColor.current,
                ) {
                    AppearanceControl(
                        preference = appearancePreference,
                        onChange = onAppearancePreferenceChanged,
                        modifier = Modifier
                            .then(if (wideUtility) Modifier.widthIn(min = 112.dp, max = 132.dp) else Modifier)
                            .testTag("workspace-day-night-toggle"),
                        compactLabel = !wideUtility,
                    )
                }
            }
            HeaderIconAction(
                icon = if (homeFinish) Icons.Filled.Notifications else Icons.AutoMirrored.Filled.HelpOutline,
                accessibilityLabel = supportDescription,
                onClick = onOpenSupport,
                badgeCount = pendingSupportCount,
                homeFinish = homeFinish,
                compactGlass = compactGlass,
            )
            WorkspaceAccountControl(
                employeeName = employeeName,
                locationLabel = locationLabel,
                canChangeTill = canChangeTill,
                appearancePreference = appearancePreference,
                onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                onChangeTill = onChangeTill,
                onSignOut = onSignOut,
                homeFinish = homeFinish,
            )
            if (wideUtility) {
                Spacer(Modifier.weight(1f))
                WorkspaceDateTime(homeFinish = homeFinish)
            }
        }
    }
}

@Composable
private fun WorkspaceRailRevealButton(onClick: () -> Unit) {
    val finish = executiveHomePalette()
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
            .clip(Radius.shapeMd)
            .workspaceGlass(
                LocalWorkspaceGlassScene.current,
                WorkspaceGlassRole.CONTROL,
                MaterialTheme.colorScheme.background.luminance() > 0.5f,
                Radius.shapeMd,
            )
            .testTag("workspace-rail-show"),
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = "Show navigation sidebar",
            tint = finish.gold,
        )
    }
}

@Composable
private fun WorkspaceAccountControl(
    employeeName: String,
    locationLabel: String,
    canChangeTill: Boolean,
    appearancePreference: AppearancePreference,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit,
    onChangeTill: () -> Unit,
    onSignOut: () -> Unit,
    homeFinish: Boolean = false,
) {
    val finish = executiveHomePalette()
    var accountMenuOpen by remember { mutableStateOf(false) }
    Box {
        if (homeFinish) {
            Box(
                modifier = Modifier.size(48.dp).clickable { accountMenuOpen = true }
                    .semantics { role = Role.Button; contentDescription = "Account actions" },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = Color(0xFF1B2944),
                    border = BorderStroke(1.dp, Color(0xFF405270)),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        employeeName.trim().firstOrNull()?.uppercase() ?: "?",
                        color = Color(0xFFE8EDF5),
                        style = ExecutiveHomeType.Utility,
                        fontWeight = FontWeight.Bold,
                    )
                    }
                }
            }
        } else {
            HeaderIconAction(
                icon = Icons.Filled.Person,
                accessibilityLabel = "Account actions",
                onClick = { accountMenuOpen = true },
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        LiquidDropdownMenu(
            expanded = accountMenuOpen,
            onDismissRequest = { accountMenuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(employeeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            locationLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                onClick = { accountMenuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Appearance · ${appearancePreference.name.lowercase().replaceFirstChar(Char::uppercase)}") },
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

@Composable
private fun WorkspaceDateTime(homeFinish: Boolean = false) {
    val finish = executiveHomePalette()
    val presentation = workspaceDateTimePresentation(rememberWorkspaceClock())
    val fontScale = LocalDensity.current.fontScale
    val day = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val modifier = Modifier.width(if (homeFinish) homeDateTimeWidth(fontScale) else 132.dp)
        .height(48.dp)
        .testTag("workspace-date-time")
        .semantics {
            contentDescription =
                "Local date and time: ${presentation.date}, ${presentation.time}, ${presentation.zoneId}"
        }
    if (homeFinish) {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).height(36.dp).clip(Radius.shapeSm)
                    .workspaceGlass(
                        LocalWorkspaceGlassScene.current,
                        WorkspaceGlassRole.CONTROL,
                        day,
                        Radius.shapeSm,
                    )
                    .testTag("workspace-date-surface")
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    presentation.date,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-date-label"),
                    color = if (day) finish.text else finish.textMuted,
                    style = ExecutiveHomeType.ShortcutMeta,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
            Box(
                Modifier.width(homeTimeTextWidth(fontScale) + 14.dp).height(36.dp)
                    .clip(Radius.shapeSm)
                    .workspaceGlass(
                        LocalWorkspaceGlassScene.current,
                        WorkspaceGlassRole.CONTROL,
                        day,
                        Radius.shapeSm,
                    )
                    .testTag("workspace-time-surface")
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    presentation.time,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-time-label"),
                    color = finish.text,
                    style = ExecutiveHomeType.ShortcutMeta.copy(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    } else {
        Column(
            modifier,
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                presentation.date,
                modifier = Modifier.fillMaxWidth().testTag("workspace-date-label"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            Text(
                presentation.time,
                modifier = Modifier.fillMaxWidth().testTag("workspace-time-label"),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

internal data class WorkspaceDateTimePresentation(
    val date: String,
    val time: String,
    val zoneId: String,
)

internal fun workspaceDateTimePresentation(now: ZonedDateTime): WorkspaceDateTimePresentation =
    WorkspaceDateTimePresentation(
        date = now.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)),
        time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
        zoneId = now.zone.id,
    )

/**
 * Updates on the platform's minute tick and immediately after clock, timezone,
 * or foreground lifecycle changes. The receiver is registered only while the
 * workspace is started, so an idle dashboard does no polling work.
 */
@Composable
private fun rememberWorkspaceClock(): ZonedDateTime {
    val applicationContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(ZonedDateTime.now(ZoneId.systemDefault())) }

    DisposableEffect(applicationContext, lifecycleOwner) {
        var registered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                now = ZonedDateTime.now(ZoneId.systemDefault())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        fun register() {
            if (registered) return
            ContextCompat.registerReceiver(
                applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
            now = ZonedDateTime.now(ZoneId.systemDefault())
        }
        fun unregister() {
            if (!registered) return
            applicationContext.unregisterReceiver(receiver)
            registered = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return now
}

@Composable
private fun HomeAppearanceControl(
    preference: AppearancePreference,
    onChange: (AppearancePreference) -> Unit,
    compactGlass: Boolean = false,
) {
    val finish = executiveHomePalette()
    val contentColor = if (compactGlass) workspaceGlassRoleStyle(
        WorkspaceGlassRole.CONTROL,
        MaterialTheme.colorScheme.background.luminance() > 0.5f,
    ).primaryContent else finish.textMuted
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier.width(homeAppearanceControlWidth(LocalDensity.current.fontScale)).height(48.dp)
                .clickable { expanded = true }
                .testTag("workspace-day-night-toggle")
                .semantics {
                    role = Role.Button
                    contentDescription = "Day, night and system appearance"
                    stateDescription = preference.name.lowercase().replaceFirstChar(Char::uppercase)
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(40.dp).clip(Radius.shapeMd).workspaceGlass(
                    LocalWorkspaceGlassScene.current,
                    WorkspaceGlassRole.CONTROL,
                    MaterialTheme.colorScheme.background.luminance() > 0.5f,
                    Radius.shapeMd,
                ),
                color = Color.Transparent,
                shape = Radius.shapeMd,
                border = null,
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                Icon(
                    when (preference) {
                        AppearancePreference.System -> Icons.Filled.BrightnessAuto
                        AppearancePreference.Day -> Icons.Filled.LightMode
                        AppearancePreference.Night -> Icons.Filled.DarkMode
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
                    Text(
                        "${preference.name.lowercase().replaceFirstChar(Char::uppercase)} mode",
                        modifier = Modifier.weight(1f).testTag("workspace-appearance-label"),
                        color = contentColor,
                        style = ExecutiveHomeType.Utility,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LiquidDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            role = WorkspaceGlassRole.CONTROL,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            AppearancePreference.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                AppearancePreference.System -> "Use system setting"
                                AppearancePreference.Day -> "Day"
                                AppearancePreference.Night -> "Night"
                            },
                        )
                    },
                    modifier = Modifier.testTag("appearance-option-${option.name}"),
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
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
    homeFinish: Boolean = false,
    compactGlass: Boolean = false,
) {
    val finish = executiveHomePalette()
    Box(
        modifier = Modifier.size(48.dp)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = accessibilityLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = if (homeFinish) Color.Transparent else MaterialTheme.colorScheme.surface,
            shape = Radius.shapeMd,
            border = if (homeFinish) null else BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.size(if (homeFinish) 40.dp else 48.dp).then(
                if (homeFinish) Modifier.clip(Radius.shapeMd) else Modifier,
            ).then(
                if (homeFinish) Modifier.workspaceGlass(
                    LocalWorkspaceGlassScene.current,
                    WorkspaceGlassRole.CONTROL,
                    MaterialTheme.colorScheme.background.luminance() > 0.5f,
                    Radius.shapeMd,
                ) else Modifier,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (homeFinish && compactGlass) workspaceGlassRoleStyle(
                    WorkspaceGlassRole.CONTROL,
                    MaterialTheme.colorScheme.background.luminance() > 0.5f,
                ).iconContent else if (homeFinish) finish.textMuted else tint,
                modifier = Modifier.size(if (homeFinish) 18.dp else 21.dp),
            )
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
}

@Composable
private fun ConnectivityStatus(
    problem: SyncAvailabilityProblem,
    expanded: Boolean,
    narrow: Boolean,
    onOpenSupport: () -> Unit,
    homeFinish: Boolean = false,
    compactGlass: Boolean = false,
) {
    val finish = executiveHomePalette()
    var detailsOpen by remember { mutableStateOf(false) }
    val (label, color, icon) = when (problem) {
        SyncAvailabilityProblem.NONE -> Triple("Online", MaterialTheme.colorScheme.operationalGood, Icons.Filled.CloudDone)
        SyncAvailabilityProblem.VERIFYING -> Triple("Checking", MaterialTheme.colorScheme.operationalInformation, Icons.Filled.Sync)
        SyncAvailabilityProblem.NO_NETWORK -> Triple("No internet", MaterialTheme.colorScheme.operationalWarning, Icons.Filled.CloudOff)
        SyncAvailabilityProblem.SERVER_UNREACHABLE -> Triple("Server issue", MaterialTheme.colorScheme.operationalDanger, Icons.Filled.Error)
        SyncAvailabilityProblem.RECOVERING -> Triple("Restoring", MaterialTheme.colorScheme.operationalInformation, Icons.Filled.Sync)
    }
    val background = if (homeFinish) {
        finish.canvas.copy(alpha = 0.76f)
    } else if (problem == SyncAvailabilityProblem.NONE) {
        MaterialTheme.colorScheme.surface
    } else {
        color.copy(alpha = 0.12f)
    }
    val border = if (homeFinish && problem == SyncAvailabilityProblem.NONE) {
        finish.outline
    } else if (problem == SyncAvailabilityProblem.NONE) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        color.copy(alpha = 0.42f)
    }
    val visibleLabel = when {
        problem == SyncAvailabilityProblem.NO_NETWORK -> "Offline"
        problem == SyncAvailabilityProblem.SERVER_UNREACHABLE -> "Server"
        else -> label
    }
    val statusShape = if (homeFinish) Radius.shapeMd else Radius.shapePill
    val compactRole = when (problem) {
        SyncAvailabilityProblem.NO_NETWORK -> WorkspaceGlassRole.WARNING
        SyncAvailabilityProblem.SERVER_UNREACHABLE -> WorkspaceGlassRole.ERROR
        else -> WorkspaceGlassRole.CONTROL
    }
    val compactStyle = workspaceGlassRoleStyle(
        compactRole,
        MaterialTheme.colorScheme.background.luminance() > 0.5f,
    )

    Surface(
        color = if (homeFinish || compactGlass) Color.Transparent else background,
        shape = statusShape,
        border = if (compactGlass) null else BorderStroke(1.dp, border),
        modifier = Modifier
            // Width is fixed for each breakpoint. A status transition changes
            // neither the header geometry nor the active workflow below it.
            .width(if (expanded) 104.dp else if (narrow) 104.dp else 124.dp)
            .height(48.dp)
            .then(if (homeFinish || compactGlass) Modifier.clip(statusShape) else Modifier)
            .then(
                if (homeFinish || compactGlass) Modifier.workspaceGlass(
                    LocalWorkspaceGlassScene.current,
                    if (compactGlass) compactRole
                    else if (problem == SyncAvailabilityProblem.NONE) WorkspaceGlassRole.CONTROL
                    else WorkspaceGlassRole.WARNING,
                    MaterialTheme.colorScheme.background.luminance() > 0.5f,
                    statusShape,
                ) else Modifier,
            )
            .clickable { detailsOpen = true }
            .semantics {
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
                stateDescription = label
                contentDescription = "Connection status: $label. Open connection details"
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (narrow) Spacing.xs else Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (narrow) Spacing.xs else Spacing.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (compactGlass) compactStyle.iconContent else color,
                modifier = Modifier.size(19.dp),
            )
            Text(
                visibleLabel,
                color = if (compactGlass) compactStyle.primaryContent
                else if (problem == SyncAvailabilityProblem.NONE && homeFinish) finish.textMuted
                else if (problem == SyncAvailabilityProblem.NONE) MaterialTheme.colorScheme.onSurfaceVariant else color,
                style = if (homeFinish) ExecutiveHomeType.Utility else MaterialTheme.typography.labelMedium,
                fontWeight = if (problem == SyncAvailabilityProblem.NONE) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("workspace-connectivity-label"),
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
    homeFinish: Boolean = false,
    compactGlass: Boolean = false,
) {
    val finish = executiveHomePalette()
    val visibleLabel = outboxWorkVisibleLabel(status, syncing, showDetail)
    Box(
        modifier = Modifier
            // While work is visible, label changes retain a fixed wide or
            // compact slot. The caller removes the slot only after work clears.
            .width(if (showDetail) 192.dp else 88.dp)
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
            val compactRole = when {
                status.protectedReviewUnknown || status.protectedReviewCount > 0 || status.actionRequiredCount > 0 -> WorkspaceGlassRole.ERROR
                status.retryableCount > 0 && !syncing -> WorkspaceGlassRole.WARNING
                else -> WorkspaceGlassRole.CONTROL
            }
            val compactStyle = workspaceGlassRoleStyle(
                compactRole,
                MaterialTheme.colorScheme.background.luminance() > 0.5f,
            )
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
                    .then(
                        if (homeFinish || compactGlass) Modifier.workspaceGlass(
                            LocalWorkspaceGlassScene.current,
                            if (compactGlass) compactRole
                            else if (status.protectedReviewUnknown || status.protectedReviewCount > 0 || status.actionRequiredCount > 0) {
                                WorkspaceGlassRole.ERROR
                            } else WorkspaceGlassRole.CONTROL,
                            MaterialTheme.colorScheme.background.luminance() > 0.5f,
                            Radius.shapePill,
                        ) else Modifier.background(color.copy(alpha = 0.12f)),
                    )
                    .then(
                        if (compactGlass) Modifier else Modifier.border(
                            1.dp,
                            if (homeFinish) color.copy(alpha = 0.48f) else color.copy(alpha = 0.34f),
                            Radius.shapePill,
                        ),
                    )
                    .semantics { contentDescription = "Saved work status: $accessibilityDetail" }
                    .padding(horizontal = if (showDetail) Spacing.md else Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (compactGlass) compactStyle.iconContent else color))
                Text(
                    visibleLabel,
                    color = if (compactGlass) compactStyle.primaryContent else color,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("workspace-outbox-label"),
                )
            }
        }
    }
}

@Composable
private fun DestinationCommandDialog(
    destinations: List<Destination>,
    searchEnabled: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onRootObserved: ((View) -> Unit)?,
    onDismiss: () -> Unit,
    onSelect: (Destination) -> Unit,
) {
    val glass = LocalWorkspaceGlassScene.current != null
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
            val dialogRoot = LocalView.current.rootView
            SideEffect { onRootObserved?.invoke(dialogRoot) }
            Text(
                if (searchEnabled) "Find a module" else "More modules",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("workspace-command-title"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (searchEnabled) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
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
                                    .clip(Radius.shapeMd)
                                    .then(
                                        if (glass) Modifier.liquidWorkspaceSurface(
                                            Radius.shapeMd,
                                            WorkspaceGlassRole.UTILITY,
                                        ) else Modifier,
                                    )
                                    .clickable { onSelect(destination) }
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

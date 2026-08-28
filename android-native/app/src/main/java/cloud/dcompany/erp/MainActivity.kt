package cloud.dcompany.erp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.AuthState
import cloud.dcompany.erp.ui.SessionViewModel
import cloud.dcompany.erp.ui.TerminalChangeUiState
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.allowedDestinations
import cloud.dcompany.erp.ui.canManageMemberships
import cloud.dcompany.erp.ui.canManageSystemSettings
import cloud.dcompany.erp.ui.workspaceLocationLabel
import cloud.dcompany.erp.ui.screens.accesscontrol.AccessControlScreen
import cloud.dcompany.erp.ui.screens.audit.AuditLogScreen
import cloud.dcompany.erp.ui.screens.analytics.AnalyticsScreen
import cloud.dcompany.erp.ui.screens.customers.CustomersScreen
import cloud.dcompany.erp.ui.screens.events.EventsScreen
import cloud.dcompany.erp.ui.screens.memberships.MembershipsScreen
import cloud.dcompany.erp.ui.screens.finance.FinanceScreen
import cloud.dcompany.erp.ui.screens.inventory.InventoryScreen
import cloud.dcompany.erp.ui.screens.kitchen.KitchenScreen
import cloud.dcompany.erp.ui.screens.menu.MenuScreen
import cloud.dcompany.erp.ui.screens.refunds.RefundsScreen
import cloud.dcompany.erp.ui.screens.reports.ReportsScreen
import cloud.dcompany.erp.ui.screens.tables.TablesScreen
import cloud.dcompany.erp.ui.screens.settings.SettingsScreen
import cloud.dcompany.erp.ui.screens.staff.StaffScreen
import cloud.dcompany.erp.ui.screens.gaming.GamingScreen
import cloud.dcompany.erp.ui.screens.shift.ShiftScreen
import cloud.dcompany.erp.ui.screens.LoginScreen
import cloud.dcompany.erp.ui.screens.PosScreen
import cloud.dcompany.erp.ui.screens.PosViewModel
import cloud.dcompany.erp.ui.screens.TerminalSelectionScreen
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.core.net.ClientCompatibilityState
import cloud.dcompany.erp.core.net.ClientUpdateNotice
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.safeHttpsUpdateUrl
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.alarm.OperationalNotificationDestination
import cloud.dcompany.erp.core.alarm.OperationalNotificationTarget
import cloud.dcompany.erp.core.alarm.OperationalRouteDecision
import cloud.dcompany.erp.core.alarm.operationalRouteDecision
import cloud.dcompany.erp.core.alarm.operationalTargetExistsInCurrentScope
import cloud.dcompany.erp.core.sync.summarizeOutboxWork
import cloud.dcompany.erp.ui.components.syncAvailabilityProblem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DCompanyApp.instance.notificationRoutes.accept(intent)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                    AppRoot(onOpenUpdate = ::openSecureUpdate)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DCompanyApp.instance.notificationRoutes.accept(intent)
    }

    private fun openSecureUpdate(rawUrl: String) {
        val safeUrl = safeHttpsUpdateUrl(rawUrl)
        if (safeUrl == null) {
            Toast.makeText(this, "No safe HTTPS update link is available. Ask an owner for the current app.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Could not open the update link. Ask an owner for the current app.", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onOpenUpdate: (String) -> Unit,
    session: SessionViewModel = viewModel(),
) {
    val compatibility = DCompanyApp.instance.clientCompatibility
    val compatibilityState by compatibility.state.collectAsStateWithLifecycle()
    val networkValidated by DCompanyApp.instance.connectivity.networkValidated.collectAsStateWithLifecycle()
    val backendReachability by ApiClient.backendReachability.state.collectAsStateWithLifecycle()
    val syncAvailability = syncAvailabilityProblem(networkValidated, backendReachability)
    val unresolvedOutboxGroups by DCompanyApp.instance.db.outboxSafetyDao()
        .observeUnresolvedGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val outboxWorkStatus = remember(unresolvedOutboxGroups) {
        summarizeOutboxWork(unresolvedOutboxGroups)
    }
    val syncing by DCompanyApp.instance.sync.syncing.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val signingIn by session.signingIn.collectAsStateWithLifecycle()
    val loginError by session.loginError.collectAsStateWithLifecycle()
    val accountSafetyNotice by session.accountSafetyNotice.collectAsStateWithLifecycle()
    val accessChangeNotice by session.accessChangeNotice.collectAsStateWithLifecycle()
    val terminalChange by session.terminalChange.collectAsStateWithLifecycle()
    val activeTerminal by DCompanyApp.instance.terminalStore.activeValidatedTerminal.collectAsStateWithLifecycle()
    val pendingNotificationTarget by DCompanyApp.instance.notificationRoutes.pending.collectAsStateWithLifecycle()
    val rejectedNotificationOpenNotice by
        DCompanyApp.instance.notificationRoutes.rejectedOpenNotice.collectAsStateWithLifecycle()
    var notificationRouteNotice by rememberSaveable { mutableStateOf<String?>(null) }

    when (val update = compatibilityState) {
        is ClientCompatibilityState.UpdateRequired -> {
            RequiredUpdateScreen(update.notice, onOpenUpdate)
            return
        }

        // Render the cached workspace beneath a non-dismissible safety gate.
        // This avoids a blank-looking launch while preserving the preflight:
        // no offline write can be captured until the server either supports
        // this build or the bounded compatibility check fails open.
        ClientCompatibilityState.Checking,
        is ClientCompatibilityState.UpdateAvailable,
        ClientCompatibilityState.Supported -> Unit
    }

    when (val s = state) {
        is AuthState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Brand.Gold)
        }

        is AuthState.SigningOut -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Brand.Gold)
                Text("Signing out safely…", color = Brand.ForegroundMuted)
            }
        }

        is AuthState.SignOutFailed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Couldn't finish sign-out", style = MaterialTheme.typography.titleLarge)
                Text(s.message, color = Brand.ForegroundMuted)
                Button(onClick = session::signOut) { Text("Retry sign-out") }
                Text(
                    "If retry still fails, keep this tablet with a manager and contact support.",
                    color = Brand.ForegroundMuted,
                )
            }
        }

        is AuthState.SignedOut -> PreLoginViewModelScope {
            Box(Modifier.fillMaxSize()) {
                LoginScreen(
                    signingIn = signingIn,
                    error = loginError,
                    onSignIn = session::signIn,
                )
                if (pendingNotificationTarget != null) {
                    PendingNotificationSignInBanner(pendingNotificationTarget!!)
                }
            }
        }

        is AuthState.SelectTerminal -> TerminalSelectionScreen(
            employeeName = s.me.name,
            terminals = s.terminals,
            choosing = s.choosing,
            error = s.error,
            isReassignment = s.reassigning,
            previousTerminalName = s.previousTerminalName,
            onConfirm = session::selectTerminal,
            onRefresh = session::refreshTerminalChoices,
            onExit = if (s.reassigning) session::cancelTerminalReassignment else session::signOut,
        )

        // Credentials are intact — this is a connectivity problem, so the fix
        // is a retry, never a login screen that makes staff re-enter a
        // password they never lost.
        is AuthState.Unreachable -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Can't reach the server", style = MaterialTheme.typography.titleLarge)
                Text(s.message, color = Brand.ForegroundMuted)
                Button(onClick = session::restore) { Text("Retry") }
                TextButton(onClick = session::signOut) { Text("Sign in as someone else") }
            }
        }

        is AuthState.Blocked -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Cannot open this workspace", style = MaterialTheme.typography.titleLarge)
                Text(s.message, color = Brand.ForegroundMuted)
                Button(onClick = session::restore) { Text("Verify again") }
                TextButton(onClick = session::signOut) { Text("Sign in as someone else") }
            }
        }

        is AuthState.SignedIn -> {
            // Authority is part of the workspace lifetime. If an owner revokes
            // a module, dispose every feature ViewModel from the old profile
            // so its polling/queued actions cannot continue behind a removed tab.
            SessionViewModelScope(s.me) {
                val permissions = remember(s.me) { EffectivePermissions.from(s.me) }
                val destinations = remember(s.me) { allowedDestinations(s.me) }
                var confirmSignOut by remember(s.me.userId) { mutableStateOf(false) }
                var currentDestination by rememberSaveable(s.me.userId) {
                    mutableStateOf(destinations.firstOrNull() ?: Destination.Settings)
                }
                var operationalFocus by remember(s.me.userId) {
                    mutableStateOf<OperationalNotificationTarget?>(null)
                }
                LaunchedEffect(pendingNotificationTarget, destinations, s.me.userId) {
                    val target = pendingNotificationTarget ?: return@LaunchedEffect
                    val destination = when (target.destination) {
                        OperationalNotificationDestination.POS -> Destination.Pos
                        OperationalNotificationDestination.GAMING -> Destination.Gaming
                    }
                    when (
                        operationalRouteDecision(
                            signedIn = true,
                            destinationAllowed = destination in destinations,
                        )
                    ) {
                        OperationalRouteDecision.WAIT_FOR_SIGN_IN -> Unit
                        OperationalRouteDecision.ACCESS_DENIED -> {
                            notificationRouteNotice =
                                "This account cannot access ${destination.label}. Ask a manager or " +
                                    "sign in with an authorised account. No order or session was changed."
                            DCompanyApp.instance.notificationRoutes.consume(target)
                        }
                        OperationalRouteDecision.NAVIGATE -> {
                            val resource = if (destination == Destination.Pos) "orders" else "gaming"
                            DCompanyApp.instance.sync.refresh(resource)
                            val exists = operationalTargetExistsInCurrentScope(
                                context = DCompanyApp.instance,
                                target = target,
                            )
                            if (exists) {
                                operationalFocus = target
                                currentDestination = destination
                            } else {
                                notificationRouteNotice = if (
                                    DCompanyApp.instance.connectivity.online.value
                                ) {
                                    when (target) {
                                        is OperationalNotificationTarget.HeldOrder ->
                                            "This order is no longer waiting. It may have been billed or voided on another till."
                                        is OperationalNotificationTarget.GamingSession ->
                                            "This gaming session is no longer active. It may have been stopped on another tablet."
                                    }
                                } else {
                                    "This tablet cannot verify the alert while offline. Reconnect, then refresh ${destination.label}."
                                }
                            }
                            DCompanyApp.instance.notificationRoutes.consume(target)
                        }
                    }
                }
                val visibleDestination = currentDestination.takeIf { it in destinations }
                    ?: destinations.firstOrNull()
                    ?: Destination.Settings
                val requiresTill = permissions.has(ErpPermission.PosRead)
                val locationLabel = workspaceLocationLabel(
                    branchId = s.me.branchId,
                    branchName = s.me.branchName,
                    requiresTill = requiresTill,
                    activeTerminal = activeTerminal,
                )
                WorkspaceScaffold(
                    destinations = destinations,
                    currentDestination = visibleDestination,
                    employeeName = s.me.name,
                    locationLabel = locationLabel,
                    connectivityProblem = syncAvailability,
                    outboxWorkStatus = outboxWorkStatus,
                    syncing = syncing,
                    canChangeTill = s.me.protectedAccess && requiresTill,
                    onChangeTill = session::requestTerminalReassignment,
                    onSignOut = { confirmSignOut = true },
                    onDestinationChanged = {
                        currentDestination = it
                        val focusDestination = when (operationalFocus?.destination) {
                            OperationalNotificationDestination.POS -> Destination.Pos
                            OperationalNotificationDestination.GAMING -> Destination.Gaming
                            null -> null
                        }
                        if (focusDestination != it) operationalFocus = null
                    },
                ) { destination, navigateTo ->
                    when (destination) {
                            Destination.Pos -> {
                                // Constructing a feature ViewModel starts its
                                // initial API pulls. Keep it inside its allowed
                                // destination so hidden tabs cannot generate
                                // background 403s for low-privilege accounts.
                                val pos: PosViewModel = viewModel()
                                val posState by pos.state.collectAsStateWithLifecycle()
                                val recentPosReceipts by pos.recentReceipts.collectAsStateWithLifecycle()
                                val unacknowledgedPosReceipt by pos.unacknowledgedReceipt.collectAsStateWithLifecycle()
                                val heldFocus = operationalFocus
                                    as? OperationalNotificationTarget.HeldOrder
                                LaunchedEffect(heldFocus?.orderId) {
                                    heldFocus?.let { pos.focusHeldOrder(it.orderId) }
                                }
                                PosScreen(
                                    state = posState,
                                    recentReceipts = recentPosReceipts,
                                    unacknowledgedReceipt = unacknowledgedPosReceipt,
                                    access = permissions.posAccess(),
                                    onAccessChanged = pos::updateAccess,
                                    onAdd = pos::add,
                                    onAddConfigured = pos::addConfigured,
                                    onRemove = pos::remove,
                                    onIncrementLine = pos::incrementLine,
                                    onDecrementLine = pos::decrementLine,
                                    onSelectCategory = pos::selectCategory,
                                    onClearCart = pos::clearCart,
                                    onUpdateDraftDetails = pos::updateDraftDetails,
                                    onRefresh = pos::refresh,
                                    onPrepareDirectCheckout = pos::prepareDirectCheckout,
                                    onDismissDirectCheckout = pos::dismissDirectCheckout,
                                    onConfirmDirectZero = pos::confirmDirectZero,
                                    onRedeemDirectPoints = pos::redeemDirectPoints,
                                    onCapture = pos::captureSale,
                                    onRetryRejectedSale = pos::retryRejectedSale,
                                    onRetryHeldPayment = pos::retryRejectedHeldPayment,
                                    onPrepareHeldOrder = pos::prepareHeldOrderCheckout,
                                    onConfirmHeldOrder = pos::confirmHeldOrderPayment,
                                    onConfirmHeldOrderZero = pos::confirmHeldOrderZero,
                                    onVoidOrder = pos::voidOrder,
                                    onDismissHeldOrder = pos::dismissHeldOrderCheckout,
                                    onDismissNotice = pos::dismissNotice,
                                    onAcknowledgeReceipt = pos::acknowledgeReceipt,
                                    onFocusOldestOverdue = pos::focusOldestOverdueOrder,
                                    onSnoozeOverdue = pos::snoozeOverdueBanner,
                                    onUnmuteOverdue = pos::unmuteOverdueBanner,
                                    onDismissHeldFocus = {
                                        pos.dismissHeldOrderFocus()
                                        if (operationalFocus is OperationalNotificationTarget.HeldOrder) {
                                            operationalFocus = null
                                        }
                                    },
                                )
                            }
                            Destination.Gaming -> {
                                val gamingFocus = operationalFocus
                                    as? OperationalNotificationTarget.GamingSession
                                GamingScreen(
                                    access = permissions.gamingAccess().let { granted ->
                                        granted.copy(
                                            canReconcileLegacySessions =
                                                granted.canReconcileLegacySessions &&
                                                    s.me.protectedAccess && s.me.auditAccess,
                                        )
                                    },
                                    focusSessionId = gamingFocus?.sessionId,
                                    focusStationId = gamingFocus?.stationId,
                                    onDismissFocus = { operationalFocus = null },
                                )
                            }
                            Destination.Tables -> TablesScreen(access = permissions.tablesAccess())
                            Destination.Kitchen -> KitchenScreen(
                                access = permissions.kitchenAccess(),
                                onExit = {
                                    // A dedicated kitchen account has no POS
                                    // destination.  Sending it to POS used to
                                    // be silently ignored, which made "Exit
                                    // KDS" look broken.  Operational accounts
                                    // return to POS; kitchen-only accounts get
                                    // the normal, explicit sign-out warning.
                                    if (Destination.Pos in destinations) {
                                        navigateTo(Destination.Pos)
                                    } else {
                                        confirmSignOut = true
                                    }
                                },
                            )
                            Destination.Shift -> ShiftScreen(access = permissions.shiftAccess())
                            Destination.Customers -> CustomersScreen(access = permissions.customersAccess())
                            Destination.Menu -> MenuScreen(access = permissions.menuAccess())
                            Destination.Staff -> StaffScreen(
                                profile = s.me,
                                access = permissions.staffAccess(),
                            )
                            Destination.Inventory -> InventoryScreen(access = permissions.inventoryAccess())
                            Destination.Reports -> ReportsScreen()
                            Destination.Analytics -> AnalyticsScreen()
                            Destination.Finance -> FinanceScreen(access = permissions.financeAccess())
                            Destination.Events -> EventsScreen(access = permissions.eventsAccess())
                            Destination.Memberships -> MembershipsScreen(
                                canManage = canManageMemberships(s.me),
                            )
                            Destination.Refunds -> RefundsScreen()
                            Destination.AuditLog -> AuditLogScreen()
                            Destination.AccessControl -> AccessControlScreen()
                            Destination.Settings -> SettingsScreen(
                                canManageSystem = canManageSystemSettings(s.me),
                                onPasswordChanged = session::expireAfterPasswordChange,
                            )
                    }
                }

                if (confirmSignOut) {
                    AlertDialog(
                        onDismissRequest = { confirmSignOut = false },
                        title = { Text("Sign out of this tablet?") },
                        text = {
                            Text(
                                "The open shift stays open. Unsaved carts and form edits will be " +
                                    "discarded; saved offline work remains safely on this tablet.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmSignOut = false
                                    session.signOut()
                                },
                            ) { Text("Sign out") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmSignOut = false }) {
                                Text("Stay signed in")
                            }
                        },
                    )
                }

                when (val change = terminalChange) {
                    TerminalChangeUiState.Idle -> Unit
                    is TerminalChangeUiState.Confirm -> AlertDialog(
                        onDismissRequest = session::dismissTerminalChange,
                        title = { Text("Change this tablet's till?") },
                        text = {
                            Text(
                                "Current till: ${change.terminalName}. The app will first verify a live " +
                                    "connection, a clean Sync queue, no open/closing shift, and no active " +
                                    "or unbilled gaming work. If any check fails, this assignment stays unchanged.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = session::confirmTerminalReassignment) {
                                Text("Run safety checks")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = session::dismissTerminalChange) { Text("Cancel") }
                        },
                    )
                    TerminalChangeUiState.Checking -> AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Checking this tablet") },
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = Brand.Gold)
                                Text("Verifying the current till, Shift, Gaming and saved Sync work…")
                            }
                        },
                        confirmButton = {},
                    )
                    is TerminalChangeUiState.Blocked -> AlertDialog(
                        onDismissRequest = session::dismissTerminalChange,
                        title = { Text("Till not changed") },
                        text = { Text(change.message) },
                        confirmButton = {
                            TextButton(onClick = session::dismissTerminalChange) { Text("OK") }
                        },
                    )
                }
            }
        }
    }

    if (compatibilityState is ClientCompatibilityState.Checking) {
        CompatibilityCheckDialog()
    } else if (accountSafetyNotice != null) {
        AlertDialog(
            onDismissRequest = session::dismissAccountSafetyNotice,
            title = { Text("Account safety lock") },
            text = { Text(accountSafetyNotice.orEmpty()) },
            confirmButton = {
                TextButton(onClick = session::dismissAccountSafetyNotice) { Text("OK") }
            },
        )
    } else if (accessChangeNotice != null) {
        AlertDialog(
            onDismissRequest = session::dismissAccessChangeNotice,
            title = { Text("Access updated") },
            text = { Text(accessChangeNotice.orEmpty()) },
            confirmButton = {
                TextButton(onClick = session::dismissAccessChangeNotice) { Text("OK") }
            },
        )
    } else if (rejectedNotificationOpenNotice != null) {
        AlertDialog(
            onDismissRequest = DCompanyApp.instance.notificationRoutes::dismissRejectedOpenNotice,
            title = { Text("Alert not opened") },
            text = { Text(rejectedNotificationOpenNotice.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = DCompanyApp.instance.notificationRoutes::dismissRejectedOpenNotice,
                ) { Text("OK") }
            },
        )
    } else if (notificationRouteNotice != null) {
        AlertDialog(
            onDismissRequest = { notificationRouteNotice = null },
            title = { Text("Alert could not be opened") },
            text = { Text(notificationRouteNotice.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { notificationRouteNotice = null }) { Text("OK") }
            },
        )
    } else if (compatibilityState is ClientCompatibilityState.UpdateAvailable) {
        val notice = (compatibilityState as ClientCompatibilityState.UpdateAvailable).notice
        OptionalUpdateBanner(
            notice = notice,
            onDismiss = compatibility::dismissOptionalUpdate,
            onOpenUpdate = onOpenUpdate,
        )
    }
}

@Composable
private fun CompatibilityCheckDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Getting this tablet ready") },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = Brand.Gold)
                Text(
                    "Verifying this app version before orders can be changed. " +
                        "Saved work is safe; this usually takes a moment.",
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PendingNotificationSignInBanner(target: OperationalNotificationTarget) {
    Box(
        Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(color = Brand.SurfaceRaised, shape = cloud.dcompany.erp.ui.theme.Radius.shapeMd) {
            Text(
                text = when (target) {
                    is OperationalNotificationTarget.HeldOrder ->
                        "Sign in to open this held-order alert. Nothing will be billed automatically."
                    is OperationalNotificationTarget.GamingSession ->
                        "Sign in to open this gaming-session alert. Nothing will be stopped automatically."
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Feature ViewModels belong to one authenticated session, not to the whole
 * Activity. Clearing this store on logout/account switch prevents carts,
 * form drafts, polling jobs, and one employee's notices appearing for the
 * next employee who uses the same cafe tablet.
 */
private class AuthenticatedViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** Password-recovery secrets and challenges live only while Login is visible. */
@Composable
private fun PreLoginViewModelScope(content: @Composable () -> Unit) {
    val owner = remember { AuthenticatedViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

@Composable
private fun SessionViewModelScope(
    sessionKey: Any,
    content: @Composable () -> Unit,
) {
    val owner = remember(sessionKey) { AuthenticatedViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** A banner, not a dialog: staff can keep using the till without dismissing it. */
@Composable
private fun OptionalUpdateBanner(
    notice: ClientUpdateNotice,
    onDismiss: () -> Unit,
    onOpenUpdate: (String) -> Unit,
) {
    val safeUrl = safeHttpsUpdateUrl(notice.updateUrl)
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(color = Brand.SurfaceRaised, shape = cloud.dcompany.erp.ui.theme.Radius.shapeMd) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App update available", color = Brand.Foreground, style = MaterialTheme.typography.titleSmall)
                    Text(notice.message, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (safeUrl != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onOpenUpdate(safeUrl)
                    }) { Text("Update securely") }
                }
                TextButton(onClick = onDismiss) { Text(if (safeUrl == null) "OK" else "Later") }
            }
        }
    }
}

@Composable
private fun RequiredUpdateScreen(
    notice: ClientUpdateNotice,
    onOpenUpdate: (String) -> Unit,
) {
    val safeUrl = safeHttpsUpdateUrl(notice.updateUrl)
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("App update required", style = MaterialTheme.typography.headlineSmall, color = Brand.Foreground)
            Text(notice.message, color = Brand.ForegroundMuted)
            if (notice.minimumSupportedVersionCode != null) {
                Text(
                    "Installed build ${notice.currentVersionCode ?: BuildConfig.VERSION_CODE} · " +
                        "minimum supported ${notice.minimumSupportedVersionCode}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "Saved offline work and your signed-in account remain on this device.",
                color = Brand.Foreground,
            )
            if (safeUrl != null) {
                Button(onClick = { onOpenUpdate(safeUrl) }) { Text("Update securely") }
            } else {
                Text(
                    "No verified HTTPS download link was supplied. Ask an owner to install the current D Company ERP app.",
                    color = Brand.Danger,
                )
            }
        }
    }
}

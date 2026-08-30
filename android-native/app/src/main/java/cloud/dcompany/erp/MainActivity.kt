package cloud.dcompany.erp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.core.view.WindowCompat
import androidx.core.content.FileProvider
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
import cloud.dcompany.erp.ui.canManageSystemSettings
import cloud.dcompany.erp.ui.resolveWorkspaceDestination
import cloud.dcompany.erp.ui.workspaceLocationLabel
import cloud.dcompany.erp.ui.usesAdvancedTerminalWorkflow
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
import cloud.dcompany.erp.ui.screens.reservations.ReservationsScreen
import cloud.dcompany.erp.ui.screens.tables.TablesScreen
import cloud.dcompany.erp.ui.screens.settings.SettingsScreen
import cloud.dcompany.erp.ui.screens.settings.HelpScreen
import cloud.dcompany.erp.ui.screens.settings.SupportInboxScreen
import cloud.dcompany.erp.ui.screens.settings.BugReportDialog
import cloud.dcompany.erp.ui.screens.settings.BugReportLaunchContext
import cloud.dcompany.erp.ui.screens.settings.BugReportOwnerScope
import cloud.dcompany.erp.ui.screens.settings.BugReportViewModel
import cloud.dcompany.erp.ui.screens.settings.bugReportConnectivity
import cloud.dcompany.erp.ui.screens.settings.currentAndroidBugReportContext
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
import cloud.dcompany.erp.core.net.safeHttpsUpdateUrl
import cloud.dcompany.erp.core.update.AppUpdateUiState
import cloud.dcompany.erp.core.update.AppUpdateViewModel
import cloud.dcompany.erp.core.update.DirectUpdateMetadataResult
import cloud.dcompany.erp.core.update.InstallerLaunchResult
import cloud.dcompany.erp.core.update.matchesDescriptor
import cloud.dcompany.erp.core.update.validateDirectUpdateMetadata
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.alarm.OperationalNotificationDestination
import cloud.dcompany.erp.core.alarm.OperationalNotificationTarget
import cloud.dcompany.erp.core.alarm.OperationalRouteDecision
import cloud.dcompany.erp.core.alarm.operationalRouteDecision
import cloud.dcompany.erp.core.alarm.operationalTargetExistsInCurrentScope
import cloud.dcompany.erp.core.sync.summarizeOutboxWork
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.components.syncAvailabilityProblem
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DCompanyApp.instance.notificationRoutes.accept(intent)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                    AppRoot(
                        onOpenUpdateLink = ::openSecureUpdate,
                        onInstallVerifiedUpdate = ::requestVerifiedUpdateInstall,
                    )
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

    /**
     * Direct-distribution builds may hand a fully verified private APK to the
     * system installer. Android still owns the final confirmation. The Play
     * build has neither this capability nor the corresponding manifest entry.
     */
    private fun requestVerifiedUpdateInstall(file: File): InstallerLaunchResult {
        if (!BuildConfig.DIRECT_UPDATES_ENABLED) {
            Toast.makeText(this, "This app build uses the normal update link.", Toast.LENGTH_LONG).show()
            return InstallerLaunchResult.INSTALLER_UNAVAILABLE
        }
        val updateDirectory = File(cacheDir, "verified-updates").canonicalFile
        val candidate = runCatching { file.canonicalFile }.getOrNull()
        if (candidate == null || candidate.parentFile != updateDirectory || !candidate.isFile) {
            Toast.makeText(this, "The verified update file is no longer available. Download it again.", Toast.LENGTH_LONG).show()
            return InstallerLaunchResult.VERIFIED_FILE_UNAVAILABLE
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            return runCatching { startActivity(settingsIntent) }.fold(
                onSuccess = {
                Toast.makeText(
                    this,
                    "Allow installs for D Company ERP, return here, then tap Install update again.",
                    Toast.LENGTH_LONG,
                ).show()
                    InstallerLaunchResult.PERMISSION_REQUIRED
                },
                onFailure = {
                    Toast.makeText(
                        this,
                        "Android could not open the install permission. Ask the device owner for help.",
                        Toast.LENGTH_LONG,
                    ).show()
                    InstallerLaunchResult.INSTALLER_UNAVAILABLE
                },
            )
        }

        val contentUri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.updates", candidate)
        }.getOrElse {
            Toast.makeText(this, "The verified update could not be handed to Android.", Toast.LENGTH_LONG).show()
            return InstallerLaunchResult.INSTALLER_UNAVAILABLE
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        return runCatching { startActivity(installIntent) }.fold(
            onSuccess = { InstallerLaunchResult.OPENED },
            onFailure = {
                Toast.makeText(
                    this,
                    "Android Package Installer could not open. The app was not changed.",
                    Toast.LENGTH_LONG,
                ).show()
                InstallerLaunchResult.INSTALLER_UNAVAILABLE
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onOpenUpdateLink: (String) -> Unit,
    onInstallVerifiedUpdate: (File) -> InstallerLaunchResult,
    session: SessionViewModel = viewModel(),
    appUpdate: AppUpdateViewModel = viewModel(),
) {
    val compatibility = DCompanyApp.instance.clientCompatibility
    val compatibilityState by compatibility.state.collectAsStateWithLifecycle()
    val appUpdateState by appUpdate.state.collectAsStateWithLifecycle()
    // The connectivity observer owns the user-visible state. Raw per-request
    // outcomes still protect writes immediately, but cannot repeatedly
    // invalidate this entire application root while the network is flapping.
    val connectivity by DCompanyApp.instance.connectivity.presentation.collectAsStateWithLifecycle()
    val networkValidated = connectivity.networkValidated
    val effectiveOnline = connectivity.online
    val syncAvailability = syncAvailabilityProblem(connectivity.phase)
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
            RequiredUpdateScreen(
                notice = update.notice,
                updateState = appUpdateState,
                outboxWorkStatus = outboxWorkStatus,
                onDownload = { appUpdate.download(update.notice) },
                onCancelDownload = appUpdate::cancel,
                onOpenUpdateLink = onOpenUpdateLink,
                onInstall = {
                    appUpdate.verifiedFile(update.notice)?.let { file ->
                        appUpdate.installerLaunchResult(
                            update.notice,
                            onInstallVerifiedUpdate(file),
                        )
                    }
                },
            )
            return
        }

        // Keep the cached workspace usable while the bounded compatibility
        // preflight runs. An authoritative HTTP 426/UpdateRequired result is
        // still handled above as the only blocking update state.
        ClientCompatibilityState.Checking,
        is ClientCompatibilityState.UpdateAvailable,
        ClientCompatibilityState.Supported -> Unit
    }

    when (val s = state) {
        is AuthState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Brand.Gold)
        }

        is AuthState.VerifyingCached -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                CircularProgressIndicator(color = Brand.Gold)
                Text("Checking access", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Restoring ${s.me.name}'s workspace safely…",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "If the server is unavailable, the last verified offline workspace will open automatically.",
                    color = Brand.ForegroundMuted,
                )
            }
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

        is AuthState.SelectTerminal -> SessionViewModelScope(s.me) {
            val reportConnectivity = bugReportConnectivity(effectiveOnline, networkValidated)
            val bugReportVm: BugReportViewModel = viewModel(
                factory = BugReportViewModel.factory(
                    app = DCompanyApp.instance,
                    owner = BugReportOwnerScope(
                        companyId = s.me.companyId,
                        userId = s.me.userId,
                    ),
                ),
            )
            val bugReportState by bugReportVm.state.collectAsStateWithLifecycle()
            fun openSetupSupport() {
                bugReportVm.open(
                    BugReportLaunchContext(
                        currentScreen = "Workspace setup",
                        lastAction = "Choosing a workspace",
                        errorCode = s.error?.let { "workspace_selection_error" },
                    ),
                )
            }
            Box(Modifier.fillMaxSize()) {
                TerminalSelectionScreen(
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
                TextButton(
                    onClick = ::openSetupSupport,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Text("Help")
                }
            }
            BugReportDialog(
                state = bugReportState,
                connectivity = reportConnectivity,
                onReasonChange = bugReportVm::reasonChanged,
                onContinuationChange = bugReportVm::continuationChanged,
                onDescriptionChange = bugReportVm::descriptionChanged,
                onAttachmentChange = bugReportVm::attachmentChanged,
                onAttachmentRejected = bugReportVm::attachmentRejected,
                onAttachmentConsentChange = bugReportVm::attachmentConsentChanged,
                onSubmit = {
                    bugReportVm.submit(
                        currentAndroidBugReportContext(
                            launchContext = bugReportState.launchContext,
                            branchId = s.me.branchId,
                            branchName = s.me.branchName,
                            terminalId = null,
                            terminalName = null,
                            connectivity = reportConnectivity,
                        ),
                    )
                },
                onRetry = bugReportVm::retrySubmitted,
                onOpenHistory = bugReportVm::showHistory,
                onCloseHistory = bugReportVm::closeHistory,
                onRefreshHistory = { bugReportVm.refreshHistory(silent = false) },
                onRetryHistoryItem = bugReportVm::retryHistoryItem,
                onDiscardHistoryItem = bugReportVm::discardHistoryItem,
                onDismiss = bugReportVm::dismiss,
            )
        }

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
                val bugReportVm: BugReportViewModel = viewModel(
                    factory = BugReportViewModel.factory(
                        app = DCompanyApp.instance,
                        owner = BugReportOwnerScope(
                            companyId = s.me.companyId,
                            userId = s.me.userId,
                        ),
                    ),
                )
                val bugReportState by bugReportVm.state.collectAsStateWithLifecycle()
                var confirmSignOut by remember(s.me.userId) { mutableStateOf(false) }
                var currentDestination by rememberSaveable(s.me.userId) {
                    mutableStateOf(resolveWorkspaceDestination(null, destinations))
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
                val visibleDestination = resolveWorkspaceDestination(currentDestination, destinations)
                val requiresTill = permissions.requiresOperationalWorkspace()
                val locationLabel = workspaceLocationLabel(
                    branchId = s.me.branchId,
                    branchName = s.me.branchName,
                    requiresTill = requiresTill,
                    activeTerminal = activeTerminal,
                )
                val advancedTerminalWorkflow = usesAdvancedTerminalWorkflow(activeTerminal)
                val reportConnectivity = bugReportConnectivity(effectiveOnline, networkValidated)
                fun openSupport() {
                    bugReportVm.open(
                        BugReportLaunchContext(
                            currentScreen = visibleDestination.label,
                            lastAction = "Opened Help from ${visibleDestination.label}",
                            errorCode = when (syncAvailability) {
                                cloud.dcompany.erp.ui.components.SyncAvailabilityProblem.NO_NETWORK ->
                                    "network_offline"
                                cloud.dcompany.erp.ui.components.SyncAvailabilityProblem.SERVER_UNREACHABLE ->
                                    "server_unreachable"
                                cloud.dcompany.erp.ui.components.SyncAvailabilityProblem.VERIFYING ->
                                    "connection_verifying"
                                cloud.dcompany.erp.ui.components.SyncAvailabilityProblem.RECOVERING ->
                                    "connection_recovering"
                                cloud.dcompany.erp.ui.components.SyncAvailabilityProblem.NONE -> null
                            },
                        ),
                    )
                }
                WorkspaceScaffold(
                    destinations = destinations,
                    currentDestination = visibleDestination,
                    employeeName = s.me.name,
                    locationLabel = locationLabel,
                    connectivityProblem = syncAvailability,
                    outboxWorkStatus = outboxWorkStatus,
                    syncing = syncing,
                    pendingSupportCount = bugReportState.pendingCount,
                    canChangeTill = s.me.protectedAccess && requiresTill && advancedTerminalWorkflow,
                    onOpenSupport = ::openSupport,
                    onChangeTill = session::requestTerminalReassignment,
                    onSignOut = { confirmSignOut = true },
                    onDestinationChanged = {
                        if (it !in destinations) return@WorkspaceScaffold
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
                            Destination.Dashboard -> AnalyticsScreen()
                            Destination.Pos -> {
                                // Constructing a feature ViewModel starts its
                                // initial API pulls. Keep it inside its allowed
                                // destination so hidden tabs cannot generate
                                // background 403s for low-privilege accounts.
                                val pos: PosViewModel = viewModel()
                                val posState by pos.state.collectAsStateWithLifecycle()
                                val recentPosReceipts by pos.recentReceipts.collectAsStateWithLifecycle()
                                val canonicalPosReceipts by pos.canonicalReceipts.collectAsStateWithLifecycle()
                                val receiptHistorySyncState by
                                    pos.receiptHistorySyncState.collectAsStateWithLifecycle()
                                val receiptHistoryLoading by
                                    pos.receiptHistoryLoading.collectAsStateWithLifecycle()
                                val receiptHistoryError by
                                    pos.receiptHistoryError.collectAsStateWithLifecycle()
                                val unacknowledgedPosReceipt by pos.unacknowledgedReceipt.collectAsStateWithLifecycle()
                                val heldFocus = operationalFocus
                                    as? OperationalNotificationTarget.HeldOrder
                                LaunchedEffect(heldFocus?.orderId) {
                                    heldFocus?.let { pos.focusHeldOrder(it.orderId) }
                                }
                                PosScreen(
                                    state = posState,
                                    recentReceipts = recentPosReceipts,
                                    canonicalReceipts = canonicalPosReceipts,
                                    receiptHistoryHasMore = receiptHistorySyncState?.hasMore == true,
                                    receiptHistoryLoading = receiptHistoryLoading,
                                    receiptHistoryError = receiptHistoryError,
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
                                    onRefreshReceiptHistory = pos::refreshReceiptHistory,
                                    onLoadMoreReceiptHistory = pos::loadMoreReceiptHistory,
                                    onOpenCanonicalReceipt = pos::refreshReceiptHistoryDetail,
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
                            Destination.Reservations -> ReservationsScreen(
                                access = permissions.reservationsAccess(),
                            )
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
                                access = permissions.membershipAccess(s.me),
                            )
                            Destination.Refunds -> RefundsScreen()
                            Destination.AuditLog -> AuditLogScreen()
                            Destination.AccessControl -> AccessControlScreen()
                            Destination.Settings -> SettingsScreen(
                                canManageSystem = canManageSystemSettings(s.me),
                                onPasswordChanged = session::expireAfterPasswordChange,
                                onReportProblem = ::openSupport,
                            )
                            Destination.SupportInbox -> SupportInboxScreen()
                            Destination.Help -> HelpScreen(
                                pendingRequestCount = bugReportState.pendingCount,
                                onReportProblem = ::openSupport,
                                onOpenMyRequests = {
                                    openSupport()
                                    bugReportVm.showHistory()
                                },
                            )
                    }
                }

                BugReportDialog(
                    state = bugReportState,
                    connectivity = reportConnectivity,
                    onReasonChange = bugReportVm::reasonChanged,
                    onContinuationChange = bugReportVm::continuationChanged,
                    onDescriptionChange = bugReportVm::descriptionChanged,
                    onAttachmentChange = bugReportVm::attachmentChanged,
                    onAttachmentRejected = bugReportVm::attachmentRejected,
                    onAttachmentConsentChange = bugReportVm::attachmentConsentChanged,
                    onSubmit = {
                        bugReportVm.submit(
                            currentAndroidBugReportContext(
                                launchContext = bugReportState.launchContext,
                                branchId = s.me.branchId,
                                branchName = s.me.branchName,
                                terminalId = activeTerminal?.terminalId,
                                terminalName = activeTerminal?.terminalName,
                                connectivity = reportConnectivity,
                            ),
                        )
                    },
                    onRetry = bugReportVm::retrySubmitted,
                    onOpenHistory = bugReportVm::showHistory,
                    onCloseHistory = bugReportVm::closeHistory,
                    onRefreshHistory = { bugReportVm.refreshHistory(silent = false) },
                    onRetryHistoryItem = bugReportVm::retryHistoryItem,
                    onDiscardHistoryItem = bugReportVm::discardHistoryItem,
                    onDismiss = bugReportVm::dismiss,
                )

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
        CompatibilityCheckOverlay()
    }
    if (accountSafetyNotice != null) {
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
            updateState = appUpdateState,
            outboxWorkStatus = outboxWorkStatus,
            onDismiss = {
                appUpdate.discard()
                compatibility.dismissOptionalUpdate()
            },
            onDownload = { appUpdate.download(notice) },
            onCancelDownload = appUpdate::cancel,
            onOpenUpdateLink = onOpenUpdateLink,
            onInstall = {
                appUpdate.verifiedFile(notice)?.let { file ->
                    appUpdate.installerLaunchResult(notice, onInstallVerifiedUpdate(file))
                }
            },
        )
    }
}

@Composable
private fun CompatibilityCheckOverlay() {
    Box(
        Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = Brand.SurfaceRaised,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapePill,
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = Brand.Gold,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Checking for app updates · cached work remains available",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
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
    updateState: AppUpdateUiState,
    outboxWorkStatus: OutboxWorkStatus,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenUpdateLink: (String) -> Unit,
    onInstall: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(color = Brand.SurfaceRaised, shape = cloud.dcompany.erp.ui.theme.Radius.shapeMd) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                    Text("App update available", color = Brand.Foreground, style = MaterialTheme.typography.titleSmall)
                    Text(notice.message, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
                        UpdateVersionAndNotes(notice, compact = true)
                    }
                    TextButton(onClick = onDismiss) { Text("Later") }
                }
                UpdateActionArea(
                    notice = notice,
                    state = updateState,
                    outboxWorkStatus = outboxWorkStatus,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onOpenUpdateLink = {
                        onDismiss()
                        onOpenUpdateLink(it)
                    },
                    onInstall = onInstall,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun RequiredUpdateScreen(
    notice: ClientUpdateNotice,
    updateState: AppUpdateUiState,
    outboxWorkStatus: OutboxWorkStatus,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenUpdateLink: (String) -> Unit,
    onInstall: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Surface(
            color = Brand.SurfaceRaised,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
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
                UpdateVersionAndNotes(notice, compact = false)
                Text(
                    "This is an in-place update. Your signed-in account, local database, and saved offline work stay on this device. Never uninstall or clear app data to update.",
                    color = Brand.Foreground,
                )
                UpdateActionArea(
                    notice = notice,
                    state = updateState,
                    outboxWorkStatus = outboxWorkStatus,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onOpenUpdateLink = onOpenUpdateLink,
                    onInstall = onInstall,
                    compact = false,
                )
            }
        }
    }
}

@Composable
private fun UpdateVersionAndNotes(notice: ClientUpdateNotice, compact: Boolean) {
    val version = notice.latestVersionName?.trim()?.take(80)?.takeIf(String::isNotEmpty)
    val notes = notice.releaseNotes?.trim()?.take(2_000)?.takeIf(String::isNotEmpty)
    if (version != null) {
        Text(
            "Release $version · build ${notice.latestVersionCode ?: "unknown"}",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (notes != null) {
        Text(
            if (compact) notes.lineSequence().first().take(180) else "What's new\n$notes",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UpdateActionArea(
    notice: ClientUpdateNotice,
    state: AppUpdateUiState,
    outboxWorkStatus: OutboxWorkStatus,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenUpdateLink: (String) -> Unit,
    onInstall: () -> Unit,
    compact: Boolean,
) {
    val safeUrl = safeHttpsUpdateUrl(notice.updateUrl)
    val directMetadata = validateDirectUpdateMetadata(notice)
    val directDescriptor = (directMetadata as? DirectUpdateMetadataResult.Valid)?.descriptor
    val directAvailable = BuildConfig.DIRECT_UPDATES_ENABLED &&
        directDescriptor != null
    val noticeVersion = notice.latestVersionCode
    val stateForNotice = when (state) {
        is AppUpdateUiState.Downloading,
        is AppUpdateUiState.Verifying,
        is AppUpdateUiState.Ready ->
            directDescriptor?.let { state.takeIf { current -> current.matchesDescriptor(it) } }
        is AppUpdateUiState.Failed -> state.takeIf {
            if (it.descriptor != null) {
                it.descriptor == directDescriptor
            } else {
                it.versionCode == null || it.versionCode == noticeVersion
            }
        }
        AppUpdateUiState.Idle -> state
    } ?: AppUpdateUiState.Idle

    if (!outboxWorkStatus.isClear) {
        Surface(
            color = Brand.WarningMuted,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeSm,
        ) {
            Text(
                "Keep this app installed: ${outboxWorkStatus.totalCount} saved or pending " +
                    "item${if (outboxWorkStatus.totalCount == 1) "" else "s"} will be preserved and resumed after the in-place update.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                color = Brand.Warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    when (stateForNotice) {
        AppUpdateUiState.Idle -> {
            if (directAvailable) {
                Button(onClick = onDownload) { Text("Download verified update") }
                Text(
                    "The APK will be checked for exact size, checksum, package, version and signing lineage before Android can open it.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (safeUrl != null) {
                if (BuildConfig.DIRECT_UPDATES_ENABLED) {
                    Text(
                        "Verified in-app download details are not available for this release. Use the HTTPS update link.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { onOpenUpdateLink(safeUrl) }) { Text("Open update link") }
            } else {
                Text(
                    "No safe HTTPS update link was supplied. Ask an owner for the current D Company ERP release.",
                    color = Brand.Danger,
                )
            }
        }
        is AppUpdateUiState.Downloading -> {
            val progress = if (stateForNotice.totalBytes > 0) {
                stateForNotice.downloadedBytes.toFloat() / stateForNotice.totalBytes.toFloat()
            } else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Information,
                trackColor = Brand.Surface,
            )
            Text(
                "Downloading ${(progress * 100).toInt()}% · do not close the app",
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancelDownload) { Text("Cancel download") }
        }
        is AppUpdateUiState.Verifying -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = Brand.Gold, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Verifying package, version and signature…", color = Brand.Foreground)
        }
        is AppUpdateUiState.Ready -> {
            Text(
                "Verified. Android Package Installer will show the app identity and require your confirmation.",
                color = Brand.Good,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onInstall) { Text("Install update") }
            Text(
                "If Android asks for install permission, allow it, return here, and tap Install update again.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is AppUpdateUiState.Failed -> {
            Text(stateForNotice.message, color = Brand.Danger, style = MaterialTheme.typography.bodySmall)
            if (directAvailable) Button(onClick = onDownload) { Text("Try download again") }
            if (safeUrl != null) {
                TextButton(onClick = { onOpenUpdateLink(safeUrl) }) { Text("Open HTTPS update link") }
            }
        }
    }

    if (!compact && !BuildConfig.DIRECT_UPDATES_ENABLED && safeUrl != null) {
        Text(
            "Android will handle the download/install flow and ask for confirmation. This app does not install updates silently.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

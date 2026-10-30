package cloud.dcompany.erp.review

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.MutableWorkspaceRouteAuthority
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundController
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.screens.settings.WorkspaceBackgroundSettingsCard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import cloud.dcompany.erp.ui.screens.ExecutiveEntrySplash
import cloud.dcompany.erp.ui.screens.LoginWorkspace
import cloud.dcompany.erp.ui.screens.home.ExecutiveHomeWorkspace
import cloud.dcompany.erp.ui.screens.home.HomeActivity
import cloud.dcompany.erp.ui.screens.home.HomeActivityCapabilities
import cloud.dcompany.erp.ui.screens.home.HomeAggregateMetric
import cloud.dcompany.erp.ui.screens.home.HomeAggregateSnapshot
import cloud.dcompany.erp.ui.screens.home.HomeHourPoint
import cloud.dcompany.erp.ui.screens.home.HomeUiState
import java.time.Instant
import androidx.core.view.WindowCompat

private const val ReviewSceneExtra = "dcompany.review.scene"

private enum class ReviewScene {
    Foundation,
    Splash,
    Login;

    companion object {
        fun from(raw: String?): ReviewScene = when (raw?.lowercase()) {
            "splash" -> Splash
            "login" -> Login
            "home", "foundation" -> Foundation
            else -> Foundation
        }
    }
}

class ExecutiveReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val scene = ReviewScene.from(intent.getStringExtra(ReviewSceneExtra))
        setContent { ExecutiveReview(scene) }
    }
}

@Composable
private fun ExecutiveReview(scene: ReviewScene) {
    val destinations = listOf(
        Destination.Dashboard,
        Destination.Gaming,
        Destination.Pos,
        Destination.Shift,
        Destination.Customers,
        Destination.Inventory,
        Destination.Reports,
        Destination.Settings,
        Destination.Help,
    )
    val destinationState = rememberSaveable { mutableStateOf(Destination.Dashboard) }
    var destination by destinationState
    var appearance by rememberSaveable { mutableStateOf(AppearancePreference.Night) }
    val routeAuthority = remember(destinationState, destinations) {
        MutableWorkspaceRouteAuthority(
            readDestination = { destinationState.value },
            writeDestination = { destinationState.value = it },
            allowedDestinations = { destinations },
        )
    }
    val backgroundController: WorkspaceBackgroundController = viewModel()
    val backgroundState by backgroundController.state.collectAsStateWithLifecycle()

    DCompanyTheme(appearance) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (scene) {
                        ReviewScene.Foundation -> WorkspaceScaffold(
                            destinations = destinations,
                            currentDestination = destination,
                            employeeName = "Review only",
                            locationLabel = "OFFLINE · UNKNOWN OPERATIONAL STATE",
                            connectivityProblem = SyncAvailabilityProblem.NO_NETWORK,
                            outboxWorkStatus = OutboxWorkStatus(),
                            syncing = false,
                            canChangeTill = false,
                            appearancePreference = appearance,
                            onAppearancePreferenceChanged = { appearance = it },
                            onOpenSupport = {},
                            onChangeTill = {},
                            onSignOut = {},
                            onDestinationChanged = { routeAuthority.replace(it) },
                            railRouteAuthority = routeAuthority,
                            backgroundState = backgroundState,
                        ) { route, navigateTo ->
                            FoundationSpecimen(
                                destination = route,
                                destinations = destinations,
                                navigateTo = navigateTo,
                                backgroundState = backgroundState,
                                onImportBackground = backgroundController::import,
                                onResetBackground = backgroundController::reset,
                                onSelectBackgroundPreset = backgroundController::selectPreset,
                                onUseSavedBackground = backgroundController::useSavedCustom,
                                onCancelBackground = backgroundController::cancel,
                                onClearBackgroundNotice = backgroundController::clearNotice,
                            )
                        }

                        ReviewScene.Splash -> ExecutiveEntrySplash()
                        ReviewScene.Login -> LoginWorkspace(
                            signingIn = false,
                            error = null,
                            onSignIn = { _, _ -> },
                            appearancePreference = appearance,
                            onAppearancePreferenceChanged = { appearance = it },
                            onRecover = {},
                        )
                    }
                }
                ReviewDisclosure()
            }
        }
    }
}

@Composable
private fun ReviewDisclosure(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("review-disclosure"),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
    ) {
        Text(
            "VISUAL REVIEW · NO BUSINESS CONNECTION",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun FoundationSpecimen(
    destination: Destination,
    destinations: List<Destination>,
    navigateTo: (Destination) -> Unit,
    backgroundState: WorkspaceBackgroundUiState,
    onImportBackground: (WorkspaceBackgroundSlot, Uri) -> Unit,
    onResetBackground: (WorkspaceBackgroundSlot) -> Unit,
    onSelectBackgroundPreset: (String) -> Unit,
    onUseSavedBackground: () -> Unit,
    onCancelBackground: () -> Unit,
    onClearBackgroundNotice: () -> Unit,
) {
    if (destination == Destination.Dashboard) {
        ExecutiveHomeWorkspace(
            employeeName = "Review operator",
            locationLabel = "Synthetic visual fixture · no business connection",
            state = HomeUiState(
                loading = false,
                aggregate = HomeAggregateSnapshot(
                    schemaVersion = 1,
                    ownerUserId = "review-user",
                    ownerCompanyId = "review-company",
                    branchId = "11111111-1111-1111-1111-111111111111",
                    terminalId = "22222222-2222-2222-2222-222222222222",
                    businessDate = "2026-10-23",
                    timezone = "Asia/Kolkata",
                    snapshotAtMillis = 1_792_758_600_000L,
                    revenue = HomeAggregateMetric(2_458_000, 2_100_000, 17.05, "available"),
                    orders = HomeAggregateMetric(48, 42, 14.29, "available"),
                    customerCount = 312,
                    customerScope = "company_live_directory",
                    customerComparisonStatus = "unavailable_no_immutable_history",
                    activeStationCapacity = 9,
                    openSessions = 6,
                    dayLengthMinutes = 1_440,
                    profileBasis = "pos_sales_tip_exclusive_local_hour_profile",
                    profileReconcilesToDailyGross = false,
                    profileRevenueMinor = 1_845_000,
                    profileOrdersCount = 36,
                    hourly = (0..23).map { hour -> HomeHourPoint(hour, if (hour in 12..20) 205_000 else 0, if (hour in 12..20) 4 else 0) },
                ),
                fetchedAtMillis = 1_792_758_600_000L,
                activity = listOf(
                    HomeActivity("pos_order_paid", Instant.parse("2026-10-23T11:30:00Z"), "INV-1042", "Paid order", 24_500, "terminal"),
                    HomeActivity("gaming_session_started", Instant.parse("2026-10-23T11:25:00Z"), "PS5 · 03", "Gaming session started", null, "branch"),
                    HomeActivity("pos_order_paid", Instant.parse("2026-10-23T11:20:00Z"), "INV-1041", "Paid order", 18_900, "terminal"),
                    HomeActivity("gaming_session_started", Instant.parse("2026-10-23T11:15:00Z"), "PS5 · 07", "Gaming session started", null, "branch"),
                    HomeActivity("pos_shift_opened", Instant.parse("2026-10-23T11:10:00Z"), "Till 02", "POS shift opened", null, "terminal"),
                ),
                activityCapabilities = HomeActivityCapabilities("available", "available"),
            ),
            destinations = destinations,
            navigateTo = navigateTo,
            onRetry = {},
            hour = 18,
            reviewFixtureLabel = "Synthetic review values · disconnected from business systems",
        )
        return
    }
    if (destination == Destination.Settings) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "Device appearance",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
            )
            WorkspaceBackgroundSettingsCard(
                state = backgroundState,
                onImport = onImportBackground,
                onReset = onResetBackground,
                onSelectPreset = onSelectBackgroundPreset,
                onUseSavedCustom = onUseSavedBackground,
                onCancelOperation = onCancelBackground,
                onClearNotice = onClearBackgroundNotice,
            )
        }
        return
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            "${if (destination == Destination.Dashboard) "Home" else destination.label} foundation",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineLarge,
        )
        OperationalBanner(
            title = "Review package — no business connection",
            detail = "This screen renders the production shell and shared components with an unknown operational state.",
            tone = UiTone.Warning,
            icon = Icons.Filled.CloudOff,
        )
        SectionCard(
            title = "Executive Dark materials",
            subtitle = "Shared typography, spacing, status, action, border, and surface roles",
            icon = Icons.Filled.Info,
            elevated = true,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                OperationalStatusBadge("Unknown", UiTone.Neutral)
                OperationalStatusBadge("Ready", UiTone.Success)
                OperationalStatusBadge("Review", UiTone.Warning, icon = Icons.Filled.Warning)
                OperationalStatusBadge("Blocked", UiTone.Danger)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ErpButton("Review specimen", onClick = {})
                ErpButton("Secondary", onClick = {}, intent = ActionIntent.Secondary)
                ErpButton("Unavailable", onClick = {}, enabled = false)
            }
        }
    }
}

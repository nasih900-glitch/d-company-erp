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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
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
import cloud.dcompany.erp.ui.screens.ExecutiveEntrySplash
import cloud.dcompany.erp.ui.screens.LoginWorkspace
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
    var destination by rememberSaveable { mutableStateOf(Destination.Dashboard) }
    var appearance by rememberSaveable { mutableStateOf(AppearancePreference.Night) }

    DCompanyTheme(appearance) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                        onDestinationChanged = { destination = it },
                    ) { route, _ ->
                        FoundationSpecimen(route)
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
                ReviewDisclosure(Modifier.align(Alignment.BottomCenter))
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
private fun FoundationSpecimen(destination: Destination) {
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

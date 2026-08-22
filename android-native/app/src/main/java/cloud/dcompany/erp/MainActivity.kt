package cloud.dcompany.erp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.AuthState
import cloud.dcompany.erp.ui.SessionViewModel
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.screens.accesscontrol.AccessControlScreen
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
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        askForNotificationsOnce()
        setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                    AppRoot()
                }
            }
        }
    }

    /**
     * On Android 13+ notifications are opt-in, and without this the overtime
     * alarms post silently into nothing. Asked once at launch rather than from
     * a single screen, because a cashier who only ever opens POS would
     * otherwise never be prompted.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(session: SessionViewModel = viewModel()) {
    val state by session.state.collectAsState()
    val signingIn by session.signingIn.collectAsState()
    val loginError by session.loginError.collectAsState()

    when (val s = state) {
        is AuthState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Brand.Gold)
        }

        is AuthState.SignedOut -> LoginScreen(
            signingIn = signingIn,
            error = loginError,
            onSignIn = session::signIn,
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

        is AuthState.SignedIn -> {
            val pos: PosViewModel = viewModel()
            val posState by pos.state.collectAsState()
            Scaffold(
                containerColor = Brand.Background,
                topBar = {
                    TopAppBar(
                        title = { Text("POS · ${s.me.name}") },
                        actions = { TextButton(onClick = session::signOut) { Text("Sign out") } },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Brand.Surface,
                            titleContentColor = Brand.Foreground,
                        ),
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    // Access Control is gated on audit_access specifically, not
                    // protected_access — a co-owner has protected_access=true but
                    // must not see this, matching the web app's own gate exactly
                    // (audit_access is effectively super-owner-only server-side).
                    val destinations = Destination.entries.filter {
                        (it != Destination.AccessControl || s.me.auditAccess) &&
                            (it != Destination.Analytics || s.me.accessibleModules.contains("insights_reports")) &&
                            (it != Destination.Menu || s.me.accessibleModules.contains("menu")) &&
                            (it != Destination.Staff || s.me.accessibleModules.contains("staff"))
                    }
                    WorkspaceScaffold(header = {}, destinations = destinations) { destination ->
                        when (destination) {
                            Destination.Pos -> PosScreen(
                                state = posState,
                                onAdd = pos::add,
                                onRemove = pos::remove,
                                onSelectCategory = pos::selectCategory,
                                onClearCart = pos::clearCart,
                                onRefresh = pos::refresh,
                                onCapture = pos::captureSale,
                                onDismissNotice = pos::dismissNotice,
                            )
                            Destination.Gaming -> GamingScreen()
                            Destination.Tables -> TablesScreen()
                            Destination.Kitchen -> KitchenScreen()
                            Destination.Shift -> ShiftScreen()
                            Destination.Customers -> CustomersScreen()
                            Destination.Menu -> MenuScreen()
                            Destination.Staff -> StaffScreen()
                            Destination.Inventory -> InventoryScreen()
                            Destination.Reports -> ReportsScreen()
                            Destination.Analytics -> AnalyticsScreen()
                            Destination.Finance -> FinanceScreen()
                            Destination.Events -> EventsScreen()
                            Destination.Memberships -> MembershipsScreen()
                            Destination.Refunds -> RefundsScreen()
                            Destination.AccessControl -> AccessControlScreen()
                            Destination.Settings -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}

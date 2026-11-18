package cloud.dcompany.erp.ui.screens.staff.time

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse

internal val LocalSharedStaffTime = staticCompositionLocalOf<StaffTimeViewModel?> { null }

/** The authenticated workspace owns attendance, including its single password host. */
@Composable
internal fun SharedStaffTimeWorkspace(profile: MeResponse, content: @Composable () -> Unit) {
    val app = DCompanyApp.instance
    val lease by app.cacheIsolation.activeLeaseFlow.collectAsStateWithLifecycle()
    val factory = remember(profile, lease) { viewModelFactory { initializer { StaffTimeViewModel(profile, lease) } } }
    val vm: StaffTimeViewModel = viewModel(key = "staff-time:${profile.userId}:${lease?.generation}:${profile.hashCode()}", factory = factory)
    val dialogs = remember(vm) { SharedAttendancePresentation() }
    val state by vm.sharedState.collectAsStateWithLifecycle()
    val online by app.connectivity.online.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSharedStaffTime provides vm, LocalSharedAttendancePresentation provides dialogs) {
        content()
        SharedAttendanceDialogHost(state, online, dialogs, { vm.refreshShared(true) }, vm::selectShared,
            vm::dismissShared, vm::confirmShared, vm::retryShared, vm::acknowledgeShared)
    }
}

@Composable
internal fun InlineSharedAttendance() {
    val vm = LocalSharedStaffTime.current ?: return
    val app = DCompanyApp.instance
    val profile by app.shiftCache.profile.collectAsStateWithLifecycle()
    if (!EffectivePermissions.from(profile ?: return).has(ErpPermission.StaffAttendanceWrite)) return
    val state by vm.sharedState.collectAsStateWithLifecycle()
    val online by app.connectivity.online.collectAsStateWithLifecycle()
    LaunchedEffect(vm, online) { if (online) vm.refreshShared() else vm.offline() }
    SharedAttendanceCard(state, online, { vm.refreshShared() }, { vm.refreshShared(true) }, vm::selectShared,
        vm::dismissShared, vm::confirmShared, vm::retryShared, vm::acknowledgeShared)
}

package cloud.dcompany.erp.ui.screens.shift.work

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.handover.ReviewedStartWorkResolution
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.ui.remote.RemoteSensitiveContent
import cloud.dcompany.erp.ui.screens.shift.OwnerStartWorkDialog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Public login/setup recovery never installs a session or adopts an employee cache. */
internal class SavedStartWorkRecoveryRuntime(private val visible: () -> Boolean,
    private val app: DCompanyApp = DCompanyApp.instance) : StartWorkRuntime {
    private val session = app.tokens.refreshLease()
    private val generation = app.cacheIsolation.currentGeneration()
    override fun current() = visible() && app.tokens.refreshLease() == session && app.cacheIsolation.currentGeneration() == generation
    override fun online() = app.connectivity.online.value
    override suspend fun capture(): StartWorkAuthority = error("Sign in to start a new shift.")
    override suspend fun attempt(authority: StartWorkAuthority, pending: PendingStartWork,
        receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit): Unit = error("Recovery cannot resend an old start.")
    override suspend fun observe(pending: PendingStartWork, ownerEmail: String?, password: String?, cancel: Boolean,
        receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit) {
        check(current() && online()); pending.validate(); require(!ownerEmail.isNullOrBlank() && !password.isNullOrEmpty())
        check(app.awaitPersistedStartupState() == PersistedStartupStateResult.Ready && current())
        val permit = app.handoverOperations.beginStartWorkOwnerRecovery(pending.reference, if(cancel) "owner_cancel" else "owner_status")
        try {
            val api = ApiClient.startWorkReviewApi<OwnerStartWorkApi>(permit, beforeDispatch = { check(current() && online()) })
            val outcome = api.review(OwnerStartWorkReview(pending.reference, if(cancel) "cancel" else "status",
                ownerEmail, password, if(cancel) pending.cancelReason else null))
            check(current()); outcome.validate(pending.reference)
            check(outcome.state != "unconfirmed") { "No result has been confirmed. An Owner can retire this saved request if it never committed." }
            if (outcome.state == "committed") {
                receipt(requireNotNull(outcome.receipt))
                if (outcome.currentShift?.status != "closed") throw StartWorkRecoveryReviewRequired(
                    "The earlier shift start was recorded and the shift is still open. An authorized staff member must review it in its original shop before this saved request can be settled. The shift and attendance were preserved.")
            }
            permit.owner.checkpointStartWorkReview(permit, ReviewedStartWorkResolution(pending.reference,outcome), ::current) {
                check(current()); complete(outcome)
            }
        } finally { withContext(NonCancellable) { permit.owner.finishStartWorkReview(permit) } }
    }
}

/** Before Owner proof the login screen reveals no former employee, company or cash details. */
@Composable
internal fun SavedStartWorkRecoveryHost(workspaceKey: Any, available: Boolean, stillVisible: () -> Boolean) {
    if (!available) return
    key(workspaceKey) {
        val app = DCompanyApp.instance
        val controller = remember { StartWorkController(SavedStartWorkRecoveryRuntime(stillVisible),
            AtomicStartWorkStore(File(app.noBackupFilesDir.canonicalFile,START_WORK_DIRECTORY))) }
        val state by controller.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var open by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<PendingStartWork?>(null) }
        LaunchedEffect(controller) { controller.refresh() }
        if (state.pending.isNotEmpty() || (!state.ready && state.error != null)) {
            Box(Modifier.fillMaxSize().padding(16.dp),contentAlignment=Alignment.BottomCenter) {
                Surface(Modifier.widthIn(max=560.dp),shape=MaterialTheme.shapes.medium,
                    tonalElevation=6.dp) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("A saved work-start request needs review",style=MaterialTheme.typography.titleSmall)
                        Text("An Owner can check or retire this request without changing the signed-in account or deleting saved work.",style=MaterialTheme.typography.bodySmall)
                        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        state.notice?.let { Text(it) }
                        TextButton({ open=true },enabled=state.ready && !state.busy) { Text("Owner review saved start") }
                        if (!state.ready) TextButton({ scope.launch { controller.refresh() } }) { Text("Check saved requests") }
                    }
                }
            }
        }
        if (open) RemoteSensitiveContent {
            cloud.dcompany.erp.ui.components.LiquidAlertDialog(onDismissRequest={if(!state.busy)open=false},
                title={Text("Owner review of saved requests")}, text={
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        state.pending.forEachIndexed { index,pending ->
                            TextButton({ selected=pending;open=false },enabled=!state.busy) { Text("Review saved request ${index+1}") }
                        }
                    }
                },confirmButton={},dismissButton={TextButton({open=false}){Text("Close")}})
        }
        selected?.let { pending -> RemoteSensitiveContent {
            OwnerStartWorkDialog(pending,state.busy,{selected=null},displayName="Saved work-start request",initialReason="") { email,password,reason,cancel ->
                selected=null;scope.launch { controller.owner(pending,email,password,reason,cancel) }
            }
        } }
    }
}

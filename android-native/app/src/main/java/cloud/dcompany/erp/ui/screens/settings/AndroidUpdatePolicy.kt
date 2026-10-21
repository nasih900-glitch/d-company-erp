package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface AndroidUpdatePolicyApi {
    @retrofit2.http.GET("client-updates/android/policy")
    suspend fun androidUpdatePolicy(): AndroidUpdatePolicyDto
    @retrofit2.http.PUT("client-updates/android/policy")
    suspend fun setAndroidUpdatePolicy(@retrofit2.http.Body body: AndroidUpdatePolicyBody): AndroidUpdatePolicyDto
}

@Serializable internal data class PolicyActiveRelease(
    val id: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val status: String,
    @SerialName("manifest_sha256") val manifestSha256: String,
    @SerialName("policy_fingerprint") val policyFingerprint: String,
)
@Serializable internal data class AndroidUpdatePolicyDto(
    val mode: String, val revision: Int,
    @SerialName("environment_minimum_version_code") val environmentMinimum: Int,
    @SerialName("minimum_supported_version_code") val minimum: Int,
    @SerialName("active_release") val activeRelease: PolicyActiveRelease? = null,
)
@Serializable internal data class AndroidUpdatePolicyBody(
    val mode: String,
    @SerialName("expected_revision") val expectedRevision: Int,
    val reason: String,
    @SerialName("release_id") val releaseId: String? = null,
    @SerialName("expected_release_fingerprint") val expectedReleaseFingerprint: String? = null,
)
internal fun updatePolicyAuthority(profile: MeResponse?): String? = profile?.takeIf { it.releaseControlAccess }?.let {
    "${it.companyId}|${it.userId}|${it.branchId.orEmpty()}"
}
internal fun policyWriteBody(policy: AndroidUpdatePolicyDto, mode: String, reason: String): AndroidUpdatePolicyBody? {
    if (mode !in setOf("optional", "required") || reason.trim().length !in 3..500) return null
    if (mode == "optional") return AndroidUpdatePolicyBody(mode, policy.revision, reason.trim())
    val target = policy.activeRelease?.takeIf { it.status == "active" && it.policyFingerprint.matches(Regex("[a-f0-9]{64}")) } ?: return null
    return AndroidUpdatePolicyBody(mode, policy.revision, reason.trim(), target.id, target.policyFingerprint)
}
internal data class AndroidUpdatePolicyState(
    val authority: String? = null, val policy: AndroidUpdatePolicyDto? = null,
    val mode: String = "optional", val reason: String = "", val reviewed: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
)

/** Online-only global controller, bound to the current verified cache/auth generation. */
internal class AndroidUpdatePolicyViewModel(
    private val api: AndroidUpdatePolicyApi = ApiClient.create(),
    private val tasks: CoroutineScope? = null,
    private val lease: () -> CacheScopeLease? = { DCompanyApp.instance.cacheIsolation.currentLease() },
    private val commit: suspend (CacheScopeLease, () -> Unit) -> Boolean = { captured, change ->
        DCompanyApp.instance.cacheIsolation.commitIfCurrent(captured, change)
    },
) : ViewModel() {
    private val mutable = MutableStateFlow(AndroidUpdatePolicyState())
    val state = mutable.asStateFlow()
    private val scope get() = tasks ?: viewModelScope
    private var generation = 0L
    private var request: Job? = null
    private var boundLease: CacheScopeLease? = null
    fun bind(profile: MeResponse?) {
        val authority = updatePolicyAuthority(profile)
        val currentLease = lease()
        if (authority == mutable.value.authority && currentLease == boundLease) return
        boundLease = currentLease
        ++generation; request?.cancel()
        mutable.value = AndroidUpdatePolicyState(authority = authority)
        if (authority != null) refresh()
    }
    fun mode(value: String) { if (!mutable.value.busy && value in setOf("optional", "required")) mutable.value = mutable.value.copy(mode=value, reviewed=false) }
    fun reason(value: String) { if (!mutable.value.busy) mutable.value = mutable.value.copy(reason=value.take(500), reviewed=false) }
    fun reviewed(value: Boolean) { if (!mutable.value.busy) mutable.value = mutable.value.copy(reviewed=value) }
    fun refresh() = perform(null)
    fun apply() {
        val before = mutable.value
        if (before.busy || !before.reviewed) return
        val body = before.policy?.let { policyWriteBody(it, before.mode, before.reason) } ?: return
        perform(body)
    }
    private fun perform(body: AndroidUpdatePolicyBody?) {
        val before = mutable.value
        val authority = before.authority ?: return
        if (before.busy) return
        val captured = lease()?.takeIf { authority == "${it.scope.companyId}|${it.scope.userId}|${it.scope.branchId.orEmpty()}" } ?: return
        val operation = ++generation
        request?.cancel()
        mutable.value = before.copy(busy=true, error=null, notice=null, reviewed=false)
        request = scope.launch {
            try {
                // A lost PUT response is not guessed or replayed with a different revision.
                // Refresh exposes the committed global state for another explicit review.
                val policy = if (body == null) api.androidUpdatePolicy() else api.setAndroidUpdatePolicy(body)
                commit(captured) {
                    if (operation == generation && mutable.value.authority == authority) mutable.value = mutable.value.copy(
                        policy=policy, mode=if (policy.mode == "required") "required" else "optional",
                        reason="", busy=false, notice=if(body!=null) "Policy saved at revision ${policy.revision}. Saved tablet work is unchanged." else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                commit(captured) { if (operation == generation && mutable.value.authority == authority) mutable.value = mutable.value.copy(
                    policy=if(body!=null || failure.policyAuthorityRejected()) null else mutable.value.policy,
                    reason=if(body!=null || failure.policyAuthorityRejected()) "" else mutable.value.reason,
                    reviewed=false, busy=false, error="${failure.message ?: "Policy could not be confirmed."} Refresh and review before retrying.") }
            }
        }
    }
}

private fun Exception.policyAuthorityRejected(): Boolean =
    (this as? cloud.dcompany.erp.core.net.ApiException)?.status in setOf(401,403) ||
        (this as? retrofit2.HttpException)?.code() in setOf(401,403)

@Composable internal fun AndroidUpdatePolicyPanel(profile: MeResponse, vm: AndroidUpdatePolicyViewModel = viewModel()) {
    val authority = updatePolicyAuthority(profile)
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(profile) { vm.bind(profile) }
    if (authority == null || state.authority != authority) return
    val policy = state.policy
    SectionCard(title="Optional / Required updates", subtitle="Global direct Android installations · including your own app") {
        Text("Android still requires installation approval. Saved sales and Sync work are never deleted.", style=MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        policy?.let {
            Text("Current: ${if(it.mode=="inherit") "Deployment policy" else it.mode.replaceFirstChar(Char::uppercase)} · revision ${it.revision}")
            Text("Minimum supported build ${it.minimum}. Optional keeps the deployment safety floor at ${it.environmentMinimum}.")
            Text("Build 55 checks about once a second while online and in the foreground. Offline and older apps react at their next server check. Explicit Optional clears an older Required block but hides optional banners below build 55.", style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(selected=state.mode=="optional", onClick={vm.mode("optional")}, enabled=!state.busy, label={Text("Optional")})
                FilterChip(selected=state.mode=="required", onClick={vm.mode("required")}, enabled=!state.busy, label={Text("Required")})
            }
            if(state.mode=="required") Text(it.activeRelease?.let { target -> "Target: ${target.versionName} · build ${target.versionCode} · manifest ${target.manifestSha256}" } ?: "No active release. Offer a verified release first.")
            OutlinedTextField(value=state.reason, onValueChange=vm::reason, enabled=!state.busy,
                label={Text("Reason")}, modifier=Modifier.fillMaxWidth().testTag("update-policy-reason"))
            Row { Checkbox(checked=state.reviewed, onCheckedChange=vm::reviewed, enabled=!state.busy); Text("I reviewed the global effect and selected release.", modifier=Modifier.weight(1f)) }
            Button(onClick=vm::apply, enabled=!state.busy && state.reviewed && policyWriteBody(it,state.mode,state.reason)!=null,
                modifier=Modifier.heightIn(min=48.dp)) { Text("Apply update policy") }
        } ?: Text("Policy has not been loaded. No update policy is assumed.")
        state.error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
        TextButton(onClick=vm::refresh, enabled=!state.busy) { Text("Refresh policy") }
    }
}

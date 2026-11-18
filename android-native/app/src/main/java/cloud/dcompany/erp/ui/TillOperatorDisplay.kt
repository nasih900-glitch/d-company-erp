package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/** Display facts only; never used to authorize a till operation. */
@Serializable
internal data class TillOperatorRead(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("shift_status") val shiftStatus: String,
    @SerialName("operator_user_id") val operatorUserId: String? = null,
    @SerialName("operator_name") val operatorName: String? = null,
    val revision: Int,
    val tracking: String,
)

internal interface TillOperatorApi {
    @GET("pos/shifts/{id}/operator-state")
    suspend fun operatorState(@Path("id") id: String): TillOperatorRead
}

internal data class TillOperatorScope(
    val lease: CacheScopeLease,
    val terminal: ValidatedTerminalDisplay,
    val actorName: String,
    val shiftId: String? = null,
)

internal fun tillOperatorScope(
    profile: MeResponse?,
    terminal: ValidatedTerminalDisplay?,
    lease: CacheScopeLease?,
): TillOperatorScope? {
    if (profile == null || terminal == null || lease == null) return null
    if (!EffectivePermissions.from(profile).hasAny(ErpPermission.PosWrite, ErpPermission.PosShiftHandoverRecover)) return null
    val scope = lease.scope
    if (scope.userId != profile.userId || scope.companyId != profile.companyId ||
        scope.branchId != profile.branchId || scope.terminalId != terminal.terminalId ||
        (scope.branchId != null && scope.branchId != terminal.branchId)) return null
    return TillOperatorScope(lease, terminal, profile.name)
}

internal fun RealtimeEvent.requiresTillOperatorRefresh(): Boolean =
    this == RealtimeEvent.ReconnectedAfterGap || this == RealtimeEvent.Changed("shifts")

internal enum class TillOperatorStatus { NO_SHIFT, CHECKING, VERIFIED, STALE, UNAVAILABLE }

internal data class TillOperatorDisplay(
    val actorName: String,
    val status: TillOperatorStatus,
    val verified: TillOperatorRead? = null,
) {
    val operatorLabel: String
        get() = when {
            status == TillOperatorStatus.NO_SHIFT -> "No open till shift"
            verified?.tracking == "untracked" -> "Till operator not recorded"
            verified?.operatorName?.isNotBlank() == true -> "Till operator: ${verified.operatorName}"
            verified?.operatorUserId != null -> "Till operator: name unavailable"
            status == TillOperatorStatus.CHECKING -> "Checking till operator…"
            else -> "Till operator unavailable"
        }
    val detail: String
        get() {
            val freshness = when (status) {
                TillOperatorStatus.STALE -> "Last verified · current status unavailable"
                TillOperatorStatus.CHECKING -> if (verified != null) "Last verified · checking…" else null
                TillOperatorStatus.VERIFIED -> if (verified?.tracking == "untracked") "Handover not yet recorded" else "Verified"
                else -> null
            }
            return listOfNotNull(freshness, "Signed in: $actorName").joinToString(" · ")
        }
}

/** Main-dispatcher owner. Tickets reject late responses even from a non-cancellable transport. */
internal class TillOperatorDisplayController(
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val isCurrent: (TillOperatorScope) -> Boolean,
    private val load: suspend (TillOperatorScope) -> TillOperatorRead,
) {
    private val _display = MutableStateFlow<TillOperatorDisplay?>(null)
    val display = _display.asStateFlow()
    private var current: TillOperatorScope? = null
    private var job: Job? = null
    private var ticket = 0L

    fun setScope(next: TillOperatorScope?) {
        if (next == current) return
        invalidate()
        current = next
        _display.value = next?.let {
            TillOperatorDisplay(it.actorName, if (it.shiftId == null) TillOperatorStatus.NO_SHIFT else TillOperatorStatus.CHECKING)
        }
        refresh()
    }

    fun refresh() {
        invalidate()
        val expected = current ?: return
        if (!isCurrent(expected)) {
            current = null
            _display.value = null
            return
        }
        if (expected.shiftId == null) return
        val old = _display.value?.verified
        if (!online()) {
            _display.value = TillOperatorDisplay(expected.actorName,
                if (old != null) TillOperatorStatus.STALE else TillOperatorStatus.UNAVAILABLE, old)
            return
        }
        _display.value = TillOperatorDisplay(expected.actorName, TillOperatorStatus.CHECKING, old)
        val request = ticket
        job = scope.launch {
            try {
                val result = load(expected)
                if (!mayPublish(expected, request)) return@launch
                require(result.shiftId == expected.shiftId) { "The operator response belongs to another shift." }
                require(result.shiftStatus in setOf("open", "closed")) { "The shift status is unknown." }
                if (result.shiftStatus == "closed") {
                    _display.value = TillOperatorDisplay(expected.actorName, TillOperatorStatus.NO_SHIFT)
                } else {
                    require((result.tracking == "untracked" && result.operatorUserId == null && result.revision == 0) ||
                        (result.tracking == "recorded" && !result.operatorUserId.isNullOrBlank() && result.revision >= 1)) {
                        "The operator response is incomplete."
                    }
                    _display.value = TillOperatorDisplay(expected.actorName, TillOperatorStatus.VERIFIED, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mayPublish(expected, request)) {
                    _display.value = TillOperatorDisplay(expected.actorName,
                        if (old != null) TillOperatorStatus.STALE else TillOperatorStatus.UNAVAILABLE, old)
                }
            }
        }
    }

    /** A current UI launch hint only; the handover transport still re-verifies the live shift. */
    fun verifiedOpenShiftHint(expected: TillOperatorDisplay): String? {
        val owner = current ?: return null
        val read = expected.verified ?: return null
        if (_display.value !== expected || !isCurrent(owner) || !online() ||
            expected.status != TillOperatorStatus.VERIFIED || read.shiftStatus != "open" ||
            read.shiftId.isBlank() || read.shiftId != owner.shiftId) return null
        return read.shiftId
    }

    private fun mayPublish(expected: TillOperatorScope, request: Long): Boolean =
        current == expected && ticket == request && isCurrent(expected)

    private fun invalidate() {
        ticket++
        job?.cancel()
        job = null
    }
}

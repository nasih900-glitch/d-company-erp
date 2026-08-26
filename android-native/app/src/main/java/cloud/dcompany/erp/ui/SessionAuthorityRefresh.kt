package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val FORCED_LOGOUT_MESSAGE =
    "Your access changed or this sign-in expired. Sign in again. Ask a manager if this was unexpected."

internal const val ACCESS_CHANGED_MESSAGE =
    "Your access was updated by an owner. The sections and actions shown now match your current role."

internal fun RealtimeEvent.requiresAuthorityRefresh(): Boolean = when (this) {
    // Role/status edits are broadcast as `staff` and advance auth_version;
    // checking /me here makes revocation immediate even on a screen that
    // otherwise has no reason to call a staff endpoint.
    is RealtimeEvent.Changed -> resource == "access_control" || resource == "staff"
    RealtimeEvent.ReconnectedAfterGap -> true
}

/** `/auth/me` may replace authority only inside the login lineage that requested it. */
internal fun canApplyAuthorityProfile(
    previous: MeResponse,
    refreshed: MeResponse,
    activeAccessToken: String,
): Boolean =
    OutboxOwnerIdentity.from(previous) == OutboxOwnerIdentity.from(refreshed) &&
        cachedProfileMatchesToken(activeAccessToken, refreshed)

internal fun accessAuthorityChanged(previous: MeResponse, refreshed: MeResponse): Boolean =
    previous.roles.toSet() != refreshed.roles.toSet() ||
        previous.protectedAccess != refreshed.protectedAccess ||
        previous.auditAccess != refreshed.auditAccess ||
        previous.accessibleModules?.toSet() != refreshed.accessibleModules?.toSet() ||
        previous.effectivePermissions?.toSet() != refreshed.effectivePermissions?.toSet()

/**
 * A conflated, serial worker for websocket bursts. Requests received during a
 * refresh produce exactly one trailing pass; the short quiet period coalesces
 * a reconnect plus its immediately-following access-control frames.
 */
internal class SessionAuthorityRefreshCoordinator(
    scope: CoroutineScope,
    private val debounceMillis: Long = 250L,
    private val refresh: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job = scope.launch {
        for (ignored in requests) {
            if (debounceMillis > 0) delay(debounceMillis)
            while (requests.tryReceive().isSuccess) Unit
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A refresh failure must not kill the long-lived collector.
                // The next realtime frame/reconnect can retry; API failures
                // are classified and surfaced by SessionViewModel.
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }

    fun cancel() {
        requests.close()
        worker.cancel()
    }
}

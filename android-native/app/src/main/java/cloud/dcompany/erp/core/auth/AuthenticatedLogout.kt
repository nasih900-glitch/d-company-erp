package cloud.dcompany.erp.core.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of the optional server half of sign-out.
 *
 * Local credential removal is authoritative for the tablet and always runs;
 * this value exists only for tests and diagnostics, never to hold the login UI
 * open when the cafe network is unavailable.
 */
internal enum class RemoteLogoutResult {
    REVOKED,
    FAILED,
    TIMED_OUT,
}

/**
 * Give the backend a short opportunity to revoke this refresh-token family,
 * then unconditionally remove the tablet session.
 *
 * The whole sequence is [NonCancellable] once the employee has confirmed
 * sign-out. That prevents Activity/ViewModel teardown from interrupting the
 * security-critical local clear. The remote call still has its own strict
 * timeout, so offline DNS/TLS/server failures cannot strand the employee on a
 * Signing out screen. A failure from [clearLocalSession] is deliberately not
 * swallowed: the caller must keep the tablet locked and explain that durable
 * credential deletion failed.
 */
internal suspend fun bestEffortAuthenticatedLogout(
    timeoutMillis: Long,
    revokeRemoteSession: suspend () -> Unit,
    clearLocalSession: suspend () -> Unit,
): RemoteLogoutResult = withContext(NonCancellable) {
    val result = try {
        if (timeoutMillis <= 0L) {
            // A configuration mistake must not bypass the security-critical
            // local credential clear. Skip the remote attempt and surface it
            // as a diagnostic failure while still completing local sign-out.
            RemoteLogoutResult.FAILED
        } else {
            withTimeoutOrNull(timeoutMillis) {
                revokeRemoteSession()
                RemoteLogoutResult.REVOKED
            } ?: RemoteLogoutResult.TIMED_OUT
        }
    } catch (_: Exception) {
        // Server revocation is defence in depth. The employee must still be
        // signed out locally when DNS, TLS, serialization, or HTTP fails.
        RemoteLogoutResult.FAILED
    } finally {
        clearLocalSession()
    }

    result
}

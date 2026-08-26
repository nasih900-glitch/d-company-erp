package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.TokenStore
import java.io.IOException
import java.util.concurrent.CountDownLatch

internal fun interface SessionRefreshObserver {
    fun onFollowerJoined()
}

private object NoOpSessionRefreshObserver : SessionRefreshObserver {
    override fun onFollowerJoined() = Unit
}

/**
 * Synchronous single-flight refresh for OkHttp interceptor threads.
 *
 * Requests that received 401 with the same exact session snapshot share one
 * refresh call. TokenStore's lease then makes both refresh installation and a
 * definitive rejection conditional: logout or a new login always wins.
 */
internal class SessionRefreshCoordinator(
    private val tokenStore: TokenStore,
    private val refreshCall: (String) -> TokenPair,
    private val onForcedLogout: () -> Unit,
    private val observer: SessionRefreshObserver = NoOpSessionRefreshObserver,
) {
    private val flightLock = Any()
    private var inFlight: RefreshFlight? = null

    fun refresh(lease: SessionRefreshLease): String? {
        var leader = false
        val flight = synchronized(flightLock) {
            if (!tokenStore.isCurrent(lease)) {
                // A successful leader may already have rotated this same
                // login. A sign-out or explicit login has another lineage and
                // intentionally returns null.
                return tokenStore.currentAccessFor(lease)
            }

            val active = inFlight
            if (active != null && active.lease.isSameSnapshot(lease)) {
                observer.onFollowerJoined()
                active
            } else {
                leader = true
                RefreshFlight(lease).also { inFlight = it }
            }
        }

        if (!leader) {
            val shared = flight.await()
            return shared?.takeIf { tokenStore.currentAccessFor(lease) == it }
        }

        val outcome = try {
            RefreshOutcome(accessToken = performRefresh(lease), failure = null)
        } catch (failure: Throwable) {
            RefreshOutcome(accessToken = null, failure = failure)
        }
        flight.complete(outcome)
        synchronized(flightLock) {
            if (inFlight === flight) inFlight = null
        }
        outcome.failure?.let { throw it }
        return outcome.accessToken?.takeIf { tokenStore.currentAccessFor(lease) == it }
    }

    private fun performRefresh(lease: SessionRefreshLease): String? {
        return try {
            val pair = refreshCall(lease.refreshToken)
            tokenStore.saveRefreshedIfCurrent(
                lease = lease,
                access = pair.accessToken,
                refresh = pair.refreshToken,
            )
            // Logout/new-login wins with null; a separately completed refresh
            // in the same lineage may safely be reused.
            tokenStore.currentAccessFor(lease)
        } catch (error: ApiException) {
            if (error.status == 401 || error.status == 403) {
                val cleared = tokenStore.clearIfCurrent(lease)
                if (cleared) runCatching(onForcedLogout)
                // If another refresh won, use it. Never clear or reuse an
                // explicit login, which has a different lineage.
                return tokenStore.currentAccessFor(lease)
            }
            // Preserve the real 5xx/network classification. Returning null
            // would make AuthInterceptor return the original access-token
            // 401, and SessionViewModel would then destroy a valid offline
            // session instead of offering cached-profile recovery.
            throw error
        } catch (error: IOException) {
            // The dedicated refresh client normally wraps transport failures
            // as ApiException(network_error); retain this guard for injected
            // clients/tests and propagate rather than fabricating auth loss.
            throw error
        }
    }

    private class RefreshFlight(
        val lease: SessionRefreshLease,
    ) {
        private val completed = CountDownLatch(1)

        @Volatile
        private var outcome: RefreshOutcome? = null

        fun complete(value: RefreshOutcome) {
            outcome = value
            completed.countDown()
        }

        fun await(): String? {
            try {
                completed.await()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for session refresh", error)
            }
            val completedOutcome = checkNotNull(outcome)
            completedOutcome.failure?.let { throw it }
            return completedOutcome.accessToken
        }
    }

    private class RefreshOutcome(
        val accessToken: String?,
        val failure: Throwable?,
    )
}

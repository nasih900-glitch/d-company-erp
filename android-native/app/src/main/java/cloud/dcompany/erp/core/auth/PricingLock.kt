package cloud.dcompany.erp.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-memory holder for a live `X-Pricing-Token` — the shared piece four
 * screens need (Menu here first, later Events/Memberships/Gaming stations):
 * price-field edits stay online-only everywhere in this app, gated behind a
 * fresh password re-check (`POST /admin/pricing/unlock`), because there is
 * no safe way to unlock pricing offline.
 *
 * Deliberately **not** persisted to disk (unlike the web app's localStorage
 * approach) — a 10-minute unlock surviving an app restart isn't worth the
 * small extra risk of a stale token sitting in storage; requiring a fresh
 * unlock after a restart is the safer default for a shared café tablet.
 */
object PricingLock {
    private data class Held(val token: String, val expiresAtMillis: Long)

    private val held = MutableStateFlow<Held?>(null)

    private val _unlocked = MutableStateFlow(false)
    /** For a small "Pricing unlocked" indicator, if a screen wants one. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun unlock(token: String, expiresInSeconds: Int) {
        held.value = Held(token, System.currentTimeMillis() + expiresInSeconds * 1000L)
        _unlocked.value = true
    }

    fun lock() {
        held.value = null
        _unlocked.value = false
    }

    /**
     * Null once expired — self-heals back to "locked" the moment anything
     * asks, same as the web app's own interceptor proactively clearing an
     * expired token rather than sending it and waiting for a 401.
     */
    fun currentToken(): String? {
        val h = held.value ?: return null
        if (System.currentTimeMillis() >= h.expiresAtMillis) {
            lock()
            return null
        }
        return h.token
    }
}

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
    private class Held(
        val token: String,
        val expiresAtMillis: Long,
        val lineage: Any,
        val owner: OutboxOwnerIdentity,
    )

    private val mutationLock = Any()

    private val held = MutableStateFlow<Held?>(null)

    private val _unlocked = MutableStateFlow(false)
    /** For a small "Pricing unlocked" indicator, if a screen wants one. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    internal fun unlock(token: String, expiresInSeconds: Int, session: PricingSessionLease) {
        require(token.isNotBlank()) { "Pricing token cannot be blank" }
        require(expiresInSeconds > 0) { "Pricing unlock must have a positive lifetime" }
        synchronized(mutationLock) {
            held.value = Held(
                token = token,
                expiresAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L,
                lineage = session.lineage,
                owner = session.owner,
            )
            _unlocked.value = true
        }
    }

    fun lock() {
        synchronized(mutationLock) {
            held.value = null
            _unlocked.value = false
        }
    }

    /**
     * Null once expired — self-heals back to "locked" the moment anything
     * asks, same as the web app's own interceptor proactively clearing an
     * expired token rather than sending it and waiting for a 401.
     */
    internal fun currentToken(session: PricingSessionLease?): String? = synchronized(mutationLock) {
        val h = held.value ?: return@synchronized null
        if (
            session == null ||
            h.lineage !== session.lineage ||
            h.owner != session.owner ||
            System.currentTimeMillis() >= h.expiresAtMillis
        ) {
            held.value = null
            _unlocked.value = false
            return@synchronized null
        }
        h.token
    }
}

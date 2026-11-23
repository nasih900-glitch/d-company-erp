package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.LoginSessionLease
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.net.MeResponse

/**
 * Process-local proof that the visible success handoff belongs to the explicit
 * login and exact cache scope which just opened the workspace. It is cosmetic,
 * but it must never survive a lineage, account, branch, till, or scope-generation
 * change and accidentally cover a different employee's workspace.
 */
internal class FreshLoginHandoffCoordinator(
    private val nextNonce: () -> Long,
    private val isLoginCurrent: (LoginSessionLease) -> Boolean,
    private val currentScopeLease: () -> CacheScopeLease?,
) {
    private data class Binding(
        val nonce: Long,
        val owner: OutboxOwnerIdentity,
        val login: LoginSessionLease,
        val scope: CacheScopeLease,
    )

    private var binding: Binding? = null

    /** Arm only after authentication and exact cache-scope activation finish. */
    @Synchronized
    fun arm(me: MeResponse, login: LoginSessionLease): Long? {
        if (!isLoginCurrent(login)) return null
        val scope = currentScopeLease() ?: return null
        val owner = OutboxOwnerIdentity.from(me)
        if (!scope.matches(owner)) return null
        val nonce = nextNonce().takeIf { it > 0L } ?: return null
        // Validate the whole capability tuple before replacing an existing
        // handoff. A late completion from an older login must be a no-op; it
        // must not erase the newer login's still-visible success screen.
        binding = Binding(nonce, owner, login, scope)
        return nonce
    }

    /**
     * A same-lineage authority refresh may update permissions while the handoff
     * is visible. Preserve it only when every opaque capability is still exact.
     */
    @Synchronized
    fun preserve(
        visibleNonce: Long?,
        previous: MeResponse,
        refreshed: MeResponse,
    ): Long? {
        val expected = binding ?: return null
        val previousOwner = OutboxOwnerIdentity.from(previous)
        val refreshedOwner = OutboxOwnerIdentity.from(refreshed)
        if (visibleNonce != expected.nonce) return null
        val valid =
            previousOwner == expected.owner &&
            refreshedOwner == expected.owner &&
            isLoginCurrent(expected.login) &&
            currentScopeLease() == expected.scope
        if (!valid) clear()
        return expected.nonce.takeIf { valid }
    }

    /** Consume exactly once; a stale gesture or callback is a no-op. */
    @Synchronized
    fun consume(visibleNonce: Long?, requestedNonce: Long, me: MeResponse): Boolean {
        val expected = binding ?: return false
        val valid = visibleNonce == expected.nonce &&
            requestedNonce == expected.nonce &&
            OutboxOwnerIdentity.from(me) == expected.owner &&
            isLoginCurrent(expected.login) &&
            currentScopeLease() == expected.scope
        if (valid) clear()
        return valid
    }

    @Synchronized
    fun clear() {
        binding = null
    }

    private fun CacheScopeLease.matches(owner: OutboxOwnerIdentity): Boolean =
        scope.userId == owner.userId &&
            scope.companyId == owner.companyId &&
            scope.branchId == owner.branchId
}

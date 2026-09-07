package cloud.dcompany.erp.core.alarm

import android.content.Context
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CachedScopeLeaseAdoption
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.net.MeResponse

/**
 * Reconstructs the exact Room scope that a cached, encrypted session may use
 * after Android starts only a receiver process (boot, package replacement, or
 * an alarm firing after process death). A persisted profile or terminal id on
 * its own is never enough authority to expose another employee's reminders.
 */
internal fun cachedOperationalAlarmScope(
    accessToken: String,
    profile: MeResponse,
    persistedTerminalId: String?,
): CacheScope? {
    if (AccessTokenIdentityParser.parse(accessToken) != OutboxOwnerIdentity.from(profile)) {
        return null
    }
    val terminalId = if (EffectivePermissions.from(profile).requiresOperationalWorkspace()) {
        persistedTerminalId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    } else {
        null
    }
    return runCatching {
        CacheScope(
            userId = profile.userId.trim(),
            companyId = profile.companyId.trim(),
            branchId = profile.branchId?.trim()?.takeIf(String::isNotEmpty),
            terminalId = terminalId,
        )
    }.getOrNull()
}

/**
 * A disproved saved owner may cancel stale alarms. A competing active lease is
 * instead an in-process race and must propagate into the retry/preserve path.
 */
internal fun operationalAlarmAdoptionOrNull(
    result: CachedScopeLeaseAdoption,
): CachedScopeLeaseAdoption.Ready? = when (result) {
    is CachedScopeLeaseAdoption.Ready -> result
    CachedScopeLeaseAdoption.StoredScopeMismatch -> null
    CachedScopeLeaseAdoption.ActiveScopeConflict ->
        throw IllegalStateException("Another verified workspace became active during alarm recovery")
}

/**
 * A lineage or lease change after adoption is a race, not proof that no
 * workspace owns the alarm ledger. Throwing routes receivers through their
 * retry/preserve path instead of allowing stale recovery to cancel a newly
 * activated workspace's alarms.
 */
internal fun requireOperationalAlarmOwnershipAfterAdoption(
    tokenLineageStillOwned: Boolean,
    exactLeaseStillOwned: Boolean,
) {
    if (!tokenLineageStillOwned || !exactLeaseStillOwned) {
        throw IllegalStateException("Verified workspace changed during alarm recovery")
    }
}

internal object OperationalAlarmRuntime {

    /** True only when the process' active Room lease still belongs to its encrypted session. */
    fun hasActiveOwnedScope(context: Context): Boolean {
        val app = context.applicationContext as? DCompanyApp ?: return false
        val session = app.tokens.refreshLease() ?: return false
        val access = app.tokens.currentAccessFor(session) ?: return false
        val profile = app.shiftCache.profile.value ?: return false
        val expected = cachedOperationalAlarmScope(
            accessToken = access,
            profile = profile,
            persistedTerminalId = app.terminalStore.terminalId(),
        ) ?: return false
        return app.cacheIsolation.currentLease()?.scope == expected &&
            app.tokens.currentAccessFor(session) != null
    }

    /**
     * Cold receiver processes do not create SessionViewModel, so reactivate an
     * already validated cached scope locally. This can retain an exact marker;
     * it can never purge, switch, or invent a workspace.
     */
    suspend fun ensureActiveOwnedScope(
        context: Context,
        retryFailedStartup: Boolean = false,
    ): Boolean {
        val app = context.applicationContext as? DCompanyApp ?: return false
        if (
            app.awaitPersistedStartupState(
                retryFailed = retryFailedStartup,
            ) !is PersistedStartupStateResult.Ready
        ) {
            // "false" means no valid owner and makes receivers cancel every
            // alarm. A restoration timeout is unknown authority, so route it
            // through the receiver's bounded retry path instead.
            throw IllegalStateException("Persisted startup authority is unavailable")
        }
        val session = app.tokens.refreshLease() ?: return false
        val access = app.tokens.currentAccessFor(session) ?: return false
        val profile = app.shiftCache.profile.value ?: return false
        val expected = cachedOperationalAlarmScope(
            accessToken = access,
            profile = profile,
            persistedTerminalId = app.terminalStore.terminalId(),
        ) ?: return false

        // This atomic adoption is deliberately different from foreground
        // activation: a stale receiver for A may borrow an existing exact A
        // lease, but it can never replace a newly activated B lease.
        val adoption = operationalAlarmAdoptionOrNull(
            app.cacheIsolation.adoptCachedOnlyIfInactive(expected),
        ) ?: return false
        val adoptedLease = adoption.adoptedLease

        // Sign-out/new-login can race the disk work above. Revoke the lease we
        // actually adopted if its exact token lineage no longer exists. Never
        // revoke an exact lease that was already owned by the foreground.
        val tokenLineageStillOwned = app.tokens.currentAccessFor(session) != null
        if (!tokenLineageStillOwned) {
            adoptedLease?.let { app.cacheIsolation.deactivateIfCurrent(it) }
        }
        requireOperationalAlarmOwnershipAfterAdoption(
            tokenLineageStillOwned = tokenLineageStillOwned,
            exactLeaseStillOwned = app.cacheIsolation.currentLease() == adoption.lease,
        )
        return true
    }
}

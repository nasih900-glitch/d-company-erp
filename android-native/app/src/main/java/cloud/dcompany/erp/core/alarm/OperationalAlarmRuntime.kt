package cloud.dcompany.erp.core.alarm

import android.content.Context
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
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
    suspend fun ensureActiveOwnedScope(context: Context): Boolean {
        val app = context.applicationContext as? DCompanyApp ?: return false
        val session = app.tokens.refreshLease() ?: return false
        val access = app.tokens.currentAccessFor(session) ?: return false
        val profile = app.shiftCache.profile.value ?: return false
        val expected = cachedOperationalAlarmScope(
            accessToken = access,
            profile = profile,
            persistedTerminalId = app.terminalStore.terminalId(),
        ) ?: return false

        val active = app.cacheIsolation.currentLease()
        if (active != null && active.scope != expected) return false
        if (active == null) {
            val activated = runCatching { app.cacheIsolation.activateCached(expected) }.isSuccess
            if (!activated) return false
        }

        // Sign-out/new-login can race the disk work above. Revoke the lease we
        // just adopted if its exact token lineage no longer exists.
        if (app.tokens.currentAccessFor(session) == null) {
            app.cacheIsolation.deactivate()
            return false
        }
        return app.cacheIsolation.currentLease()?.scope == expected
    }
}

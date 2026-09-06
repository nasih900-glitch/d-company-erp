package cloud.dcompany.erp.core.remote

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RemoteUiCommandResult(
    val succeeded: Boolean,
    val reasonCode: String? = null,
)

internal data class RemoteUiRouteSnapshot(
    val key: String?,
    val revision: Long,
)

internal data class RemoteUiSemanticAdmission(
    val route: RemoteUiRouteSnapshot? = null,
    val reasonCode: String? = null,
)

internal class RemoteUiCommandHost(
    val currentRouteKey: () -> String?,
    val availableModules: () -> Set<RemoteErpModule>,
    val semanticActionsAllowed: () -> Boolean = { true },
    val navigate: (RemoteErpModule) -> Unit,
    val refreshCurrent: () -> Boolean,
)

/** Activity/Compose state stays out of the process coordinator and is never retained after disposal. */
internal class RemoteUiCommandGateway {
    private val activeHost = AtomicReference<RemoteUiCommandHost?>(null)
    private val routeLock = Any()
    private var lastObservedHost: RemoteUiCommandHost? = null
    private var lastObservedKey: String? = null
    private var routeRevision = 0L

    fun attach(host: RemoteUiCommandHost) {
        activeHost.set(host)
        synchronized(routeLock) {
            lastObservedHost = host
            lastObservedKey = null
            routeRevision += 1L
        }
    }

    fun detach(host: RemoteUiCommandHost) {
        if (activeHost.compareAndSet(host, null)) {
            synchronized(routeLock) {
                lastObservedHost = null
                lastObservedKey = null
                routeRevision += 1L
            }
        }
    }

    fun hasVisibleHost(): Boolean = activeHost.get() != null

    /** Called before any local or remote navigation mutates the visible route. */
    fun markRouteTransition() = synchronized(routeLock) {
        routeRevision += 1L
    }

    /** Compose reports every committed destination, including local staff navigation. */
    fun reportVisibleRoute(host: RemoteUiCommandHost, routeKey: String?) = synchronized(routeLock) {
        if (activeHost.get() !== host) return@synchronized
        observeRouteLocked(host, routeKey)
    }

    suspend fun routeSnapshot(): RemoteUiRouteSnapshot = withContext(Dispatchers.Main.immediate) {
        val host = activeHost.get()
        val key = host?.currentRouteKey?.invoke()
        synchronized(routeLock) {
            observeRouteLocked(host, key)
            RemoteUiRouteSnapshot(key = key, revision = routeRevision)
        }
    }

    suspend fun routeKey(): String? = routeSnapshot().key

    /** A main-thread snapshot proving the same host gate used by navigate/refresh. */
    suspend fun semanticAdmission(
        executionStillAllowed: () -> Boolean,
    ): RemoteUiSemanticAdmission = withContext(Dispatchers.Main.immediate) {
        val host = activeHost.get()
            ?: return@withContext RemoteUiSemanticAdmission(reasonCode = "not_in_foreground")
        if (!executionStillAllowed()) {
            return@withContext RemoteUiSemanticAdmission(reasonCode = "session_inactive")
        }
        if (!host.semanticActionsAllowed()) {
            return@withContext RemoteUiSemanticAdmission(reasonCode = "permission_denied")
        }
        val key = host.currentRouteKey()
        val route = synchronized(routeLock) {
            observeRouteLocked(host, key)
            RemoteUiRouteSnapshot(key, routeRevision)
        }
        RemoteUiSemanticAdmission(route = route)
    }

    suspend fun navigate(
        module: RemoteErpModule,
        executionStillAllowed: () -> Boolean,
    ): RemoteUiCommandResult =
        withContext(Dispatchers.Main.immediate) {
            val host = activeHost.get()
                ?: return@withContext RemoteUiCommandResult(false, "not_in_foreground")
            if (!executionStillAllowed()) {
                return@withContext RemoteUiCommandResult(false, "session_inactive")
            }
            if (!host.semanticActionsAllowed()) {
                return@withContext RemoteUiCommandResult(false, "permission_denied")
            }
            if (module !in host.availableModules()) {
                return@withContext RemoteUiCommandResult(false, "permission_denied")
            }
            host.navigate(module)
            RemoteUiCommandResult(true)
        }

    suspend fun refreshCurrent(
        executionStillAllowed: () -> Boolean,
    ): RemoteUiCommandResult = withContext(Dispatchers.Main.immediate) {
        val host = activeHost.get()
            ?: return@withContext RemoteUiCommandResult(false, "not_in_foreground")
        if (!executionStillAllowed()) {
            return@withContext RemoteUiCommandResult(false, "session_inactive")
        }
        if (!host.semanticActionsAllowed()) {
            return@withContext RemoteUiCommandResult(false, "permission_denied")
        }
        if (host.refreshCurrent()) {
            RemoteUiCommandResult(true)
        } else {
            RemoteUiCommandResult(false, "module_unavailable")
        }
    }

    private fun observeRouteLocked(host: RemoteUiCommandHost?, key: String?) {
        if (lastObservedHost !== host || lastObservedKey != key) {
            lastObservedHost = host
            lastObservedKey = key
            routeRevision += 1L
        }
    }
}

internal fun remoteUiAdmissionRemainedStable(
    before: RemoteUiRouteSnapshot,
    after: RemoteUiRouteSnapshot,
): Boolean = before.key == "help" &&
    after.key == before.key &&
    after.revision == before.revision

internal fun remotePrivacyAdmissionRemainedStable(
    before: RemotePrivacySnapshot,
    after: RemotePrivacySnapshot,
): Boolean = !before.blocked &&
    !after.blocked &&
    after.revision == before.revision

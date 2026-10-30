package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException

internal sealed interface ReportsLoadResult<out T> {
    data class Fresh<T>(val value: T, val fetchedAtMillis: Long, val cacheStored: Boolean) : ReportsLoadResult<T>
    data class Offline<T>(val cached: CachedReportsValue<T>?) : ReportsLoadResult<T>
    data class Failed<T>(val cached: CachedReportsValue<T>?, val error: String) : ReportsLoadResult<T>
    data class Denied(val deletionSucceeded: Boolean) : ReportsLoadResult<Nothing>
    data object Stale : ReportsLoadResult<Nothing>
}

/**
 * Shared, testable surface orchestration. It owns no UI state. The caller's
 * [attemptIsCurrent] includes request serial, exact lease and the permission
 * for this surface, so each suspension boundary fails closed.
 */
internal class ReportsLoadExecutor(
    private val cacheIsolation: CacheIsolationCoordinator,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun <T> execute(
        lease: CacheScopeLease,
        online: Boolean,
        attemptIsCurrent: () -> Boolean,
        cacheAdmissionAuthorized: () -> Boolean,
        loadCached: suspend () -> CachedReportsValue<T>?,
        onCached: suspend (CachedReportsValue<T>) -> Unit,
        fetch: suspend () -> T,
        store: suspend (T) -> Boolean,
        revokeCacheAdmission: () -> Unit,
        deleteSurfaceCache: suspend () -> Boolean,
        freshFetch: (suspend (() -> Boolean) -> T?)? = null,
    ): ReportsLoadResult<T> {
        fun current(): Boolean = attemptIsCurrent() && cacheIsolation.currentLease() == lease
        if (!current()) return ReportsLoadResult.Stale
        val cached = try {
            if (cacheAdmissionAuthorized()) loadCached() else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (!current()) return ReportsLoadResult.Stale
        if (cached != null) {
            val committed = cacheIsolation.commitIfCurrent(lease) {
                if (attemptIsCurrent()) onCached(cached)
            }
            if (!committed || !current()) return ReportsLoadResult.Stale
        }
        if (!online && freshFetch == null) return ReportsLoadResult.Offline(cached)
        if (!current()) return ReportsLoadResult.Stale

        return try {
            val value = if (freshFetch != null) {
                freshFetch(::current) ?: return ReportsLoadResult.Stale
            } else fetch()
            if (!current()) return ReportsLoadResult.Stale
            var stored = false
            val committed = cacheIsolation.commitIfCurrent(lease) {
                if (attemptIsCurrent()) stored = store(value)
            }
            if (!committed || !current()) ReportsLoadResult.Stale
            else ReportsLoadResult.Fresh(value, nowMillis(), stored)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (!current()) return ReportsLoadResult.Stale
            if (error.status == 401 || error.status == 403) {
                var deleted = false
                val committed = cacheIsolation.commitIfCurrent(lease) {
                    if (attemptIsCurrent()) {
                        revokeCacheAdmission()
                        deleted = deleteSurfaceCache()
                    }
                }
                if (!committed || !current()) ReportsLoadResult.Stale
                else ReportsLoadResult.Denied(deleted)
            } else {
                ReportsLoadResult.Failed(cached, reportLoadError(error))
            }
        } catch (error: Exception) {
            if (!current()) ReportsLoadResult.Stale
            else ReportsLoadResult.Failed(cached, reportLoadError(error))
        }
    }
}

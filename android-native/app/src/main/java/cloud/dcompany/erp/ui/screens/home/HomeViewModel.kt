package cloud.dcompany.erp.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.ManualBackendOperation
import cloud.dcompany.erp.core.net.ManualBackendRequestAdmission
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class HomeUiState(
    val lease: CacheScopeLease? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val aggregate: HomeAggregateSnapshot? = null,
    val fetchedAtMillis: Long? = null,
    val activity: List<HomeActivity> = emptyList(),
    val activityCapabilities: HomeActivityCapabilities? = null,
    val stale: Boolean = false,
    val denied: Boolean = false,
)

internal sealed interface HomeLoadResult {
    data class Fresh(
        val payload: ValidatedHomePayload,
        val fetchedAtMillis: Long,
        val cacheStored: Boolean,
    ) : HomeLoadResult
    data class Offline(val cached: CachedHomeAggregate?) : HomeLoadResult
    data class Failed(val cached: CachedHomeAggregate?, val error: String) : HomeLoadResult
    data class Denied(
        val key: String?,
        val deletionSucceeded: Boolean,
    ) : HomeLoadResult
    data object StaleLease : HomeLoadResult
}

/**
 * One execution path shared by production and race tests. It never owns UI state;
 * every observable publication remains the ViewModel's lease/serial decision.
 */
internal class HomeLoadExecutor(
    private val repository: HomeRepository,
    private val cacheIsolation: CacheIsolationCoordinator,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun execute(
        lease: CacheScopeLease,
        online: Boolean,
        attemptIsCurrent: () -> Boolean,
        cacheSuppressed: (String) -> Boolean,
        onCached: suspend (CachedHomeAggregate) -> Unit,
        cacheAdmissionAuthorized: () -> Boolean = { false },
        revokeCacheAdmission: () -> Unit = {},
        freshFetch: (suspend (() -> Boolean) -> ValidatedHomePayload?)? = null,
    ): HomeLoadResult {
        fun current(): Boolean = attemptIsCurrent() && cacheIsolation.currentLease() == lease
        if (!current()) return HomeLoadResult.StaleLease
        try {
            repository.validateLease(lease)
        } catch (error: Exception) {
            return HomeLoadResult.Failed(null, homeError(error))
        }
        val cached = try {
            if (cacheAdmissionAuthorized()) {
                repository.latestCached(lease)?.takeUnless { cacheSuppressed(it.key) }
            } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (!current()) return HomeLoadResult.StaleLease
        if (cached != null) {
            val published = cacheIsolation.commitIfCurrent(lease) {
                if (attemptIsCurrent()) onCached(cached)
            }
            if (!published || !current()) return HomeLoadResult.StaleLease
        }
        if (!online && freshFetch == null) return HomeLoadResult.Offline(cached)
        if (!current()) return HomeLoadResult.StaleLease

        return try {
            val payload = if (freshFetch != null) {
                freshFetch(::current) ?: return HomeLoadResult.StaleLease
            } else {
                repository.fetch(lease)
            }
            if (!current()) return HomeLoadResult.StaleLease
            var stored = false
            val committed = cacheIsolation.commitIfCurrent(lease) {
                if (!attemptIsCurrent()) return@commitIfCurrent
                stored = repository.store(lease, payload.aggregate)
            }
            if (!committed || !current()) HomeLoadResult.StaleLease
            else HomeLoadResult.Fresh(payload, clock.millis(), stored)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (!current()) return HomeLoadResult.StaleLease
            if (error.status == 401 || error.status == 403) {
                val key = cached?.key
                var deleted = false
                val committed = cacheIsolation.commitIfCurrent(lease) {
                    if (attemptIsCurrent()) {
                        revokeCacheAdmission()
                        deleted = repository.deleteScope(lease)
                    }
                }
                if (!committed || !current()) HomeLoadResult.StaleLease
                else HomeLoadResult.Denied(
                    key = key,
                    deletionSucceeded = deleted,
                )
            } else {
                HomeLoadResult.Failed(cached, homeError(error))
            }
        } catch (error: Exception) {
            if (!current()) HomeLoadResult.StaleLease
            else HomeLoadResult.Failed(cached, homeError(error))
        }
    }
}

internal class HomeViewModel : ViewModel() {
    private val app = DCompanyApp.instance
    private val cacheIsolation = app.cacheIsolation
    private val repository = HomeRepository(
        api = ApiClient.create<HomeApi>(),
        cache = RoomHomeCacheStore(app.db.reportSnapshotDao()),
    )
    private val executor = HomeLoadExecutor(repository, cacheIsolation)
    private val manualAdmission = ManualBackendRequestAdmission(
        online = { app.connectivity.online.value },
    )
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    val activeLease: StateFlow<CacheScopeLease?> = cacheIsolation.activeLeaseFlow

    fun currentLease(): CacheScopeLease? = cacheIsolation.currentLease()

    private var requestJob: Job? = null
    private var requestSerial = 0L
    private var observedLease: CacheScopeLease? = null
    private val deniedScopes = mutableSetOf<CacheScope>()
    private val deniedCacheKeys = mutableSetOf<Pair<CacheScope, String>>()
    // Intentionally process-local and exact-generation. A cold process must
    // revalidate Home online before any persisted aggregate can be displayed.
    private val cacheAuthorizedLeases = mutableSetOf<CacheScopeLease>()

    init {
        viewModelScope.launch {
            combine(cacheIsolation.activeLeaseFlow, app.connectivity.online) { lease, online -> lease to online }
                .distinctUntilChanged()
                .collect { (lease, online) ->
                    val leaseChanged = lease != observedLease
                    observedLease = lease
                    if (leaseChanged) {
                        requestSerial += 1
                        requestJob?.cancel()
                        deniedScopes.removeAll { it != lease?.scope }
                        deniedCacheKeys.removeAll { it.first != lease?.scope }
                        cacheAuthorizedLeases.removeAll { it != lease }
                        _state.value = HomeUiState(lease = lease, loading = lease != null)
                    } else if (!online) {
                        _state.value = _state.value.copy(
                            activity = emptyList(),
                            activityCapabilities = null,
                            stale = _state.value.aggregate != null,
                        )
                    }
                    if (lease != null) load(lease, online)
                }
        }
    }

    fun retry() {
        val lease = cacheIsolation.currentLease() ?: run {
            _state.value = HomeUiState(loading = false, error = "The current workspace is unavailable.")
            return
        }
        val operationOnline = app.connectivity.online.value
        load(lease, operationOnline, explicitRefresh = true)
    }

    private fun load(
        lease: CacheScopeLease,
        online: Boolean,
        explicitRefresh: Boolean = false,
    ) {
        val serial = ++requestSerial
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            fun current(): Boolean = serial == requestSerial && cacheIsolation.currentLease() == lease
            val admitted = cacheIsolation.commitIfCurrent(lease) {
                if (!current()) return@commitIfCurrent
                _state.value = _state.value.copy(
                    loading = true,
                    error = null,
                    activity = if (online) _state.value.activity else emptyList(),
                    activityCapabilities = if (online) _state.value.activityCapabilities else null,
                )
            }
            if (!admitted || !current()) return@launch
            val result = executor.execute(
                lease = lease,
                online = online,
                attemptIsCurrent = ::current,
                cacheSuppressed = { key -> lease.scope in deniedScopes || (lease.scope to key) in deniedCacheKeys },
                onCached = { cached ->
                    if (current()) {
                        _state.value = _state.value.copy(
                            aggregate = cached.aggregate,
                            fetchedAtMillis = cached.fetchedAtMillis,
                            stale = true,
                        )
                    }
                },
                cacheAdmissionAuthorized = { lease in cacheAuthorizedLeases },
                revokeCacheAdmission = { cacheAuthorizedLeases -= lease },
                freshFetch = if (explicitRefresh) {
                    { isCurrent ->
                        fetchHomeForExplicitRefresh(
                            lease = lease,
                            isCurrent = isCurrent,
                            repository = repository,
                            admission = manualAdmission,
                            directApi = { ApiClient.createManualDirectApi(HomeApi::class.java) },
                        )
                    }
                } else null,
            )
            if (!current()) return@launch
            if (result is HomeLoadResult.StaleLease) return@launch
            cacheIsolation.commitIfCurrent(lease) {
                if (!current()) return@commitIfCurrent
                _state.value = when (result) {
                is HomeLoadResult.Fresh -> {
                    deniedScopes.remove(lease.scope)
                    deniedCacheKeys.remove(lease.scope to homeCacheKey(result.payload.aggregate.branchId, result.payload.aggregate.businessDate))
                    if (result.cacheStored) cacheAuthorizedLeases += lease else cacheAuthorizedLeases -= lease
                    freshHomeState(lease, result)
                }
                is HomeLoadResult.Offline -> retainedHomeState(
                    lease = lease,
                    previous = _state.value,
                    cached = result.cached,
                    error = when {
                        result.cached != null -> "Offline · showing the saved aggregate only."
                        _state.value.aggregate != null -> "Offline · showing the last verified Home overview."
                        else -> "Connect to load today's Home overview."
                    },
                )
                is HomeLoadResult.Failed -> retainedHomeState(
                    lease = lease,
                    previous = _state.value,
                    cached = result.cached,
                    error = result.error,
                )
                is HomeLoadResult.Denied -> {
                    cacheAuthorizedLeases -= lease
                    deniedScopes += lease.scope
                    result.key?.let { deniedCacheKeys += lease.scope to it }
                    HomeUiState(
                        lease = lease,
                        loading = false,
                        error = "Home overview access is unavailable for this account.",
                        denied = true,
                    )
                }
                HomeLoadResult.StaleLease -> error("Stale Home result passed publication guard")
                }
            }
        }
    }
}

/**
 * Executes only the user-owned Home refresh. The direct API is created after a
 * fresh readiness proof and a second exact-lease/request check, then discarded.
 */
internal suspend fun fetchHomeForExplicitRefresh(
    lease: CacheScopeLease,
    isCurrent: () -> Boolean,
    repository: HomeRepository,
    admission: ManualBackendRequestAdmission,
    directApi: () -> HomeApi,
): ValidatedHomePayload? {
    if (!isCurrent()) return null
    return admission.execute(
        operation = ManualBackendOperation.MANUAL_READ,
        normal = {
            if (!isCurrent()) null else repository.fetch(lease)
        },
        direct = {
            if (!isCurrent()) null else repository.fetch(lease, directApi())
        },
    )
}

internal fun freshHomeState(
    lease: CacheScopeLease,
    fresh: HomeLoadResult.Fresh,
): HomeUiState = HomeUiState(
    lease = lease,
    loading = false,
    aggregate = fresh.payload.aggregate,
    fetchedAtMillis = fresh.fetchedAtMillis,
    activity = fresh.payload.activity,
    activityCapabilities = fresh.payload.capabilities,
    stale = false,
)

internal fun retainedHomeState(
    lease: CacheScopeLease,
    previous: HomeUiState,
    cached: CachedHomeAggregate?,
    error: String,
): HomeUiState {
    val exactPrevious = previous.takeIf { it.lease == lease }
    val retainedAggregate = cached?.aggregate ?: exactPrevious?.aggregate
    val retainedFetchedAt = cached?.fetchedAtMillis ?: exactPrevious?.fetchedAtMillis
    return HomeUiState(
        lease = lease,
        loading = false,
        error = error,
        aggregate = retainedAggregate,
        fetchedAtMillis = retainedFetchedAt,
        stale = retainedAggregate != null,
    )
}

internal fun visibleHomeState(
    state: HomeUiState,
    signaledLease: CacheScopeLease?,
    currentLease: CacheScopeLease?,
): HomeUiState = if (state.lease == signaledLease && signaledLease == currentLease) {
    state
} else {
    HomeUiState(lease = currentLease, loading = currentLease != null)
}

private fun homeError(error: Throwable): String = when (error) {
    is HomeContractException -> "The Home response could not be verified. Refresh or ask support."
    is ApiException -> error.message?.takeIf(String::isNotBlank) ?: "Could not load the Home overview."
    else -> "Could not load the Home overview. Check the connection and try again."
}

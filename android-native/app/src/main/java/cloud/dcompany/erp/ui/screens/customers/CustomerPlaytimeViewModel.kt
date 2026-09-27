package cloud.dcompany.erp.ui.screens.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal const val PLAYTIME_PAGE_SIZE = 25

data class CustomerPlaytimeUiState(
    val items: List<PlaytimeLeaderboardItem> = emptyList(),
    val total: Int? = null,
    val program: PlaytimeProgramDraft? = null,
    val nextPage: Int = 1,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = total != null && items.size < total
}

/**
 * Keep the server's rank order; the local directory is neither complete nor
 * ordered by playtime. Refuse inconsistent pages instead of showing duplicate
 * ranks when the leaderboard changes during pagination.
 */
internal fun appendPlaytimePage(
    current: CustomerPlaytimeUiState,
    requestedPage: Int,
    response: PlaytimeLeaderboard,
): CustomerPlaytimeUiState {
    check(response.page == requestedPage && response.limit == PLAYTIME_PAGE_SIZE) {
        "The leaderboard page did not match the request. Refresh and try again."
    }
    check(requestedPage == current.nextPage && response.total >= 0 &&
        (requestedPage == 1 || response.total == current.total)) {
        "The leaderboard changed while loading. Refresh and try again."
    }
    val previous = if (requestedPage == 1) emptyList() else current.items
    val next = response.items
    check(next.size <= PLAYTIME_PAGE_SIZE && previous.size + next.size <= response.total) {
        "The leaderboard changed while loading. Refresh and try again."
    }
    check(next.isNotEmpty() || previous.size == response.total) {
        "The leaderboard changed while loading. Refresh and try again."
    }
    val seen = previous.mapTo(mutableSetOf(), PlaytimeLeaderboardItem::customerId)
    var lastRank = previous.lastOrNull()?.rank ?: 0
    next.forEach { item ->
        check(item.rank > lastRank && seen.add(item.customerId)) {
            "The leaderboard changed while loading. Refresh and try again."
        }
        lastRank = item.rank
    }
    return CustomerPlaytimeUiState(
        items = previous + next,
        total = response.total,
        program = response.program,
        nextPage = requestedPage + 1,
    )
}

/**
 * Online-only, bounded leaderboard. It deliberately has no Room cache: the
 * saved customer directory remains available offline, while ranks and draft
 * reward projections cannot be mistaken for current server facts.
 */
class CustomerPlaytimeViewModel : ViewModel() {
    private val appCtx = DCompanyApp.instance
    private val api by lazy { ApiClient.create<CustomersApi>() }
    private val mutableState = MutableStateFlow(CustomerPlaytimeUiState())
    val state: StateFlow<CustomerPlaytimeUiState> = mutableState.asStateFlow()
    private var requestJob: Job? = null
    private var generation = 0L
    private var loadedLease: CacheScopeLease? = null

    fun stateForCurrentScope(snapshot: CustomerPlaytimeUiState): CustomerPlaytimeUiState =
        if (loadedLease == appCtx.cacheIsolation.currentLease()) snapshot
        else CustomerPlaytimeUiState(error = "Refresh to load this account's playtime ranks.")

    /** The ViewModel outlives this destination; do not retain another account's ranks. */
    fun clear() {
        generation++
        requestJob?.cancel()
        requestJob = null
        loadedLease = null
        mutableState.value = CustomerPlaytimeUiState()
    }

    fun clearIfScopeChanged() {
        if (loadedLease != null && loadedLease != appCtx.cacheIsolation.currentLease()) clear()
    }

    fun refresh() {
        generation++
        requestJob?.cancel()
        val lease = appCtx.cacheIsolation.currentLease()
        loadedLease = lease
        when {
            lease == null -> {
                mutableState.value = CustomerPlaytimeUiState(error = "Sign in to load recorded play hours.")
                return
            }
            !appCtx.connectivity.online.value -> {
                mutableState.value = CustomerPlaytimeUiState(error = "Reconnect to load recorded play hours.")
                return
            }
        }
        mutableState.value = CustomerPlaytimeUiState(loading = true)
        requestJob = viewModelScope.launch { fetchPage(requireNotNull(lease), 1, generation) }
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || !current.canLoadMore) return
        val lease = appCtx.cacheIsolation.currentLease()
        if (lease == null || lease != loadedLease) {
            refresh()
            return
        }
        if (!appCtx.connectivity.online.value) {
            mutableState.value = current.copy(error = "Reconnect to load the remaining ranks.")
            return
        }
        mutableState.value = current.copy(loading = true, error = null)
        requestJob = viewModelScope.launch { fetchPage(lease, current.nextPage, generation) }
    }

    private suspend fun fetchPage(lease: CacheScopeLease, page: Int, requestGeneration: Long) {
        try {
            val response = api.playtimeLeaderboard(page = page, limit = PLAYTIME_PAGE_SIZE)
            appCtx.cacheIsolation.commitIfCurrent(lease) {
                if (generation == requestGeneration) {
                    mutableState.value = appendPlaytimePage(mutableState.value, page, response)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            appCtx.cacheIsolation.commitIfCurrent(lease) {
                if (generation == requestGeneration) {
                    val authDenied = error is HttpException && error.code() in setOf(401, 403)
                    val message = when {
                        error is HttpException && error.code() == 401 ->
                            "Sign in again to view playtime ranks."
                        error is HttpException && error.code() == 403 ->
                            "This account cannot view playtime ranks. The customer directory remains available."
                        error is HttpException ->
                            "Could not load recorded play hours (server ${error.code()}). Retry."
                        else -> error.message?.takeIf(String::isNotBlank)
                            ?: "Could not load recorded play hours. Retry."
                    }
                    // A role/session may be revoked during pagination. Do not
                    // leave earlier ranks visible after an explicit denial.
                    mutableState.value = if (authDenied) CustomerPlaytimeUiState(error = message)
                    else mutableState.value.copy(loading = false, error = message)
                }
            }
        }
    }
}

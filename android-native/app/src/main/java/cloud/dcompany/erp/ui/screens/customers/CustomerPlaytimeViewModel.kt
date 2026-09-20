package cloud.dcompany.erp.ui.screens.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CustomerPlaytimeUiState(
    val loading: Boolean = false,
    val leaderboard: PlaytimeLeaderboard? = null,
    val error: String? = null,
)

/**
 * Online-only draft projection. It deliberately has no Room cache: presenting
 * stale reward estimates as current would be misleading while the proposal is
 * still being discussed and no reward ledger exists.
 */
class CustomerPlaytimeViewModel : ViewModel() {
    private val appCtx = DCompanyApp.instance
    private val api by lazy { ApiClient.create<CustomersApi>() }
    private val mutableState = MutableStateFlow(CustomerPlaytimeUiState())
    val state: StateFlow<CustomerPlaytimeUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value.loading) return
        if (!appCtx.connectivity.online.value) {
            mutableState.value = CustomerPlaytimeUiState(
                error = "Reconnect to load recorded play hours.",
            )
            return
        }
        val lease = appCtx.cacheIsolation.currentLease() ?: return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val response = api.playtimeLeaderboard()
                appCtx.cacheIsolation.commitIfCurrent(lease) {
                    mutableState.value = CustomerPlaytimeUiState(leaderboard = response)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appCtx.cacheIsolation.commitIfCurrent(lease) {
                    mutableState.value = CustomerPlaytimeUiState(
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "Could not load recorded play hours.",
                    )
                }
            }
        }
    }
}

package cloud.dcompany.erp.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FinanceUiState(
    val loading: Boolean = true,
    /** The server's own message, never an HTTP code. */
    val error: String? = null,
    val pl: ProfitAndLoss? = null,
    val metrics: BusinessMetrics? = null,
    val distributable: DistributableProfit? = null,
    val expenses: List<Expense> = emptyList(),
    val partners: List<Partner> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
) {
    /** True once a load has succeeded; the five figures always arrive together. */
    val loaded: Boolean get() = pl != null

    val expenseTotalMinor: Long get() = expenses.sumOf { it.amountMinor }

    /**
     * Nothing booked this period. Distinguished from "not loaded yet" so the
     * screen can say *why* it is showing zeros instead of leaving an owner to
     * guess whether the tablet failed or the month genuinely started quiet.
     */
    val periodIdle: Boolean
        get() = pl != null &&
            pl.revenueMinor == 0L && pl.cogsMinor == 0L && pl.expensesMinor == 0L

    fun categoryName(id: String): String = categoryNames[id] ?: "—"
}

/**
 * One shot, six parallel GETs, one screen. Read-only: this ViewModel has no
 * write path at all, so there is no idempotency key to manage here.
 */
class FinanceViewModel : ViewModel() {

    private val api = ApiClient.create<FinanceApi>()

    private val _state = MutableStateFlow(FinanceUiState())
    val state: StateFlow<FinanceUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                coroutineScope {
                    val pl = async { api.profitAndLoss() }
                    val metrics = async { api.metrics() }
                    val distributable = async { api.distributable() }
                    val expenses = async { api.expenses() }
                    val partners = async { api.partners() }
                    // Best effort only. Category names are a label on rows whose
                    // amounts are already correct, so losing them must not blank
                    // out an owner's whole finance screen.
                    val categories = async {
                        try {
                            api.expenseCategories()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }

                    _state.value = FinanceUiState(
                        loading = false,
                        error = null,
                        pl = pl.await(),
                        metrics = metrics.await(),
                        distributable = distributable.await(),
                        expenses = expenses.await(),
                        partners = partners.await(),
                        categoryNames = categories.await().associate { it.id to it.name },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Keep whatever was already on screen: stale-but-labelled beats
                // an empty screen when the wifi drops mid-refresh.
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Could not read the finance figures: ${e.message ?: "unexpected error"}",
                )
            }
        }
    }
}

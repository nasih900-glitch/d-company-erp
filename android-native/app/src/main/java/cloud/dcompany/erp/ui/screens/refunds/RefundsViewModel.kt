package cloud.dcompany.erp.ui.screens.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** The reasons an owner actually gives; free text goes in the note. */
val REFUND_REASONS = listOf(
    "customer_unhappy" to "Customer unhappy",
    "wrong_item" to "Wrong item",
    "order_cancelled" to "Order cancelled",
    "billing_error" to "Billing error",
    "other" to "Other",
)

data class RefundsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val orders: List<Order> = emptyList(),
    val query: String = "",
    val selected: Order? = null,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val visible: List<Order>
        get() {
            val q = query.trim().lowercase()
            val paid = orders.filter { it.status == "paid" }
            return if (q.isEmpty()) paid
            else paid.filter { (it.invoiceNo ?: "").lowercase().contains(q) }
        }
}

class RefundsViewModel : ViewModel() {

    private val api = ApiClient.create<RefundsApi>()

    private val _state = MutableStateFlow(RefundsUiState())
    val state: StateFlow<RefundsUiState> = _state.asStateFlow()

    /**
     * One key per refund attempt, held until that attempt resolves. A refund
     * moves real money out; regenerating the key on retry is how one refund
     * becomes two.
     */
    private var refundKey: String = UUID.randomUUID().toString()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loading = false, orders = api.orders())
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load orders.",
                )
            }
        }
    }

    fun search(q: String) { _state.value = _state.value.copy(query = q) }

    fun select(order: Order?) { _state.value = _state.value.copy(selected = order) }

    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    /**
     * The server is the authority on what is refundable — it checks the paid
     * balance minus anything already refunded and refuses with its own words.
     * Those words are shown verbatim rather than replaced with a generic
     * failure, because "refund exceeds paid balance" tells an owner what to do
     * and "something went wrong" does not.
     */
    fun refund(order: Order, amountMinor: Long, reasonCode: String, note: String?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val result = api.refund(
                    order.id,
                    RefundBody(
                        reasonCode = reasonCode,
                        amountMinor = amountMinor,
                        mode = "cash",
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                    refundKey,
                )
                refundKey = UUID.randomUUID().toString()
                _state.value = _state.value.copy(
                    busy = false,
                    selected = null,
                    notice = "Refunded. Settled as ${result.settlementMethod ?: "cash"}.",
                )
                load()
            } catch (e: Exception) {
                // The key is deliberately NOT rotated here: if the refund did
                // commit and we simply never heard back, replaying the same key
                // returns that refund instead of paying the customer twice.
                _state.value = _state.value.copy(
                    busy = false,
                    notice = e.message ?: "The refund could not be completed.",
                )
            }
        }
    }
}

package cloud.dcompany.erp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MenuCategory
import cloud.dcompany.erp.core.net.MenuItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CartLine(val item: MenuItem, val qty: Int)

data class PosUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val categories: List<MenuCategory> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    val selectedCategoryId: String? = null,
    val cart: List<CartLine> = emptyList(),
) {
    val visibleItems: List<MenuItem>
        get() = if (selectedCategoryId == null) items
            else items.filter { it.categoryId == selectedCategoryId }

    /**
     * A cart-side estimate only. The server does the canonical pricing —
     * tax, membership discounts and rounding — when the bill is prepared, and
     * that figure is what may be collected.
     */
    val estimateMinor: Long
        get() = cart.sumOf { it.item.basePriceMinor * it.qty }

    val cartCount: Int get() = cart.sumOf { it.qty }
}

class PosViewModel : ViewModel() {

    private val _state = MutableStateFlow(PosUiState())
    val state: StateFlow<PosUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val categories = ApiClient.api.menuCategories()
                val items = ApiClient.api.menuItems()
                _state.value = _state.value.copy(
                    loading = false,
                    categories = categories.sortedBy { it.sortOrder },
                    items = items.filter { it.isAvailable },
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load the menu.",
                )
            }
        }
    }

    fun add(item: MenuItem) = mutateCart { cart ->
        val existing = cart.indexOfFirst { it.item.id == item.id }
        if (existing >= 0) {
            cart.toMutableList().also { it[existing] = it[existing].copy(qty = it[existing].qty + 1) }
        } else {
            cart + CartLine(item, 1)
        }
    }

    fun remove(item: MenuItem) = mutateCart { cart ->
        val existing = cart.indexOfFirst { it.item.id == item.id }
        if (existing < 0) return@mutateCart cart
        val line = cart[existing]
        if (line.qty <= 1) cart.filterIndexed { i, _ -> i != existing }
        else cart.toMutableList().also { it[existing] = line.copy(qty = line.qty - 1) }
    }

    fun clearCart() = mutateCart { emptyList() }

    fun selectCategory(id: String?) {
        _state.value = _state.value.copy(selectedCategoryId = id)
    }

    private fun mutateCart(block: (List<CartLine>) -> List<CartLine>) {
        _state.value = _state.value.copy(cart = block(_state.value.cart))
    }
}

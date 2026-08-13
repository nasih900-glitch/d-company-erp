package cloud.dcompany.erp.ui.screens.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Draft of the add/edit form. `id == null` means "new customer". */
data class CustomerEditor(
    val id: String? = null,
    val originalPhone: String = "",
    val phone: String = "",
    /**
     * The phone number *is* the loyalty identity, so it stays locked while
     * editing until the user deliberately unlocks it. Editing it by accident
     * moves someone's points onto a number they do not own.
     */
    val phoneUnlocked: Boolean = false,
    val name: String = "",
    val email: String = "",
    val birthday: LocalDate? = null,
    val notes: String = "",
) {
    val isNew: Boolean get() = id == null
    val phoneChanged: Boolean get() = phone.trim() != originalPhone
    /** Backend requires 4..20 characters; catching it here saves a round trip. */
    val phoneValid: Boolean get() = phone.trim().length in 4..20
}

data class CustomersUiState(
    val loading: Boolean = true,
    /** Server's own message, shown verbatim — never "HTTP 422". */
    val error: String? = null,
    val rows: List<Customer> = emptyList(),
    val query: String = "",
    val selectedId: String? = null,
    val editor: CustomerEditor? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val notice: String? = null,
) {
    val selected: Customer? get() = rows.firstOrNull { it.id == selectedId }

    val totalVisits: Long get() = rows.sumOf { it.visitCount.toLong() }
    val totalSpentMinor: Long get() = rows.sumOf { it.totalSpentMinor }
    val totalPoints: Long get() = rows.sumOf { it.loyaltyPoints.toLong() }
    val searching: Boolean get() = query.isNotBlank()
}

class CustomersViewModel : ViewModel() {

    private val api = ApiClient.create<CustomersApi>()

    private val _state = MutableStateFlow(CustomersUiState())
    val state: StateFlow<CustomersUiState> = _state.asStateFlow()

    /** Separate jobs so a reload triggered from inside the debounce cannot cancel itself. */
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    init {
        reload()
    }

    // -------------------------------------------------------------- search

    /**
     * Search runs on the server, not over the loaded page: the list endpoint
     * is capped, so filtering only what was already downloaded would quietly
     * fail to find the 300th customer — exactly the one being looked up at
     * the counter.
     */
    fun search(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reload()
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = "") }
        reload()
    }

    fun retry() {
        searchJob?.cancel()
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val q = _state.value.query.trim().ifBlank { null }
            try {
                val rows = api.list(q = q)
                _state.update { st ->
                    st.copy(
                        loading = false,
                        error = null,
                        rows = rows,
                        // Drop a selection the new result set no longer contains,
                        // so the detail pane can never show a stale record.
                        selectedId = st.selectedId?.takeIf { id -> rows.any { it.id == id } },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.messageForUi("Could not load customers."))
                }
            }
        }
    }

    // ------------------------------------------------------------ selection

    fun select(customer: Customer) {
        _state.update { it.copy(selectedId = customer.id) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedId = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // --------------------------------------------------------------- editor

    fun startCreate() {
        _state.update {
            it.copy(
                // A search for a phone number that found nothing is almost
                // always about to become that customer, so seed the form.
                editor = CustomerEditor(phone = it.query.trim().filter(Char::isDigit)),
                saveError = null,
                notice = null,
            )
        }
    }

    fun startEdit(customer: Customer) {
        _state.update {
            it.copy(
                editor = CustomerEditor(
                    id = customer.id,
                    originalPhone = customer.phone,
                    phone = customer.phone,
                    name = customer.name.orEmpty(),
                    email = customer.email.orEmpty(),
                    birthday = isoDay(customer.birthday),
                    notes = customer.notes.orEmpty(),
                ),
                saveError = null,
                notice = null,
            )
        }
    }

    fun editorChanged(editor: CustomerEditor) {
        _state.update { it.copy(editor = editor) }
    }

    fun cancelEdit() {
        _state.update { it.copy(editor = null, saveError = null) }
    }

    fun save() {
        val editor = _state.value.editor ?: return
        if (_state.value.saving || !editor.phoneValid) return

        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val saved = if (editor.isNew) create(editor) else update(editor)
                _state.update {
                    it.copy(
                        saving = false,
                        editor = null,
                        selectedId = saved.id,
                        notice = noticeFor(editor, saved),
                    )
                }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stay in the dialog with the typed values intact: a rejected
                // save must never cost the user the record they just entered.
                _state.update {
                    it.copy(saving = false, saveError = e.messageForUi("Could not save this customer."))
                }
            }
        }
    }

    private suspend fun create(editor: CustomerEditor): Customer = api.upsert(
        CustomerUpsertBody(
            phone = editor.phone.trim(),
            name = editor.name.trim().ifBlank { null },
            email = editor.email.trim().ifBlank { null },
            birthday = editor.birthday?.asUtcInstantString(),
            notes = editor.notes.trim().ifBlank { null },
        )
    )

    private suspend fun update(editor: CustomerEditor): Customer = api.update(
        id = editor.id!!,
        body = CustomerUpdateBody(
            name = editor.name.trim().ifBlank { null },
            // Only sent when deliberately unlocked and actually different —
            // the server treats a phone change as moving a loyalty identity.
            phone = editor.phone.trim()
                .takeIf { editor.phoneUnlocked && it.isNotBlank() && it != editor.originalPhone },
            email = editor.email.trim().ifBlank { null },
            birthday = editor.birthday?.asUtcInstantString(),
            notes = editor.notes.trim().ifBlank { null },
        ),
    )

    /**
     * POST /customers is an upsert, so "create" can silently land on an
     * existing customer. Visits already on the record prove that happened,
     * and staff need to be told — otherwise they believe the name they just
     * typed was saved, when the server only fills in fields that were blank.
     */
    private fun noticeFor(editor: CustomerEditor, saved: Customer): String = when {
        !editor.isNew -> "Saved."
        saved.visitCount > 0 || saved.lastVisitAt != null ->
            "${saved.phone} was already on file — this is ${saved.displayName}'s existing " +
                "record, with ${saved.loyaltyPoints.grouped()} points. Use Edit to change " +
                "their details."
        else -> "Customer saved."
    }

    private companion object {
        /** Long enough that a typed phone number is one request, not ten. */
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

private fun Throwable.messageForUi(fallback: String): String =
    (this as? ApiException)?.message ?: message?.takeIf { it.isNotBlank() } ?: fallback

private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
    value = block(value)
}

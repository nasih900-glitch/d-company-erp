package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.dcompany.erp.core.net.Terminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.terminalDataStore by preferencesDataStore(name = "dcompany_terminal")

/**
 * Non-secret label for the till whose exact id + branch were validated by the
 * server and committed as this process' cache scope. Persisting the label lets
 * an offline restart identify the physical till to staff; it is never itself
 * authority to attach X-Terminal-Id.
 */
data class ValidatedTerminalDisplay(
    val terminalId: String,
    val terminalName: String,
    val branchId: String,
)

/**
 * Which till this tablet is.
 *
 * Every POS write on the backend refuses without an X-Terminal-Id header
 * ("X-Terminal-Id header required for POS writes", pos/router.py). The first
 * native build sent no such header, so every queued offline sale was refused
 * on sync — the trial run found sales sitting in the outbox that could never
 * be delivered. The id is resolved once from /settings/terminals and cached,
 * because the interceptor that attaches it runs on a background thread and
 * cannot suspend to go and fetch it.
 */
class TerminalStore(private val context: Context) {

    private val key = stringPreferencesKey("terminal_id")
    private val nameKey = stringPreferencesKey("terminal_name")
    private val branchKey = stringPreferencesKey("terminal_branch_id")

    @Volatile private var cached: String? = null
    @Volatile private var persistedDisplay: ValidatedTerminalDisplay? = null
    private val _terminalId = MutableStateFlow<String?>(null)
    private val _activeValidatedTerminal = MutableStateFlow<ValidatedTerminalDisplay?>(null)
    /** Reacts when a later authenticated account is assigned to another till. */
    val terminalIdFlow: StateFlow<String?> = _terminalId.asStateFlow()
    /**
     * Process-local display authority. This is deliberately null after logout,
     * during bootstrap/selection, and on legacy installs that have only an id.
     */
    val activeValidatedTerminal: StateFlow<ValidatedTerminalDisplay?> =
        _activeValidatedTerminal.asStateFlow()

    fun terminalId(): String? = cached

    suspend fun load() {
        val prefs = context.terminalDataStore.data.first()
        cached = prefs[key]?.trim()?.takeIf(String::isNotEmpty)
        persistedDisplay = validatedDisplay(
            terminalId = cached,
            terminalName = prefs[nameKey],
            branchId = prefs[branchKey],
        )
        _terminalId.value = cached
        // Loading a persisted candidate is not runtime activation. SessionViewModel
        // first has to prove the exact cached/server scope before exposing it.
        _activeValidatedTerminal.value = null
    }

    /**
     * Suspending, not runBlocking. The first version blocked the main thread
     * on a DataStore write from inside a coroutine and the value never reached
     * disk — the cache file simply never appeared, so the header was still
     * missing after a restart. ShiftCache had it right; this now matches.
     */
    suspend fun remember(id: String?) {
        context.terminalDataStore.edit {
            if (id == null) {
                it.remove(key)
            } else {
                it[key] = id
            }
            // An id-only write cannot retain a label proven for another id.
            it.remove(nameKey)
            it.remove(branchKey)
        }
        // Publish only after persistence succeeds.
        cached = id
        persistedDisplay = null
        _terminalId.value = id
        _activeValidatedTerminal.value = null
    }

    /**
     * Persist only after SessionViewModel has committed the exact cache scope.
     * All values are harmless display metadata; credentials/device secrets are
     * never written to this DataStore.
     */
    suspend fun rememberValidated(terminal: Terminal) {
        val display = validatedDisplay(
            terminalId = terminal.id,
            terminalName = terminal.name,
            branchId = terminal.branchId,
        ) ?: throw IllegalArgumentException("A validated till must have an id and branch")
        context.terminalDataStore.edit {
            it[key] = display.terminalId
            it[nameKey] = display.terminalName
            it[branchKey] = display.branchId
        }
        cached = display.terminalId
        persistedDisplay = display
        _terminalId.value = display.terminalId
        _activeValidatedTerminal.value = display
    }

    /** Activate an offline label only when the already-validated cache marker matches exactly. */
    fun activateCachedValidated(terminalId: String?, branchId: String?): Boolean {
        val active = persistedDisplay?.takeIf { hasCachedValidated(terminalId, branchId) }
        _activeValidatedTerminal.value = active
        return active != null
    }

    /** Read-only preflight used before a background worker reopens the cache marker. */
    internal fun hasCachedValidated(terminalId: String?, branchId: String?): Boolean =
        persistedDisplay?.let {
            it.terminalId == terminalId?.trim() && it.branchId == branchId?.trim()
        } == true

    fun deactivateValidatedDisplay() {
        _activeValidatedTerminal.value = null
    }

    private fun validatedDisplay(
        terminalId: String?,
        terminalName: String?,
        branchId: String?,
    ): ValidatedTerminalDisplay? {
        val id = terminalId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val branch = branchId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val name = terminalName?.trim()?.takeIf(String::isNotEmpty)
            ?: "Till ${id.take(8)}"
        return ValidatedTerminalDisplay(id, name, branch)
    }
}

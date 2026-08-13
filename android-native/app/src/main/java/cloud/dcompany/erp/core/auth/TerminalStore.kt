package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.terminalDataStore by preferencesDataStore(name = "dcompany_terminal")

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

    @Volatile private var cached: String? = null

    fun terminalId(): String? = cached

    suspend fun load() {
        cached = context.terminalDataStore.data.first()[key]
    }

    /**
     * Suspending, not runBlocking. The first version blocked the main thread
     * on a DataStore write from inside a coroutine and the value never reached
     * disk — the cache file simply never appeared, so the header was still
     * missing after a restart. ShiftCache had it right; this now matches.
     */
    suspend fun remember(id: String?) {
        cached = id
        context.terminalDataStore.edit {
            if (id == null) it.remove(key) else it[key] = id
        }
    }
}

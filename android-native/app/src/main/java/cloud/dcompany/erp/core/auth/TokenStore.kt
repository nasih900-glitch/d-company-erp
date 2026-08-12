package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.tokenDataStore by preferencesDataStore(name = "dcompany_session")

/**
 * Access + refresh tokens.
 *
 * Tokens are only ever cleared on a definitive server rejection (401/403 from
 * the refresh endpoint). A timeout, an offline tablet or a 5xx must leave them
 * alone: treating "I could not reach the server" as "this session is invalid"
 * is what logged cashiers out mid-shift on congested cafe wifi in the WebView
 * build, and a 7-day refresh token was being destroyed by 20 seconds of bad
 * link.
 */
class TokenStore(private val context: Context) {

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    // Mirrors the persisted values so the OkHttp interceptor, which runs on a
    // background thread and cannot suspend, never has to block on disk for the
    // common case.
    @Volatile private var cachedAccess: String? = null
    @Volatile private var cachedRefresh: String? = null

    suspend fun load() {
        val prefs = context.tokenDataStore.data.first()
        cachedAccess = prefs[accessKey]
        cachedRefresh = prefs[refreshKey]
    }

    fun accessToken(): String? = cachedAccess
    fun refreshToken(): String? = cachedRefresh

    fun save(access: String, refresh: String) {
        cachedAccess = access
        cachedRefresh = refresh
        // The interceptor calls this from a non-suspending context after a
        // successful refresh; the cache above is already updated, so the disk
        // write is allowed to lag by a few milliseconds.
        runBlocking {
            context.tokenDataStore.edit {
                it[accessKey] = access
                it[refreshKey] = refresh
            }
        }
    }

    fun clear() {
        cachedAccess = null
        cachedRefresh = null
        runBlocking {
            context.tokenDataStore.edit {
                it.remove(accessKey)
                it.remove(refreshKey)
            }
        }
    }

    fun hasSession(): Boolean = cachedAccess != null
}

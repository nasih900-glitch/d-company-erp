package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.shiftDataStore by preferencesDataStore(name = "dcompany_shift")

/**
 * Signed-in-user data cached outside Room, so it's available before the
 * database has anything to say.
 *
 * The shift itself is not cached here — every screen that needs "is a shift
 * open" reads Room's `local_shifts` table directly (the same table
 * ShiftViewModel writes), which is both the durable offline record and the
 * single source of truth. An earlier version of this class duplicated that
 * id into a separate DataStore key for screens that couldn't easily observe
 * Room; that duplication was itself a bug — the two writes (Room's insert
 * and this cache's write) weren't atomic, so a process death between them
 * left Room correctly showing an open shift while this cache silently
 * stayed empty, and every screen still reading from here saw "no shift
 * open" forever, with no way to recover short of closing and reopening.
 */
class ShiftCache(private val context: Context) {

    private val profileKey = stringPreferencesKey("me_profile_json")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Volatile private var cachedProfileJson: String? = null
    private val _profile = MutableStateFlow<MeResponse?>(null)
    /** Current signed-in profile for opener/protected-access UI policy. */
    val profile: StateFlow<MeResponse?> = _profile.asStateFlow()

    /** Loaded once before Room/UI construction, like tokens and terminal id. */
    suspend fun loadProfile() {
        val raw = context.shiftDataStore.data.first()[profileKey]
        cachedProfileJson = raw
        _profile.value = raw?.let(::decodeProfile)
    }

    /**
     * The signed-in user's profile, kept so the till can open offline.
     *
     * Without this, a tablet that restarts with no link cannot get past the
     * "can't reach the server" screen — /auth/me is unreachable, so the app
     * has no identity to render a session with, and the entire offline store
     * sits there unusable. Caching the profile is what makes "works offline"
     * true across a restart rather than only within one running session.
     *
     * This is not an authorisation decision: the tokens still have to satisfy
     * the server for any request to succeed, and a genuine 401/403 still signs
     * the user out. It only avoids blocking local work on a network round trip.
     */
    suspend fun cachedProfile(): String? = cachedProfileJson

    suspend fun rememberProfile(profileJson: String?) {
        context.shiftDataStore.edit {
            if (profileJson == null) it.remove(profileKey) else it[profileKey] = profileJson
        }
        cachedProfileJson = profileJson
        _profile.value = profileJson?.let(::decodeProfile)
    }

    private fun decodeProfile(raw: String): MeResponse? = runCatching {
        json.decodeFromString(MeResponse.serializer(), raw)
    }.getOrNull()
}

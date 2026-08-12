package cloud.dcompany.erp.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.shiftDataStore by preferencesDataStore(name = "dcompany_shift")

/**
 * The last known open shift for this terminal.
 *
 * Shift membership is server state, but the till has to keep billing through a
 * dropped link, and a sale must be attached to a shift for the cash drawer to
 * reconcile. Caching the id is what makes an offline sale possible at all.
 *
 * It is intentionally *not* treated as proof the shift is still open: the
 * server rechecks on sync, and a sale against a closed shift is refused and
 * queued for an owner rather than silently accepted.
 */
class ShiftCache(private val context: Context) {

    private val key = stringPreferencesKey("open_shift_id")
    private val profileKey = stringPreferencesKey("me_profile_json")

    suspend fun cachedShiftId(): String? =
        context.shiftDataStore.data.first()[key]

    suspend fun remember(shiftId: String?) {
        context.shiftDataStore.edit {
            if (shiftId == null) it.remove(key) else it[key] = shiftId
        }
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
    suspend fun cachedProfile(): String? =
        context.shiftDataStore.data.first()[profileKey]

    suspend fun rememberProfile(json: String?) {
        context.shiftDataStore.edit {
            if (json == null) it.remove(profileKey) else it[profileKey] = json
        }
    }
}

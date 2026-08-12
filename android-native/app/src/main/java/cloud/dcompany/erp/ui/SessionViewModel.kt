package cloud.dcompany.erp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.LoginRequest
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val me: MeResponse) : AuthState
    /** Credentials survive; only the network failed. Offer retry, not a logout. */
    data class Unreachable(val message: String) : AuthState
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val tokens = (app as DCompanyApp).tokens
    private val cache = (app as DCompanyApp).shiftCache
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    init {
        ApiClient.onForcedLogout = { _state.value = AuthState.SignedOut }
        restore()
    }

    /**
     * A stored token is not proof of a live session, but failing to reach the
     * server is not proof of a dead one either. Only a definitive 401/403
     * signs the user out; anything else surfaces as Unreachable with the
     * credentials intact.
     */
    fun restore() {
        if (!tokens.hasSession()) {
            _state.value = AuthState.SignedOut
            return
        }
        _state.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val me = ApiClient.api.me()
                cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
                _state.value = AuthState.SignedIn(me)
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 403) {
                    // The server itself rejected this session. That is the only
                    // thing allowed to sign a cashier out.
                    tokens.clear()
                    cache.rememberProfile(null)
                    _state.value = AuthState.SignedOut
                    return@launch
                }
                // Unreachable, not rejected. If this device has signed in
                // before, open the till on the cached profile — the whole point
                // of the offline store is that a dead link does not stop
                // trading. Blocking here would have made every offline restart
                // a lockout with a full local database sitting unused.
                val cached = cache.cachedProfile()?.let {
                    runCatching { json.decodeFromString(MeResponse.serializer(), it) }.getOrNull()
                }
                _state.value = if (cached != null) AuthState.SignedIn(cached)
                    else AuthState.Unreachable(e.message ?: "Could not reach the server.")
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (_signingIn.value) return
        _signingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            try {
                val pair = ApiClient.api.login(
                    LoginRequest(email = email.trim().lowercase(), password = password),
                )
                tokens.save(pair.accessToken, pair.refreshToken)
                val me = ApiClient.api.me()
                cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
                _state.value = AuthState.SignedIn(me)
            } catch (e: ApiException) {
                _loginError.value = e.message
            } finally {
                _signingIn.value = false
            }
        }
    }

    fun signOut() {
        tokens.clear()
        // Must also drop the cached profile: leaving it would let the next
        // cold start reopen the till as the staff member who just signed out.
        viewModelScope.launch { cache.rememberProfile(null) }
        _state.value = AuthState.SignedOut
    }
}

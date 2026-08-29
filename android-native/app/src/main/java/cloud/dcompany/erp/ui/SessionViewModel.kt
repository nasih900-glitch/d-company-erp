package cloud.dcompany.erp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.alarm.GamingAlarmReconciler
import cloud.dcompany.erp.core.alarm.HeldOrderAlarmReconciler
import cloud.dcompany.erp.core.alarm.OperationalAlarmRegistry
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeActivation
import cloud.dcompany.erp.core.auth.CacheScopeException
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.LoginSessionLease
import cloud.dcompany.erp.core.auth.OutboxGateResult
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.PricingLock
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.TerminalResolution
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.auth.activateAndRememberTerminal
import cloud.dcompany.erp.core.auth.resolveTerminalAssignment
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.LoginRequest
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.ui.screens.gaming.GamingApi
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

sealed interface AuthState {
    data object Loading : AuthState
    /**
     * A cached identity is available for display, but it is deliberately not
     * writable until the bounded live authority check either succeeds or
     * proves that the server cannot be reached. This closes the cold-start
     * window where revoked permissions or an archived till could otherwise
     * enqueue work before `/me` and the terminal list returned.
     */
    data class VerifyingCached(val me: MeResponse) : AuthState
    data object SigningOut : AuthState
    data class SignOutFailed(val message: String) : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val me: MeResponse) : AuthState
    /** A branch has multiple tills and this tablet has no valid saved assignment. */
    data class SelectTerminal(
        val me: MeResponse,
        val terminals: List<Terminal>,
        val choosing: Boolean = false,
        val error: String? = null,
        val reassigning: Boolean = false,
        val previousTerminalName: String? = null,
    ) : AuthState
    /** Credentials survive; only the network failed. Offer retry, not a logout. */
    data class Unreachable(val message: String) : AuthState
    /** Credentials survive, but identity/location isolation could not be proven. */
    data class Blocked(val message: String) : AuthState
}

sealed interface TerminalChangeUiState {
    data object Idle : TerminalChangeUiState
    data class Confirm(val terminalName: String) : TerminalChangeUiState
    data object Checking : TerminalChangeUiState
    data class Blocked(val message: String) : TerminalChangeUiState
}

/** Turn authentication failures into instructions a new employee can act on. */
internal fun loginErrorMessage(error: ApiException): String {
    val serverMessage = error.message.orEmpty()
    return when {
        error.status == 429 ->
            "Too many sign-in attempts. Wait a moment, then try again."
        error.status == 401 && serverMessage.contains("locked", ignoreCase = true) ->
            "This account is temporarily locked after several failed attempts. " +
                "Wait, then try again or ask an owner."
        error.status == 401 ->
            "Email or password is incorrect. Check both fields and try again."
        error.status == null ->
            "The server could not be reached. Check the connection and try again."
        else -> serverMessage.takeIf { it.isNotBlank() }
            ?: "Sign-in failed. Check the details and try again."
    }
}

/**
 * Sign-out safety is operational feedback, not an authentication failure.
 * A refused sign-out is shown by OutboxSafety's account-safety dialog and must
 * not leak onto the next employee's login screen. A later successful sign-out
 * also clears stale feedback left by an earlier attempt or app version.
 */
internal fun loginErrorAfterSignOutDecision(
    currentLoginError: String?,
    decision: OutboxGateResult,
): String? = when (decision) {
    OutboxGateResult.Allowed -> null
    is OutboxGateResult.Blocked -> currentLoginError
}

internal enum class RestoreFailureAction {
    KEEP_CACHED_SESSION,
    SHOW_UNREACHABLE,
    SIGN_OUT,
}

/** Only a definitive authentication decision may discard a restored session. */
internal fun restoreFailureAction(
    cachedIdentityAvailable: Boolean,
    liveProfileVerified: Boolean = false,
    error: ApiException,
): RestoreFailureAction = when {
    error.status == 401 || error.status == 403 -> RestoreFailureAction.SIGN_OUT
    // Offline operation is allowed only when the server did not answer. An
    // authoritative HTTP response (including 5xx/426) keeps the cache locked:
    // it is not evidence that the last-known permissions or till remain valid.
    cachedIdentityAvailable && !liveProfileVerified && error.status == null ->
        RestoreFailureAction.KEEP_CACHED_SESSION
    else -> RestoreFailureAction.SHOW_UNREACHABLE
}

/** A cached profile is usable only when it belongs to the encrypted token on this device. */
internal fun cachedProfileMatchesToken(accessToken: String, profile: MeResponse): Boolean =
    AccessTokenIdentityParser.parse(accessToken) == OutboxOwnerIdentity.from(profile)

private class TerminalScopeException(message: String) : Exception(message)

private data class PendingTerminalSession(
    val me: MeResponse,
    val installedLogin: LoginSessionLease? = null,
    val restoredSession: SessionRefreshLease? = null,
    val previousTerminal: ValidatedTerminalDisplay? = null,
) {
    init {
        check((installedLogin == null) xor (restoredSession == null))
    }
}

/**
 * The ordering contract behind cold start: cached identity may make the wait
 * understandable, but cached write authority is not activated here. The
 * remote verifier owns the later decision to activate a live or offline
 * scope. Keeping this tiny coordinator pure makes the no-write verification
 * window deterministic in tests.
 */
internal suspend fun <T : Any> verifyRemoteBeforeCachedActivation(
    cached: T?,
    validateCachedIdentity: suspend (T) -> Boolean,
    publishVerifying: (T) -> Unit,
    refreshRemote: suspend (cachedIdentity: T?) -> Unit,
) {
    val cachedIdentity = cached?.takeIf { validateCachedIdentity(it) }
    if (cached != null && cachedIdentity == null) return
    if (cachedIdentity != null) publishVerifying(cachedIdentity)
    refreshRemote(cachedIdentity)
}

/**
 * A login cancellation is not allowed to leave its newly persisted token behind.
 * The caller supplies the lineage-guarded rollback so a late cancellation can
 * never clear a newer employee's explicit login.
 */
internal suspend fun <T : Any> rollbackCancelledLoginAndRethrow(
    installedLogin: T?,
    cancelled: CancellationException,
    rollbackIfCurrent: suspend (T) -> Unit,
): Nothing {
    if (installedLogin != null) {
        withContext(NonCancellable + Dispatchers.IO) {
            rollbackIfCurrent(installedLogin)
        }
    }
    throw cancelled
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val tokens = (app as DCompanyApp).tokens
    private val cache = (app as DCompanyApp).shiftCache
    private val terminals = (app as DCompanyApp).terminalStore
    private val realtime = (app as DCompanyApp).realtime
    private val sync = (app as DCompanyApp).sync
    private val connectivity = (app as DCompanyApp).connectivity
    private val db = (app as DCompanyApp).db
    private val outboxSafety = (app as DCompanyApp).outboxSafety
    private val cacheIsolation = (app as DCompanyApp).cacheIsolation
    private val shiftApi = ApiClient.create<ShiftApi>()
    private val gamingApi = ApiClient.create<GamingApi>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()
    val accountSafetyNotice = outboxSafety.notice
    private val _accessChangeNotice = MutableStateFlow<String?>(null)
    val accessChangeNotice: StateFlow<String?> = _accessChangeNotice.asStateFlow()
    private val _terminalChange = MutableStateFlow<TerminalChangeUiState>(TerminalChangeUiState.Idle)
    val terminalChange: StateFlow<TerminalChangeUiState> = _terminalChange.asStateFlow()
    private var restoreJob: Job? = null
    private var terminalChangeJob: Job? = null
    private val terminalChangeGate = TerminalReassignmentRequestGate()
    private var pendingTerminalSession: PendingTerminalSession? = null
    private val authorityRefresh = SessionAuthorityRefreshCoordinator(viewModelScope) {
        refreshSessionAuthority()
    }

    init {
        viewModelScope.launch {
            realtime.changes.collect { event ->
                if (event.requiresAuthorityRefresh()) authorityRefresh.request()
            }
        }
        ApiClient.onForcedLogout = {
            // The owner record is deliberately retained. If the refresh token
            // expires with writes still queued, only this same identity may
            // sign back in and drain them.
            realtime.disconnect()
            terminalChangeJob?.cancel()
            terminalChangeGate.finish()
            _terminalChange.value = TerminalChangeUiState.Idle
            deactivateTerminalRuntime()
            PricingLock.lock()
            // Remove feature ViewModels immediately so no new action can
            // capture the old lease while revocation waits for an already-
            // entered Room commit to finish.
            pendingTerminalSession = null
            _accessChangeNotice.value = null
            _loginError.value = FORCED_LOGOUT_MESSAGE
            _state.value = AuthState.Loading
            viewModelScope.launch {
                cacheIsolation.deactivate()
                sync.clearSessionFeedback()
                cache.rememberProfile(null)
                _state.value = AuthState.SignedOut
                refreshSignedOutSafetyNotice()
            }
        }
        restore()
    }

    /**
     * Re-read server authority after an access-control event or a websocket
     * gap. Identity and token lineage are checked both before persistence and
     * before publication, so a late response can never overwrite a newer
     * employee's profile.
     */
    private suspend fun refreshSessionAuthority() {
        val previous = (_state.value as? AuthState.SignedIn)?.me ?: return
        val lease = tokens.refreshLease() ?: return
        try {
            val refreshed = ApiClient.api.me()
            val activeAccess = tokens.currentAccessFor(lease) ?: return
            if (!canApplyAuthorityProfile(previous, refreshed, activeAccess)) {
                blockWorkspace(
                    "This session could not be safely verified after an access change. " +
                        "Sign in again or ask a manager.",
                )
                return
            }
            val current = (_state.value as? AuthState.SignedIn)?.me ?: return
            if (OutboxOwnerIdentity.from(current) != OutboxOwnerIdentity.from(previous)) return

            val authorityChanged = accessAuthorityChanged(previous, refreshed)
            val gainedOperationalWorkspace =
                !EffectivePermissions.from(previous).requiresOperationalWorkspace() &&
                    EffectivePermissions.from(refreshed).requiresOperationalWorkspace()
            if (gainedOperationalWorkspace && !activateResolvedTerminalOrRequestSelection(
                    me = refreshed,
                    restoredSession = lease,
                )
            ) {
                if (authorityChanged) _accessChangeNotice.value = ACCESS_CHANGED_MESSAGE
                return
            }

            if (tokens.currentAccessFor(lease) == null) return
            cache.rememberProfile(json.encodeToString(MeResponse.serializer(), refreshed))
            if (tokens.currentAccessFor(lease) == null) {
                // Forced logout may win while DataStore is committing. Remove
                // only the just-written old profile before Login is exposed.
                cache.rememberProfile(null)
                return
            }
            val stillCurrent = (_state.value as? AuthState.SignedIn)?.me ?: return
            if (OutboxOwnerIdentity.from(stillCurrent) != OutboxOwnerIdentity.from(previous)) return
            _state.value = AuthState.SignedIn(refreshed)
            if (authorityChanged) _accessChangeNotice.value = ACCESS_CHANGED_MESSAGE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (error.status == 401 || error.status == 403) {
                _loginError.value = FORCED_LOGOUT_MESSAGE
                rejectRestoredSession(lease, message = null)
            }
            // Connectivity failures retain the last verified authority. A
            // reconnect produces another refresh request automatically.
        } catch (error: CacheScopeException) {
            blockWorkspace(
                error.message ?: "The updated access could not safely open this tablet's saved workspace.",
            )
        } catch (error: TerminalScopeException) {
            blockWorkspace(
                error.message ?: "The updated access could not safely verify this tablet's till.",
            )
        }
    }

    /**
     * A stored token is not proof of a live session, but failing to reach the
     * server is not proof of a dead one either. Only a definitive 401/403
     * signs the user out; anything else surfaces as Unreachable with the
     * credentials intact.
     */
    fun restore() {
        restoreJob?.cancel()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        deactivateTerminalRuntime()
        _state.value = AuthState.Loading
        restoreJob = viewModelScope.launch {
            cacheIsolation.deactivate()
            sync.clearSessionFeedback()
            if (!tokens.hasSession()) {
                cancelOperationalAlarms()
                _state.value = AuthState.SignedOut
                refreshSignedOutSafetyNotice()
                return@launch
            }
            val restoreLease = tokens.refreshLease() ?: run {
                cancelOperationalAlarms()
                _state.value = AuthState.SignedOut
                return@launch
            }
            try {
                val cached = cache.cachedProfile()?.let {
                    runCatching { json.decodeFromString(MeResponse.serializer(), it) }.getOrNull()
                }
                verifyRemoteBeforeCachedActivation(
                    cached = cached,
                    validateCachedIdentity = { validateCachedIdentity(restoreLease, it) },
                    publishVerifying = { _state.value = AuthState.VerifyingCached(it) },
                    refreshRemote = { cachedIdentity ->
                        if (tokens.currentAccessFor(restoreLease) != null) {
                            refreshRestoredSession(restoreLease, cachedIdentity)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Room/DataStore failure means ownership cannot be proven.
                // Fail closed instead of exposing feature ViewModels that can
                // add more writes to an identity-ambiguous queue.
                rejectRestoredSession(
                    restoreLease,
                    "Could not verify this tablet's saved work. Sign-in is locked; try again or ask support.",
                )
            }
        }
    }

    /** Validate display identity only; this must not grant a Room write lease. */
    private suspend fun validateCachedIdentity(
        restoreLease: SessionRefreshLease,
        profile: MeResponse,
    ): Boolean {
        if (!cachedProfileMatchesToken(restoreLease.accessToken, profile)) {
            rejectRestoredSession(
                restoreLease,
                "The saved employee does not match this tablet's secure session. " +
                    "Sign-in is locked; ask a manager or support technician for help.",
            )
            return false
        }
        return tokens.currentAccessFor(restoreLease) != null
    }

    private suspend fun activateCachedSession(
        restoreLease: SessionRefreshLease,
        profile: MeResponse,
    ): Boolean {
        if (!validateCachedIdentity(restoreLease, profile)) return false
        if (tokens.currentAccessFor(restoreLease) == null) return false
        val scope = runCatching { cachedScope(profile) }.getOrNull() ?: return false
        try {
            cacheIsolation.activateCached(scope)
            sync.clearSessionFeedback()
        } catch (_: CacheScopeException) {
            return false
        }
        ApiClient.activateTerminalScope(scope.terminalId)
        val cachedTerminalValid = scope.terminalId == null ||
            terminals.activateCachedValidated(scope.terminalId, scope.branchId)
        if (!cachedTerminalValid) {
            deactivateTerminalRuntime()
            cacheIsolation.deactivate()
            return false
        }
        if (scope.terminalId == null) terminals.deactivateValidatedDisplay()
        // Do not bind or attribute even a legacy local outbox until the
        // persisted user/company/branch/terminal cache scope is exact.
        if (!acceptAuthenticated(profile, restoredSession = restoreLease)) return false
        reconcileOperationalAlarms()
        return tokens.currentAccessFor(restoreLease) != null
    }

    /**
     * Server verification continues after cached UI is available. The hard
     * bound prevents a damaged route, DNS black-hole, or retrying proxy from
     * keeping a cache-less install on Loading forever.
     */
    private suspend fun refreshRestoredSession(
        restoreLease: SessionRefreshLease,
        cachedProfile: MeResponse?,
    ) {
        var liveProfileVerified = false
        try {
            val me = withTimeoutOrNull(RESTORE_SERVER_TIMEOUT_MILLIS) {
                ApiClient.api.me()
            } ?: throw ApiException(
                "The server did not respond in time. Continue offline or try again.",
                code = "network_timeout",
            )

            val activeAccess = tokens.currentAccessFor(restoreLease) ?: return
            if (!cachedProfileMatchesToken(activeAccess, me)) {
                rejectRestoredSession(
                    restoreLease,
                    "The server session identity changed unexpectedly. Sign-in was locked to protect saved work.",
                )
                return
            }
            // From this point onward the server has authoritatively answered
            // `/me`. If the later terminal/shift check loses connectivity, we
            // must not fall back to the older cached permissions: the fresh
            // profile may already have revoked a module or changed authority.
            liveProfileVerified = true
            // The cached profile is display-only while this check runs. No
            // feature ViewModel or Room write lease exists until the live
            // account and till below have been verified.
            if (!activateResolvedTerminalOrRequestSelection(
                    me = me,
                    restoredSession = restoreLease,
                )
            ) return
            if (!acceptAuthenticated(me, restoredSession = restoreLease)) return
            if (tokens.currentAccessFor(restoreLease) == null) return
            cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
            if (EffectivePermissions.from(me).has(ErpPermission.PosRead)) {
                refreshShiftAuthorityAtLogin()
            }
            if (tokens.currentAccessFor(restoreLease) == null) return
            _state.value = AuthState.SignedIn(me)
            sync.requestSync()
            realtime.connect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: CacheScopeException) {
            blockWorkspace(e.message ?: "The active account scope could not be verified.")
        } catch (e: TerminalScopeException) {
            val message = e.message ?: "This tablet's terminal could not be verified."
            blockWorkspace(message)
        } catch (e: ApiException) {
            when (
                restoreFailureAction(
                    cachedIdentityAvailable = cachedProfile != null,
                    liveProfileVerified = liveProfileVerified,
                    error = e,
                )
            ) {
                RestoreFailureAction.KEEP_CACHED_SESSION -> {
                    if (
                        cachedProfile != null &&
                        activateCachedSession(restoreLease, cachedProfile)
                    ) {
                        _state.value = AuthState.SignedIn(cachedProfile)
                        sync.requestSync()
                        realtime.connect()
                    } else {
                        lockRestoredWorkspaceAsUnreachable(
                            restoreLease,
                            e.message ?: "Could not reach the server.",
                        )
                    }
                }
                RestoreFailureAction.SHOW_UNREACHABLE -> {
                    lockRestoredWorkspaceAsUnreachable(
                        restoreLease,
                        e.message ?: "Could not reach the server.",
                    )
                }
                RestoreFailureAction.SIGN_OUT -> {
                    rejectRestoredSession(restoreLease, message = null)
                }
            }
        } catch (_: Exception) {
            // A malformed response is not the same as being offline. Keep the
            // cached write lease locked and give the employee a clear retry
            // path instead of accepting possibly revoked authority.
            lockRestoredWorkspaceAsUnreachable(
                restoreLease,
                "Could not verify the server session. Check the connection and try again.",
            )
        }
    }

    /** Revoke any partially activated live scope before exposing a retry UI. */
    private suspend fun lockRestoredWorkspaceAsUnreachable(
        restoreLease: SessionRefreshLease,
        message: String,
    ) {
        if (tokens.currentAccessFor(restoreLease) == null) return
        deactivateTerminalRuntime()
        cacheIsolation.deactivate()
        sync.clearSessionFeedback()
        cancelOperationalAlarms()
        realtime.disconnect()
        if (tokens.currentAccessFor(restoreLease) != null) {
            _state.value = AuthState.Unreachable(message)
        }
    }

    fun signIn(email: String, password: String) {
        if (_signingIn.value) return
        // A pricing re-auth belongs to one login lineage, not merely a user id.
        // Relogging as the same owner must require the password again.
        PricingLock.lock()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        _accessChangeNotice.value = null
        _signingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            var installedLogin: LoginSessionLease? = null
            // withContext has prompt cancellation on its return dispatch. Keep
            // the lease as soon as the durable install completes so cleanup
            // can still identify this lineage if cancellation wins that race.
            val installedLoginCapture = AtomicReference<LoginSessionLease?>(null)
            try {
                val pair = ApiClient.api.login(
                    LoginRequest(email = email.trim().lowercase(), password = password),
                )
                val preflight = outboxSafety.canInstallSession(pair.accessToken)
                if (preflight is OutboxGateResult.Blocked) {
                    _loginError.value = preflight.message
                    return@launch
                }
                installedLogin = withContext(Dispatchers.IO) {
                    tokens.installForLogin(pair.accessToken, pair.refreshToken).also {
                        installedLoginCapture.set(it)
                    }
                }
                val me = ApiClient.api.me()
                val tokenIdentity = AccessTokenIdentityParser.parse(pair.accessToken)
                if (tokenIdentity == null || tokenIdentity != OutboxOwnerIdentity.from(me)) {
                    rollbackFailedLogin(installedLogin)
                    _loginError.value =
                        "The signed-in identity could not be verified. No account was changed; try again."
                    return@launch
                }
                if (!activateResolvedTerminalOrRequestSelection(
                        me = me,
                        installedLogin = installedLogin,
                    )
                ) return@launch
                if (!acceptAuthenticated(me, installedLogin)) return@launch
                cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
                // Alarm readers require token + cached profile + Room lease to
                // agree. Explicit login installs the profile only here, so
                // re-arm any retained unresolved reminders after that commit.
                reconcileOperationalAlarms()
                if (EffectivePermissions.from(me).has(ErpPermission.PosRead)) {
                    refreshShiftAuthorityAtLogin()
                }
                _state.value = AuthState.SignedIn(me)
                sync.requestSync()
                realtime.connect()
            } catch (cancelled: CancellationException) {
                rollbackCancelledLoginAndRethrow(
                    installedLogin = installedLogin ?: installedLoginCapture.get(),
                    cancelled = cancelled,
                    rollbackIfCurrent = { rollbackFailedLogin(it) },
                )
            } catch (e: ApiException) {
                rollbackFailedLogin(installedLogin)
                _loginError.value = loginErrorMessage(e)
            } catch (e: CacheScopeException) {
                rollbackFailedLogin(installedLogin)
                _loginError.value = e.message
            } catch (e: TerminalScopeException) {
                rollbackFailedLogin(installedLogin)
                _loginError.value = e.message
            } catch (_: Exception) {
                // A local ownership/database failure must fail closed. Never
                // leave a newly installed token able to drain an unverified
                // queue under the wrong staff identity.
                rollbackFailedLogin(installedLogin)
                _loginError.value =
                    "Could not verify this tablet's unsynced work. No account was changed; try again or ask support."
            } finally {
                _signingIn.value = false
            }
        }
    }
    /**
     * Establish the exact branch/till cache scope before any feature ViewModel
     * is rendered. Multiple tills require an explicit employee choice; the API
     * response order is never used as a default.
     *
     * A replacement till is persisted only after the old scope has been
     * safely purged and the new marker committed. Persisting it earlier would
     * make the previous till's unresolved work impossible to reopen if the
     * transactional purge refused the change.
     */
    private suspend fun activateResolvedTerminalOrRequestSelection(
        me: MeResponse,
        installedLogin: LoginSessionLease? = null,
        restoredSession: SessionRefreshLease? = null,
    ): Boolean {
        pendingTerminalSession = null
        val requiresTerminal = EffectivePermissions.from(me).requiresOperationalWorkspace()
        if (!requiresTerminal) {
            activateValidatedScope(scopeFor(me, terminalId = null))
            return true
        }

        val branchId = me.branchId?.trim()?.takeIf(String::isNotEmpty)
        val available = if (branchId == null) emptyList() else ApiClient.api.terminals(branchId)
        val cachedId = terminals.terminalId()
        val singleHybridOnly = WorkspaceFeatureProfiles.Active.singleHybridTerminalOnly
        val branchTerminals = available
            .filter { it.id.isNotBlank() && it.branchId == branchId }
            .distinctBy(Terminal::id)
        val singleHybridTopologyValid = branchTerminals.size == 1 &&
            branchTerminals.single().purpose == TerminalPurpose.HYBRID
        val cachedStillValid = branchId != null &&
            branchTerminals.any { it.id == cachedId } &&
            (!singleHybridOnly || singleHybridTopologyValid)
        // The exact saved till is the only scope allowed to reopen unresolved
        // work. A clean install/reassignment must prove the queue is empty.
        val hasUnresolvedLocalWork = if (cachedStillValid) {
            false
        } else {
            !outboxSafety.snapshot().isClean
        }
        val resolution = resolveTerminalAssignment(
            requiresPosTerminal = true,
            branchId = branchId,
            availableTerminals = available,
            cachedTerminalId = cachedId,
            hasUnresolvedLocalWork = hasUnresolvedLocalWork,
            singleHybridOnly = singleHybridOnly,
        )

        return when (resolution) {
            TerminalResolution.NotRequired -> error("POS terminal resolution unexpectedly skipped")
            is TerminalResolution.Resolved -> {
                activateAndRememberTerminal(
                    terminalId = resolution.terminal.id,
                    // Refresh the non-secret label even when the id itself was
                    // already saved (legacy installs have no name/branch metadata).
                    shouldRemember = true,
                    activate = { terminalId -> activateValidatedScope(scopeFor(me, terminalId)) },
                    remember = { terminals.rememberValidated(resolution.terminal) },
                )
                true
            }
            is TerminalResolution.SelectionRequired -> {
                // The server has rejected/no longer recognises any saved till.
                // Revoke its lease and marker so a process restart cannot
                // reopen that workspace offline while staff are choosing.
                cacheIsolation.invalidate()
                sync.clearSessionFeedback()
                deactivateTerminalRuntime()
                realtime.disconnect()
                pendingTerminalSession = PendingTerminalSession(
                    me = me,
                    installedLogin = installedLogin,
                    restoredSession = restoredSession,
                )
                _state.value = AuthState.SelectTerminal(me, resolution.terminals)
                false
            }
            is TerminalResolution.Blocked -> {
                // Preserve an old exact marker only when it is needed to
                // recover already-saved work; otherwise prevent offline reuse
                // of a server-rejected branch/till assignment.
                if (!hasUnresolvedLocalWork) {
                    cacheIsolation.invalidate()
                    sync.clearSessionFeedback()
                    deactivateTerminalRuntime()
                }
                throw TerminalScopeException(resolution.message)
            }
        }
    }

    fun requestTerminalReassignment() {
        if (_terminalChange.value !is TerminalChangeUiState.Idle) return
        val me = (_state.value as? AuthState.SignedIn)?.me ?: return
        val canRequest = me.protectedAccess &&
            EffectivePermissions.from(me).requiresOperationalWorkspace()
        if (!canRequest) {
            _terminalChange.value = TerminalChangeUiState.Blocked(
                "Only a protected owner with operational access can change this tablet's workspace.",
            )
            return
        }
        val currentName = terminals.activeValidatedTerminal.value?.terminalName
            ?: "the current till"
        _terminalChange.value = TerminalChangeUiState.Confirm(currentName)
    }

    fun confirmTerminalReassignment() {
        if (_terminalChange.value !is TerminalChangeUiState.Confirm) return
        if (!terminalChangeGate.tryStart()) return
        _terminalChange.value = TerminalChangeUiState.Checking
        terminalChangeJob = viewModelScope.launch {
            try {
                beginTerminalReassignment()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                _terminalChange.value = TerminalChangeUiState.Blocked(
                    terminalReassignmentApiError(error),
                )
            } catch (error: CacheScopeException) {
                _terminalChange.value = TerminalChangeUiState.Blocked(
                    error.message ?: "The current tablet workspace could not be verified. No till was changed.",
                )
            } catch (error: TerminalScopeException) {
                _terminalChange.value = TerminalChangeUiState.Blocked(
                    error.message ?: "The till safety check failed. No till was changed.",
                )
            } catch (_: Exception) {
                _terminalChange.value = TerminalChangeUiState.Blocked(
                    "The tablet could not complete all till safety checks. No till was changed; " +
                        "check the connection and try again.",
                )
            } finally {
                terminalChangeGate.finish()
                terminalChangeJob = null
                if (_terminalChange.value is TerminalChangeUiState.Checking) {
                    _terminalChange.value = TerminalChangeUiState.Blocked(
                        "The till check did not finish. No till was changed; try again.",
                    )
                }
            }
        }
    }

    fun dismissTerminalChange() {
        if (_terminalChange.value !is TerminalChangeUiState.Checking) {
            _terminalChange.value = TerminalChangeUiState.Idle
        }
    }

    /**
     * Verify remote state first, then perform one final Room-authoritative
     * check while revoking the current cache lease. Until that final allowed
     * decision, the prior header, cache marker and persisted assignment remain
     * untouched.
     */
    private suspend fun beginTerminalReassignment() {
        val me = (_state.value as? AuthState.SignedIn)?.me
            ?: throw TerminalScopeException("The signed-in workspace changed. No till was changed.")
        val lease = tokens.refreshLease()
            ?: throw TerminalScopeException("Your sign-in expired. Sign in again before changing tills.")
        val active = terminals.activeValidatedTerminal.value
        val preliminary = reassignmentFacts(
            expected = me,
            lease = lease,
            active = active,
            currentTerminalStillAvailable = true,
            serverOpenShiftCount = 0,
            serverGamingBlockerCount = 0,
            alternativeTillCount = 1,
        )
        requireReassignmentAllowed(preliminary)
        val exactActive = active
            ?: throw TerminalScopeException(
                "Reconnect once to verify this tablet's till name and branch before changing it.",
            )
        val branchId = me.branchId?.trim()?.takeIf(String::isNotEmpty)
            ?: throw TerminalScopeException(
                "This account has no branch assignment. Ask an owner to correct it before changing tills.",
            )

        val firstTerminalRows = verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
        requireCurrentReassignmentSession(me, lease)
        val currentStillAvailable = firstTerminalRows.any {
            it.id == exactActive.terminalId && it.branchId == exactActive.branchId
        }

        val openShifts = shiftApi.shifts(onlyOpen = true, limit = 200)
        requireCurrentReassignmentSession(me, lease)
        val serverOpenCount = openShifts.count { row ->
            row.branchId != branchId ||
                row.status != "open" ||
                row.terminalId == null ||
                row.terminalId == exactActive.terminalId
        }

        val serverSessions = gamingApi.sessions(unbilledOnly = true, limit = 500)
        requireCurrentReassignmentSession(me, lease)
        val serverGamingCount = serverSessions.count {
            gamingBlocksTillReassignment(it.status, it.orderId)
        } + if (serverSessions.size >= 500 && serverSessions.none {
                gamingBlocksTillReassignment(it.status, it.orderId)
            }
        ) 1 else 0

        // Re-fetch choices after the slower shift/gaming checks. The selector
        // never opens from a stale first response.
        val finalTerminalRows = verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
        requireCurrentReassignmentSession(me, lease)
        val finalCurrentStillAvailable = currentStillAvailable && finalTerminalRows.any {
            it.id == exactActive.terminalId && it.branchId == exactActive.branchId
        }
        val alternatives = finalTerminalRows.filter { it.id != exactActive.terminalId }

        val remoteDecision = reassignmentFacts(
            expected = me,
            lease = lease,
            active = exactActive,
            currentTerminalStillAvailable = finalCurrentStillAvailable,
            serverOpenShiftCount = serverOpenCount,
            serverGamingBlockerCount = serverGamingCount,
            alternativeTillCount = alternatives.size,
        )
        requireReassignmentAllowed(remoteDecision)

        val finalDecision = cacheIsolation.deactivateAfterOutboxGate {
            val facts = reassignmentFacts(
                expected = me,
                lease = lease,
                active = exactActive,
                currentTerminalStillAvailable = finalCurrentStillAvailable,
                serverOpenShiftCount = serverOpenCount,
                serverGamingBlockerCount = serverGamingCount,
                alternativeTillCount = alternatives.size,
            )
            when (val decision = terminalReassignmentDecision(facts)) {
                TerminalReassignmentDecision.Allowed -> OutboxGateResult.Allowed
                is TerminalReassignmentDecision.Blocked -> OutboxGateResult.Blocked(decision.message)
            }
        }
        if (finalDecision is OutboxGateResult.Blocked) {
            throw TerminalScopeException(finalDecision.message)
        }

        sync.clearSessionFeedback()

        // The final gate has revoked every feature lease, so no cart/shift or
        // outbox write can race the switch from this point onward.
        if (!isCurrentReassignmentSession(me, lease)) {
            restorePreviousTerminalAfterInterruptedChange(me, lease, exactActive)
            throw TerminalScopeException(
                "Your sign-in changed during the final till check. The previous assignment was kept.",
            )
        }
        deactivateTerminalRuntime()
        realtime.disconnect()
        pendingTerminalSession = PendingTerminalSession(
            me = me,
            restoredSession = lease,
            previousTerminal = exactActive,
        )
        _terminalChange.value = TerminalChangeUiState.Idle
        _state.value = AuthState.SelectTerminal(
            me = me,
            terminals = alternatives,
            reassigning = true,
            previousTerminalName = exactActive.terminalName,
        )
    }

    private suspend fun reassignmentFacts(
        expected: MeResponse,
        lease: SessionRefreshLease,
        active: ValidatedTerminalDisplay?,
        currentTerminalStillAvailable: Boolean,
        serverOpenShiftCount: Int,
        serverGamingBlockerCount: Int,
        alternativeTillCount: Int,
    ): TerminalReassignmentFacts {
        val current = (_state.value as? AuthState.SignedIn)?.me
        val identityExact = current != null &&
            OutboxOwnerIdentity.from(current) == OutboxOwnerIdentity.from(expected)
        val branchId = current?.branchId?.trim()?.takeIf(String::isNotEmpty)
        val currentScope = cacheIsolation.currentLease()?.scope
        val activeExact = active != null &&
            active.terminalId == terminals.terminalId() &&
            active.branchId == branchId &&
            currentScope == scopeFor(current ?: expected, active.terminalId)
        val terminalId = active?.terminalId
        val localShiftCount = terminalId?.let {
            db.shiftCloseSafetyDao().currentShiftCountForTerminal(it)
        } ?: 0
        val localGamingCount = db.gamingDao().localSessionOverlaysForAlarms().size
        val outbox = outboxSafety.snapshot()
        return TerminalReassignmentFacts(
            protectedOwner = identityExact && current?.protectedAccess == true,
            tokenLineageCurrent = identityExact && tokens.currentAccessFor(lease) != null,
            online = connectivity.networkValidated.value,
            activeTerminalExact = activeExact,
            currentTerminalStillAvailable = currentTerminalStillAvailable,
            unresolvedOutboxCount = outbox.count,
            localShiftWorkflowCount = localShiftCount,
            localGamingWorkflowCount = localGamingCount,
            serverOpenShiftCount = serverOpenShiftCount,
            serverGamingBlockerCount = serverGamingBlockerCount,
            alternativeTillCount = alternativeTillCount,
        )
    }

    private fun requireReassignmentAllowed(facts: TerminalReassignmentFacts) {
        val decision = terminalReassignmentDecision(facts)
        if (decision is TerminalReassignmentDecision.Blocked) {
            throw TerminalScopeException(decision.message)
        }
    }

    private fun isCurrentReassignmentSession(
        expected: MeResponse,
        lease: SessionRefreshLease,
    ): Boolean {
        val current = (_state.value as? AuthState.SignedIn)?.me ?: return false
        return tokens.currentAccessFor(lease) != null &&
            current.protectedAccess &&
            OutboxOwnerIdentity.from(current) == OutboxOwnerIdentity.from(expected)
    }

    private fun requireCurrentReassignmentSession(
        expected: MeResponse,
        lease: SessionRefreshLease,
    ) {
        if (!isCurrentReassignmentSession(expected, lease)) {
            throw TerminalScopeException(
                "Your sign-in or owner access changed during the till check. No till was changed.",
            )
        }
    }

    private suspend fun restorePreviousTerminalAfterInterruptedChange(
        expected: MeResponse,
        lease: SessionRefreshLease,
        previous: ValidatedTerminalDisplay,
    ) {
        val current = (_state.value as? AuthState.SignedIn)?.me ?: return
        if (
            tokens.currentAccessFor(lease) == null ||
            OutboxOwnerIdentity.from(current) != OutboxOwnerIdentity.from(expected)
        ) return
        runCatching {
            cacheIsolation.activateCached(scopeFor(current, previous.terminalId))
            sync.clearSessionFeedback()
            ApiClient.activateTerminalScope(previous.terminalId)
            terminals.activateCachedValidated(previous.terminalId, previous.branchId)
            reconcileOperationalAlarms()
        }
    }

    private fun terminalReassignmentApiError(error: ApiException): String = when {
        error.status == null ->
            "The ERP server could not be reached. No till was changed; reconnect and try again."
        error.status == 401 || error.status == 403 ->
            "The server could not authorise every till safety check. No till was changed; " +
                "sign in again or ask another protected owner."
        else ->
            "The server could not verify the current shift, gaming work, and till list. " +
                "No till was changed; refresh those screens and try again."
    }

    /** Complete a multi-till login after the employee makes an explicit choice. */
    fun selectTerminal(terminal: Terminal) {
        val visible = _state.value as? AuthState.SelectTerminal ?: return
        val pending = pendingTerminalSession ?: return
        if (visible.choosing) return
        if (visible.terminals.none { it.id == terminal.id }) {
            _state.value = visible.copy(error = "That till is no longer available. Choose one shown here.")
            return
        }
        _state.value = visible.copy(choosing = true, error = null)

        viewModelScope.launch {
            try {
                if (!pending.isTokenCurrent()) return@launch
                val branchId = pending.me.branchId?.trim()?.takeIf(String::isNotEmpty)
                    ?: throw TerminalScopeException(
                        "This account has no branch assignment. Ask a manager to assign one before opening the workspace.",
                    )
                val refreshed = if (pending.previousTerminal == null) {
                    verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
                } else {
                    verifiedReassignmentChoices(pending, branchId)
                }
                val chosen = refreshed.firstOrNull { it.id == terminal.id }
                if (chosen == null) {
                    _state.value = visible.copy(
                        terminals = refreshed,
                        choosing = false,
                        error = "That till was removed or moved to another branch. Choose an available till.",
                    )
                    return@launch
                }
                if (!outboxSafety.snapshot().isClean) {
                    throw TerminalScopeException(
                        "Saved work appeared before the till was assigned. Reconnect with the previous " +
                            "setup and resolve Sync before changing tills.",
                    )
                }

                activateAndRememberTerminal(
                    terminalId = chosen.id,
                    shouldRemember = true,
                    activate = { terminalId ->
                        activateValidatedScope(scopeFor(pending.me, terminalId))
                    },
                    remember = { terminals.rememberValidated(chosen) },
                )
                if (!pending.isTokenCurrent()) return@launch
                val authenticated = acceptAuthenticated(
                    pending.me,
                    installedLogin = pending.installedLogin,
                    restoredSession = pending.restoredSession,
                )
                if (!authenticated) return@launch
                if (!pending.isTokenCurrent()) return@launch

                cache.rememberProfile(json.encodeToString(MeResponse.serializer(), pending.me))
                refreshShiftAuthorityAtLogin()
                if (!pending.isTokenCurrent()) return@launch
                pendingTerminalSession = null
                _state.value = AuthState.SignedIn(pending.me)
                reconcileOperationalAlarms()
                sync.requestSync()
                realtime.connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                updateTerminalSelectionError(
                    if (visible.reassigning) terminalReassignmentApiError(error)
                    else terminalSelectionError(error),
                )
            } catch (error: CacheScopeException) {
                updateTerminalSelectionError(
                    error.message ?: "This tablet could not safely open the selected till. Try again.",
                )
            } catch (error: TerminalScopeException) {
                updateTerminalSelectionError(error.message ?: "The selected till could not be verified.")
            } catch (_: Exception) {
                updateTerminalSelectionError(
                    "The selected till could not be saved safely. No workspace was opened; try again or ask support.",
                )
            } finally {
                val current = _state.value
                if (current is AuthState.SelectTerminal && current.choosing) {
                    _state.value = current.copy(choosing = false)
                }
            }
        }
    }

    fun refreshTerminalChoices() {
        val visible = _state.value as? AuthState.SelectTerminal ?: return
        val pending = pendingTerminalSession ?: return
        if (visible.choosing) return
        _state.value = visible.copy(choosing = true, error = null)

        viewModelScope.launch {
            try {
                if (!pending.isTokenCurrent()) return@launch
                val branchId = pending.me.branchId?.trim()?.takeIf(String::isNotEmpty)
                    ?: throw TerminalScopeException(
                        "This account has no branch assignment. Ask a manager to assign one before opening the workspace.",
                    )
                val refreshed = verifiedBranchTerminals(
                    ApiClient.api.terminals(branchId),
                    branchId,
                ).let { rows ->
                    pending.previousTerminal?.let { old ->
                        rows.filter { it.id != old.terminalId }
                    } ?: rows
                }
                if (!pending.isTokenCurrent()) return@launch
                _state.value = visible.copy(
                    terminals = refreshed,
                    choosing = false,
                    error = if (refreshed.isEmpty()) {
                        if (visible.reassigning) {
                            "No other till is configured for this branch. Create one in Settings, " +
                                "or keep the previous till."
                        } else {
                            "No till is configured for this branch. Ask an owner to create one, then refresh."
                        }
                    } else {
                        null
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                updateTerminalSelectionError(terminalSelectionError(error))
            } catch (error: TerminalScopeException) {
                updateTerminalSelectionError(error.message ?: "The till list could not be verified.")
            } catch (_: Exception) {
                updateTerminalSelectionError(
                    "The till list could not be refreshed safely. Check the connection and try again.",
                )
            } finally {
                val current = _state.value
                if (current is AuthState.SelectTerminal && current.choosing) {
                    _state.value = current.copy(choosing = false)
                }
            }
        }
    }

    /**
     * Selection can stay open while another tablet starts a shift/session, so
     * repeat the live guards immediately before committing the chosen till.
     * X-Terminal-Id is intentionally inactive here; shifts are filtered back
     * to the exact previous terminal while gaming remains branch-wide.
     */
    private suspend fun verifiedReassignmentChoices(
        pending: PendingTerminalSession,
        branchId: String,
    ): List<Terminal> {
        val previous = pending.previousTerminal
            ?: throw TerminalScopeException("The previous till assignment is unavailable. Cancel and try again.")
        if (!pending.isTokenCurrent() || !pending.me.protectedAccess) {
            throw TerminalScopeException(
                "Your sign-in or protected-owner access changed. Cancel the till change and sign in again.",
            )
        }
        if (!connectivity.networkValidated.value) {
            throw TerminalScopeException(
                "Changing tills requires a live server check. Reconnect, then choose the till again.",
            )
        }

        val firstRows = verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }
        val openShifts = shiftApi.shifts(onlyOpen = true, limit = 200)
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }
        val sessions = gamingApi.sessions(unbilledOnly = true, limit = 500)
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }
        val finalRows = verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }

        val currentAvailable = firstRows.any { it.id == previous.terminalId } &&
            finalRows.any { it.id == previous.terminalId }
        val alternatives = finalRows.filter { it.id != previous.terminalId }
        val localShiftCount = db.shiftCloseSafetyDao()
            .currentShiftCountForTerminal(previous.terminalId)
        val localGamingCount = db.gamingDao().localSessionOverlaysForAlarms().size
        val outboxCount = outboxSafety.snapshot().count
        val serverShiftCount = openShifts.count { row ->
            row.branchId != branchId ||
                row.status != "open" ||
                row.terminalId == null ||
                row.terminalId == previous.terminalId
        }
        val serverGamingCount = sessions.count {
            gamingBlocksTillReassignment(it.status, it.orderId)
        } + if (sessions.size >= 500 && sessions.none {
                gamingBlocksTillReassignment(it.status, it.orderId)
            }
        ) 1 else 0
        requireReassignmentAllowed(
            TerminalReassignmentFacts(
                protectedOwner = pending.me.protectedAccess,
                tokenLineageCurrent = pending.isTokenCurrent(),
                online = connectivity.networkValidated.value,
                activeTerminalExact =
                    terminals.terminalId() == previous.terminalId &&
                        previous.branchId == branchId,
                currentTerminalStillAvailable = currentAvailable,
                unresolvedOutboxCount = outboxCount,
                localShiftWorkflowCount = localShiftCount,
                localGamingWorkflowCount = localGamingCount,
                serverOpenShiftCount = serverShiftCount,
                serverGamingBlockerCount = serverGamingCount,
                alternativeTillCount = alternatives.size,
            ),
        )
        return alternatives
    }

    fun cancelTerminalReassignment() {
        val visible = _state.value as? AuthState.SelectTerminal ?: return
        val pending = pendingTerminalSession ?: return
        val previous = pending.previousTerminal ?: return
        if (!visible.reassigning || visible.choosing) return
        _state.value = visible.copy(choosing = true, error = null)
        viewModelScope.launch {
            try {
                if (!pending.isTokenCurrent()) {
                    throw TerminalScopeException(
                        "Your sign-in changed while choosing a till. Sign in again to reopen the tablet.",
                    )
                }
                cacheIsolation.activateCached(scopeFor(pending.me, previous.terminalId))
                sync.clearSessionFeedback()
                ApiClient.activateTerminalScope(previous.terminalId)
                if (!terminals.activateCachedValidated(previous.terminalId, previous.branchId)) {
                    deactivateTerminalRuntime()
                    cacheIsolation.deactivate()
                    throw TerminalScopeException(
                        "The previous till label could not be verified. Reconnect and refresh the till list.",
                    )
                }
                pendingTerminalSession = null
                _state.value = AuthState.SignedIn(pending.me)
                sync.requestSync()
                realtime.connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CacheScopeException) {
                updateTerminalSelectionError(
                    error.message ?: "The previous till could not be reopened safely. Try again.",
                )
            } catch (error: TerminalScopeException) {
                updateTerminalSelectionError(error.message ?: "The previous till could not be reopened.")
            } catch (_: Exception) {
                updateTerminalSelectionError(
                    "The previous till could not be reopened safely. Refresh the till list or ask support.",
                )
            } finally {
                val current = _state.value
                if (current is AuthState.SelectTerminal && current.choosing) {
                    _state.value = current.copy(choosing = false)
                }
            }
        }
    }

    private fun PendingTerminalSession.isTokenCurrent(): Boolean = when {
        installedLogin != null -> tokens.isCurrent(installedLogin)
        restoredSession != null -> tokens.currentAccessFor(restoredSession) != null
        else -> false
    }

    private fun updateTerminalSelectionError(message: String) {
        val current = _state.value as? AuthState.SelectTerminal ?: return
        _state.value = current.copy(choosing = false, error = message)
    }

    private fun verifiedBranchTerminals(rows: List<Terminal>, branchId: String): List<Terminal> = rows
        .filter { it.branchId == branchId && it.id.isNotBlank() }
        .distinctBy(Terminal::id)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Terminal::name).thenBy(Terminal::id))

    private fun terminalSelectionError(error: ApiException): String = when {
        error.status == null ->
            "The server could not be reached. Reconnect, then try the till selection again."
        error.status == 401 || error.status == 403 ->
            "This sign-in is no longer authorised. Sign in again before choosing a till."
        else -> error.message?.takeIf(String::isNotBlank)
            ?: "The available tills could not be verified. Try again."
    }

    private fun cachedScope(me: MeResponse): CacheScope {
        val terminalId = if (EffectivePermissions.from(me).requiresOperationalWorkspace()) {
            terminals.terminalId() ?: throw TerminalScopeException(
                "Reconnect once to verify this tablet's workspace before opening saved data.",
            )
        } else {
            null
        }
        return scopeFor(me, terminalId)
    }

    private fun scopeFor(me: MeResponse, terminalId: String?): CacheScope = CacheScope(
        userId = me.userId.trim(),
        companyId = me.companyId.trim(),
        branchId = me.branchId?.trim()?.takeIf(String::isNotEmpty),
        terminalId = terminalId?.trim()?.takeIf(String::isNotEmpty),
    )

    /** Cancel persisted alarms from the purged workspace before B is rendered. */
    private suspend fun activateValidatedScope(scope: CacheScope) {
        terminals.deactivateValidatedDisplay()
        val activation = cacheIsolation.activateValidated(scope)
        sync.clearSessionFeedback()
        ApiClient.activateTerminalScope(scope.terminalId)
        if (activation == CacheScopeActivation.PURGED) {
            // A route is scoped to the account/branch/till that issued its
            // notification. Never redirect a different employee after purge.
            (getApplication() as DCompanyApp).notificationRoutes.clearAllForScopeChange()
            // AlarmManager is outside Room. Reconciliation against the now-empty
            // caches removes A's tags; an alarm-service failure must not
            // roll back an already committed cache scope transition.
            reconcileOperationalAlarms()
        } else {
            reconcileOperationalAlarms()
        }
    }

    private suspend fun reconcileOperationalAlarms() {
        runCatching { GamingAlarmReconciler.reconcile(getApplication()) }
        runCatching { HeldOrderAlarmReconciler.reconcile(getApplication()) }
    }

    /** Header authority and its human-readable label always change together. */
    private fun deactivateTerminalRuntime() {
        ApiClient.deactivateTerminalScope()
        terminals.deactivateValidatedDisplay()
    }

    /**
     * Do not expose an Activity-scoped POS ViewModel with another account's
     * stale adopted cache. A successful sign-in is online, so clear only the
     * replaceable server read cache and await one terminal-scoped pull before
     * rendering the workspace. Local open/close outbox legs are untouched.
     */
    private suspend fun refreshShiftAuthorityAtLogin() {
        if (terminals.terminalId() == null) return
        // SyncEngine orders the clear and authoritative pull behind the same
        // shift-resource lock used by realtime refresh and shift recovery.
        // An older GET therefore cannot repopulate stale authority between
        // this login reset and its replacement read.
        sync.refreshShiftAuthorityAtLogin()
    }

    fun signOut() {
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        viewModelScope.launch {
            val decision = try {
                cacheIsolation.deactivateAfterOutboxGate {
                    outboxSafety.canSignOut()
                }
            } catch (_: Exception) {
                OutboxGateResult.Blocked(
                    "Sign-out was blocked because the app could not verify whether saved work is synced. " +
                        "Check the connection and try again.",
                )
            }
            _loginError.value = loginErrorAfterSignOutDecision(_loginError.value, decision)
            if (decision is OutboxGateResult.Blocked) {
                outboxSafety.publishNotice(decision.message)
                return@launch
            }
            // Dispose the authenticated feature tree before any IO-backed
            // credential cleanup suspends. Otherwise the old workspace stays
            // tappable with an already-deactivated cache lease and actions can
            // appear to do nothing during sign-out.
            _state.value = AuthState.SigningOut
            sync.clearSessionFeedback()
            deactivateTerminalRuntime()
            realtime.disconnect()
            PricingLock.lock()
            restoreJob?.cancel()
            pendingTerminalSession = null
            _accessChangeNotice.value = null
            cancelOperationalAlarms()
            (getApplication() as DCompanyApp).notificationRoutes.clearPending()
            try {
                withContext(Dispatchers.IO) { tokens.clear() }
            } catch (_: Exception) {
                _state.value = AuthState.SignOutFailed(
                    "This tablet could not durably remove the secure session. Keep this screen open, " +
                        "retry sign-out, and do not hand the tablet to another employee until it succeeds.",
                )
                return@launch
            }
            // Must also drop the cached profile: leaving it would let the next
            // cold start reopen the till as the staff member who just signed out.
            try {
                cache.rememberProfile(null)
            } catch (_: Exception) {
                // Credentials are already gone, so sign-out itself succeeded.
                // Surface the local cleanup problem on Login rather than
                // leaving the employee trapped behind an endless spinner.
                _loginError.value =
                    "Signed out, but this tablet could not clear its saved display profile. " +
                        "Restart the app before the next employee signs in."
            }
            _state.value = AuthState.SignedOut
        }
    }

    /**
     * A successful password reset increments the server auth version, so the
     * current bearer is already revoked. Leave the operational workspace
     * immediately instead of waiting for a later API call to discover that
     * fact. Saved outbox ownership is deliberately retained so only this same
     * employee can sign back in and finish any queued work.
     */
    fun expireAfterPasswordChange() {
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        _state.value = AuthState.SigningOut
        sync.clearSessionFeedback()
        deactivateTerminalRuntime()
        realtime.disconnect()
        PricingLock.lock()
        restoreJob?.cancel()
        pendingTerminalSession = null
        _accessChangeNotice.value = null
        cancelOperationalAlarms()
        (getApplication() as DCompanyApp).notificationRoutes.clearPending()
        viewModelScope.launch {
            var cleanupWarning: String? = null
            try {
                cacheIsolation.deactivate()
            } catch (_: Exception) {
                cleanupWarning =
                    "The previous workspace could not be fully deactivated; restart the app before another employee signs in."
            }
            try {
                withContext(Dispatchers.IO) { tokens.clear() }
            } catch (_: Exception) {
                _state.value = AuthState.SignOutFailed(
                    "Your password changed, but this tablet could not durably clear the old secure session. " +
                        "Keep this screen open and restart the app before anyone else uses it.",
                )
                return@launch
            }
            try {
                cache.rememberProfile(null)
            } catch (_: Exception) {
                cleanupWarning =
                    "The saved display profile could not be cleared; restart the app before another employee signs in."
            }
            _loginError.value = buildString {
                append("Password updated. Sign in again with the new password to continue.")
                cleanupWarning?.let { append(" ").append(it) }
            }
            _state.value = AuthState.SignedOut
            refreshSignedOutSafetyNotice()
        }
    }

    fun dismissAccountSafetyNotice() = outboxSafety.clearNotice()

    fun dismissAccessChangeNotice() {
        _accessChangeNotice.value = null
    }

    /** Fail closed before exposing any feature ViewModel that can enqueue a write. */
    private suspend fun acceptAuthenticated(
        me: MeResponse,
        installedLogin: LoginSessionLease? = null,
        restoredSession: SessionRefreshLease? = null,
    ): Boolean {
        check(installedLogin == null || restoredSession == null) {
            "An authenticated session cannot be both a new login and a restore"
        }
        val decision = outboxSafety.bindAuthenticated(OutboxOwnerIdentity.from(me))
        if (decision is OutboxGateResult.Allowed) {
            // Old installs could already have an offline open leg before
            // opener metadata existed. The ownership gate above proves this
            // identity owns that unresolved row; fill only missing metadata,
            // never its state/idempotency/close fields.
            val scopeLease = cacheIsolation.currentLease() ?: return false
            if (!cacheIsolation.commitIfCurrent(scopeLease) {
                    db.shiftDao().attributeLegacyPendingOpen(
                        userId = me.userId,
                        name = me.name,
                        email = me.email,
                        terminalId = terminals.terminalId(),
                        branchId = me.branchId,
                    )
                }
            ) return false
            return true
        }

        decision as OutboxGateResult.Blocked
        if (installedLogin == null) {
            if (restoredSession == null) {
                withContext(Dispatchers.IO) { tokens.clear() }
                PricingLock.lock()
                deactivateTerminalRuntime()
                cache.rememberProfile(null)
                _state.value = AuthState.SignedOut
                realtime.disconnect()
            } else {
                rejectRestoredSession(restoredSession, decision.message)
            }
        } else {
            rollbackFailedLogin(installedLogin)
        }
        _loginError.value = decision.message
        return false
    }

    /** Clear only this failed login; a newer explicit login always wins. */
    private suspend fun rollbackFailedLogin(installedLogin: LoginSessionLease?): Boolean {
        if (
            installedLogin == null ||
            !withContext(Dispatchers.IO) { tokens.rollbackLoginIfCurrent(installedLogin) }
        ) return false
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        cacheIsolation.deactivate()
        sync.clearSessionFeedback()
        deactivateTerminalRuntime()
        cancelOperationalAlarms()
        cache.rememberProfile(null)
        _state.value = AuthState.SignedOut
        realtime.disconnect()
        return true
    }

    /**
     * Remove only the session lineage this restore started with. A late server
     * result can therefore never clear a newer explicit login. Token refresh
     * may have rotated the exact snapshot, so capture the current lease only
     * after proving it still belongs to the original lineage.
     */
    private suspend fun rejectRestoredSession(
        restoreLease: SessionRefreshLease,
        message: String?,
    ) {
        val activeAccess = tokens.currentAccessFor(restoreLease)
        if (activeAccess != null) {
            val current = tokens.refreshLease()
            if (current != null && current.accessToken == activeAccess) {
                withContext(Dispatchers.IO) { tokens.clearIfCurrent(current) }
            }
        }
        if (tokens.hasSession()) return

        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        PricingLock.lock()
        deactivateTerminalRuntime()
        cache.rememberProfile(null)
        cacheIsolation.deactivate()
        sync.clearSessionFeedback()
        cancelOperationalAlarms()
        message?.let {
            _loginError.value = it
            outboxSafety.publishNotice(it)
        }
        _state.value = AuthState.SignedOut
        realtime.disconnect()
        refreshSignedOutSafetyNotice()
    }

    private suspend fun refreshSignedOutSafetyNotice() {
        try {
            outboxSafety.canSync()
        } catch (_: Exception) {
            outboxSafety.publishNotice(
                "Could not verify this tablet's saved work. Keep it offline and ask support before switching users.",
            )
        }
    }

    private suspend fun blockWorkspace(message: String) {
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        PricingLock.lock()
        deactivateTerminalRuntime()
        cacheIsolation.deactivate()
        sync.clearSessionFeedback()
        cancelOperationalAlarms()
        realtime.disconnect()
        _state.value = AuthState.Blocked(message)
    }

    private fun cancelOperationalAlarms() {
        OperationalAlarmRegistry.cancelAll(getApplication())
        (getApplication() as DCompanyApp).notificationRoutes.clearPending()
    }

    private companion object {
        const val RESTORE_SERVER_TIMEOUT_MILLIS = 8_000L
    }

    override fun onCleared() {
        authorityRefresh.cancel()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        super.onCleared()
    }
}

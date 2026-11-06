package cloud.dcompany.erp.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.PersistedStartupFailure
import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.alarm.OperationalAlarmRegistry
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.CachedScopeLeaseAdoption
import cloud.dcompany.erp.core.auth.CacheScopeActivation
import cloud.dcompany.erp.core.auth.CacheScopeException
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.LoginSessionLease
import cloud.dcompany.erp.core.auth.OutboxGateResult
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.PricingLock
import cloud.dcompany.erp.core.auth.SessionLogoutLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.TerminalResolution
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.auth.activateAndRememberTerminal
import cloud.dcompany.erp.core.auth.bestEffortAuthenticatedLogout
import cloud.dcompany.erp.core.auth.resolveTerminalAssignment
import cloud.dcompany.erp.core.handover.AcceptedTillReads
import cloud.dcompany.erp.core.handover.HandoverAction
import cloud.dcompany.erp.core.handover.HandoverAuthority
import cloud.dcompany.erp.core.handover.HandoverCoordinator
import cloud.dcompany.erp.core.handover.HandoverIntent
import cloud.dcompany.erp.core.handover.HandoverStage
import cloud.dcompany.erp.core.handover.HandoverStartupStatus
import cloud.dcompany.erp.core.handover.OriginalOperationBinding
import cloud.dcompany.erp.core.handover.StartupShiftAuthorityException
import cloud.dcompany.erp.core.handover.StartupShiftAuthorityFailure
import cloud.dcompany.erp.core.handover.TillPreparationBlocked
import cloud.dcompany.erp.core.handover.WorkspaceExitAuthority
import cloud.dcompany.erp.core.handover.WorkspaceExitKind
import cloud.dcompany.erp.core.handover.inspectLocalHandoverWork
import cloud.dcompany.erp.core.handover.logoutOriginalWorkspace
import cloud.dcompany.erp.core.handover.WorkspaceSelectorReads
import cloud.dcompany.erp.core.handover.workspaceSelectorReads
import cloud.dcompany.erp.core.errors.RecoveryRisk
import cloud.dcompany.erp.core.errors.recoveryGuidance
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.ErpApi
import cloud.dcompany.erp.core.net.LoginRequest
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.ui.screens.gaming.GamingApi
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
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

private enum class RestoreStage {
    SERVER_PROFILE, IDENTITY_CHECK, TERMINAL_AND_CACHE, OUTBOX_BIND, SAVE_PROFILE,
    SHIFT_AUTHORITY, PUBLISH_WORKSPACE, START_SYNC, START_REALTIME, RECEIPT_REFRESH,
}

private val restoreFrameNames = setOf(
    "SessionViewModel", "HandoverOperations", "HandoverCoordinator", "SyncEngine", "CacheIsolationCoordinator",
    "OutboxSafetyGate", "ShiftCache", "TerminalStore", "TokenStore", "SessionRefreshCoordinator", "ApiClient",
    "CoordinatorOperationDispatchGate", "OperationDispatchInterceptor", "DCompanyApp",
)

private fun logRestoreFailure(stage: RestoreStage, error: Exception) {
    val kind = when (error) {
        is kotlinx.serialization.SerializationException -> "SerializationException"
        is IllegalStateException -> "IllegalStateException"
        is IllegalArgumentException -> "IllegalArgumentException"
        is SecurityException -> "SecurityException"
        is java.io.IOException -> "IOException"
        else -> "OtherException"
    }
    val frame = error.stackTrace.firstOrNull {
        it.className.startsWith("cloud.dcompany.erp.") &&
            it.className.substringAfterLast('.').substringBefore('$') in restoreFrameNames
    }
    val frameName = frame?.className?.substringAfterLast('.')?.substringBefore('$') ?: "UNKNOWN"
    val line = frame?.lineNumber?.takeIf { it > 0 } ?: 0
    val startupAuthority = (error as? StartupShiftAuthorityException)?.stage?.name
    Log.i("DCompanyRestore", "stage=${stage.name} kind=$kind frame=$frameName line=$line" +
        if (startupAuthority == null) "" else " startupAuthority=$startupAuthority")
}

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
    data class SignedIn(
        val me: MeResponse,
        /** Nonsecret, process-local proof for a newly completed explicit login. */
        val freshLoginNonce: Long? = null,
    ) : AuthState
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
        else -> recoveryGuidance(
            risk = RecoveryRisk.AUTH,
            failure = error,
            subject = "sign-in",
        ).message
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

/**
 * Authority refresh is allowed to publish only into the exact SignedIn object
 * that launched it. Owner equality is insufficient because a same employee's
 * later explicit login has a different token lineage and fresh-login nonce.
 */
internal fun authorityRefreshMayPublish(
    expected: AuthState.SignedIn,
    current: AuthState,
    refreshLeaseCurrent: Boolean,
): Boolean = refreshLeaseCurrent && current === expected

/** Called synchronously under the exact cache and login ownership checks. */
internal fun publishVerifiedStartupState(
    ownsAttempt: Boolean,
    original: OriginalOperationBinding?,
    lease: CacheScopeLease,
    login: SessionRefreshLease,
    requireOrdinaryReadiness: () -> Unit,
    publish: () -> Unit,
): Boolean {
    if (!ownsAttempt) return false
    check(original == null || (
        original.cacheLease == lease &&
            original.loginLease.session.lineage === login.session.lineage &&
            original.isCurrent()
        )) { "The verified login and till changed before opening the workspace." }
    requireOrdinaryReadiness()
    publish()
    return true
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
    val selectorAuthority: WorkspaceExitAuthority? = null,
    val selectorExit: HandoverCoordinator.WorkspaceExit? = null,
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

    private val dCompanyApp = app as DCompanyApp
    private val tokens = dCompanyApp.tokens
    private val cache = dCompanyApp.shiftCache
    private val terminals = dCompanyApp.terminalStore
    private val realtime = dCompanyApp.realtime
    private val sync = dCompanyApp.sync
    private val connectivity = dCompanyApp.connectivity
    private val db = dCompanyApp.db
    private val outboxSafety = dCompanyApp.outboxSafety
    private val cacheIsolation = dCompanyApp.cacheIsolation
    private data class PendingWorkspaceExit(val authority: WorkspaceExitAuthority, val cap: HandoverCoordinator.WorkspaceExit)
    private var pendingLogoutExit: PendingWorkspaceExit? = null
    private var pendingLogoutRemoval: SessionLogoutLease? = null
    private var pendingLogoutProfile: String? = null
    private var pendingLogoutOwnsProfile = false
    private suspend fun publishOriginalLogout(authority: WorkspaceExitAuthority, removal: SessionLogoutLease,
        publish: () -> Unit): Boolean = cacheIsolation.publishIfInactiveGeneration(authority.originalGeneration + 1) {
        tokens.publishIfCurrentLogout(removal, publish)
    }
    private var signOutJob: Job? = null
    private fun captureWorkspaceExit(): WorkspaceExitAuthority {
        val lease = cacheIsolation.currentLease()
        val me = (_state.value as? AuthState.SignedIn)?.me
        val original = if (lease == null) null else
            checkNotNull(dCompanyApp.handoverOperations.captureOriginal(me, lease)) { "The original workspace needs recovery before leaving." }
        return WorkspaceExitAuthority(original, tokens.refreshLease(), lease, cacheIsolation.currentGeneration(),
            tokens::refreshLease, tokens::currentAccessFor, cacheIsolation::currentLease, cacheIsolation::currentGeneration)
    }
    private suspend fun closeWorkspaceAdmission(authority: WorkspaceExitAuthority, kind: WorkspaceExitKind): PendingWorkspaceExit {
        val cap = dCompanyApp.handoverOperations.beginWorkspaceExit(authority.original, kind, authority::beforeDeactivation)
        return PendingWorkspaceExit(authority, cap)
    }
    private suspend fun exitLifetimesDrained(exit: PendingWorkspaceExit): Boolean =
        dCompanyApp.handoverOperations.coordinator.awaitWorkspaceExitDrained(exit.cap, 15_000) &&
            (exit.authority.original?.let { sync.awaitWorkspaceExitWorkers(it, exit.cap) } ?: true)
    private suspend fun privateExitWorkClear(exit: PendingWorkspaceExit): Boolean =
        inspectLocalHandoverWork(dCompanyApp).isClear &&
            (exit.authority.original?.let { dCompanyApp.idleCheckoutClaims.snapshot(it.authority.scope).isEmpty() } ?: true)

    private val shiftApi = ApiClient.create<ShiftApi>()
    private val gamingApi = ApiClient.create<GamingApi>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _tillTakeover = MutableStateFlow<TillTakeoverUi?>(null)
    internal val tillTakeover = _tillTakeover.asStateFlow()
    private var tillTakeoverJob: Job? = null
    private var tillSelection: OriginalOperationBinding? = null
    private val handovers get() = dCompanyApp.handoverOperations
    private val tillTransport get() = dCompanyApp.tillHandoverTransport

    internal fun showTillTakeover(shiftId: String) {
        if (tillTakeoverJob?.isActive == true) return
        val signedIn = _state.value as? AuthState.SignedIn ?: return
        if (!connectivity.online.value || !EffectivePermissions.from(signedIn.me).has(ErpPermission.PosWrite)) return
        val original = handovers.captureOriginal(signedIn.me, cacheIsolation.currentLease()) ?: return
        tillSelection = original
        val display = TillTakeoverUi(shiftId, busy = true)
        _tillTakeover.value = display
        loadTillCandidates(original, display)
    }

    internal fun moreTillCandidates() {
        val visible = _tillTakeover.value ?: return
        val original = tillSelection ?: return
        if (visible.busy || visible.intent != null || visible.nextCursor == null) return
        val display = visible.copy(busy = true, error = null)
        _tillTakeover.value = display
        loadTillCandidates(original, display)
    }

    private fun loadTillCandidates(original: OriginalOperationBinding, display: TillTakeoverUi) {
        tillTakeoverJob = viewModelScope.launch {
            try {
                val (revision, candidates) = tillTransport.choices(original, display.shiftId, display.nextCursor)
                check(display.revision == null || display.revision == revision)
                check(candidates.items.none { person -> display.people.any { it.id == person.id } })
                if (_tillTakeover.value === display && original.isCurrent()) {
                    _tillTakeover.value = display.copy(revision = revision, busy = false,
                        people = display.people + candidates.items, nextCursor = candidates.nextCursor)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_tillTakeover.value === display && original.isCurrent()) {
                    _tillTakeover.value = display.copy(busy = false, error = "The current till and eligible staff could not be verified.")
                }
            }
        }
    }

    internal fun dismissTillTakeover() {
        if (_tillTakeover.value?.intent != null || tillTakeoverJob?.isActive == true) return
        tillSelection = null
        _tillTakeover.value = null
    }

    internal fun confirmTillTakeover(display: TillTakeoverUi, selected: String, password: String) {
        val original = tillSelection ?: return
        val revision = display.revision ?: return
        if (_tillTakeover.value !== display || display.busy || display.intent != null ||
            display.people.none { it.id == selected } || !original.isCurrent()) return
        val action = HandoverAction(UUID.randomUUID().toString(), original.authority, display.shiftId, selected, revision)
        runTillAction(display) { tillTransport.submit(original, action, password, approved = true) }
    }

    internal fun recoverTillTakeover(display: TillTakeoverUi, password: String?) {
        val expected = display.intent ?: return
        if (expected.stage == HandoverStage.ACCEPTED || (expected.stage == HandoverStage.NOT_SENT && password == null)) return
        runTillAction(display) {
            val original = recoverOriginalTill(expected)
            when {
                expected.stage == HandoverStage.NOT_SENT ->
                    tillTransport.resumeNotSent(original, expected, requireNotNull(password), approved = true)
                password == null -> tillTransport.receipt(original, expected)
                else -> tillTransport.retry(original, expected, password, approved = true)
            }
        }
    }

    internal fun cancelUnsentTillTakeover(display: TillTakeoverUi) {
        val expected = display.intent?.takeIf { it.stage == HandoverStage.NOT_SENT } ?: return
        if (_tillTakeover.value !== display || display.busy || tillTakeoverJob?.isActive == true) return
        val working = display.copy(busy = true, error = null)
        _tillTakeover.value = working
        tillTakeoverJob = viewModelScope.launch {
            try {
                val original = recoverOriginalTill(expected)
                check(dCompanyApp.handoverDomains.inspect(original, expected) is
                    cloud.dcompany.erp.core.handover.OriginalDomainCheck.Clear)
                check(original.isCurrent())
                handovers.coordinator.cancelNotSent(original.authority, expected.action.key)
                _tillTakeover.value = null
                restore()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_tillTakeover.value === working) showRetainedTill(expected, "The original cashier’s saved work still needs verification.")
            }
        }
    }

    internal fun verifyOriginalTillLogin(display: TillTakeoverUi, email: String, password: String) {
        val expected = display.intent?.takeIf { it.stage != HandoverStage.ACCEPTED } ?: return
        if (_tillTakeover.value !== display || display.busy || tillTakeoverJob?.isActive == true ||
            email.isBlank() || password.isEmpty()) return
        val working = display.copy(busy = true, error = null)
        _tillTakeover.value = working
        tillTakeoverJob = viewModelScope.launch {
            try {
                val previous = tokens.refreshLease()
                val expectedScope = expected.action.origin.scope
                fun stillCurrent() = retainedTill() == expected && connectivity.online.value &&
                    (if (previous == null) tokens.refreshLease() == null else tokens.currentAccessFor(previous) != null)
                val guard = { check(stillCurrent()) }
                val api = ApiClient.createEphemeralAuthorityApi<ErpApi>(beforeDispatch = guard, singleAttempt = true)
                val pair = api.login(LoginRequest(email.trim().lowercase(), password))
                val signedIn = ApiClient.createEphemeralAuthorityApi<ErpApi>(accessToken = pair.accessToken,
                    beforeDispatch = guard, singleAttempt = true)
                val original = signedIn.me()
                check(AccessTokenIdentityParser.parse(pair.accessToken) == OutboxOwnerIdentity.from(original) &&
                    original.companyId == expectedScope.companyId && original.userId == expectedScope.actorId &&
                    (original.branchId == null || original.branchId == expectedScope.branchId))
                val terminal = verifiedHandoverTerminal(signedIn, requireNotNull(expectedScope.branchId),
                    requireNotNull(expectedScope.terminalId))
                check(stillCurrent())
                val adoption = cacheIsolation.adoptCachedOnlyIfInactive(scopeFor(original, terminal.id))
                val lease = (adoption as? CachedScopeLeaseAdoption.Ready)?.lease ?: error("The original till cache changed.")
                check(cacheIsolation.commitIfCurrent(lease) {
                    check(stillCurrent())
                    withContext(Dispatchers.IO) {
                        check(tokens.commitIfOriginalLoginCurrent(previous) {
                            tokens.installForLogin(pair.accessToken, pair.refreshToken)
                        })
                    }
                    cache.rememberProfile(json.encodeToString(MeResponse.serializer(), original))
                    terminals.rememberValidated(terminal)
                })
                if (_tillTakeover.value === working) showRetainedTill(expected, "Original cashier verified. Continue checking this saved handover.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_tillTakeover.value === working) showRetainedTill(expected, "Use the original cashier’s sign-in for this exact till.")
            }
        }
    }

    private fun retainedTill(): HandoverIntent? =
        (handovers.status() as? HandoverStartupStatus.RecoveryRequired)?.intent

    private fun runTillAction(display: TillTakeoverUi, run: suspend () -> HandoverIntent) {
        if (_tillTakeover.value !== display || display.busy || tillTakeoverJob?.isActive == true) return
        val working = display.copy(busy = true, error = null)
        _tillTakeover.value = working
        tillTakeoverJob = viewModelScope.launch {
            try {
                val next = run()
                if (_tillTakeover.value === working) showRetainedTill(next)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (BuildConfig.DEBUG && error is TillPreparationBlocked) {
                    Log.i("DCompanyTill", error.evidence)
                }
                if (_tillTakeover.value !== working) return@launch
                val retained = retainedTill() ?: handovers.coordinator.state.value.intent
                if (retained != null) showRetainedTill(retained, "This handover still needs verification. The saved action was kept.")
                else _tillTakeover.value = display.copy(error = (error as? TillPreparationBlocked)?.message
                    ?: "The tablet could not verify this till for handover. Refresh Shift and retry.")
            }
        }
    }

    private fun showRetainedTill(intent: HandoverIntent, message: String? = null) {
        tillSelection = null
        _tillTakeover.value = TillTakeoverUi(intent.action.shiftId, intent = intent, error = message)
        _state.value = AuthState.Blocked("Complete the retained till handover before using this tablet.")
        realtime.disconnect()
    }

    private suspend fun recoverOriginalTill(expected: HandoverIntent): OriginalOperationBinding {
        check(retainedTill() == expected && connectivity.online.value)
        val scope = expected.action.origin.scope
        val login = checkNotNull(tokens.refreshLease())
        val bearer = checkNotNull(tokens.currentAccessFor(login))
        val originalApi = ApiClient.createEphemeralAuthorityApi<ErpApi>(
            accessToken = bearer, singleAttempt = true, beforeDispatch = {
                check(retainedTill() == expected && tokens.currentAccessFor(login) == bearer)
            })
        val me = originalApi.me()
        check(AccessTokenIdentityParser.parse(tokens.currentAccessFor(login).orEmpty()) == OutboxOwnerIdentity.from(me) &&
            me.companyId == scope.companyId && me.userId == scope.actorId &&
            (me.branchId == null || me.branchId == scope.branchId))
        val branchId = requireNotNull(scope.branchId)
        val terminal = verifiedHandoverTerminal(originalApi, branchId, requireNotNull(scope.terminalId))
        fun originalIsCurrent() = retainedTill() == expected && tokens.currentAccessFor(login) != null
        check(originalIsCurrent())
        val adoption = cacheIsolation.adoptCachedOnlyIfInactive(scopeFor(me, terminal.id))
        val lease = (adoption as? CachedScopeLeaseAdoption.Ready)?.lease ?: error("The original till cache could not be verified.")
        check(cacheIsolation.commitIfCurrent(lease) {
            check(originalIsCurrent())
            terminals.rememberValidated(terminal)
            check(originalIsCurrent())
            cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
        })
        return checkNotNull(if (expected.stage == HandoverStage.NOT_SENT) handovers.captureForRecovery(me, lease, expected)
            else handovers.captureForSentHandover(me, lease, expected))
    }

    private suspend fun verifiedHandoverTerminal(api: ErpApi, branchId: String, terminalId: String): Terminal {
        val available = api.terminals(branchId).filter { it.branchId == branchId }
        if (WorkspaceFeatureProfiles.Active.singleHybridTerminalOnly) {
            check(available.size == 1 && available.single().purpose == TerminalPurpose.HYBRID)
        }
        return available.single { it.id == terminalId }
    }

    internal fun activateIncomingTill(display: TillTakeoverUi, email: String, password: String, ownerReturned: Boolean) {
        val expected = display.intent?.takeIf { it.stage == HandoverStage.ACCEPTED } ?: return
        if (_tillTakeover.value !== display || display.busy || tillTakeoverJob?.isActive == true ||
            email.isBlank() || password.isEmpty()) return
        val working = display.copy(busy = true, error = null)
        _tillTakeover.value = working
        tillTakeoverJob = viewModelScope.launch {
            try {
                finishIncomingTill(expected, email, password, ownerReturned)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_tillTakeover.value === working) {
                    val message = if (ownerReturned) "The Owner’s linked return or original cashier could not be verified. Keep this handover and check the Owner’s recovery receipt."
                        else "The incoming operator or running till could not be verified. Sign in again to continue, or ask an Owner to recover this till."
                    showRetainedTill(expected, message)
                }
            }
        }
    }

    private suspend fun finishIncomingTill(expected: HandoverIntent, email: String, password: String, ownerReturned: Boolean) {
        val receipt = checkNotNull(expected.receipt)
        val expectedActor = if (ownerReturned) receipt.initiatedBy else receipt.incomingUserId
        fun verifyState(state: cloud.dcompany.erp.core.handover.IncomingTillState) {
            if (ownerReturned) state.verifyOwnerReturn(expected) else state.verify(expected)
        }
        val previous = tokens.refreshLease()
        val previousLease = cacheIsolation.currentLease()
        val generation = cacheIsolation.currentGeneration()
        fun originalIsCurrent() = retainedTill() == expected && connectivity.online.value &&
            (if (previous == null) tokens.refreshLease() == null else tokens.currentAccessFor(previous) != null)
        check(originalIsCurrent())
        if (previous != null) {
            val identity = checkNotNull(AccessTokenIdentityParser.parse(previous.accessToken))
            check(identity.companyId == receipt.companyId && identity.userId in setOf(receipt.initiatedBy, receipt.incomingUserId))
        }
        previousLease?.scope?.let {
            check(it.companyId == receipt.companyId && it.userId in setOf(receipt.initiatedBy, receipt.incomingUserId) &&
                (it.branchId == null || it.branchId == receipt.branchId) && it.terminalId == receipt.terminalId)
        }
        val verifyOriginal = {
            check(originalIsCurrent() && cacheIsolation.currentLease() == previousLease &&
                cacheIsolation.currentGeneration() == generation)
        }
        val unauthenticated = ApiClient.createEphemeralAuthorityApi<ErpApi>(
            singleAttempt = true, beforeDispatch = verifyOriginal)
        val pair = unauthenticated.login(LoginRequest(email.trim().lowercase(), password))
        val verifiedApi = ApiClient.createEphemeralAuthorityApi<ErpApi>(
            accessToken = pair.accessToken, singleAttempt = true, beforeDispatch = verifyOriginal)
        val me = verifiedApi.me()
        check(AccessTokenIdentityParser.parse(pair.accessToken) == OutboxOwnerIdentity.from(me) &&
            me.userId == expectedActor && me.companyId == receipt.companyId &&
            (me.branchId == null || me.branchId == receipt.branchId) && EffectivePermissions.from(me).has(ErpPermission.PosWrite))
        val terminal = verifiedHandoverTerminal(verifiedApi, receipt.branchId, receipt.terminalId)
        val canReadGaming = EffectivePermissions.from(me).has(ErpPermission.GamingRead)
        val before = AcceptedTillReads(receipt, pair.accessToken, canReadGaming) {
            originalIsCurrent() && cacheIsolation.currentLease() == previousLease && cacheIsolation.currentGeneration() == generation
        }.till.state(receipt.shiftId).also(::verifyState)
        check(canReadGaming || !before.hasRunningGames)
        check(inspectLocalHandoverWork(dCompanyApp).isClear && outboxSafety.canInstallSession(pair.accessToken) is OutboxGateResult.Allowed)
        val incomingScope = scopeFor(me, receipt.terminalId)
        val lease = cacheIsolation.activateValidatedForHandover(incomingScope, previousLease, generation, ::originalIsCurrent)
        sync.clearSessionFeedback()
        var installed: LoginSessionLease? = null
        withContext(Dispatchers.IO) {
            check(cacheIsolation.currentLease() == lease && tokens.commitIfOriginalLoginCurrent(previous) {
                check(originalIsCurrent())
                installed = tokens.installForLogin(pair.accessToken, pair.refreshToken)
            })
        }
        val login = checkNotNull(installed)
        fun incomingIsCurrent() = retainedTill() == expected && connectivity.online.value &&
            cacheIsolation.currentLease() == lease && tokens.isCurrent(login)
        check(cacheIsolation.commitIfCurrent(lease) {
            check(incomingIsCurrent())
            check(outboxSafety.bindAuthenticated(OutboxOwnerIdentity.from(me)) is OutboxGateResult.Allowed)
            check(incomingIsCurrent())
            cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
            check(incomingIsCurrent())
            terminals.rememberValidated(terminal)
        })
        val reads = AcceptedTillReads(receipt, pair.accessToken, canReadGaming, ::incomingIsCurrent)
        sync.hydrateAcceptedTill(lease, receipt.shiftId, receipt.branchId, reads.shifts, reads.gaming.takeIf { canReadGaming },
            requireRunningGame = before.hasRunningGames, stillCurrent = ::incomingIsCurrent)
        val current = reads.till.state(receipt.shiftId).also(::verifyState)
        check(current.hasRunningGames == before.hasRunningGames && (canReadGaming || !current.hasRunningGames))
        check(inspectLocalHandoverWork(dCompanyApp).isClear)
        val authority = HandoverAuthority(expected.action.origin.scope.copy(actorId = me.userId), 1)
        handovers.initializeAcceptedRecovery(expected, authority, ::incomingIsCurrent)
        withContext(NonCancellable) {
            handovers.coordinator.completeAccepted(expected, ::incomingIsCurrent)
            check(cacheIsolation.commitIfCurrent(lease) {
                check(tokens.isCurrent(login))
                ApiClient.activateTerminalScope(receipt.terminalId)
                _tillTakeover.value = null
                _state.value = AuthState.SignedIn(me)
            })
        }
        PricingLock.lock()
        dCompanyApp.onVerifiedScopeAvailable()
        reconcileOperationalAlarms()
        realtime.connect()
        sync.requestSync()
    }

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
    private var persistedStartupRestoreAttempted = false
    private var terminalChangeJob: Job? = null
    private val terminalChangeGate = TerminalReassignmentRequestGate()
    private var pendingTerminalSession: PendingTerminalSession? = null
    private val freshLoginNonce = AtomicLong(0L)
    private val freshLoginHandoff = FreshLoginHandoffCoordinator(
        nextNonce = {
            freshLoginNonce.updateAndGet { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            }
        },
        isLoginCurrent = tokens::isCurrent,
        currentScopeLease = cacheIsolation::currentLease,
    )
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
            viewModelScope.launch(Dispatchers.Main.immediate) {
                authorityRefresh.cancelPending()
                realtime.disconnect()
                terminalChangeJob?.cancel()
                terminalChangeGate.finish()
                _terminalChange.value = TerminalChangeUiState.Idle
                deactivateTerminalRuntime()
                PricingLock.lock()
                // Serialize state and handoff revocation on the VM dispatcher
                // so an old authority refresh cannot publish SignedIn after
                // forced logout has started.
                pendingTerminalSession = null
                freshLoginHandoff.clear()
                _accessChangeNotice.value = null
                _loginError.value = FORCED_LOGOUT_MESSAGE
                _state.value = AuthState.Loading
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
        val previousState = _state.value as? AuthState.SignedIn ?: return
        val previous = previousState.me
        val lease = tokens.refreshLease() ?: return
        try {
            val refreshed = ApiClient.api.me()
            val activeAccess = tokens.currentAccessFor(lease) ?: return
            if (!authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) return
            if (!canApplyAuthorityProfile(previous, refreshed, activeAccess)) {
                blockWorkspace(
                    "This session could not be safely verified after an access change. " +
                        "Sign in again or ask a manager.",
                )
                return
            }
            if (!authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) return

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

            if (!authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) return
            cache.rememberProfile(json.encodeToString(MeResponse.serializer(), refreshed))
            if (!authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) {
                // Forced logout clears its profile on the serialized VM path.
                // If another SignedIn object already won, never erase that
                // newer login's profile from this stale refresh callback.
                if (_state.value === previousState) cache.rememberProfile(null)
                return
            }
            val preservedNonce = freshLoginHandoff.preserve(
                visibleNonce = previousState.freshLoginNonce,
                previous = previous,
                refreshed = refreshed,
            )
            if (!authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) return
            _state.value = AuthState.SignedIn(refreshed, preservedNonce)
            if (authorityChanged) _accessChangeNotice.value = ACCESS_CHANGED_MESSAGE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (
                (error.status == 401 || error.status == 403) &&
                authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) {
                _loginError.value = FORCED_LOGOUT_MESSAGE
                rejectRestoredSession(lease, message = null)
            }
            // Connectivity failures retain the last verified authority. A
            // reconnect produces another refresh request automatically.
        } catch (error: CacheScopeException) {
            if (authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) {
                blockWorkspace(
                    error.message ?: "The updated access could not safely open this tablet's saved workspace.",
                )
            }
        } catch (error: TerminalScopeException) {
            if (authorityRefreshMayPublish(
                    expected = previousState,
                    current = _state.value,
                    refreshLeaseCurrent = tokens.currentAccessFor(lease) != null,
                )
            ) {
                blockWorkspace(
                    error.message ?: "The updated access could not safely verify this tablet's till.",
                )
            }
        }
    }

    /**
     * A stored token is not proof of a live session, but failing to reach the
     * server is not proof of a dead one either. Only a definitive 401/403
     * signs the user out; anything else surfaces as Unreachable with the
     * credentials intact.
     */
    fun restore() {
        authorityRefresh.cancelPending()
        restoreJob?.cancel()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        freshLoginHandoff.clear()
        deactivateTerminalRuntime()
        _state.value = AuthState.Loading
        restoreJob = viewModelScope.launch {
            val retryStartupRestore = persistedStartupRestoreAttempted
            persistedStartupRestoreAttempted = true
            when (
                val restored = dCompanyApp.awaitPersistedStartupState(
                    retryFailed = retryStartupRestore,
                )
            ) {
                PersistedStartupStateResult.Ready -> Unit
                is PersistedStartupStateResult.Unavailable -> {
                    if (restored.failure == PersistedStartupFailure.HANDOVER_RECOVERY) {
                        retainedTill()?.let {
                            showRetainedTill(it)
                            return@launch
                        }
                    }
                    _state.value = AuthState.Blocked(
                        when (restored.failure) {
                            PersistedStartupFailure.TIMEOUT ->
                                "The tablet took too long to restore its saved sign-in and offline work. " +
                                    "Nothing was changed. Tap Verify again; if this continues, restart the app or contact support."
                            PersistedStartupFailure.HANDOVER_RECOVERY ->
                                "This tablet has a retained till handover that needs reconciliation. The original cashier's work is preserved."
                            PersistedStartupFailure.STORAGE ->
                                "The tablet could not safely restore its saved sign-in and offline work. " +
                                    "Nothing was changed. Tap Verify again or contact support."
                        },
                    )
                    return@launch
                }
            }
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
        val expectedState = _state.value
        var liveProfileVerified = false
        var stage = RestoreStage.SERVER_PROFILE
        try {
            val me = withTimeoutOrNull(RESTORE_SERVER_TIMEOUT_MILLIS) {
                ApiClient.api.me()
            } ?: throw ApiException(
                "The server did not respond in time. Continue offline or try again.",
                code = "network_timeout",
            )

            stage = RestoreStage.IDENTITY_CHECK
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
            stage = RestoreStage.TERMINAL_AND_CACHE
            if (!activateResolvedTerminalOrRequestSelection(
                    me = me,
                    restoredSession = restoreLease,
                )
            ) return
            val verifiedCache = cacheIsolation.currentLease()
            stage = RestoreStage.OUTBOX_BIND
            if (!acceptAuthenticated(me, restoredSession = restoreLease)) return
            if (tokens.currentAccessFor(restoreLease) == null) return
            stage = RestoreStage.SAVE_PROFILE
            cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
            val original = if (EffectivePermissions.from(me).has(ErpPermission.PosRead)) {
                stage = RestoreStage.SHIFT_AUTHORITY
                refreshShiftAuthorityAtLogin(me, restoreLease.session.lineage, verifiedCache)
            } else null
            stage = RestoreStage.PUBLISH_WORKSPACE
            if (!publishStartupWorkspace(restoreLease, verifiedCache, original, AuthState.SignedIn(me)) {
                    _state.value === expectedState
                }
            ) return
            stage = RestoreStage.START_SYNC
            sync.requestSync()
            stage = RestoreStage.START_REALTIME
            realtime.connect()
            stage = RestoreStage.RECEIPT_REFRESH
            refreshReceiptHistoryAfterLogin(me)
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
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) logRestoreFailure(stage, error)
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
        freshLoginHandoff.clear()
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
        val expectedState = _state.value
        authorityRefresh.pauseForLogin()
        // A pricing re-auth belongs to one login lineage, not merely a user id.
        // Relogging as the same owner must require the password again.
        PricingLock.lock()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        pendingTerminalSession = null
        freshLoginHandoff.clear()
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
                when (val startup = dCompanyApp.awaitPersistedStartupState(retryFailed = true)) {
                    PersistedStartupStateResult.Ready -> Unit
                    is PersistedStartupStateResult.Unavailable -> {
                        _loginError.value = if (startup.failure == PersistedStartupFailure.HANDOVER_RECOVERY)
                            "This tablet has a retained till handover. The original cashier's account and queued work are preserved."
                        else "The saved account and work could not be verified. Verify again before signing in."
                        return@launch
                    }
                }
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
                val verifiedCache = cacheIsolation.currentLease()
                if (!acceptAuthenticated(me, installedLogin)) return@launch
                cache.rememberProfile(json.encodeToString(MeResponse.serializer(), me))
                // Alarm readers require token + cached profile + Room lease to
                // agree. Explicit login installs the profile only here, so
                // re-arm any retained unresolved reminders after that commit.
                reconcileOperationalAlarms()
                val original = if (EffectivePermissions.from(me).has(ErpPermission.PosRead)) {
                    refreshShiftAuthorityAtLogin(me, installedLogin.lineage, verifiedCache)
                } else null
                val publicationLogin = tokens.refreshLease()
                    ?.takeIf { it.session.lineage === installedLogin.lineage } ?: return@launch
                val signedIn = freshLoginState(me, installedLogin)
                if (!publishStartupWorkspace(publicationLogin, verifiedCache, original, signedIn) {
                        _state.value === expectedState && tokens.isCurrent(installedLogin)
                    }
                ) return@launch
                sync.requestSync()
                realtime.connect()
                refreshReceiptHistoryAfterLogin(me)
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
                authorityRefresh.resumeAfterLogin(_state.value is AuthState.SignedIn)
            }
        }
    }

    private fun freshLoginState(
        me: MeResponse,
        installedLogin: LoginSessionLease,
    ): AuthState.SignedIn {
        // This presentation is cosmetic, not authentication authority. If an
        // unexpected scope race prevents an exact binding, keep the already
        // accepted session usable and simply omit the one-shot handoff.
        val nonce = freshLoginHandoff.arm(me, installedLogin)
        return AuthState.SignedIn(me = me, freshLoginNonce = nonce)
    }

    /** Continue only the currently visible, exact-login success handoff. */
    fun acknowledgeFreshLoginHandoff(nonce: Long): Boolean {
        val current = _state.value as? AuthState.SignedIn ?: return false
        if (!freshLoginHandoff.consume(current.freshLoginNonce, nonce, current.me)) return false
        _state.value = current.copy(freshLoginNonce = null)
        return true
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
                freshLoginHandoff.clear()
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

        val exitAuthority = captureWorkspaceExit()
        val exit = closeWorkspaceAdmission(exitAuthority, WorkspaceExitKind.TERMINAL_CHANGE)
        try {
            check(exitLifetimesDrained(exit)) { "The original workspace is still finishing saved work." }
            val observed = dCompanyApp.handoverOperations.observeWorkspaceExit(
                requireNotNull(exitAuthority.original), exit.cap,
            ) {
                val firstTerminalRows = verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
                requireCurrentReassignmentSession(me, lease)
                val currentStillAvailable = firstTerminalRows.any {
                    it.id == exactActive.terminalId && it.branchId == exactActive.branchId
                }

                val openShifts = shiftApi.shifts(onlyOpen = true, limit = 200)
                requireCurrentReassignmentSession(me, lease)
                val serverOpenCount = terminalReassignmentShiftBlockers(openShifts, branchId, exactActive.terminalId)
                    ?: throw TerminalScopeException(TERMINAL_SHIFT_RESPONSE_LIMIT_MESSAGE)

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

                Triple(alternatives, finalCurrentStillAvailable, serverOpenCount to serverGamingCount)
            }
            val alternatives = observed.first
            val finalCurrentStillAvailable = observed.second
            val serverOpenCount = observed.third.first
            val serverGamingCount = observed.third.second

            var finalDecision: OutboxGateResult = OutboxGateResult.Blocked("Saved work needs recovery before changing tills.")
            dCompanyApp.handoverOperations.coordinator.finalizeWorkspaceExit(exit.cap, exitAuthority::beforeDeactivation) {
                finalDecision = cacheIsolation.deactivateOriginalAfterOutboxGate(
                    exitAuthority.originalCache, exitAuthority.originalGeneration, exitAuthority::beforeDeactivation,
                    { commit -> tokens.commitIfOriginalLoginCurrent(exitAuthority.login, commit) },
                ) {
                    if (!privateExitWorkClear(exit)) return@deactivateOriginalAfterOutboxGate OutboxGateResult.Blocked(
                        "Finish or review the original employee's pending work and open checkout before changing tills.")
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
                finalDecision is OutboxGateResult.Allowed
            }

            val exactDecision = finalDecision
            if (exactDecision is OutboxGateResult.Blocked) {
                throw TerminalScopeException(exactDecision.message)
            }

            sync.clearSessionFeedback()

            // The final gate has revoked every feature lease, so no cart/shift or
            // outbox write can race the switch from this point onward.
            if (!isCurrentReassignmentSession(me, lease)) {
                restorePreviousTerminalAfterInterruptedChange(me, lease, exactActive, exitAuthority.originalGeneration + 1)
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
                selectorAuthority = exitAuthority,
                selectorExit = exit.cap,
            )
            _terminalChange.value = TerminalChangeUiState.Idle
            _state.value = AuthState.SelectTerminal(
                me = me,
                terminals = alternatives,
                reassigning = true,
                previousTerminalName = exactActive.terminalName,
            )
            dCompanyApp.handoverOperations.coordinator.completeWorkspaceExit(exit.cap, exitAuthority::afterDeactivation)
        } catch (failure: Throwable) {
            if (!exit.cap.deactivated) {
                try { dCompanyApp.handoverOperations.coordinator.abortWorkspaceExit(exit.cap, exitAuthority::beforeDeactivation) }
                catch (_: Exception) { /* Actual storage/authority uncertainty remains closed. */ }
            }
            throw failure
        }
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
            // Android-validated Wi-Fi is not proof that the ERP backend can
            // authorize a workspace change. The coordinator publishes online
            // only after a successful readiness proof.
            online = connectivity.online.value,
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
        inactiveGeneration: Long,
    ) {
        val visible = _state.value as? AuthState.SignedIn ?: return
        if (OutboxOwnerIdentity.from(visible.me) != OutboxOwnerIdentity.from(expected)) return
        runCatching {
            resumeOriginalCachedTerminal(cacheIsolation, scopeFor(expected, previous.terminalId), inactiveGeneration,
                originalSelectionCurrent = { _state.value === visible && tokens.currentAccessFor(lease) != null },
                commitOriginalLogin = { commit -> tokens.commitIfOriginalLoginCurrent(lease, commit) },
                publish = {
                    if (!terminals.activateCachedValidated(previous.terminalId, previous.branchId)) {
                        throw TerminalScopeException("The previous till label could not be verified.")
                    }
                    ApiClient.activateTerminalScope(previous.terminalId)
                    reconcileOperationalAlarms()
                },
                cleanup = ::deactivateTerminalRuntime,
            )
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
        val choosingState = visible.copy(choosing = true, error = null)
        _state.value = choosingState

        viewModelScope.launch {
            try {
                if (!pending.isTokenCurrent()) return@launch
                val selector = pending.reassignmentReads()
                val branchId = pending.me.branchId?.trim()?.takeIf(String::isNotEmpty) ?: selector?.branchId
                    ?: throw TerminalScopeException(
                        "This account has no branch assignment. Ask a manager to assign one before opening the workspace.",
                    )
                val refreshed = if (pending.previousTerminal == null) {
                    verifiedBranchTerminals(ApiClient.api.terminals(branchId), branchId)
                } else {
                    verifiedReassignmentChoices(pending, branchId, requireNotNull(selector))
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

                selector?.requireCurrent()
                activateAndRememberTerminal(
                    terminalId = chosen.id,
                    shouldRemember = true,
                    activate = { terminalId ->
                        activateValidatedScope(scopeFor(pending.me, terminalId))
                    },
                    remember = { terminals.rememberValidated(chosen) },
                )
                if (!pending.isTokenCurrent()) return@launch
                val verifiedCache = cacheIsolation.currentLease()
                val loginLineage = pending.installedLogin?.lineage
                    ?: requireNotNull(pending.restoredSession).session.lineage
                val authenticated = acceptAuthenticated(
                    pending.me,
                    installedLogin = pending.installedLogin,
                    restoredSession = pending.restoredSession,
                )
                if (!authenticated) return@launch
                if (!pending.isTokenCurrent()) return@launch

                cache.rememberProfile(json.encodeToString(MeResponse.serializer(), pending.me))
                val original = if (EffectivePermissions.from(pending.me).has(ErpPermission.PosRead)) {
                    refreshShiftAuthorityAtLogin(pending.me, loginLineage, verifiedCache)
                } else null
                if (!pending.isTokenCurrent()) return@launch
                val publicationLogin = pending.restoredSession ?: tokens.refreshLease()
                    ?.takeIf { it.session.lineage === loginLineage } ?: return@launch
                val signedIn = if (pending.installedLogin != null && pending.previousTerminal == null) {
                    freshLoginState(pending.me, pending.installedLogin)
                } else {
                    freshLoginHandoff.clear()
                    AuthState.SignedIn(pending.me)
                }
                if (!publishStartupWorkspace(publicationLogin, verifiedCache, original, signedIn) {
                        pendingTerminalSession === pending && _state.value === choosingState && pending.isTokenCurrent()
                    }
                ) return@launch
                pendingTerminalSession = null
                reconcileOperationalAlarms()
                sync.requestSync()
                realtime.connect()
                refreshReceiptHistoryAfterLogin(pending.me)
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
                val selector = pending.reassignmentReads()
                val branchId = pending.me.branchId?.trim()?.takeIf(String::isNotEmpty) ?: selector?.branchId
                    ?: throw TerminalScopeException(
                        "This account has no branch assignment. Ask a manager to assign one before opening the workspace.",
                    )
                val refreshed = verifiedBranchTerminals(
                    selector?.terminals() ?: ApiClient.api.terminals(branchId),
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
        selector: WorkspaceSelectorReads,
    ): List<Terminal> {
        val previous = pending.previousTerminal
            ?: throw TerminalScopeException("The previous till assignment is unavailable. Cancel and try again.")
        if (!pending.isTokenCurrent() || !pending.me.protectedAccess) {
            throw TerminalScopeException(
                "Your sign-in or protected-owner access changed. Cancel the till change and sign in again.",
            )
        }
        if (!connectivity.online.value) {
            throw TerminalScopeException(
                "Changing tills requires a verified ERP server connection. " +
                    "Check the connection status, then choose the till again.",
            )
        }

        check(selector.branchId == branchId && previous.branchId == branchId)
        val firstRows = verifiedBranchTerminals(selector.terminals(), branchId)
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }
        val openShifts = selector.shifts()
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }
        val sessions = selector.sessions()
        if (!pending.isTokenCurrent()) {
            throw TerminalScopeException("Your sign-in changed during the till check. Cancel and sign in again.")
        }
        val finalRows = verifiedBranchTerminals(selector.terminals(), branchId)
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
        val serverShiftCount = terminalReassignmentShiftBlockers(openShifts, branchId, previous.terminalId)
            ?: throw TerminalScopeException(TERMINAL_SHIFT_RESPONSE_LIMIT_MESSAGE)
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
                online = connectivity.online.value,
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
        selector.requireCurrent()
        return alternatives
    }

    fun cancelTerminalReassignment() {
        val visible = _state.value as? AuthState.SelectTerminal ?: return
        val pending = pendingTerminalSession ?: return
        val previous = pending.previousTerminal ?: return
        if (!visible.reassigning || visible.choosing) return
        val choosing = visible.copy(choosing = true, error = null)
        val login = pending.restoredSession ?: return
        val inactiveGeneration = pending.selectorAuthority?.originalGeneration?.plus(1) ?: return
        _state.value = choosing
        // Error/finally publication must not change a newer person's selector either.
        fun feedback(message: String?) {
            tokens.commitIfOriginalLoginCurrent(login) {
                if (pendingTerminalSession === pending && _state.value === choosing) {
                    _state.value = choosing.copy(choosing = false, error = message)
                }
            }
        }
        viewModelScope.launch {
            try {
                requireNotNull(pending.reassignmentReads()).requireCurrent()
                val owned = resumeOriginalCachedTerminal(cacheIsolation, scopeFor(pending.me, previous.terminalId), inactiveGeneration,
                    originalSelectionCurrent = { pendingTerminalSession === pending && _state.value === choosing && pending.isTokenCurrent() },
                    commitOriginalLogin = { commit -> tokens.commitIfOriginalLoginCurrent(login, commit) },
                    publish = {
                        if (!terminals.activateCachedValidated(previous.terminalId, previous.branchId)) {
                            throw TerminalScopeException("The previous till label could not be verified. Reconnect and refresh the till list.")
                        }
                        ApiClient.activateTerminalScope(previous.terminalId)
                        pendingTerminalSession = null
                        _state.value = AuthState.SignedIn(pending.me)
                    },
                    cleanup = ::deactivateTerminalRuntime,
                ) ?: return@launch
                // Never enter worker/socket locks while holding the login mutation lock.
                cacheIsolation.commitIfCurrent(owned) {
                    if (tokens.currentAccessFor(login) != null) sync.clearSessionFeedback()
                }
                dCompanyApp.handoverOperations.caller(pending.me, owned).run { sync.requestSync() }
                realtime.connectForOriginal(login)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CacheScopeException) {
                feedback(error.message ?: "The previous till could not be reopened safely. Try again.")
            } catch (error: TerminalScopeException) {
                feedback(error.message ?: "The previous till could not be reopened.")
            } catch (_: Exception) {
                feedback("The previous till could not be reopened safely. Refresh the till list or ask support.")
            } finally {
                feedback(null)
            }
        }
    }

    /** Keep authority, not an expiring API/bearer, across explicit selector refresh attempts. */
    private fun PendingTerminalSession.reassignmentReads(): WorkspaceSelectorReads? {
        val previous = previousTerminal ?: return null
        val authority = selectorAuthority
            ?: throw TerminalScopeException("The original till-selection authority is unavailable. Sign in again.")
        val cap = selectorExit
            ?: throw TerminalScopeException("The original till-selection proof is unavailable. Sign in again.")
        return workspaceSelectorReads(authority, cap, dCompanyApp.handoverOperations.coordinator,
            previous.branchId, dCompanyApp.handoverOperations::status)
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
        dCompanyApp.handoverOperations.requireOperationalReadiness()
        terminals.deactivateValidatedDisplay()
        val activation = cacheIsolation.activateValidated(scope)
        sync.clearSessionFeedback()
        ApiClient.activateTerminalScope(scope.terminalId)
        if (activation == CacheScopeActivation.PURGED) {
            // A route is scoped to the account/branch/till that issued its
            // notification. Never redirect a different employee after purge.
            (getApplication() as DCompanyApp).notificationRoutes.clearAllForScopeChange()
            // AlarmManager is outside Room. Reconciliation against the now-empty
            // caches eventually removes A's tags, but a process observer may
            // be in backoff. Withdraw A's scheduled and already-visible alerts
            // before B can render, then let the process owner arm B below.
            try {
                withContext(Dispatchers.IO) {
                    check(OperationalAlarmRegistry.cancelAll(getApplication())) {
                        "The previous workspace alarm ledger could not be cleared"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The scope transition is already durable and cannot be rolled
                // back. The generation trigger below immediately enters the
                // process-owned retry path instead of silently abandoning it.
                Log.e("DCompanyAlarms", "Immediate scope-change alarm cleanup failed", failure)
            }
        }
        reconcileOperationalAlarms()
    }

    private fun reconcileOperationalAlarms() {
        (getApplication() as DCompanyApp).requestOperationalAlarmReconciliation()
    }

    /** Header authority and its human-readable label always change together. */
    private fun deactivateTerminalRuntime() {
        cloud.dcompany.erp.core.diagnostics.DiagnosticsRuntime.onScopeUnavailable()
        (getApplication() as DCompanyApp).remoteAssistance.onScopeUnavailable()
        ApiClient.deactivateTerminalScope()
        terminals.deactivateValidatedDisplay()
    }

    /** Do not expose the POS workspace until this verified login has read its own till. */
    private suspend fun refreshShiftAuthorityAtLogin(
        profile: MeResponse,
        loginLineage: Any,
        verifiedCache: CacheScopeLease?,
    ): OriginalOperationBinding {
        val lease = verifiedCache ?: throw StartupShiftAuthorityException(StartupShiftAuthorityFailure.ORIGIN)
        val original = dCompanyApp.handoverOperations.captureStartupShiftAuthority(profile, lease, loginLineage)
        sync.refreshShiftAuthorityAtLogin(original)
        return original
    }

    /** Acquiring the cache lock may suspend. The final checks and UI commit do not. */
    private suspend fun publishStartupWorkspace(
        login: SessionRefreshLease,
        verifiedCache: CacheScopeLease?,
        original: OriginalOperationBinding?,
        signedIn: AuthState.SignedIn,
        ownsAttempt: () -> Boolean,
    ): Boolean {
        if (!ownsAttempt() || tokens.currentAccessFor(login) == null) return false
        val lease = checkNotNull(verifiedCache) { "The verified workspace is unavailable." }
        var published = false
        val committed = cacheIsolation.commitIfCurrent(lease) {
            tokens.commitIfOriginalLoginCurrent(login) {
                published = publishVerifiedStartupState(
                    ownsAttempt(),
                    original,
                    lease,
                    login,
                    dCompanyApp.handoverOperations::requireOperationalReadiness,
                ) { _state.value = signedIn }
            }
        }
        if (!committed && ownsAttempt() && tokens.currentAccessFor(login) != null) {
            error("The verified workspace changed before it could open.")
        }
        return published
    }

    /** Receipt history is useful at login, but never belongs on the sign-in critical path. */
    private fun refreshReceiptHistoryAfterLogin(me: MeResponse) {
        if (!EffectivePermissions.from(me).has(ErpPermission.PosRead)) return
        viewModelScope.launch { sync.refresh("receipts") }
    }

    fun signOut() {
        if (signOutJob?.isActive == true) return
        val captured = try { pendingLogoutExit?.authority ?: captureWorkspaceExit() }
        catch (_: Exception) {
            outboxSafety.publishNotice("The original workspace needs recovery before sign-out. Saved work has been kept.")
            return
        }
        val removal = pendingLogoutRemoval ?: tokens.captureLogoutLease(captured.login) ?: return
        if (pendingLogoutRemoval == null) {
            pendingLogoutProfile = cache.profileSnapshot()
            pendingLogoutOwnsProfile = cache.profile.value?.let { profile ->
                captured.login?.accessToken?.let(AccessTokenIdentityParser::parse) == OutboxOwnerIdentity.from(profile)
            } == true
            pendingLogoutRemoval = removal
        }
        authorityRefresh.cancelPending()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        _terminalChange.value = TerminalChangeUiState.Idle
        signOutJob = viewModelScope.launch {
            var exit = pendingLogoutExit
            val decision = try {
                if (exit == null) {
                    exit = closeWorkspaceAdmission(captured, WorkspaceExitKind.LOGOUT)
                    pendingLogoutExit = exit
                }
                val held = requireNotNull(exit)
                if (held.cap.deactivated) {
                    check(captured.afterDeactivation() || captured.localSessionRemoved())
                    OutboxGateResult.Allowed
                } else if (!exitLifetimesDrained(held)) {
                    OutboxGateResult.Blocked("The original workspace is still finishing or recovering saved work. No employee was signed out.")
                } else {
                    var gate: OutboxGateResult = OutboxGateResult.Blocked("Saved work needs recovery before sign-out.")
                    dCompanyApp.handoverOperations.coordinator.finalizeWorkspaceExit(held.cap, captured::beforeDeactivation) {
                        gate = cacheIsolation.deactivateOriginalAfterOutboxGate(
                            captured.originalCache, captured.originalGeneration, captured::beforeDeactivation,
                            { commit -> tokens.commitIfOriginalLoginCurrent(captured.login, commit) },
                        ) {
                            if (!privateExitWorkClear(held)) OutboxGateResult.Blocked("Finish or review the original employee's pending work and open checkout before sign-out.")
                            else outboxSafety.canSignOut()
                        }
                        gate is OutboxGateResult.Allowed
                    }
                    gate
                }
            } catch (_: Exception) {
                OutboxGateResult.Blocked(
                    "Sign-out was blocked because the app could not verify whether saved work is synced. " +
                        "Check the connection and try again.",
                )
            }
            _loginError.value = loginErrorAfterSignOutDecision(_loginError.value, decision)
            if (decision is OutboxGateResult.Blocked) {
                exit?.takeIf { !it.cap.deactivated }?.let { held ->
                    try {
                        dCompanyApp.handoverOperations.coordinator.abortWorkspaceExit(held.cap, captured::beforeDeactivation)
                        pendingLogoutExit = null
                        pendingLogoutRemoval = null
                        pendingLogoutProfile = null
                    } catch (_: Exception) { /* Actual storage/authority uncertainty remains closed. */ }
                }
                outboxSafety.publishNotice(decision.message)
                return@launch
            }
            val held = requireNotNull(exit)
            // Dispose the authenticated feature tree before any IO-backed
            // credential cleanup suspends. Otherwise the old workspace stays
            // tappable with an already-deactivated cache lease and actions can
            // appear to do nothing during sign-out.
            if (!publishOriginalLogout(captured, removal) { _state.value = AuthState.SigningOut }) return@launch
            freshLoginHandoff.clear()
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
                bestEffortAuthenticatedLogout(
                    timeoutMillis = REMOTE_LOGOUT_TIMEOUT_MILLIS,
                    revokeRemoteSession = { logoutOriginalWorkspace(captured, held.cap, dCompanyApp.handoverOperations.coordinator) },
                    clearLocalSession = {
                        withContext(Dispatchers.IO) {
                            check(captured.inactiveCacheUnchanged() && tokens.clearForLogoutIfCurrent(removal)) {
                                "The original logout cleanup authority changed."
                            }
                        }
                    },
                )
            } catch (_: Exception) {
                publishOriginalLogout(captured, removal) {
                    _state.value = AuthState.SignOutFailed(
                        "This tablet could not durably remove the secure session. Keep this screen open, " +
                            "retry sign-out, and do not hand the tablet to another employee until it succeeds.",
                    )
                }
                return@launch
            }
            // A pre-terminal logout cannot erase another unadopted owner's cached profile.
            try {
                if (pendingLogoutOwnsProfile) {
                    check(cache.forgetOriginalProfile(pendingLogoutProfile) {
                        captured.inactiveCacheUnchanged() && tokens.isCurrentLogoutRemoval(removal)
                    }) { "The original cached profile changed." }
                }
                dCompanyApp.handoverOperations.coordinator.completeWorkspaceExit(held.cap) {
                    cacheIsolation.publishIfInactiveGeneration(captured.originalGeneration + 1) {
                        tokens.publishIfCurrentLogoutRemoval(removal) {
                            pendingLogoutExit = null
                            pendingLogoutRemoval = null
                            pendingLogoutProfile = null
                            _state.value = AuthState.SignedOut
                        }
                    }
                }
            } catch (_: Exception) {
                publishOriginalLogout(captured, removal) {
                    _state.value = AuthState.SignOutFailed(
                        "The secure session was removed, but workspace cleanup needs recovery. Keep this screen open and retry sign-out.")
                }
            }
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
        freshLoginHandoff.clear()
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
            (getApplication() as DCompanyApp).onVerifiedScopeAvailable()
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
        freshLoginHandoff.clear()
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
        freshLoginHandoff.clear()
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
        freshLoginHandoff.clear()
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
        const val REMOTE_LOGOUT_TIMEOUT_MILLIS = 1_000L
        const val RESTORE_SERVER_TIMEOUT_MILLIS = 8_000L
    }

    override fun onCleared() {
        authorityRefresh.cancel()
        terminalChangeJob?.cancel()
        terminalChangeGate.finish()
        super.onCleared()
    }
}

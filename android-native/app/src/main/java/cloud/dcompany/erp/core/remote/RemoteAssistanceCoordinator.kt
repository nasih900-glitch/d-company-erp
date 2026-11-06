package cloud.dcompany.erp.core.remote

import android.content.Context
import android.os.SystemClock
import android.view.Window
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.handover.OriginalOperationBinding
import cloud.dcompany.erp.core.handover.KnownRemoteKeyObservation
import cloud.dcompany.erp.core.sync.RealtimeClient
import cloud.dcompany.erp.core.sync.RealtimeEvent
import cloud.dcompany.erp.core.update.InstallationIdentityStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class RemoteAssistanceUiState(
    val consent: RemoteConsentChoice = RemoteConsentChoice.UNDECIDED,
    val actionAuthority: cloud.dcompany.erp.core.handover.HandoverAuthority? = null,
    val displayedKeyId: String? = null,
    val consentGrantId: String? = null,
    val displayedSessionId: String? = null,
    val sharingConfirmed: Boolean = false,
    val canResumeSharing: Boolean = false,
    val revokeSaveRetryRequired: Boolean = false,
    val pendingGrant: RemoteGrantPrompt? = null,
    val activeSession: RemoteActiveSession? = null,
    val privacyProtected: Boolean = true,
    val notificationReady: Boolean = false,
    val deviceKeyStatus: RemoteDeviceKeyStatus? = null,
    val pairingCode: String? = null,
    val decisionInFlight: Boolean = false,
    val statusMessage: String? = null,
    val lastCommandLabel: String? = null,
)

/**
 * Process-scoped, foreground-only remote assistance. The control plane can
 * request only closed semantic commands; the capture plane can read only the
 * attached ERP Activity window and never survives an app-background edge.
 */
internal class RemoteAssistanceCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val cacheIsolation: CacheIsolationCoordinator,
    private val realtime: RealtimeClient,
    private val online: StateFlow<Boolean>,
    installationIdentity: InstallationIdentityStore,
    private val collectDiagnostics: suspend () -> Boolean,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val installationIdProvider = installationIdentity::installationId
    @Volatile private var journalScope: RemoteAssistanceJournalScope? = null
    private val store = RemoteAssistanceStore(appContext, currentScope = { journalScope })
    private val deviceIdentityStore = RemoteDeviceIdentityStore(appContext)
    private val deviceKeyStore = RemoteDeviceKeyStore()
    private val notification = RemoteAssistanceNotification(appContext)
    private val captureSource = RemoteAppWindowCaptureSource()
    private val frameEncoder = RemoteFrameEncoder(captureSource)
    val privacy = RemoteCapturePrivacyController()
    val uiGateway = RemoteUiCommandGateway()

    private val api: RemoteAssistanceApi by lazy {
        createInstallationProofApi(RemoteAssistanceApi::class.java)
    }

    internal fun <T> createInstallationProofApi(service: Class<T>): T =
        ApiClient.createApiWithNetworkProof(
            service,
            RemoteDeviceSigningInterceptor(
                identity = ::currentRemoteDeviceIdentity,
                currentScope = {
                    journalScope?.takeIf { verifiedScope && it == currentCacheJournalScope() }
                },
                keyStore = deviceKeyStore,
            ),
        )

    internal fun currentInstallationProofTag(): RemoteRequestScopeTag? =
        currentRemoteRequestTag()
    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<RemoteAssistanceUiState> = _uiState.asStateFlow()
    private val pollWakeups = Channel<Unit>(Channel.CONFLATED)
    private val pollMutex = Mutex()
    private val mutationMutex = Mutex()
    private val deviceKeyMutex = Mutex()
    private val lifecycleLock = Any()
    private val originalCommit = RemoteOriginalCommit(lifecycleLock,
        commitLogin = { login, commit -> cloud.dcompany.erp.DCompanyApp.instance.tokens.commitIfOriginalLoginCurrent(login, commit) },
        originalViewCurrent = { it.isCurrent() })
    private val localSharing = RemoteLocalSharingGuard(::pendingOriginCurrent)
    private val processIdentity = UUID.randomUUID().toString()
    private val frameSequence = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))

    @Volatile private var originalOperation: cloud.dcompany.erp.core.handover.OriginalOperationBinding? = null
    @Volatile private var verifiedScope = false
    @Volatile private var appForeground = false
    @Volatile private var forceDeviceKeyStatusCheck = true
    private val pollJobs = RemoteJobSlot<OriginalOperationBinding>()
    private val captureJobs = RemoteJobSlot<OriginalOperationBinding>()
    private val commandJobs = RemoteJobSlot<OriginalOperationBinding>()
    @Volatile private var pollRestartRequested = false
    private var nextDeviceKeyStatusCheckAt = Long.MIN_VALUE
    private var pendingDeviceKeyDeadline: Pair<String, Long>? = null

    init {
        scope.launch {
            realtime.changes.collect { event ->
                if (event.isRemoteAssistanceInvalidation()) {
                    forceDeviceKeyStatusCheck = true
                    pollWakeups.trySend(Unit)
                }
            }
        }
        scope.launch {
            online.collect { isOnline ->
                val original = originalOperation?.takeIf { it.isCurrent() }
                _uiState.value.activeSession?.let { active ->
                    if (original == null || !sharingAllowed(original, active)) return@let
                    val remaining =
                        (active.deadlineElapsedMillis - elapsedRealtime()).coerceAtLeast(1_000L)
                    if (!notification.show(remaining, notificationTarget(active) ?: return@let, sharingPaused = !isOnline)) {
                        requestSessionEnd(active, "capture_stopped", original ?: return@let)
                    }
                }
                if (isOnline) {
                    ensurePolling()
                    pollWakeups.trySend(Unit)
                }
            }
        }
    }

    private fun actionTarget(state: RemoteAssistanceUiState): RemoteUiTarget? = state.actionAuthority?.let {
        RemoteUiTarget(it, state.pendingGrant, state.activeSession, state.displayedKeyId, state.consentGrantId, state.displayedSessionId)
    }
    fun captureUiActions(displayed: RemoteAssistanceUiState): RemoteUiActionBinding? =
        captureRemoteUiAction(originalOperation, actionTarget(displayed), actionTarget(_uiState.value))
    private fun actionCurrent(action: RemoteUiActionBinding) = action.matches(originalOperation, actionTarget(_uiState.value))
    private fun notificationTarget(session: RemoteActiveSession): RemoteStopTarget? {
        val original = originalOperation?.takeIf { it.isCurrent() && _uiState.value.actionAuthority == it.authority } ?: return null
        return RemoteStopTarget(processIdentity, original.authority, session.sessionId, session.grantId)
    }
    fun stopFromNotification(target: RemoteStopTarget) {
        val original = originalOperation ?: return
        val active = _uiState.value.activeSession ?: return
        if (target.processIdentity != processIdentity || target.authority != original.authority || !original.isCurrent() ||
            active.sessionId != target.sessionId || active.grantId != target.grantId) return
        requestSessionEnd(active, "user_ended", original, explicitRetry = true)
    }

    fun attachWindow(window: Window) {
        captureSource.attach(window)
    }

    fun detachWindow(window: Window) {
        captureSource.detach(window)
    }

    fun onVerifiedScopeAvailable() {
        val app = cloud.dcompany.erp.DCompanyApp.instance
        val nextOperation = app.handoverOperations.captureOriginal(app.shiftCache.profile.value, cacheIsolation.currentLease())
        if (nextOperation == null) { onScopeUnavailable(); return }
        val nextScope = currentCacheJournalScope()
        if (nextScope == null) {
            onScopeUnavailable()
            return
        }
        var commandToJoin: Job? = null
        val published = originalCommit.withOriginal(nextOperation) {
            val previousScope = journalScope
            if (previousScope != null && (previousScope != nextScope || originalOperation?.authority != nextOperation.authority)) {
                pollJobs.cancel()
                _uiState.value.activeSession?.let { active -> store.recordSessionEnd(active.sessionId, "capture_stopped") }
                commandToJoin = clearActiveSession("Remote support stopped because the authenticated ERP user changed.")
            }
            val changedOrigin = originalOperation !== nextOperation || previousScope != nextScope
            originalOperation = nextOperation
            journalScope = nextScope
            verifiedScope = true
            if (changedOrigin) {
                localSharing.authorityChanged()
                _uiState.value = initialUiState().copy(actionAuthority = nextOperation.authority)
            }
            forceDeviceKeyStatusCheck = true
            refreshConsentUi()
            true
        } == true
        scope.launch { commandToJoin?.join() }
        if (published) ensurePolling()
    }

    fun onScopeUnavailable() {
        val expectedView = originalOperation
        val loginSnapshot = cloud.dcompany.erp.DCompanyApp.instance.tokens.refreshLease()
        var commandToJoin: Job? = null
        originalCommit.withLoginSnapshot(loginSnapshot) {
            if (originalOperation !== expectedView) return@withLoginSnapshot
            localSharing.authorityChanged()
            originalOperation = null
            verifiedScope = false
            pollJobs.cancel()
            _uiState.value.activeSession?.let { active -> store.recordSessionEnd(active.sessionId, "capture_stopped") }
            commandToJoin = clearActiveSession("Remote support stopped because this ERP workspace is no longer active.")
            journalScope = null
            _uiState.value = initialUiState().copy(consent = store.snapshot().consentChoice)
        }
        scope.launch { commandToJoin?.join() }
    }

    /** The in-app Stop banner is part of admission, not optional decoration. */
    fun onVisibleWorkspaceUnavailable(expected: OriginalOperationBinding) {
        if (originalOperation !== expected || !expected.isCurrent()) return
        val active = _uiState.value.activeSession ?: return
        requestSessionEnd(active, "capture_stopped", expected)
    }

    fun onAppForegrounded() {
        appForeground = true
        forceDeviceKeyStatusCheck = true
        refreshConsentUi()
        ensurePolling()
    }

    fun onAppBackgrounded() {
        appForeground = false
        val original = originalOperation ?: return
        val active = originalCommit.withOriginal<RemoteActiveSession?>(original) {
            if (!pendingOriginCurrent(original)) return@withOriginal null
            pollJobs.cancel()
            _uiState.value.activeSession
        } ?: return
        requestSessionEnd(active, "app_backgrounded", original)
    }

    fun allowPendingGrant(action: RemoteUiActionBinding) {
        if (!actionCurrent(action)) return
        decidePendingGrant(allow = true, action)
    }

    fun denyPendingGrant(action: RemoteUiActionBinding) {
        if (!actionCurrent(action)) return
        decidePendingGrant(allow = false, action)
    }

    fun revokeConsent(action: RemoteUiActionBinding) {
        if (!actionCurrent(action) || !action.matchesConsentGrant(store.snapshot().grantId)) return
        val original = action.original
        val grantId = requireNotNull(action.target.consentGrantId)
        val active = _uiState.value.activeSession
        var commandToJoin: Job? = null
        val revocation = originalCommit.withOriginal<PendingRemoteGrantRevocation?>(original) {
            if (!actionCurrent(action)) return@withOriginal null
            localSharing.privacyRecord(
            RemoteLocalPrivacyAction(original, RemotePendingKind.REVOKE, grantId), explicitRetry = true,
            stop = { commandToJoin = clearActiveSessionFor(original, null) },
            record = { recordOriginalPending(original) { expected -> store.recordRevocation(expected, grantId) } },
            matches = { it.grantId == grantId },
        ) }
        if (!pendingOriginCurrent(original)) return
        if (active != null) recordStoppedSession(active, "permission_revoked", original, explicitRetry = true)
        publishSharingState(original) { _uiState.value = _uiState.value.copy(
            consent = store.snapshot().consentChoice,
            revokeSaveRetryRequired = localSharing.needsRevokeRetry(original, grantId),
            pendingGrant = null, activeSession = active.takeIf { localSharing.hasWriteHold() },
            sharingConfirmed = false, canResumeSharing = false, privacyProtected = true,
            decisionInFlight = revocation != null,
            statusMessage = if (revocation == null) "Sharing stopped locally. Revocation could not be saved. Retry Revoke owner support."
                else "Owner remote support is revoked locally. Its saved action still needs a verified server receipt.",
        ) }
        scope.launch {
            commandToJoin?.join()
            sendPendingSessionEnd(original)
            sendPendingRevocation(original)
            sendHeartbeat(original)
            pollWakeups.trySend(Unit)
        }
    }

    /** Confirmation is local only; refresh exact server/A state before renewing this displayed target. */
    fun resumeSharing(action: RemoteUiActionBinding) {
        if (!actionCurrent(action) || _uiState.value.sharingConfirmed || !_uiState.value.canResumeSharing) return
        val original = action.original
        val displayed = action.target.session ?: return
        if (action.target.consentGrantId != displayed.grantId || action.target.displayedSessionId != displayed.sessionId ||
            !pendingAllowsSharing(displayed) || localSharing.hasWriteHold()) return
        val tag = currentRemoteRequestTag() ?: return
        if (!publishSharingState(original) {
            check(actionCurrent(action))
            localSharing.pause(original)
            _uiState.value = _uiState.value.copy(canResumeSharing = false)
        }) return
        scope.launch {
            try {
                cloud.dcompany.erp.DCompanyApp.instance.handoverOperations.run<Unit>(original) {
                    val response = api.deviceState(tag.scope.installationId, 0L, tag)
                    check(actionCurrent(action) && remoteRequestScopeIsCurrent(tag))
                    val active = checkNotNull(evaluateRemoteSession(response.session, response.serverTime,
                        elapsedRealtime(), tag.scope.installationId).active)
                    check(active.sessionId == displayed.sessionId && active.grantId == displayed.grantId &&
                        appForeground && uiGateway.hasVisibleHost() && notification.canShowPersistentIndicator() && pendingAllowsSharing(active))
                    check(publishSharingState(original) {
                        check(actionCurrent(action))
                        localSharing.observe(original, active.grantId, active.sessionId)
                        check(localSharing.resume(original, active.grantId, active.sessionId, pendingAllowsSharing(active)))
                    })
                    activateSession(active, original)
                    cloud.dcompany.erp.core.handover.OperationResult(Unit, cloud.dcompany.erp.core.handover.OperationOutcome.NO_WRITE_SENT)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (pendingOriginCurrent(original)) _uiState.value = _uiState.value.copy(
                    sharingConfirmed = false, canResumeSharing = pendingAllowsSharing(displayed) && !localSharing.hasWriteHold(),
                    statusMessage = "Sharing remains paused. Verify the current support session and retry Resume sharing.")
            }
        }
    }

    fun stopByUser(action: RemoteUiActionBinding) {
        if (!actionCurrent(action)) return
        val active = action.target.session ?: return
        requestSessionEnd(active, "user_ended", action.original, explicitRetry = true)
    }

    fun requestRefresh() {
        ensurePolling()
        pollWakeups.trySend(Unit)
    }

    /**
     * Creates a candidate without replacing the active signer. Normal device
     * routes keep using the approved key; only the candidate's exact status
     * route uses the candidate until owner approval atomically promotes it.
     */
    fun startDeviceKeyReplacement(action: RemoteUiActionBinding) {
        if (!actionCurrent(action)) return
        if (!appForeground || !canUseRemoteApi()) {
            if (_uiState.value.statusMessage != RemoteDeviceIdentityUnavailable().message) _uiState.value = _uiState.value.copy(
                statusMessage = "Reconnect this signed-in tablet before starting replacement pairing.",
            )
            return
        }
        val original = action.original
        scope.launch {
            remoteKeyWork<Unit>(original) { deviceKeyMutex.withLock<Unit> {
                if (originalOperation !== original || !original.isCurrent()) return@withLock
                if (!appForeground || !canUseRemoteApi()) return@withLock
                val bound = journalScope
                    ?.takeIf { it == currentCacheJournalScope() }
                    ?: return@withLock
                val active = deviceIdentityStore.activeIdentity(
                    bound.companyId,
                    bound.installationId,
                )?.takeIf(::localDeviceKeyMatches)
                    ?: return@withLock
                if (active.keyId != action.target.deviceKeyId) return@withLock
                val candidate = deviceIdentityStore.enrollmentCandidate(
                    bound.companyId,
                    bound.installationId,
                ) ?: createRemoteDeviceIdentity(bound.companyId, bound.installationId)
                ?: return@withLock
                // Retaining this reference documents and enforces that a
                // replacement is never implemented by deleting the live key.
                if (deviceIdentityStore.activeIdentity(
                        bound.companyId,
                        bound.installationId,
                    )?.keyId != active.keyId
                ) {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Remote support kept the current key; replacement pairing was not started.",
                    )
                    return@withLock
                }
                forceDeviceKeyStatusCheck = true
                publishDeviceIdentity(candidate)
                when (candidate.keyStatus) {
                    RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(candidate, original)
                    RemoteDeviceKeyStatus.PENDING -> refreshRemoteDeviceKeyStatus(candidate, original)
                    else -> Unit
                }
            } }
            pollWakeups.trySend(Unit)
        }
    }

    /** Refreshes presentation only; session/capture admission still rechecks the indicator itself. */
    fun refreshNotificationReadiness() {
        _uiState.value = _uiState.value.copy(
            notificationReady = notification.canShowPersistentIndicator(),
        )
        if (_uiState.value.notificationReady) requestRefresh()
    }

    internal fun inspectRetainedWork(): RemoteJournalReadiness = store.inspectHandoverWork().let { if (localSharing.hasWriteHold()) it.copy(verified = false) else it }

    private fun initialUiState(): RemoteAssistanceUiState = RemoteAssistanceUiState(
        consent = store.snapshot().consentChoice,
        notificationReady = notification.canShowPersistentIndicator(),
        statusMessage = if (store.inspectHandoverWork().verified) null else REMOTE_JOURNAL_RECOVERY_MESSAGE,
    )

    private fun refreshConsentUi() {
        val original = originalOperation ?: return
        originalCommit.withOriginal(original) {
            if (!pendingOriginCurrent(original)) return@withOriginal
            if (!store.inspectHandoverWork().verified) {
                clearActiveSession(REMOTE_JOURNAL_RECOVERY_MESSAGE)
                _uiState.value = _uiState.value.copy(decisionInFlight = false, statusMessage = REMOTE_JOURNAL_RECOVERY_MESSAGE)
                return@withOriginal
            }
            val persisted = store.snapshot()
            val visibleGrantId = _uiState.value.pendingGrant?.grantId
            _uiState.value = _uiState.value.copy(
                consent = persisted.consentChoice,
                consentGrantId = persisted.grantId,
                revokeSaveRetryRequired = localSharing.needsRevokeRetry(originalOperation, persisted.grantId),
                actionAuthority = originalOperation?.takeIf { it.isCurrent() }?.authority,
                notificationReady = notification.canShowPersistentIndicator(),
                decisionInFlight = persisted.pendingDecision?.grantId == visibleGrantId ||
                    persisted.pendingRevocation != null,
            )
        }
    }

    private fun decidePendingGrant(allow: Boolean, action: RemoteUiActionBinding) {
        if (!actionCurrent(action)) return
        val heartbeatOrigin = action.original
        val prompt = action.target.prompt ?: return
        val mutation = recordOriginalPending(action.original) { expected -> store.recordDecision(
            grantId = prompt.grantId,
            allow = allow,
            grantKind = prompt.kind,
            grantExpiresAt = prompt.expiresAt,
            expectedScope = expected,
        ) } ?: run {
            _uiState.value = _uiState.value.copy(
                statusMessage = "This support choice could not be saved safely. Try again.",
            )
            return
        }
        if (!publishSharingState(action.original) {
            check(actionCurrent(action))
            if (allow) localSharing.noteFreshAllow(action.original, prompt.grantId, action.target.displayedSessionId, mutation.decisionId)
        }) return
        _uiState.value = _uiState.value.copy(
            consent = if (allow) RemoteConsentChoice.ALLOWED else RemoteConsentChoice.DENIED,
            pendingGrant = null,
            decisionInFlight = true,
            statusMessage = if (allow) {
                "Owner support is allowed for short, visible ERP-only sessions."
            } else {
                "Owner remote support is off on this tablet."
            },
        )
        scope.launch {
            sendGrantDecision(mutation, heartbeatOrigin)
            sendHeartbeat(heartbeatOrigin)
            pollWakeups.trySend(Unit)
        }
    }

    private fun ensurePolling() {
        val heartbeatOrigin = originalOperation?.takeIf { it.isCurrent() } ?: return
        if (!verifiedScope || !appForeground || cacheIsolation.currentLease() == null) return
        val launched = originalCommit.withOriginal<Job?>(heartbeatOrigin) {
            if (!pendingOriginCurrent(heartbeatOrigin)) return@withOriginal null
            val job = pollJobs.reserve(scope, heartbeatOrigin) {
                var initialPoll = true
                var lastHeartbeatAt = Long.MIN_VALUE
                while (isActive && verifiedScope && appForeground && heartbeatOrigin.isCurrent()) {
                    if (online.value && cacheIsolation.currentLease() != null) {
                        val deviceReady = ensureRemoteDeviceProof(heartbeatOrigin)
                        if (deviceReady) {
                            val now = elapsedRealtime()
                            if (
                                lastHeartbeatAt == Long.MIN_VALUE ||
                                now < lastHeartbeatAt ||
                                now - lastHeartbeatAt >= REMOTE_HEARTBEAT_INTERVAL_MILLIS
                            ) {
                                sendHeartbeat(heartbeatOrigin)
                                lastHeartbeatAt = now
                            }
                            sendPendingMutations(heartbeatOrigin)
                            pollDeviceState(initialPoll)
                            initialPoll = false
                        }
                    }
                    withTimeoutOrNull(POLL_INTERVAL_MILLIS) { pollWakeups.receive() }
                }
            }
            // A foreground/owner re-entry must not lose its only trigger while the old
            // cancelled poll still owns its full finally/child lifetime.
            pollRestartRequested = job == null
            job
        }
        launched?.let { restartRemotePollAfterCompletion(it, scope, { pollRestartRequested && verifiedScope && appForeground && online.value }, ::ensurePolling) }
        launched?.start()
    }

    private suspend fun ensureRemoteDeviceProof(original: OriginalOperationBinding): Boolean = remoteKeyWork<Boolean>(original) { deviceKeyMutex.withLock<Boolean> {
        if (originalOperation !== original || !original.isCurrent()) return@withLock false
        if (!canUseRemoteEnrollmentApi()) return@withLock false
        val lease = cacheIsolation.currentLease() ?: return@withLock false
        val companyId = lease.scope.companyId.trim().lowercase()
        val installationId = installationIdProvider()?.trim()?.lowercase() ?: return@withLock false
        if (!isCanonicalUuidV4(companyId) || !isCanonicalUuidV4(installationId)) {
            _uiState.value = _uiState.value.copy(
                deviceKeyStatus = null,
                pairingCode = null,
                statusMessage = "Remote support could not verify this ERP installation identity.",
            )
            return@withLock false
        }
        deleteRetiredDeviceKeys()
        deviceIdentityStore.identities(companyId, installationId)
            .filter { it.keyStatus in setOf(RemoteDeviceKeyStatus.REVOKED, RemoteDeviceKeyStatus.EXPIRED) }
            .forEach { stale ->
                if (deviceKeyStore.delete(stale.keyId)) {
                    deviceIdentityStore.remove(companyId, installationId, stale.keyId)
                }
            }
        var active = deviceIdentityStore.activeIdentity(companyId, installationId)
        var candidate = deviceIdentityStore.enrollmentCandidate(companyId, installationId)
        if (active != null && !localDeviceKeyMatches(active)) {
            deviceKeyStore.delete(active.keyId)
            deviceIdentityStore.remove(companyId, installationId, active.keyId)
            active = null
        }
        if (candidate != null && !localDeviceKeyMatches(candidate)) {
            deviceKeyStore.delete(candidate.keyId)
            deviceIdentityStore.remove(companyId, installationId, candidate.keyId)
            candidate = null
        }
        if (candidate != null) {
            val reconcileActiveToo = forceDeviceKeyStatusCheck && active != null
            publishDeviceIdentity(candidate)
            when (candidate.keyStatus) {
                RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(candidate, original)
                RemoteDeviceKeyStatus.PENDING -> {
                    val now = elapsedRealtime()
                    if (
                        forceDeviceKeyStatusCheck ||
                        now < 0L ||
                        nextDeviceKeyStatusCheckAt == Long.MIN_VALUE ||
                        pendingDeviceKeyDeadline?.let { (keyId, deadline) ->
                            keyId == candidate.keyId && now >= deadline
                        } == true ||
                        now >= nextDeviceKeyStatusCheckAt
                    ) {
                        refreshRemoteDeviceKeyStatus(candidate, original)
                    }
                }
                else -> Unit
            }
            var usable = deviceIdentityStore.activeIdentity(companyId, installationId)
            if (
                reconcileActiveToo &&
                usable != null &&
                usable.keyId == active?.keyId
            ) {
                refreshRemoteDeviceKeyStatus(usable, original)
                usable = deviceIdentityStore.activeIdentity(companyId, installationId)
            }
            if (usable != null && localDeviceKeyMatches(usable)) return@withLock true
            candidate = deviceIdentityStore.enrollmentCandidate(companyId, installationId)
            return@withLock candidate?.keyStatus == RemoteDeviceKeyStatus.ACTIVE
        }
        if (active != null) {
            publishDeviceIdentity(active)
            return@withLock if (forceDeviceKeyStatusCheck) {
                refreshRemoteDeviceKeyStatus(active, original)
            } else {
                true
            }
        }
        candidate = createRemoteDeviceIdentity(companyId, installationId)
            ?: return@withLock false
        publishDeviceIdentity(candidate)
        when (candidate.keyStatus) {
            RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(candidate, original)
            RemoteDeviceKeyStatus.PENDING -> {
                val now = elapsedRealtime()
                if (
                    forceDeviceKeyStatusCheck ||
                    now < 0L ||
                    nextDeviceKeyStatusCheckAt == Long.MIN_VALUE ||
                    pendingDeviceKeyDeadline?.let { (keyId, deadline) ->
                        keyId == candidate.keyId && now >= deadline
                    } == true ||
                    now >= nextDeviceKeyStatusCheckAt
                ) {
                    refreshRemoteDeviceKeyStatus(candidate, original)
                } else {
                    false
                }
            }
            RemoteDeviceKeyStatus.ACTIVE -> {
                pendingDeviceKeyDeadline = null
                true
            }
            RemoteDeviceKeyStatus.REVOKED,
            RemoteDeviceKeyStatus.EXPIRED,
            null,
            -> rotateAndEnrollRemoteDeviceKey(candidate, original)
        }
    } } ?: false

    /** Finite key work includes local writes and every awaited request/checkpoint child. */
    private suspend fun <T> remoteKeyWork(original: OriginalOperationBinding, work: suspend () -> T): T? {
        if (originalOperation !== original || !original.isCurrent()) return null
        return try {
            cloud.dcompany.erp.DCompanyApp.instance.handoverOperations.run<T>(original) {
                cloud.dcompany.erp.core.handover.OperationResult<T>(work(), cloud.dcompany.erp.core.handover.OperationOutcome.DURABLE_OUTCOME)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RemoteDeviceIdentityUnavailable) { showIdentityUnavailable(original); null }
        catch (_: Exception) {
            if (originalOperation === original && original.isCurrent()) _uiState.value = _uiState.value.copy(
                statusMessage = "Remote device approval could not be verified. Reconnect and retry; saved identity was retained.")
            null
        }
    }
    private fun showIdentityUnavailable(original: OriginalOperationBinding?) {
        if (originalOperation === original) _uiState.value = _uiState.value.copy(
            statusMessage = RemoteDeviceIdentityUnavailable().message, deviceKeyStatus = null, pairingCode = null)
    }

    private fun createRemoteDeviceIdentity(
        companyId: String,
        installationId: String,
    ): PersistedRemoteDeviceIdentity? {
        val keyId = UUID.randomUUID().toString()
        val enrollmentId = UUID.randomUUID().toString()
        val spki = try {
            deviceKeyStore.generate(keyId)
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Remote support could not create its protected device key.",
            )
            return null
        }
        val identity = try {
            PersistedRemoteDeviceIdentity(
                companyId = companyId,
                installationId = installationId,
                keyId = keyId,
                enrollmentId = enrollmentId,
                status = RemoteDeviceKeyStatus.CREATED.storedValue,
                fingerprintSha256 = remoteSha256Hex(spki),
            )
        } finally {
            spki.fill(0)
        }
        val saved = try { deviceIdentityStore.put(identity) } catch (unavailable: RemoteDeviceIdentityUnavailable) {
            deviceKeyStore.delete(keyId) // Only this attempt's newly generated, never-persisted alias.
            throw unavailable
        }
        if (!saved) {
            deviceKeyStore.delete(keyId)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Remote support could not save its protected device identity.",
            )
            return null
        }
        nextDeviceKeyStatusCheckAt = Long.MIN_VALUE
        return identity
    }

    private fun localDeviceKeyMatches(identity: PersistedRemoteDeviceIdentity): Boolean {
        if (!deviceKeyStore.privateKeyIsNonExportable(identity.keyId)) return false
        val spki = deviceKeyStore.publicKeySpki(identity.keyId) ?: return false
        return try {
            remoteSha256Hex(spki) == identity.fingerprintSha256
        } finally {
            spki.fill(0)
        }
    }

    private suspend fun enrollRemoteDeviceKey(identity: PersistedRemoteDeviceIdentity, original: OriginalOperationBinding): Boolean {
        if (originalOperation !== original || !original.isCurrent()) return false
        val expectedState = deviceIdentityStore.expectedState(identity) ?: return false
        val requestScope = currentRemoteRequestTag()
            ?.takeIf {
                it.scope.companyId == identity.companyId &&
                    it.scope.installationId == identity.installationId
            }
            ?: return false
        val now = elapsedRealtime()
        if (
            !forceDeviceKeyStatusCheck &&
            nextDeviceKeyStatusCheckAt != Long.MIN_VALUE &&
            now >= 0L &&
            now < nextDeviceKeyStatusCheckAt
        ) return false
        val manager = cloud.dcompany.erp.DCompanyApp.instance.handoverOperations
        val attempt = remoteEnrollmentAttempt(original.authority.scope, identity)
        return try {
            when (createdEnrollmentNextStep(
                hasFinishedUnknown = manager.coordinator.hasFinishedKnownAttempt(original.authority, attempt),
                observeExactKey = { refreshRemoteDeviceKeyStatus(identity, original) },
                originalAndExpectedCurrent = { originalOperation === original && original.isCurrent() &&
                    remoteRequestScopeIsCurrent(requestScope) && deviceIdentityStore.isCurrent(expectedState) },
            )) {
                CreatedEnrollmentNextStep.ACTIVE_CHECKPOINTED -> return true
                CreatedEnrollmentNextStep.HELD -> return false
                CreatedEnrollmentNextStep.POST_SAME_INTENT -> Unit
            }
            val valid = manager.runKnownJournalAttempt<PersistedRemoteDeviceIdentity>(original, attempt, {
                val spki = requireNotNull(deviceKeyStore.publicKeySpki(identity.keyId))
                try {
                    check(remoteSha256Hex(spki) == identity.fingerprintSha256)
                    val signedAt = java.time.Instant.now().epochSecond
                    val requestNonce = UUID.randomUUID().toString()
                    val statement = canonicalRemoteEnrollmentStatement(
                        companyId = identity.companyId,
                        installationId = identity.installationId,
                        keyId = identity.keyId,
                        enrollmentId = identity.enrollmentId,
                        signedAtEpochSeconds = signedAt,
                        nonce = requestNonce,
                        spkiSha256 = identity.fingerprintSha256,
                    )
                    val signature = try {
                        remoteBase64Url(deviceKeyStore.sign(identity.keyId, statement))
                    } finally {
                        statement.fill(0)
                    }
                    val response = api.enrollDeviceKey(
                        RemoteDeviceKeyEnrollmentRequest(
                            keyId = identity.keyId,
                            enrollmentId = identity.enrollmentId,
                            installationId = identity.installationId,
                            publicKeySpki = remoteBase64Url(spki),
                            signedAtEpochSeconds = signedAt,
                            nonce = requestNonce,
                            signature = signature,
                        ),
                        requestScope,
                    )
                    check(originalOperation === original && original.isCurrent() && remoteRequestScopeIsCurrent(requestScope))
                    requireNotNull(validatedRemoteDeviceStatus(identity, response)) { "Invalid remote enrollment response." }
                } finally { spki.fill(0) }
            }, { updated -> checkpointDeviceIdentity(original, requestScope, expectedState, updated) })
            if (originalOperation !== original || !original.isCurrent() || !remoteRequestScopeIsCurrent(requestScope)) return false
            forceDeviceKeyStatusCheck = false
            applyPersistedRemoteDeviceStatus(valid, firstPendingPoll = true, original)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is RemoteDeviceIdentityUnavailable) throw error
            if (originalOperation !== original || !original.isCurrent()) return false
            observeDeviceEndpointFailure(error)
            nextDeviceKeyStatusCheckAt = elapsedRealtime() + DEVICE_KEY_RETRY_MILLIS
            forceDeviceKeyStatusCheck = false
            if (originalOperation === original && original.isCurrent()) _uiState.value = _uiState.value.copy(
                statusMessage = "Remote support device pairing is waiting for a verified connection.",
            )
            false
        }
    }

    private suspend fun refreshRemoteDeviceKeyStatus(
        identity: PersistedRemoteDeviceIdentity,
        original: OriginalOperationBinding,
    ): Boolean {
        if (originalOperation !== original || !original.isCurrent()) return false
        val expectedState = deviceIdentityStore.expectedState(identity) ?: return false
        val requestScope = currentRemoteRequestTag()
            ?.takeIf {
                it.scope.companyId == identity.companyId &&
                    it.scope.installationId == identity.installationId
            }
            ?: return false
        nextDeviceKeyStatusCheckAt = elapsedRealtime() + DEVICE_KEY_STATUS_INTERVAL_MILLIS
        return try {
            val manager = cloud.dcompany.erp.DCompanyApp.instance.handoverOperations
            val attempt = remoteEnrollmentAttempt(original.authority.scope, identity)
            val observed = KnownRemoteKeyObservation(attempt, identity.keyId, identity.installationId)
            val response = manager.observeKnownRemoteKey<RemoteDeviceKeyStatusResponse>(original, observed) { permit ->
                val value = api.deviceKeyStatus(identity.keyId, identity.installationId, requestScope)
                check(originalOperation === original && original.isCurrent() && remoteRequestScopeIsCurrent(requestScope))
                val positive = validatedRemoteDeviceStatus(identity, value, activeOnly = true)
                if (positive != null) {
                    manager.coordinator.checkpointKnownAttempt(permit, attempt, original.isCurrent) {
                        checkpointDeviceIdentity(original, requestScope, expectedState, positive)
                    }
                }
                value
            }
            if (originalOperation !== original || !original.isCurrent() || !remoteRequestScopeIsCurrent(requestScope)) return false
            forceDeviceKeyStatusCheck = false
            val valid = validatedRemoteDeviceStatus(identity, response) ?: return false
            if (valid.keyStatus != RemoteDeviceKeyStatus.ACTIVE) {
                // Local update/follow-up cannot inherit the restricted key GET permission.
                checkpointDeviceIdentity(original, requestScope, expectedState, valid)
            }
            applyPersistedRemoteDeviceStatus(valid, firstPendingPoll = false, original)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is RemoteDeviceIdentityUnavailable) throw error
            if (originalOperation !== original || !original.isCurrent()) return false
            // A generic 401 is never authority to destroy a still-valid local
            // key. AuthInterceptor already refreshed bearer once; retain the
            // key and require a successful signed status reconciliation.
            observeDeviceEndpointFailure(error)
            nextDeviceKeyStatusCheckAt = elapsedRealtime() + DEVICE_KEY_RETRY_MILLIS
            forceDeviceKeyStatusCheck = false
            _uiState.value = _uiState.value.copy(
                statusMessage = "Remote support device approval could not be verified yet.",
            )
            false
        }
    }

    private suspend fun checkpointDeviceIdentity(original: OriginalOperationBinding, requestScope: RemoteRequestScopeTag,
        expected: RemoteDeviceIdentityStore.ExpectedState, updated: PersistedRemoteDeviceIdentity) {
        var saved = false
        val owned = cacheIsolation.commitIfCurrent(original.cacheLease) {
            cloud.dcompany.erp.DCompanyApp.instance.tokens.commitIfOriginalLoginCurrent(original.loginLease) {
                check(originalOperation === original && original.isCurrent() && remoteRequestScopeIsCurrent(requestScope))
                saved = deviceIdentityStore.compareAndSet(expected, updated)
            }
        }
        check(owned && saved) { "The owned remote device checkpoint changed or could not be saved." }
    }

    private suspend fun applyPersistedRemoteDeviceStatus(valid: PersistedRemoteDeviceIdentity,
        firstPendingPoll: Boolean, original: OriginalOperationBinding): Boolean {
        if (originalOperation !== original || !original.isCurrent()) return false
        val status = requireNotNull(valid.keyStatus)
        deleteRetiredDeviceKeys()
        publishDeviceIdentity(valid)
        return when (status) {
            RemoteDeviceKeyStatus.ACTIVE -> {
                pendingDeviceKeyDeadline = null
                true
            }
            RemoteDeviceKeyStatus.PENDING -> {
                val deadline = remotePendingDeviceKeyDeadline(
                    serverTimeRaw = valid.serverTime.orEmpty(),
                    pendingExpiresAtRaw = valid.pendingExpiresAt.orEmpty(),
                    nowElapsedMillis = elapsedRealtime(),
                ) ?: return false
                pendingDeviceKeyDeadline = valid.keyId to deadline
                nextDeviceKeyStatusCheckAt = elapsedRealtime() + if (firstPendingPoll) {
                    DEVICE_KEY_FIRST_STATUS_MILLIS
                } else {
                    DEVICE_KEY_STATUS_INTERVAL_MILLIS
                }
                false
            }
            RemoteDeviceKeyStatus.REVOKED,
            RemoteDeviceKeyStatus.EXPIRED,
            -> {
                pendingDeviceKeyDeadline = null
                _uiState.value.activeSession?.let { active ->
                    requestSessionEnd(active, "capture_stopped", original)
                }
                rotateAndEnrollRemoteDeviceKey(valid, original)
            }
            RemoteDeviceKeyStatus.CREATED -> false
        }
    }

    private suspend fun rotateAndEnrollRemoteDeviceKey(
        previous: PersistedRemoteDeviceIdentity,
        original: OriginalOperationBinding,
    ): Boolean {
        if (originalOperation !== original || !original.isCurrent()) return false
        if (!deviceKeyStore.delete(previous.keyId)) return false
        if (!deviceIdentityStore.remove(
                previous.companyId,
                previous.installationId,
                previous.keyId,
            )
        ) return false
        val replacement = deviceIdentityStore.enrollmentCandidate(
            previous.companyId,
            previous.installationId,
        ) ?: createRemoteDeviceIdentity(previous.companyId, previous.installationId)
        ?: return false
        forceDeviceKeyStatusCheck = true
        return when (replacement.keyStatus) {
            RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(replacement, original)
            RemoteDeviceKeyStatus.PENDING -> refreshRemoteDeviceKeyStatus(replacement, original)
            RemoteDeviceKeyStatus.ACTIVE -> true
            else -> false
        }
    }

    private fun publishDeviceIdentity(identity: PersistedRemoteDeviceIdentity) {
        _uiState.value = _uiState.value.copy(
            deviceKeyStatus = identity.keyStatus,
            displayedKeyId = identity.keyId,
            pairingCode = identity.pairingCode,
            statusMessage = when (identity.keyStatus) {
                RemoteDeviceKeyStatus.CREATED -> "Preparing secure remote-support pairing."
                RemoteDeviceKeyStatus.PENDING ->
                    "The owner must enter this tablet's one-time pairing code before support can start."
                RemoteDeviceKeyStatus.REVOKED -> "Remote-support device approval was revoked."
                RemoteDeviceKeyStatus.EXPIRED -> "Remote-support device pairing expired."
                RemoteDeviceKeyStatus.ACTIVE -> _uiState.value.statusMessage
                    ?.takeUnless { it in DEVICE_PAIRING_STATUS_MESSAGES }
                null -> _uiState.value.statusMessage
            },
        )
    }

    private fun deleteRetiredDeviceKeys() {
        deviceIdentityStore.retiredKeyIds().forEach { keyId ->
            if (deviceKeyStore.delete(keyId)) {
                deviceIdentityStore.acknowledgeRetiredKeyDeleted(keyId)
            }
        }
    }

    private fun currentRemoteDeviceIdentity(request: Request): PersistedRemoteDeviceIdentity? {
        val bound = request.tag(RemoteRequestScopeTag::class.java)?.scope ?: return null
        if (!verifiedScope || bound != journalScope || bound != currentCacheJournalScope()) return null
        val statusKeyId = REMOTE_DEVICE_STATUS_PATH.matchEntire(request.url.encodedPath)
            ?.groupValues
            ?.get(1)
        return if (statusKeyId != null) {
            deviceIdentityStore.identityForKey(
                bound.companyId,
                bound.installationId,
                statusKeyId,
            )
        } else {
            deviceIdentityStore.activeIdentity(bound.companyId, bound.installationId)
        }
    }

    private suspend fun sendHeartbeat(original: cloud.dcompany.erp.core.handover.OriginalOperationBinding?) {
        if (original == null || !original.isCurrent() || !canUseRemoteApi()) return
        try {
            cloud.dcompany.erp.DCompanyApp.instance.handoverOperations.runRepeatableRemoteHeartbeat(original) {
                sendHeartbeatRequest()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A closed/stale advisory attempt is deferred, never new authority. */ }
    }

    private suspend fun sendHeartbeatRequest() {
        if (!canUseRemoteApi()) return
        val requestScope = currentRemoteRequestTag() ?: return
        val installationId = installationIdProvider() ?: return
        val consent = store.snapshot().consentChoice
        val capability = if (
            consent == RemoteConsentChoice.ALLOWED &&
            notification.canShowPersistentIndicator()
        ) {
            RemoteSharingCapability.AVAILABLE
        } else {
            RemoteSharingCapability.PERMISSION_REQUIRED
        }
        try {
            api.heartbeat(
                RemoteDeviceHeartbeatRequest(
                    installationId = installationId,
                    protocolVersion = REMOTE_ASSISTANCE_PROTOCOL_VERSION,
                    sharingCapability = capability.wireValue,
                ),
                requestScope,
            ).requireRemoteAssistanceSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeDeviceEndpointFailure(error)
            // Heartbeat is retried by the bounded foreground poll loop.
        }
    }

    private suspend fun pollDeviceState(initialPoll: Boolean) = pollMutex.withLock {
        val original = originalOperation?.takeIf { it.isCurrent() } ?: return@withLock
        if (!canUseRemoteApi()) return@withLock
        val requestScope = currentRemoteRequestTag() ?: return@withLock
        val installationId = installationIdProvider() ?: return@withLock
        val currentSessionId = _uiState.value.activeSession?.sessionId
        val afterSequence = currentSessionId?.let { store.afterSequence(it, initialPoll) } ?: 0L
        try {
            val response = api.deviceState(installationId, afterSequence, requestScope)
            if (!canUseRemoteApi() || !remoteRequestScopeIsCurrent(requestScope)) return@withLock
            if (!pendingOriginCurrent(original)) return@withLock
            handleDeviceState(response, original)
            _uiState.value = _uiState.value.copy(
                notificationReady = notification.canShowPersistentIndicator(),
                statusMessage = _uiState.value.statusMessage
                    ?.takeUnless { it == CONNECTION_MESSAGE },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeDeviceEndpointFailure(error)
            _uiState.value = _uiState.value.copy(statusMessage = CONNECTION_MESSAGE)
        }
    }

    private suspend fun handleDeviceState(response: RemoteDeviceStateResponse, original: OriginalOperationBinding) {
        if (!pendingOriginCurrent(original)) return
        val installationId = installationIdProvider() ?: return
        val authenticatedUserId = journalScope?.userId ?: return
        val displayedSession = response.session?.takeIf {
            it.installationId == installationId && isCanonicalUuidV4(it.id) && isCanonicalUuidV4(it.grantId) &&
                it.status in setOf("requested", "active")
        }
        if (!publishSharingState(original) { localSharing.observe(original, displayedSession?.grantId, displayedSession?.id) }) return
        _uiState.value.activeSession?.let { previous ->
            if (!localSharing.allows(original, previous.grantId, previous.sessionId)) {
                clearActiveSessionFor(original, null)?.join()
                if (!pendingOriginCurrent(original)) return
            }
        }
        _uiState.value = _uiState.value.copy(displayedSessionId = displayedSession?.id)
        val protectedActiveGrantId = response.session
            ?.takeIf { it.status == "active" }
            ?.grantId
        if (protectedActiveGrantId == null || isCanonicalUuidV4(protectedActiveGrantId)) {
            store.retireExpiredInstallationGrants(
                serverTimeRaw = response.serverTime,
                protectedActiveGrantId = protectedActiveGrantId,
            )
        }
        val prompt = pendingRemoteGrant(
            grants = response.pendingGrants,
            serverTimeRaw = response.serverTime,
            expectedInstallationId = installationId,
            expectedUserId = authenticatedUserId,
        )
        if (prompt != null) {
            val replay = store.pendingDecisionForGrant(prompt.grantId)
            if (replay == null) {
                _uiState.value = _uiState.value.copy(pendingGrant = prompt)
            } else {
                sendGrantDecision(replay, original)
                _uiState.value = _uiState.value.copy(pendingGrant = null)
            }
        } else {
            _uiState.value = _uiState.value.copy(pendingGrant = null)
        }

        // Active-only device/state is not an exact terminal action receipt.
        when {
            response.session == null -> {
                store.markCommandSessionTerminal()
                store.retireExpiredGrant(response.serverTime)
            }
            response.session.status !in setOf("requested", "active") -> {
                store.markCommandSessionTerminal(response.session.id)
                store.retireExpiredGrant(response.serverTime)
            }
        }

        val evaluation = evaluateRemoteSession(
            session = response.session,
            serverTimeRaw = response.serverTime,
            nowElapsedMillis = elapsedRealtime(),
            expectedInstallationId = installationId,
        )
        val persisted = store.snapshot()
        val consent = persisted.consentChoice
        val active = evaluation.active
        val locallyEnded = persisted.pendingSessionEnd?.sessionId == active?.sessionId
        when {
            active == null -> clearActiveSessionFor(original, null)?.join()
            !store.authorizeSession(
                grantId = active.grantId,
                sessionId = active.sessionId,
                serverTimeRaw = response.serverTime,
            ) -> requestSessionEnd(
                active,
                if (consent == RemoteConsentChoice.REVOKED) "permission_revoked" else "capture_stopped", original,
            )
            !notification.canShowPersistentIndicator() -> {
                _uiState.value = _uiState.value.copy(
                    notificationReady = false,
                    statusMessage = "Remote support could not start because its visible notification is disabled.",
                )
                requestSessionEnd(active, "capture_stopped", original)
            }
            !appForeground -> requestSessionEnd(active, "app_backgrounded", original)
            locallyEnded -> sendPendingSessionEnd(original)
            !uiGateway.hasVisibleHost() -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Remote support could not start without its visible in-app Stop control.",
                )
                requestSessionEnd(active, "capture_stopped", original)
            }
            else -> activateSession(active, original)
        }

        val accepted = _uiState.value.activeSession
        if (accepted != null && accepted.sessionId == active?.sessionId) {
            if (sharingAllowed(original, accepted) && store.activateCommandSession(accepted.sessionId)) {
                startCommandProcessing(
                    original = original,
                    session = accepted,
                    serverTimeRaw = response.serverTime,
                    commands = response.commands,
                )
            } else if (sharingAllowed(original, accepted)) {
                requestSessionEnd(accepted, "capture_stopped", original)
            }
        }
        refreshConsentUi()
    }

    private fun pendingAllowsSharing(session: RemoteActiveSession): Boolean {
        val pending = store.snapshot()
        return store.inspectHandoverWork().verified && pending.consentChoice == RemoteConsentChoice.ALLOWED &&
            pending.grantId == session.grantId && pending.pendingDecision == null &&
            pending.pendingRevocation == null && pending.pendingSessionEnd == null
    }
    private fun sharingAllowed(original: OriginalOperationBinding, session: RemoteActiveSession): Boolean =
        pendingOriginCurrent(original) && pendingAllowsSharing(session) && localSharing.allows(original, session.grantId, session.sessionId)

    private suspend fun activateSession(session: RemoteActiveSession, original: OriginalOperationBinding) {
        if (!pendingOriginCurrent(original)) return
        val held = _uiState.value.activeSession?.takeIf { localSharing.keepRetryTarget(original, it.grantId, it.sessionId) }
        val admittedSession = clampRemoteSessionDeadline(held ?: _uiState.value.activeSession, held ?: session)
        val sameSession = _uiState.value.activeSession?.sessionId == admittedSession.sessionId
        if (!sameSession) originalCommit.withOriginal<Job?>(original) {
            if (pendingOriginCurrent(original)) cancelCommandExecution() else null
        }?.join()
        if (!pendingOriginCurrent(original)) return
        if (!sharingAllowed(original, admittedSession)) {
            clearActiveSessionFor(original, null)?.join()
            publishSharingState(original) {
                if (sharingAllowed(original, admittedSession)) return@publishSharingState
                notification.cancel()
                _uiState.value = _uiState.value.copy(activeSession = admittedSession,
                    displayedSessionId = admittedSession.sessionId, sharingConfirmed = false,
                    canResumeSharing = pendingAllowsSharing(admittedSession) && !localSharing.hasWriteHold() &&
                        appForeground && uiGateway.hasVisibleHost() && notification.canShowPersistentIndicator(),
                    privacyProtected = true, statusMessage = "Sharing is paused. Confirm Resume sharing for this support session. Stop remains available.")
            }
            return
        }
        val sharingPublished = publishSharingState(original) {
            if (!sharingAllowed(original, admittedSession)) return@publishSharingState
            val remaining = (admittedSession.deadlineElapsedMillis - elapsedRealtime()).coerceAtLeast(1_000L)
            if (!notification.show(remaining, notificationTarget(admittedSession) ?: return@publishSharingState, sharingPaused = !online.value)) {
                requestSessionEnd(admittedSession, "capture_stopped", original)
                return@publishSharingState
            }
            _uiState.value = _uiState.value.copy(activeSession = admittedSession,
                displayedSessionId = admittedSession.sessionId, sharingConfirmed = true, canResumeSharing = false,
                notificationReady = true, privacyProtected = true,
                statusMessage = if (sameSession) _uiState.value.statusMessage else null)
        }
        if (sharingPublished && sharingAllowed(original, admittedSession)) startCaptureLoop(original)
    }

    private fun startCaptureLoop(original: OriginalOperationBinding) {
        val registered = originalCommit.withOriginal<Job?>(original) {
            if (!pendingOriginCurrent(original)) return@withOriginal null
            captureJobs.reserve(scope, original) {
            var consecutiveFailures = 0
            while (isActive && pendingOriginCurrent(original)) {
                val startedAt = elapsedRealtime()
                val session = _uiState.value.activeSession ?: break
                if (!sharingAllowed(original, session)) break
                val indicatorVisible = notification.canShowPersistentIndicator() &&
                    notification.isPersistentIndicatorPosted()
                if (!remoteFrameAdmissionAllowed(
                        appForeground = appForeground,
                        notificationVisible = indicatorVisible,
                        nowElapsedMillis = startedAt,
                        deadlineElapsedMillis = session.deadlineElapsedMillis,
                        activeSessionMatches = true,
                    )
                ) {
                    if (!indicatorVisible) {
                        _uiState.value = _uiState.value.copy(
                            notificationReady = false,
                            statusMessage = "Remote support stopped because its visible notification was disabled.",
                        )
                    }
                    requestSessionEnd(
                        session,
                        if (appForeground) "capture_stopped" else "app_backgrounded", original,
                    )
                    break
                }
                if (!online.value) {
                    delay(REMOTE_FRAME_INTERVAL_MILLIS)
                    continue
                }
                val preRoute = uiGateway.routeSnapshot()
                val prePrivacy = privacy.snapshot()
                val preDisposition = remoteCaptureDisposition(
                    routeKey = preRoute.key,
                    sensitiveOverlayVisible = prePrivacy.blocked,
                    appForeground = appForeground,
                )
                var frame = frameEncoder.encode(preDisposition)
                val postRoute = uiGateway.routeSnapshot()
                val postPrivacy = privacy.snapshot()
                val postDisposition = remoteCaptureDisposition(
                    routeKey = postRoute.key,
                    sensitiveOverlayVisible = postPrivacy.blocked,
                    appForeground = appForeground,
                )
                if (mustReplaceWithPrivacyPlaceholder(
                        capturedPrivacyPlaceholder = frame.privacyPlaceholder,
                        beforeRoute = preRoute,
                        afterRoute = postRoute,
                        beforePrivacy = prePrivacy,
                        afterPrivacy = postPrivacy,
                        afterDisposition = postDisposition,
                    )
                ) {
                    frame.bytes.fill(0)
                    frame = frameEncoder.encode(RemoteCaptureDisposition.PRIVACY_PLACEHOLDER)
                }
                ensureActive()
                val beforeUpload = elapsedRealtime()
                val uploadIndicatorVisible = notification.canShowPersistentIndicator() &&
                    notification.isPersistentIndicatorPosted()
                if (!sharingAllowed(original, session) || !remoteFrameAdmissionAllowed(
                        appForeground = appForeground,
                        notificationVisible = uploadIndicatorVisible,
                        nowElapsedMillis = beforeUpload,
                        deadlineElapsedMillis = session.deadlineElapsedMillis,
                        activeSessionMatches = _uiState.value.activeSession?.sessionId == session.sessionId,
                    )
                ) {
                    frame.bytes.fill(0)
                    if (!uploadIndicatorVisible) {
                        _uiState.value = _uiState.value.copy(
                            notificationReady = false,
                            statusMessage = "Remote support stopped because its visible notification was disabled.",
                        )
                    }
                    requestSessionEnd(
                        session,
                        if (appForeground) "capture_stopped" else "app_backgrounded", original,
                    )
                    break
                }
                _uiState.value = _uiState.value.copy(
                    privacyProtected = frame.privacyPlaceholder,
                )
                val uploaded = try {
                    val requestScope = currentRemoteRequestTag()
                        ?: error("authenticated remote scope unavailable")
                    api.uploadFrame(
                        sessionId = session.sessionId,
                        installationId = installationIdProvider() ?: error("installation unavailable"),
                        frameId = UUID.randomUUID().toString(),
                        frameSequence = nextFrameSequence(),
                        width = frame.width,
                        height = frame.height,
                        jpeg = frame.bytes.toRequestBody(JPEG_MEDIA_TYPE),
                        requestScope = requestScope,
                    ).requireRemoteAssistanceSuccess()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    observeDeviceEndpointFailure(error)
                    false
                } finally {
                    frame.bytes.fill(0)
                }
                consecutiveFailures = if (uploaded) 0 else consecutiveFailures + 1
                if (consecutiveFailures >= MAX_CONSECUTIVE_FRAME_FAILURES) {
                    requestSessionEnd(session, "capture_stopped", original)
                    break
                }
                val wait = REMOTE_FRAME_INTERVAL_MILLIS - (elapsedRealtime() - startedAt)
                if (wait > 0L) delay(wait)
            }
            }
        }
        registered?.start()
    }

    private fun startCommandProcessing(
        original: OriginalOperationBinding,
        session: RemoteActiveSession,
        serverTimeRaw: String,
        commands: List<RemoteCommandResponse>,
    ) {
        if (commands.isEmpty() || !pendingOriginCurrent(original)) return
        val registered = originalCommit.withOriginal<Job?>(original) {
            if (!pendingOriginCurrent(original)) return@withOriginal null
            commandJobs.reserve(scope, original) {
                processCommands(original, session, serverTimeRaw, commands)
            }
        }
        registered?.start()
    }

    private suspend fun processCommands(
        original: OriginalOperationBinding,
        session: RemoteActiveSession,
        serverTimeRaw: String,
        commands: List<RemoteCommandResponse>,
    ) {
        if (!pendingOriginCurrent(original)) return
        var expectedSequence = store.afterSequence(session.sessionId, initialPoll = false) + 1L
        // If the server accepted a result immediately before cancellation or
        // process death, that command is no longer returned as pending. Replay
        // the durable completed receipt before treating the next server command
        // as a sequence gap. The business result stays immutable; request proof
        // nonce/timestamp are regenerated by the network interceptor.
        while (commands.none { it.sequence == expectedSequence }) {
            val completed = store.completedReceiptAwaitingAck(session.sessionId) ?: break
            if (completed.sequence != expectedSequence || !sendCommandReceipt(session, completed)) {
                return
            }
            expectedSequence = store.afterSequence(session.sessionId, initialPoll = false) + 1L
        }
        if (!remoteCommandBatchHasContiguousSequence(
                commands = commands,
                expectedSequence = expectedSequence,
                expectedSessionId = session.sessionId,
            )
        ) {
            requestSessionEnd(session, "capture_stopped", original)
            return
        }
        processRemoteCommandsInOrder(commands, expectedSequence) commandLoop@{ command ->
            if (!commandSessionIsCurrent(session)) return@commandLoop false
            val existing = store.commandReceipt(command.commandId)
            if (
                existing != null &&
                (existing.sessionId != session.sessionId || existing.sequence != command.sequence)
            ) {
                requestSessionEnd(session, "capture_stopped", original)
                return@commandLoop false
            }
            val receipt = when {
                existing?.state == RECEIPT_COMPLETED -> existing
                existing?.state == RECEIPT_RESERVED -> store.recoverInterruptedCommand(command.commandId)
                else -> {
                    val diagnosticsRouteBeforeReservation = if (command.type == "collect_diagnostics") {
                        uiGateway.routeSnapshot()
                    } else {
                        null
                    }
                    val diagnosticsPrivacyBeforeReservation = if (command.type == "collect_diagnostics") {
                        privacy.snapshot()
                    } else {
                        null
                    }
                    if (!commandSessionIsCurrent(session)) return@commandLoop false
                    val reserved = store.reserveCommand(
                        sessionId = session.sessionId,
                        commandId = command.commandId,
                        sequence = command.sequence,
                    ) ?: run {
                        requestSessionEnd(session, "capture_stopped", original)
                        return@commandLoop false
                    }
                    val validation = validateRemoteCommand(
                        command = command,
                        session = session,
                        serverTimeRaw = serverTimeRaw,
                        foreground = appForeground,
                        sensitiveOverlayVisible = privacy.snapshot().blocked,
                    )
                    val result = if (validation.command == null) {
                        RemoteUiCommandResult(false, validation.rejectionReason ?: "unsupported_command")
                    } else {
                        executeCommand(
                            session = session,
                            command = validation.command,
                            diagnosticsRouteBeforeReservation = diagnosticsRouteBeforeReservation,
                            diagnosticsPrivacyBeforeReservation = diagnosticsPrivacyBeforeReservation,
                        )
                    }
                    if (!commandSessionIsCurrent(session)) return@commandLoop false
                    store.completeCommand(
                        commandId = reserved.commandId,
                        outcome = if (result.succeeded) "acknowledged" else "rejected",
                        reasonCode = result.reasonCode,
                    )
                }
            } ?: run {
                requestSessionEnd(session, "capture_stopped", original)
                return@commandLoop false
            }
            if (!commandSessionIsCurrent(session)) return@commandLoop false
            val acknowledged = sendCommandReceipt(session, receipt)
            if (!acknowledged) return@commandLoop false
            true
        }
    }

    private suspend fun executeCommand(
        session: RemoteActiveSession,
        command: RemoteSemanticCommand,
        diagnosticsRouteBeforeReservation: RemoteUiRouteSnapshot?,
        diagnosticsPrivacyBeforeReservation: RemotePrivacySnapshot?,
    ): RemoteUiCommandResult {
        return try {
            // Recheck immediately before execution as well as during parsing.
            // A sensitive Compose surface may have appeared while the command
            // was being journalled; commands never outrank local staff privacy.
            val admissionFailure = semanticCommandAdmissionFailure(session)
            if (admissionFailure != null) {
                RemoteUiCommandResult(false, admissionFailure)
            } else if (privacy.snapshot().blocked) {
                RemoteUiCommandResult(false, "permission_denied")
            } else when (command) {
                is RemoteSemanticCommand.Navigate -> uiGateway.navigate(command.module) {
                    semanticCommandAdmissionFailure(session) == null
                }.also { result ->
                    if (result.succeeded && commandSessionIsCurrent(session)) {
                        _uiState.value = _uiState.value.copy(
                            lastCommandLabel = "Owner opened ${command.module.wireValue}",
                        )
                    }
                }
                RemoteSemanticCommand.Refresh -> refreshCurrentModule(session).also { result ->
                    if (result.succeeded && commandSessionIsCurrent(session)) {
                        _uiState.value = _uiState.value.copy(lastCommandLabel = "Owner refreshed this module")
                    }
                }
                RemoteSemanticCommand.CollectDiagnostics -> {
                    val reservedRoute = diagnosticsRouteBeforeReservation
                        ?: return RemoteUiCommandResult(false, "execution_failed")
                    val reservedPrivacy = diagnosticsPrivacyBeforeReservation
                        ?: return RemoteUiCommandResult(false, "execution_failed")
                    val before = uiGateway.semanticAdmission {
                        semanticCommandAdmissionFailure(session) == null
                    }
                    val beforeRoute = before.route
                        ?: return RemoteUiCommandResult(
                            false,
                            before.reasonCode ?: "permission_denied",
                        )
                    val beforePrivacy = privacy.snapshot()
                    if (
                        !remoteUiAdmissionRemainedStable(reservedRoute, beforeRoute) ||
                        !remotePrivacyAdmissionRemainedStable(reservedPrivacy, beforePrivacy)
                    ) {
                        return RemoteUiCommandResult(false, "permission_denied")
                    }
                    val collected = collectDiagnostics()
                    if (!commandSessionIsCurrent(session)) {
                        RemoteUiCommandResult(false, "session_inactive")
                    } else {
                        val after = uiGateway.semanticAdmission {
                            semanticCommandAdmissionFailure(session) == null
                        }
                        val afterRoute = after.route
                        val afterPrivacy = privacy.snapshot()
                        if (
                            afterRoute == null ||
                            !remoteUiAdmissionRemainedStable(beforeRoute, afterRoute) ||
                            !remotePrivacyAdmissionRemainedStable(beforePrivacy, afterPrivacy)
                        ) {
                            RemoteUiCommandResult(
                                false,
                                after.reasonCode ?: "permission_denied",
                            )
                        } else if (collected) {
                            _uiState.value = _uiState.value.copy(
                                lastCommandLabel = "Safe diagnostics collected",
                            )
                            RemoteUiCommandResult(true)
                        } else {
                            RemoteUiCommandResult(false, "execution_failed")
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteUiCommandResult(false, "execution_failed")
        }
    }

    private suspend fun refreshCurrentModule(session: RemoteActiveSession): RemoteUiCommandResult {
        val route = uiGateway.routeKey()
        semanticCommandAdmissionFailure(session)?.let {
            return RemoteUiCommandResult(false, it)
        }
        return when (route) {
            "help" -> uiGateway.refreshCurrent {
                semanticCommandAdmissionFailure(session) == null
            }.takeIf { commandSessionIsCurrent(session) }
                ?: RemoteUiCommandResult(false, "session_inactive")
            else -> RemoteUiCommandResult(false, "permission_denied")
        }
    }

    private suspend fun sendCommandReceipt(
        session: RemoteActiveSession,
        receipt: RemoteCommandReceipt,
    ): Boolean {
        if (!commandSessionIsCurrent(session)) return false
        val requestScope = currentRemoteRequestTag() ?: return false
        if (store.commandReceipt(receipt.commandId) != receipt) return false
        val installationId = installationIdProvider() ?: return false
        val outcome = receipt.outcome ?: return false
        return try {
            api.commandResult(
                commandId = receipt.commandId,
                body = RemoteCommandResultRequest(
                    installationId = installationId,
                    sequence = receipt.sequence,
                    outcome = outcome,
                    reasonCode = receipt.reasonCode,
                ),
                requestScope,
            ).requireRemoteAssistanceSuccess()
            if (
                !remoteRequestScopeIsCurrent(requestScope) ||
                !commandSessionIsCurrent(session)
            ) return false
            store.acknowledgeCommand(receipt.commandId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeDeviceEndpointFailure(error)
            false
        }
    }

    private fun commandSessionIsCurrent(session: RemoteActiveSession): Boolean =
        originalOperation?.let { sharingAllowed(it, session) } == true && remoteCommandExecutionAllowed(
            expected = session,
            current = _uiState.value.activeSession,
            appForeground = appForeground,
            notificationVisible = notification.canShowPersistentIndicator() &&
                notification.isPersistentIndicatorPosted(),
            nowElapsedMillis = elapsedRealtime(),
        )

    private fun semanticCommandAdmissionFailure(session: RemoteActiveSession): String? = when {
        !commandSessionIsCurrent(session) -> "session_inactive"
        privacy.snapshot().blocked -> "permission_denied"
        else -> null
    }

    private fun recordStoppedSession(session: RemoteActiveSession, reason: String, original: OriginalOperationBinding,
        explicitRetry: Boolean): PendingRemoteSessionEnd? {
        val effectiveReason = localSharing.heldEndReason(original, session.sessionId) ?: reason
        return originalCommit.withOriginal<PendingRemoteSessionEnd?>(original) {
            if (!pendingOriginCurrent(original)) return@withOriginal null
            localSharing.privacyRecord(RemoteLocalPrivacyAction(original, RemotePendingKind.END, session.sessionId, effectiveReason), explicitRetry,
            stop = { clearActiveSessionFor(original, null) },
            record = { recordOriginalPending(original) { expected -> store.recordSessionEnd(session.sessionId, effectiveReason, expected) } },
            matches = { it.sessionId == session.sessionId && it.reason == effectiveReason })
        }
    }
    private fun requestSessionEnd(session: RemoteActiveSession, reason: String, original: OriginalOperationBinding,
        explicitRetry: Boolean = false) {
        if (!pendingOriginCurrent(original)) return
        var commandToJoin: Job? = null
        val effectiveReason = localSharing.heldEndReason(original, session.sessionId) ?: reason
        val saved = originalCommit.withOriginal<PendingRemoteSessionEnd?>(original) {
            if (!pendingOriginCurrent(original)) return@withOriginal null
            localSharing.privacyRecord(RemoteLocalPrivacyAction(original, RemotePendingKind.END, session.sessionId, effectiveReason), explicitRetry,
            stop = { commandToJoin = clearActiveSessionFor(original, null) },
            record = { recordOriginalPending(original) { expected -> store.recordSessionEnd(session.sessionId, effectiveReason, expected) } },
            matches = { it.sessionId == session.sessionId && it.reason == effectiveReason })
        }
        if (!pendingOriginCurrent(original)) return
        publishSharingState(original) { _uiState.value = _uiState.value.copy(activeSession = session.takeIf { localSharing.hasWriteHold() },
            sharingConfirmed = false, canResumeSharing = false, privacyProtected = true,
            statusMessage = if (saved == null || localSharing.hasWriteHold())
                "Sharing stopped locally. Stop could not be saved. Tap Stop again to retry this session."
                else "Sharing stopped locally. The saved Stop action is awaiting its verified server receipt.") }
        scope.launch { commandToJoin?.join(); sendPendingSessionEnd(original) }
    }

    private fun clearActiveSessionFor(original: OriginalOperationBinding, message: String?): Job? =
        originalCommit.withOriginal<Job?>(original) { if (pendingOriginCurrent(original)) clearActiveSession(message) else null }
    private fun publishSharingState(original: OriginalOperationBinding, publish: () -> Unit): Boolean =
        originalCommit.withOriginal(original) { if (!pendingOriginCurrent(original)) false else { publish(); true } } == true

    /** Local state and visibility stop immediately; the canceled command is joined before reconciliation. */
    private fun clearActiveSession(message: String?): Job? {
        captureJobs.cancel()
        val commandToJoin = cancelCommandExecution()
        notification.cancel()
        if (_uiState.value.activeSession != null || message != null) {
            _uiState.value = _uiState.value.copy(
                activeSession = _uiState.value.activeSession?.takeIf { active -> originalOperation?.let {
                    pendingOriginCurrent(it) && localSharing.keepRetryTarget(it, active.grantId, active.sessionId)
                } == true },
                sharingConfirmed = false, canResumeSharing = false,
                privacyProtected = true,
                statusMessage = message ?: _uiState.value.statusMessage,
            )
        }
        return commandToJoin
    }

    private fun cancelCommandExecution(): Job? = commandJobs.cancel()

    private suspend fun sendPendingMutations(original: OriginalOperationBinding) {
        if (!pendingOriginCurrent(original)) return
        val snapshot = store.snapshot()
        snapshot.pendingDecision?.let { sendGrantDecision(it, original) }
        sendPendingRevocation(original)
        sendPendingSessionEnd(original)
    }

    private suspend fun sendGrantDecision(mutation: PendingRemoteGrantDecision, original: OriginalOperationBinding) =
        reconcilePendingAction(original, RemotePendingAction.decision(mutation))

    private suspend fun sendPendingRevocation(original: OriginalOperationBinding) {
        if (!pendingOriginCurrent(original)) return
        store.snapshot().pendingRevocation?.let { reconcilePendingAction(original, RemotePendingAction.revoke(it)) }
    }

    private suspend fun sendPendingSessionEnd(original: OriginalOperationBinding) {
        if (!pendingOriginCurrent(original)) return
        store.snapshot().pendingSessionEnd?.let { reconcilePendingAction(original, RemotePendingAction.end(it)) }
    }

    /** Synchronous original-login lock plus exact journal scope; never read B's journal in an old callback. */
    private fun <T> recordOriginalPending(original: OriginalOperationBinding, record: (RemoteAssistanceJournalScope) -> T?): T? {
        if (!pendingOriginCurrent(original)) return null
        val installation = installationIdProvider() ?: return null
        val expected = RemoteAssistanceJournalScope(original.authority.scope.companyId, installation, original.authority.scope.actorId)
        var result: T? = null
        try {
            cloud.dcompany.erp.DCompanyApp.instance.tokens.commitIfOriginalLoginCurrent(original.loginLease) {
                if (pendingOriginCurrent(original)) result = record(expected)
            }
        } catch (_: Exception) { return null }
        return result
    }

    private fun pendingOriginCurrent(original: OriginalOperationBinding): Boolean =
        originalOperation === original && original.isCurrent() && journalScope?.let {
            it.companyId == original.authority.scope.companyId && it.userId == original.authority.scope.actorId
        } == true

    private suspend fun reconcilePendingAction(original: OriginalOperationBinding, action: RemotePendingAction) = mutationMutex.withLock {
        if (!pendingOriginCurrent(original) || !canUseRemoteEnrollmentApi()) return@withLock
        val requestScope = currentRemoteRequestTag() ?: return@withLock
        val expected = store.pendingRecoverySnapshot() ?: return@withLock
        if (!action.matches(expected)) return@withLock
        val key = deviceIdentityStore.activeIdentity(requestScope.scope.companyId, requestScope.scope.installationId)
            ?: return@withLock
        fun stillCurrent() = pendingOriginCurrent(original) && remoteRequestScopeIsCurrent(requestScope) &&
            store.snapshot() == expected && deviceIdentityStore.activeIdentity(key.companyId, key.installationId) == key
        val manager = cloud.dcompany.erp.DCompanyApp.instance.handoverOperations
        try {
            manager.run<Unit>(original) {
                val recovery = RemotePendingRecovery(manager, api, original, requestScope, key.keyId, ::stillCurrent, checkpoint = { resolution ->
                    var saved = false
                    val owned = cacheIsolation.commitIfCurrent(original.cacheLease) {
                        cloud.dcompany.erp.DCompanyApp.instance.tokens.commitIfOriginalLoginCurrent(original.loginLease) {
                            check(stillCurrent())
                            saved = store.checkpointPending(requestScope.scope, expected, action, resolution)
                        }
                    }
                    check(owned && saved) { "The original remote action checkpoint changed or could not be saved." }
                }, postUncertain = { localSharing.discardFreshAllow(original, action.actionId) }, reassertPending = {
                    var durable = false
                    val owned = cacheIsolation.commitIfCurrent(original.cacheLease) {
                        cloud.dcompany.erp.DCompanyApp.instance.tokens.commitIfOriginalLoginCurrent(original.loginLease) {
                            check(stillCurrent())
                            durable = store.reassertPending(requestScope.scope, expected, action)
                        }
                    }
                    check(owned && durable) { "The saved remote action needs a durable original-owned reassertion." }
                })
                val resolution = checkNotNull(recovery.reconcile(action)) { "The exact remote action remains unresolved." }
                if (action.kind == RemotePendingKind.DECISION) publishSharingState(original) {
                    localSharing.acceptFreshAllow(original, action, resolution,
                        store.snapshot().let { it.consentChoice == RemoteConsentChoice.ALLOWED && it.pendingDecision == null && it.pendingRevocation == null && it.pendingSessionEnd == null })
                }
                if (pendingOriginCurrent(original)) _uiState.value = _uiState.value.copy(statusMessage =
                    if (resolution.kind == RemoteResolutionKind.TERMINAL_SUPERSEDED)
                        "This saved support action was superseded by the verified terminal state. No active permission was restored."
                    else _uiState.value.statusMessage)
                cloud.dcompany.erp.core.handover.OperationResult(Unit, cloud.dcompany.erp.core.handover.OperationOutcome.NO_WRITE_SENT)
            }
        } catch (cancelled: CancellationException) { localSharing.discardFreshAllow(original, action.actionId); throw cancelled }
        catch (error: Exception) {
            localSharing.discardFreshAllow(original, action.actionId)
            if (pendingOriginCurrent(original)) {
                observeDeviceEndpointFailure(error)
                _uiState.value = _uiState.value.copy(statusMessage = "A saved support action still needs verified reconciliation. Reconnect and retry.")
            }
        }
        if (pendingOriginCurrent(original)) refreshConsentUi()
    }

    private fun canUseRemoteEnrollmentApi(): Boolean =
        verifiedScope &&
            journalScope != null &&
            journalScope == currentCacheJournalScope() &&
            online.value

    private fun canUseRemoteApi(): Boolean {
        if (!store.inspectHandoverWork().verified || !canUseRemoteEnrollmentApi()) return false
        val scope = cacheIsolation.currentLease()?.scope ?: return false
        val installationId = installationIdProvider() ?: return false
        val original = originalOperation
        return try { deviceIdentityStore.activeIdentity(scope.companyId.trim().lowercase(), installationId.trim().lowercase()) != null }
        catch (_: RemoteDeviceIdentityUnavailable) { showIdentityUnavailable(original); false }
    }

    private fun currentRemoteRequestTag(): RemoteRequestScopeTag? {
        val bound = journalScope ?: return null
        if (!verifiedScope || bound != currentCacheJournalScope()) return null
        return RemoteRequestScopeTag(bound)
    }

    private fun remoteRequestScopeIsCurrent(requestScope: RemoteRequestScopeTag): Boolean =
        verifiedScope &&
            journalScope == requestScope.scope &&
            currentCacheJournalScope() == requestScope.scope

    private fun currentCacheJournalScope(): RemoteAssistanceJournalScope? {
        val cacheScope = cacheIsolation.currentLease()?.scope ?: return null
        val installationId = installationIdProvider()?.trim()?.lowercase() ?: return null
        val scoped = RemoteAssistanceJournalScope(
            companyId = cacheScope.companyId.trim().lowercase(),
            installationId = installationId,
            userId = cacheScope.userId.trim().lowercase(),
        )
        return scoped.takeIf {
            isCanonicalUuidV4(it.companyId) &&
                isCanonicalUuidV4(it.installationId) &&
                isCanonicalUuidV4(it.userId)
        }
    }

    private fun observeDeviceEndpointFailure(error: Throwable) {
        if (error.remoteAssistanceStatus() == 401) {
            // Reconcile through the sole status exception. A generic 401 can
            // also be bearer/session failure and never authorises key deletion.
            forceDeviceKeyStatusCheck = true
            pollWakeups.trySend(Unit)
        }
    }

    private fun nextFrameSequence(): Long = frameSequence.updateAndGet { previous ->
        maxOf(previous + 1L, System.currentTimeMillis().coerceAtLeast(1L))
    }

    private fun Throwable.remoteAssistanceStatus(): Int? = when (this) {
        is ApiException -> status
        is RemoteAssistanceHttpException -> status
        else -> null
    }

    private fun RealtimeEvent.isRemoteAssistanceInvalidation(): Boolean = when (this) {
        is RealtimeEvent.Changed -> resource.trim().lowercase().let { value ->
            value == "remote_assistance" || value == "remote-assistance" || value == "remote_support"
        }
        RealtimeEvent.ReconnectedAfterGap -> true
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 4_000L
        const val DEVICE_KEY_FIRST_STATUS_MILLIS = 5_000L
        const val DEVICE_KEY_STATUS_INTERVAL_MILLIS = 15_000L
        const val DEVICE_KEY_RETRY_MILLIS = 15_000L
        const val MAX_CONSECUTIVE_FRAME_FAILURES = 3
        const val CONNECTION_MESSAGE = "Remote support is waiting for a verified connection."
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        val REMOTE_DEVICE_STATUS_PATH = Regex(
            "/api/v1/remote-assistance/device/keys/" +
                "([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/status",
        )
        val DEVICE_PAIRING_STATUS_MESSAGES = setOf(
            "Preparing secure remote-support pairing.",
            "The owner must enter this tablet's one-time pairing code before support can start.",
            "Remote-support device approval was revoked.",
            "Remote-support device pairing expired.",
            "Remote support device pairing is waiting for a verified connection.",
            "Remote support device approval could not be verified yet.",
        )
    }
}

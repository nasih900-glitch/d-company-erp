package cloud.dcompany.erp.core.remote

import android.content.Context
import android.os.SystemClock
import android.view.Window
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.sync.RealtimeClient
import cloud.dcompany.erp.core.sync.RealtimeEvent
import cloud.dcompany.erp.core.update.InstallationIdentityStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
        ApiClient.createApiWithNetworkProof(
            RemoteAssistanceApi::class.java,
            RemoteDeviceSigningInterceptor(
                identity = ::currentRemoteDeviceIdentity,
                currentScope = {
                    journalScope?.takeIf { verifiedScope && it == currentCacheJournalScope() }
                },
                keyStore = deviceKeyStore,
            ),
        )
    }
    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<RemoteAssistanceUiState> = _uiState.asStateFlow()
    private val pollWakeups = Channel<Unit>(Channel.CONFLATED)
    private val pollMutex = Mutex()
    private val mutationMutex = Mutex()
    private val deviceKeyMutex = Mutex()
    private val lifecycleLock = Any()
    private val frameSequence = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))

    @Volatile private var verifiedScope = false
    @Volatile private var appForeground = false
    @Volatile private var forceDeviceKeyStatusCheck = true
    private var pollJob: Job? = null
    private var captureJob: Job? = null
    private var commandJob: Job? = null
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
                _uiState.value.activeSession?.let { active ->
                    val remaining =
                        (active.deadlineElapsedMillis - elapsedRealtime()).coerceAtLeast(1_000L)
                    if (!notification.show(remaining, sharingPaused = !isOnline)) {
                        requestSessionEnd(active, "capture_stopped")
                    }
                }
                if (isOnline) {
                    ensurePolling()
                    pollWakeups.trySend(Unit)
                }
            }
        }
    }

    fun attachWindow(window: Window) {
        captureSource.attach(window)
    }

    fun detachWindow(window: Window) {
        captureSource.detach(window)
    }

    fun onVerifiedScopeAvailable() {
        val nextScope = currentCacheJournalScope()
        if (nextScope == null) {
            onScopeUnavailable()
            return
        }
        val previousScope = journalScope
        if (previousScope != null && previousScope != nextScope) {
            _uiState.value.activeSession?.let { active ->
                store.recordSessionEnd(active.sessionId, "capture_stopped")
            }
            val commandToJoin = clearActiveSession(
                "Remote support stopped because the authenticated ERP user changed.",
            )
            scope.launch { commandToJoin?.join() }
        }
        journalScope = nextScope
        verifiedScope = true
        forceDeviceKeyStatusCheck = true
        refreshConsentUi()
        ensurePolling()
    }

    fun onScopeUnavailable() {
        verifiedScope = false
        synchronized(lifecycleLock) {
            pollJob?.cancel()
            pollJob = null
        }
        _uiState.value.activeSession?.let { active ->
            store.recordSessionEnd(active.sessionId, "capture_stopped")
        }
        val commandToJoin = clearActiveSession(
            "Remote support stopped because this ERP workspace is no longer active.",
        )
        scope.launch { commandToJoin?.join() }
        journalScope = null
        _uiState.value = initialUiState().copy(
            consent = store.snapshot().consentChoice,
            statusMessage = null,
        )
    }

    /** The in-app Stop banner is part of admission, not optional decoration. */
    fun onVisibleWorkspaceUnavailable() {
        val active = _uiState.value.activeSession ?: return
        requestSessionEnd(active, "capture_stopped")
    }

    fun onAppForegrounded() {
        appForeground = true
        forceDeviceKeyStatusCheck = true
        refreshConsentUi()
        ensurePolling()
    }

    fun onAppBackgrounded() {
        appForeground = false
        synchronized(lifecycleLock) {
            pollJob?.cancel()
            pollJob = null
        }
        val active = _uiState.value.activeSession ?: return
        requestSessionEnd(active, "app_backgrounded")
    }

    fun allowPendingGrant() {
        decidePendingGrant(allow = true)
    }

    fun denyPendingGrant() {
        decidePendingGrant(allow = false)
    }

    fun revokeConsent() {
        val revocation = store.recordRevocation()
        val active = _uiState.value.activeSession
        val commandToJoin = clearActiveSession(null)
        _uiState.value = _uiState.value.copy(
            consent = RemoteConsentChoice.REVOKED,
            pendingGrant = null,
            activeSession = null,
            privacyProtected = true,
            decisionInFlight = revocation != null,
            statusMessage = "Owner remote support has been revoked on this tablet.",
        )
        if (active != null) store.recordSessionEnd(active.sessionId, "permission_revoked")
        scope.launch {
            commandToJoin?.join()
            sendPendingSessionEnd()
            sendPendingRevocation()
            sendHeartbeat()
            pollWakeups.trySend(Unit)
        }
    }

    fun stopByUser() {
        val active = _uiState.value.activeSession ?: return
        requestSessionEnd(active, "user_ended")
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
    fun startDeviceKeyReplacement() {
        if (!appForeground || !canUseRemoteApi()) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Reconnect this signed-in tablet before starting replacement pairing.",
            )
            return
        }
        scope.launch {
            deviceKeyMutex.withLock {
                if (!appForeground || !canUseRemoteApi()) return@withLock
                val bound = journalScope
                    ?.takeIf { it == currentCacheJournalScope() }
                    ?: return@withLock
                val active = deviceIdentityStore.activeIdentity(
                    bound.companyId,
                    bound.installationId,
                )?.takeIf(::localDeviceKeyMatches)
                    ?: return@withLock
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
                    RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(candidate)
                    RemoteDeviceKeyStatus.PENDING -> refreshRemoteDeviceKeyStatus(candidate)
                    else -> Unit
                }
            }
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

    private fun initialUiState(): RemoteAssistanceUiState = RemoteAssistanceUiState(
        consent = store.snapshot().consentChoice,
        notificationReady = notification.canShowPersistentIndicator(),
    )

    private fun refreshConsentUi() {
        val persisted = store.snapshot()
        val visibleGrantId = _uiState.value.pendingGrant?.grantId
        _uiState.value = _uiState.value.copy(
            consent = persisted.consentChoice,
            notificationReady = notification.canShowPersistentIndicator(),
            decisionInFlight = persisted.pendingDecision?.grantId == visibleGrantId ||
                persisted.pendingRevocation != null,
        )
    }

    private fun decidePendingGrant(allow: Boolean) {
        val prompt = _uiState.value.pendingGrant ?: return
        val mutation = store.recordDecision(
            grantId = prompt.grantId,
            allow = allow,
            grantKind = prompt.kind,
            grantExpiresAt = prompt.expiresAt,
        ) ?: run {
            _uiState.value = _uiState.value.copy(
                statusMessage = "This support choice could not be saved safely. Try again.",
            )
            return
        }
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
            sendGrantDecision(mutation)
            sendHeartbeat()
            pollWakeups.trySend(Unit)
        }
    }

    private fun ensurePolling() {
        if (!verifiedScope || !appForeground || cacheIsolation.currentLease() == null) return
        synchronized(lifecycleLock) {
            if (pollJob?.isActive == true) return
            pollJob = scope.launch {
                var initialPoll = true
                var lastHeartbeatAt = Long.MIN_VALUE
                while (isActive && verifiedScope && appForeground) {
                    if (online.value && cacheIsolation.currentLease() != null) {
                        val deviceReady = ensureRemoteDeviceProof()
                        if (deviceReady) {
                            val now = elapsedRealtime()
                            if (
                                lastHeartbeatAt == Long.MIN_VALUE ||
                                now < lastHeartbeatAt ||
                                now - lastHeartbeatAt >= REMOTE_HEARTBEAT_INTERVAL_MILLIS
                            ) {
                                sendHeartbeat()
                                lastHeartbeatAt = now
                            }
                            sendPendingMutations()
                            pollDeviceState(initialPoll)
                            initialPoll = false
                        }
                    }
                    withTimeoutOrNull(POLL_INTERVAL_MILLIS) { pollWakeups.receive() }
                }
            }
        }
    }

    private suspend fun ensureRemoteDeviceProof(): Boolean = deviceKeyMutex.withLock {
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
                RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(candidate)
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
                        refreshRemoteDeviceKeyStatus(candidate)
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
                refreshRemoteDeviceKeyStatus(usable)
                usable = deviceIdentityStore.activeIdentity(companyId, installationId)
            }
            if (usable != null && localDeviceKeyMatches(usable)) return@withLock true
            candidate = deviceIdentityStore.enrollmentCandidate(companyId, installationId)
            return@withLock candidate?.keyStatus == RemoteDeviceKeyStatus.ACTIVE
        }
        if (active != null) {
            publishDeviceIdentity(active)
            return@withLock if (forceDeviceKeyStatusCheck) {
                refreshRemoteDeviceKeyStatus(active)
            } else {
                true
            }
        }
        candidate = createRemoteDeviceIdentity(companyId, installationId)
            ?: return@withLock false
        publishDeviceIdentity(candidate)
        when (candidate.keyStatus) {
            RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(candidate)
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
                    refreshRemoteDeviceKeyStatus(candidate)
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
            -> rotateAndEnrollRemoteDeviceKey(candidate)
        }
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
        if (!deviceIdentityStore.put(identity)) {
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

    private suspend fun enrollRemoteDeviceKey(identity: PersistedRemoteDeviceIdentity): Boolean {
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
        val spki = deviceKeyStore.publicKeySpki(identity.keyId) ?: return false
        try {
            if (remoteSha256Hex(spki) != identity.fingerprintSha256) return false
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
            if (!remoteRequestScopeIsCurrent(requestScope)) return false
            forceDeviceKeyStatusCheck = false
            return applyRemoteDeviceKeyStatus(identity, response, firstPendingPoll = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeDeviceEndpointFailure(error)
            nextDeviceKeyStatusCheckAt = elapsedRealtime() + DEVICE_KEY_RETRY_MILLIS
            // The failed attempt already exercised bearer refresh when
            // appropriate. Retrying every four-second device-state tick would
            // turn an outage or rejected enrollment into an unbounded proof
            // loop; keep retries on the explicit pairing cadence.
            forceDeviceKeyStatusCheck = false
            _uiState.value = _uiState.value.copy(
                statusMessage = "Remote support device pairing is waiting for a verified connection.",
            )
            return false
        } finally {
            spki.fill(0)
        }
    }

    private suspend fun refreshRemoteDeviceKeyStatus(
        identity: PersistedRemoteDeviceIdentity,
    ): Boolean {
        val requestScope = currentRemoteRequestTag()
            ?.takeIf {
                it.scope.companyId == identity.companyId &&
                    it.scope.installationId == identity.installationId
            }
            ?: return false
        nextDeviceKeyStatusCheckAt = elapsedRealtime() + DEVICE_KEY_STATUS_INTERVAL_MILLIS
        return try {
            val response = api.deviceKeyStatus(
                identity.keyId,
                identity.installationId,
                requestScope,
            )
            if (!remoteRequestScopeIsCurrent(requestScope)) return false
            forceDeviceKeyStatusCheck = false
            applyRemoteDeviceKeyStatus(identity, response, firstPendingPoll = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
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

    private suspend fun applyRemoteDeviceKeyStatus(
        previous: PersistedRemoteDeviceIdentity,
        response: RemoteDeviceKeyStatusResponse,
        firstPendingPoll: Boolean,
    ): Boolean {
        if (
            response.keyId != previous.keyId ||
            response.installationId != previous.installationId ||
            response.fingerprintSha256 != previous.fingerprintSha256
        ) return false
        val status = RemoteDeviceKeyStatus.fromStored(response.status)
            ?.takeUnless { it == RemoteDeviceKeyStatus.CREATED }
            ?: return false
        val updated = PersistedRemoteDeviceIdentity(
            companyId = previous.companyId,
            installationId = previous.installationId,
            keyId = previous.keyId,
            enrollmentId = previous.enrollmentId,
            status = status.storedValue,
            fingerprintSha256 = response.fingerprintSha256,
            pairingCode = response.pairingCode,
            serverTime = response.serverTime,
            enrolledAt = response.enrolledAt,
            pendingExpiresAt = response.pendingExpiresAt,
            approvedAt = response.approvedAt,
            revokedAt = response.revokedAt,
        )
        val valid = validRemoteDeviceIdentity(updated) ?: return false
        val persisted = if (
            status == RemoteDeviceKeyStatus.ACTIVE &&
            previous.keyStatus != RemoteDeviceKeyStatus.ACTIVE
        ) {
            deviceIdentityStore.promote(valid)
        } else {
            deviceIdentityStore.put(valid)
        }
        if (!persisted) return false
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
                    requestSessionEnd(active, "capture_stopped")
                }
                rotateAndEnrollRemoteDeviceKey(valid)
            }
            RemoteDeviceKeyStatus.CREATED -> false
        }
    }

    private suspend fun rotateAndEnrollRemoteDeviceKey(
        previous: PersistedRemoteDeviceIdentity,
    ): Boolean {
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
            RemoteDeviceKeyStatus.CREATED -> enrollRemoteDeviceKey(replacement)
            RemoteDeviceKeyStatus.PENDING -> refreshRemoteDeviceKeyStatus(replacement)
            RemoteDeviceKeyStatus.ACTIVE -> true
            else -> false
        }
    }

    private fun publishDeviceIdentity(identity: PersistedRemoteDeviceIdentity) {
        _uiState.value = _uiState.value.copy(
            deviceKeyStatus = identity.keyStatus,
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

    private suspend fun sendHeartbeat() {
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
        if (!canUseRemoteApi()) return@withLock
        val requestScope = currentRemoteRequestTag() ?: return@withLock
        val installationId = installationIdProvider() ?: return@withLock
        val currentSessionId = _uiState.value.activeSession?.sessionId
        val afterSequence = currentSessionId?.let { store.afterSequence(it, initialPoll) } ?: 0L
        try {
            val response = api.deviceState(installationId, afterSequence, requestScope)
            if (!canUseRemoteApi() || !remoteRequestScopeIsCurrent(requestScope)) return@withLock
            handleDeviceState(response)
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

    private suspend fun handleDeviceState(response: RemoteDeviceStateResponse) {
        val installationId = installationIdProvider() ?: return
        val authenticatedUserId = journalScope?.userId ?: return
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
                sendGrantDecision(replay)
                _uiState.value = _uiState.value.copy(pendingGrant = null)
            }
        } else {
            _uiState.value = _uiState.value.copy(pendingGrant = null)
        }

        val pendingEnd = store.snapshot().pendingSessionEnd
        if (
            pendingEnd != null &&
            response.session?.id == pendingEnd.sessionId &&
            response.session.status !in setOf("requested", "active")
        ) {
            store.acknowledgeSessionEnd(pendingEnd.sessionId, pendingEnd.endId)
        }
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
            active == null -> clearActiveSession(null)?.join()
            !store.authorizeSession(
                grantId = active.grantId,
                sessionId = active.sessionId,
                serverTimeRaw = response.serverTime,
            ) -> requestSessionEnd(
                active,
                if (consent == RemoteConsentChoice.REVOKED) "permission_revoked" else "capture_stopped",
            )
            !notification.canShowPersistentIndicator() -> {
                _uiState.value = _uiState.value.copy(
                    notificationReady = false,
                    statusMessage = "Remote support could not start because its visible notification is disabled.",
                )
                requestSessionEnd(active, "capture_stopped")
            }
            !appForeground -> requestSessionEnd(active, "app_backgrounded")
            locallyEnded -> sendPendingSessionEnd()
            !uiGateway.hasVisibleHost() -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Remote support could not start without its visible in-app Stop control.",
                )
                requestSessionEnd(active, "capture_stopped")
            }
            else -> activateSession(active)
        }

        val accepted = _uiState.value.activeSession
        if (accepted != null && accepted.sessionId == active?.sessionId) {
            if (store.activateCommandSession(accepted.sessionId)) {
                startCommandProcessing(
                    session = accepted,
                    serverTimeRaw = response.serverTime,
                    commands = response.commands,
                )
            } else {
                requestSessionEnd(accepted, "capture_stopped")
            }
        }
        refreshConsentUi()
    }

    private suspend fun activateSession(session: RemoteActiveSession) {
        val admittedSession = clampRemoteSessionDeadline(_uiState.value.activeSession, session)
        val remaining = (admittedSession.deadlineElapsedMillis - elapsedRealtime()).coerceAtLeast(1_000L)
        if (!notification.show(remaining, sharingPaused = !online.value)) {
            requestSessionEnd(admittedSession, "capture_stopped")
            return
        }
        val sameSession = _uiState.value.activeSession?.sessionId == admittedSession.sessionId
        if (!sameSession) cancelCommandExecution()?.join()
        _uiState.value = _uiState.value.copy(
            activeSession = admittedSession,
            notificationReady = true,
            privacyProtected = true,
            statusMessage = if (sameSession) _uiState.value.statusMessage else null,
        )
        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val startedAt = elapsedRealtime()
                val session = _uiState.value.activeSession ?: break
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
                        if (appForeground) "capture_stopped" else "app_backgrounded",
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
                if (!remoteFrameAdmissionAllowed(
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
                        if (appForeground) "capture_stopped" else "app_backgrounded",
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
                    requestSessionEnd(session, "capture_stopped")
                    break
                }
                val wait = REMOTE_FRAME_INTERVAL_MILLIS - (elapsedRealtime() - startedAt)
                if (wait > 0L) delay(wait)
            }
        }
    }

    private fun startCommandProcessing(
        session: RemoteActiveSession,
        serverTimeRaw: String,
        commands: List<RemoteCommandResponse>,
    ) {
        if (commands.isEmpty()) return
        synchronized(lifecycleLock) {
            if (commandJob?.isActive == true) return
            lateinit var launched: Job
            launched = scope.launch(start = CoroutineStart.LAZY) {
                processCommands(session, serverTimeRaw, commands)
            }
            launched.invokeOnCompletion {
                synchronized(lifecycleLock) {
                    if (commandJob === launched) commandJob = null
                }
            }
            commandJob = launched
            launched.start()
        }
    }

    private suspend fun processCommands(
        session: RemoteActiveSession,
        serverTimeRaw: String,
        commands: List<RemoteCommandResponse>,
    ) {
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
            requestSessionEnd(session, "capture_stopped")
            return
        }
        processRemoteCommandsInOrder(commands, expectedSequence) commandLoop@{ command ->
            if (!commandSessionIsCurrent(session)) return@commandLoop false
            val existing = store.commandReceipt(command.commandId)
            if (
                existing != null &&
                (existing.sessionId != session.sessionId || existing.sequence != command.sequence)
            ) {
                requestSessionEnd(session, "capture_stopped")
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
                        requestSessionEnd(session, "capture_stopped")
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
                requestSessionEnd(session, "capture_stopped")
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
        remoteCommandExecutionAllowed(
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

    private fun requestSessionEnd(session: RemoteActiveSession, reason: String) {
        store.recordSessionEnd(session.sessionId, reason)
        val commandToJoin = clearActiveSession(
            when (reason) {
                "user_ended" -> "Remote support stopped on this tablet."
                "permission_revoked" -> "Remote support permission was revoked."
                "app_backgrounded" -> "Remote support stopped when D Company ERP left the screen."
                else -> "Remote support stopped safely."
            },
        )
        scope.launch {
            commandToJoin?.join()
            sendPendingSessionEnd()
        }
    }

    /** Local state and visibility stop immediately; the canceled command is joined before reconciliation. */
    private fun clearActiveSession(message: String?): Job? {
        captureJob?.cancel()
        captureJob = null
        val commandToJoin = cancelCommandExecution()
        notification.cancel()
        if (_uiState.value.activeSession != null || message != null) {
            _uiState.value = _uiState.value.copy(
                activeSession = null,
                privacyProtected = true,
                statusMessage = message ?: _uiState.value.statusMessage,
            )
        }
        return commandToJoin
    }

    private fun cancelCommandExecution(): Job? = synchronized(lifecycleLock) {
        commandJob.also { running ->
            running?.cancel()
            commandJob = null
        }
    }

    private suspend fun sendPendingMutations() {
        val snapshot = store.snapshot()
        snapshot.pendingDecision?.let { sendGrantDecision(it) }
        sendPendingRevocation()
        sendPendingSessionEnd()
    }

    private suspend fun sendGrantDecision(mutation: PendingRemoteGrantDecision) =
        mutationMutex.withLock {
            if (!canUseRemoteApi()) return@withLock
            val requestScope = currentRemoteRequestTag() ?: return@withLock
            if (store.snapshot().pendingDecision != mutation) return@withLock
            val installationId = installationIdProvider() ?: return@withLock
            try {
                api.decideGrant(
                    grantId = mutation.grantId,
                    body = RemoteGrantDecisionRequest(
                        installationId = installationId,
                        decision = mutation.decision,
                        decisionId = mutation.decisionId,
                    ),
                    requestScope = requestScope,
                ).requireRemoteAssistanceSuccess()
                if (!remoteRequestScopeIsCurrent(requestScope)) return@withLock
                store.acknowledgeDecision(mutation.grantId, mutation.decisionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                observeDeviceEndpointFailure(error)
                if (remoteMutationStatusIsTerminal(error.remoteAssistanceStatus())) {
                    store.acknowledgeDecision(mutation.grantId, mutation.decisionId)
                }
            }
            refreshConsentUi()
        }

    private suspend fun sendPendingRevocation() = mutationMutex.withLock {
        if (!canUseRemoteApi()) return@withLock
        val requestScope = currentRemoteRequestTag() ?: return@withLock
        val mutation = store.snapshot().pendingRevocation ?: return@withLock
        val installationId = installationIdProvider() ?: return@withLock
        try {
            api.revokeGrant(
                grantId = mutation.grantId,
                body = RemoteGrantRevocationRequest(installationId, mutation.revocationId),
                requestScope = requestScope,
            ).requireRemoteAssistanceSuccess()
            if (!remoteRequestScopeIsCurrent(requestScope)) return@withLock
            store.acknowledgeRevocation(mutation.grantId, mutation.revocationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeDeviceEndpointFailure(error)
            if (remoteMutationStatusIsTerminal(error.remoteAssistanceStatus())) {
                store.acknowledgeRevocation(mutation.grantId, mutation.revocationId)
            }
        }
        refreshConsentUi()
    }

    private suspend fun sendPendingSessionEnd() = mutationMutex.withLock {
        if (!canUseRemoteApi()) return@withLock
        val requestScope = currentRemoteRequestTag() ?: return@withLock
        val mutation = store.snapshot().pendingSessionEnd ?: return@withLock
        val installationId = installationIdProvider() ?: return@withLock
        try {
            api.endSession(
                sessionId = mutation.sessionId,
                body = RemoteSessionEndRequest(
                    installationId = installationId,
                    endId = mutation.endId,
                    reason = mutation.reason,
                ),
                requestScope = requestScope,
            ).requireRemoteAssistanceSuccess()
            if (!remoteRequestScopeIsCurrent(requestScope)) return@withLock
            store.acknowledgeSessionEnd(mutation.sessionId, mutation.endId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observeDeviceEndpointFailure(error)
            if (remoteMutationStatusIsTerminal(error.remoteAssistanceStatus())) {
                store.acknowledgeSessionEnd(mutation.sessionId, mutation.endId)
            }
        }
        refreshConsentUi()
    }

    private fun canUseRemoteEnrollmentApi(): Boolean =
        verifiedScope &&
            journalScope != null &&
            journalScope == currentCacheJournalScope() &&
            online.value

    private fun canUseRemoteApi(): Boolean {
        if (!canUseRemoteEnrollmentApi()) return false
        val scope = cacheIsolation.currentLease()?.scope ?: return false
        val installationId = installationIdProvider() ?: return false
        return deviceIdentityStore.activeIdentity(
            scope.companyId.trim().lowercase(),
            installationId.trim().lowercase(),
        ) != null
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

package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.MeResponse
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-owned bridge. Every provider is explicit; absent startup/authority never means permission. */
internal class HandoverOperations(
    store: HandoverIntentStore,
    private val startupFence: HandoverStartupFence,
    private val startupResult: () -> PersistedStartupStateResult?,
    private val currentSession: () -> SessionRefreshLease?,
    private val currentAccessFor: (SessionRefreshLease) -> String?,
    private val currentCache: () -> CacheScopeLease?,
    private val currentProfile: () -> MeResponse?,
    private val currentTerminal: () -> ValidatedTerminalDisplay?,
) {
    val coordinator = HandoverCoordinator(store)
    val dispatchGate: OperationDispatchGate = CoordinatorOperationDispatchGate(coordinator, ::status)
    private val bindMutex = Mutex()
    private val captures = Any()
    private val epochs = AtomicLong()
    private var observed: OriginalOperationBinding? = null
    private var observedSignature: Signature? = null
    private var boundAuthority: HandoverAuthority? = null
    private data class Signature(val actor: String, val company: String, val branch: String?, val roles: Set<String>, val permissions: Set<String>?, val modules: Set<String>?, val protected: Boolean, val audit: Boolean, val release: Boolean)
    private fun signature(profile: MeResponse) = Signature(profile.userId, profile.companyId, profile.branchId,
        profile.roles.toSet(), profile.effectivePermissions?.toSet(), profile.accessibleModules?.toSet(), profile.protectedAccess, profile.auditAccess, profile.releaseControlAccess)

    fun status(): HandoverStartupStatus {
        when (startupResult()) {
            PersistedStartupStateResult.Ready -> Unit
            null -> return HandoverStartupStatus.Loading
            else -> return HandoverStartupStatus.Unavailable
        }
        val state = coordinator.state.value
        if (state.storageFault) return HandoverStartupStatus.Unavailable
        if (state.preparationAction != null || state.workspaceExitKey != null) return HandoverStartupStatus.Preparing
        if (state.initialized) return state.intent?.let { HandoverStartupStatus.RecoveryRequired(it) } ?: HandoverStartupStatus.Open
        return startupFence.state.value
    }
    fun requireOperationalReadiness() { check(status() == HandoverStartupStatus.Open) { "The original till handover needs reconciliation." } }
    fun caller(profile: MeResponse?, lease: CacheScopeLease?) = CallerOperations(this, captureOriginal(profile, lease))

    /** Root/pre-terminal callers may bind once later, but only within their original login. */
    fun deferredCaller(actorId: String, companyId: String, login: SessionRefreshLease?): CallerOperations {
        val lock = Any()
        var captured: OriginalOperationBinding? = null
        return CallerOperations(null, { binding, block -> run(binding, block) }, {
            synchronized(lock) {
                if (login == null || currentAccessFor(login) == null ||
                    currentSession()?.session?.lineage !== login.session.lineage) return@synchronized null
                captured?.let { return@synchronized it }
                val profile = currentProfile()?.takeIf { it.userId == actorId && it.companyId == companyId }
                    ?: return@synchronized null
                captureOriginal(profile, currentCache())?.also { captured = it }
            }
        })
    }

    /** Call at caller construction with its actual profile/lease, never with freshly-read B in an old callback. */
    fun captureOriginal(profile: MeResponse?, lease: CacheScopeLease?): OriginalOperationBinding? = captureCurrent(profile, lease, null)

    /** Only the immutable origin can enter exact-intent observation/cleanup, never ordinary work. */
    fun captureForRecovery(profile: MeResponse?, lease: CacheScopeLease?, expected: HandoverIntent): OriginalOperationBinding? =
        captureCurrent(profile, lease, expected)

    /** This binding can verify only the retained uncertain handover, never ordinary work. */
    fun captureForSentHandover(profile: MeResponse?, lease: CacheScopeLease?, expected: HandoverIntent): OriginalOperationBinding? =
        captureCurrent(profile, lease, expected, HandoverStage.SENT_UNKNOWN)

    private fun captureCurrent(profile: MeResponse?, lease: CacheScopeLease?, recovery: HandoverIntent?,
        recoveryStage: HandoverStage = HandoverStage.NOT_SENT): OriginalOperationBinding? = synchronized(captures) {
        val status = status()
        val permitted = if (recovery == null) status == HandoverStartupStatus.Open else
            status == HandoverStartupStatus.RecoveryRequired(recovery) && recovery.stage == recoveryStage
        if (profile == null || lease == null || !permitted) return@synchronized null
        val session = currentSession() ?: return@synchronized null
        if (currentCache() != lease || profile.userId != lease.scope.userId || profile.companyId != lease.scope.companyId ||
            AccessTokenIdentityParser.parse(session.accessToken) != OutboxOwnerIdentity.from(profile)) return@synchronized null
        val scope = runCatching { effectiveOperationScope(lease.scope, currentTerminal()) }.getOrNull() ?: return@synchronized null
        if (profile.branchId != null && scope.branchId != profile.branchId) return@synchronized null
        if (recovery != null && recovery.action.origin.scope != scope) return@synchronized null
        val expectedSignature = signature(profile)
        if (currentProfile()?.let(::signature) != expectedSignature) return@synchronized null
        observed?.takeIf { it.cacheLease == lease && it.loginLease.session.lineage === session.session.lineage &&
            it.authority.scope == scope && observedSignature == expectedSignature && it.isCurrent() }?.let { return@synchronized it }
        val epoch = epochs.incrementAndGet(); check(epoch > 0)
        val binding = OriginalOperationBinding(HandoverAuthority(scope, epoch), lease, session) {
            currentCache() == lease && currentAccessFor(session) != null && currentProfile()?.let(::signature) == expectedSignature &&
                runCatching { effectiveOperationScope(lease.scope, currentTerminal()) }.getOrNull() == scope
        }
        observed = binding; observedSignature = expectedSignature
        binding
    }
    private suspend fun bind(binding: OriginalOperationBinding) = bindMutex.withLock {
        check(binding.isCurrent()) { "The originating account or workspace changed." }
        if (boundAuthority != binding.authority) {
            check(coordinator.awaitOwnerSupportIdle(15_000) && binding.isCurrent()) { "The original support delivery is still finishing." }
            coordinator.initialize(binding.authority)
            boundAuthority = binding.authority
        }
    }
    suspend fun <T> runOwnerSupport(owner: OutboxOwnerIdentity, login: SessionRefreshLease,
        block: suspend CoroutineScope.(OwnerSupportDeliveryContext) -> T): T {
        check(currentCoroutineContext()[OperationDispatchContext] == null && currentCoroutineContext()[OwnerSupportDeliveryContext] == null)
        fun originalCurrent() = currentSession()?.session?.lineage === login.session.lineage &&
            currentAccessFor(login)?.let(AccessTokenIdentityParser::parse) == owner
        requireOperationalReadiness()
        val permit = coordinator.admitOwnerSupport(owner.companyId, owner.userId, ::originalCurrent)
        val context = OwnerSupportDeliveryContext(permit, owner, ::originalCurrent)
        try {
            return withContext(context) { coroutineScope { block(context) } }
        } finally {
            withContext(NonCancellable) { coordinator.finishOwnerSupport(permit) }
        }
    }
    /** This endpoint cannot grant consent, execute commands or mutate money; never settle a whole poll/batch. */
    suspend fun runRepeatableRemoteHeartbeat(binding: OriginalOperationBinding, send: suspend () -> Unit) {
        check(currentCoroutineContext()[OwnerSupportDeliveryContext] == null && currentCoroutineContext()[OperationDispatchContext] == null)
        requireOperationalReadiness(); bind(binding)
        val permit = coordinator.admitRemoteHeartbeat(binding.authority)
        try {
            withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { coroutineScope { send() } }
        } finally {
            withContext(NonCancellable) { coordinator.finish(permit, OperationOutcome.REPEATABLE_RESTRICTIVE_EFFECT) }
        }
    }
    suspend fun beginWorkspaceExit(binding: OriginalOperationBinding?, kind: WorkspaceExitKind,
        isAuthorityCurrent: () -> Boolean): HandoverCoordinator.WorkspaceExit {
        requireOperationalReadiness()
        if (binding != null) bind(binding) else check(currentCache() == null)
        return coordinator.beginWorkspaceExit(binding?.authority, kind, isAuthorityCurrent)
    }
    suspend fun <T> observeWorkspaceExit(binding: OriginalOperationBinding, cap: HandoverCoordinator.WorkspaceExit,
        read: suspend () -> T): T = coordinator.observeWorkspaceExit(cap, binding.isCurrent) { permit ->
        withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { read() }
    }
    suspend fun prepare(binding: OriginalOperationBinding, action: HandoverAction): HandoverCoordinator.Preparation {
        requireOperationalReadiness(); bind(binding)
        return coordinator.beginPreparation(binding.authority, action, binding.isCurrent)
    }
    suspend fun requirePreparing(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation) {
        check(binding.isCurrent()); coordinator.requirePreparing(binding.authority, cap)
    }
    suspend fun abortPreparation(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation) {
        coordinator.abortPreparation(binding.authority, cap, binding.isCurrent)
    }
    suspend fun persistPreparation(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation,
        verify: suspend (HandoverAction) -> PriorQuiescenceResult): HandoverIntent? {
        check(binding.isCurrent())
        return coordinator.observeAndPersistPreparation(binding.authority, cap, binding.isCurrent) { action, permit ->
            withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { verify(action) }
        }
    }
    suspend fun <T> cleanupPreparedCheckout(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation,
        orderId: String, block: suspend () -> OperationResult<T>): T {
        check(binding.isCurrent())
        return coordinator.runCheckoutCleanup(binding.authority, orderId, cap) { permit ->
            withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { block() }
        }
    }

    fun requireFreshOriginalProfile(binding: OriginalOperationBinding, fresh: MeResponse) {
        check(binding.isCurrent() && currentProfile()?.let(::signature) == signature(fresh) &&
            fresh.userId == binding.authority.scope.actorId && fresh.companyId == binding.authority.scope.companyId) {
            "The original operational profile changed."
        }
    }
    suspend fun initializeAcceptedRecovery(expected: HandoverIntent, incomingAuthority: HandoverAuthority,
        incomingIsCurrent: () -> Boolean) = bindMutex.withLock {
        check(status() == HandoverStartupStatus.RecoveryRequired(expected) && expected.stage == HandoverStage.ACCEPTED &&
            incomingIsCurrent())
        if (!coordinator.state.value.initialized) {
            coordinator.initialize(incomingAuthority)
            boundAuthority = incomingAuthority
        }
    }

    fun handoverBearer(binding: OriginalOperationBinding): String {
        check(binding.isCurrent())
        return checkNotNull(currentAccessFor(binding.loginLease)) { "The original till login is unavailable." }
    }

    suspend fun <T> withHandoverWire(binding: OriginalOperationBinding, expected: HandoverIntent,
        purpose: OperationPurpose, bearer: String, body: TillHandoverBody? = null,
        exchange: suspend (HandoverCoordinator.Permit) -> T): T {
        check(currentCoroutineContext()[OperationDispatchContext] == null &&
            currentCoroutineContext()[OwnerSupportDeliveryContext] == null)
        check(if (purpose == OperationPurpose.SEND_HANDOVER) body?.action == expected.action
            else purpose == OperationPurpose.READ_HANDOVER_RECEIPT && body == null)
        check(handoverBearer(binding) == bearer)
        bind(binding)
        val permit = coordinator.admitHandoverWire(binding.authority, expected, purpose, binding.isCurrent)
        try {
            return withContext(OperationDispatchContext(binding, permit, handoverBearer = bearer, handoverBody = body)) {
                exchange(permit)
            }
        } finally {
            withContext(NonCancellable) { coordinator.finishHandoverWire(permit) }
        }
    }

    suspend fun observeNotSent(binding: OriginalOperationBinding, expected: HandoverIntent,
        verify: suspend (HandoverIntent) -> PriorQuiescenceResult): Boolean {
        check(binding.isCurrent()); bind(binding)
        return coordinator.reconcilePriorQuiescenceObserved(binding.authority, expected, binding.isCurrent) { retained, permit ->
            withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { verify(retained) }
        }
    }
    suspend fun <T> cleanupCheckout(binding: OriginalOperationBinding, expected: HandoverIntent, orderId: String,
        block: suspend () -> OperationResult<T>): T {
        check(binding.isCurrent() && coordinator.state.value.intent == expected && expected.action.origin.scope == binding.authority.scope)
        bind(binding)
        return coordinator.runCheckoutCleanup(binding.authority, orderId) { permit ->
            withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { block() }
        }
    }

    /** Explicit normal /auth/me verification boundary; cached profile observation is not enough. */
    suspend fun rebindVerifiedSameActor(binding: OriginalOperationBinding, verifiedProfile: MeResponse) = bindMutex.withLock {
        fun verifiedCurrent() = startupResult() == PersistedStartupStateResult.Ready && binding.isCurrent() &&
            currentProfile()?.let(::signature) == signature(verifiedProfile) &&
            verifiedProfile.userId == binding.authority.scope.actorId && verifiedProfile.companyId == binding.authority.scope.companyId &&
            (verifiedProfile.branchId == null || verifiedProfile.branchId == binding.authority.scope.branchId)
        check(verifiedCurrent())
        val previous = requireNotNull(boundAuthority)
        coordinator.rebindVerifiedSameActor(previous, binding.authority, ::verifiedCurrent)
        boundAuthority = binding.authority
    }

    /** One exact-key read, never a poll/parent/mutation capability. */
    suspend fun <T> observeKnownRemoteKey(binding: OriginalOperationBinding, observation: KnownRemoteKeyObservation,
        block: suspend CoroutineScope.(HandoverCoordinator.Permit) -> T): T {
        check(currentCoroutineContext()[OwnerSupportDeliveryContext] == null && binding.isCurrent())
        val parent = currentCoroutineContext()[OperationDispatchContext]
        if (parent == null) { requireOperationalReadiness(); bind(binding) }
        else check(parent.binding.authority == binding.authority && parent.permit?.purpose == OperationPurpose.ORDINARY)
        return coordinator.runKnownKeyObservation<T>(binding.authority, observation, parent?.permit) { permit ->
            withContext<T>(OperationDispatchContext(binding, permit).asCoroutineContext()) { block(permit) }
        }
    }

    /** One exact signed status read; no generic poll/mutation/child authority. */
    suspend fun <T> observeKnownRemoteStatus(binding: OriginalOperationBinding, observation: KnownRemoteStatusObservation,
        block: suspend CoroutineScope.(HandoverCoordinator.Permit) -> T): T {
        check(currentCoroutineContext()[OwnerSupportDeliveryContext] == null && binding.isCurrent())
        val parent = currentCoroutineContext()[OperationDispatchContext]
        if (parent == null) { requireOperationalReadiness(); bind(binding) }
        else check(parent.binding.authority == binding.authority && parent.permit?.purpose == OperationPurpose.ORDINARY)
        return coordinator.runKnownStatusObservation<T>(binding.authority, observation, parent?.permit) { permit ->
            withContext<T>(OperationDispatchContext(binding, permit).asCoroutineContext()) { block(permit) }
        }
    }

    /** Each finite adapter must validate its exact response and own the exact checkpoint. */
    suspend fun <T> runKnownJournalAttempt(binding: OriginalOperationBinding, identity: KnownJournalAttempt,
        sendAndValidate: suspend CoroutineScope.() -> T, checkpoint: suspend (T) -> Unit): T = run(binding) { permit ->
        coordinator.bindKnownAttempt(permit, identity)
        val value = coroutineScope { sendAndValidate() }
        coordinator.checkpointKnownAttempt(permit, identity, binding.isCurrent) { checkpoint(value) }
        OperationResult(value, OperationOutcome.DURABLE_OUTCOME)
    }

    suspend fun <T> run(
        binding: OriginalOperationBinding,
        block: suspend CoroutineScope.(HandoverCoordinator.Permit) -> OperationResult<T>,
    ): T {
        check(currentCoroutineContext()[OwnerSupportDeliveryContext] == null) { "Support delivery cannot admit ordinary workspace work." }
        // UNDISPATCHED launch in another scope can temporarily share the physical thread.
        // Only a logical coroutine-context parent is authority to continue admitted work.
        val inherited = currentCoroutineContext()[OperationDispatchContext]
        if (inherited?.permit != null) {
            check(inherited.binding.authority == binding.authority && binding.isCurrent()) { "The originating operation changed." }
            return coordinator.runChildOperation(inherited.permit) { permit ->
                withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { block(permit) }
            }
        }
        requireOperationalReadiness(); bind(binding)
        return coordinator.runOperation(binding.authority) { permit ->
            withContext(OperationDispatchContext(binding, permit).asCoroutineContext()) { block(permit) }
        }
    }
}

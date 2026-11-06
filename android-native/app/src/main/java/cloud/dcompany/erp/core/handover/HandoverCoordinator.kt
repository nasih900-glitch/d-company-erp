package cloud.dcompany.erp.core.handover

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class OperationPurpose { ORDINARY, RELEASE_OWN_CHECKOUT, RECONCILE_OWN_ACTION, OBSERVE_HANDOVER_STATE, OBSERVE_WORKSPACE_EXIT, REMOTE_HEARTBEAT, OBSERVE_REMOTE_KEY, OBSERVE_REMOTE_STATUS, SEND_HANDOVER, READ_HANDOVER_RECEIPT;

    val isHandoverWire get() = this == SEND_HANDOVER || this == READ_HANDOVER_RECEIPT
}
internal enum class WorkspaceExitKind { LOGOUT, TERMINAL_CHANGE }
internal enum class OperationOutcome {
    NO_WRITE_SENT,
    // DTO/response handling and durable checkpoints finished. The separate outbox gate still applies.
    DURABLE_OUTCOME,
    UNRESOLVED,
    // Endpoint-specific liveness and restrictive former-user cleanup; not proof of server commit.
    REPEATABLE_RESTRICTIVE_EFFECT,
}
internal enum class PriorQuiescenceResult { VERIFIED, BLOCKED }
internal data class OperationResult<T>(val value: T, val outcome: OperationOutcome)
internal data class HandoverCoordinatorState(
    val initialized: Boolean = false,
    val intent: HandoverIntent? = null,
    val preparationAction: HandoverAction? = null,
    val workspaceExitKey: String? = null,
    val activeOperations: Set<String> = emptySet(),
    val unresolvedOperations: Set<String> = emptySet(),
    val storageFault: Boolean = false,
    val priorQuiescenceUnverified: Boolean = false,
    val mutationUncertaintySticky: Boolean = false,
    val sameActorResumed: Boolean = false,
)
internal sealed interface HandoverDrainResult {
    data class Drained(val actionKey: String) : HandoverDrainResult
    data class Blocked(val active: Set<String>, val unresolved: Set<String>, val storageFault: Boolean, val priorQuiescenceUnverified: Boolean,
        val mutationUncertainty: Boolean = false, val sameActorResumed: Boolean = false) : HandoverDrainResult
}

/** Foundation only: callers must register their entire workflow, and still run the domain/outbox gate. */
internal class HandoverCoordinator(private val store: HandoverIntentStore) {
    internal class Preparation internal constructor(internal val owner: HandoverCoordinator, val action: HandoverAction) {
        override fun toString() = "HandoverPreparation(<redacted>)"
    }
    private var preparation: Preparation? = null
    internal class WorkspaceExit internal constructor(internal val owner: HandoverCoordinator,
        val authority: HandoverAuthority?, val kind: WorkspaceExitKind, val key: String = UUID.randomUUID().toString()) {
        @Volatile internal var deactivated = false
        override fun toString() = "WorkspaceExit(<redacted>)"
    }
    private var workspaceExit: WorkspaceExit? = null

    internal class SameActorResumption internal constructor(internal val owner: HandoverCoordinator, val authority: HandoverAuthority, val actionKey: String) {
        override fun toString() = "SameActorResumption(<redacted>)"
    }
    internal class Permit internal constructor(
        internal val owner: HandoverCoordinator,
        val id: String,
        val authority: HandoverAuthority,
        val purpose: OperationPurpose,
        val existingAction: String?,
        val knownKeyObservation: KnownRemoteKeyObservation? = null,
        val knownStatusObservation: KnownRemoteStatusObservation? = null,
        val handoverIntent: HandoverIntent? = null,
    )
    private data class Work(val permit: Permit, val parentId: String? = null, var dispatched: Boolean = false, var unresolved: Boolean = false,
        var mutationInSubtree: Boolean = false, var mutationHistory: Boolean = false,
        var knownAttempt: KnownJournalAttempt? = null, var checkpointInProgress: Boolean = false,
        var checkpointComplete: Boolean = false, var childOnlyFailure: Boolean = false,
        var priorKnownUnknowns: Set<String> = emptySet(), var knownReadStarted: Boolean = false, var handoverWireStarted: Boolean = false)
    private val mutex = Mutex()
    private val work = linkedMapOf<String, Work>()
    internal class OwnerSupportPermit internal constructor(internal val owner: HandoverCoordinator,
        val companyId: String, val userId: String, val id: String = UUID.randomUUID().toString()) {
        override fun toString() = "OwnerSupportPermit(<redacted>)"
    }
    private val supportWork = linkedMapOf<String, OwnerSupportPermit>()
    private fun workEmpty() = work.isEmpty() && supportWork.isEmpty()
    private var authority: HandoverAuthority? = null
    private var lifecycleVersion = 0L
    private var resumption: SameActorResumption? = null
    private val mutable = MutableStateFlow(HandoverCoordinatorState())
    val state = mutable.asStateFlow()

    private fun publish() {
        lifecycleVersion++
        mutable.value = mutable.value.copy(
            activeOperations = work.filterValues { !it.unresolved }.keys.toSet() + supportWork.keys,
            unresolvedOperations = work.filterValues { it.unresolved }.keys.toSet(),
        )
    }
    private fun current(expected: HandoverAuthority) {
        check(mutable.value.initialized && authority == expected) { "The original operation authority is no longer current." }
    }
    private fun usable(expected: HandoverAuthority) { current(expected); check(!mutable.value.storageFault) { "Restore verified handover storage before continuing." } }
    private fun record(permit: Permit): Work {
        check(permit.owner === this)
        return requireNotNull(work[permit.id]).also { check(it.permit === permit) }
    }
    private suspend fun <T> persisted(block: suspend () -> T): T = try { block() } catch (error: Exception) {
        lifecycleVersion++
        mutable.value = mutable.value.copy(storageFault = true)
        throw error
    }
    suspend fun admitOwnerSupport(companyId: String, userId: String, originalLoginIsCurrent: () -> Boolean): OwnerSupportPermit = mutex.withLock {
        check(!mutable.value.storageFault && mutable.value.intent == null && preparation == null && workspaceExit == null && originalLoginIsCurrent())
        require(companyId.isNotBlank() && userId.isNotBlank())
        persisted { check(store.read() == null) }
        OwnerSupportPermit(this, companyId, userId).also { supportWork[it.id] = it; publish() }
    }
    suspend fun requireOwnerSupport(permit: OwnerSupportPermit, originalLoginIsCurrent: () -> Boolean) = mutex.withLock {
        check(permit.owner === this && supportWork[permit.id] === permit && !mutable.value.storageFault && originalLoginIsCurrent())
    }
    /** Existing finite support delivery may finish while admission is closed; only owned support rows may use it. */
    suspend fun <T> commitOwnerSupport(permit: OwnerSupportPermit, originalLoginIsCurrent: () -> Boolean,
        write: suspend () -> T): T = mutex.withLock {
        check(permit.owner === this && supportWork[permit.id] === permit && !mutable.value.storageFault && originalLoginIsCurrent())
        write()
    }
    suspend fun finishOwnerSupport(permit: OwnerSupportPermit) = mutex.withLock {
        check(permit.owner === this && supportWork[permit.id] === permit)
        supportWork.remove(permit.id); publish()
    }
    suspend fun awaitOwnerSupportIdle(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..300_000)
        return withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val current = mutex.withLock { supportWork.keys.toSet() }
                if (current.isEmpty()) return@withTimeoutOrNull true
                state.first { value -> current.none { it in value.activeOperations } }
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }
    suspend fun initialize(active: HandoverAuthority) = mutex.withLock {
        active.validate(); check(workEmpty() && preparation == null && workspaceExit == null) { "Finish the original operation lifetimes/preparation first." }
        authority?.let { check(active == it || active.epoch > it.epoch) }
        // A failed read must never look like an empty fence.
        mutable.value = HandoverCoordinatorState(storageFault = true)
        resumption = null
        val retained = persisted { store.read() }
        retained?.validate()
        authority = active
        lifecycleVersion++
        mutable.value = HandoverCoordinatorState(initialized = true, intent = retained,
            priorQuiescenceUnverified = retained?.stage == HandoverStage.NOT_SENT,
            mutationUncertaintySticky = retained?.priorMutationUncertainty == true)
    }
    /** Explicit fresh verification, not initialize/registry-empty, advances the same actor's epoch. */
    suspend fun rebindVerifiedSameActor(previous: HandoverAuthority, next: HandoverAuthority,
        freshAuthorityIsCurrent: () -> Boolean) = mutex.withLock {
        current(previous); next.validate()
        check(next.scope == previous.scope && next.epoch > previous.epoch && freshAuthorityIsCurrent())
        check(supportWork.isEmpty() && work.values.all { it.unresolved } && preparation == null && workspaceExit == null)
        // Reading successfully does not clear a pre-existing storage fault or inherited uncertainty.
        persisted { check(store.read() == mutable.value.intent) { "The retained handover state changed." } }
        check(freshAuthorityIsCurrent())
        authority = next
        resumption = null // An old epoch's recovery capability is never reusable.
        publish()
    }

    /** Attach only to a finite leaf before wire; batch roots must use separate child attempts. */
    suspend fun bindKnownAttempt(permit: Permit, expected: KnownJournalAttempt) = mutex.withLock {
        usable(permit.authority); expected.validate()
        val entry = record(permit)
        check(expected.scope == permit.authority.scope && permit.purpose == OperationPurpose.ORDINARY &&
            !entry.dispatched && !entry.mutationHistory && !entry.unresolved && !entry.checkpointInProgress && work.values.none { it.parentId == permit.id })
        check(entry.knownAttempt == null || entry.knownAttempt == expected)
        if (entry.knownAttempt == null) {
            entry.knownAttempt = expected
            entry.priorKnownUnknowns = work.filter { (_, old) -> old.unresolved && old.knownAttempt == expected }.keys.toSet()
        }
        publish()
    }

    /** Selection only: presence never proves an outcome or changes authority/storage. */
    suspend fun hasFinishedKnownAttempt(active: HandoverAuthority, expected: KnownJournalAttempt): Boolean = mutex.withLock {
        usable(active); expected.validate(); check(expected.scope == active.scope)
        work.values.any { it.unresolved && it.knownAttempt == expected }
    }

    suspend fun admitKnownKeyObservation(active: HandoverAuthority, observation: KnownRemoteKeyObservation, parent: Permit? = null): Permit = mutex.withLock {
        usable(active); observation.validate()
        check(observation.identity.scope == active.scope)
        if (parent == null) check(mutable.value.intent == null && preparation == null && workspaceExit == null)
        else check(parent.authority == active && parent.purpose == OperationPurpose.ORDINARY && !record(parent).unresolved && record(parent).knownAttempt == null)
        val permit = Permit(this, UUID.randomUUID().toString(), active, OperationPurpose.OBSERVE_REMOTE_KEY, observation.keyId, observation)
        work[permit.id] = Work(permit, parentId = parent?.id, knownAttempt = observation.identity,
            priorKnownUnknowns = work.filterValues { it.unresolved && it.knownAttempt == observation.identity }.keys.toSet())
        publish(); permit
    }
    suspend fun beforeKnownKeyRead(permit: Permit, active: HandoverAuthority) = mutex.withLock {
        usable(active)
        val entry = record(permit)
        check(permit.authority == active && permit.purpose == OperationPurpose.OBSERVE_REMOTE_KEY && !entry.knownReadStarted && !entry.unresolved && !entry.checkpointComplete)
        entry.knownReadStarted = true
        publish()
    }

    suspend fun admitKnownStatusObservation(active: HandoverAuthority, observation: KnownRemoteStatusObservation, parent: Permit? = null): Permit = mutex.withLock {
        usable(active); observation.validate()
        check(observation.identity.scope == active.scope)
        if (parent == null) check(mutable.value.intent == null && preparation == null && workspaceExit == null)
        else check(parent.authority == active && parent.purpose == OperationPurpose.ORDINARY && !record(parent).unresolved && record(parent).knownAttempt == null)
        val permit = Permit(this, UUID.randomUUID().toString(), active, OperationPurpose.OBSERVE_REMOTE_STATUS,
            observation.identity.target, knownStatusObservation = observation)
        work[permit.id] = Work(permit, parentId = parent?.id, knownAttempt = observation.identity,
            priorKnownUnknowns = work.filterValues { it.unresolved && it.knownAttempt == observation.identity }.keys.toSet())
        publish(); permit
    }
    suspend fun beforeKnownStatusRead(permit: Permit, active: HandoverAuthority) = mutex.withLock {
        usable(active)
        val entry = record(permit)
        check(permit.authority == active && permit.purpose == OperationPurpose.OBSERVE_REMOTE_STATUS &&
            !entry.knownReadStarted && !entry.unresolved && !entry.checkpointComplete)
        entry.knownReadStarted = true
        publish()
    }

    /**
     * Caller contract: validate the exact response first; [checkpoint] performs only the real
     * owned journal CAS/fsync (throw on refusal), never network, a clean-store test or Boolean proof.
     * The local checkpoint and registry retirement are cancellation-safe; network work is not.
     */
    suspend fun checkpointKnownAttempt(permit: Permit, expected: KnownJournalAttempt,
        originalAuthorityIsCurrent: () -> Boolean, checkpoint: suspend () -> Unit) {
        mutex.withLock {
            usable(permit.authority)
            val entry = record(permit)
            check(entry.knownAttempt == expected && (entry.dispatched || (permit.purpose in setOf(OperationPurpose.OBSERVE_REMOTE_KEY, OperationPurpose.OBSERVE_REMOTE_STATUS) && entry.knownReadStarted)) && !entry.unresolved &&
                !entry.checkpointInProgress && !entry.checkpointComplete && originalAuthorityIsCurrent() &&
                work.values.none { it.parentId == permit.id })
            entry.checkpointInProgress = true
        }
        try {
            withContext(NonCancellable) {
                checkpoint() // Outside the coordinator/cache mutex; the real adapter owns its CAS.
                mutex.withLock {
                    usable(permit.authority)
                    val entry = record(permit)
                    check(entry.knownAttempt == expected && entry.checkpointInProgress && originalAuthorityIsCurrent())
                    entry.checkpointComplete = true
                    val matching = work.filter { (id, old) -> id in entry.priorKnownUnknowns && old.unresolved &&
                        old.knownAttempt == expected && old.permit.authority.scope == permit.authority.scope &&
                        work.values.none { it.parentId == id } }.keys
                    val retiring = aggregateRemovals(matching)
                    retireWork(retiring)
                    publish()
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { work[permit.id]?.takeIf { it.permit === permit }?.checkpointInProgress = false }
            }
        }
    }

    /** Only failed, wire-free aggregate ancestors can disappear with their last resolved child. */
    private fun aggregateRemovals(initial: Set<String>): Set<String> {
        val removing = initial.toMutableSet()
        do {
            val parents = removing.mapNotNull { work[it]?.parentId }.toSet()
            val added = parents.filter { id ->
                val entry = work[id]
                id !in removing && entry != null && entry.unresolved && entry.childOnlyFailure &&
                    !entry.dispatched && !entry.checkpointInProgress &&
                    work.none { (childId, child) -> child.parentId == id && childId !in removing }
            }
            removing.addAll(added)
        } while (added.isNotEmpty())
        return removing
    }

    /** Resolved child effects do not become an unclassified direct write in their parent. */
    private fun retireWork(removing: Set<String>) {
        var parents = removing.mapNotNull { work[it]?.parentId }.toSet()
        removing.forEach(work::remove)
        while (parents.isNotEmpty()) {
            val next = mutableSetOf<String>()
            for (id in parents) {
                val entry = work[id] ?: continue
                entry.mutationInSubtree = entry.dispatched || work.values.any { it.parentId == id && it.mutationInSubtree }
                entry.parentId?.let(next::add)
            }
            parents = next
        }
    }

    private fun add(active: HandoverAuthority, purpose: OperationPurpose, action: String?, parent: String? = null): Permit {
        val permit = Permit(this, UUID.randomUUID().toString(), active, purpose, action)
        work[permit.id] = Work(permit, parent)
        publish()
        return permit
    }
    suspend fun admit(active: HandoverAuthority): Permit = mutex.withLock {
        usable(active)
        check(mutable.value.intent == null && preparation == null && workspaceExit == null) { "Resolve the original workspace transition before starting another operation." }
        add(active, OperationPurpose.ORDINARY, null)
    }
    suspend fun admitRemoteHeartbeat(active: HandoverAuthority): Permit = mutex.withLock {
        usable(active)
        check(mutable.value.intent == null && preparation == null && workspaceExit == null)
        add(active, OperationPurpose.REMOTE_HEARTBEAT, null)
    }
    suspend fun admitCleanup(active: HandoverAuthority, purpose: OperationPurpose, existingAction: String,
        preparing: Preparation? = null): Permit = mutex.withLock {
        usable(active)
        require(!purpose.isHandoverWire && purpose != OperationPurpose.ORDINARY && purpose != OperationPurpose.OBSERVE_HANDOVER_STATE && purpose != OperationPurpose.OBSERVE_WORKSPACE_EXIT && purpose != OperationPurpose.REMOTE_HEARTBEAT && purpose != OperationPurpose.OBSERVE_REMOTE_KEY && purpose != OperationPurpose.OBSERVE_REMOTE_STATUS)
        require(existingAction.length in 1..200 && existingAction.none { it.isWhitespace() || it.isISOControl() })
        val action = mutable.value.intent?.action ?: requirePreparation(preparing, active).action
        check(action.origin.scope == active.scope) { "Cleanup belongs only to the original actor/workspace." }
        add(active, purpose, existingAction)
    }
    suspend fun child(parent: Permit): Permit = mutex.withLock {
        current(parent.authority)
        check(!parent.purpose.isHandoverWire && parent.purpose != OperationPurpose.OBSERVE_HANDOVER_STATE && parent.purpose != OperationPurpose.OBSERVE_WORKSPACE_EXIT && parent.purpose != OperationPurpose.REMOTE_HEARTBEAT && parent.purpose != OperationPurpose.OBSERVE_REMOTE_KEY && parent.purpose != OperationPurpose.OBSERVE_REMOTE_STATUS) { "This finite capability cannot admit child work." }
        check(!record(parent).unresolved && record(parent).knownAttempt == null && !record(parent).checkpointInProgress)
        add(parent.authority, parent.purpose, parent.existingAction, parent.id)
    }
    /** Call at the real mutation-dispatch seam, not merely when a coroutine starts. */
    suspend fun beforeMutationDispatch(permit: Permit, active: HandoverAuthority) = mutex.withLock {
        usable(active); check(permit.authority == active)
        check(!permit.purpose.isHandoverWire && permit.purpose != OperationPurpose.OBSERVE_HANDOVER_STATE && permit.purpose != OperationPurpose.OBSERVE_WORKSPACE_EXIT && permit.purpose != OperationPurpose.OBSERVE_REMOTE_KEY && permit.purpose != OperationPurpose.OBSERVE_REMOTE_STATUS) { "A read observation cannot dispatch a mutation." }
        val entry = record(permit); check(!entry.unresolved && !entry.checkpointInProgress && !entry.checkpointComplete)
        check(entry.knownAttempt == null || !entry.dispatched) { "A known journal attempt admits one mutation; retry requires a new lifetime." }
        entry.dispatched = true
        var ancestor: Work? = entry
        while (ancestor != null) {
            ancestor.mutationInSubtree = true
            ancestor.mutationHistory = true
            ancestor = ancestor.parentId?.let(work::get)
        }
        val retained = mutable.value.intent
        if (retained != null && !retained.priorMutationUncertainty) {
            val next = retained.copy(priorMutationUncertainty = true)
            // A failed fsync refuses wire dispatch; it is never permission to proceed.
            persisted { store.compareAndSet(retained, next) }
            mutable.value = mutable.value.copy(intent = next)
        }
        publish()
    }
    suspend fun requireContinuation(permit: Permit, active: HandoverAuthority) = mutex.withLock {
        current(active); check(permit.authority == active && !record(permit).unresolved)
    }
    suspend fun finish(permit: Permit, outcome: OperationOutcome) = mutex.withLock { finishLocked(permit, outcome) }
    private suspend fun finishLocked(permit: Permit, outcome: OperationOutcome) {
        current(permit.authority)
        check(!permit.purpose.isHandoverWire && permit.purpose != OperationPurpose.OBSERVE_HANDOVER_STATE && permit.purpose != OperationPurpose.OBSERVE_WORKSPACE_EXIT) { "Read observations have a separate settlement boundary." }
        val entry = record(permit)
        check(!entry.checkpointInProgress)
        if (entry.knownAttempt != null && entry.dispatched && outcome != OperationOutcome.UNRESOLVED) {
            check(outcome == OperationOutcome.DURABLE_OUTCOME && entry.checkpointComplete) { "A known attempt needs its exact owned checkpoint." }
        }
        if (outcome == OperationOutcome.REPEATABLE_RESTRICTIVE_EFFECT) check(permit.purpose == OperationPurpose.REMOTE_HEARTBEAT)
        if (outcome == OperationOutcome.UNRESOLVED) {
            val retained = mutable.value.intent
            if (retained != null && !retained.priorMutationUncertainty) {
                val next = retained.copy(priorMutationUncertainty = true)
                persisted { store.compareAndSet(retained, next) }
                mutable.value = mutable.value.copy(intent = next)
            }
            entry.unresolved = true
            entry.childOnlyFailure = false // Explicit unknown is never inferred to be a child-only error.
        }
        else {
            check(work.values.none { it.parentId == permit.id }) { "The child operation lifetime is not complete." }
            if (outcome == OperationOutcome.NO_WRITE_SENT) check(!entry.dispatched) { "A dispatched mutation needs an actual outcome." }
            val removing = aggregateRemovals(setOf(permit.id))
            val retained = mutable.value.intent
            if (retained?.priorMutationUncertainty == true && !mutable.value.mutationUncertaintySticky && !mutable.value.storageFault &&
                work.none { (id, other) -> id !in removing && (other.mutationInSubtree || other.unresolved) }) {
                val next = retained.copy(priorMutationUncertainty = false)
                // Retain this Work until the clear fsync succeeds, so failure keeps its permit.
                persisted { store.compareAndSet(retained, next) }
                mutable.value = mutable.value.copy(intent = next)
            }
            retireWork(removing)
        }
        publish()
    }
    /** An actual domain reconciliation can settle an unknown operation even after its caller threw. */
    suspend fun resolve(active: HandoverAuthority, operationId: String, outcome: OperationOutcome) {
        require(outcome != OperationOutcome.UNRESOLVED)
        val permit = mutex.withLock {
            current(active)
            val entry = requireNotNull(work[operationId])
            check(entry.unresolved && entry.permit.authority == active && entry.knownAttempt == null)
            entry.permit
        }
        finish(permit, outcome)
    }
    private suspend fun failed(permit: Permit) = mutex.withLock {
        val entry = record(permit)
        if (entry.checkpointComplete) {
            finishLocked(permit, OperationOutcome.DURABLE_OUTCOME)
            return@withLock
        }
        if (!entry.mutationInSubtree && work.values.none { it.parentId == permit.id }) {
            finishLocked(permit, OperationOutcome.NO_WRITE_SENT)
            return@withLock
        } else {
            entry.unresolved = true
            entry.childOnlyFailure = !entry.dispatched && work.values.any { it.parentId == permit.id }
        }
        publish()
    }
    /** Structured children are joined before finishing; external child work needs an explicit child permit. */
    suspend fun <T> runOperation(active: HandoverAuthority, block: suspend CoroutineScope.(Permit) -> OperationResult<T>): T {
        return execute(admit(active), block)
    }
    suspend fun <T> runCheckoutCleanup(active: HandoverAuthority, orderId: String, preparing: Preparation? = null,
        block: suspend CoroutineScope.(Permit) -> OperationResult<T>): T =
        execute(admitCleanup(active, OperationPurpose.RELEASE_OWN_CHECKOUT, orderId, preparing), block)

    /** A continuation already admitted under A may spawn bounded structured work while draining. */
    suspend fun <T> runChildOperation(parent: Permit, block: suspend CoroutineScope.(Permit) -> OperationResult<T>): T =
        execute(child(parent), block)

    suspend fun <T> runKnownKeyObservation(active: HandoverAuthority, observation: KnownRemoteKeyObservation, parent: Permit? = null,
        block: suspend CoroutineScope.(Permit) -> T): T = execute(admitKnownKeyObservation(active, observation, parent)) {
        OperationResult(block(it), OperationOutcome.NO_WRITE_SENT)
    }

    suspend fun <T> runKnownStatusObservation(active: HandoverAuthority, observation: KnownRemoteStatusObservation, parent: Permit? = null,
        block: suspend CoroutineScope.(Permit) -> T): T = execute(admitKnownStatusObservation(active, observation, parent)) {
        OperationResult(block(it), OperationOutcome.NO_WRITE_SENT)
    }

    private suspend fun <T> execute(permit: Permit, block: suspend CoroutineScope.(Permit) -> OperationResult<T>): T {
        try {
            val result = coroutineScope { block(permit) }
            finish(permit, result.outcome)
            return result.value
        } catch (error: Throwable) {
            withContext(NonCancellable) { failed(permit) }
            throw error
        }
    }
    private fun requirePreparation(cap: Preparation?, active: HandoverAuthority): Preparation {
        val held = requireNotNull(cap)
        check(held.owner === this && preparation === held && held.action.origin == active && mutable.value.intent == null)
        return held
    }
    suspend fun requirePreparing(active: HandoverAuthority, cap: Preparation) = mutex.withLock {
        usable(active); requirePreparation(cap, active); Unit
    }
    /** No incoming identity or handover action is invented for an ordinary workspace exit. */
    suspend fun beginWorkspaceExit(active: HandoverAuthority?, kind: WorkspaceExitKind,
        isAuthorityCurrent: () -> Boolean): WorkspaceExit = mutex.withLock {
        check(!mutable.value.storageFault && preparation == null && mutable.value.intent == null && isAuthorityCurrent())
        if (active != null) usable(active) else check(work.isEmpty())
        workspaceExit?.let { check(it.authority == active && it.kind == kind); return@withLock it }
        persisted { check(store.read() == null) }
        WorkspaceExit(this, active, kind).also {
            workspaceExit = it; mutable.value = mutable.value.copy(workspaceExitKey = it.key); publish()
        }
    }
    private fun requireExit(cap: WorkspaceExit) {
        check(cap.owner === this && workspaceExit === cap && mutable.value.workspaceExitKey == cap.key &&
            !mutable.value.storageFault && mutable.value.intent == null && preparation == null)
    }
    suspend fun requireWorkspaceExit(cap: WorkspaceExit) = mutex.withLock { requireExit(cap) }
    suspend fun awaitWorkspaceExitDrained(cap: WorkspaceExit, timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..300_000)
        mutex.withLock { requireExit(cap) }
        return withTimeoutOrNull(timeoutMillis) {
            state.first { it.storageFault || it.workspaceExitKey != cap.key ||
                (it.activeOperations.isEmpty() && it.unresolvedOperations.isEmpty()) }
            mutex.withLock { requireExit(cap); workEmpty() }
        } ?: false
    }
    /** Exact read-only terminal checks run outside both coordinator and cache locks. */
    suspend fun <T> observeWorkspaceExit(cap: WorkspaceExit, isAuthorityCurrent: () -> Boolean,
        read: suspend (Permit) -> T): T {
        val observer = mutex.withLock {
            requireExit(cap); check(cap.kind == WorkspaceExitKind.TERMINAL_CHANGE && !cap.deactivated && isAuthorityCurrent() && workEmpty())
            add(requireNotNull(cap.authority), OperationPurpose.OBSERVE_WORKSPACE_EXIT, cap.key)
        }
        try {
            val version = mutex.withLock { requireExit(cap); check(isAuthorityCurrent() && exclusiveObservation(observer)); lifecycleVersion }
            val result = read(observer)
            return mutex.withLock {
                requireExit(cap); check(isAuthorityCurrent() && lifecycleVersion == version && exclusiveObservation(observer)); result
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock {
                work[observer.id]?.takeIf { it.permit === observer }?.let {
                    check(!it.dispatched && it.parentId == null); work.remove(observer.id); publish()
                }
            } }
        }
    }
    /** Only the final atomic clean-work/cache gate runs here; never join jobs inside this callback. */
    suspend fun finalizeWorkspaceExit(cap: WorkspaceExit, isAuthorityCurrent: () -> Boolean,
        deactivateIfClean: suspend () -> Boolean): Boolean = mutex.withLock {
        requireExit(cap); check(!cap.deactivated && isAuthorityCurrent() && workEmpty())
        persisted { check(store.read() == null) }
        if (!deactivateIfClean()) return@withLock false
        cap.deactivated = true; publish(); true
    }
    suspend fun abortWorkspaceExit(cap: WorkspaceExit, isAuthorityCurrent: () -> Boolean) = mutex.withLock {
        requireExit(cap); check(!cap.deactivated && isAuthorityCurrent())
        check(work.values.none { !it.unresolved && it.permit.purpose != OperationPurpose.ORDINARY })
        persisted { check(store.read() == null) }
        workspaceExit = null; mutable.value = mutable.value.copy(workspaceExitKey = null); publish()
    }
    suspend fun completeWorkspaceExit(cap: WorkspaceExit, cleanLocalExit: suspend () -> Boolean) = mutex.withLock {
        requireExit(cap); check(cap.deactivated && workEmpty() && cleanLocalExit())
        workspaceExit = null; mutable.value = mutable.value.copy(workspaceExitKey = null); publish()
    }
    suspend fun beginPreparation(active: HandoverAuthority, action: HandoverAction,
        isAuthorityCurrent: () -> Boolean): Preparation = mutex.withLock {
        usable(active); action.validate(); check(action.origin == active && isAuthorityCurrent() && workspaceExit == null)
        check(mutable.value.intent == null)
        preparation?.let { check(it.action == action); return@withLock it }
        Preparation(this, action).also {
            preparation = it; mutable.value = mutable.value.copy(preparationAction = action); publish()
        }
    }
    /** Cancel only the unsent process-owned preparation; original A business/outbox evidence stays. */
    suspend fun abortPreparation(active: HandoverAuthority, cap: Preparation, isAuthorityCurrent: () -> Boolean) = mutex.withLock {
        usable(active); requirePreparation(cap, active); check(isAuthorityCurrent())
        check(work.values.none { !it.unresolved && it.permit.purpose != OperationPurpose.ORDINARY })
        persisted { check(store.read() == null) }
        preparation = null; mutable.value = mutable.value.copy(preparationAction = null); publish()
    }
    suspend fun awaitPreparationDrained(cap: Preparation, timeoutMillis: Long): HandoverDrainResult {
        require(timeoutMillis in 1..300_000)
        mutex.withLock { usable(cap.action.origin); requirePreparation(cap, cap.action.origin) }
        withTimeoutOrNull(timeoutMillis) {
            state.first { it.storageFault || it.preparationAction != cap.action ||
                (it.activeOperations.isEmpty() && it.unresolvedOperations.isEmpty()) }
        } ?: return blocked(state.value)
        return mutex.withLock {
            if (!mutable.value.storageFault && preparation === cap && authority == cap.action.origin && workEmpty())
                HandoverDrainResult.Drained(cap.action.key) else blocked(mutable.value)
        }
    }
    /** Final real domain reads and durable freeze are one closed-admission boundary, never a reusable proof. */
    suspend fun observeAndPersistPreparation(active: HandoverAuthority, cap: Preparation,
        isAuthorityCurrent: () -> Boolean,
        verify: suspend (HandoverAction, Permit) -> PriorQuiescenceResult): HandoverIntent? {
        val observer = mutex.withLock {
            usable(active); requirePreparation(cap, active); check(isAuthorityCurrent() && workEmpty())
            persisted { check(store.read() == null) }
            add(active, OperationPurpose.OBSERVE_HANDOVER_STATE, cap.action.key)
        }
        try {
            val version = mutex.withLock { requirePreparation(cap, active); check(isAuthorityCurrent() && exclusiveObservation(observer)); lifecycleVersion }
            val result = verify(cap.action, observer)
            return mutex.withLock {
                usable(active); requirePreparation(cap, active)
                check(isAuthorityCurrent() && lifecycleVersion == version && exclusiveObservation(observer))
                persisted { check(store.read() == null) }
                if (result != PriorQuiescenceResult.VERIFIED) return@withLock null
                val intent = HandoverIntent(cap.action, priorMutationUncertainty = false)
                persisted { store.begin(intent) }
                preparation = null
                mutable.value = mutable.value.copy(intent = intent, preparationAction = null)
                publish()
                // Storage may now contain the intent even if authority changed during IO: remain closed.
                check(isAuthorityCurrent()) { "The original workspace changed after retaining the handover." }
                intent
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    work[observer.id]?.takeIf { it.permit === observer }?.let {
                        check(!it.dispatched && it.parentId == null); work.remove(observer.id); publish()
                    }
                }
            }
        }
    }

    suspend fun freeze(active: HandoverAuthority, action: HandoverAction): HandoverIntent = mutex.withLock {
        usable(active); action.validate(); check(action.origin == active && preparation == null && workspaceExit == null)
        mutable.value.intent?.let { retained ->
            check(retained.action == action && retained.stage == HandoverStage.NOT_SENT)
            persisted { check(store.read() == retained) }
            return@withLock retained
        }
        val intent = HandoverIntent(action, priorMutationUncertainty = work.values.any { it.mutationInSubtree || it.unresolved })
        persisted { store.begin(intent) }
        lifecycleVersion++
        mutable.value = mutable.value.copy(intent = intent)
        intent
    }
    /** No cache or coordinator mutex is held while the registered full workflows finish. */
    suspend fun awaitDrained(key: String, timeoutMillis: Long): HandoverDrainResult {
        require(timeoutMillis in 1..300_000)
        mutex.withLock { check(mutable.value.initialized && mutable.value.intent?.action?.key == key) }
        val observed = withTimeoutOrNull(timeoutMillis) {
            state.first { it.storageFault || it.priorQuiescenceUnverified || it.mutationUncertaintySticky || it.sameActorResumed || it.intent?.action?.key != key || (it.activeOperations.isEmpty() && it.unresolvedOperations.isEmpty()) }
        } ?: return blocked(state.value)
        return if (!observed.storageFault && !observed.priorQuiescenceUnverified && !observed.mutationUncertaintySticky && !observed.sameActorResumed &&
            observed.intent?.priorMutationUncertainty == false && observed.intent.action.key == key && observed.activeOperations.isEmpty() && observed.unresolvedOperations.isEmpty())
            HandoverDrainResult.Drained(key)
        else blocked(observed)
    }
    private fun blocked(state: HandoverCoordinatorState) = HandoverDrainResult.Blocked(state.activeOperations, state.unresolvedOperations,
        state.storageFault, state.priorQuiescenceUnverified, state.intent?.priorMutationUncertainty == true, state.sameActorResumed)

    /** Authority is the fresh current A epoch; the persisted pre-restart epoch is never resurrected. */
    suspend fun resumeSameActor(active: HandoverAuthority, expected: HandoverIntent): SameActorResumption = mutex.withLock {
        usable(active)
        check(mutable.value.intent == expected && expected.stage == HandoverStage.NOT_SENT && expected.action.origin.scope == active.scope)
        persisted { check(store.read() == expected) }
        SameActorResumption(this, active, expected.action.key).also {
            resumption = it; mutable.value = mutable.value.copy(sameActorResumed = true); publish()
        }
    }
    private suspend fun admitResumed(capability: SameActorResumption): Permit = mutex.withLock {
        usable(capability.authority)
        check(capability.owner === this && resumption === capability)
        val retained = requireNotNull(mutable.value.intent)
        check(retained.stage == HandoverStage.NOT_SENT && retained.action.key == capability.actionKey && retained.action.origin.scope == capability.authority.scope)
        add(capability.authority, OperationPurpose.ORDINARY, null)
    }
    suspend fun <T> runResumedOperation(capability: SameActorResumption, block: suspend CoroutineScope.(Permit) -> OperationResult<T>): T =
        execute(admitResumed(capability), block)

    suspend fun pauseSameActorResumption(capability: SameActorResumption) = mutex.withLock {
        usable(capability.authority); check(capability.owner === this && resumption === capability)
        resumption = null; mutable.value = mutable.value.copy(sameActorResumed = false); publish()
    }
    /** Dependency boundary only: the caller must perform real prior-domain/outbox/claim reconciliation.
     * The callback holds no coordinator/cache lock and has no default success implementation.
     */
    suspend fun reconcilePriorQuiescence(
        active: HandoverAuthority,
        expected: HandoverIntent,
        verify: suspend (HandoverIntent) -> PriorQuiescenceResult,
    ): Boolean = reconcilePriorQuiescenceChecked(active, expected, null, { true }, verify)

    /** The sole bounded read permit exists before version capture; reads never clear mutation uncertainty. */
    suspend fun reconcilePriorQuiescenceObserved(
        active: HandoverAuthority,
        expected: HandoverIntent,
        isAuthorityCurrent: () -> Boolean,
        verify: suspend (HandoverIntent, Permit) -> PriorQuiescenceResult,
    ): Boolean {
        val observer = mutex.withLock {
            usable(active)
            check(isAuthorityCurrent() && mutable.value.intent == expected)
            check(expected.stage == HandoverStage.NOT_SENT && expected.action.origin.scope == active.scope && workEmpty())
            persisted { check(store.read() == expected) }
            add(active, OperationPurpose.OBSERVE_HANDOVER_STATE, expected.action.key)
        }
        try {
            return reconcilePriorQuiescenceChecked(active, expected, observer, isAuthorityCurrent) { verify(it, observer) }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    // No dispatched work/unknown mutation is acknowledged by this read-only cleanup.
                    work[observer.id]?.takeIf { it.permit === observer }?.let {
                        check(!it.dispatched && it.parentId == null)
                        work.remove(observer.id); publish()
                    }
                }
            }
        }
    }
    private fun exclusiveObservation(observer: Permit?): Boolean = if (observer == null) workEmpty() else
        supportWork.isEmpty() && work.size == 1 && work[observer.id]?.let { it.permit === observer && !it.dispatched && !it.unresolved &&
            observer.purpose in setOf(OperationPurpose.OBSERVE_HANDOVER_STATE, OperationPurpose.OBSERVE_WORKSPACE_EXIT) } == true

    private suspend fun reconcilePriorQuiescenceChecked(
        active: HandoverAuthority,
        expected: HandoverIntent,
        observer: Permit?,
        isAuthorityCurrent: () -> Boolean,
        verify: suspend (HandoverIntent) -> PriorQuiescenceResult,
    ): Boolean {
        val version = mutex.withLock {
            usable(active)
            check((observer != null || mutable.value.priorQuiescenceUnverified) && mutable.value.intent == expected)
            check(expected.stage == HandoverStage.NOT_SENT && expected.action.origin.scope == active.scope)
            check(isAuthorityCurrent() && exclusiveObservation(observer))
            persisted { check(store.read() == expected) }
            lifecycleVersion to mutable.value.priorQuiescenceUnverified
        }
        val result = verify(expected)
        return mutex.withLock {
            usable(active)
            check(lifecycleVersion == version.first && mutable.value.intent == expected &&
                mutable.value.priorQuiescenceUnverified == version.second)
            check(isAuthorityCurrent() && exclusiveObservation(observer))
            persisted { check(store.read() == expected) }
            if (result == PriorQuiescenceResult.VERIFIED) {
                lifecycleVersion++
                mutable.value = mutable.value.copy(priorQuiescenceUnverified = false)
                true
            } else false
        }
    }
    /** Only a previously retained unknown can admit its exact send or lookup. */
    suspend fun admitHandoverWire(active: HandoverAuthority, expected: HandoverIntent,
        purpose: OperationPurpose, originalIsCurrent: () -> Boolean): Permit = mutex.withLock {
        usable(active)
        check(purpose.isHandoverWire && workEmpty() && preparation == null && workspaceExit == null)
        requireExactSentHandover(active, expected)
        check(originalIsCurrent())
        Permit(this, UUID.randomUUID().toString(), active, purpose, expected.action.key,
            handoverIntent = expected).also { work[it.id] = Work(it); publish() }
    }

    private suspend fun requireExactSentHandover(active: HandoverAuthority, expected: HandoverIntent) {
        check(expected.stage == HandoverStage.SENT_UNKNOWN && expected.action.origin.scope == active.scope &&
            mutable.value.intent == expected && !expected.priorMutationUncertainty &&
            !mutable.value.priorQuiescenceUnverified && !mutable.value.mutationUncertaintySticky)
        persisted { check(store.read() == expected) }
    }

    suspend fun beforeHandoverWire(permit: Permit, active: HandoverAuthority,
        originalIsCurrent: () -> Boolean) = mutex.withLock {
        usable(active)
        check(permit.authority == active && permit.purpose.isHandoverWire)
        val entry = record(permit)
        check(!entry.handoverWireStarted)
        requireExactSentHandover(active, requireNotNull(permit.handoverIntent))
        check(originalIsCurrent())
        entry.handoverWireStarted = true
    }

    suspend fun acceptHandoverWire(permit: Permit, receipt: AcceptedHandover,
        originalIsCurrent: () -> Boolean): HandoverIntent = mutex.withLock {
        usable(permit.authority)
        check(permit.purpose.isHandoverWire && record(permit).handoverWireStarted)
        val expected = requireNotNull(permit.handoverIntent)
        requireExactSentHandover(permit.authority, expected)
        check(originalIsCurrent())
        acceptLocked(expected, receipt)
    }

    suspend fun finishHandoverWire(permit: Permit) = mutex.withLock {
        check(permit.purpose.isHandoverWire)
        record(permit)
        work.remove(permit.id)
        publish()
    }

    /** Registry drain is necessary, not sufficient: the caller must also pass its real Room/outbox/lease gates. */
    suspend fun markSentUnknown(active: HandoverAuthority, key: String,
        originalIsCurrent: () -> Boolean = { true }): HandoverIntent = mutex.withLock {
        usable(active); check(workEmpty() && !mutable.value.priorQuiescenceUnverified)
        val prior = requireNotNull(mutable.value.intent)
        check(prior.action.key == key && prior.action.origin.scope == active.scope)
        require(prior.stage == HandoverStage.NOT_SENT)
        check(!prior.priorMutationUncertainty && !mutable.value.mutationUncertaintySticky && resumption == null && originalIsCurrent())
        val next = prior.copy(stage = HandoverStage.SENT_UNKNOWN)
        persisted { store.compareAndSet(prior, next) }
        lifecycleVersion++
        mutable.value = mutable.value.copy(intent = next)
        next
    }
    suspend fun accept(active: HandoverAuthority, key: String, receipt: AcceptedHandover): HandoverIntent = mutex.withLock {
        usable(active)
        val prior = requireNotNull(mutable.value.intent)
        check(prior.action.key == key && prior.action.origin.scope == active.scope)
        acceptLocked(prior, receipt)
    }
    private suspend fun acceptLocked(prior: HandoverIntent, receipt: AcceptedHandover): HandoverIntent {
        val next = prior.copy(stage = HandoverStage.ACCEPTED, receipt = receipt)
        validateHandoverTransition(prior, next)
        persisted { store.compareAndSet(prior, next) }
        lifecycleVersion++
        mutable.value = mutable.value.copy(intent = next)
        return next
    }
    suspend fun completeAccepted(expected: HandoverIntent, incomingIsCurrent: () -> Boolean) = mutex.withLock {
        check(expected.stage == HandoverStage.ACCEPTED && !expected.priorMutationUncertainty &&
            mutable.value.intent == expected && !mutable.value.storageFault && !mutable.value.mutationUncertaintySticky &&
            workEmpty() && preparation == null && workspaceExit == null && incomingIsCurrent())
        persisted {
            check(store.read() == expected && incomingIsCurrent())
            store.completeAccepted(expected)
        }
        lifecycleVersion++
        mutable.value = mutable.value.copy(intent = null)
    }

    suspend fun cancelNotSent(active: HandoverAuthority, key: String) = mutex.withLock {
        usable(active)
        val prior = requireNotNull(mutable.value.intent)
        check(prior.action.key == key && prior.action.origin.scope == active.scope)
        require(prior.stage == HandoverStage.NOT_SENT && !mutable.value.priorQuiescenceUnverified)
        check(!prior.priorMutationUncertainty && !mutable.value.mutationUncertaintySticky)
        persisted { store.cancelNotSent(prior) }
        lifecycleVersion++
        mutable.value = mutable.value.copy(intent = null)
        resumption = null
        mutable.value = mutable.value.copy(sameActorResumed = false)
    }
}

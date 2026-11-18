package cloud.dcompany.erp.core.handover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

internal sealed interface PreparedDomainCheck {
    data class RetainedNotSent(val intent: HandoverIntent) : PreparedDomainCheck
    data class Blocked(val check: OriginalDomainCheck.Blocked) : PreparedDomainCheck
}

internal enum class OriginalDomainBlock { LOCAL_WORK, UNVERIFIED, CHECKOUT_CLAIMS, UNKNOWN_CHECKOUT, OPEN_CHECKOUT }
internal sealed interface OriginalDomainCheck {
    /** Original-A preflight only; not B authentication, handover acceptance or incoming READY. */
    data class Clear(val actionKey: String, val revision: Int) : OriginalDomainCheck
    data class Blocked(val reason: OriginalDomainBlock, val ownOrderIds: Set<String> = emptySet()) : OriginalDomainCheck
}

/** Real domain checks remain outside cache/coordinator locks. Providers have no permissive default. */
internal class OriginalHandoverDomains(
    private val operations: HandoverOperations,
    private val claims: IdleCheckoutClaims,
    private val inspectLocal: suspend () -> LocalHandoverWork,
    private val apiFor: (OriginalOperationBinding) -> HandoverStateApi,
    private val awaitWorkers: suspend (OriginalOperationBinding, HandoverCoordinator.Preparation) -> Boolean,
) {
    /** Normal path: draining/known-claim cleanup precedes this atomic real-read + durable-freeze boundary. */
    suspend fun persistPrepared(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation,
        trace: TillPreparationTrace? = null): PreparedDomainCheck = withTimeout(30_000) {
        var check: OriginalDomainCheck = OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED)
        try {
            if (!awaitDrained(binding, cap, trace)) {
                return@withTimeout PreparedDomainCheck.Blocked(OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED))
            }
            trace?.at(TillPreparationStage.DURABLE_SAVE)
            val retained = operations.persistPreparation(binding, cap) { action ->
                check = readDomains(binding, action, trace)
                if (check is OriginalDomainCheck.Clear) {
                    trace?.at(TillPreparationStage.DURABLE_SAVE)
                    PriorQuiescenceResult.VERIFIED
                } else PriorQuiescenceResult.BLOCKED
            }
            retained?.let { PreparedDomainCheck.RetainedNotSent(it) }
                ?: PreparedDomainCheck.Blocked(check as? OriginalDomainCheck.Blocked
                    ?: OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PreparedDomainCheck.Blocked(OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED)) }
    }
    /** True settles known RAM handles only. persistPrepared must still re-read every real domain/server claim. */
    suspend fun releasePreparedKnownIdleClaims(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation,
        trace: TillPreparationTrace? = null): Boolean = withTimeout(30_000) {
        if (!awaitDrained(binding, cap, trace) ||
            !localClear(TillPreparationStage.LOCAL_BEFORE_CLAIM_CLEANUP, trace)) return@withTimeout false
        val handles = claims.snapshot(binding.authority.scope)
        trace?.claims(TillPreparationStage.LOCAL_CHECKOUT, handles.count { !it.abandoned })
        for (handle in handles) {
            if (!handle.abandoned) return@withTimeout false
            trace?.at(TillPreparationStage.CHECKOUT_CLEANUP)
            val settled = try {
                operations.cleanupPreparedCheckout(binding, cap, handle.orderId) { releaseOne(binding, cap.action, handle) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!settled) return@withTimeout false
        }
        true
    }
    private suspend fun awaitDrained(binding: OriginalOperationBinding, cap: HandoverCoordinator.Preparation,
        trace: TillPreparationTrace?): Boolean {
        trace?.at(TillPreparationStage.OPERATION_DRAIN)
        when (val drain = operations.coordinator.awaitPreparationDrained(cap, 15_000)) {
            is HandoverDrainResult.Blocked -> {
                trace?.drain(drain)
                return false
            }
            is HandoverDrainResult.Drained -> Unit
        }
        trace?.at(TillPreparationStage.WORKER_JOIN)
        return awaitWorkers(binding, cap)
    }

    private suspend fun localClear(stage: TillPreparationStage, trace: TillPreparationTrace?): Boolean {
        trace?.at(stage)
        val local = inspectLocal()
        trace?.local(local)
        return local.isClear
    }

    private suspend fun readDomains(binding: OriginalOperationBinding, action: HandoverAction,
        trace: TillPreparationTrace? = null): OriginalDomainCheck {
        if (!localClear(TillPreparationStage.LOCAL_BEFORE_SERVER, trace)) {
            return OriginalDomainCheck.Blocked(OriginalDomainBlock.LOCAL_WORK)
        }
        val open = claims.snapshot(binding.authority.scope).count { !it.abandoned }
        trace?.claims(TillPreparationStage.LOCAL_CHECKOUT, open)
        if (open != 0) return OriginalDomainCheck.Blocked(OriginalDomainBlock.OPEN_CHECKOUT)
        val observedClaims = claims.snapshot(binding.authority.scope)
        trace?.at(TillPreparationStage.SERVER_PROFILE_BEFORE)
        val api = apiFor(binding)
        operations.requireFreshOriginalProfile(binding, api.me())
        trace?.at(TillPreparationStage.SERVER_SHIFT)
        val state = api.state(action.shiftId).also { it.requireOriginal(action) }
        trace?.at(TillPreparationStage.SERVER_PROFILE_AFTER)
        operations.requireFreshOriginalProfile(binding, api.me())
        if (!localClear(TillPreparationStage.LOCAL_AFTER_SERVER, trace)) {
            return OriginalDomainCheck.Blocked(OriginalDomainBlock.LOCAL_WORK)
        }
        val own = state.ownActiveCheckoutOrderIds.toSet()
        claims.observeAbsent(binding.authority.scope, own, observedClaims)
        trace?.claims(TillPreparationStage.SERVER_CHECKOUT, own.size)
        return if (own.isEmpty()) OriginalDomainCheck.Clear(action.key, state.revision)
            else OriginalDomainCheck.Blocked(OriginalDomainBlock.CHECKOUT_CLAIMS, own)
    }

    suspend fun inspect(binding: OriginalOperationBinding, expected: HandoverIntent): OriginalDomainCheck = withTimeout(30_000) {
        var result: OriginalDomainCheck = OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED)
        try {
            val verified = operations.observeNotSent(binding, expected) { retained ->
                result = readDomains(binding, retained.action)
                if (result is OriginalDomainCheck.Clear) PriorQuiescenceResult.VERIFIED else PriorQuiescenceResult.BLOCKED
            }
            if (verified || result is OriginalDomainCheck.Blocked) result
            else OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED) }
    }

    /** Only known idle handles. Payment/private-draft work must be resolved by A, never cleared here. */
    suspend fun releaseKnownIdleClaims(binding: OriginalOperationBinding, expected: HandoverIntent): OriginalDomainCheck = withTimeout(30_000) {
        val initial = inspect(binding, expected)
        if (initial !is OriginalDomainCheck.Blocked || initial.reason != OriginalDomainBlock.CHECKOUT_CLAIMS) return@withTimeout initial
        for (orderId in initial.ownOrderIds) {
            val handle = claims.known(binding.authority.scope, orderId)
                ?: return@withTimeout OriginalDomainCheck.Blocked(OriginalDomainBlock.UNKNOWN_CHECKOUT, initial.ownOrderIds)
            try {
                val settled = operations.cleanupCheckout(binding, expected, orderId) {
                    releaseOne(binding, expected.action, handle)
                }
                if (!settled) return@withTimeout OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withTimeout OriginalDomainCheck.Blocked(OriginalDomainBlock.UNVERIFIED) }
        }
        inspect(binding, expected)
    }
    private suspend fun releaseOne(binding: OriginalOperationBinding, action: HandoverAction,
        handle: IdleCheckoutClaims.Handle): OperationResult<Boolean> {
        if (!binding.isCurrent() || !handle.abandoned || !claims.isCurrent(handle) || !inspectLocal().isClear) {
            return OperationResult(false, OperationOutcome.NO_WRITE_SENT)
        }
        val api = apiFor(binding)
        operations.requireFreshOriginalProfile(binding, api.me())
        val before = api.state(action.shiftId).also { it.requireOriginal(action) }
        if (handle.orderId !in before.ownActiveCheckoutOrderIds) {
            claims.released(handle)
            return OperationResult(true, OperationOutcome.NO_WRITE_SENT)
        }
        // A lost DELETE reply is settled only by a fresh exact-scope own-claim absence.
        try { api.release(handle.orderId, handle.token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the handle; the following authoritative read decides. */ }
        val after = api.state(action.shiftId).also { it.requireOriginal(action) }
        operations.requireFreshOriginalProfile(binding, api.me())
        val absent = handle.orderId !in after.ownActiveCheckoutOrderIds && binding.isCurrent()
        if (absent) claims.released(handle)
        return OperationResult(absent, if (absent) OperationOutcome.DURABLE_OUTCOME else OperationOutcome.UNRESOLVED)
    }

}

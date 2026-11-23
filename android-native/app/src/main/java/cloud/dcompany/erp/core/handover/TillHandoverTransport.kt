package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Transport only. The calling workspace retains A and never activates B from this receipt. */
internal class TillHandoverTransport(
    private val operations: HandoverOperations,
    private val domains: OriginalHandoverDomains,
    private val apiFor: (OriginalOperationBinding, String) -> TillHandoverApi,
) {
    private val exchange = Mutex()

    suspend fun choices(binding: OriginalOperationBinding, shiftId: String, afterId: String? = null):
        Pair<Int, TillOperatorCandidates> {
        handoverUuid(shiftId)
        afterId?.let(::handoverUuid)
        operations.requireOperationalReadiness()
        val (api, _) = verifiedApi(binding, sending = true)
        val actorId = binding.authority.scope.actorId
        val state = api.state(shiftId).also { it.requireOriginal(shiftId, actorId) }
        val candidates = api.candidates(shiftId, afterId).also { it.verify(actorId, afterId) }
        operations.requireOperationalReadiness()
        check(binding.isCurrent())
        return state.revision to candidates
    }

    suspend fun submit(binding: OriginalOperationBinding, action: HandoverAction, password: String,
        approved: Boolean): HandoverIntent = oneExchange {
        requireApproval(password, approved)
        val trace = TillPreparationTrace()
        val retained = try {
            val preparation = operations.prepare(binding, action)
            try {
                check(domains.releasePreparedKnownIdleClaims(binding, preparation, trace))
                val result = domains.persistPrepared(binding, preparation, trace)
                (result as? PreparedDomainCheck.RetainedNotSent)?.intent
                    ?: error("The original till still has work to verify.")
            } finally {
                withContext(NonCancellable) {
                    if (operations.coordinator.state.value.preparationAction == action && binding.isCurrent()) {
                        operations.abortPreparation(binding, preparation)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw trace.blocked(binding.isCurrent(), operations.coordinator.state.value)
        }
        sendNotSent(binding, retained, password)
    }

    /** A retained unsent action needs a new complete domain check after restoration. */
    suspend fun resumeNotSent(binding: OriginalOperationBinding, retained: HandoverIntent, password: String,
        approved: Boolean): HandoverIntent = oneExchange {
        requireApproval(password, approved)
        check(retained.stage == HandoverStage.NOT_SENT)
        if (domains.inspect(binding, retained) !is OriginalDomainCheck.Clear) {
            throw IOException("The original till still has work to verify.")
        }
        sendNotSent(binding, retained, password)
    }

    /** The caller must ask B to re-enter their password for every explicit retry. */
    suspend fun retry(binding: OriginalOperationBinding, retained: HandoverIntent, password: String,
        approved: Boolean): HandoverIntent = oneExchange {
        requireApproval(password, approved)
        requireSent(binding, retained)
        val (api, bearer) = verifiedApi(binding, sending = true)
        send(binding, retained, api, bearer, password)
    }

    suspend fun receipt(binding: OriginalOperationBinding, retained: HandoverIntent): HandoverIntent = oneExchange {
        requireSent(binding, retained)
        val (api, bearer) = verifiedApi(binding)
        settle(binding, retained, bearer, OperationPurpose.READ_HANDOVER_RECEIPT) { action ->
            api.receipt(action.shiftId, action.key)
        }
    }

    private suspend fun sendNotSent(binding: OriginalOperationBinding, retained: HandoverIntent,
        password: String): HandoverIntent {
        check(retained.stage == HandoverStage.NOT_SENT &&
            operations.status() == HandoverStartupStatus.RecoveryRequired(retained))
        val (api, bearer) = verifiedApi(binding, sending = true)
        val sent = operations.coordinator.markSentUnknown(binding.authority, retained.action.key, binding.isCurrent)
        return send(binding, sent, api, bearer, password)
    }

    private suspend fun send(binding: OriginalOperationBinding, retained: HandoverIntent,
        api: TillHandoverApi, bearer: String, password: String): HandoverIntent {
        val body = TillHandoverBody(retained.action, password)
        return settle(binding, retained, bearer, OperationPurpose.SEND_HANDOVER, body) { action ->
            api.send(action.shiftId, action.key, body)
        }
    }

    private suspend fun settle(binding: OriginalOperationBinding, retained: HandoverIntent, bearer: String,
        purpose: OperationPurpose, body: TillHandoverBody? = null,
        call: suspend (HandoverAction) -> TillHandoverReceipt): HandoverIntent {
        try {
            return operations.withHandoverWire(binding, retained, purpose, bearer, body) { permit ->
                val receipt = call(retained.action).verify(retained.action)
                operations.coordinator.acceptHandoverWire(permit, receipt, binding.isCurrent)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A rejected retry or an absent receipt cannot settle an earlier physical POST.
            throw IOException("The till handover outcome is unverified. Keep this action for recovery.")
        }
    }

    private fun requireSent(binding: OriginalOperationBinding, retained: HandoverIntent) {
        check(retained.stage == HandoverStage.SENT_UNKNOWN &&
            binding.authority.scope == retained.action.origin.scope &&
            operations.status() == HandoverStartupStatus.RecoveryRequired(retained))
    }

    private suspend fun verifiedApi(binding: OriginalOperationBinding, sending: Boolean = false): Pair<TillHandoverApi, String> {
        val bearer = operations.handoverBearer(binding)
        val api = apiFor(binding, bearer)
        val fresh = api.me()
        operations.requireFreshOriginalProfile(binding, fresh)
        if (sending) check(EffectivePermissions.from(fresh).has(ErpPermission.PosWrite)) {
            "This account cannot hand over the till."
        }
        return api to bearer
    }

    private fun requireApproval(password: String, approved: Boolean) {
        require(approved && password.codePointCount(0, password.length) in 1..256) {
            "Confirm this handover and enter the incoming operator’s password."
        }
    }

    private suspend fun oneExchange(block: suspend () -> HandoverIntent): HandoverIntent {
        check(exchange.tryLock()) { "This till handover is already being checked." }
        try {
            return block()
        } finally {
            exchange.unlock()
        }
    }
}

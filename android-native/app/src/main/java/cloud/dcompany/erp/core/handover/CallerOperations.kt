package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.DCompanyApp
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Evaluate once at the originating screen/controller construction boundary. */
internal fun applicationCallerOperations(): CallerOperations {
    val app = DCompanyApp.instance
    return app.handoverOperations.caller(app.shiftCache.profile.value, app.cacheIsolation.currentLease())
}

/** A checkpoint acknowledges only writes preceding it, never a later request or child. */
internal class OperationProgress {
    private var dispatched = 0L
    private var reconciled = 0L
    @Synchronized fun mutationDispatched() { dispatched++ }
    @Synchronized fun reconciled() { reconciled = dispatched }
    @Synchronized fun outcome(): OperationOutcome = when {
        dispatched == 0L -> OperationOutcome.NO_WRITE_SENT
        reconciled == dispatched -> OperationOutcome.DURABLE_OUTCOME
        else -> OperationOutcome.UNRESOLVED
    }
}

internal class OperationWorkScope internal constructor(
    override val coroutineContext: CoroutineContext,
    private val progress: OperationProgress,
) : CoroutineScope {
    /** Call only after response validation and the real Room/journal/domain handling finished. */
    fun reconciled() {
        check(coroutineContext[Job]?.children?.none { !it.isCompleted } == true) { "Join the full child outcomes before acknowledging this operation." }
        progress.reconciled()
    }
}

/** Construct once with the originating profile/cache/login binding, not inside a late action. */
internal class CallerOperations(
    private val original: OriginalOperationBinding?,
    private val execute: suspend (OriginalOperationBinding, suspend CoroutineScope.(HandoverCoordinator.Permit) -> OperationResult<Unit>) -> Unit,
    private val deferredOriginal: (() -> OriginalOperationBinding?)?,
) {
    constructor(original: OriginalOperationBinding?, execute: suspend (OriginalOperationBinding, suspend CoroutineScope.(HandoverCoordinator.Permit) -> OperationResult<Unit>) -> Unit) : this(original, execute, null)
    constructor(manager: HandoverOperations, original: OriginalOperationBinding?) : this(original, { binding, block -> manager.run(binding, block) })

    suspend fun run(block: suspend OperationWorkScope.() -> Unit) {
        val binding = checkNotNull(deferredOriginal?.invoke() ?: original) { "The original signed-in workspace was not available." }
        check(binding.isCurrent()) { "The original signed-in workspace changed." }
        execute(binding) { permit ->
            val progress = OperationProgress()
            withContext(OperationDispatchContext(binding, permit, progress).asCoroutineContext()) {
                coroutineScope { OperationWorkScope(coroutineContext, progress).block() }
            }
            OperationResult(Unit, progress.outcome())
        }
    }

    /** The refusal callback also releases flags set synchronously before admission. */
    fun launch(
        scope: CoroutineScope,
        onRejected: (Throwable) -> Unit,
        block: suspend OperationWorkScope.() -> Unit,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var entered = false
        try { this@CallerOperations.run { entered = true; block(this) } }
        catch (cancelled: CancellationException) {
            if (!entered) onRejected(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            // Once entered, the caller owns its existing error/finally policy.
            // An unexpected escaping error still remains an unresolved coordinator outcome.
            onRejected(failure)
        }
    }
}

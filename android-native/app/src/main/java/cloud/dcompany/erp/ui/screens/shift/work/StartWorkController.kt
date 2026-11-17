package cloud.dcompany.erp.ui.screens.shift.work

import cloud.dcompany.erp.core.net.ApiException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class StartWorkAuthority(val actorId: String, val companyId: String, val branchId: String,
    val terminalId: String, val installationId: String, val actorName: String, val authBinding: String,
    val current: () -> Boolean)

internal interface StartWorkRuntime {
    fun current(): Boolean
    fun online(): Boolean
    suspend fun capture(): StartWorkAuthority
    suspend fun attempt(authority: StartWorkAuthority, pending: PendingStartWork,
        receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit)
    suspend fun observe(pending: PendingStartWork, ownerEmail: String?, password: String?, cancel: Boolean,
        receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit)
}
internal class StartWorkRecoveryReviewRequired(message: String) : IllegalStateException(message)

internal data class StartWorkState(val ready: Boolean = false, val busy: Boolean = false,
    val pending: List<PendingStartWork> = emptyList(), val error: String? = null, val notice: String? = null,
    val completion: String? = null)

/** Singleton operation lock spans dispatch and owned journal handling across route instances. */
internal class StartWorkController(private val runtime: StartWorkRuntime, private val store: StartWorkStore) {
    companion object { private val operation = Mutex() }
    private val mutable = MutableStateFlow(StartWorkState())
    val state = mutable.asStateFlow()
    suspend fun refresh() = operation.withLock { refreshLocked() }
    private suspend fun refreshLocked() {
        if (!runtime.current()) return
        try { val values = store.pending(); if (runtime.current()) mutable.value = mutable.value.copy(ready = true, pending = values) }
        catch (_: Exception) { mutable.value = mutable.value.copy(ready = false, error = "Saved shift starts could not be verified. Keep this tablet's data and ask an Owner for recovery.") }
    }
    suspend fun start(cash: Long) = operation.withLock {
        if (!runtime.current() || !runtime.online()) return@withLock
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            require(cash >= 0); check(store.pending().isEmpty()) { "Resolve the saved start before creating another shift." }
            val a = runtime.capture(); check(a.current() && runtime.current())
            val ref = StartWorkReference(UUID.randomUUID().toString(), a.companyId, a.actorId, a.branchId,
                a.terminalId, a.installationId, openingFloatMinor = cash, requestHash = "")
            val value = PendingStartWork(reference = ref.copy(requestHash = ref.expectedHash()), actorName = a.actorName, authBinding = a.authBinding)
            value.validate(); store.save(null, value); check(a.current() && runtime.current())
            dispatch(value, a, first = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runtime.current()) mutable.value = mutable.value.copy(error = "Start work was not confirmed. Keep the original saved request and review its status.") }
        finally { if (runtime.current()) { refreshLocked(); mutable.value = mutable.value.copy(busy = false) } }
    }
    suspend fun retry(value: PendingStartWork) = operation.withLock {
        if (!runtime.current() || !runtime.online()) return@withLock
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            check(store.pending().contains(value)); val a = runtime.capture()
            check(runtime.current() && a.current())
            val same = a.actorId == value.reference.actorId && a.companyId == value.reference.companyId &&
                a.branchId == value.reference.branchId && a.terminalId == value.reference.terminalId &&
                a.installationId == value.reference.installationId && a.authBinding == value.authBinding
            if (same && value.cancelReason == null && value.phase != "CONFIRMED_AWAITING_HYDRATION") dispatch(value, a, first = false)
            else observe(value, null, null, false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runtime.current()) mutable.value = mutable.value.copy(error = "The original result remains unconfirmed. Owner review can check or retire this exact saved start without changing the cashier login.") }
        finally { if (runtime.current()) { refreshLocked(); mutable.value = mutable.value.copy(busy = false) } }
    }
    suspend fun owner(value: PendingStartWork, email: String, password: String, reason: String, cancel: Boolean) = operation.withLock {
        if (!runtime.current() || !runtime.online()) return@withLock
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            check(store.pending().contains(value)); var original = value
            if (cancel) {
                require(reason.trim().length in 12..500)
                original = value.copy(cancelReason = reason.trim()); store.save(value, original)
            }
            observe(original, email, password, cancel)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (runtime.current()) mutable.value = mutable.value.copy(error =
            if (error is StartWorkRecoveryReviewRequired) error.message else "Owner review did not resolve this request. Check the Owner details, or request status again; the saved action remains intact.") }
        finally { if (runtime.current()) { refreshLocked(); mutable.value = mutable.value.copy(busy = false) } }
    }
    private suspend fun dispatch(value: PendingStartWork, authority: StartWorkAuthority, first: Boolean) {
        var held = value
        if (held.phase == "REJECTED") { val next = held.copy(phase = "UNKNOWN", rejectedStatus = null); store.save(held, next); held = next }
        try { runtime.attempt(authority, held, { r ->
            check(runtime.current() && authority.current()); r.validate(held.reference)
            val next = held.copy(phase = "CONFIRMED_AWAITING_HYDRATION", receipt = r)
            store.save(held, next); held = next
        }, { outcome -> resolved(held, outcome) }) }
        catch (e: ApiException) {
            if (first && e.status in setOf(400,401,403,404,422) && runtime.current() && held.phase == "UNKNOWN") {
                val rejected = held.copy(phase = "REJECTED", rejectedStatus = e.status); store.save(held, rejected)
            }
            throw e
        }
    }
    private suspend fun observe(value: PendingStartWork, email: String?, password: String?, cancel: Boolean) {
        var held = value
        runtime.observe(value, email, password, cancel, { r ->
            check(runtime.current()); r.validate(held.reference)
            val next = held.copy(phase = "CONFIRMED_AWAITING_HYDRATION", receipt = r)
            store.save(held, next); held = next
        }, { outcome -> resolved(held, outcome) })
    }
    private suspend fun resolved(value: PendingStartWork, outcome: StartWorkOutcome) {
        check(runtime.current()); outcome.validate(value.reference)
        store.archive(value, outcome, runtime::current); check(runtime.current())
        mutable.value = mutable.value.copy(completion = value.reference.actionId, notice = when(outcome.state) {
            "cancelled" -> "Owner retired this exact unconfirmed start. It can never open a shift or clock anyone in."
            else -> "${value.actorName}'s shift start and recorded clock-in are confirmed. The current till state is shown separately."
        })
    }
}

package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.net.ApiException
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString

internal const val SHARED_ATTENDANCE_DIRECTORY = "shared-attendance-intents-v1"

/** Kept outside the user cache; callers must inspect this before a future handover gate. */
internal class SharedAttendancePendingStore(directory: File) : StaffTimePendingStore by AtomicStaffTimePendingStore(directory) {
    suspend fun hasPending(actorId: String, companyId: String, branchId: String, terminalId: String): Boolean {
        listOf(actorId, companyId, branchId, terminalId).forEach(::timeUuid)
        return read(timeDigest(listOf(actorId, companyId, branchId, terminalId).joinToString("|"))) != null
    }
}

internal data class SharedAttendanceState(
    val people: List<SharedTimePerson> = emptyList(),
    val nextCursor: String? = null,
    val selected: SharedTimePerson? = null,
    val pending: PendingTimeAttempt? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/** B proves only an attendance action. A remains the sole authenticated cashier. */
internal class SharedAttendanceController(
    private val actorId: String,
    private val companyId: String,
    private val environment: StaffTimeEnvironment,
    private val store: StaffTimePendingStore,
) {
    private val operation = Mutex()
    private val epoch = AtomicLong()
    private val publication = Any()
    private var scope: String? = null
    private val mutable = MutableStateFlow(SharedAttendanceState())
    val state = mutable.asStateFlow()
    fun handoverBlocked() = synchronized(publication) {
        epoch.incrementAndGet()
        mutable.value = mutable.value.copy(busy = false, ready = false,
            error = "Resolve the retained till handover in the original account before recording attendance. Saved requests are retained.")
    }
    fun invalidate() = synchronized(publication) {
        epoch.incrementAndGet()
        mutable.value = SharedAttendanceState(error = "Reopen attendance in the verified cashier workspace.")
    }
    fun offline() = synchronized(publication) {
        epoch.incrementAndGet()
        mutable.value = mutable.value.copy(ready = false, busy = false, selected = null,
            error = "Connect before verifying another staff member. Retained requests are not replayed automatically.")
    }
    private fun current(a: StaffTimeAuthority, e: Long) = a.isCurrent() && epoch.get() == e
    private fun publish(a: StaffTimeAuthority, e: Long, change: (SharedAttendanceState) -> SharedAttendanceState) = synchronized(publication) {
        if (current(a, e)) mutable.value = change(mutable.value)
    }
    private suspend fun authority(): StaffTimeAuthority {
        check(environment.online()) { "Connect before recording attendance." }
        val a = environment.capture()
        check(a.isCurrent() && a.actorId == actorId && a.companyId == companyId && a.canClockShared)
        timeUuid(requireNotNull(a.branchId)); timeUuid(requireNotNull(a.terminalId))
        requireNotNull(a.sharedApi)
        check(a.authBinding?.matches(Regex("[0-9a-f]{64}")) == true) { "Sign in again to verify this cashier session." }
        check(scope == null || scope == a.scopeKey) { "The attendance workspace changed." }
        scope = a.scopeKey
        return a
    }
    private fun matches(i: StaffTimeIntent, a: StaffTimeAuthority): Boolean =
        i.actorId == a.actorId && i.companyId == a.companyId && i.branchId == a.branchId && i.terminalId == a.terminalId &&
            i.kind in setOf(TimeWriteKind.SHARED_IN, TimeWriteKind.SHARED_OUT)
    private fun safeFailure(error: Throwable): String = when (error) {
        is ApiException -> "The server could not confirm this action (HTTP ${error.status ?: "unavailable"})."
        else -> "Attendance could not be verified. Refresh or retry the retained request."
    }
    suspend fun refresh(more: Boolean = false) {
        if (!operation.tryLock()) return
        val e = epoch.get()
        var a: StaffTimeAuthority? = null
        try {
            val s = mutable.value
            if (more && s.nextCursor == null) return
            a = authority()
            publish(a, e) { it.copy(busy = true, ready = false, error = null) }
            val pending = store.read(a.scopeKey)
            pending?.let { it.validate(); check(matches(it.intent, a)) }
            val cursor = if (more) s.nextCursor else null
            val page = requireNotNull(a.sharedApi).people(cursor)
            require(page.items.size <= 50)
            page.items.forEach {
                timeUuid(it.id); require(it.name.length in 1..200)
                require(it.onDuty == (it.attendanceId != null)); it.attendanceId?.let(::timeUuid)
            }
            page.nextCursor?.let { timeUuid(it); require(cursor == null || it > cursor) }
            require(page.items.map { it.id }.distinct().size == page.items.size)
            val people = if (more) s.people + page.items else page.items
            require(people.map { it.id }.distinct().size == people.size)
            publish(a, e) { it.copy(people = people, nextCursor = page.nextCursor, selected = null,
                pending = pending, ready = true, busy = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (a != null) publish(a, e) { it.copy(ready = false, busy = false, error = safeFailure(error)) }
            else if (epoch.get() == e) mutable.value = mutable.value.copy(ready = false, busy = false, error = safeFailure(error))
        } finally { operation.unlock() }
    }
    fun select(id: String) {
        val s = mutable.value
        if (s.ready && !s.busy && s.pending == null) mutable.value = s.copy(selected = s.people.singleOrNull { it.id == id }, error = null)
    }
    fun dismiss() { if (!mutable.value.busy) mutable.value = mutable.value.copy(selected = null) }
    suspend fun confirm(password: String, breakText: String) = write(password, breakText, false)
    suspend fun retry(password: String) = write(password, "", true)
    private suspend fun write(password: String, breakText: String, retry: Boolean): Boolean {
        if (!operation.tryLock()) return false
        val e = epoch.get()
        var a: StaffTimeAuthority? = null
        try {
            check(password.length in 1..256) { "Enter this staff member's password." }
            check(mutable.value.ready) { "Refresh the verified attendance directory." }
            a = authority()
            check(current(a, e))
            val prior = store.read(a.scopeKey)
            val attempt = if (retry) requireNotNull(prior) else {
                check(prior == null)
                val person = requireNotNull(mutable.value.selected)
                check(person in mutable.value.people)
                val fields = SharedTimeFields(person.id, person.attendanceId, if (person.onDuty) actualBreak(breakText) else null)
                PendingTimeAttempt(StaffTimeIntent(UUID.randomUUID().toString(), actorId, companyId,
                    requireNotNull(a.branchId), a.terminalId, requireNotNull(a.authBinding), person.id, person.name,
                    if (person.onDuty) TimeWriteKind.SHARED_OUT else TimeWriteKind.SHARED_IN,
                    person.attendanceId, StaffTimeJson.encodeToString(fields)))
            }
            attempt.validate(); check(matches(attempt.intent, a))
            val dispatchAttempt = if (retry) store.prepareForDispatch(a.scopeKey, attempt)
                else attempt.also { store.save(a.scopeKey, it) }
            publish(a, e) { it.copy(pending = dispatchAttempt, busy = true, selected = null, error = null) }
            if (!current(a, e)) return false
            val i = attempt.intent
            val fields = StaffTimeJson.decodeFromString<SharedTimeFields>(i.payload)
            try {
                val api = requireNotNull(a.sharedApi)
                val receipt = if (i.kind == TimeWriteKind.SHARED_IN) api.clockIn(SharedTimeClockIn(i.targetId, password), i.key)
                    else api.clockOut(SharedTimeClockOut(i.targetId, password, requireNotNull(fields.attendanceId), requireNotNull(fields.breakMinutes)), i.key)
                validateSharedReceipt(i, receipt)
                check(current(a, e))
                store.complete(a.scopeKey, i.key)
                publish(a, e) { it.copy(pending = null, busy = false, ready = false,
                    notice = "${receipt.staffName}: clock-${if (i.kind == TimeWriteKind.SHARED_IN) "in" else "out"} confirmed. Cashier unchanged.") }
                return current(a, e)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = (error as? ApiException)?.status
                // A denial during reauthentication cannot disprove an earlier unknown commit.
                val definitive = status in DEFINITIVE_TIME_REJECTIONS && (!retry || prior?.rejectedStatus != null)
                val kept = if (definitive) store.reject(a.scopeKey, i.key, requireNotNull(status)) else store.read(a.scopeKey)
                publish(a, e) { it.copy(pending = kept, busy = false, ready = false, error = safeFailure(error)) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (a != null) publish(a, e) { it.copy(busy = false, error = if (error is IllegalArgumentException) "Review the password and actual break minutes." else safeFailure(error)) }
            else if (epoch.get() == e) mutable.value = mutable.value.copy(busy = false, ready = false, error = safeFailure(error))
        } finally { operation.unlock() }
        return false
    }
    suspend fun acknowledgeRejected() {
        if (!operation.tryLock()) return
        val e = epoch.get()
        try {
            val a = authority()
            val kept = requireNotNull(store.read(a.scopeKey))
            check(current(a, e) && matches(kept.intent, a) && kept.rejectedStatus in DEFINITIVE_TIME_REJECTIONS)
            store.acknowledgeRejected(a.scopeKey, kept.intent.key)
            publish(a, e) { it.copy(pending = null, ready = false, notice = "Rejected request kept in private evidence. Refresh before a corrected request.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (epoch.get() == e) mutable.value = mutable.value.copy(error = safeFailure(error)) }
        finally { operation.unlock() }
    }
}

internal fun validateSharedReceipt(i: StaffTimeIntent, r: SharedTimeReceipt) {
    i.validate(); timeUuid(r.id); Instant.parse(r.updatedAt)
    require(r.userId == i.targetId && r.branchId == i.branchId && r.staffName.length in 1..200)
    val start = Instant.parse(r.clockInAt)
    if (i.kind == TimeWriteKind.SHARED_IN) require(r.clockOutAt == null && r.breakMinutes == 0)
    else {
        require(i.kind == TimeWriteKind.SHARED_OUT)
        val fields = StaffTimeJson.decodeFromString<SharedTimeFields>(i.payload)
        val end = Instant.parse(requireNotNull(r.clockOutAt))
        require(r.id == i.attendanceId && r.breakConfirmed && r.breakMinutes == fields.breakMinutes &&
            !end.isBefore(start) && r.breakMinutes.toLong() * 60 <= java.time.Duration.between(start, end).seconds)
    }
}

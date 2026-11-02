package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.money.minorToRupeesInput
import cloud.dcompany.erp.core.net.ApiException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class StaffTimeAuthorityChanged : IllegalStateException("Access or credentials changed. Reopen the verified workspace.")

internal class StaffTimeAuthority(
    val actorId: String,
    val companyId: String,
    val branchId: String?,
    val terminalId: String?,
    val authBinding: String?,
    val canManage: Boolean,
    val isCurrent: () -> Boolean,
    val api: StaffTimeApi,
) {
    val scopeKey = timeDigest(listOf(actorId, companyId, branchId.orEmpty(), terminalId.orEmpty()).joinToString("|"))
}
internal interface StaffTimeEnvironment {
    fun online(): Boolean
    suspend fun capture(): StaffTimeAuthority
}
internal data class TimeEditDraft(
    val kind: TimeWriteKind,
    val targetId: String,
    val targetName: String,
    val epoch: Long,
    val attendance: TimeAttendance? = null,
    val expectedRevision: Int = 0,
    val breakText: String = "",
    val reason: String = "",
    val salaryText: String = "",
    val presenceText: String = "",
    val plannedBreakText: String = "",
    val weekdayMask: Int = 0,
    val effectiveFrom: String = "",
    val inUtc: String = "",
    val outUtc: String = "",
) {
    override fun toString() = "TimeEditDraft(kind=$kind, fields=<redacted>)"
}
internal data class StaffTimeState(
    val actorId: String,
    val month: String,
    val canManage: Boolean,
    val selectedId: String = actorId,
    val summary: StaffTimeSummary? = null,
    val people: List<TimePerson> = emptyList(),
    val nextPeopleCursor: String? = null,
    val peopleLoaded: Boolean = false,
    val policies: List<TimeWorkPolicy> = emptyList(),
    val policyHistoryLoaded: Boolean = false,
    val morePolicies: Boolean = false,
    val pending: PendingTimeAttempt? = null,
    val pendingAuthorityChanged: Boolean = false,
    val storageReady: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val fresh: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val draft: TimeEditDraft? = null,
) {
    val serverCurrentMonth: String? get() = summary?.let {
        YearMonth.from(Instant.parse(it.asOf).atZone(ZoneId.of(it.timezone))).toString()
    }
    val openAttendance: TimeAttendance? get() = summary?.entries?.singleOrNull { it.clockOutAt == null }
    val currentMonth: Boolean get() = month == serverCurrentMonth
}

/** Server facts, a frozen write intent and authority generations are separate state. No automatic replay. */
internal class StaffTimeController(
    private val actorId: String,
    private val companyId: String,
    initialAccess: StaffTimeAccess,
    private val environment: StaffTimeEnvironment,
    private val store: StaffTimePendingStore,
    initialMonth: String = YearMonth.now(ZoneOffset.UTC).toString(),
) {
    private val epoch = AtomicLong()
    private val publication = Any()
    private val writer = Mutex()
    private var boundScope: String? = null
    private var monthChosen = false
    private val mutable = MutableStateFlow(StaffTimeState(actorId, timeMonth(initialMonth), initialAccess.canManage))
    val state = mutable.asStateFlow()
    fun invalidate() = synchronized(publication) {
        epoch.incrementAndGet()
        mutable.value = StaffTimeState(actorId, mutable.value.month, false,
            error = "The verified workspace changed. Reopen Staff in the current signed-in workspace.")
    }
    private fun publish(a: StaffTimeAuthority, e: Long, transform: (StaffTimeState) -> StaffTimeState) = synchronized(publication) {
        if (current(a, e)) mutable.value = transform(mutable.value)
    }
    private fun readFailure(error: Throwable) {
        if (error is StaffTimeAuthorityChanged || (error as? ApiException)?.status in setOf(401, 403, 404)) invalidate()
        else fail(message(error))
    }
    private fun current(a: StaffTimeAuthority, e: Long): Boolean = a.isCurrent() && epoch.get() == e
    private suspend fun authority(): StaffTimeAuthority {
        check(environment.online()) { "Reconnect to view or record verified working time." }
        val a = environment.capture()
        check(a.actorId == actorId && a.companyId == companyId && a.isCurrent()) { "The staff-time workspace changed." }
        if (boundScope != null && boundScope != a.scopeKey) invalidate()
        boundScope = a.scopeKey
        return a
    }
    private fun fail(message: String) { mutable.value = mutable.value.copy(loading = false, busy = false, fresh = false, error = message) }
    private fun message(error: Throwable) = when (error) {
        is ApiException -> error.message ?: "The server could not verify this action."
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "Review the entered values."
        else -> "The server response could not be verified. Refresh before continuing."
    }
    suspend fun refresh() {
        if (mutable.value.busy || mutable.value.draft != null) return
        val e = epoch.incrementAndGet()
        mutable.value = mutable.value.copy(loading = true, fresh = false, error = null)
        var a: StaffTimeAuthority? = null
        try {
            a = authority()
            if (!current(a, e)) return
            var month = mutable.value.month
            val target = mutable.value.selectedId
            check(target == actorId || a.canManage) { "You can only view your own time." }
            var summary = a.api.summary(month, target.takeUnless { it == actorId })
            validateSummary(summary, target, month, a.canManage)
            if (!current(a, e)) return
            val serverMonth = YearMonth.from(Instant.parse(summary.asOf).atZone(ZoneId.of(summary.timezone))).toString()
            if (!monthChosen && month != serverMonth) {
                month = timeMonth(serverMonth)
                summary = a.api.summary(month, target.takeUnless { it == actorId })
                validateSummary(summary, target, month, a.canManage)
                if (!current(a, e)) return
            }
            val pending = store.read(a.scopeKey)
            if (!current(a, e)) return
            var policies = emptyList<TimeWorkPolicy>()
            if (a.canManage) {
                policies = a.api.policies(target, null)
                validatePolicies(policies, target)
                if (!current(a, e)) return
            }
            publish(a, e) { it.copy(month = month, canManage = a.canManage, summary = summary,
                pending = pending, pendingAuthorityChanged = pending != null && !matches(pending.intent, a),
                policies = policies, policyHistoryLoaded = a.canManage, morePolicies = policies.size == 50,
                storageReady = true, loading = false, fresh = true, error = null) }
            if (a.canManage && !mutable.value.peopleLoaded) loadPeople(false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (a == null || current(a, e)) readFailure(error)
            else if (!a.isCurrent()) invalidate()
        }
    }
    suspend fun selectPerson(id: String) {
        if (mutable.value.busy || mutable.value.pending != null || mutable.value.draft != null) return
        if (!mutable.value.canManage || (id != actorId && mutable.value.people.none { it.id == id })) return
        timeUuid(id)
        epoch.incrementAndGet()
        mutable.value = mutable.value.copy(selectedId = id, summary = null, policies = emptyList(),
            policyHistoryLoaded = false, fresh = false, error = null, notice = null)
        refresh()
    }
    suspend fun selectMonth(month: String) {
        if (mutable.value.busy || mutable.value.draft != null) return
        try { timeMonth(month) } catch (error: Throwable) { mutable.value = mutable.value.copy(error = message(error)); return }
        monthChosen = true; epoch.incrementAndGet()
        mutable.value = mutable.value.copy(month = month, summary = null, fresh = false)
        refresh()
    }
    suspend fun loadPeople(more: Boolean) {
        val s = mutable.value
        if (!s.canManage || s.busy || (more && s.nextPeopleCursor == null)) return
        val e = epoch.get()
        try {
            val a = authority()
            check(a.canManage)
            val cursor = if (more) s.nextPeopleCursor else null
            val page = a.api.people(cursor)
            require(page.items.size <= 50)
            page.items.forEach { timeUuid(it.id); require(it.name.length in 1..200) }
            page.nextCursor?.let { timeUuid(it); require(it != cursor) }
            require(page.items.map { it.id }.distinct().size == page.items.size)
            if (!current(a, e)) return
            val rows = if (more) s.people + page.items else page.items
            require(rows.map { it.id }.distinct().size == rows.size)
            publish(a, e) { it.copy(people = rows, nextPeopleCursor = page.nextCursor, peopleLoaded = true) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (epoch.get() == e) readFailure(error) }
    }
    suspend fun olderPolicies() {
        val s = mutable.value
        if (!s.canManage || !s.morePolicies || s.busy || s.policies.isEmpty()) return
        val e = epoch.get()
        try {
            val a = authority(); check(a.canManage)
            val before = s.policies.last().revision
            val page = a.api.policies(s.selectedId, before)
            validatePolicies(page, s.selectedId)
            require(page.all { it.revision < before })
            if (!current(a, e)) return
            publish(a, e) { it.copy(policies = s.policies + page, morePolicies = page.size == 50) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (epoch.get() == e) readFailure(error) }
    }
    fun openClockDraft(out: Boolean) {
        val s = mutable.value
        if (!s.fresh || !s.currentMonth || s.pending != null || s.busy || s.summary == null) return
        val other = s.selectedId != actorId
        if (other && !s.canManage) return
        if (!out && (s.openAttendance != null || s.people.any { it.id == s.selectedId && (it.departed || it.status != "active") })) return
        val entry = if (out) s.openAttendance ?: return else null
        val kind = if (other) { if (out) TimeWriteKind.OWNER_OUT else TimeWriteKind.OWNER_IN }
            else if (out) TimeWriteKind.SELF_OUT else TimeWriteKind.SELF_IN
        mutable.value = s.copy(draft = TimeEditDraft(kind, s.selectedId, s.summary.name, epoch.get(), entry), error = null)
    }
    fun openCorrection(entry: TimeAttendance) {
        val s = mutable.value
        if (!s.canManage || !s.fresh || s.pending != null || s.busy || entry !in s.summary?.entries.orEmpty()) return
        fun utc(value: String) = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toString()
        mutable.value = s.copy(draft = TimeEditDraft(TimeWriteKind.CORRECTION, s.selectedId, requireNotNull(s.summary).name,
            epoch.get(), entry, breakText = if (entry.breakConfirmed || entry.breakMinutes > 0) entry.breakMinutes.toString() else "",
            inUtc = utc(entry.clockInAt), outUtc = entry.clockOutAt?.let(::utc).orEmpty()), error = null)
    }
    fun openPolicy() {
        val s = mutable.value
        if (!s.canManage || !s.fresh || !s.policyHistoryLoaded || s.pending != null || s.busy || s.summary == null) return
        if (s.people.any { it.id == s.selectedId && (it.departed || it.status != "active") }) return
        val prior = s.summary.policy
        mutable.value = s.copy(draft = TimeEditDraft(TimeWriteKind.POLICY, s.selectedId, s.summary.name,
            epoch.get(), expectedRevision = s.policies.firstOrNull()?.revision ?: 0,
            salaryText = prior?.monthlySalaryMinor?.let(::minorToRupeesInput).orEmpty(),
            presenceText = prior?.dailyPresenceMinutes?.toString().orEmpty(),
            plannedBreakText = prior?.dailyBreakMinutes?.toString().orEmpty(), weekdayMask = prior?.workingWeekdayMask ?: 0,
            effectiveFrom = Instant.parse(s.summary.asOf).atZone(ZoneId.of(s.summary.timezone)).toLocalDate().toString()), error = null)
    }
    fun editDraft(transform: (TimeEditDraft) -> TimeEditDraft) {
        if (mutable.value.busy) return
        val original = mutable.value.draft ?: return
        val next = transform(original)
        require(next.kind == original.kind && next.targetId == original.targetId && next.epoch == original.epoch &&
            next.attendance == original.attendance && next.expectedRevision == original.expectedRevision)
        mutable.value = mutable.value.copy(draft = next, error = null)
    }
    fun closeDraft() { if (!mutable.value.busy) mutable.value = mutable.value.copy(draft = null, error = null) }
    fun markOffline() {
        mutable.value = mutable.value.copy(fresh = false,
            error = "Offline: the last verified time is shown for review. Recording time and draft pay requires a connection.")
    }
    private fun makeIntent(a: StaffTimeAuthority, d: TimeEditDraft): StaffTimeIntent {
        check(d.epoch == epoch.get() && d.targetId == mutable.value.selectedId) { "Refresh this person's time before confirming." }
        val branch = requireNotNull(a.branchId) { "Select a verified branch workspace before recording time." }
        val proof = requireNotNull(a.authBinding) { "Sign in again to verify this staff-time session." }
        val body = when (d.kind) {
            TimeWriteKind.SELF_IN -> StaffTimeJson.encodeToString(TimeSelfClockIn(branch))
            TimeWriteKind.SELF_OUT -> StaffTimeJson.encodeToString(TimeSelfClockOut(requireNotNull(d.attendance).id, actualBreak(d.breakText)))
            TimeWriteKind.OWNER_IN -> StaffTimeJson.encodeToString(TimeOwnerClockIn(branch, timeReason(d.reason)))
            TimeWriteKind.OWNER_OUT -> StaffTimeJson.encodeToString(TimeOwnerClockOut(requireNotNull(d.attendance).id, actualBreak(d.breakText), timeReason(d.reason)))
            TimeWriteKind.CORRECTION -> StaffTimeJson.encodeToString(TimeAttendanceCorrection(requireNotNull(d.attendance).updatedAt,
                utcAttendance(d.inUtc), d.outUtc.trim().takeIf(String::isNotEmpty)?.let(::utcAttendance), actualBreak(d.breakText), timeReason(d.reason)))
            TimeWriteKind.POLICY -> StaffTimeJson.encodeToString(TimePolicyWrite(d.expectedRevision, LocalDate.parse(d.effectiveFrom).toString(),
                draftSalary(d.salaryText), requireNotNull(d.presenceText.toIntOrNull()) { "Enter planned presence in whole minutes." },
                requireNotNull(d.plannedBreakText.toIntOrNull()) { "Enter planned break in whole minutes, including 0." },
                d.weekdayMask, reason = timeReason(d.reason)))
        }
        return StaffTimeIntent(UUID.randomUUID().toString(), actorId, companyId, branch, a.terminalId,
            proof, d.targetId, d.targetName, d.kind, d.attendance?.id, body).also { it.validate(); require(!it.needsOwner || a.canManage) }
    }
    private fun matches(i: StaffTimeIntent, a: StaffTimeAuthority): Boolean =
        i.actorId == a.actorId && i.companyId == a.companyId && i.branchId == a.branchId &&
            i.terminalId == a.terminalId && i.authBinding == a.authBinding && (!i.needsOwner || a.canManage)
    suspend fun confirmDraft() = write(false)
    suspend fun retryExact() = write(true)
    private suspend fun write(retry: Boolean) {
        if (!writer.tryLock()) return
        var a: StaffTimeAuthority? = null
        val e = epoch.get()
        try {
            val s = mutable.value
            check(s.fresh && s.storageReady && !s.loading) { "Refresh verified time before confirming." }
            a = authority()
            check(current(a, e)) { "The verified workspace changed." }
            val retained = store.read(a.scopeKey)
            val intent = if (retry) {
                requireNotNull(retained) { "There is no retained request to retry." }.intent
            } else {
                check(retained == null) { "Resolve the retained request before recording another one." }
                try {
                    makeIntent(a, requireNotNull(s.draft) { "Review this action before confirming." })
                } catch (invalid: IllegalArgumentException) {
                    // Editable input failed before persistence or transport; verified facts remain valid.
                    publish(a, e) { it.copy(error = message(invalid)) }
                    return
                } catch (invalid: DateTimeException) {
                    publish(a, e) { it.copy(error = "Enter a valid date or explicit UTC attendance timestamp.") }
                    return
                }
            }
            intent.validate()
            check(matches(intent, a)) { "Access or credentials changed. The earlier request is retained for Owner review; do not repeat it." }
            mutable.value = mutable.value.copy(busy = true, error = null)
            if (!retry) store.save(a.scopeKey, PendingTimeAttempt(intent))
            if (!current(a, e)) return
            publish(a, e) { it.copy(pending = retained ?: PendingTimeAttempt(intent)) }
            try {
                dispatch(a, intent)
                check(current(a, e)) { "The workspace changed before the receipt could be accepted." }
                store.complete(a.scopeKey, intent.key)
                if (!current(a, e)) return
                publish(a, e) { it.copy(pending = null, pendingAuthorityChanged = false,
                    draft = null, busy = false, notice = "The server confirmed this exact request.") }
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (!current(a, e)) return
                val status = (error as? ApiException)?.status
                // A later denial can precede receipt lookup. It cannot erase an earlier unknown result.
                val definitive = status in DEFINITIVE_TIME_REJECTIONS && (!retry || retained?.rejectedStatus != null)
                val pending = if (definitive) store.reject(a.scopeKey, intent.key, requireNotNull(status))
                    else store.read(a.scopeKey)
                if (status in setOf(401, 403, 404)) { invalidate(); return }
                publish(a, e) { it.copy(busy = false, fresh = false, draft = null, pending = pending,
                    error = if (definitive) "The server rejected this exact request. ${message(error)}"
                    else "The outcome is not confirmed. Keep this request and refresh; retry only this same request when the connection is stable.") }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (a == null || current(a, e)) readFailure(error) }
        finally { writer.unlock(); if (a != null && !a.isCurrent()) invalidate() }
    }
    suspend fun acknowledgeRejected() {
        if (!writer.tryLock()) return
        val e = epoch.get()
        try {
            val a = authority()
            val pending = requireNotNull(store.read(a.scopeKey))
            check(current(a, e) && matches(pending.intent, a) && pending.rejectedStatus in DEFINITIVE_TIME_REJECTIONS)
            store.acknowledgeRejected(a.scopeKey, pending.intent.key)
            if (current(a, e)) mutable.value = mutable.value.copy(pending = null, draft = null,
                notice = "The rejected request was kept in local evidence. Review a corrected request before confirming.", error = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (epoch.get() == e) fail(message(error)) }
        finally { writer.unlock() }
    }
    private suspend fun dispatch(a: StaffTimeAuthority, i: StaffTimeIntent) {
        check(a.isCurrent() && matches(i, a)) { "The request's verified authority changed." }
        val attendance: TimeAttendance? = when (i.kind) {
            TimeWriteKind.SELF_IN -> a.api.selfClockIn(StaffTimeJson.decodeFromString(i.payload), i.key)
            TimeWriteKind.SELF_OUT -> a.api.selfClockOut(StaffTimeJson.decodeFromString(i.payload), i.key)
            TimeWriteKind.OWNER_IN -> a.api.ownerClockIn(i.targetId, StaffTimeJson.decodeFromString(i.payload), i.key)
            TimeWriteKind.OWNER_OUT -> a.api.ownerClockOut(i.targetId, StaffTimeJson.decodeFromString(i.payload), i.key)
            TimeWriteKind.CORRECTION -> a.api.correct(i.targetId, requireNotNull(i.attendanceId), StaffTimeJson.decodeFromString(i.payload), i.key)
            TimeWriteKind.POLICY -> {
                val body = StaffTimeJson.decodeFromString<TimePolicyWrite>(i.payload)
                val p = a.api.savePolicy(i.targetId, body, i.key)
                validatePolicy(p, i.targetId)
                require(p.userId == i.targetId && p.revision == body.expectedRevision + 1 && p.payStatus == "draft" &&
                    p.overtimeMultiplierBasisPoints == null && p.monthlySalaryMinor == body.monthlySalaryMinor &&
                    p.effectiveFrom == body.effectiveFrom && p.dailyPresenceMinutes == body.dailyPresenceMinutes &&
                    p.dailyBreakMinutes == body.dailyBreakMinutes && p.workingWeekdayMask == body.workingWeekdayMask && p.reason == body.reason) {
                    "The draft-policy receipt did not match this request."
                }
                null
            }
        }
        if (attendance != null) {
            require(attendance.userId == i.targetId && attendance.branchId == i.branchId)
            timeUuid(attendance.id)
            val arrival = Instant.parse(attendance.clockInAt)
            val departure = attendance.clockOutAt?.let(Instant::parse)
            val updated = Instant.parse(attendance.updatedAt)
            require(attendance.breakMinutes in 0..1440)
            if (departure != null) require(!departure.isBefore(arrival) &&
                attendance.breakMinutes.toLong() * 60 <= java.time.Duration.between(arrival, departure).seconds)
            else require(attendance.breakMinutes == 0)
            when (i.kind) {
                TimeWriteKind.SELF_IN, TimeWriteKind.OWNER_IN -> require(attendance.clockOutAt == null)
                TimeWriteKind.SELF_OUT, TimeWriteKind.OWNER_OUT -> {
                    val breaks = if (i.kind == TimeWriteKind.SELF_OUT) StaffTimeJson.decodeFromString<TimeSelfClockOut>(i.payload).breakMinutes
                        else StaffTimeJson.decodeFromString<TimeOwnerClockOut>(i.payload).breakMinutes
                    require(attendance.id == i.attendanceId && attendance.clockOutAt != null && attendance.breakConfirmed && attendance.breakMinutes == breaks)
                }
                TimeWriteKind.CORRECTION -> {
                    val b = StaffTimeJson.decodeFromString<TimeAttendanceCorrection>(i.payload)
                    require(updated.isAfter(Instant.parse(b.expectedUpdatedAt)) &&
                        attendance.id == i.attendanceId && Instant.parse(attendance.clockInAt) == Instant.parse(b.clockInAt) &&
                        attendance.clockOutAt?.let(Instant::parse) == b.clockOutAt?.let(Instant::parse) &&
                        attendance.breakMinutes == b.breakMinutes && (b.clockOutAt == null || attendance.breakConfirmed))
                }
                TimeWriteKind.POLICY -> error("Unexpected policy attendance receipt")
            }
        }
    }
    private fun validateSummary(s: StaffTimeSummary, target: String, month: String, owner: Boolean) {
        require(s.userId == target && s.month == month && s.name.length in 1..200)
        ZoneId.of(s.timezone); Instant.parse(s.asOf)
        require(s.payStatus == "draft" && s.payableMinor == null && s.overtimePayMinor == null)
        require(s.closedGrossSeconds >= 0 && (s.closedWorkSeconds == null || s.closedWorkSeconds in 0..s.closedGrossSeconds) && s.provisionalOpenSeconds >= 0)
        require(s.entries.size <= 5000 && s.entries.map { it.id }.distinct().size == s.entries.size)
        s.entries.forEach { require(it.userId == target); timeUuid(it.id); timeUuid(it.branchId); Instant.parse(it.clockInAt); it.clockOutAt?.let(Instant::parse) }
        require(s.entries.count { it.clockOutAt == null } <= 1)
        if (!owner) require(s.policy == null && s.referenceHourlyNumeratorMinor == null && s.referenceHourlyDenominatorMinutes == null)
        s.policy?.let { validatePolicy(it, target) }
    }
    private fun validatePolicy(p: TimeWorkPolicy, target: String) {
        timeUuid(p.id); require(p.userId == target && p.revision > 0 && p.payStatus == "draft")
        Instant.parse(p.createdAt)
        validateTimePolicy(TimePolicyWrite(p.revision - 1, p.effectiveFrom, p.monthlySalaryMinor,
            p.dailyPresenceMinutes, p.dailyBreakMinutes, p.workingWeekdayMask, p.overtimeMultiplierBasisPoints, p.reason))
    }
    private fun validatePolicies(rows: List<TimeWorkPolicy>, target: String) {
        require(rows.size <= 50 && rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { validatePolicy(it, target) }
        require(rows.zipWithNext().all { (a, b) -> a.revision > b.revision })
    }
}

package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.net.ApiException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StaffTimeControllerTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private val entryId = "44444444-4444-4444-8444-444444444444"
    private val other = "55555555-5555-4555-8555-555555555555"
    private fun entry(id: String = user, closed: Boolean = false, confirmed: Boolean = false) = TimeAttendance(
        entryId, id, branch, "2026-11-02T08:00:00Z", if (closed) "2026-11-02T09:00:00Z" else null,
        0, "app", "2026-11-02T09:00:00Z", confirmed,
    )
    private fun summary(id: String = user, month: String = "2026-11", entries: List<TimeAttendance> = emptyList()) =
        StaffTimeSummary(id, "Synthetic worker", month, "Asia/Kolkata", "2026-11-02T09:00:00Z",
            entries, 0, 0, 0, emptyList(), "draft")
    private open inner class Api : StaffTimeApi {
        var reads = 0; var peopleCalls = 0; var policyCalls = 0
        var cardsHook: (suspend (String, String, String?) -> AttendanceCardsPage)? = null
        val writes = mutableListOf<Pair<String, String>>()
        var rows = emptyList<TimeAttendance>()
        var summaryHook: (suspend (String, String?) -> StaffTimeSummary)? = null
        var inHook: (suspend (TimeSelfClockIn, String) -> TimeAttendance)? = null
        var outHook: (suspend (TimeSelfClockOut, String) -> TimeAttendance)? = null
        override suspend fun attendanceCards(period: String, start: String, after: String?, limit: Int): AttendanceCardsPage {
            return cardsHook?.invoke(period, start, after) ?: run {
                val zone = java.time.ZoneId.of("Asia/Kolkata")
                fun fact(row: TimeAttendance) = AttendanceClockFact(row.id, row.userId, row.branchId,
                    java.time.Instant.parse(row.clockInAt).atZone(zone).toLocalDate().toString(), row.clockInAt, row.clockOutAt, row.clockOutAt == null)
                val first = java.time.LocalDate.parse(start); val end = attendancePeriodEnd(period, first)
                val entries = rows.map(::fact).filter { java.time.LocalDate.parse(it.localDate).let { d -> !d.isBefore(first) && d.isBefore(end) } }
                AttendanceCardsPage(period, start, end.toString(), zone.id, "2026-11-02T09:00:00Z", branch,
                    rows.singleOrNull { it.userId == user && it.clockOutAt == null }?.let(::fact),
                    if (entries.isEmpty()) emptyList() else listOf(AttendancePersonCard(user, "Synthetic worker", entries.map { it.localDate }.distinct().size, entries, false, false)), null)
            }
        }
        override suspend fun summary(month: String, userId: String?): StaffTimeSummary {
            reads++; return summaryHook?.invoke(month, userId) ?: summary(userId ?: user, month, rows)
        }
        override suspend fun people(afterId: String?, limit: Int): TimePeoplePage {
            peopleCalls++; return TimePeoplePage(listOf(TimePerson(other, "Synthetic colleague", "active", false)))
        }
        override suspend fun policies(userId: String, beforeRevision: Int?, limit: Int): List<TimeWorkPolicy> {
            policyCalls++; return emptyList()
        }
        override suspend fun selfClockIn(body: TimeSelfClockIn, key: String): TimeAttendance {
            writes += key to StaffTimeJson.encodeToString(TimeSelfClockIn.serializer(), body)
            return inHook?.invoke(body, key) ?: entry()
        }
        override suspend fun selfClockOut(body: TimeSelfClockOut, key: String): TimeAttendance {
            writes += key to StaffTimeJson.encodeToString(TimeSelfClockOut.serializer(), body)
            return outHook?.invoke(body, key) ?: entry(closed = true, confirmed = true).copy(breakMinutes = body.breakMinutes)
        }
        override suspend fun ownerClockIn(userId: String, body: TimeOwnerClockIn, key: String): TimeAttendance {
            writes += key to body.reason; return entry(userId)
        }
        override suspend fun ownerClockOut(userId: String, body: TimeOwnerClockOut, key: String): TimeAttendance {
            writes += key to body.reason; return entry(userId, true, true).copy(breakMinutes = body.breakMinutes)
        }
        override suspend fun correct(userId: String, attendanceId: String, body: TimeAttendanceCorrection, key: String): TimeAttendance =
            entry(userId, body.clockOutAt != null, body.clockOutAt != null).copy(clockInAt = body.clockInAt, clockOutAt = body.clockOutAt, breakMinutes = body.breakMinutes)
        override suspend fun savePolicy(userId: String, body: TimePolicyWrite, key: String): TimeWorkPolicy =
            TimeWorkPolicy(entryId, userId, body.expectedRevision + 1, body.effectiveFrom, body.monthlySalaryMinor,
                body.dailyPresenceMinutes, body.dailyBreakMinutes, body.workingWeekdayMask, null, "draft", body.reason, "2026-11-02T09:00:00Z")
    }
    private class Store : StaffTimePendingStore {
        var attempt: PendingTimeAttempt? = null; var failPrepare = false; var failSave = false; var cleared = 0; var archived = 0
        override suspend fun read(scopeKey: String) = attempt
        override suspend fun save(scopeKey: String, attempt: PendingTimeAttempt) {
            if (failSave) throw IOException("Synthetic storage failure")
            check(this.attempt == null || this.attempt == attempt); this.attempt = attempt
        }
        override suspend fun prepareForDispatch(scopeKey: String, expected: PendingTimeAttempt): PendingTimeAttempt {
            if (failPrepare) throw IOException("Synthetic transition failure")
            check(attempt == expected)
            return expected.copy(rejectedStatus = null).also { attempt = it }
        }
        override suspend fun reject(scopeKey: String, key: String, status: Int): PendingTimeAttempt {
            check(attempt?.intent?.key == key && status in DEFINITIVE_TIME_REJECTIONS)
            return requireNotNull(attempt).copy(rejectedStatus = status).also { attempt = it }
        }
        override suspend fun complete(scopeKey: String, key: String) { check(attempt?.intent?.key == key); cleared++; attempt = null }
        override suspend fun acknowledgeRejected(scopeKey: String, key: String) {
            check(attempt?.intent?.key == key && attempt?.rejectedStatus in DEFINITIVE_TIME_REJECTIONS)
            archived++; attempt = null
        }
    }
    private inner class Env(val api: Api, var manage: Boolean = false) : StaffTimeEnvironment {
        var active = true; var connected = true; var proof = "a".repeat(64); var generation = 0
        override fun online() = connected
        override suspend fun capture(): StaffTimeAuthority {
            val captured = generation
            return StaffTimeAuthority(user, company, branch, null, proof, manage, { active && generation == captured }, api)
        }
    }
    private fun controller(env: Env, store: Store) = StaffTimeController(user, company, StaffTimeAccess(true, env.manage), env, store, "2026-11")

    @Test fun ordinaryStaffReadsSharedClockFactsAndNeverPrivateSummaryPeopleOrSalaryHistory() = runBlocking {
        val api = Api(); val env = Env(api); val c = controller(env, Store()); c.refresh()
        assertTrue(c.state.value.fresh); assertNull(c.state.value.summary); assertNotNull(c.state.value.cards); assertEquals(0, api.reads)
        assertEquals(0, api.peopleCalls); assertEquals(0, api.policyCalls)
        c.selectPerson(other); c.openPolicy(); assertEquals(user, c.state.value.selectedId); assertNull(c.state.value.draft)
    }
    @Test fun ordinaryStaffNeverRequestsPotentiallyPrivatePayEstimate() = runBlocking {
        val api = Api(); val env = Env(api); val c = controller(env, Store())
        api.summaryHook = { month, id -> summary(id ?: user, month).copy(payEstimate = TimePayEstimate(
            "unavailable", "Private salary review", "paid_presence_clock_in_workday", "company", monthlySalaryMinor = 1200000)) }
        c.refresh()
        assertNull(c.state.value.summary)
        assertTrue(c.state.value.fresh); assertEquals(0, api.reads)
    }
    @Test fun ownerUsesDedicatedPeopleProjectionAndSeparatePolicyHistory() = runBlocking {
        val api = Api(); val c = controller(Env(api, true), Store()); c.refresh()
        assertEquals(1, api.peopleCalls); assertEquals(1, api.policyCalls)
        c.selectPerson(other); assertEquals(other, c.state.value.summary?.userId)
        c.openPolicy(); assertEquals("", c.state.value.draft?.salaryText)
        assertEquals("", c.state.value.draft?.presenceText); assertEquals(0, c.state.value.draft?.weekdayMask)
    }
    @Test fun storageFailureNeverDispatchesAnUnretainedRequest() = runBlocking {
        val api = Api(); val store = Store().apply { failSave = true }; val c = controller(Env(api), store)
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        assertTrue(api.writes.isEmpty()); assertNull(store.attempt)
    }
    @Test fun unknownOutcomeRetainsSameKeyPayloadAndRequiresExplicitReplay() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api), store)
        api.inHook = { _, _ -> throw IOException("Synthetic lost response") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        val frozen = requireNotNull(store.attempt)
        assertEquals(1, api.writes.size); assertFalse(c.state.value.fresh)
        c.refresh(); assertEquals(1, api.writes.size)
        api.inHook = null; c.retryExact()
        assertEquals(2, api.writes.size); assertEquals(api.writes[0], api.writes[1]); assertEquals(frozen.intent.key, api.writes[1].first)
        assertNull(store.attempt); assertEquals(1, store.cleared)
    }
    @Test fun changedCredentialsRetainUnknownIntentWithoutReplayOrDiscard() = runBlocking {
        val api = Api(); val env = Env(api); val store = Store(); val c = controller(env, store)
        api.inHook = { _, _ -> throw IOException("Synthetic lost response") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        env.proof = "b".repeat(64); c.refresh(); assertTrue(c.state.value.pendingAuthorityChanged)
        c.retryExact(); c.acknowledgeRejected()
        assertEquals(1, api.writes.size); assertNotNull(store.attempt); assertEquals(0, store.archived)
    }
    @Test fun wrongPersonOrBranchReceiptCannotClearIntent() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api), store)
        api.inHook = { _, _ -> entry(other) }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        assertNotNull(store.attempt); assertEquals(0, store.cleared)
    }
    @Test fun missingBreakConfirmationNeverTurnsZeroIntoConfirmedTime() = runBlocking {
        val api = Api().apply { rows = listOf(entry()) }; val store = Store(); val c = controller(Env(api), store)
        api.outHook = { _, _ -> entry(closed = true, confirmed = false) }
        c.refresh(); c.openClockDraft(true); assertEquals("", c.state.value.draft?.breakText)
        c.editDraft { it.copy(breakText = "0") }; c.confirmDraft()
        assertNotNull(store.attempt); assertEquals(0, store.cleared)
    }
    @Test fun explicitConfirmedZeroBreakReceiptClearsOnlyMatchingIntent() = runBlocking {
        val api = Api().apply { rows = listOf(entry()) }; val store = Store(); val c = controller(Env(api), store)
        c.refresh(); c.openClockDraft(true); c.editDraft { it.copy(breakText = "0") }; c.confirmDraft()
        assertNull(store.attempt); assertEquals(1, store.cleared)
    }
    @Test fun unknownRequestsCannotBeAcknowledgedAsDefinitiveRejections() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api), store)
        api.inHook = { _, _ -> throw ApiException("Pending", 409, "idempotency_in_progress") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft(); c.acknowledgeRejected()
        assertNotNull(store.attempt); assertEquals(0, store.archived)
        assertFalse(426 in DEFINITIVE_TIME_REJECTIONS)
    }
    @Test fun explicitServerRejectionCanBeArchivedBeforeCorrectedReview() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api), store)
        api.inHook = { _, _ -> throw ApiException("Synthetic branch rejection", 400, "business_rule") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        assertEquals(400, store.attempt?.rejectedStatus); c.acknowledgeRejected()
        assertNull(store.attempt); assertEquals(1, store.archived); assertEquals(1, api.writes.size)
    }
    @Test fun staffCannotReceiveOwnerSalaryInSummaryState() = runBlocking {
        val api = Api(); val c = controller(Env(api), Store())
        api.summaryHook = { month, id -> summary(id ?: user, month).copy(
            policy = TimeWorkPolicy(entryId, user, 1, "2026-11-01", 50_000, 600, 30, 31, null, "draft", "Private", "2026-11-01T00:00:00Z")) }
        c.refresh(); assertNull(c.state.value.summary); assertTrue(c.state.value.fresh); assertEquals(0, api.reads)
    }
    @Test fun delayedResponseAfterWorkspaceInvalidationCannotRestoreOldData() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = Api(); val env = Env(api); val c = controller(env, Store())
        api.cardsHook = { period, start, _ -> entered.complete(Unit); release.await()
            AttendanceCardsPage(period, start, attendancePeriodEnd(period, java.time.LocalDate.parse(start)).toString(),
                "UTC", "2026-11-02T09:00:00Z", branch, null, emptyList(), null) }
        val task = async { c.refresh() }; entered.await(); env.active = false; c.invalidate(); release.complete(Unit); task.await()
        assertNull(c.state.value.summary); assertTrue(c.state.value.people.isEmpty()); assertFalse(c.state.value.fresh)
    }
    @Test fun changedWorkspaceAfterServerWriteKeepsExactRequestForReview() = runBlocking {
        val api = Api(); val env = Env(api); val store = Store(); val c = controller(env, store)
        api.inHook = { _, _ -> env.generation++; entry() }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        assertNotNull(store.attempt); assertEquals(0, store.cleared); assertNull(c.state.value.summary)
    }
    @Test fun doubleConfirmNeverPublishesTwoIntentKeys() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = Api(); val store = Store(); val c = controller(Env(api), store)
        api.inHook = { _, _ -> entered.complete(Unit); release.await(); entry() }
        c.refresh(); c.openClockDraft(false)
        val first = async { c.confirmDraft() }; entered.await(); c.confirmDraft(); release.complete(Unit); first.await()
        assertEquals(1, api.writes.size); assertEquals(1, store.cleared)
    }
    @Test fun historicalCardPeriodDoesNotHideOwnClockButOfflineStillNeverDispatches() = runBlocking {
        val api = Api().apply { rows = listOf(entry()) }; val env = Env(api); val c = controller(env, Store())
        c.refresh(); c.selectAttendance("month", "2026-10-01"); c.openClockDraft(true)
        assertEquals(entryId, c.state.value.draft?.clockFact?.id); assertNull(c.state.value.draft?.attendance)
        c.closeDraft(); env.connected = false; c.refresh(); c.openClockDraft(false)
        assertFalse(c.state.value.fresh); assertNull(c.state.value.draft); assertTrue(api.writes.isEmpty())
    }
    @Test fun unknownRetry404CannotBecomeProofOfOriginalRejection() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api), store)
        api.inHook = { _, _ -> throw IOException("Synthetic original response lost") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        val original = requireNotNull(store.attempt).intent
        api.inHook = { _, _ -> throw ApiException("Target later unavailable", 404, "not_found") }
        c.refresh(); c.retryExact()
        assertEquals(original, store.attempt?.intent)
        assertNull("A retry rejection cannot prove the earlier request did not commit", store.attempt?.rejectedStatus)
        assertNull(c.state.value.summary); assertTrue(c.state.value.people.isEmpty()); assertFalse(c.state.value.canManage)
        c.acknowledgeRejected()
        assertNotNull(store.attempt); assertEquals(0, store.archived)
    }
    @Test fun unknownRetry403RetainsExactIntentAndClearsOwnerPrivateProjection() = runBlocking {
        val api = Api(); val env = Env(api, true); val store = Store(); val c = controller(env, store)
        api.summaryHook = { month, id -> summary(id ?: user, month).copy(
            policy = TimeWorkPolicy(entryId, user, 1, "2026-11-01", 50_000, 600, 30, 31, null, "draft", "Private", "2026-11-01T00:00:00Z")) }
        api.inHook = { _, _ -> throw IOException("Synthetic original response lost") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        val original = requireNotNull(store.attempt).intent
        api.inHook = { _, _ -> throw ApiException("Permission later revoked", 403, "forbidden") }
        c.refresh(); assertNotNull(c.state.value.summary?.policy); c.retryExact()
        assertEquals(original, store.attempt?.intent); assertNull(store.attempt?.rejectedStatus)
        assertNull(c.state.value.summary); assertTrue(c.state.value.people.isEmpty())
        assertTrue(c.state.value.policies.isEmpty()); assertFalse(c.state.value.canManage)
        assertEquals(0, store.cleared); assertEquals(0, store.archived)
    }

    @Test fun localBreakValidationCanBeCorrectedInPlaceWithoutRefreshOrDispatch() = runBlocking {
        val api = Api().apply { rows = listOf(entry()) }; val store = Store(); val c = controller(Env(api), store)
        c.refresh(); c.openClockDraft(true); c.confirmDraft()
        assertEquals(0, api.writes.size); assertNull(store.attempt); assertNotNull(c.state.value.draft)
        assertTrue("Local validation must not invalidate verified server facts", c.state.value.fresh)
        c.editDraft { it.copy(breakText = "0") }; c.confirmDraft()
        assertEquals(1, api.writes.size); assertNull(store.attempt); assertNull(c.state.value.draft)
    }

    private fun assertPrivateProjectionCleared(c: StaffTimeController) {
        assertNull(c.state.value.summary); assertTrue(c.state.value.people.isEmpty())
        assertTrue(c.state.value.policies.isEmpty()); assertFalse(c.state.value.canManage); assertNull(c.state.value.draft)
    }
    @Test fun scopedSummary404ClearsPrivateFactsButKeepsUnknownRequestEvidence() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api, true), store)
        api.inHook = { _, _ -> throw IOException("Synthetic original response lost") }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        val original = requireNotNull(store.attempt)
        api.summaryHook = { _, _ -> throw ApiException("Scoped person unavailable", 404, "not_found") }
        c.refresh(); assertPrivateProjectionCleared(c)
        assertEquals(original, store.attempt); assertEquals(0, store.cleared); assertEquals(0, store.archived)
    }
    @Test fun scopedPeople404ClearsPrivateFacts() = runBlocking {
        var rejectPeople = false
        val api = object : Api() {
            override suspend fun people(afterId: String?, limit: Int): TimePeoplePage {
                if (rejectPeople) throw ApiException("Scoped people unavailable", 404, "not_found")
                return super.people(afterId, limit)
            }
        }
        val c = controller(Env(api, true), Store()); c.refresh(); assertTrue(c.state.value.people.isNotEmpty())
        rejectPeople = true; c.loadPeople(false); assertPrivateProjectionCleared(c)
    }
    @Test fun scopedOlderPolicies404ClearsPrivateFacts() = runBlocking {
        val api = object : Api() {
            override suspend fun policies(userId: String, beforeRevision: Int?, limit: Int): List<TimeWorkPolicy> {
                if (beforeRevision != null) throw ApiException("Scoped history unavailable", 404, "not_found")
                return (50 downTo 1).map { revision -> TimeWorkPolicy(java.util.UUID.randomUUID().toString(), userId,
                    revision, "2026-11-01", 50_000, 600, 30, 31, null, "draft", "Synthetic private history", "2026-11-01T00:00:00Z") }
            }
        }
        val c = controller(Env(api, true), Store()); c.refresh(); assertEquals(50, c.state.value.policies.size)
        c.olderPolicies(); assertPrivateProjectionCleared(c)
    }

    @Test fun untrustedMalformedClockOutReceiptCannotClearIntent() = runBlocking {
        val api = Api().apply { rows = listOf(entry()) }; val store = Store(); val c = controller(Env(api), store)
        api.outHook = { body, _ -> entry(closed = true, confirmed = true).copy(clockOutAt = "invalid", breakMinutes = body.breakMinutes) }
        c.refresh(); c.openClockDraft(true); c.editDraft { it.copy(breakText = "0") }; c.confirmDraft()
        assertNotNull("Malformed receipt must remain unconfirmed", store.attempt); assertEquals(0, store.cleared)
    }
    private suspend fun policyReceiptMustRemainUnknown(id: String = entryId, createdAt: String = "2026-11-02T09:00:00Z") {
        val api = object : Api() {
            override suspend fun savePolicy(userId: String, body: TimePolicyWrite, key: String): TimeWorkPolicy =
                super.savePolicy(userId, body, key).copy(id = id, createdAt = createdAt)
        }
        val store = Store(); val c = controller(Env(api, true), store); c.refresh(); c.openPolicy()
        c.editDraft { it.copy(salaryText = "500", presenceText = "600", plannedBreakText = "30", weekdayMask = 31, reason = "Synthetic reviewed draft") }
        c.confirmDraft(); assertNotNull("Malformed policy receipt must remain unconfirmed", store.attempt); assertEquals(0, store.cleared)
    }
    @Test fun untrustedPolicyReceiptIdentityCannotClearIntent() = runBlocking { policyReceiptMustRemainUnknown(id = "invalid") }
    @Test fun untrustedPolicyReceiptTimestampCannotClearIntent() = runBlocking { policyReceiptMustRemainUnknown(createdAt = "invalid") }
    @Test fun untrustedOtherPersonNestedPolicyCannotBecomeSelectedSummary() = runBlocking {
        val api = Api(); val c = controller(Env(api, true), Store())
        api.summaryHook = { month, id -> summary(id ?: user, month).copy(policy = TimeWorkPolicy(entryId, other,
            1, "2026-11-01", 50_000, 600, 30, 31, null, "draft", "Synthetic wrong-person terms", "2026-11-01T00:00:00Z")) }
        c.refresh(); assertNull("Another person's terms must not publish", c.state.value.summary); assertFalse(c.state.value.fresh)
    }
    @Test fun untrustedNonAdvancingCorrectionReceiptCannotClearIntent() = runBlocking {
        val row = entry(); val api = Api().apply { rows = listOf(row) }; val store = Store(); val c = controller(Env(api, true), store)
        c.refresh(); c.openCorrection(row); c.editDraft { it.copy(breakText = "0", reason = "Synthetic reviewed correction") }
        c.confirmDraft(); assertNotNull("Correction must prove a newer revision", store.attempt); assertEquals(0, store.cleared)
    }

    @Test fun validDraftPolicyReceiptClearsOnlyTheMatchingIntentAndRefreshes() = runBlocking {
        val api = Api(); val store = Store(); val c = controller(Env(api, true), store)
        c.refresh(); c.openPolicy(); c.editDraft { it.copy(salaryText = "500", presenceText = "600",
            plannedBreakText = "30", weekdayMask = 31, reason = "Synthetic reviewed draft") }
        c.confirmDraft(); assertNull(store.attempt); assertEquals(1, store.cleared); assertTrue(c.state.value.fresh)
    }
    @Test fun validMonotonicCorrectionReceiptClearsOnlyTheMatchingIntent() = runBlocking {
        val row = entry()
        val api = object : Api() {
            override suspend fun correct(userId: String, attendanceId: String, body: TimeAttendanceCorrection, key: String): TimeAttendance =
                super.correct(userId, attendanceId, body, key).copy(updatedAt = "2026-11-02T09:00:00.000001Z")
        }.apply { rows = listOf(row) }
        val store = Store(); val c = controller(Env(api, true), store)
        c.refresh(); c.openCorrection(row); c.editDraft { it.copy(breakText = "0", reason = "Synthetic reviewed correction") }
        c.confirmDraft(); assertNull(store.attempt); assertEquals(1, store.cleared); assertTrue(c.state.value.fresh)
    }

    @Test fun rejectedRetryLostResponseIsDurablyUnknownAndCannotBeArchivedAfterRestart() = runBlocking {
        val api = Api(); val env = Env(api); val store = Store(); val c = controller(env, store)
        api.inHook = { _, _ -> throw ApiException("Denied", 401) }
        c.refresh(); c.openClockDraft(false); c.confirmDraft()
        val rejected = requireNotNull(store.attempt); assertEquals(401, rejected.rejectedStatus)
        c.refresh(); api.inHook = { _, _ ->
            assertNull(store.attempt?.rejectedStatus)
            throw IOException("Commit may have succeeded; reply lost")
        }
        c.retryExact()
        assertEquals(rejected.intent, store.attempt?.intent); assertNull(store.attempt?.rejectedStatus)
        val restored = controller(env, store); restored.refresh(); restored.acknowledgeRejected()
        assertNotNull(store.attempt); assertEquals(0, store.archived)
    }
    @Test fun rejectedRetryCancellationKeepsUnknownAndSameKey() = runBlocking {
        val api = Api(); val env = Env(api); val store = Store(); val c = controller(env, store)
        api.inHook = { _, _ -> throw ApiException("Denied", 401) }
        c.refresh(); c.openClockDraft(false); c.confirmDraft(); val intent = store.attempt!!.intent
        c.refresh(); api.inHook = { _, _ -> throw kotlinx.coroutines.CancellationException("Synthetic process loss") }
        assertTrue(runCatching { c.retryExact() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(intent, store.attempt?.intent); assertNull(store.attempt?.rejectedStatus)
        val restored = controller(env, store); restored.refresh(); restored.acknowledgeRejected()
        assertEquals(0, store.archived)
    }
    @Test fun failedUnknownTransitionPreventsRetryTransportButNewDefinitiveDenialRemainsReviewable() = runBlocking {
        val api = Api(); val env = Env(api); val store = Store(); val c = controller(env, store)
        api.inHook = { _, _ -> throw ApiException("Denied", 401) }
        c.refresh(); c.openClockDraft(false); c.confirmDraft(); c.refresh()
        store.failPrepare = true; c.retryExact()
        assertEquals(1, api.writes.size); assertEquals(401, store.attempt?.rejectedStatus)
        store.failPrepare = false; c.refresh(); c.retryExact()
        assertEquals(2, api.writes.size); assertEquals(401, store.attempt?.rejectedStatus)
    }

    @Test fun ownerRoleLossClearsPrivateSummaryAndPoliciesWhileCardsRemainUsable() = runBlocking {
        val api = Api(); val env = Env(api, true); val c = controller(env, Store())
        c.refresh(); assertNotNull(c.state.value.summary)
        env.manage = false; c.refresh()
        assertNull(c.state.value.summary); assertTrue(c.state.value.policies.isEmpty()); assertFalse(c.state.value.canManage)
        assertEquals(user, c.state.value.selectedId); assertNotNull(c.state.value.cards)
    }
    @Test fun companyMondayAndNewMonthDefaultsFollowAsOfButExplicitPeriodDoesNotMove() = runBlocking {
        val api = Api(); val c = controller(Env(api), Store())
        api.cardsHook = { period, start, _ -> AttendanceCardsPage(period, start,
            attendancePeriodEnd(period, java.time.LocalDate.parse(start)).toString(), "Asia/Dubai", "2026-11-30T21:00:00Z", branch, null, emptyList(), null) }
        c.refresh(); assertEquals("2026-11-30", c.state.value.attendanceStart)
        c.selectAttendance("month"); assertEquals("2026-12-01", c.state.value.attendanceStart)
        c.selectAttendance("week", "2026-10-05"); c.refresh(); assertEquals("2026-10-05", c.state.value.attendanceStart)
    }
    @Test fun badScopeCardCannotPublishOrAuthorizeClock() = runBlocking {
        val api = Api(); val c = controller(Env(api), Store())
        api.cardsHook = { period, start, _ -> AttendanceCardsPage(period, start,
            attendancePeriodEnd(period, java.time.LocalDate.parse(start)).toString(), "UTC", "2026-11-02T09:00:00Z", other, null, emptyList(), null) }
        c.refresh(); c.openClockDraft(false); assertFalse(c.state.value.fresh); assertNull(c.state.value.draft); assertNull(c.state.value.cards)
    }

    @Test fun sundayUtcDefaultUsesMondayCompanyWeek() = runBlocking {
        val api = Api(); val c = controller(Env(api), Store())
        api.cardsHook = { period, start, _ -> AttendanceCardsPage(period, start,
            attendancePeriodEnd(period, java.time.LocalDate.parse(start)).toString(), "Asia/Dubai", "2026-11-01T21:00:00Z", branch, null, emptyList(), null) }
        c.refresh(); assertEquals("2026-11-02", c.state.value.attendanceStart)
    }
    @Test fun selfClockOutUsesOnlyMinimalFactAndKeepsExactReceiptValidation() = runBlocking {
        val api = Api().apply { rows = listOf(entry()) }; val store = Store(); val c = controller(Env(api), store)
        c.refresh(); c.selectAttendance("month", "2026-10-01"); c.openClockDraft(true)
        val draft = requireNotNull(c.state.value.draft)
        assertNull(draft.attendance); assertEquals(entryId, draft.clockFact?.id)
        c.editDraft { it.copy(breakText = "0") }; c.confirmDraft()
        assertEquals(TimeSelfClockOut(entryId, 0), StaffTimeJson.decodeFromString<TimeSelfClockOut>(api.writes.single().second))
        assertNull(store.attempt); assertEquals(0, api.reads)
    }

}

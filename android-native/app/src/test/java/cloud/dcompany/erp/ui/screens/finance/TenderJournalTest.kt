package cloud.dcompany.erp.ui.screens.finance

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TenderJournalTest {
    private val scope = TenderScope("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333")
    private val body = PaymentTenderCommit("44444444-4444-4444-4444-444444444444", "a".repeat(64), "Confirmed by owner")
    private fun command(key: String = "exact-action") = paymentTenderCommand(scope, body, key, 1000)
    private class Storage : TenderJournalStorage {
        val rows = mutableMapOf<String, String>()
        var readFailure = false
        var writeFailure = false
        var writeAppliesBeforeFailure = false
        var removeFailure = false
        override fun read(key: String): String? {
            if (readFailure) throw IOException("read denied")
            return rows[key]
        }
        override fun put(key: String, value: String): Boolean {
            if (writeFailure) {
                if (writeAppliesBeforeFailure) rows[key] = value
                return false
            }
            rows[key] = value
            return true
        }
        override fun remove(key: String): Boolean {
            if (removeFailure) return false
            rows.remove(key)
            return true
        }
    }

    @Test fun processRestartRetainsExactBodyAndKeyWithoutAuthority() = runBlocking {
        val storage = Storage()
        TenderJournal(storage).reserve(command())
        assertEquals(command(), TenderJournal(storage).read(scope))
        assertFalse(storage.rows.values.single().contains("token"))
        assertFalse(storage.rows.values.single().contains("password"))
        assertEquals(body, journalJson.decodeFromString<PaymentTenderCommit>(command().bodyJson))
    }

    @Test fun independentInstancesNeverOverwriteAnotherAction() = runBlocking {
        val storage = Storage()
        val a = TenderJournal(storage)
        val b = TenderJournal(storage)
        val outcomes = listOf(async { runCatching { a.reserve(command("a")) } }, async { runCatching { b.reserve(command("b")) } }).awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        val saved = requireNotNull(a.read(scope))
        assertTrue(saved.idempotencyKey in setOf("a", "b"))
        assertEquals(saved, b.reserve(saved))
    }

    @Test fun exactRecoveryAfterOtherInstanceClearsNeverInventsNewIdentity() = runBlocking {
        val storage = Storage()
        val a = TenderJournal(storage)
        val b = TenderJournal(storage)
        val retained = a.reserve(command())
        assertTrue(b.clearExact(retained))
        assertNull(a.read(scope))
        assertEquals(retained, a.reserve(retained))
        assertEquals("exact-action", requireNotNull(b.read(scope)).idempotencyKey)
    }

    @Test fun accountAndBranchSwitchRetainOtherScopesButDoNotAdoptThem() = runBlocking {
        val storage = Storage()
        val journal = TenderJournal(storage)
        journal.reserve(command())
        assertNull(journal.read(scope.copy(ownerId = "55555555-5555-5555-5555-555555555555")))
        assertNull(journal.read(scope.copy(branchId = "66666666-6666-6666-6666-666666666666")))
        assertEquals(command(), journal.read(scope))
    }

    @Test fun corruptPendingDataFailsClosedAndIsNeverDeleted() = runBlocking {
        val storage = Storage()
        val journal = TenderJournal(storage)
        journal.reserve(command())
        val key = storage.rows.keys.single()
        storage.rows[key] = "{corrupt"
        assertTrue(runCatching { journal.read(scope) }.isFailure)
        assertTrue(runCatching { journal.reserve(command("new")) }.isFailure)
        assertTrue(runCatching { journal.clearExact(command()) }.isFailure)
        assertEquals("{corrupt", storage.rows[key])
    }

    @Test fun unknownSchemaOrScopeCannotBecomeEmptySlot() = runBlocking {
        val storage = Storage()
        val journal = TenderJournal(storage)
        journal.reserve(command())
        val key = storage.rows.keys.single()
        storage.rows[key] = journalJson.encodeToString(command().copy(schemaVersion = 9))
        assertTrue(runCatching { journal.read(scope) }.isFailure)
        storage.rows[key] = journalJson.encodeToString(command().copy(scope = scope.copy(branchId = "66666666-6666-6666-6666-666666666666")))
        assertTrue(runCatching { journal.read(scope) }.isFailure)
        assertEquals(1, storage.rows.size)
    }

    @Test fun readWriteAndClearFailuresNeverClaimEmptyOrSuccessfulPersistence() = runBlocking {
        val storage = Storage()
        val journal = TenderJournal(storage)
        storage.writeFailure = true
        assertTrue(runCatching { journal.reserve(command()) }.isFailure)
        storage.writeFailure = false
        journal.reserve(command())
        storage.readFailure = true
        assertTrue(runCatching { journal.read(scope) }.isFailure)
        assertTrue(runCatching { journal.reserve(command()) }.isFailure)
        storage.readFailure = false
        storage.removeFailure = true
        assertTrue(runCatching { journal.clearExact(command()) }.isFailure)
        assertEquals(command(), journal.read(scope))
    }

    @Test fun failedCommitWithUpdatedMemoryCannotAuthoriseSendOnExactRetry() = runBlocking {
        val storage = Storage().also { it.writeFailure = true; it.writeAppliesBeforeFailure = true }
        val journal = TenderJournal(storage)
        assertTrue(runCatching { journal.reserve(command()) }.isFailure)
        assertEquals(command(), journal.read(scope))
        assertTrue(runCatching { journal.reserve(command()) }.isFailure)
        assertEquals(command(), journal.read(scope))
        storage.writeFailure = false
        assertEquals(command(), journal.reserve(command()))
    }

    @Test fun compareBeforeClearProtectsNewerOrChangedCommands() = runBlocking {
        val storage = Storage()
        val journal = TenderJournal(storage)
        journal.reserve(command())
        assertFalse(journal.clearExact(command("other")))
        assertEquals(command(), journal.read(scope))
    }

    @Test fun dailyScopeAndAmountBoundariesAreValidatedWithoutPostingDelta() {
        val daily = DailyTenderCommit(scope.branchId, "2026-10-18", "a".repeat(64), 230000, 403800, "Confirmed actuals")
        val saved = dailyTenderCommand(scope, daily, "daily-exact", 1000)
        assertEquals(daily, journalJson.decodeFromString<DailyTenderCommit>(saved.bodyJson))
        assertFalse(saved.bodyJson.contains("delta"))
        assertTrue(runCatching { dailyTenderCommand(scope, daily.copy(actualCashMinor = -1), nowMillis = 1000) }.isFailure)
        assertTrue(runCatching { dailyTenderCommand(scope.copy(branchId = "55555555-5555-5555-5555-555555555555"), daily, nowMillis = 1000) }.isFailure)
    }

    @Test fun dailyReceiptVariantsMustKeepActualDifferenceAndPostingStateTruthful() {
        val body = DailyTenderCommit(scope.branchId,"2026-10-18","a".repeat(64),8000,12000,"Confirmed actuals")
        val command = dailyTenderCommand(scope,body,"daily-exact",1000)
        val receipt = TenderReceipt(
            id="receipt",receiptReference="DR-receipt",operation="daily_collections_reconciled",
            financialEffect=true,companyId=scope.companyId,branchId=scope.branchId,
            businessDate=body.businessDate,businessTimezone="Asia/Kolkata",recordedBy=scope.ownerId,
            recordedAt="2026-10-19T10:00:00Z",reason=body.reason,evidenceKind="owner_attestation",
            sourceFingerprint=body.sourceFingerprint,idempotencyKey=command.idempotencyKey,
            sourceAllocation="unallocated_owner_attestation",totalDifferenceMinor=0,
            kind="applied",beforeCashMinor=10000,beforeUpiMinor=10000,actualCashMinor=8000,actualUpiMinor=12000,
            cashDeltaMinor=-2000,upiDeltaMinor=2000,
        )
        assertTrue(command.matches(receipt))
        assertFalse(command.matches(receipt.copy(financialEffect=false)))
        assertFalse(command.matches(receipt.copy(kind="unresolved")))
        assertFalse(command.matches(receipt.copy(cashDeltaMinor=null)))
        val unresolved=receipt.copy(operation="daily_actuals_recorded",kind="unresolved",financialEffect=false,
            totalDifferenceMinor=-2000,cashDeltaMinor=0,upiDeltaMinor=0)
        assertTrue(command.matches(unresolved))
        assertFalse(command.matches(unresolved.copy(totalDifferenceMinor=0)))
        assertFalse(command.matches(unresolved.copy(financialEffect=true)))
        assertFalse(command.matches(unresolved.copy(cashDeltaMinor=-2000)))
        val missing=journalJson.encodeToString(unresolved).replace("\"total_difference_minor\":-2000,", "")
        assertTrue(runCatching { journalJson.decodeFromString<TenderReceipt>(missing) }.isFailure)
    }

    @Test fun grantIsMemoryOnlyAndBoundToExactOwnerBranchPurposeAndExpiry() {
        val grant = TenderGrant(scope, TenderKind.PAYMENT, "write-secret", "history-secret", 3000)
        assertTrue(grant.allows(command(), 2999))
        assertFalse(grant.allows(command(), 3000))
        assertFalse(grant.copy(kind = TenderKind.DAILY).allows(command(), 2000))
        assertFalse(grant.copy(scope = scope.copy(ownerId = "55555555-5555-5555-5555-555555555555")).allows(command(), 2000))
        assertFalse(grant.copy(scope = scope.copy(branchId = "66666666-6666-6666-6666-666666666666")).allows(command(), 2000))
        assertFalse(journalJson.encodeToString(command()).contains("secret"))
    }
}

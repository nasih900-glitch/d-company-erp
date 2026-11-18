package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.ReceiptMistakeReason
import cloud.dcompany.erp.core.net.ReceiptMistakeRequest
import cloud.dcompany.erp.ui.screens.finance.FileTenderJournalStorage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptMistakeReportsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CacheScope("actor", "company", "branch", "terminal")
    private val server = "https://erp.example.test/"
    private val key = receiptMistakeJournalKey(server, scope, "order")

    private fun openJournal(directory: Path) = ReceiptMistakeJournal(FileTenderJournalStorage(
        directory = { directory }, syncDirectory = {},
    ))

    @Test fun `a reopened receipt retries the original body and key until confirmed`() = runBlocking<Unit> {
        val original = ReceiptMistakeRequest(ReceiptMistakeReason.NoFundsReceived, "original note \n")
        val edited = original.copy(note = "different note")
        val directory = folder.root.toPath().resolve("receipts")
        val first = requireNotNull(openJournal(directory).prepare(key, original))
        assertTrue(first.newlySaved)
        val reopened = openJournal(directory)
        assertEquals(first.attempt, reopened.read(key)?.attempt)
        val retry = requireNotNull(reopened.prepare(key, edited))
        assertFalse(retry.newlySaved)
        assertEquals(first.attempt, retry.attempt)
        val otherActor = receiptMistakeJournalKey(server, scope.copy(userId = "other"), "order")
        val otherServer = receiptMistakeJournalKey("https://different.example.test/", scope, "order")
        assertNull(reopened.read(otherActor))
        assertNull(reopened.read(otherServer))
        assertEquals(key, receiptMistakeJournalKey(server, scope.copy(terminalId = "new-terminal"), "order"))
        reopened.finish(key, retry.attempt, acknowledgedKey = retry.attempt.key)
        val afterAcknowledgement = openJournal(directory)
        assertEquals(ReceiptMistakeRecord(acknowledgedKey = first.attempt.key), afterAcknowledgement.read(key))
        val saved = directory.resolve(key.replace(':', '_') + ".json").toFile().readText()
        assertFalse(saved.contains("original note"))
        assertNull(afterAcknowledgement.prepare(key, edited))
        assertNotEquals(first.attempt.key, afterAcknowledgement.prepare(key, edited, first.attempt.key)?.attempt?.key)
        assertFalse(original.toString().contains("original note"))
    }

    @Test fun `interrupted preparation is promoted durably with the original key`() = runBlocking<Unit> {
        val directory = folder.root.toPath().resolve("receipts")
        val interrupted = ReceiptMistakeJournal(FileTenderJournalStorage(
            directory = { directory }, syncDirectory = {},
            moveAtomically = { _, _ -> throw IOException("simulated interrupted promotion") },
        ))
        val draft = ReceiptMistakeRequest(ReceiptMistakeReason.WrongItem)
        val failure = runCatching { interrupted.prepare(key, draft) }.exceptionOrNull()
        assertTrue(failure is IOException)
        val recovered = openJournal(directory)
        val original = requireNotNull(recovered.read(key)?.attempt)
        assertEquals(original, recovered.prepare(key, null)?.attempt)
        assertEquals(original, openJournal(directory).read(key)?.attempt)
        recovered.finish(key, original, acknowledgedKey = null)
        assertNull(openJournal(directory).read(key))
    }

    @Test fun `a partial preparation can be discarded when no dispatchable record exists`() = runBlocking<Unit> {
        val directory = folder.newFolder("receipts").toPath()
        val preparing = directory.resolve(key.replace(':', '_') + ".json.preparing")
        preparing.toFile().writeText("{\"attempt\":")
        val journal = openJournal(directory)
        assertNull(journal.read(key))
        assertFalse(Files.exists(preparing))
        assertTrue(requireNotNull(journal.prepare(key, ReceiptMistakeRequest(ReceiptMistakeReason.Other))).newlySaved)
    }

    @Test fun `an interrupted acknowledgement still retries the canonical attempt`() = runBlocking<Unit> {
        val directory = folder.root.toPath().resolve("receipts")
        val journal = openJournal(directory)
        val first = requireNotNull(journal.prepare(key, ReceiptMistakeRequest(ReceiptMistakeReason.WrongItem))).attempt
        val interrupted = ReceiptMistakeJournal(FileTenderJournalStorage(
            directory = { directory }, syncDirectory = {},
            moveAtomically = { _, _ -> throw IOException("simulated interrupted acknowledgement") },
        ))
        assertTrue(runCatching { interrupted.finish(key, first, first.key) }.exceptionOrNull() is IOException)
        val reopened = openJournal(directory)
        assertEquals(first, reopened.prepare(key, null)?.attempt)
        reopened.finish(key, first, first.key)
        assertEquals(first.key, openJournal(directory).read(key)?.acknowledgedKey)
    }

    @Test fun `only one view model can send or clear a receipt attempt at a time`() = runBlocking<Unit> {
        val directory = folder.root.toPath().resolve("receipts")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val running = launch {
            assertTrue(openJournal(directory).dispatch(key) {
                entered.complete(Unit)
                finish.await()
            })
        }
        entered.await()
        try {
            assertFalse(openJournal(directory).dispatch(key) { error("Duplicate dispatch") })
        } finally {
            finish.complete(Unit)
        }
        running.join()
        assertTrue(openJournal(directory).dispatch(key) {})
    }

    @Test fun `only the current matching POS actor can report and protected permission is required to review`() {
        val lease = CacheScopeLease(CacheScope("actor", "company", "branch", "terminal"), 1L)
        val staff = MeResponse("actor", "test@example.test", "Cashier", companyId = "company", branchId = "branch",
            effectivePermissions = listOf("pos.read", "pos.write"))
        val reportOnly = requireNotNull(receiptMistakeAccess(staff, lease))
        assertTrue(reportOnly.report)
        assertFalse(reportOnly.review)
        assertEquals(reportOnly, receiptMistakeAccess(staff.copy(name = "Updated name"), lease))
        assertNull(receiptMistakeAccess(staff.copy(userId = "other-actor"), lease))
        assertNull(receiptMistakeAccess(staff.copy(branchId = "other-branch"), lease))

        val owner = staff.copy(effectivePermissions = listOf("pos.read", "pos.write", "finance.receipt.correct"))
        assertFalse(requireNotNull(receiptMistakeAccess(owner, lease)).review)
        assertTrue(requireNotNull(receiptMistakeAccess(owner.copy(auditAccess = true), lease)).review)
    }
}

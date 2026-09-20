package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpenseReceiptRecoveryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `authoritative hash match marks the local receipt accepted`() {
        val decision = reconcileRejectedExpenseReceipt(
            expenseServerId = "expense-1",
            contentSha256 = HASH_A,
            serverReceipts = listOf(receipt("receipt-1", "expense-1", HASH_A)),
        )

        assertEquals(ExpenseReceiptReconciliation.AlreadyAccepted("receipt-1"), decision)
    }

    @Test
    fun `authoritative complete list without the hash proves absence`() {
        val decision = reconcileRejectedExpenseReceipt(
            expenseServerId = "expense-1",
            contentSha256 = HASH_A,
            serverReceipts = listOf(receipt("receipt-2", "expense-1", HASH_B)),
        )

        assertEquals(ExpenseReceiptReconciliation.ConfirmedAbsent, decision)
        assertEquals(
            ExpenseReceiptReconciliation.ConfirmedAbsent,
            reconcileRejectedExpenseReceipt("expense-1", HASH_A, emptyList()),
        )
    }

    @Test
    fun `old or cross-expense server payload cannot authorize deletion`() {
        val missingHash = reconcileRejectedExpenseReceipt(
            "expense-1",
            HASH_A,
            listOf(receipt("receipt-2", "expense-1", null)),
        )
        val wrongExpense = reconcileRejectedExpenseReceipt(
            "expense-1",
            HASH_A,
            listOf(receipt("receipt-2", "expense-2", HASH_B)),
        )

        assertTrue(missingHash is ExpenseReceiptReconciliation.Unverifiable)
        assertTrue(wrongExpense is ExpenseReceiptReconciliation.Unverifiable)
    }

    @Test
    fun `discard authority is fenced to the exact signed-in outbox owner`() {
        val owner = OutboxOwnerIdentity("user-a", "company-a", "branch-a")
        val scope = CacheScope("user-a", "company-a", "branch-a", "terminal-a")

        assertTrue(expenseReceiptDiscardScopeMatches(owner, owner, owner, scope, true))
        assertFalse(
            expenseReceiptDiscardScopeMatches(
                owner,
                OutboxOwnerIdentity("user-b", "company-a", "branch-a"),
                owner,
                scope,
                true,
            ),
        )
        assertFalse(expenseReceiptDiscardScopeMatches(owner, owner, owner, scope, false))
        assertFalse(
            expenseReceiptDiscardScopeMatches(
                owner,
                owner,
                owner,
                scope.copy(branchId = "branch-b"),
                true,
            ),
        )
    }

    @Test
    fun `accepted parent must match every immutable field and actor`() {
        val local = localExpense()
        val server = serverExpense()

        assertTrue(authoritativeExpenseMatchesLocal(local, server, "user-a"))
        assertFalse(
            authoritativeExpenseMatchesLocal(
                local,
                server.copy(amountMinor = server.amountMinor + 1),
                "user-a",
            ),
        )
        assertFalse(
            authoritativeExpenseMatchesLocal(
                local,
                server.copy(note = "Different evidence"),
                "user-a",
            ),
        )
        assertFalse(authoritativeExpenseMatchesLocal(local, server, "user-b"))
    }

    @Test
    fun `accepted parent compares payment instants rather than timestamp spelling`() {
        val local = localExpense(paidAt = "2026-09-19T17:30:00+05:30")
        val sameInstant = serverExpense(paidAt = "2026-09-19T12:00:00Z")

        assertTrue(authoritativeExpenseMatchesLocal(local, sameInstant, "user-a"))
        assertFalse(
            authoritativeExpenseMatchesLocal(
                local,
                sameInstant.copy(paidAt = "2026-09-19T12:00:01Z"),
                "user-a",
            ),
        )
    }

    @Test
    fun `camera pruning keeps active fresh and unrelated files`() {
        val directory = temporary.newFolder(EXPENSE_RECEIPT_CAMERA_DIRECTORY)
        val now = 2 * EXPENSE_RECEIPT_CAMERA_STALE_MILLIS
        val stale = cameraFile(directory, "stale", now - EXPENSE_RECEIPT_CAMERA_STALE_MILLIS - 1)
        val active = cameraFile(directory, "active", now - EXPENSE_RECEIPT_CAMERA_STALE_MILLIS - 1)
        val fresh = cameraFile(directory, "fresh", now - 1)
        val unrelated = File(directory, "other-app.jpg").apply {
            writeText("keep")
            setLastModified(now - EXPENSE_RECEIPT_CAMERA_STALE_MILLIS - 1)
        }

        val result = pruneExpenseReceiptCameraFiles(
            directory = directory,
            activeFilename = active.name,
            nowMillis = now,
        )

        assertFalse(stale.exists())
        assertTrue(active.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
        assertEquals(3, result.inspected)
        assertEquals(1, result.deleted)
    }

    @Test
    fun `camera pruning work is capped per entry`() {
        val directory = temporary.newFolder("bounded-camera")
        val now = 2 * EXPENSE_RECEIPT_CAMERA_STALE_MILLIS
        val oldest = cameraFile(directory, "oldest", 1)
        val later = cameraFile(directory, "later", 2)

        val result = pruneExpenseReceiptCameraFiles(
            directory = directory,
            activeFilename = null,
            nowMillis = now,
            maximumFiles = 1,
        )

        assertFalse(oldest.exists())
        assertTrue(later.exists())
        assertEquals(1, result.inspected)
        assertEquals(1, result.deleted)
    }

    private fun cameraFile(directory: File, marker: String, modifiedAt: Long): File =
        File(directory, "$EXPENSE_RECEIPT_CAMERA_PREFIX$marker$EXPENSE_RECEIPT_CAMERA_SUFFIX").apply {
            writeText(marker)
            check(setLastModified(modifiedAt))
        }

    private fun receipt(id: String, expenseId: String, sha256: String?) = ExpenseReceipt(
        id = id,
        expenseId = expenseId,
        originalFilename = "bill.pdf",
        contentType = "application/pdf",
        sizeBytes = 100,
        sha256 = sha256,
        source = "file",
        status = "pending",
        createdAt = "2026-09-19T12:00:00Z",
    )

    private fun localExpense(
        paidAt: String = "2026-09-19T12:00:00Z",
    ) = LocalExpenseEntity(
        localId = "11111111-1111-4111-8111-111111111111",
        branchId = "branch-a",
        categoryId = "category-a",
        supplierId = "supplier-a",
        amountMinor = 12_345,
        paidVia = "cash",
        paidAt = paidAt,
        vendorName = "Vendor",
        invoiceNo = "INV-1",
        note = "Immutable evidence",
        createdAtMillis = 1,
        shiftId = "shift-a",
        syncState = "rejected",
        lastError = "Permanent validation rejection",
    )

    private fun serverExpense(
        paidAt: String = "2026-09-19T12:00:00Z",
    ) = Expense(
        id = "expense-a",
        branchId = "branch-a",
        shiftId = "shift-a",
        createdBy = "user-a",
        categoryId = "category-a",
        supplierId = "supplier-a",
        amountMinor = 12_345,
        paidVia = "cash",
        paidAt = paidAt,
        vendorName = "Vendor",
        invoiceNo = "INV-1",
        note = "Immutable evidence",
    )

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

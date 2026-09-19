package cloud.dcompany.erp.ui.screens.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseReceiptPolicyTest {

    @Test
    fun `jpeg needs both start and end markers`() {
        assertEquals(
            "image/jpeg",
            detectExpenseReceiptType(
                byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01,
                    0xFF.toByte(), 0xD9.toByte(), '\n'.code.toByte(),
                ),
            ),
        )
        assertNull(
            detectExpenseReceiptType(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01),
            ),
        )
    }

    @Test
    fun `pdf needs eof marker near the end`() {
        assertEquals(
            "application/pdf",
            detectExpenseReceiptType("%PDF-1.7\n1 0 obj\n%%EOF\n".encodeToByteArray()),
        )
        assertNull(detectExpenseReceiptType("%PDF-1.7\n1 0 obj\n".encodeToByteArray()))
    }

    @Test
    fun `heic and heif containers are refused before entering the outbox`() {
        val bytes = ByteArray(24)
        bytes[3] = 24
        "ftyp".encodeToByteArray().copyInto(bytes, destinationOffset = 4)
        "isom".encodeToByteArray().copyInto(bytes, destinationOffset = 8)
        "heic".encodeToByteArray().copyInto(bytes, destinationOffset = 16)

        assertNull(detectExpenseReceiptType(bytes))
        assertTrue(EXPENSE_RECEIPT_PICKER_TYPES.none { it in setOf("image/heic", "image/heif") })
    }

    @Test
    fun `filename uses detected extension and removes path components`() {
        assertEquals("bill.jpg", sanitizeExpenseReceiptFilename("../../bill.exe", ".jpg"))
        assertEquals("receipt.pdf", sanitizeExpenseReceiptFilename("  ", ".pdf"))
        assertTrue(sanitizeExpenseReceiptFilename("a".repeat(300), ".webp").length <= 200)
    }

    @Test
    fun `receipt replay identity and digest stay stable`() {
        assertEquals("expense-receipt:local-7", expenseReceiptIdempotencyKey("local-7"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            expenseReceiptSha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `camera launch failure discards its retained temporary target`() {
        val retained = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val result = launchExpenseReceiptCapture(
            create = { "content://expense/temp-photo" },
            retain = retained::add,
            launch = { error("No camera activity") },
            discard = discarded::add,
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("content://expense/temp-photo"), retained)
        assertEquals(retained, discarded)
    }

    @Test
    fun `accepted camera launch retains target without deleting it early`() {
        val retained = mutableListOf<String>()
        val launched = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val result = launchExpenseReceiptCapture(
            create = { "content://expense/temp-photo" },
            retain = retained::add,
            launch = launched::add,
            discard = discarded::add,
        )

        assertEquals("content://expense/temp-photo", result.getOrThrow())
        assertEquals(retained, launched)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun `large receipt is split below cursor limit and reassembled with integrity proof`() {
        val content = ByteArray(EXPENSE_RECEIPT_CHUNK_BYTES * 2 + 17) { (it % 251).toByte() }
        val chunks = expenseReceiptContentChunks(content)

        assertEquals(listOf(EXPENSE_RECEIPT_CHUNK_BYTES, EXPENSE_RECEIPT_CHUNK_BYTES, 17), chunks.map { it.size })
        assertTrue(
            content.contentEquals(
                reassembleExpenseReceiptContent(chunks, content.size, expenseReceiptSha256(content)),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            reassembleExpenseReceiptContent(chunks, content.size, "wrong-digest")
        }
    }

    @Test
    fun `receipt status copy distinguishes missing pending and rejected evidence`() {
        assertEquals("No receipt · Needs review", expenseReceiptSummary(0, "pending"))
        assertEquals("1 receipt · Verified", expenseReceiptSummary(1, "verified"))
        assertEquals("2 receipts · Rejected", expenseReceiptSummary(2, "rejected"))
    }
}

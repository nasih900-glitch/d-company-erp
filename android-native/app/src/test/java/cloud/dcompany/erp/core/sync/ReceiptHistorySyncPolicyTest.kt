package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptHistorySyncPolicyTest {
    @Test
    fun `old backend 404 is a non-blocking compatibility notice`() {
        val message = receiptHistoryCompatibilityMessage(
            ApiException("Not found", status = 404),
        )

        assertTrue(message!!.contains("server update"))
        assertTrue(message.contains("tablet are still available"))
    }

    @Test
    fun `network and server failures remain truthful refresh failures`() {
        assertNull(receiptHistoryCompatibilityMessage(ApiException("offline")))
        assertNull(receiptHistoryCompatibilityMessage(ApiException("server failed", status = 500)))
        assertNull(receiptHistoryCompatibilityMessage(ApiException("forbidden", status = 403)))
    }

    @Test
    fun `realtime refresh walks every page in the persisted loaded window`() = runBlocking {
        val requestedCursors = mutableListOf<String?>()

        val window = fetchReceiptHistoryWindow(targetCount = 100) { cursor ->
            requestedCursors += cursor
            when (cursor) {
                null -> ReceiptHistoryPageWindow(
                    items = (1..50).toList(),
                    nextCursor = "page-2",
                    hasMore = true,
                )
                "page-2" -> ReceiptHistoryPageWindow(
                    items = (51..100).toList(),
                    nextCursor = "page-3",
                    hasMore = true,
                )
                else -> error("Unexpected cursor $cursor")
            }
        }

        assertEquals(listOf(null, "page-2"), requestedCursors)
        assertEquals((1..100).toList(), window.items)
        assertEquals("page-3", window.nextCursor)
        assertTrue(window.hasMore)
    }

    @Test
    fun `initial receipt refresh remains one fifty-row page`() = runBlocking {
        var calls = 0
        val window = fetchReceiptHistoryWindow(targetCount = 0) {
            calls += 1
            ReceiptHistoryPageWindow(
                items = (1..50).toList(),
                nextCursor = "page-2",
                hasMore = true,
            )
        }

        assertEquals(1, calls)
        assertEquals(50, window.items.size)
    }
}

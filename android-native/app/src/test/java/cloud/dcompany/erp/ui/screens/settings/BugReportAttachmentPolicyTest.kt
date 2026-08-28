package cloud.dcompany.erp.ui.screens.settings

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportAttachmentPolicyTest {

    @Test
    fun `source reader accepts an image larger than final upload limit for compression`() {
        assertTrue(BUG_REPORT_ATTACHMENT_SOURCE_MAX_BYTES > BUG_REPORT_ATTACHMENT_MAX_BYTES)

        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = readBugReportSource(ByteArrayInputStream(content), maximumBytes = content.size)

        assertTrue(result is BugReportSourceRead.Ready)
        assertArrayEquals(content, (result as BugReportSourceRead.Ready).content)
    }

    @Test
    fun `source reader stops at its memory ceiling`() {
        val result = readBugReportSource(
            ByteArrayInputStream(ByteArray(9) { it.toByte() }),
            maximumBytes = 8,
        )

        assertEquals(BugReportSourceRead.TooLarge, result)
    }
}

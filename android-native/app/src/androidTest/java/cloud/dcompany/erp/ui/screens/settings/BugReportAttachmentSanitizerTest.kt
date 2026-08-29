package cloud.dcompany.erp.ui.screens.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BugReportAttachmentSanitizerTest {

    @Test
    fun redmiSizedImageOverServerLimitIsDownscaledAndAccepted() {
        val width = 2_560
        val height = 1_600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        var seed = 0x12345678
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 1_664_525 + 1_013_904_223
                row[x] = 0xff000000.toInt() or (seed and 0x00ffffff)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val sourceOutput = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, sourceOutput))
        bitmap.recycle()
        val source = sourceOutput.toByteArray()

        assertTrue("test source must exercise compression", source.size > BUG_REPORT_ATTACHMENT_MAX_BYTES)
        assertTrue("test source must fit the bounded picker", source.size <= BUG_REPORT_ATTACHMENT_SOURCE_MAX_BYTES)

        val result = sanitizeBugReportImage(source)
        assertTrue(result is BugReportAttachmentLoadResult.Ready)
        val attachment = (result as BugReportAttachmentLoadResult.Ready).attachment
        assertEquals("image/jpeg", attachment.contentType)
        assertTrue(attachment.byteSize <= BUG_REPORT_ATTACHMENT_MAX_BYTES)
        val decoded = BitmapFactory.decodeByteArray(attachment.content, 0, attachment.content.size)
        assertEquals(1_600, maxOf(decoded.width, decoded.height))
        decoded.recycle()
    }
}

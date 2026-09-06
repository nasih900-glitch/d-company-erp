package cloud.dcompany.erp.core.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistanceNotificationCopyTest {
    @Test
    fun `online notification discloses the exact Help-only boundary and Stop`() {
        val copy = remoteAssistanceNotificationCopy(sharingPaused = false)

        assertTrue(copy.title.contains("Help"))
        assertTrue(copy.detail.contains("view Help only"))
        assertTrue(copy.detail.contains("other ERP screens are hidden"))
        assertTrue(copy.detail.contains("Stop is always available"))
        assertFalse(copy.detail.contains("app only"))
    }

    @Test
    fun `offline notification says sharing is paused while privacy and Stop remain`() {
        val copy = remoteAssistanceNotificationCopy(sharingPaused = true)

        assertTrue(copy.title.contains("sharing paused"))
        assertTrue(copy.detail.contains("sharing is paused"))
        assertTrue(copy.detail.contains("Owner can view Help only"))
        assertTrue(copy.detail.contains("other ERP screens stay hidden"))
        assertTrue(copy.detail.contains("Stop remains available"))
    }
}

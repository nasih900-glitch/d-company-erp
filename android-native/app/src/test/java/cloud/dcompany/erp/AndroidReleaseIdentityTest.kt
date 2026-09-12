package cloud.dcompany.erp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Guards the installable Android artifact identity against accidental reuse. */
class AndroidReleaseIdentityTest {
    @Test
    fun `code 29 artifact keeps production and isolated audit identities distinct`() {
        assertEquals(29, BuildConfig.VERSION_CODE)
        if (BuildConfig.BUILD_TYPE == "physicalAudit") {
            assertEquals("cloud.dcompany.erp.physicalaudit", BuildConfig.APPLICATION_ID)
            assertEquals("3.1.20-physical-audit", BuildConfig.VERSION_NAME)
            assertEquals("managed", BuildConfig.DISTRIBUTION_CHANNEL)
            assertFalse(BuildConfig.DIRECT_UPDATES_ENABLED)
        } else {
            assertEquals("cloud.dcompany.erp", BuildConfig.APPLICATION_ID)
            assertEquals("3.1.20", BuildConfig.VERSION_NAME)
        }
    }
}

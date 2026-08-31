package cloud.dcompany.erp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the installable Android artifact identity against accidental reuse. */
class AndroidReleaseIdentityTest {
    @Test
    fun `code 20 artifact has the expected package and semantic version`() {
        assertEquals("cloud.dcompany.erp", BuildConfig.APPLICATION_ID)
        assertEquals(20, BuildConfig.VERSION_CODE)
        assertEquals("3.1.9", BuildConfig.VERSION_NAME)
    }
}

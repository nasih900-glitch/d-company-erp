package cloud.dcompany.erp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the installable Android artifact identity against accidental reuse. */
class AndroidReleaseIdentityTest {
    @Test
    fun `code 23 artifact has the expected package and semantic version`() {
        assertEquals("cloud.dcompany.erp", BuildConfig.APPLICATION_ID)
        assertEquals(23, BuildConfig.VERSION_CODE)
        assertEquals("3.1.12", BuildConfig.VERSION_NAME)
    }
}

package cloud.dcompany.erp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the installable Android artifact identity against accidental reuse. */
class AndroidReleaseIdentityTest {
    @Test
    fun `code 19 artifact has the expected package and semantic version`() {
        assertEquals("cloud.dcompany.erp", BuildConfig.APPLICATION_ID)
        assertEquals(19, BuildConfig.VERSION_CODE)
        assertEquals("3.1.8", BuildConfig.VERSION_NAME)
    }
}

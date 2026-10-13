package cloud.dcompany.erp.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePreferenceTest {
    @Test
    fun `appearance cycles through system day and night`() {
        assertEquals(AppearancePreference.Day, AppearancePreference.System.next())
        assertEquals(AppearancePreference.Night, AppearancePreference.Day.next())
        assertEquals(AppearancePreference.System, AppearancePreference.Night.next())
    }
}

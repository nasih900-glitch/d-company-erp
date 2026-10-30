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

    @Test
    fun `fresh installs default to night while every explicit choice round trips`() {
        assertEquals(AppearancePreference.Night, appearancePreferenceFromPersistedValue(null))
        assertEquals(AppearancePreference.Night, appearancePreferenceFromPersistedValue(""))
        assertEquals(AppearancePreference.Night, appearancePreferenceFromPersistedValue("legacy-unknown"))

        AppearancePreference.entries.forEach { preference ->
            assertEquals(
                preference,
                appearancePreferenceFromPersistedValue(preference.persistedValue()),
            )
        }
        assertEquals("system", AppearancePreference.System.persistedValue())
        assertEquals("day", AppearancePreference.Day.persistedValue())
        assertEquals("night", AppearancePreference.Night.persistedValue())
    }
}

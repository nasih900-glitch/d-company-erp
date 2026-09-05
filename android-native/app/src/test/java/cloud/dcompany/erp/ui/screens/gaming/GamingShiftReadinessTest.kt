package cloud.dcompany.erp.ui.screens.gaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GamingShiftReadinessTest {
    @Test
    fun `saved offline shift is never described as ready for gaming`() {
        assertEquals(GamingShiftSummary("Saved", "Reconnect to confirm shift"),
            gamingShiftSummary("local-shift", confirmed = false, online = false))
        assertEquals(GamingShiftSummary("Saved", "Waiting for shift sync"),
            gamingShiftSummary("local-shift", confirmed = false, online = true))
        assertNotNull(gamingStartShiftBlockMessage("local-shift", false))
    }

    @Test
    fun `confirmed shift remains operational offline but missing shift does not`() {
        assertEquals(GamingShiftSummary("Open", "Session starts enabled"),
            gamingShiftSummary("server-shift", confirmed = true, online = false))
        assertNull(gamingStartShiftBlockMessage("server-shift", true))
        assertEquals(GamingShiftSummary("Required", "Open shift to start"),
            gamingShiftSummary(null, confirmed = false, online = true))
    }
}

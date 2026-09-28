package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamingDurationDialHitTest {
    private val dial = IntSize(1000, 1000)

    @Test
    fun twoPublishedPackagesSelectAtOppositeEndsOfTheRing() {
        assertEquals(0, closestDurationDialStop(Offset(154f, 300f), dial, 2))
        assertEquals(1, closestDurationDialStop(Offset(846f, 300f), dial, 2))
    }

    @Test
    fun tapsAwayFromTheRingCannotChangeTheSelectedPackage() {
        assertNull(closestDurationDialStop(Offset(500f, 500f), dial, 2))
        assertNull(closestDurationDialStop(Offset(500f, 900f), dial, 2))
        assertNull(closestDurationDialStop(Offset(-10f, 300f), dial, 2))
    }

    @Test
    fun additionalPublishedDurationsRemainReachable() {
        assertEquals(1, closestDurationDialStop(Offset(500f, 100f), dial, 3))
    }
}

package cloud.dcompany.erp.ui.screens.gaming

import org.junit.Assert.*
import org.junit.Test

class GamingDialogBodyFitTest {
    @Test fun unknownMeasurementsDoNotClaimFit() {
        assertNull(gamingPinnedBodyCapacityPx(600, null, 80, 96, 64, 880))
        assertNull(gamingPinnedBodyCapacityPx(0, 80, 80, 96, 64, 880))
        assertNull(gamingPinnedBodyCapacityPx(Int.MAX_VALUE, 80, 80, 96, 64, 880))
        assertEquals(GamingDialogBodyFit.Unknown, gamingDialogBodyFit(null, 220))
        assertEquals(GamingDialogBodyFit.Unknown, gamingDialogBodyFit(300, 0))
    }

    @Test fun actualChromeAndPaddingLeaveFinitePinnedCapacity() {
        assertEquals(220, gamingPinnedBodyCapacityPx(600, 120, 80, 96, 84, 880))
        assertEquals(GamingDialogBodyFit.Fits, gamingDialogBodyFit(220, 220))
        assertEquals(GamingDialogBodyFit.ScrollChrome, gamingDialogBodyFit(219, 220))
    }

    @Test fun existingFormHeightCapStillLimitsPinnedBody() {
        assertEquals(180, gamingPinnedBodyCapacityPx(900, 120, 80, 96, 64, 180))
        assertEquals(GamingDialogBodyFit.ScrollChrome, gamingDialogBodyFit(180, 220))
    }

    @Test fun expandedViewportDoesNotChangePinnedCapacityOrOscillateMode() {
        val pinned = gamingPinnedBodyCapacityPx(480, 120, 80, 96, 64, 880)
        assertEquals(120, pinned)
        assertTrue(480 - 96 >= 220) // Full viewport can fit the measured owner.
        repeat(3) { assertEquals(GamingDialogBodyFit.ScrollChrome, gamingDialogBodyFit(pinned, 220)) }
    }

    @Test fun viewportAndFontSizedOwnerChangesRecomputeFit() {
        assertEquals(GamingDialogBodyFit.Fits, gamingDialogBodyFit(220, 200))
        assertEquals(GamingDialogBodyFit.ScrollChrome, gamingDialogBodyFit(220, 240))
        assertEquals(GamingDialogBodyFit.Fits, gamingDialogBodyFit(300, 240))
    }

    @Test fun chromeLargerThanWindowYieldsZeroNotNegativeBody() {
        assertEquals(0, gamingPinnedBodyCapacityPx(100, 120, 80, 96, 64, 880))
        assertEquals(GamingDialogBodyFit.ScrollChrome, gamingDialogBodyFit(0, 220))
    }

    @Test fun malformedOrOverflowingMeasurementsCannotProduceFalseFit() {
        assertNull(gamingPinnedBodyCapacityPx(100, -1, 0, 0, 0, 100))
        assertEquals(0, gamingPinnedBodyCapacityPx(100, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 100))
        assertEquals(GamingDialogBodyFit.Unknown, gamingDialogBodyFit(-1, 220))
    }
}

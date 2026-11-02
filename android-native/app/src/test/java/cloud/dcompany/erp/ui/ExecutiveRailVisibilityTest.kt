package cloud.dcompany.erp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveRailVisibilityTest {
    @Test
    fun directionLockLeavesVerticalAndDiagonalGesturesToExistingOwners() {
        val slop = 8f
        assertEquals(
            ExecutiveRailVisibilityGestureDecision.Pending,
            executiveRailVisibilityGestureDecision(
                deltaX = -7f,
                deltaY = 7f,
                touchSlopPx = slop,
                direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
            ),
        )
        listOf(-10f to 40f, 18f to 1f).forEach { (x, y) ->
            assertEquals(
                ExecutiveRailVisibilityGestureDecision.Reject,
                executiveRailVisibilityGestureDecision(
                    deltaX = x,
                    deltaY = y,
                    touchSlopPx = slop,
                    direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
                ),
            )
        }
        assertEquals(
            ExecutiveRailVisibilityGestureDecision.Pending,
            executiveRailVisibilityGestureDecision(
                deltaX = -20f,
                deltaY = 20f,
                touchSlopPx = slop,
                direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
            ),
        )
    }

    @Test
    fun hideAndRevealRequireOppositeDeliberateHorizontalDirections() {
        assertEquals(
            ExecutiveRailVisibilityGestureDecision.Claim,
            executiveRailVisibilityGestureDecision(
                deltaX = -24f,
                deltaY = 4f,
                touchSlopPx = 8f,
                direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
            ),
        )
        assertEquals(
            ExecutiveRailVisibilityGestureDecision.Claim,
            executiveRailVisibilityGestureDecision(
                deltaX = 24f,
                deltaY = 4f,
                touchSlopPx = 8f,
                direction = ExecutiveRailVisibilityGestureDirection.ShowToRight,
            ),
        )
        assertFalse(
            executiveRailVisibilityGestureCommits(
                deltaX = -55f,
                deltaY = 0f,
                commitDistancePx = 56f,
                direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
            ),
        )
        assertTrue(
            executiveRailVisibilityGestureCommits(
                deltaX = -56f,
                deltaY = 8f,
                commitDistancePx = 56f,
                direction = ExecutiveRailVisibilityGestureDirection.HideToLeft,
            ),
        )
        assertTrue(
            executiveRailVisibilityGestureCommits(
                deltaX = 56f,
                deltaY = 8f,
                commitDistancePx = 56f,
                direction = ExecutiveRailVisibilityGestureDirection.ShowToRight,
            ),
        )
        assertFalse(
            executiveRailVisibilityGestureCommits(
                deltaX = 56f,
                deltaY = 50f,
                commitDistancePx = 56f,
                direction = ExecutiveRailVisibilityGestureDirection.ShowToRight,
            ),
        )
    }
}

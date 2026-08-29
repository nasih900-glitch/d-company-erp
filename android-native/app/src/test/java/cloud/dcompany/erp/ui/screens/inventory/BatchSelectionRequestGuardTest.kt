package cloud.dcompany.erp.ui.screens.inventory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSelectionRequestGuardTest {

    @Test
    fun `rapid second selection makes first response stale`() {
        val guard = BatchSelectionRequestGuard()
        val first = guard.begin("ingredient-a", "branch-a")
        val second = guard.begin("ingredient-b", "branch-a")

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `retry of same ingredient still invalidates previous request`() {
        val guard = BatchSelectionRequestGuard()
        val first = guard.begin("ingredient-a", "branch-a")
        val retry = guard.begin("ingredient-a", "branch-a")

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(retry))
    }

    @Test
    fun `clearing selection prevents late response from changing feedback`() {
        val guard = BatchSelectionRequestGuard()
        val selected = guard.begin("ingredient-a", "branch-a")
        val cleared = guard.begin(null, "branch-a")

        assertFalse(guard.isCurrent(selected))
        assertTrue(guard.isCurrent(cleared))
    }

    @Test
    fun `same ingredient in another branch invalidates first response`() {
        val guard = BatchSelectionRequestGuard()
        val first = guard.begin("ingredient-a", "branch-a")
        val second = guard.begin("ingredient-a", "branch-b")

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }
}

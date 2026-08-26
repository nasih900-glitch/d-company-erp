package cloud.dcompany.erp.ui.screens.inventory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSelectionRequestGuardTest {

    @Test
    fun `rapid second selection makes first response stale`() {
        val guard = BatchSelectionRequestGuard()
        val first = guard.begin("ingredient-a")
        val second = guard.begin("ingredient-b")

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `retry of same ingredient still invalidates previous request`() {
        val guard = BatchSelectionRequestGuard()
        val first = guard.begin("ingredient-a")
        val retry = guard.begin("ingredient-a")

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(retry))
    }

    @Test
    fun `clearing selection prevents late response from changing feedback`() {
        val guard = BatchSelectionRequestGuard()
        val selected = guard.begin("ingredient-a")
        val cleared = guard.begin(null)

        assertFalse(guard.isCurrent(selected))
        assertTrue(guard.isCurrent(cleared))
    }
}

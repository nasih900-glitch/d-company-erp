package cloud.dcompany.erp.ui.screens.menu

import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MenuGamingCentrePresentationTest {

    @Test
    fun `gaming centre only offers drink and snack product types for new items`() {
        val presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()
        val options = menuItemTypeOptions(presentation)

        assertEquals(listOf("food", "drink"), options.map { it.first })
        assertFalse(options.joinToString().contains("event", ignoreCase = true))
        assertFalse(options.joinToString().contains("hookah", ignoreCase = true))
    }

    @Test
    fun `historic dormant item types remain visible under neutral labels`() {
        val presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()

        assertEquals("Snack / product", menuItemTypeLabel("food", presentation))
        assertEquals("Drink", menuItemTypeLabel("drink", presentation))
        assertEquals("Legacy product", menuItemTypeLabel("dessert", presentation))
        assertEquals("Legacy service", menuItemTypeLabel("event", presentation))
        assertEquals("Legacy service", menuItemTypeLabel("hookah", presentation))
    }
}

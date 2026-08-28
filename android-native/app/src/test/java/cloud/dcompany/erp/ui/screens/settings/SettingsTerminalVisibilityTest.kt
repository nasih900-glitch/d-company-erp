package cloud.dcompany.erp.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTerminalVisibilityTest {

    @Test
    fun `one active workspace hides terminal management even for owner`() {
        assertFalse(
            showAdvancedTerminalSettings(
                canManageSystem = true,
                activeTerminalCount = 1,
                pendingTerminalCount = 0,
            ),
        )
    }

    @Test
    fun `multiple or newly pending workspaces expose owner management`() {
        assertTrue(showAdvancedTerminalSettings(true, activeTerminalCount = 2, pendingTerminalCount = 0))
        assertTrue(showAdvancedTerminalSettings(true, activeTerminalCount = 1, pendingTerminalCount = 1))
        assertFalse(showAdvancedTerminalSettings(false, activeTerminalCount = 2, pendingTerminalCount = 0))
    }
}

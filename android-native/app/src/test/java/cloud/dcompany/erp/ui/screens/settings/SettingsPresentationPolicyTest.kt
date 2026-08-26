package cloud.dcompany.erp.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationPolicyTest {

    @Test
    fun `terminal device id matches the backend length contract`() {
        assertNull(terminalDeviceIdError("asset-${"x".repeat(94)}"))
        assertEquals(
            "Tablet device ID must be 100 characters or fewer.",
            terminalDeviceIdError("x".repeat(101)),
        )
    }

    @Test
    fun `missing required settings never render as a successful empty state`() {
        assertEquals(
            SettingsReadPresentation.INITIAL_LOADING,
            settingsReadPresentation(hasData = false, loading = false, error = null),
        )
        assertEquals(
            SettingsReadPresentation.BLOCKING_ERROR,
            settingsReadPresentation(hasData = false, loading = false, error = "Offline"),
        )
    }

    @Test
    fun `successful empty lists are valid but failed cached lists are stale`() {
        assertEquals(
            SettingsReadPresentation.FRESH,
            settingsReadPresentation(
                hasData = false,
                loading = false,
                error = null,
                emptyIsValid = true,
            ),
        )
        assertEquals(
            SettingsReadPresentation.STALE,
            settingsReadPresentation(hasData = true, loading = false, error = "Timed out"),
        )
        assertEquals(
            SettingsReadPresentation.REFRESHING,
            settingsReadPresentation(hasData = true, loading = true, error = null),
        )
    }

    @Test
    fun `company branch and terminal confirmations name the consequence`() {
        val company = settingsConfirmation(DestructiveSettingsAction.DiscardCompanyEdits)
        val branch = settingsConfirmation(
            DestructiveSettingsAction.DiscardBranchForm("Main Cafe", isNew = false),
        )
        val terminal = settingsConfirmation(DestructiveSettingsAction.DeleteTerminal("Front Till"))

        assertTrue(company.body.contains("lost", ignoreCase = true))
        assertTrue(company.body.contains("payment", ignoreCase = true))
        assertTrue(branch.body.contains("Main Cafe"))
        assertTrue(branch.body.contains("lost", ignoreCase = true))
        assertTrue(terminal.title.contains("Front Till"))
        assertTrue(terminal.body.contains("shifts", ignoreCase = true))
        assertTrue(terminal.body.contains("history", ignoreCase = true))
    }

    @Test
    fun `branch cancel prompts only after the form actually changed`() {
        val original = BranchDto(id = "branch-a", name = "Main Cafe")
        val unchanged = SettingsUiState(
            branches = listOf(original),
            branchForm = original.toForm(),
        )
        val changed = unchanged.copy(branchForm = original.toForm().copy(name = "Renamed Cafe"))

        assertFalse(unchanged.branchFormDirty)
        assertTrue(changed.branchFormDirty)
        assertFalse(SettingsUiState(branchForm = BranchForm()).branchFormDirty)
        assertTrue(
            SettingsUiState(branchForm = BranchForm(name = "New Branch")).branchFormDirty,
        )
    }

    @Test
    fun `unexpected settings failures do not expose implementation details`() {
        val message = IllegalStateException("sqlite constraint internals")
            .settingsReadable("Could not save this change.")

        assertEquals("Could not save this change.", message)
        assertFalse(message.contains("sqlite", ignoreCase = true))
    }
}

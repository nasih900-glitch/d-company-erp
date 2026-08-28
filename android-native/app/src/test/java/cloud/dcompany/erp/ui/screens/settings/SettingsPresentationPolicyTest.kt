package cloud.dcompany.erp.ui.screens.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationPolicyTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    fun `invoice series is required normalized and sent on the wire`() {
        val form = BranchForm(name = "Main Cafe", invoiceSeriesCode = " m1 ")

        assertNull(form.validate())
        assertEquals("M1", form.toBody().invoiceSeriesCode)
        assertTrue(
            json.encodeToString(form.toBody()).contains("\"invoice_series_code\":\"M1\""),
        )
    }

    @Test
    fun `invoice series rejects blank wrong length and punctuation`() {
        for (series in listOf("", "M", "MAIN", "M-", "₹1")) {
            assertEquals(
                "Invoice series must be exactly two letters or digits, for example MN.",
                BranchForm(name = "Main Cafe", invoiceSeriesCode = series).validate(),
            )
        }
    }

    @Test
    fun `queued branch recovery never hides corrupt explicit series with display code`() {
        assertEquals("M1", resolveQueuedInvoiceSeries(null, " m1 "))
        assertNull(resolveQueuedInvoiceSeries(null, "Main"))
        assertNull(resolveQueuedInvoiceSeries("??", "M1"))
        assertEquals("A2", resolveQueuedInvoiceSeries(" a2 ", "M1"))
    }

    @Test
    fun `known branch invoice series conflict gives an actionable local error`() {
        val state = SettingsUiState(
            branches = listOf(
                BranchDto(
                    id = "branch-a",
                    name = "Main Cafe",
                    invoiceSeriesCode = "MN",
                ),
            ),
        )

        val conflict = state.invoiceSeriesConflict(
            BranchForm(name = "Second Cafe", invoiceSeriesCode = "mn"),
        )

        assertTrue(conflict.orEmpty().contains("Main Cafe"))
        assertTrue(conflict.orEmpty().contains("Choose another"))
    }

    @Test
    fun `unexpected settings failures do not expose implementation details`() {
        val message = IllegalStateException("sqlite constraint internals")
            .settingsReadable("Could not save this change.")

        assertEquals("Could not save this change.", message)
        assertFalse(message.contains("sqlite", ignoreCase = true))
    }
}

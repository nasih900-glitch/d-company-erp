package cloud.dcompany.erp.ui.screens.finance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceFormBusyContractTest {

    @Test
    fun `finance status changes cannot reflow the report viewport`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt")
        val dataLayout = source.between(
            "FinancePrimaryContentState.DATA ->",
            "when (val dialog = state.dialog)",
        )
        val statusRegion = source.between(
            "private fun FinanceStatusRegion",
            "private val FINANCE_STATUS_REGION_HEIGHT",
        )
        val header = source.between("private fun Header", "private fun FinanceStatusRegion")

        assertTrue("DATA layout must own one stable status region", "FinanceStatusRegion(" in dataLayout)
        assertTrue(
            "Offline, error, recovery and pending panels must stay inside the fixed status region",
            "height(FINANCE_STATUS_REGION_HEIGHT)" in statusRegion &&
                "FinanceOfflineBanner" in statusRegion &&
                "ErrorBanner" in statusRegion &&
                "PendingOnlineFinanceWriteBanner" in statusRegion &&
                "PendingFinanceChangesPanel" in statusRegion,
        )
        assertTrue(
            "Background refresh must use a permanently reserved progress rail",
            "Box(Modifier.fillMaxWidth().height(4.dp))" in header,
        )
    }

    @Test
    fun `every finance form locks every editable control while its write is busy`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt")
        val dialogs = listOf(
            "private fun ManualCollectionCreateDialog" to "private fun TipPayoutCreateDialog",
            "private fun TipPayoutCreateDialog" to "private fun VoidManualCollectionDialog",
            "private fun VoidManualCollectionDialog" to "private fun VoidTipPayoutDialog",
            "private fun VoidTipPayoutDialog" to "private fun BusinessDatePickerField",
            "private fun ExpenseCreateDialog" to "private fun AssetCreateDialog",
            "private fun AssetCreateDialog" to "private fun CapitalEntryCreateDialog",
            "private fun CapitalEntryCreateDialog" to "private fun nowIso",
        )

        dialogs.forEach { (start, end) ->
            val dialog = source.between(start, end)
            val editableControls = Regex(
                "\\n\\s+(?:BusinessDatePickerField|PickerField|DecimalField|OutlinedTextField)\\(",
            ).findAll(dialog).count()

            assertTrue("$start must use the busy-aware FinanceFormDialog", "FinanceFormDialog(" in dialog)
            assertEquals(
                "$start must pass the busy-derived enabled state to every editable control",
                editableControls,
                dialog.countOccurrences("enabled = formEnabled"),
            )
        }
    }

    private fun read(relativePath: String): String =
        Files.newBufferedReader(projectRoot().resolve(relativePath)).use { it.readText() }

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }

    private fun projectRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/AndroidManifest.xml") to Paths.get(""),
            Paths.get("app/src/main/AndroidManifest.xml") to Paths.get("app"),
            Paths.get("android-native/app/src/main/AndroidManifest.xml") to Paths.get("android-native/app"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.first) }?.second
            ?.toAbsolutePath()?.normalize()
            ?: error("Could not locate the Android app module")
    }
}

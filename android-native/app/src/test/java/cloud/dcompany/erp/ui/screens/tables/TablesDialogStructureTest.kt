package cloud.dcompany.erp.ui.screens.tables

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TablesDialogStructureTest {

    @Test
    fun `bill cancellation uses one focus owning alert dialog`() {
        val source = readSource(mainSourceRoot().resolve(TABLES_SCREEN_PATH))
        val billDialog = source.bracedBlockAfter("private fun BillDialog(")

        assertEquals(
            "Nested AlertDialogs compete for the Android IME and can make the cancellation reason untypable",
            1,
            Regex("""\bAlertDialog\s*\(""").findAll(billDialog).count(),
        )
        assertTrue(
            "Cancellation must switch the existing dialog into reason-entry mode",
            "if (cancellingLine != null)" in billDialog,
        )
        assertTrue(
            "Cancellation selection must survive activity recreation without saving the full bill line",
            "rememberSaveable" in billDialog && "cancellingLineKey" in billDialog,
        )
        assertTrue(
            "A local bill receiving its server id during sync must not reset the cancellation draft",
            "val billIdentity = bill.localBillId ?: bill.serverOrderId" in billDialog &&
                Regex("""rememberSaveable\(billIdentity\)""").findAll(billDialog).count() == 3,
        )
        assertTrue(
            "A blank cancellation reason must not enable confirmation",
            "cancellationReason.isNotBlank()" in billDialog,
        )
    }

    @Test
    fun `last active line explicitly routes to whole bill cancellation`() {
        val source = readSource(mainSourceRoot().resolve(TABLES_SCREEN_PATH))
        val billDialog = source.bracedBlockAfter("private fun BillDialog(")

        assertTrue(
            "The cancellation scope must follow the current non-voided line count",
            "val activeLineCount = bill.lines.count { !it.voided }" in billDialog &&
                "activeLineCount == 1" in billDialog,
        )
        assertTrue(
            "The final active line must call whole-bill cancellation rather than line cancellation",
            "onCancelBill(cancellationReason)" in billDialog,
        )
        assertTrue(
            "Bills with more than one active line must retain the line-cancellation path",
            "onCancelLine(cancellingLine, cancellationReason)" in billDialog,
        )
        assertTrue(
            "Staff must be told that the destructive action applies to the whole bill",
            "Void whole bill" in billDialog && "Keep bill" in billDialog &&
                "not only this line" in billDialog,
        )
        assertTrue(
            "The screen must bind whole-bill cancellation to the ViewModel's guarded request",
            "onCancelBill = vm::requestBillCancellation" in source,
        )
    }

    private fun String.bracedBlockAfter(marker: String): String {
        val markerIndex = indexOf(marker)
        require(markerIndex >= 0) { "Missing source marker: $marker" }
        val openBrace = indexOf('{', markerIndex + marker.length)
        require(openBrace >= 0) { "Missing opening brace after $marker" }
        var depth = 0
        for (index in openBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(openBrace + 1, index)
                }
            }
        }
        error("Unclosed source block after $marker")
    }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root from ${Paths.get("").toAbsolutePath()}")
    }

    private fun readSource(path: Path): String = Files.newBufferedReader(path).use { it.readText() }

    private companion object {
        const val TABLES_SCREEN_PATH = "cloud/dcompany/erp/ui/screens/tables/TablesScreen.kt"
    }
}

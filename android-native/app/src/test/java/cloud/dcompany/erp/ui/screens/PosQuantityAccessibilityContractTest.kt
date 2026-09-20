package cloud.dcompany.erp.ui.screens

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosQuantityAccessibilityContractTest {

    @Test
    fun `every POS quantity control names its item and exposes button semantics`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt")
        val callers = source.substringBefore("private fun QtyButton(")
        val control = source.substringAfter("private fun QtyButton(")
            .substringBefore("private fun Long.asPosTime")

        assertEquals(4, callers.countOccurrences("QtyButton("))
        assertEquals(4, callers.countOccurrences("accessibilityLabel ="))
        assertTrue("Modifier controls must name their option", "\${option.name} quantity" in callers)
        assertTrue("Cart controls must name their item", "\${line.item.name} quantity" in callers)
        assertTrue("The reusable control must require a label", "accessibilityLabel: String" in control)
        assertTrue("The control must expose button role", "role = Role.Button" in control)
        assertTrue("The control must expose its full spoken label", "contentDescription = accessibilityLabel" in control)
        assertFalse("A bare symbol-only quantity control is not accessible", "QtyButton(\"+\"" in callers)
    }

    @Test
    fun `direct online and offline payments explain an expired workspace lease`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val online = source.between("fun captureSale(", "private fun captureOfflineSale(")
        val offline = source.between("private fun captureOfflineSale(", "fun retryRejectedSale(")

        for (boundary in listOf(online, offline)) {
            assertFalse("Payment capture must never silently abandon a missing lease", "currentLease() ?: return" in boundary)
            assertTrue("Payment capture must show the recovery message", "POS_PAYMENT_WORKSPACE_UNAVAILABLE_MESSAGE" in boundary)
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
            ?: error("Could not locate the Android app module from ${Paths.get("").toAbsolutePath()}")
    }
}

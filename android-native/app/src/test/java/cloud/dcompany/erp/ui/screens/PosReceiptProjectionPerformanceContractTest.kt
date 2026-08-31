package cloud.dcompany.erp.ui.screens

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the route-lifecycle and dispatcher contract of UI-only receipt projections. */
class PosReceiptProjectionPerformanceContractTest {

    @Test
    fun `receipt projections stop after POS leaves composition`() {
        val source = readPosViewModel()
        val receiptProjection = source.between(
            "val recentReceipts:",
            "/**\n     * The UI reads Room, never the network.",
        )

        assertTrue(
            "Receipt projections must use one bounded while-subscribed policy",
            "receiptUiSharing = SharingStarted.WhileSubscribed(5_000)" in source,
        )
        assertTrue(
            "All five receipt UI StateFlows must use the bounded sharing policy",
            receiptProjection.countOccurrences(
                ".stateIn(viewModelScope, receiptUiSharing,",
            ) == 5,
        )
        assertFalse(
            "UI-only receipt projections must not remain active after leaving POS",
            "SharingStarted.Eagerly" in receiptProjection,
        )
    }

    @Test
    fun `canonical receipt payload decoding is dispatched away from Main`() {
        val canonicalProjection = readPosViewModel().between(
            "val canonicalReceipts:",
            "val receiptHistoryError:",
        )

        val decode = canonicalProjection.indexOf("it.decodedReceipt()")
        val defaultDispatcher = canonicalProjection.indexOf(".flowOn(Dispatchers.Default)")
        val sharedState = canonicalProjection.indexOf(".stateIn(viewModelScope, receiptUiSharing,")

        assertTrue("Canonical payload decoding must remain in the projection", decode >= 0)
        assertTrue(
            "Canonical payload decoding must be upstream of the Default dispatcher boundary",
            defaultDispatcher > decode,
        )
        assertTrue(
            "The dispatcher boundary must be applied before Main-owned StateFlow sharing",
            sharedState > defaultDispatcher,
        )
    }

    private fun readPosViewModel(): String = Files.newBufferedReader(
        projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt"),
    )
        .use { it.readText() }

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
            Paths.get("android-native/app/src/main/AndroidManifest.xml") to
                Paths.get("android-native/app"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.first) }?.second
            ?.toAbsolutePath()?.normalize()
            ?: error("Could not locate the Android app module from ${Paths.get("").toAbsolutePath()}")
    }
}

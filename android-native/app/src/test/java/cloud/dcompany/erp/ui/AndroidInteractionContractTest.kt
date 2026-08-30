package cloud.dcompany.erp.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidInteractionContractTest {

    @Test
    fun `activity owns hardware keyboard availability changes`() {
        val manifest = read(projectRoot().resolve("src/main/AndroidManifest.xml"))

        assertTrue(
            "Attaching or removing a hardware keyboard must not recreate an in-progress till form",
            "orientation|screenSize|screenLayout|keyboard|keyboardHidden|uiMode" in manifest,
        )
    }

    @Test
    fun `target 35 workspace stays clear of enforced system bars`() {
        val activity = read(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/MainActivity.kt"),
        )

        assertTrue(
            "The window must use one edge-to-edge contract on every supported Android release",
            "setDecorFitsSystemWindows(window, false)" in activity,
        )
        assertTrue(
            "The full window, including system-bar regions, must retain the branded background",
            "Modifier.fillMaxSize(),\n                    color = Brand.Background" in activity,
        )
        assertTrue(
            "Only the interactive workspace should be inset clear of system bars",
            "Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)" in activity,
        )
    }

    @Test
    fun `staff password fields request a password keyboard`() {
        val source = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/ui/screens/staff/StaffScreen.kt",
            ),
        )
        val protectedFields = Regex(
            """visualTransformation\s*=\s*PasswordVisualTransformation\(\),\s*""" +
                """keyboardOptions\s*=\s*KeyboardOptions\(keyboardType\s*=\s*KeyboardType\.Password\)""",
        ).findAll(source).count()

        assertEquals(4, protectedFields)
    }

    @Test
    fun `ticket phone entry uses the phone IME and bounded characters`() {
        val source = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/ui/screens/events/EventsScreen.kt",
            ),
        )
        val sellTickets = source.substringAfter("private fun SellTicketsDialog(")
            .substringBefore("private fun TicketsDialog(")

        assertTrue("Phone input must request the phone IME", "KeyboardType.Phone" in sellTickets)
        assertTrue("Phone input must stay within the API contract", ".take(20)" in sellTickets)
    }

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

    private fun read(path: Path): String = Files.newBufferedReader(path).use { it.readText() }
}

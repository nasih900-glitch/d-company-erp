package cloud.dcompany.erp.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumFoundationContractTest {

    @Test
    fun `typography keeps tabular numerals in numeric components only`() {
        val theme = read("src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt")
        val operationalComponents = read(
            "src/main/java/cloud/dcompany/erp/ui/components/OperationalComponents.kt",
        )

        assertFalse(
            "Global typography must not force tabular numerals onto headings and labels",
            "fontFeatureSettings = \"tnum\"" in theme,
        )
        assertTrue(
            "Money and timer values must retain stable tabular numerals",
            "style.copy(fontFeatureSettings = \"tnum\")" in operationalComponents,
        )
    }

    @Test
    fun `login production resources preserve the approved scene and standalone mark`() {
        val root = projectRoot()
        val scene = root.resolve("src/main/res/drawable-nodpi/login_scene_hd_r1.png")
        val reference = root.resolve("src/main/res/drawable-nodpi/login_reference_original.png")
        val mark = root.resolve("src/main/res/drawable/login_logo_d.xml")
        val retiredGeneratedArt = root.resolve("src/main/res/drawable-nodpi/ref_login_art.png")

        assertEquals(
            "13aa59b3860110f143b0b0a0bba6e84a6847667bacc2e01ae0b3b5990cef2844",
            sha256(scene),
        )
        assertEquals(1_672 to 941, pngDimensions(scene))
        assertEquals(
            "679707186d15f553b719ec0313a906f3ce2ce49303ea3ddf2b9a19a59930d49a",
            sha256(reference),
        )
        assertEquals(512 to 341, pngDimensions(reference))
        assertEquals(
            "94adb2de76f0b76ba72684718bf88d87890f79ef9f7ce6e14900525ee4bc4148",
            sha256(mark),
        )
        assertFalse("The retired generated Login artwork must not return", Files.exists(retiredGeneratedArt))
    }

    @Test
    fun `Android presentation uses matching launch surfaces without decorative gradients or glow`() {
        val theme = read("src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt")
        val primitives = read("src/main/java/cloud/dcompany/erp/ui/components/Primitives.kt")
        val nativeColors = read("src/main/res/values/colors.xml")

        val nativeNightColors = read("src/main/res/values-night/colors.xml")
        assertTrue("Day launch must match the Android Compose background", "#F7F7F7" in nativeColors)
        assertTrue("Night launch must match the Android Compose background", "#080E17" in nativeNightColors)
        assertFalse("linearGradient" in theme)
        assertFalse("verticalGradient" in theme)
        assertFalse("GoldSheen" in theme)
        assertFalse("surfaceFade" in theme)
        assertFalse("linearGradient" in primitives)
    }

    @Test
    fun `target tablet uses executive rail and retains full size action targets`() {
        val navigation = read("src/main/java/cloud/dcompany/erp/ui/Nav.kt")

        assertTrue("usesExecutiveNavigationRail(maxWidth, maxHeight)" in navigation)
        assertTrue("Modifier.width(80.dp).fillMaxHeight()" in navigation)
        assertTrue("rememberSaveableStateHolder()" in navigation)
        assertTrue(".height(56.dp)" in navigation)
        assertTrue("private fun HeaderIconAction(" in navigation)
        assertTrue("modifier = Modifier.size(48.dp)" in navigation)
    }

    private fun read(relativePath: String): String =
        Files.newBufferedReader(projectRoot().resolve(relativePath)).use { it.readText() }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun pngDimensions(path: Path): Pair<Int, Int> {
        val bytes = Files.readAllBytes(path)
        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
        return dimensions.int to dimensions.int
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
}

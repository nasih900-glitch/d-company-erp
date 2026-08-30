package cloud.dcompany.erp.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
    fun `login uses the real brand mark and focused gaming centre language`() {
        val login = read("src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt")
        val brandMark = read("src/main/java/cloud/dcompany/erp/ui/components/DCompanyBrandMark.kt")

        assertTrue("DCompanyBrandMark(" in login)
        assertTrue("size = 92.dp" in login)
        assertTrue("painterResource(R.mipmap.ic_launcher_foreground)" in brandMark)
        assertTrue("clip(CircleShape)" in brandMark)
        assertTrue("contentScale = ContentScale.Crop" in brandMark)
        assertTrue("scaleX = 1.5f" in brandMark)
        assertTrue("GAMING CENTRE" in login)
        assertFalse("Cafe + Gaming Lounge" in login)
        assertTrue("shape = Radius.shapeXl" in login)
    }

    @Test
    fun `target tablet uses compact shell with full size action targets`() {
        val navigation = read("src/main/java/cloud/dcompany/erp/ui/Nav.kt")

        assertTrue("maxWidth <= 1_280.dp" in navigation)
        assertTrue("Modifier.fillMaxWidth().height(68.dp)" in navigation)
        assertTrue("private fun HeaderIconAction(" in navigation)
        assertTrue("modifier = Modifier.size(48.dp)" in navigation)
    }

    private fun read(relativePath: String): String =
        Files.newBufferedReader(projectRoot().resolve(relativePath)).use { it.readText() }

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

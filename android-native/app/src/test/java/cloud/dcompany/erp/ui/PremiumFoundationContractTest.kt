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
    fun `login uses the real brand mark and supplied reference artwork`() {
        val login = read("src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt")
        val navigation = read("src/main/java/cloud/dcompany/erp/ui/Nav.kt")
        val brandMark = read("src/main/java/cloud/dcompany/erp/ui/components/DCompanyBrandMark.kt")

        assertTrue("DCompanyBrandMark(" in login)
        assertTrue("painterResource(R.mipmap.ic_launcher_foreground)" in brandMark)
        assertTrue("clip(CircleShape)" in brandMark)
        assertTrue("contentScale = ContentScale.Crop" in brandMark)
        assertTrue("scaleX = 1.5f" in brandMark)
        assertTrue("GAMING LOUNGE" in login)
        assertTrue("R.drawable.login_reference_original" in login)
        assertTrue("LoginReferenceArtworkBounds" in login)
        assertTrue("srcOffset = IntOffset(crop.left, crop.top)" in login)
        assertTrue("srcSize = IntSize(crop.width, crop.height)" in login)
        assertTrue("OPERATIONS" in navigation)
        assertFalse("GAMING CENTRE" in login)
        assertFalse("GAMING CENTRE" in navigation)
        assertFalse("Cafe + Gaming Lounge" in login)
        assertFalse("Remember me" in login)
        assertFalse("R.drawable.ref_login_art" in login)
        assertFalse("painterResource(" in login)
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

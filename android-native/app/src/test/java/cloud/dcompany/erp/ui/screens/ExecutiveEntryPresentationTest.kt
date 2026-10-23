package cloud.dcompany.erp.ui.screens

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveEntryPresentationTest {
    @Test
    fun `login reference bytes remain exact and artwork crop excludes the flat UI`() {
        val image = projectRoot().resolve("src/main/res/drawable-nodpi/login_reference_original.png")
        val bytes = Files.readAllBytes(image)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)

        assertEquals(LoginReferenceAssetSha256, digest)
        assertEquals(512, dimensions.int)
        assertEquals(341, dimensions.int)
        assertEquals(
            LoginArtworkSourceCrop(left = 221, top = 42, width = 266, height = 261),
            LoginReferenceArtworkBounds,
        )
        assertTrue(LoginReferenceArtworkBounds.left > 200)
        assertTrue(LoginReferenceArtworkBounds.right < 512)
        assertTrue(LoginReferenceArtworkBounds.top > 30)
        assertTrue(LoginReferenceArtworkBounds.bottom < 341)
        assertEquals(
            LoginArtworkSourceCrop(left = 221, top = 42, width = 112, height = 50),
            LoginReferencePalmBounds,
        )
        assertTrue(LoginReferencePalmBounds.right <= LoginReferenceArtworkBounds.right)
        assertTrue(LoginReferencePalmBounds.bottom < 100)
    }

    @Test
    fun `login artwork fill crop remains inside the supplied artwork region`() {
        listOf(
            loginReferenceArtworkSourceCrop(768, 800),
            loginReferenceArtworkSourceCrop(1_280, 800),
            loginReferenceArtworkSourceCrop(600, 220),
        ).forEach { crop ->
            assertTrue(crop.left >= LoginReferenceArtworkBounds.left)
            assertTrue(crop.top >= LoginReferenceArtworkBounds.top)
            assertTrue(crop.right <= LoginReferenceArtworkBounds.right)
            assertTrue(crop.bottom <= LoginReferenceArtworkBounds.bottom)
        }
    }

    @Test
    fun `login presentation uses allocated brand width real version and a native ambience fade`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt"),
        ).use { it.readText() }
        val brandHeader = source.substring(
            startIndex = source.indexOf("private fun LoginBrandHeader"),
            endIndex = source.indexOf("private fun LoginReferencePalmAmbience"),
        )

        assertTrue("Both brand lines must fill their allocated column",
            brandHeader.split("modifier = Modifier.fillMaxWidth()").size - 1 >= 2)
        assertTrue("Footer must derive from the packaged version", "v\${BuildConfig.VERSION_NAME}" in source)
        assertTrue("Palm ambience must blend into the native panel", "Brush.verticalGradient(" in source)
        assertTrue("Centered action text must receive finite width",
            "padding(horizontal = 32.dp)" in source)
        assertFalse("Version must not be hard-coded", "v3.1.47" in source)
    }

    @Test
    fun `authorized image bytes remain exact`() {
        val image = projectRoot().resolve("src/main/res/drawable-nodpi/authorized_lounge_original.png")
        val bytes = Files.readAllBytes(image)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)

        assertEquals(AuthorizedLoungeAssetSha256, digest)
        assertEquals(960, dimensions.int)
        assertEquals(1_280, dimensions.int)
    }

    @Test
    fun `landscape and split panel crops stay entirely below television`() {
        assertEquals(
            LoungeSourceCrop(left = 96, top = 800, width = 768, height = 480),
            authorizedLoungeSourceCrop(destinationWidthPx = 1_280, destinationHeightPx = 800),
        )
        assertEquals(
            LoungeSourceCrop(left = 249, top = 800, width = 461, height = 480),
            authorizedLoungeSourceCrop(destinationWidthPx = 768, destinationHeightPx = 800),
        )
    }

    @Test
    fun `portrait and panoramic crops remain inside approved lower scene`() {
        listOf(
            authorizedLoungeSourceCrop(destinationWidthPx = 600, destinationHeightPx = 800),
            authorizedLoungeSourceCrop(destinationWidthPx = 1_000, destinationHeightPx = 200),
        ).forEach { crop ->
            assertTrue(crop.left >= 0)
            assertTrue(crop.top >= 800)
            assertTrue(crop.right <= 960)
            assertTrue(crop.bottom <= 1_280)
        }
    }

    @Test
    fun `required update branch remains before the two splash auth branches`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/MainActivity.kt"),
        ).use { it.readText() }
        val requiredUpdate = source.indexOf("is ClientCompatibilityState.UpdateRequired ->")
        val loading = source.indexOf("is AuthState.Loading -> ExecutiveEntrySplash()")
        val verifying = source.indexOf("is AuthState.VerifyingCached -> ExecutiveEntrySplash(")

        assertTrue(requiredUpdate >= 0)
        assertTrue(loading > requiredUpdate)
        assertTrue(verifying > loading)
    }

    @Test
    fun `entry presentation stays pure and has no full bitmap fallback`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/ExecutiveEntryPresentation.kt"),
        ).use { it.readText() }

        listOf("DCompanyApp", "ApiClient", "ViewModel", "CoroutineScope", "NavController")
            .forEach { forbidden -> assertFalse("Pure entry presentation must exclude $forbidden", forbidden in source) }
        assertTrue("Renderer must pass the approved source offset", "srcOffset = IntOffset(crop.left, crop.top)" in source)
        assertTrue("Renderer must pass the approved source size", "srcSize = IntSize(crop.width, crop.height)" in source)
        assertFalse("Renderer must never use a painter fallback", "painterResource(" in source)
        assertFalse("Renderer must never use a full-bitmap ContentScale fallback", "ContentScale." in source)
    }

    private fun projectRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/AndroidManifest.xml") to Paths.get(""),
            Paths.get("app/src/main/AndroidManifest.xml") to Paths.get("app"),
            Paths.get("android-native/app/src/main/AndroidManifest.xml") to Paths.get("android-native/app"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.first) }?.second
            ?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android app module from ${Paths.get("").toAbsolutePath()}")
    }
}

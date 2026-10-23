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

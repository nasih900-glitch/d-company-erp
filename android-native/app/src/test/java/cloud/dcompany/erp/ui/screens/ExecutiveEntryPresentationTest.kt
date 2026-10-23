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
    }

    @Test
    fun `wide composition follows measured viewport and form proportions`() {
        val geometry = loginWideGeometry(containerWidth = 1_280f, containerHeight = 800f)
        val imeGeometry = loginWideGeometry(containerWidth = 1_280f, containerHeight = 420f)
        val heightSequence = listOf(800f, 720f, 600f, 550f, 500f, 474f, 472f, 420f)
            .map { height ->
                loginWideGeometry(
                    containerWidth = 1_280f,
                    containerHeight = height,
                    fontScale = 1.3f,
                )
            }

        assertTrue(useWideLoginLayout(containerWidth = 1_280f))
        assertFalse(useWideLoginLayout(containerWidth = 600f))
        assertEquals(1_280f, geometry.compositionWidth, 0.01f)
        assertEquals(720f, geometry.compositionHeight, 0.01f)
        assertEquals(0f, geometry.compositionLeft, 0.01f)
        assertEquals(40f, geometry.compositionTop, 0.01f)
        assertEquals(0.43f, geometry.formZoneWidth / geometry.compositionWidth, 0.001f)
        assertEquals(0.57f, geometry.artworkZoneWidth / geometry.compositionWidth, 0.001f)
        assertEquals(0.79f, geometry.controlWidth / geometry.formZoneWidth, 0.001f)
        assertEquals(0.12f, geometry.brandTop / geometry.compositionHeight, 0.001f)
        assertEquals(0.36f, (geometry.brandTop + geometry.brandHeight) / geometry.compositionHeight, 0.001f)
        assertEquals(0.43f, geometry.formTop / geometry.compositionHeight, 0.001f)
        assertFalse(geometry.condensedForIme)
        assertTrue(geometry.showIdentity)
        assertTrue(imeGeometry.condensedForIme)
        assertFalse(imeGeometry.showIdentity)
        assertEquals(1_280f, imeGeometry.compositionWidth, 0.01f)
        assertEquals(420f, imeGeometry.compositionHeight, 0.01f)
        assertEquals(0.79f, imeGeometry.controlWidth / imeGeometry.formZoneWidth, 0.001f)
        assertEquals(64f, imeGeometry.formTop, 0.01f)
        heightSequence.forEach { candidate ->
            assertEquals(1_280f, candidate.compositionWidth, 0.01f)
            assertEquals(1_280f * 0.43f, candidate.formZoneWidth, 0.01f)
            assertEquals(1_280f * 0.43f * 0.79f, candidate.controlWidth, 0.01f)
        }
        heightSequence.zipWithNext().forEach { (before, after) ->
            assertEquals(before.formZoneWidth, after.formZoneWidth, 0.01f)
            assertEquals(before.controlWidth, after.controlWidth, 0.01f)
        }
        val beforeFormerThreshold = loginWideGeometry(1_280f, 474f)
        val afterFormerThreshold = loginWideGeometry(1_280f, 472f)
        assertEquals(2f, beforeFormerThreshold.formTop - afterFormerThreshold.formTop, 0.01f)
        val intermediateLargeText = loginWideGeometry(1_280f, 550f, fontScale = 1.3f)
        assertFalse(intermediateLargeText.showIdentity)
        assertTrue(intermediateLargeText.compositionHeight - intermediateLargeText.formTop >= 0f)
        val veryShort = loginWideGeometry(1_280f, 40f, fontScale = 1.3f)
        assertEquals(40f, veryShort.formTop, 0.01f)
        assertEquals(0f, veryShort.compositionHeight - veryShort.formTop, 0.01f)
    }

    @Test
    fun `generated HD scene bytes are exact and remain effectively sixteen by nine`() {
        val image = projectRoot().resolve("src/main/res/drawable-nodpi/login_scene_hd_r1.png")
        val bytes = Files.readAllBytes(image)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
        val width = dimensions.int
        val height = dimensions.int

        assertEquals(LoginSceneHdR1Sha256, digest)
        assertEquals(1_672, width)
        assertEquals(941, height)
        assertEquals(16f / 9f, width.toFloat() / height, 0.002f)
    }

    @Test
    fun `selected splash scene bytes remain exact and effectively sixteen by nine`() {
        val image = projectRoot().resolve("src/main/res/drawable-nodpi/splash_lounge_hd_r1.png")
        val bytes = Files.readAllBytes(image)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
        val width = dimensions.int
        val height = dimensions.int

        assertEquals(SplashLoungeHdR1Sha256, digest)
        assertEquals(1_672, width)
        assertEquals(941, height)
        assertEquals(16f / 9f, width.toFloat() / height, 0.002f)
    }

    @Test
    fun `landscape splash crops preserve the selected scene without stretching`() {
        assertEquals(
            SplashSceneSourceCrop(left = 83, top = 0, width = 1_506, height = 941),
            splashSceneSourceCrop(destinationWidthPx = 1_280, destinationHeightPx = 800),
        )
        assertEquals(
            SplashSceneSourceCrop(left = 0, top = 0, width = 1_672, height = 941),
            splashSceneSourceCrop(destinationWidthPx = 1_600, destinationHeightPx = 900),
        )
    }

    @Test
    fun `portrait and panoramic splash crops remain centered inside selected scene`() {
        listOf(
            splashSceneSourceCrop(destinationWidthPx = 600, destinationHeightPx = 800),
            splashSceneSourceCrop(destinationWidthPx = 1_000, destinationHeightPx = 200),
        ).forEach { crop ->
            assertTrue(crop.left >= 0)
            assertTrue(crop.top >= 0)
            assertTrue(crop.right <= 1_672)
            assertTrue(crop.bottom <= 941)
            assertTrue(kotlin.math.abs(
                crop.width.toDouble() / crop.height -
                    if (crop.height == 941) 0.75 else 5.0,
            ) <= 0.01)
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
        assertTrue("Renderer must pass the selected scene source offset", "srcOffset = IntOffset(crop.left, crop.top)" in source)
        assertTrue("Renderer must pass the selected scene source size", "srcSize = IntSize(crop.width, crop.height)" in source)
        assertTrue("Splash must load the exact scene drawable", "R.drawable.splash_lounge_hd_r1" in source)
        assertFalse("Retired lower-photo crop must not remain active", "R.drawable.authorized_lounge_original" in source)
        assertFalse("Splash must use the standalone D, not the shared seal", "DCompanyBrandMark" in source)
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

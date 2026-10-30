package cloud.dcompany.erp.ui.screens

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import cloud.dcompany.erp.ui.theme.workspaceBackgroundCropPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveEntryPresentationTest {
    @Test
    fun `fresh login swipe requires distance or upward velocity`() {
        assertFalse(shouldCompleteFreshLoginSwipe(63f, 899f, 64f))
        assertTrue(shouldCompleteFreshLoginSwipe(64f, 0f, 64f))
        assertTrue(shouldCompleteFreshLoginSwipe(24f, 900f, 64f))
        assertFalse("A tiny high-speed jitter is not purposeful", shouldCompleteFreshLoginSwipe(10f, 4_000f, 64f))
        assertFalse(shouldCompleteFreshLoginSwipe(200f, -2_000f, 240f))
    }

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
    fun `wide composition keeps the reference glass panel inset and IME responsive`() {
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
        assertEquals(800f, geometry.compositionHeight, 0.01f)
        assertEquals(0f, geometry.compositionLeft, 0.01f)
        assertEquals(0f, geometry.compositionTop, 0.01f)
        assertEquals(48f, geometry.panelLeft, 0.01f)
        assertEquals(48f, geometry.panelTop, 0.01f)
        assertEquals(704f, geometry.panelHeight, 0.01f)
        assertEquals(0.36f, geometry.formZoneWidth / geometry.compositionWidth, 0.001f)
        assertEquals(0.6025f, geometry.artworkZoneWidth / geometry.compositionWidth, 0.001f)
        assertEquals(0.79f, geometry.controlWidth / geometry.formZoneWidth, 0.001f)
        assertEquals(0.06f, geometry.brandTop / geometry.panelHeight, 0.001f)
        assertEquals(0.30f, (geometry.brandTop + geometry.brandHeight) / geometry.panelHeight, 0.001f)
        assertEquals(0.36f, geometry.formTop / geometry.panelHeight, 0.001f)
        assertFalse(geometry.condensedForIme)
        assertTrue(geometry.showIdentity)
        assertTrue(imeGeometry.condensedForIme)
        assertFalse(imeGeometry.showIdentity)
        assertEquals(1_280f, imeGeometry.compositionWidth, 0.01f)
        assertEquals(420f, imeGeometry.compositionHeight, 0.01f)
        assertEquals(48f, imeGeometry.panelLeft, 0.01f)
        assertEquals(25.2f, imeGeometry.panelTop, 0.01f)
        assertEquals(369.6f, imeGeometry.panelHeight, 0.01f)
        assertEquals(0.79f, imeGeometry.controlWidth / imeGeometry.formZoneWidth, 0.001f)
        assertEquals(64f, imeGeometry.formTop, 0.01f)
        heightSequence.forEach { candidate ->
            assertEquals(1_280f, candidate.compositionWidth, 0.01f)
            assertEquals(48f, candidate.panelLeft, 0.01f)
            assertEquals(1_280f * 0.36f, candidate.formZoneWidth, 0.01f)
            assertEquals(1_280f * 0.36f * 0.79f, candidate.controlWidth, 0.01f)
            assertEquals(1_280f - 48f - (1_280f * 0.36f), candidate.artworkZoneWidth, 0.01f)
        }
        heightSequence.zipWithNext().forEach { (before, after) ->
            assertEquals(before.formZoneWidth, after.formZoneWidth, 0.01f)
            assertEquals(before.controlWidth, after.controlWidth, 0.01f)
        }
        val beforeFormerThreshold = loginWideGeometry(1_280f, 474f)
        val afterFormerThreshold = loginWideGeometry(1_280f, 472f)
        assertEquals(0f, beforeFormerThreshold.formTop - afterFormerThreshold.formTop, 0.01f)
        val intermediateLargeText = loginWideGeometry(1_280f, 550f, fontScale = 1.3f)
        assertFalse(intermediateLargeText.showIdentity)
        assertTrue(intermediateLargeText.panelHeight - intermediateLargeText.formTop >= 0f)
        val veryShort = loginWideGeometry(1_280f, 40f, fontScale = 1.3f)
        assertEquals(35.2f, veryShort.formTop, 0.01f)
        assertEquals(0f, veryShort.panelHeight - veryShort.formTop, 0.01f)
    }

    @Test
    fun `login glass remains native presentation over the preserved scene and auth surface`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt"),
        ).use { it.readText() }

        assertTrue("Login must retain the reconstructed scene", "R.drawable.login_scene_hd_r1" in source)
        assertTrue("Login must retain the original shared brand mark", "DCompanyBrandMark" in source)
        assertTrue("The glass surface must remain a native Compose layer", "login-glass-material" in source)
        assertTrue("The glass surface needs a clipped highlight and edge", "LoginGlassShape" in source)
        listOf("Continue with Google", "Single sign-on", "Create account")
            .forEach { unsupported ->
                assertFalse("Login must not introduce the unsupported method: $unsupported", unsupported in source)
            }
    }

    @Test
    fun `login backdrop center crop covers every viewport edge without distorting source`() {
        listOf(
            1_280 to 800,
            1_280 to 720,
            1_280 to 420,
            600 to 800,
        ).forEach { (width, height) ->
            val crop = loginBackdropCenterCrop(
                sourceWidth = 1_672,
                sourceHeight = 941,
                containerWidth = width,
                containerHeight = height,
            )
            assertTrue("crop must cover left edge: $crop", crop.left <= 0)
            assertTrue("crop must cover top edge: $crop", crop.top <= 0)
            assertTrue("crop must cover right edge: $crop", crop.right >= width)
            assertTrue("crop must cover bottom edge: $crop", crop.bottom >= height)
            assertTrue(
                "one dimension must be fitted exactly: $crop",
                crop.width == width || crop.height == height,
            )
            assertEquals(
                "crop must preserve the source aspect ratio within outward pixel rounding",
                1_672f / 941f,
                crop.width.toFloat() / crop.height,
                0.003f,
            )
        }

        val tablet = loginBackdropCenterCrop(1_672, 941, 1_280, 800)
        assertEquals(0, tablet.top)
        assertEquals(800, tablet.height)
        assertTrue("16:10 must crop horizontally instead of letterboxing", tablet.left < 0)
        assertTrue(tablet.right > 1_280)
    }

    @Test
    fun `scene renderer crop matches the preserved login center crop`() {
        listOf(1_280 to 800, 1_280 to 420, 600 to 800).forEach { (width, height) ->
            val legacy = loginBackdropCenterCrop(1_672, 941, width, height)
            val renderer = workspaceBackgroundCropPlan(1_672, 941, width, height)
            val visibleLeft = -legacy.left.toFloat() / legacy.width * 1_672f
            val visibleTop = -legacy.top.toFloat() / legacy.height * 941f
            val visibleRight = (width - legacy.left).toFloat() / legacy.width * 1_672f
            val visibleBottom = (height - legacy.top).toFloat() / legacy.height * 941f

            assertEquals(width, renderer.outputWidth)
            assertEquals(height, renderer.outputHeight)
            assertEquals(renderer.sourceLeft.toFloat(), visibleLeft, 1.5f)
            assertEquals(renderer.sourceTop.toFloat(), visibleTop, 1.5f)
            assertEquals(renderer.sourceRight.toFloat(), visibleRight, 1.5f)
            assertEquals(renderer.sourceBottom.toFloat(), visibleBottom, 1.5f)
        }
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
        val executableSource = source
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)//.*$"), "")

        mapOf(
            "DCompanyApp" to Regex("\\bDCompanyApp\\b"),
            "ApiClient" to Regex("\\bApiClient\\b"),
            "ViewModel" to Regex("\\b(?:[A-Za-z_][A-Za-z0-9_]*)?ViewModel\\b"),
            "CoroutineScope" to Regex("\\bCoroutineScope\\b"),
            "NavController" to Regex("\\bNavController\\b"),
        ).forEach { (name, pattern) ->
            assertFalse(
                "Pure entry presentation must exclude executable $name dependencies",
                pattern.containsMatchIn(executableSource),
            )
        }
        assertTrue("Renderer must pass the selected scene source offset", "srcOffset = IntOffset(crop.left, crop.top)" in source)
        assertTrue("Renderer must pass the selected scene source size", "srcSize = IntSize(crop.width, crop.height)" in source)
        assertTrue("Splash must load the exact scene drawable", "R.drawable.splash_lounge_hd_r1" in source)
        assertFalse("Retired lower-photo crop must not remain active", "R.drawable.authorized_lounge_original" in source)
        assertTrue("Splash must use the web-authoritative shared brand mark", "DCompanyBrandMark" in source)
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

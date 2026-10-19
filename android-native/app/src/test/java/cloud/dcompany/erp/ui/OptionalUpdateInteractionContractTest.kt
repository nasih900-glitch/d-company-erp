package cloud.dcompany.erp.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalUpdateInteractionContractTest {

    @Test
    fun `activity wires Later only to compatibility snooze and keeps update controls separate`() {
        val activity = read("src/main/java/cloud/dcompany/erp/MainActivity.kt")
        val appRoot = activity.substringAfter("private fun AppRoot(")
            .substringBefore("@Composable\nprivate fun CompatibilityCheckOverlay")
        val bannerWiring = appRoot
            .substringAfter("else if (compatibilityState is ClientCompatibilityState.UpdateAvailable)")

        assertTrue(
            "Later must only dismiss the optional compatibility notice",
            "onDismiss = compatibility::dismissOptionalUpdate" in bannerWiring,
        )
        assertFalse(
            "Later must not discard downloaded update state",
            "appUpdate.discard()" in bannerWiring,
        )
        assertFalse(
            "Later must not cancel an active update download",
            "onDismiss = appUpdate::cancel" in bannerWiring,
        )
        assertTrue(
            "Cancel remains a separate operator action",
            "onCancelDownload = appUpdate::cancel" in bannerWiring,
        )
        assertTrue(
            "Discard remains the only destructive artifact action",
            "onDiscardDownload = appUpdate::discard" in bannerWiring,
        )
        val authStateRendering = appRoot.indexOf("when (val s = state)")
        val optionalOffer = appRoot.indexOf(
            "else if (compatibilityState is ClientCompatibilityState.UpdateAvailable)",
        )
        assertTrue("The activity must still render auth states", authStateRendering >= 0)
        assertTrue("The activity must still expose the optional update offer", optionalOffer >= 0)
        assertTrue(
            "The optional offer must remain available after every auth-state screen is rendered",
            authStateRendering < optionalOffer,
        )
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

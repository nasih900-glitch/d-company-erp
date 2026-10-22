package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.net.ClientUpdateNotice
import cloud.dcompany.erp.core.update.AppUpdateUiState
import cloud.dcompany.erp.core.update.DirectUpdateMetadataResult
import cloud.dcompany.erp.core.update.validateDirectUpdateMetadata
import cloud.dcompany.erp.optionalUpdateMayDismiss
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

    @Test
    fun `Later stays available except while the exact offered download is active`() {
        val notice = ClientUpdateNotice(
            message = "Update available", updateUrl = "https://updates.example.test/build-11.apk",
            currentVersionCode = 10, minimumSupportedVersionCode = 10,
            latestVersionCode = 11, latestVersionName = "3.1.0",
            apkSha256 = "ab".repeat(32), apkSizeBytes = 42L * 1024 * 1024,
            apkSigningCertSha256 = "12".repeat(32),
        )
        val descriptor = (validateDirectUpdateMetadata(notice, installedVersionCode = 10)
            as DirectUpdateMetadataResult.Valid).descriptor
        val downloading = AppUpdateUiState.Downloading(descriptor, 100, descriptor.sizeBytes)
        val verifying = AppUpdateUiState.Verifying(descriptor)
        assertFalse(optionalUpdateMayDismiss(notice, downloading, 10))
        assertFalse(optionalUpdateMayDismiss(notice, verifying, 10))
        listOf(AppUpdateUiState.Idle,
            AppUpdateUiState.Paused(descriptor, 100, descriptor.sizeBytes),
            AppUpdateUiState.Ready(descriptor, "/private/update.apk"),
            AppUpdateUiState.Failed(11, "Connection interrupted", descriptor, canResume = true),
        ).forEach { state -> assertTrue(optionalUpdateMayDismiss(notice, state, 10)) }
        assertTrue(optionalUpdateMayDismiss(notice.copy(apkSha256 = "cd".repeat(32)), downloading, 10))
        assertTrue(optionalUpdateMayDismiss(notice.copy(apkSha256 = null), verifying, 10))
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

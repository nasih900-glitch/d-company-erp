package cloud.dcompany.erp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.OptionalUpdateBanner
import cloud.dcompany.erp.core.net.ClientUpdateNotice
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.core.update.AppUpdateUiState
import cloud.dcompany.erp.core.update.DirectUpdateMetadataResult
import cloud.dcompany.erp.core.update.validateDirectUpdateMetadata
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionalUpdateBannerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun exactActiveDownloadKeepsProgressVisibleUntilExplicitPauseOrReady() {
        val notice = ClientUpdateNotice(
            message = "Optional test update", updateUrl = "https://updates.example.test/update.apk",
            currentVersionCode = BuildConfig.VERSION_CODE,
            minimumSupportedVersionCode = BuildConfig.VERSION_CODE,
            latestVersionCode = BuildConfig.VERSION_CODE + 1, latestVersionName = "3.2.0",
            apkSha256 = "ab".repeat(32), apkSizeBytes = 42L * 1024 * 1024,
            apkSigningCertSha256 = "12".repeat(32),
        )
        val descriptor = (validateDirectUpdateMetadata(notice) as DirectUpdateMetadataResult.Valid).descriptor
        val state = mutableStateOf<AppUpdateUiState>(AppUpdateUiState.Downloading(descriptor, 100, descriptor.sizeBytes))
        var dismissed = 0
        var pauses = 0
        compose.setContent {
            DCompanyTheme {
                OptionalUpdateBanner(notice, state.value, OutboxWorkStatus(),
                    onDismiss = { dismissed++ }, onDownload = {}, onCancelDownload = { pauses++ },
                    onDiscardDownload = {}, onOpenUpdateLink = {}, onInstall = {})
            }
        }
        compose.onNodeWithText("Later").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithText("Pause").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, pauses)
            state.value = AppUpdateUiState.Paused(descriptor, 100, descriptor.sizeBytes)
        }
        compose.onNodeWithText("Later").assertIsEnabled()
        compose.runOnIdle { state.value = AppUpdateUiState.Verifying(descriptor) }
        compose.onNodeWithText("Later").assertIsNotEnabled()
        compose.runOnIdle { state.value = AppUpdateUiState.Ready(descriptor, "/private/update.apk") }
        compose.onNodeWithText("Later").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}

package cloud.dcompany.erp.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.dcompany.erp.core.remote.RemoteActiveSession
import cloud.dcompany.erp.core.remote.RemoteAssistanceUiState
import cloud.dcompany.erp.core.remote.RemoteConsentChoice
import cloud.dcompany.erp.core.remote.RemoteCapturePrivacyController
import cloud.dcompany.erp.core.remote.RemoteGrantKind
import cloud.dcompany.erp.core.remote.RemoteGrantPrompt
import cloud.dcompany.erp.core.remote.RemoteDeviceKeyStatus
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteAssistanceDeviceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oneTimeConsentActiveStopRevokeAndPrivacyPlaceholderStayVisible() {
        var sessionStopped = false
        var permissionRevoked = false
        compose.setContent {
            var prompt by remember {
                mutableStateOf<RemoteGrantPrompt?>(grant(RemoteGrantKind.ONE_TIME))
            }
            var consent by remember { mutableStateOf(RemoteConsentChoice.UNDECIDED) }
            var active by remember { mutableStateOf(false) }
            val state = RemoteAssistanceUiState(
                consent = consent,
                pendingGrant = prompt,
                activeSession = if (active) activeSession() else null,
                privacyProtected = true,
                notificationReady = true,
            )
            DCompanyTheme {
                Column {
                    RemoteAssistanceActiveBanner(
                        active = active,
                        online = true,
                        privacyProtected = true,
                        lastCommandLabel = null,
                        onStop = {
                            sessionStopped = true
                            active = false
                        },
                    )
                    RemoteAssistanceSettingsCard(
                        state = state,
                        onStop = {
                            sessionStopped = true
                            active = false
                        },
                        onRevoke = {
                            permissionRevoked = true
                            active = false
                            consent = RemoteConsentChoice.REVOKED
                        },
                        onEnableNotifications = {},
                    )
                    RemotePrivacyPlaceholder()
                }
                prompt?.let { grant ->
                    RemoteAssistanceConsentDialog(
                        grant = grant,
                        busy = false,
                        onDeny = {
                            prompt = null
                            consent = RemoteConsentChoice.DENIED
                        },
                        onAllow = {
                            prompt = null
                            consent = RemoteConsentChoice.ALLOWED
                            active = true
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Allow one ERP support session?").assertIsDisplayed()
        compose.onNodeWithText("this request can start one short session only", substring = true)
            .assertExists()
        compose.onNodeWithText("31 Aug 2026, 10:00 UTC", substring = true).assertExists()
        compose.onNodeWithTag("remote-assistance-allow").performClick()

        compose.onNodeWithTag("remote-assistance-active").assertIsDisplayed()
        compose.onNodeWithText("Owner ERP support active — Help only").assertIsDisplayed()
        compose.onNodeWithText("Owner can view Help only", substring = true).assertExists()
        compose.onNodeWithText("other ERP screens are hidden", substring = true).assertExists()
        compose.onNodeWithTag("remote-assistance-stop").assertIsDisplayed()
        compose.onNodeWithTag("remote-assistance-settings-stop").assertExists()
        compose.onNodeWithTag("remote-assistance-revoke").assertExists()
        compose.onNodeWithTag("remote-privacy-placeholder").assertExists()
        compose.onNodeWithText("Sensitive ERP details are hidden").assertExists()

        compose.onNodeWithTag("remote-assistance-stop").performClick()
        compose.onNodeWithTag("remote-assistance-active").assertDoesNotExist()
        compose.runOnIdle { assertTrue(sessionStopped) }

        compose.onNodeWithTag("remote-assistance-revoke").performClick()
        compose.onNodeWithText("A later request will need a new explicit choice.", substring = true)
            .assertExists()
        compose.onNodeWithTag("remote-assistance-confirm-revoke").performClick()
        compose.onNodeWithText("Owner ERP support has been revoked on this tablet.").assertExists()
        compose.runOnIdle { assertTrue(permissionRevoked) }
    }

    @Test
    fun activeOfflineBannerExplainsPausedSharingAndKeepsStopAvailable() {
        var stopped = false
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceActiveBanner(
                    active = true,
                    online = false,
                    privacyProtected = true,
                    lastCommandLabel = null,
                    onStop = { stopped = true },
                )
            }
        }

        compose.onNodeWithText("Sharing paused while offline", substring = true).assertExists()
        compose.onNodeWithText("Owner can view Help only", substring = true).assertExists()
        compose.onNodeWithText("other ERP screens stay hidden", substring = true).assertExists()
        compose.onNodeWithText("Stop remains available", substring = true).assertExists()
        compose.onNodeWithTag("remote-assistance-stop").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test
    fun anytimeConsentDisclosesMaximumDurationAndCanBeDenied() {
        var denied = false
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceConsentDialog(
                    grant = grant(RemoteGrantKind.ANYTIME),
                    busy = false,
                    onDeny = { denied = true },
                    onAllow = {},
                )
            }
        }

        compose.onNodeWithText("Allow owner anytime ERP support?").assertIsDisplayed()
        compose.onNodeWithText("for up to 24 hours", substring = true).assertExists()
        compose.onNodeWithText("or until you revoke it", substring = true).assertExists()
        compose.onNodeWithText("31 Aug 2026, 10:00 UTC", substring = true).assertExists()
        compose.onNodeWithText("view only the Help screen", substring = true).assertExists()
        compose.onNodeWithText("approved ERP modules", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("remote-assistance-deny").performClick()
        compose.runOnIdle { assertTrue(denied) }
    }

    @Test
    fun sensitiveContentActivatesAndReleasesCommandPrivacyGate() {
        val privacy = RemoteCapturePrivacyController()
        compose.setContent {
            var sensitiveVisible by remember { mutableStateOf(true) }
            DCompanyTheme {
                CompositionLocalProvider(LocalRemoteCapturePrivacyController provides privacy) {
                    Column {
                        if (sensitiveVisible) {
                            RemoteSensitiveContent { Text("Customer phone entry") }
                        }
                        Button(onClick = { sensitiveVisible = false }) { Text("Close sensitive input") }
                    }
                }
            }
        }

        compose.onNodeWithText("Customer phone entry").assertExists()
        compose.runOnIdle { assertTrue(privacy.snapshot().blocked) }
        compose.onNodeWithText("Close sensitive input").performClick()
        compose.runOnIdle { assertTrue(!privacy.snapshot().blocked) }
    }

    @Test
    fun deniedNotificationPathExplainsRemediationAndExposesAction() {
        var remediationOpened = false
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceSettingsCard(
                    state = RemoteAssistanceUiState(
                        consent = RemoteConsentChoice.ALLOWED,
                        notificationReady = false,
                    ),
                    onStop = {},
                    onRevoke = {},
                    onEnableNotifications = { remediationOpened = true },
                )
            }
        }

        compose.onNodeWithText("Support cannot start", substring = true).assertIsDisplayed()
        compose.onNodeWithText("permission prompt or open", substring = true).assertExists()
        compose.onNodeWithTag("remote-assistance-enable-notifications").assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(remediationOpened) }
    }

    @Test
    fun pendingDeviceKeyShowsOneTimeGroupedPairingCode() {
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceSettingsCard(
                    state = RemoteAssistanceUiState(
                        deviceKeyStatus = RemoteDeviceKeyStatus.PENDING,
                        pairingCode = "01AB2CDE3FGH",
                        notificationReady = true,
                    ),
                    onStop = {},
                    onRevoke = {},
                    onEnableNotifications = {},
                )
            }
        }

        compose.onNodeWithTag("remote-device-pairing").assertIsDisplayed()
        compose.onNodeWithText("Pair this tablet once").assertIsDisplayed()
        compose.onNodeWithText("enter this code once", substring = true).assertExists()
        compose.onNodeWithText("current approved key stays active", substring = true).assertExists()
        compose.onNodeWithTag("remote-device-pairing-code").assertIsDisplayed()
        compose.onNodeWithText("01AB-2CDE-3FGH").assertIsDisplayed()
    }

    @Test
    fun activeDeviceKeyOffersNonDisruptiveReplacementPairing() {
        var replacementStarted = false
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceSettingsCard(
                    state = RemoteAssistanceUiState(
                        deviceKeyStatus = RemoteDeviceKeyStatus.ACTIVE,
                        notificationReady = true,
                    ),
                    onStop = {},
                    onRevoke = {},
                    onEnableNotifications = {},
                    onReplaceDeviceKey = { replacementStarted = true },
                )
            }
        }

        compose.onNodeWithText("current approved key active", substring = true).assertExists()
        compose.onNodeWithTag("remote-device-start-replacement").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(replacementStarted) }
    }

    private fun grant(kind: RemoteGrantKind) = RemoteGrantPrompt(
        grantId = "11111111-1111-4111-8111-111111111111",
        kind = kind,
        requesterName = "D Company owner",
        expiresAt = Instant.parse("2026-08-31T10:00:00Z"),
    )

    private fun activeSession() = RemoteActiveSession(
        sessionId = "22222222-2222-4222-8222-222222222222",
        grantId = "11111111-1111-4111-8111-111111111111",
        expiresAt = Instant.parse("2026-08-30T10:10:00Z"),
        deadlineElapsedMillis = 600_000L,
    )
}

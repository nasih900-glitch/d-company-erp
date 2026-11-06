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
import androidx.compose.ui.test.assertIsNotEnabled
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
                        sharingConfirmed = true,
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
    fun revokeConfirmationCannotCarryAcrossAReplacementGrant() = revokeTargetReplacement(false)

    @Test
    fun revokeConfirmationCannotCarryAcrossAReplacementAuthority() = revokeTargetReplacement(true)

    private fun revokeTargetReplacement(changeAuthority: Boolean) {
        val authority = cloud.dcompany.erp.core.handover.HandoverAuthority(
            cloud.dcompany.erp.core.handover.HandoverScope(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", null, null,
            ), 1,
        )
        val original = RemoteAssistanceUiState(
            consent = RemoteConsentChoice.ALLOWED, actionAuthority = authority,
            consentGrantId = "33333333-3333-4333-8333-333333333333",
        )
        var displayed by mutableStateOf(original)
        var revokeCalls = 0
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceSettingsCard(
                    state = displayed, onStop = {}, onEnableNotifications = {},
                    onRevoke = { revokeCalls++ },
                )
            }
        }
        compose.onNodeWithTag("remote-assistance-revoke").performClick()
        compose.onNodeWithTag("remote-assistance-confirm-revoke").assertExists()
        compose.runOnIdle {
            displayed = if (changeAuthority) original.copy(actionAuthority = authority.copy(epoch = 2))
                else original.copy(consentGrantId = "44444444-4444-4444-8444-444444444444")
        }
        compose.onNodeWithTag("remote-assistance-confirm-revoke").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, revokeCalls) }
        compose.onNodeWithTag("remote-assistance-revoke").performClick()
        compose.onNodeWithTag("remote-assistance-confirm-revoke").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, revokeCalls) }
    }

    @Test
    fun pausedSessionShowsResumeAndStopWithoutClaimingActiveSharing() {
        var resumed = 0
        var stopped = 0
        var revoked = 0
        var state by mutableStateOf(RemoteAssistanceUiState(consent = RemoteConsentChoice.ALLOWED,
            activeSession = activeSession(), sharingConfirmed = false, canResumeSharing = false))
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceSettingsCard(state, onStop = { stopped++ }, onRevoke = { revoked++ },
                    onEnableNotifications = {}, onResume = { resumed++ })
            }
        }
        compose.onNodeWithTag("remote-assistance-paused").assertExists()
        compose.onNodeWithTag("remote-assistance-resume").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(canResumeSharing = true) }
        compose.onNodeWithTag("remote-assistance-resume").performClick()
        compose.onNodeWithTag("remote-assistance-settings-stop").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, resumed); org.junit.Assert.assertEquals(1, stopped)
            state = state.copy(consent = RemoteConsentChoice.REVOKED, revokeSaveRetryRequired = true)
        }
        compose.onNodeWithText("Retry saving revoke").assertExists()
        compose.onNodeWithTag("remote-assistance-revoke").performClick()
        compose.onNodeWithTag("remote-assistance-confirm-revoke").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, revoked) }
    }

    @Test
    fun staleResumeClickRetainsItsDisplayedTargetAndCannotRenewReplacementSession() {
        val authority = cloud.dcompany.erp.core.handover.HandoverAuthority(
            cloud.dcompany.erp.core.handover.HandoverScope("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", null, null), 1)
        val original = cloud.dcompany.erp.core.handover.OriginalOperationBinding(authority,
            cloud.dcompany.erp.core.auth.CacheScopeLease(cloud.dcompany.erp.core.auth.CacheScope(authority.scope.actorId, authority.scope.companyId, null, null), 1),
            cloud.dcompany.erp.core.auth.SessionRefreshLease(cloud.dcompany.erp.core.auth.StoredSession("synthetic", "synthetic"), 1)) { true }
        val first = activeSession()
        var displayed by mutableStateOf(RemoteAssistanceUiState(consent = RemoteConsentChoice.ALLOWED,
            actionAuthority = authority, activeSession = first, consentGrantId = first.grantId,
            displayedSessionId = first.sessionId, canResumeSharing = true))
        var renewed = 0
        fun target(s: RemoteAssistanceUiState) = cloud.dcompany.erp.core.remote.RemoteUiTarget(authority, null,
            s.activeSession, null, s.consentGrantId, s.displayedSessionId)
        compose.setContent {
            val captured = requireNotNull(cloud.dcompany.erp.core.remote.captureRemoteUiAction(original, target(displayed), target(displayed)))
            DCompanyTheme {
                RemoteAssistanceSettingsCard(displayed, onStop = {}, onRevoke = {}, onEnableNotifications = {},
                    onResume = { if (captured.matches(original, target(displayed))) renewed++ })
            }
        }
        val oldClick = compose.onNodeWithTag("remote-assistance-resume").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action
        compose.runOnIdle {
            val replacement = first.copy(sessionId = "99999999-9999-4999-8999-999999999999")
            displayed = displayed.copy(activeSession = replacement, displayedSessionId = replacement.sessionId)
            oldClick?.invoke()
            org.junit.Assert.assertEquals(0, renewed)
        }
        compose.onNodeWithTag("remote-assistance-resume").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, renewed) }
    }

    @Test
    fun activeOfflineBannerExplainsPausedSharingAndKeepsStopAvailable() {
        var stopped = false
        compose.setContent {
            DCompanyTheme {
                RemoteAssistanceActiveBanner(
                    sharingConfirmed = true,
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

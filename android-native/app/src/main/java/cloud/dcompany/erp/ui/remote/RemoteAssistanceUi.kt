package cloud.dcompany.erp.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import cloud.dcompany.erp.core.remote.RemoteAssistanceUiState
import cloud.dcompany.erp.core.remote.RemoteCapturePrivacyController
import cloud.dcompany.erp.core.remote.RemoteConsentChoice
import cloud.dcompany.erp.core.remote.RemoteGrantPrompt
import cloud.dcompany.erp.core.remote.RemoteGrantKind
import cloud.dcompany.erp.core.remote.RemoteDeviceKeyStatus
import cloud.dcompany.erp.core.remote.groupedRemotePairingCode
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

internal val LocalRemoteCapturePrivacyController =
    compositionLocalOf<RemoteCapturePrivacyController?> { null }

/** Marks rendered content as uncapturable without changing what the staff member sees. */
@Composable
internal fun RemoteSensitiveContent(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val controller = LocalRemoteCapturePrivacyController.current
    DisposableEffect(controller, enabled) {
        val token = if (enabled) controller?.acquire() else null
        onDispose { token?.close() }
    }
    content()
}

@Composable
internal fun RemoteAssistanceConsentDialog(
    grant: RemoteGrantPrompt,
    busy: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("remote-assistance-consent"),
        onDismissRequest = {},
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        icon = {
            Icon(Icons.Filled.SupportAgent, contentDescription = null, tint = Brand.Gold)
        },
        title = {
            Text(
                if (grant.kind == RemoteGrantKind.ANYTIME) {
                    "Allow owner anytime ERP support?"
                } else {
                    "Allow one ERP support session?"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "${grant.requesterName} is asking for support access on this tablet.",
                    color = Brand.Foreground,
                )
                Text(
                    (if (grant.kind == RemoteGrantKind.ANYTIME) {
                        "If you allow it, the owner may start future short sessions until " +
                            "${remoteGrantExpiryLabel(grant)} (for up to 24 hours), or until you revoke it. "
                    } else {
                        "If you allow it, this request can start one short session only and expires at " +
                            "${remoteGrantExpiryLabel(grant)}. "
                    }) + "Every session is always visible. The owner can view only the Help screen, " +
                        "open or refresh Help, collect safe diagnostics, or end the session. Passwords, " +
                        "PINs, payments, voids, refunds and Shift close are hidden.",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "No taps, screen control, microphone, files or other apps are available. You can Stop a " +
                        "session immediately and revoke this choice later in Settings.",
                    color = Brand.ForegroundMuted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                enabled = !busy,
                modifier = Modifier.testTag("remote-assistance-allow"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Gold,
                    contentColor = Brand.Background,
                ),
            ) { Text(if (busy) "Saving…" else "Allow ERP support") }
        },
        dismissButton = {
            TextButton(
                onClick = onDeny,
                enabled = !busy,
                modifier = Modifier.testTag("remote-assistance-deny"),
            ) { Text("Deny") }
        },
    )
}

internal fun remoteGrantExpiryLabel(grant: RemoteGrantPrompt): String =
    REMOTE_GRANT_EXPIRY_FORMATTER.format(grant.expiresAt)

private val REMOTE_GRANT_EXPIRY_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.UK)
    .withZone(ZoneOffset.UTC)

@Composable
internal fun RemoteAssistanceActiveBanner(
    active: Boolean,
    online: Boolean,
    privacyProtected: Boolean,
    lastCommandLabel: String?,
    onStop: () -> Unit,
) {
    if (!active) return
    val statusDetail = when {
        !online -> "Sharing paused while offline. Owner can view Help only; other ERP screens " +
            "stay hidden. Stop remains available."
        privacyProtected -> "Owner can view Help only. This screen and other ERP screens are hidden."
        lastCommandLabel != null -> "$lastCommandLabel · Owner can view Help only; other ERP screens are hidden."
        else -> "Owner can view Help only; other ERP screens are hidden."
    }
    Surface(
        color = Brand.InformationMuted,
        border = BorderStroke(1.dp, Brand.Information.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("remote-assistance-active")
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = "Owner ERP remote assistance active. $statusDetail"
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                Modifier.size(34.dp).background(Brand.Information.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (privacyProtected) Icons.Filled.PrivacyTip else Icons.Filled.SupportAgent,
                    contentDescription = null,
                    tint = Brand.Information,
                    modifier = Modifier.size(21.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Owner ERP support active — Help only",
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    statusDetail,
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Button(
                onClick = onStop,
                modifier = Modifier
                    .testTag("remote-assistance-stop")
                    .semantics { role = Role.Button },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Danger,
                    contentColor = Brand.Foreground,
                ),
            ) {
                Icon(Icons.Filled.StopCircle, contentDescription = null, modifier = Modifier.size(19.dp))
                Text("Stop", modifier = Modifier.padding(start = Spacing.xs))
            }
        }
    }
}

/**
 * Passive workspace notice for a grant that is waiting for staff review.
 *
 * The shell renders this inline instead of presenting consent over an active
 * workflow. Staff explicitly move to Help before the consent dialog can open.
 */
@Composable
internal fun RemoteAssistanceRequestWaitingBanner(
    visible: Boolean,
    onReview: () -> Unit,
) {
    if (!visible) return
    Surface(
        color = Brand.InformationMuted,
        border = BorderStroke(1.dp, Brand.Information.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("remote-assistance-request-waiting")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription =
                    "Owner support request waiting. Open Help to review without interrupting this workflow."
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                Icons.Filled.SupportAgent,
                contentDescription = null,
                tint = Brand.Information,
                modifier = Modifier.size(21.dp),
            )
            Text(
                "Owner support request waiting — open Help to review",
                modifier = Modifier.weight(1f),
                color = Brand.Foreground,
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = onReview,
                modifier = Modifier
                    .testTag("remote-assistance-review-request")
                    .semantics { role = Role.Button },
            ) {
                Text("Open Help")
            }
        }
    }
}

@Composable
internal fun RemoteAssistanceSettingsCard(
    state: RemoteAssistanceUiState,
    onStop: () -> Unit,
    onRevoke: () -> Unit,
    onEnableNotifications: () -> Unit,
    onReplaceDeviceKey: () -> Unit = {},
) {
    var confirmRevoke by remember { mutableStateOf(false) }
    Surface(
        color = Brand.Surface,
        shape = Radius.shapeLg,
        border = BorderStroke(1.dp, Brand.BorderSubtle),
        modifier = Modifier.fillMaxWidth().testTag("remote-assistance-settings"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SupportAgent, contentDescription = null, tint = Brand.Gold)
                Column(Modifier.padding(start = Spacing.md)) {
                    Text("Remote assistance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state.consent) {
                            RemoteConsentChoice.ALLOWED ->
                                "ERP support is allowed for the approved request on this tablet."
                            RemoteConsentChoice.UNDECIDED -> "No owner support request has been reviewed yet."
                            RemoteConsentChoice.DENIED -> "Owner ERP support was denied on this tablet."
                            RemoteConsentChoice.REVOKED -> "Owner ERP support has been revoked on this tablet."
                        },
                        color = Brand.ForegroundMuted,
                    )
                }
            }
            if (state.deviceKeyStatus == RemoteDeviceKeyStatus.PENDING) {
                val groupedCode = state.pairingCode?.let(::groupedRemotePairingCode)
                if (groupedCode != null) {
                    Surface(
                        color = Brand.BackgroundSecondary,
                        shape = Radius.shapeMd,
                        border = BorderStroke(1.dp, Brand.BorderSubtle),
                        modifier = Modifier.fillMaxWidth().testTag("remote-device-pairing"),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text("Pair this tablet once", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Ask the owner to enter this code once in Remote Assistance Device Centre.",
                                color = Brand.ForegroundMuted,
                            )
                            Text(
                                "The current approved key stays active until the owner approves this replacement.",
                                color = Brand.ForegroundMuted,
                            )
                            Text(
                                groupedCode,
                                modifier = Modifier.testTag("remote-device-pairing-code"),
                                color = Brand.Gold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 24.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }
            }
            if (state.deviceKeyStatus == RemoteDeviceKeyStatus.ACTIVE) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        "Replacing the protected device key keeps the current approved key active while " +
                            "the owner verifies a new one-time code.",
                        color = Brand.ForegroundMuted,
                    )
                    TextButton(
                        onClick = onReplaceDeviceKey,
                        modifier = Modifier.testTag("remote-device-start-replacement"),
                    ) {
                        Text("Start replacement pairing")
                    }
                }
            }
            if (state.pendingGrant != null) {
                Text(
                    "An owner support request is waiting. Finish the current task, then open Help to review it.",
                    color = Brand.Warning,
                    modifier = Modifier.testTag("remote-assistance-settings-request-waiting"),
                )
            }
            if (state.consent == RemoteConsentChoice.ALLOWED && !state.notificationReady) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        "Support cannot start until Android notifications and the Remote assistance " +
                            "channel are enabled. Tap below; Android will show the permission prompt " +
                            "or open D Company ERP notification settings.",
                        color = Brand.Warning,
                    )
                    TextButton(
                        onClick = onEnableNotifications,
                        modifier = Modifier.testTag("remote-assistance-enable-notifications"),
                    ) {
                        Text("Enable notifications")
                    }
                }
            }
            state.statusMessage?.let { Text(it, color = Brand.ForegroundMuted) }
            if (state.activeSession != null) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.testTag("remote-assistance-settings-stop"),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text("Stop active session") }
            }
            if (state.consent == RemoteConsentChoice.ALLOWED) {
                TextButton(
                    onClick = { confirmRevoke = true },
                    enabled = !state.decisionInFlight,
                    modifier = Modifier.testTag("remote-assistance-revoke"),
                ) { Text("Revoke owner support", color = Brand.Danger) }
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Revoke owner ERP support?") },
            text = {
                Text(
                    "Any active session stops immediately. A later request will need a new explicit choice.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = false
                        onRevoke()
                    },
                    modifier = Modifier.testTag("remote-assistance-confirm-revoke"),
                ) { Text("Revoke access", color = Brand.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text("Keep allowed") }
            },
        )
    }
}

/** Rendered in device tests and mirrored by the generated JPEG placeholder. */
@Composable
internal fun RemotePrivacyPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .background(Brand.BackgroundSecondary, Radius.shapeLg)
            .testTag("remote-privacy-placeholder"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.PrivacyTip, contentDescription = null, tint = Brand.Gold)
            Text("Privacy protected", style = MaterialTheme.typography.titleMedium)
            Text("Sensitive ERP details are hidden", color = Brand.ForegroundMuted)
        }
    }
}

package cloud.dcompany.erp.core.remote

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistanceSecurityContractTest {

    @Test
    fun `manifest grants no capture accessibility audio or file authority`() {
        val manifest = read(projectRoot().resolve("src/main/AndroidManifest.xml"))

        for (forbidden in listOf(
            "BIND_ACCESSIBILITY_SERVICE",
            "RECORD_AUDIO",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
            "FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "android.media.projection",
        )) {
            assertFalse("Forbidden remote-assistance authority: $forbidden", forbidden in manifest)
        }
        assertTrue("Remote Stop receiver must not be externally callable", manifest.contains(
            "android:name=\".core.remote.RemoteAssistanceStopReceiver\"\n            android:exported=\"false\"",
        ))
    }

    @Test
    fun `remote implementation has no raw input or display capture primitives`() {
        val sourceRoot = projectRoot().resolve("src/main/java/cloud/dcompany/erp/core/remote")
        val source = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map(::read)
                .toList()
                .joinToString("\n")
        }

        for (forbidden in listOf(
            "import android.accessibilityservice",
            "import android.media.projection",
            "MediaProjectionManager(",
            "createVirtualDisplay(",
            "dispatchGesture(",
            "injectInputEvent(",
            "sendPointerSync(",
            "MotionEvent.obtain(",
            "AudioRecord(",
            "MediaRecorder(",
        )) {
            assertFalse("Forbidden remote primitive: $forbidden", forbidden in source)
        }
        assertTrue("Capture must stay bound to the ERP Activity Window", "PixelCopy.request(" in source)
        assertTrue("Protocol must expose a closed semantic command parser", "validateRemoteCommand(" in source)
    }

    @Test
    fun `Gaming defense in depth shields PII financial and free text surfaces`() {
        val gaming = read(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt"),
        )
        val startDialogCall = gaming.substringAfter("starting?.takeIf")
            .substringBefore("choosingAddonFor?.takeIf")
        val rejectedExtensionCall = gaming.substringAfter("discardingPackageExtension?.takeIf")
            .substringBefore("if (paymentQueueOpen)")
        val productConfigurationCall = gaming.substringAfter("configuringAddon?.takeIf")
            .substringBefore("voidingAddon?.takeIf")
        val paidExtensionCall = gaming.substringAfter("extendingPackage?.takeIf")
            .substringBefore("reconciling?.takeIf")
        val queueCalls = gaming.substringAfter("if (paymentQueueOpen)")
            .substringBefore("state.error?.let")
        val stationContent = gaming.substringAfter("private fun StationBody")
            .substringBefore("internal fun stationPresentation")

        assertTrue("Customer phone entry must activate the privacy shield", run {
            "RemoteSensitiveContent" in startDialogCall && "StartSessionDialog(" in startDialogCall
        })
        assertTrue("Rejected-extension reason entry must activate the privacy shield", run {
            "RemoteSensitiveContent" in rejectedExtensionCall &&
                "DiscardRejectedExtensionDialog(" in rejectedExtensionCall
        })
        assertTrue("Product notes must activate the privacy shield", run {
            "RemoteSensitiveContent" in productConfigurationCall &&
                "ProductConfigurationDialog(" in productConfigurationCall
        })
        assertTrue("Paid package extensions must activate the privacy shield", run {
            "RemoteSensitiveContent" in paidExtensionCall &&
                "PackageExtensionDialog(" in paidExtensionCall
        })
        assertTrue("Payment and cancellation queues must activate the privacy shield", run {
            queueCalls.countOccurrences("GamingQueueDialog(") == 2 &&
                queueCalls.countOccurrences("RemoteSensitiveContent") >= 2
        })
        assertTrue("Customer labels and unpaid bill cards must activate the privacy shield", run {
            stationContent.countOccurrences("RemoteSensitiveContent") >= 2 &&
                "sessionCustomerLabel(session)" in stationContent &&
                "StationVisualState.PaymentDue" in stationContent
        })
    }

    @Test
    fun `financial workflows cannot admit remote UI commands or interrupting consent`() {
        val activity = read(projectRoot().resolve("src/main/java/cloud/dcompany/erp/MainActivity.kt"))
        val coordinator = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/remote/RemoteAssistanceCoordinator.kt",
            ),
        )

        assertTrue("UI command host must use the centralized Help-only gate", run {
            "semanticActionsAllowed = {" in activity &&
                "remoteSemanticUiAdmission(" in activity &&
                "destinations.mapNotNull(Destination::remoteModule).toSet()" in activity
        })
        assertTrue("Consent must be latched only after the safe-route and privacy admission", run {
            "presentedRemoteGrantId" in activity &&
                "remotePrivacyBlocked" in activity &&
                "pendingRemoteGrant" in activity
        })
        assertTrue("Unsafe workflows must receive a passive Help cue, not an interrupting prompt", run {
            "remoteSupportRequestWaiting = remoteAssistanceState.pendingGrant != null" in activity &&
                "onReviewRemoteSupportRequest" in activity &&
                "currentDestination = Destination.Help" in activity
        })
        assertFalse("Remote refresh must never call SyncEngine or financial resources", run {
            "sync.refresh(" in coordinator
        })
        assertTrue("Diagnostics must pass the same Help-only UI gate before and after collection", run {
            coordinator.countOccurrences("uiGateway.semanticAdmission") >= 2 &&
                "remoteUiAdmissionRemainedStable" in coordinator
        })
        assertTrue("Global workflow dialogs on Help must activate the same privacy/command shield", run {
            val dialogs = activity.substringAfter("if (confirmSignOut)")
                .substringBefore("if (compatibilityState is ClientCompatibilityState.UpdateAvailable)")
            dialogs.countOccurrences("RemoteSensitiveContent") >= 7
        })
    }

    @Test
    fun `one revoke trigger makes one immediate transport attempt and keeps journal retry separate`() {
        val coordinator = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/remote/RemoteAssistanceCoordinator.kt",
            ),
        )
        val revokeTrigger = coordinator.substringAfter("fun revokeConsent()")
            .substringBefore("fun stopByUser()")
        assertTrue(
            "revokeConsent must call the mutation sender exactly once",
            revokeTrigger.countOccurrences("sendPendingRevocation()") == 1,
        )
        assertTrue("Revocation must retain the normal journal reconciliation path", run {
            coordinator.substringAfter("private suspend fun sendPendingMutations()")
                .substringBefore("private suspend fun sendGrantDecision")
                .countOccurrences("sendPendingRevocation()") == 1
        })
    }

    @Test
    fun `scope loss durably stops under retained old journal before dropping that scope`() {
        val coordinator = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/remote/RemoteAssistanceCoordinator.kt",
            ),
        )
        val unavailable = coordinator.substringAfter("fun onScopeUnavailable()")
            .substringBefore("fun onVisibleWorkspaceUnavailable()")

        assertTrue("The store must be bound to the retained verified journal, not the live cache lease", run {
            "RemoteAssistanceStore(appContext, currentScope = { journalScope })" in coordinator
        })
        assertTrue("Stop must be committed before the retained A journal is dropped", run {
            unavailable.indexOf("store.recordSessionEnd") in
                0 until unavailable.indexOf("journalScope = null")
        })
        assertTrue("Commands and capture must be canceled before the retained scope is dropped", run {
            unavailable.indexOf("clearActiveSession") in
                0 until unavailable.indexOf("journalScope = null")
        })
        assertTrue("Signed requests must additionally require the current authenticated cache scope", run {
            "remoteRequestScopeIsCurrent" in coordinator &&
                "bound != currentCacheJournalScope()" in coordinator
        })
    }

    @Test
    fun `active admission requires a posted persistent notification with Stop`() {
        val notification = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/remote/RemoteAssistanceNotification.kt",
            ),
        )

        assertTrue("Android 13 permission must be checked", "Manifest.permission.POST_NOTIFICATIONS" in notification)
        assertTrue("Global notification blocking must be checked", "areNotificationsEnabled()" in notification)
        assertTrue("Blocked channel importance must fail closed", "NotificationManager.IMPORTANCE_NONE" in notification)
        assertTrue("A notification must be proven posted", "activeNotifications?.any" in notification)
        assertTrue("Every capture admission must be able to prove the indicator remains posted", run {
            "isPersistentIndicatorPosted()" in notification
        })
        assertTrue("The active indicator must be persistent", ".setOngoing(true)" in notification)
        assertTrue("The notification must expose Stop", "\"Stop\"" in notification)
    }

    @Test
    fun `disabled remote notification warning has an actionable safe remediation path`() {
        val ui = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/ui/remote/RemoteAssistanceUi.kt",
            ),
        )
        val access = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/ui/remote/RemoteNotificationAccess.kt",
            ),
        )

        assertTrue("Settings must expose an enable-notifications action", run {
            "remote-assistance-enable-notifications" in ui &&
                "Enable notifications" in ui
        })
        assertTrue("A first Android 13 request must use the system permission contract", run {
            "ActivityResultContracts.RequestPermission()" in access &&
                "NotificationPermissionRequestStore.markRequested" in access
        })
        assertTrue("A blocked channel must route to the exact Remote assistance settings", run {
            "Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS" in access &&
                "REMOTE_ASSISTANCE_NOTIFICATION_CHANNEL_ID" in access
        })
        assertTrue("Settings failures must leave staff with a manual path", run {
            "Settings > Apps > D Company ERP > Notifications" in access
        })
    }

    @Test
    fun `remote client excludes pricing authority and supports active plus pending key rotation`() {
        val apiClient = read(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/core/net/ApiClient.kt"),
        )
        val coordinator = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/remote/RemoteAssistanceCoordinator.kt",
            ),
        )
        val remoteBuilder = apiClient.substringAfter("private fun remoteAuthenticatedClientBuilder")
            .substringBefore("private fun baseClientBuilder")
        val ordinaryFactory = apiClient.substringAfter("fun init(")
            .substringBefore("internal fun activateTerminalScope")
        val remoteFactory = apiClient.substringAfter("internal fun <T> createApiWithNetworkProof")
            .substringBefore("private fun authenticatedClientBuilder")

        assertTrue("The ordinary ERP Retrofit must retain pricing authority", run {
            "val client = authenticatedClientBuilder()" in ordinaryFactory &&
                "val client = remoteAuthenticatedClientBuilder()" !in ordinaryFactory
        })
        assertTrue("The proof client must use only the remote authority builder", run {
            "remoteAuthenticatedClientBuilder()" in remoteFactory
        })
        assertTrue("Remote support must actively strip the financial pricing capability", run {
            "PricingTokenInterceptor(allowPricingAuthority = false)" in remoteBuilder
        })
        assertTrue("Replacement pairing must retain the active signer while creating a candidate", run {
            "fun startDeviceKeyReplacement()" in coordinator &&
                "activeIdentity(" in coordinator.substringAfter("fun startDeviceKeyReplacement()")
                    .substringBefore("fun refreshNotificationReadiness()") &&
                "enrollmentCandidate(" in coordinator.substringAfter("fun startDeviceKeyReplacement()")
                    .substringBefore("fun refreshNotificationReadiness()")
        })
    }

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

    private fun read(path: Path): String = Files.newBufferedReader(path).use { it.readText() }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}

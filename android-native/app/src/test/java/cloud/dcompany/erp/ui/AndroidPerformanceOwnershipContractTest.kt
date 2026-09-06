package cloud.dcompany.erp.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPerformanceOwnershipContractTest {

    @Test
    fun `application owns reactive gaming alarm observation`() {
        val app = read("src/main/java/cloud/dcompany/erp/DCompanyApp.kt")
        val gaming = read(
            "src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt",
        )
        val session = read("src/main/java/cloud/dcompany/erp/ui/SessionViewModel.kt")
        val gamingReconciler = read(
            "src/main/java/cloud/dcompany/erp/core/alarm/GamingAlarmReconciler.kt",
        )
        val heldOrderReconciler = read(
            "src/main/java/cloud/dcompany/erp/core/alarm/HeldOrderAlarmReconciler.kt",
        )

        val appObserver = app.between(
            "private fun startAlarmReconciliation()",
            "private fun <T> kotlinx.coroutines.flow.Flow<T>.retryAlarmObservation",
        )
        val retryHelper = app.between(
            "private fun <T> kotlinx.coroutines.flow.Flow<T>.retryAlarmObservation",
            "private fun createAlarmChannel()",
        )
        val cachedActivation = session.between(
            "private suspend fun activateCachedSession",
            "private suspend fun refreshRestoredSession",
        )
        val validatedActivation = session.between(
            "private suspend fun activateValidatedScope",
            "private fun reconcileOperationalAlarms",
        )

        assertTrue(
            "DCompanyApp must react to Gaming Room changes",
            "observeSessionCache()" in appObserver,
        )
        assertTrue(
            "DCompanyApp must reconcile Gaming alarms",
            "GamingAlarmReconciler.reconcile" in appObserver,
        )
        assertTrue(
            "The sole reactive alarm observers must retry after non-cancellation failures",
            appObserver.countOccurrences(".retryAlarmObservation(") == 2 &&
                "cause is CancellationException" in retryHelper,
        )
        assertTrue(
            "A verified unchanged Room snapshot must be replayed after scope activation",
            appObserver.countOccurrences("combine(operationalAlarmReconciliationGeneration)") == 2 &&
                "requestOperationalAlarmReconciliation()" in app.between(
                    "internal fun onVerifiedScopeAvailable()",
                    "private suspend fun collectRemoteDiagnostics",
                ),
        )
        assertTrue(
            "A failed alarm-ledger commit must enter the retry path",
            appObserver.countOccurrences("alarm ledger could not be persisted") == 2,
        )
        assertTrue(
            "Alarm snapshots must include identity, transfer and label inputs with stable ordering",
            "GamingAlarmCacheFingerprint(" in appObserver &&
                "GamingAlarmLocalFingerprint(" in appObserver &&
                "stationId = it.stationId" in appObserver &&
                "serverId = it.serverId" in appObserver &&
                ".sortedBy { it.id }" in appObserver &&
                ".sortedBy { it.localId }" in appObserver,
        )
        assertTrue(
            "Blocking alarm platform work must run away from Main",
            "withContext(Dispatchers.IO)" in gamingReconciler &&
                "withContext(Dispatchers.IO)" in heldOrderReconciler,
        )
        assertFalse(
            "GamingViewModel must not collect UI state to reconcile alarms reactively",
            Regex("collect\\s*\\{[\\s\\S]{0,500}?GamingAlarmReconciler\\.reconcile")
                .containsMatchIn(gaming),
        )
        assertTrue(
            "Cached-scope activation must reconcile alarms before cached UI is trusted",
            "reconcileOperationalAlarms()" in cachedActivation,
        )
        assertTrue(
            "Validated-scope activation must reconcile alarms after scope replacement",
            "reconcileOperationalAlarms()" in validatedActivation,
        )
        assertTrue(
            "A purged workspace must withdraw old alarms off Main before B is armed",
            "withContext(Dispatchers.IO)" in validatedActivation &&
                "OperationalAlarmRegistry.cancelAll(getApplication())" in validatedActivation &&
                validatedActivation.indexOf("OperationalAlarmRegistry.cancelAll(getApplication())") <
                validatedActivation.lastIndexOf("reconcileOperationalAlarms()"),
        )
        assertTrue(
            "Feature scope changes must delegate alarm retries to the process owner",
            "requestOperationalAlarmReconciliation()" in session.between(
                "private fun reconcileOperationalAlarms",
                "private fun deactivateTerminalRuntime",
            ),
        )
    }

    @Test
    fun `support image preview decode cannot run during composition`() {
        val picker = read(
            "src/main/java/cloud/dcompany/erp/ui/screens/settings/BugReportAttachmentPicker.kt",
        )
        val dialog = read(
            "src/main/java/cloud/dcompany/erp/ui/screens/settings/BugReportDialog.kt",
        )

        val decoder = picker.between(
            "internal suspend fun decodeBugReportPreview",
            "private fun encodeWithinLimit",
        )
        assertTrue(
            "Preview decode must dispatch away from main",
            "withContext(Dispatchers.Default)" in decoder,
        )
        assertTrue(
            "Compose must load the preview through lifecycle-owned state",
            "LaunchedEffect(state.attachment.content)" in dialog &&
                "mutableStateOf<BugReportPreviewState>(BugReportPreviewState.Loading)" in dialog,
        )
        assertFalse(
            "Bitmap decoding must not execute synchronously in remember",
            Regex("remember\\([^)]*attachment\\.content[^)]*\\)\\s*\\{\\s*decodeBugReportPreview")
                .containsMatchIn(dialog),
        )
    }

    @Test
    fun `transient Gaming feedback overlays instead of reflowing the station board`() {
        val gaming = read("src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt")
        val board = gaming.between(
            "val gridHeaderCount =",
            "if (attentionCenterOpen)",
        )

        assertTrue(
            "Gaming success feedback must use a non-layout Snackbar host",
            "LaunchedEffect(state.notice)" in gaming && "SnackbarHost(" in board,
        )
        assertTrue(
            "Cancelled snackbar effects must consume only their own message",
            "finally" in gaming && "vm.dismissNotice(message)" in gaming,
        )
        assertTrue(
            "A serialized write must explain why the other station actions are paused",
            "GamingSavingOverlay(" in board &&
                "Other station actions are paused until this change is safely stored." in gaming,
        )
        assertFalse(
            "Success feedback must not insert a full-width board row",
            "Gaming action completed" in board || "if (state.busyStationId != null)" in board,
        )
        assertFalse(
            "Transient notice or busy state must not alter the grid header offset",
            "(if (state.notice != null)" in board ||
                "(if (state.busyStationId != null)" in board,
        )
    }

    private fun read(relativePath: String): String =
        Files.newBufferedReader(projectRoot().resolve(relativePath)).use { it.readText() }

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }

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

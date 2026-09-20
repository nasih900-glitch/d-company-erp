package cloud.dcompany.erp.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive wiring proof for operator-triggered POS/Gaming lease boundaries.
 * CacheScopeTest executes both sides of the lease-revocation primitive; these
 * contracts prove every financial/play action actually uses that primitive and
 * publishes a safe recovery result instead of silently returning.
 */
class WorkspaceLeaseFeedbackContractTest {

    @Test
    fun `gaming start and stop use no-write stale feedback boundary`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt")
        val start = source.between("    fun start(", "    fun stop(")
        val stop = source.between("    fun stop(", "    fun addSessionAddon(")

        assertFeedbackBoundary(start, "gamingStartWorkspaceUnavailableMessage")
        assertFeedbackBoundary(stop, "GAMING_STOP_WORKSPACE_UNAVAILABLE_MESSAGE")
    }

    @Test
    fun `every Gaming operator action reports missing and post-tap stale workspaces`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt")
        val localActions = listOf(
            source.between("    fun addSessionAddon(", "    fun voidSessionAddon("),
            source.between("    fun voidSessionAddon(", "    fun discardRejectedSessionAddonAction("),
            source.between("    private fun queueLocalPosSend(", "    private fun prepareCrossTerminalPosSend("),
            source.between("    fun extendWithPackage(", "    fun discardRejectedPackageExtension("),
        )
        val serverActions = listOf(
            source.between("    fun resolveLegacyPackageStart(", "    fun sendToPos("),
            source.between("    private fun prepareCrossTerminalPosSend(", "    fun handoffToPos("),
            source.between("    fun handoffToPos(", "    fun dismissPosTargetSelection("),
            source.between("    fun discardRejectedPackageExtension(", "    fun pauseSession("),
            source.between("    fun repairMissingBilling(", "    private fun runDirectSessionMutation("),
            source.between("    fun reconcileToPos(", "    fun cancelUnbilled("),
            source.between("    fun cancelUnbilled(", "    fun dismissError("),
        )

        localActions.forEach(::assertGamingOperatorBoundary)
        serverActions.forEach { action ->
            assertGamingOperatorBoundary(action)
            assertTrue(
                "A server response rejected by the captured lease must explain its ambiguous outcome",
                "gamingServerResultWorkspaceUnavailableMessage" in action,
            )
        }

        val rejectedAddon = source.between(
            "    fun discardRejectedSessionAddonAction(",
            "    fun resolveLegacyPackageStart(",
        )
        assertTrue("Rejected add-on review must explain an absent lease", "scopeLeaseOrError(workspaceMessage)" in rejectedAddon)
        assertTrue("Rejected add-on review must distinguish a revoked lease", "ScopedCommitResult.Stale" in rejectedAddon)
        assertTrue("Rejected add-on review must publish its recovery message", "error.value = workspaceMessage" in rejectedAddon)

        val directMutation = source.between(
            "    private fun runDirectSessionMutation(",
            "    private suspend fun storeRunningResponse(",
        )
        val responseStore = source.between(
            "    private suspend fun storeRunningResponse(",
            "    fun reconcileToPos(",
        )
        assertTrue("Pause, resume, timer and transfer must explain a missing lease", "error.value = \"This account session changed." in directMutation)
        assertTrue("Their server response must provide stale feedback", "gamingServerResultWorkspaceUnavailableMessage" in directMutation)
        assertTrue("Their response store must notify rather than silently discard", "commitIfCurrentOrNotifyStale" in responseStore)

        assertFalse(
            "No Gaming operator path may retain a raw silent current-lease return",
            "currentLease() ?: return" in source,
        )
        assertFalse(
            "All operator Unit commits must use the notifying stale boundary",
            "commitIfCurrent(scopeLease)" in source,
        )
        assertTrue(
            "Only init recovery, the feedback helper, Start, Stop and the shared direct mutation may capture a raw lease",
            source.countOccurrences("cacheIsolation.currentLease()") == 5,
        )
    }

    @Test
    fun `direct online offline and held payments use no-write stale feedback boundary`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val online = source.between("    fun captureSale(", "    private fun captureOfflineSale(")
        val offline = source.between("    private fun captureOfflineSale(", "    fun retryRejectedSale(")
        val held = source.between("    fun confirmHeldOrderPayment(", "    fun confirmHeldOrderZero(")

        listOf(online, offline, held).forEach { boundary ->
            assertFeedbackBoundary(boundary, "POS_PAYMENT_WORKSPACE_UNAVAILABLE_MESSAGE")
        }
    }

    @Test
    fun `zero completions and rejected-payment retries explain both missing and stale leases`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val directZero = source.between("    fun confirmDirectZero(", "    fun voidOrder(")
        val heldZero = source.between("    fun confirmHeldOrderZero(", "    private suspend fun refreshHeldOrdersBestEffort(")
        val directRetry = source.between("    fun retryRejectedSale(", "    private fun watchCapturedSaleOutcome(")
        val heldRetry = source.between("    fun retryRejectedHeldPayment(", "    private fun watchCapturedHeldPaymentOutcome(")

        for (zero in listOf(directZero, heldZero)) {
            assertTrue("Missing lease must show zero-completion recovery", "scopeLeaseOrNotice(POS_ZERO_WORKSPACE_UNAVAILABLE_MESSAGE)" in zero)
            assertTrue("A lease revoked after tap must stop before finalization", "commitIfCurrentOrNotifyStale" in zero)
            assertTrue("Stale zero completion must tell staff to collect no money", "POS_ZERO_WORKSPACE_UNAVAILABLE_MESSAGE" in zero)
        }
        assertRetryCleanup(directRetry, "retryingRejectedSaleIds")
        assertRetryCleanup(heldRetry, "retryingHeldPaymentIds")
    }

    @Test
    fun `POS money and destructive actions have no raw silent current-lease return`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        assertFalse(
            "Every user-triggered scope failure must publish an actionable notice",
            "cacheIsolation.currentLease() ?: return" in source,
        )
    }

    @Test
    fun `direct checkout transition and server checkpoint cannot exit without feedback`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val prepare = source.between(
            "    private fun prepareDirectCheckout(",
            "    private suspend fun publishDirectCheckoutAndPersist(",
        )
        val transition = prepare.between("val transitioned =", "val local =")
        val serverCheckpoint = prepare.between("val checkpointed =", "val priced =")

        assertTrue("A revoked cart-transition lease must explain why checkout stopped", "POS_CHECKOUT_WORKSPACE_UNAVAILABLE_MESSAGE" in transition)
        assertTrue("A revoked post-server lease must explain that a server bill may exist", "!is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed" in serverCheckpoint)
        assertTrue("The post-server stale path must use its financially safe recovery message", "POS_CHECKOUT_RESULT_WORKSPACE_UNAVAILABLE_MESSAGE" in serverCheckpoint)
        assertTrue("A cart CAS miss must also say that no payment was recorded", "no payment was recorded" in serverCheckpoint)

        val failureRecovery = prepare.substringAfter("} catch (e: Exception) {")
        assertTrue("Checkout failure recovery must retain the exact tapped cart identity", "checkoutLocalId = initialLocal.localId" in prepare)
        assertTrue("Checkout failure recovery must re-enter the captured lease", "commitIfCurrentOrNotifyStale" in failureRecovery)
        assertTrue("Checkout failure recovery must address only the captured cart", "withLines(localId)" in failureRecovery)
        assertFalse("Checkout failure recovery must not mutate a new workspace's active cart", "activeDraft()" in failureRecovery)
        assertTrue("Stale recovery must explain the possibly-created server bill", "POS_CHECKOUT_RESULT_WORKSPACE_UNAVAILABLE_MESSAGE" in failureRecovery)
    }

    @Test
    fun `void exits before POST unless the selected checkout was closed safely`() {
        val source = read("src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val void = source.between("    fun voidOrder(", "    fun captureSale(")
        val beforePost = void.substringBefore("ApiClient.api.voidOrder(")
        val afterPost = void.substringAfter("ApiClient.api.voidOrder(")

        assertTrue("A stale direct bearer must show workspace recovery", "POS_VOID_BEARER_WORKSPACE_UNAVAILABLE_MESSAGE" in beforePost)
        assertTrue("A direct bearer CAS miss must say no request was sent", "No void request was sent" in beforePost)
        assertTrue("A held selection CAS miss must say no request was sent", beforePost.countOccurrences("No void request was sent") >= 2)
        assertTrue("Both direct and held failure branches must return before POST", beforePost.countOccurrences("return@launch") >= 3)
        assertTrue("Successful direct or held closure must be recorded before POST", beforePost.countOccurrences("checkoutClosedSafely = true") == 2)
        assertTrue("Post-removal API failure must use outcome-aware wording", "posVoidFailureNotice(" in afterPost)
    }

    private fun assertRetryCleanup(source: String, setName: String) {
        assertTrue("Missing retry lease must explain that the old payment remains safe", "scopeLeaseOrNotice(POS_RETRY_WORKSPACE_UNAVAILABLE_MESSAGE)" in source)
        assertTrue("A retry lease revoked after tap must show the same warning", "commitIfCurrentOrNotifyStale" in source)
        assertTrue("Retry busy state must be released from a finally block", "finally {" in source)
        assertTrue("Retry busy state must always remove the exact id", "$setName.update { it - localId }" in source.substringAfter("finally {"))
        assertTrue("Busy cleanup must have one owner", source.countOccurrences("$setName.update { it - localId }") == 1)
    }

    private fun assertFeedbackBoundary(source: String, feedbackName: String) {
        assertFalse("Missing leases must not return silently", "currentLease() ?: return" in source)
        assertTrue("The guarded commit must report a lease revoked after tap", "commitIfCurrentOrNotifyStale" in source)
        assertTrue("The exact recovery feedback must cover both lease boundaries", source.countOccurrences(feedbackName) >= 2)
    }

    private fun assertGamingOperatorBoundary(source: String) {
        assertTrue("A missing Gaming lease must publish feedback", "scopeLeaseOrError(" in source)
        assertTrue("A post-tap revoked Gaming lease must publish feedback", "commitIfCurrentOrNotifyStale" in source)
        assertTrue("Gaming recovery must tell staff to sign in again", "workspaceMessage" in source)
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
            ?: error("Could not locate the Android app module")
    }
}

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
            source.between("    fun reconcileToPos(", "    fun prepareSessionVoid("),
        )

        localActions.forEach(::assertGamingOperatorBoundary)
        serverActions.forEach { action ->
            assertGamingOperatorBoundary(action)
            assertTrue(
                "A server response rejected by the captured lease must explain its ambiguous outcome",
                "gamingServerResultWorkspaceUnavailableMessage" in action,
            )
        }
        val voidActions = source.between("    fun prepareSessionVoid(", "    fun dismissError(")
        val prepareUnlinkedVoid = voidActions.between(
            "    fun prepareSessionVoid(",
            "    private suspend fun loadSessionVoidReview(",
        )
        val confirmUnlinkedVoid = voidActions.between(
            "    fun confirmSessionVoid(",
            "    private suspend fun executeSessionVoid(",
        )
        val executeUnlinkedVoid = voidActions.between(
            "    private suspend fun executeSessionVoid(",
            "    private fun prepareLinkedGamingVoid(",
        )
        val prepareLinkedVoid = voidActions.between(
            "    private fun prepareLinkedGamingVoid(",
            "    private suspend fun loadLinkedGamingVoidReview(",
        )
        val confirmLinkedVoid = voidActions.between(
            "    fun confirmLinkedGamingVoid(",
            "    private suspend fun executeLinkedGamingVoid(",
        )
        val executeLinkedVoid = voidActions.between(
            "    private suspend fun executeLinkedGamingVoid(",
            "    private suspend fun loadLinkedGamingVoidResult(",
        )
        val unlinkedVoidExecutor = source.between(
            "internal suspend fun executeSessionVoidAttempt(",
            "internal suspend fun loadVerifiedSessionVoidResult(",
        )
        val linkedVoidExecutor = source.between(
            "internal suspend fun executeLinkedGamingVoidAttempt(",
            "internal suspend fun loadVerifiedLinkedGamingVoidResult(",
        )
        assertTrue(
            "Each Void review must capture a lease before authenticated work",
            listOf(prepareUnlinkedVoid, prepareLinkedVoid).all { "scopeLeaseOrError(" in it },
        )
        assertTrue(
            "Each Void confirmation must bind the review and confirmation leases",
            listOf(confirmUnlinkedVoid, confirmLinkedVoid).all { "voidActionLeaseError(" in it },
        )
        assertTrue(
            "Each Void success publication must remain guarded by its captured lease",
            listOf(confirmUnlinkedVoid, confirmLinkedVoid).all {
                "commitIfCurrentOrNotifyStale(attempt.scopeLease" in it
            },
        )
        assertTrue(
            "Each Void failure must remain scoped to the captured account instead of publishing into a replacement account",
            listOf(confirmUnlinkedVoid, confirmLinkedVoid).all {
                "catch (failure: ApiException)" in it &&
                    "catch (failure: Exception)" in it &&
                    "commitIfCurrent(attempt.scopeLease)" in it
            },
        )
        assertTrue(
            "Each private Void wrapper must delegate its captured lease to the matching guarded executor",
            "executeSessionVoidAttempt(" in executeUnlinkedVoid &&
                "currentLease = appCtx.cacheIsolation::currentLease" in executeUnlinkedVoid &&
                "executeLinkedGamingVoidAttempt(" in executeLinkedVoid &&
                "currentLease = appCtx.cacheIsolation::currentLease" in executeLinkedVoid,
        )
        assertTrue(
            "Each Void executor must guard the captured lease before the canonical read, after it, and before dispatch",
            listOf(
                unlinkedVoidExecutor to listOf(
                    "The workspace changed before Void.",
                    "The workspace changed while checking Void.",
                    "The workspace changed before Void dispatch.",
                ),
                linkedVoidExecutor to listOf(
                    "The workspace changed before Owner Void.",
                    "The workspace changed while checking the Owner Void receipt.",
                    "The workspace changed before Owner Void dispatch.",
                ),
            ).all { (executor, guards) ->
                guards.all { guard -> guard in executor } &&
                    "currentLease() == attempt.scopeLease" in executor
            },
        )
        assertTrue(
            "Each Void confirmation must recheck the captured lease after the server response before reading or publishing it",
            listOf(confirmUnlinkedVoid, confirmLinkedVoid).all {
                "check(appCtx.cacheIsolation.currentLease() == attempt.scopeLease)" in it
            },
        )

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
        val capturedStart = source.between("    fun start(", "    fun stop(")
        val savedStartAcknowledgement =
            "if (appCtx.cacheIsolation.currentLease() == scopeLease) onCaptured(localSessionId)"
        assertTrue(
            "The saved-Start acknowledgement must recheck its captured lease before clearing a customer draft",
            savedStartAcknowledgement in capturedStart,
        )
        val leaseHelper = source.between(
            "    private fun scopeLeaseOrError(",
            "    private fun requireNoRejectedSequencedAction(",
        )
        assertTrue(
            "The shared Gaming lease capture must publish the supplied recovery message when no workspace exists",
            "if (lease == null) error.value = message" in leaseHelper,
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
        val directClosure = beforePost.between("                if (direct != null) {", "                if (held != null) {")
        val heldClosure = beforePost.substringAfter("                if (held != null) {")

        assertTrue("A stale direct bearer must show workspace recovery", "POS_VOID_BEARER_WORKSPACE_UNAVAILABLE_MESSAGE" in directClosure)
        assertTrue("A direct bearer CAS miss must explain that no request was sent and return before POST", "No void request was sent" in directClosure && "return@launch" in directClosure)
        assertTrue("A held selection CAS miss must explain that no request was sent and return before POST", "No void request was sent" in heldClosure && "return@launch" in heldClosure)
        assertTrue("A successful direct closure must be recorded before POST", "checkoutClosedSafely = true" in directClosure)
        assertTrue("A successful held closure must be recorded before POST", "checkoutClosedSafely = true" in heldClosure)
        assertTrue("Post-removal API failure must use outcome-aware wording", "posVoidFailureNotice(" in afterPost)
    }

    private fun assertRetryCleanup(source: String, setName: String) {
        val beforeLaunch = source.substringBefore("viewModelScope.launch")
        val launchBody = source.substringAfter("viewModelScope.launch")
        val beforeFinally = launchBody.substringBefore("finally {")
        val finallyBody = launchBody.substringAfter("finally {")
        assertTrue("Missing retry lease must explain that the old payment remains safe", "scopeLeaseOrNotice(POS_RETRY_WORKSPACE_UNAVAILABLE_MESSAGE)" in source)
        assertTrue("A retry lease revoked after tap must show the same warning", "commitIfCurrentOrNotifyStale" in source)
        assertTrue("Retry busy state must be released from a finally block", "finally {" in source)
        assertTrue("Retry busy ownership must be acquired before launching work", "$setName.update { it + localId }" in beforeLaunch)
        assertTrue("Retry busy state must not be released before the finally block", "$setName.update { it - localId }" !in beforeFinally)
        assertTrue("Retry busy state must always release the exact id from the finally block", "$setName.update { it - localId }" in finallyBody)
        assertTrue("Retry busy ownership must not be reacquired during cleanup", "$setName.update { it + localId }" !in finallyBody)
    }

    private fun assertFeedbackBoundary(source: String, feedbackName: String) {
        assertFalse("Missing leases must not return silently", "currentLease() ?: return" in source)
        val missingLeaseReportsFeedback =
            "scopeLeaseOrNotice($feedbackName)" in source ||
                ("if (scopeLease == null)" in source &&
                    feedbackName in source.substringAfter("if (scopeLease == null)").substringBefore("}"))
        assertTrue("The guarded commit must report a lease revoked after tap", "commitIfCurrentOrNotifyStale" in source)
        assertTrue("The exact recovery feedback must cover a lease missing before work begins", missingLeaseReportsFeedback)
        assertTrue("The exact recovery feedback must cover a lease revoked after work begins", feedbackName in source.substringAfter("onStale"))
    }

    private fun assertGamingOperatorBoundary(source: String) {
        assertTrue("A missing Gaming lease must publish feedback", "scopeLeaseOrError(" in source)
        assertTrue("A post-tap revoked Gaming lease must publish feedback", "commitIfCurrentOrNotifyStale" in source)
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

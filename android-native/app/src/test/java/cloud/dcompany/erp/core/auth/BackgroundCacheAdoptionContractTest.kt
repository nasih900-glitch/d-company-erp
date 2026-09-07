package cloud.dcompany.erp.core.auth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundCacheAdoptionContractTest {

    @Test
    fun `background adoption is atomic and never revokes an active foreground lease`() {
        val source = read("src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt")
        val adoption = source.between(
            "    internal suspend fun adoptCachedOnlyIfInactive(",
            "    /** A server-validated scope may replace another scope",
        )

        assertTrue("The active-lease decision must run under the scope mutex", "mutex.withLock" in adoption)
        assertTrue("An existing exact lease must be returned without replacement", "CachedScopeLeaseAdoption.Ready(lease = active, adopted = false)" in adoption)
        assertTrue("An active mismatched lease must stay distinguishable from no owner", "active.scope != scope" in adoption && "CachedScopeLeaseAdoption.ActiveScopeConflict" in adoption)
        assertTrue("Only an inactive exact marker may create a lease", "marker.current()" in adoption && "activeLease = lease" in adoption)
        assertFalse("Background adoption must never revoke another lease", "deactivateLocked()" in adoption)
    }

    @Test
    fun `alarm recovery owns and revokes only the lease it adopted`() {
        val completeSource = read(
            "src/main/java/cloud/dcompany/erp/core/alarm/OperationalAlarmRuntime.kt",
        )
        val source = completeSource.substringAfter("    suspend fun ensureActiveOwnedScope(")

        assertTrue("Alarm recovery must use non-replacing cached adoption", "adoptCachedOnlyIfInactive(expected)" in source)
        assertTrue("Alarm conflict must propagate into retry rather than no-owner cancellation", "CachedScopeLeaseAdoption.ActiveScopeConflict" in completeSource && "throw IllegalStateException" in completeSource)
        assertTrue("Only stored marker mismatch may return definitive no owner", "CachedScopeLeaseAdoption.StoredScopeMismatch -> null" in completeSource)
        assertTrue("Alarm recovery must retain only its newly adopted lease", "val adoptedLease = adoption.adoptedLease" in source)
        assertTrue("Token loss must use exact-lease revocation", "adoptedLease?.let { app.cacheIsolation.deactivateIfCurrent(it) }" in source)
        assertTrue("Success must still own the exact lease generation", "currentLease() == adoption.lease" in source)
        assertFalse("Alarm recovery must never unconditionally deactivate a foreground workspace", "cacheIsolation.deactivate()" in source)
        // StoredScopeMismatch is allowed to return false on the adoption line;
        // only ownership loss after a Ready adoption must stay retryable.
        val afterAdoption = source.substringAfter("val adoptedLease = adoption.adoptedLease")
        assertTrue(
            "Post-adoption lineage loss must enter retry/preserve handling",
            "requireOperationalAlarmOwnershipAfterAdoption(" in afterAdoption,
        )
        assertFalse(
            "Post-adoption ownership loss must never report definitive no owner",
            "return false" in afterAdoption,
        )

        val receiver = read("src/main/java/cloud/dcompany/erp/core/alarm/AlarmReceiver.kt")
        val directNoOwner = receiver.substringAfter(
            "OperationalAlarmRegistry.cancelAll(context)",
        ).substringBefore("return@launch")
        assertTrue(
            "Direct alarm cancellation must replay the latest foreground scope",
            "requestOperationalAlarmReconciliation()" in directNoOwner,
        )
        assertTrue(
            "Direct alarm cleanup failure must schedule a bounded retry",
            "if (!cancelled)" in directNoOwner &&
                "retryAfterReceiverFailure(context, identity)" in directNoOwner,
        )

        val reschedule = read(
            "src/main/java/cloud/dcompany/erp/core/alarm/AlarmRescheduleWorker.kt",
        )
        val rescheduleNoOwner = reschedule.substringAfter("if (!ensureActiveOwnedScope()) {")
            .substringBefore("} else {")
        assertTrue(
            "Lifecycle alarm cancellation must replay the latest foreground scope",
            "requestLatestScopeReconciliation()" in rescheduleNoOwner,
        )
    }

    @Test
    fun `background sync adoption and terminal publication are exact-lease guarded`() {
        val source = read(
            "src/main/java/cloud/dcompany/erp/core/sync/BackgroundSyncWorker.kt",
        )
        val prepare = source.between(
            "    private suspend fun prepareVerifiedScope(",
            "    private companion object",
        )

        assertTrue("Worker must use non-replacing cached adoption", "adoptCachedOnlyIfInactive(expectedScope)" in prepare)
        assertFalse("Worker must not use the replacing cached activation API", "activateCachedWithLease" in prepare)
        assertTrue("Worker must track only the lease it created", "val workerActivatedLease = adoption.adoptedLease" in prepare)
        assertTrue("Token or terminal failure must release only that adopted lease", prepare.countOccurrences("releaseWorkerAdoptedScope(app, workerActivatedLease)") >= 3)
        assertTrue("Cached terminal display activation must be exact-lease guarded", "commitResultIfCurrent(adoption.lease)" in prepare && "activateCachedValidated(" in prepare)
        assertTrue("Terminal header publication must be guarded by the exact adopted/borrowed lease", "commitIfCurrent(adoption.lease)" in prepare)

        val release = prepare.substringAfter("    private suspend fun releaseWorkerAdoptedScope(")
        assertTrue(
            "Worker cleanup and process-global authority clearing must share the scope mutex",
            "deactivateIfCurrentWithCleanup(adoptedLease)" in release,
        )
        val guardedCleanup = release.substringAfter("deactivateIfCurrentWithCleanup(adoptedLease) {")
            .substringBefore("            }")
        assertTrue(
            "Runtime terminal authority may clear only inside exact-lease cleanup",
            "ApiClient.deactivateTerminalScope()" in guardedCleanup &&
                "deactivateValidatedDisplay()" in guardedCleanup,
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
        var cursor = Paths.get("").toAbsolutePath().normalize()
        repeat(8) {
            val direct = cursor.resolve("src/main/AndroidManifest.xml")
            if (Files.exists(direct)) return cursor
            val nested = cursor.resolve("app/src/main/AndroidManifest.xml")
            if (Files.exists(nested)) return cursor.resolve("app")
            val repositoryNested = cursor.resolve("android-native/app/src/main/AndroidManifest.xml")
            if (Files.exists(repositoryNested)) return cursor.resolve("android-native/app")
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate Android app root from ${Paths.get("").toAbsolutePath()}")
    }
}

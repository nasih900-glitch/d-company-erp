package cloud.dcompany.erp.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the durable hand-off boundary.
 *
 * These assertions intentionally inspect the small orchestration functions,
 * not DTO formatting. A Room capture followed only by an in-process coroutine
 * works until Android kills the process; every supported offline workflow must
 * reach SyncEngine's WorkManager-scheduling entry point after its local commit.
 */
class OutboxSchedulingCoverageTest {
    private val sourceRoot = mainSourceRoot()

    @Test
    fun `all SyncEngine entry points schedule the durable worker`() {
        val source = source("cloud/dcompany/erp/core/sync/SyncEngine.kt")

        assertContainsAll(
            source.between("fun requestSync()", "fun requestKitchenSync()"),
            "scheduleDurableSync()",
            "syncRequests.request",
        )
        assertContainsAll(
            source.between("fun requestKitchenSync()", "suspend fun sync()"),
            "scheduleDurableSync()",
            "kitchenSyncRequests.request",
        )
        assertContainsAll(
            source.between("private suspend fun syncInternal", "suspend fun verifyAndClearRejectedShiftOpen"),
            "if (scheduleForProcessDeath) scheduleDurableSync()",
        )
    }

    @Test
    fun `POS money capture schedules the original durable settlement`() {
        val source = source("cloud/dcompany/erp/ui/screens/PosViewModel.kt")

        assertContainsAll(
            source.between("fun captureSale(", "private fun captureOfflineSale("),
            "insertPayment(payment)",
            "requestHeldPaymentSyncAfterActivePass()",
        )
        assertContainsAll(
            source.between("private fun captureOfflineSale(", "fun retryRejectedSale("),
            "captureOfflineDraft(",
            "app.sync.requestSync()",
        )
        assertContainsAll(
            source.between("private fun requestHeldPaymentSyncAfterActivePass()", "private suspend fun releaseClaimBestEffort"),
            "app.sync.sync()",
        )
    }

    @Test
    fun `gaming capture paths schedule start stop send and paid extension`() {
        val source = source("cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt")

        assertContainsAll(
            source.between("fun start(", "fun stop("),
            "insertStartIfStationAvailable(",
            "appCtx.sync.requestSync()",
        )
        assertContainsAll(
            source.between("fun stop(", "fun resolveLegacyPackageStart("),
            "requestSessionStop(",
            "insertLocalSession(",
            "appCtx.sync.requestSync()",
        )
        assertContainsAll(
            source.between("fun sendToPos(", "fun extendTimer("),
            "requestSessionSend(",
            "insertLocalSession(",
            "appCtx.sync.requestSync()",
        )
        assertContainsAll(
            source.between("fun extendWithPackage(", "fun discardRejectedPackageExtension("),
            "capturePackageExtension(",
            "appCtx.sync.requestSync()",
        )
    }

    @Test
    fun `customer shift and inventory local commits schedule replay`() {
        val customers = source("cloud/dcompany/erp/ui/screens/customers/CustomersViewModel.kt")
        assertContainsAll(
            customers.between("fun save()", "private fun mergeCustomers("),
            "dao.upsertLocal(",
            "appCtx.sync.requestSync()",
        )

        val shifts = source("cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt")
        assertContainsAll(
            shifts.between("fun openShift(", "fun closeShift("),
            "db.shiftDao().insert(",
            "app.sync.requestSync()",
        )
        assertContainsAll(
            shifts.between("fun closeShift(", "fun retryClose("),
            "captureExistingClose(",
            "captureAdoptedClose(",
            "app.sync.requestSync()",
        )

        val inventory = source("cloud/dcompany/erp/ui/screens/inventory/InventoryViewModel.kt")
        assertContainsAll(
            inventory.between("fun saveIngredient(", "fun deleteIngredient("),
            "localMutate",
            "upsertLocalIngredient(",
        )
        assertContainsAll(
            inventory.between("fun saveSupplier(", "fun deleteSupplier("),
            "localMutate",
            "upsertLocalSupplier(",
        )
        assertContainsAll(
            inventory.between("fun postGrn(", "fun retryGrn("),
            "localMutate",
            "captureGrn(",
        )
        assertContainsAll(
            inventory.between("fun postAdjustment(", "fun retryAdjustment("),
            "localMutate",
            "insertAdjustment(",
        )
        assertContainsAll(
            inventory.between("private fun localMutate", "private fun queuedNotice"),
            "commitIfCurrent",
            "appCtx.sync.requestSync()",
        )
    }

    private fun source(relative: String): String =
        Files.newBufferedReader(sourceRoot.resolve(relative)).use { it.readText() }
            .replace(Regex("\\s+"), " ")

    private fun assertContainsAll(section: String, vararg markers: String) {
        markers.forEach { marker ->
            assertTrue("Missing scheduling coverage marker: $marker", section.contains(marker))
        }
    }

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull(Files::isDirectory)?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root from ${Paths.get("").toAbsolutePath()}")
    }
}

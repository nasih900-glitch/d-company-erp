package cloud.dcompany.erp.ui.screens.finance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceRefreshArchitectureTest {

    @Test
    fun `finance screen observes serialized Room snapshots and scoped refresh feedback`() {
        val source = readSource(
            mainSourceRoot().resolve("cloud/dcompany/erp/ui/screens/finance/FinanceViewModel.kt"),
        )

        assertFalse(
            "FinanceViewModel must not bypass SyncEngine with direct Finance API reads",
            "ApiClient.create<FinanceApi>()" in source,
        )
        assertTrue("Finance snapshots are not Room-observed", "observeSnapshot<ProfitAndLoss>" in source)
        assertTrue(
            "Manual collections are not delivered through scoped Room snapshots",
            "observeSnapshot<List<ManualCollection>>(FinanceSnapshotKeys.MANUAL_COLLECTIONS)" in source,
        )
        assertTrue(
            "Tip payouts are not delivered through scoped Room snapshots",
            "observeSnapshot<List<TipPayout>>(FinanceSnapshotKeys.TIP_PAYOUTS)" in source,
        )
        assertTrue("Finance manual refresh bypasses SyncEngine", "appCtx.sync.refresh(\"finance\")" in source)
        assertTrue(
            "Finance realtime/manual failures are not visible in screen state",
            "resourceRefreshErrors" in source && "it[\"finance\"]" in source,
        )
        assertTrue(
            "Finance forms do not fail closed when required references are absent",
            "branches.value.isEmpty() || categoryNames.value.isEmpty()" in source,
        )

        val sync = readSource(
            mainSourceRoot().resolve("cloud/dcompany/erp/core/sync/SyncEngine.kt"),
        )
        assertTrue(
            "Partial Finance reference failures are still reported as a refresh failure",
            "throw FinanceReferenceRefreshException(missingReferences)" in sync,
        )
        assertTrue("Sync does not fetch manual collections", "financeApi.manualCollections()" in sync)
        assertTrue("Sync does not fetch tip payouts", "financeApi.tipPayouts()" in sync)
        assertTrue("Sync does not fetch Tips Payable", "financeApi.trialBalance()" in sync)
    }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root from ${Paths.get("").toAbsolutePath()}")
    }

    private fun readSource(path: Path): String = Files.newBufferedReader(path).use { it.readText() }
}

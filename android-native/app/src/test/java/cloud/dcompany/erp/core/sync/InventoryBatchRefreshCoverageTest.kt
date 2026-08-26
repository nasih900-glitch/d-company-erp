package cloud.dcompany.erp.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryBatchRefreshCoverageTest {

    @Test
    fun `realtime and confirmed stock writes refresh affected batch projections`() {
        val source = readSource(mainSourceRoot().resolve(SYNC_ENGINE_PATH))

        val realtimePull = source.bracedBlockAfter("private suspend fun pullInventoryOnDemand()")
        assertTrue("active batch missing from inventory realtime pull", "activeBatchTarget" in realtimePull)
        assertTrue("active batch is not bound to the current cache lease", "target.lease == currentLease" in realtimePull)
        assertTrue("active batch pull bypasses already-held lock helper", "pullBatchesForAlreadyLocked" in realtimePull)

        val grn = source.bracedBlockAfter("private suspend fun pushGrnOne")
        assertTrue("GRN confirmation does not refresh every affected ingredient", "lines.map" in grn)
        assertTrue("GRN batch refresh bypasses inventory lock helper", "pullBatchesForAlreadyLocked" in grn)

        val adjustment = source.bracedBlockAfter("private suspend fun pushAdjustmentOne")
        assertTrue("Adjustment confirmation does not refresh its batch", "pullBatchesForAlreadyLocked(row.ingredientId)" in adjustment)
    }

    @Test
    fun `selection batch pull uses resource feedback boundary`() {
        val source = readSource(mainSourceRoot().resolve(SYNC_ENGINE_PATH))
        val wrapper = source.bracedBlockAfter("suspend fun pullBatchesFor")
        val inventoryViewModel = readSource(
            mainSourceRoot().resolve(
                "cloud/dcompany/erp/ui/screens/inventory/InventoryViewModel.kt",
            ),
        )

        assertTrue("selection pull is not serialized", "withResourceSerialisation(\"inventory\")" in wrapper)
        assertTrue("selection pull does not log and publish failures", "runAndRecordRefreshAlreadyLocked" in wrapper)
        assertTrue(
            "closing inventory detail does not clear the retained realtime target",
            "clearActiveBatchTarget(batchTargetId)" in inventoryViewModel,
        )
    }

    private fun String.bracedBlockAfter(marker: String): String {
        val markerIndex = indexOf(marker)
        require(markerIndex >= 0) { "Missing source marker: $marker" }
        val openBrace = indexOf('{', markerIndex + marker.length)
        require(openBrace >= 0) { "Missing opening brace after $marker" }
        var depth = 0
        for (index in openBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(openBrace + 1, index)
                }
            }
        }
        error("Unclosed source block after $marker")
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

    private companion object {
        const val SYNC_ENGINE_PATH = "cloud/dcompany/erp/core/sync/SyncEngine.kt"
    }
}

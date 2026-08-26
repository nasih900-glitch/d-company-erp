package cloud.dcompany.erp.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCreateReplayPolicyTest {

    @Test
    fun `inventory create identity is stable per persisted outbox row`() {
        assertEquals(
            "ingredient-create:ingredient-local-1",
            InventoryCreateReplayPolicy.ingredientActionId("ingredient-local-1"),
        )
        assertEquals(
            "supplier-create:supplier-local-1",
            InventoryCreateReplayPolicy.supplierActionId("supplier-local-1"),
        )
    }

    @Test
    fun `create endpoints and replay calls require the stable idempotency key`() {
        val sourceRoot = mainSourceRoot()
        val api = readSource(
            sourceRoot.resolve("cloud/dcompany/erp/ui/screens/inventory/InventoryApi.kt"),
        ).replace(Regex("\\s+"), " ")
        val sync = readSource(
            sourceRoot.resolve("cloud/dcompany/erp/core/sync/SyncEngine.kt"),
        ).replace(Regex("\\s+"), " ")
        val dao = readSource(
            sourceRoot.resolve("cloud/dcompany/erp/core/db/InventoryDao.kt"),
        ).replace(Regex("\\s+"), " ")
        val viewModel = readSource(
            sourceRoot.resolve("cloud/dcompany/erp/ui/screens/inventory/InventoryViewModel.kt"),
        ).replace(Regex("\\s+"), " ")
        val ingredientApiSignature = api.between(
            "suspend fun createIngredient(",
            "): Ingredient",
        )
        val supplierApiSignature = api.between(
            "suspend fun createSupplier(",
            "): Supplier",
        )
        val ingredientReplay = sync.between(
            "val actionId = InventoryCreateReplayPolicy.ingredientActionId(row.localId)",
            "dao.setIngredientServerId(row.localId, created.id)",
        )
        val supplierReplay = sync.between(
            "val actionId = InventoryCreateReplayPolicy.supplierActionId(row.localId)",
            "dao.setSupplierServerId(row.localId, created.id)",
        )

        assertTrue(
            ingredientApiSignature.contains("@Header(\"Idempotency-Key\") key: String"),
        )
        assertTrue(
            supplierApiSignature.contains("@Header(\"Idempotency-Key\") key: String"),
        )
        assertTrue(ingredientReplay.contains("key = actionId"))
        assertTrue(
            ingredientReplay.contains(
                "provenance = outboxProvenanceHeaders(row.createdAtMillis, actionId)",
            ),
        )
        assertTrue(supplierReplay.contains("key = actionId"))
        assertTrue(
            supplierReplay.contains(
                "provenance = outboxProvenanceHeaders(row.createdAtMillis, actionId)",
            ),
        )
        val ingredientClaim = "dao.claimIngredientCreate(row.localId, row.version)"
        val supplierClaim = "dao.claimSupplierCreate(row.localId, row.version)"
        assertTrue(sync.contains(ingredientClaim))
        assertTrue(sync.contains(supplierClaim))
        assertTrue(sync.indexOf(ingredientClaim) < sync.indexOf("val actionId = InventoryCreateReplayPolicy.ingredientActionId"))
        assertTrue(sync.indexOf(supplierClaim) < sync.indexOf("val actionId = InventoryCreateReplayPolicy.supplierActionId"))
        assertTrue(dao.contains("state IN ('pending', 'create_attempted')"))
        assertTrue(viewModel.contains("dao.updateMutableIngredientCreate("))
        assertTrue(viewModel.contains("dao.deleteMutableIngredientCreate(localId)"))
        assertTrue(viewModel.contains("dao.updateMutableSupplierCreate("))
        assertTrue(viewModel.contains("dao.deleteMutableSupplierCreate(localId)"))
        assertTrue(viewModel.contains("retryRejectedIngredient(localId)"))
        assertTrue(viewModel.contains("retryRejectedSupplier(localId)"))
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

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }
}

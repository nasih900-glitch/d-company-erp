package cloud.dcompany.erp.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResourceLockCoverageTest {

    @Test
    fun `every mutable on-demand projection orders old GET before confirmation`() = runBlocking {
        val resources = listOf(
            "customers",
            "staff",
            "inventory",
            "finance",
            "events",
            "memberships",
            "settings",
            "menu",
        )

        resources.forEach { resource ->
            assertOlderGetCannotLandAfterConfirmation(resource)
        }
    }

    @Test
    fun `multi-resource order is canonical and duplicate-safe`() {
        assertEquals(
            listOf("gaming", "orders", "tables"),
            canonicalResourceLockOrder(listOf(" tables ", "orders", "gaming", "ORDERS")),
        )
        assertEquals(
            canonicalResourceLockOrder(listOf("tables", "orders")),
            canonicalResourceLockOrder(listOf("orders", "tables")),
        )
    }

    @Test
    fun `opposite multi-resource requests complete without inversion`() = runBlocking {
        val serialiser = ResourceRefreshSerialiser()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async(Dispatchers.Unconfined) {
            serialiser.runAll(listOf("tables", "orders")) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first"
            }
        }
        firstEntered.await()
        val reverse = async(Dispatchers.Unconfined) {
            serialiser.runAll(listOf("orders", "tables")) {
                order += "reverse"
            }
        }
        assertFalse(reverse.isCompleted)

        releaseFirst.complete(Unit)
        withTimeout(1_000) {
            first.await()
            reverse.await()
        }
        assertEquals(listOf("first", "reverse"), order)
    }

    @Test
    fun `sync pass keeps every shared projection push behind its resource lock`() {
        val source = readSource(mainSourceRoot().resolve(SYNC_ENGINE_PATH))
        val pass = source.bracedBlockAfter("private suspend fun runSyncPass()")

        assertLockedPass(pass, "shifts", "pushShiftOpens()")
        assertLockedPass(pass, "shifts", "pushShiftCloses()")
        assertLockedPass(
            pass,
            "orders",
            "pushPendingOrders()",
            "pushHeldOrderPayments()",
            "pushRefunds()",
        )
        assertLockedPass(pass, "kitchen", "pushKitchenAdvances()", "pushKitchenCancellationAcks()")
        assertLockedPass(pass, "customers", "pushCustomers()")
        assertLockedPass(pass, "staff", "pushStaff()")
        assertLockedPass(
            pass,
            "inventory",
            "pushIngredients()",
            "pushSuppliers()",
            "pushGrns()",
            "pushAdjustments()",
        )
        assertLockedPass(
            pass,
            "finance",
            "pushExpenses()",
            "pushAssets()",
            "pushCapitalEntries()",
            "pullFinanceSnapshots",
        )
        assertLockedPass(pass, "events", "pushTicketSales()", "pushCheckIns()")
        assertLockedPass(
            pass,
            "memberships",
            "pullMembershipPaymentTasksBestEffort()",
            "pushMembershipPaymentActions()",
            "pushCancellations()",
            "pushMembershipRefundActions()",
        )
        assertLockedPass(pass, "settings", "pushCompanyEdit()", "pushBranches()", "pushTerminals()")
        assertLockedPass(pass, "menu", "pushMenuCategories()", "pushMenuItems()", "pullMenu()")
        assertMultiLockedPass(pass, listOf("gaming", "orders"), "pushGamingSessions()")
        assertMultiLockedPass(pass, listOf("tables", "orders"), "pushCafeActions()")

        assertLockingWrapper(source, "pullBatchesFor", "inventory", "pullBatchesForAlreadyLocked")
        assertLockingWrapper(
            source,
            "pullCapitalEntriesFor",
            "finance",
            "pullCapitalEntriesForAlreadyLocked",
        )
        assertLockingWrapper(source, "pullTicketsFor", "events", "pullTicketsForAlreadyLocked")
        assertLockingWrapper(
            source,
            "pullMembershipFor",
            "memberships",
            "pullMembershipForAlreadyLocked",
        )
        assertLockingWrapper(
            source,
            "refreshCafeBillsForRecovery",
            "tables",
            "fetchActiveBills",
        )
        assertLockingWrapper(
            source,
            "clearFinanceReadCachesForScopeChange",
            "finance",
            "clearReadCachesForScopeChange",
        )
    }

    @Test
    fun `ui layer cannot replace server projections outside sync boundary`() {
        val uiRoot = mainSourceRoot().resolve("cloud/dcompany/erp/ui")
        val forbiddenMutations = listOf(
            ".replaceServerHistoryForTerminal(",
            ".reconcileServerOpen(",
            ".deleteServerOpen(",
            ".replaceStations(",
            ".replaceSessionCache(",
            ".replaceOrderCache(",
            ".replaceFloors(",
            ".replaceTables(",
            ".replaceActiveBillCache(",
            ".replaceOnShift(",
            ".replaceIngredientCache(",
            ".replaceSupplierCache(",
            ".replaceBatchesFor(",
            ".replaceExpenseCache(",
            ".replaceAssetCache(",
            ".replaceCapitalEntriesFor(",
            ".clearReadCachesForScopeChange(",
            ".replaceEventCache(",
            ".replaceTicketsFor(",
            ".replaceTierCache(",
            ".replaceMembershipFor(",
            ".replaceMembershipHistoryFor(",
            ".replaceTasksForTerminal(",
            ".replaceAttemptsForTerminal(",
            ".upsertCompany(",
            ".replaceBranchCache(",
            ".replaceTerminalCache(",
            ".replaceMenu(",
        )
        val offenders = mutableListOf<String>()

        Files.walk(uiRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file ->
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        forbiddenMutations.filter(line::contains).forEach { mutation ->
                            offenders += "${uiRoot.relativize(file)}:${index + 1}:$mutation"
                        }
                    }
                }
        }

        assertTrue(
            "Server projection writes must be routed through SyncEngine's resource locks: $offenders",
            offenders.isEmpty(),
        )
    }

    private suspend fun assertOlderGetCannotLandAfterConfirmation(resource: String) = coroutineScope {
        val serialiser = ResourceRefreshSerialiser()
        val releaseGet = CompletableDeferred<Unit>()
        var cache = "initial"

        val olderGet = async(Dispatchers.Unconfined) {
            serialiser.run(resource) {
                releaseGet.await()
                cache = "older-get"
            }
        }
        val confirmation = async(Dispatchers.Unconfined) {
            serialiser.run(resource) {
                cache = "confirmed-write"
            }
        }
        assertFalse("$resource confirmation bypassed its older GET", confirmation.isCompleted)

        releaseGet.complete(Unit)
        olderGet.await()
        confirmation.await()
        assertEquals("confirmed-write", cache)
    }

    private fun assertLockedPass(pass: String, resource: String, vararg requiredCalls: String) {
        val blocks = pass.bracedBlocksAfter("withResourceSerialisation(\"$resource\")")
        requiredCalls.forEach { call ->
            assertTrue(
                "Missing $call behind the $resource resource lock",
                blocks.any { call in it },
            )
        }
    }

    private fun assertMultiLockedPass(
        pass: String,
        resources: List<String>,
        requiredCall: String,
    ) {
        val arguments = resources.joinToString(", ") { "\"$it\"" }
        val blocks = pass.bracedBlocksAfter("withResourceSerialisations($arguments)")
        assertTrue(
            "Missing $requiredCall behind the ${resources.joinToString("+")} resource locks",
            blocks.any { requiredCall in it },
        )
    }

    private fun assertLockingWrapper(
        source: String,
        functionName: String,
        resource: String,
        delegatedCall: String,
    ) {
        val block = source.bracedBlockAfter("suspend fun $functionName")
        assertTrue(
            "$functionName must acquire the $resource resource lock",
            "withResourceSerialisation(\"$resource\")" in block,
        )
        assertTrue("$functionName must delegate to $delegatedCall", delegatedCall in block)
    }

    private fun String.bracedBlocksAfter(marker: String): List<String> {
        val blocks = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val markerIndex = indexOf(marker, searchFrom)
            if (markerIndex < 0) return blocks
            val openBrace = indexOf('{', markerIndex + marker.length)
            require(openBrace >= 0) { "Missing opening brace after $marker" }
            blocks += bracedBlockAt(openBrace)
            searchFrom = openBrace + 1
        }
    }

    private fun readSource(path: Path): String = Files.newBufferedReader(path).use { it.readText() }

    private fun String.bracedBlockAfter(marker: String): String {
        val markerIndex = indexOf(marker)
        require(markerIndex >= 0) { "Missing source marker: $marker" }
        val openBrace = indexOf('{', markerIndex + marker.length)
        require(openBrace >= 0) { "Missing opening brace after $marker" }
        return bracedBlockAt(openBrace)
    }

    private fun String.bracedBlockAt(openBrace: Int): String {
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
        error("Unclosed source block at character $openBrace")
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

    private companion object {
        const val SYNC_ENGINE_PATH = "cloud/dcompany/erp/core/sync/SyncEngine.kt"
    }
}

package cloud.dcompany.erp.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftCloseTerminalGateCoverageTest {

    @Test
    fun `production close path cannot bypass terminal scoped rows`() {
        val source = readSource(mainSourceRoot().resolve(SYNC_ENGINE_PATH))
        val closePass = source
            .bracedBlockAfter("private suspend fun pushShiftCloses()")
            .replace(Regex("\\s+"), " ")

        assertTrue(
            "shift-close terminal filter is not applied to validated close rows",
            "val terminalEligible = ShiftCloseCountPolicy.filterForTerminal(countEligible, terminalId)" in closePass,
        )
        assertTrue(
            "shift-close dependency checks bypass the terminal-scoped rows",
            "val eligible = terminalEligible.filter" in closePass,
        )
        assertTrue(
            "shift-close POST loop bypasses the fully eligible rows",
            "for ((row, countedMinor) in eligible)" in closePass,
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

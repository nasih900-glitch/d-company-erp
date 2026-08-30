package cloud.dcompany.erp.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConnectivityAuthorityCoverageTest {

    @Test
    fun `workspace authority never treats validated wifi as verified ERP availability`() {
        val source = String(
            Files.readAllBytes(mainSourceRoot().resolve("cloud/dcompany/erp/ui/SessionViewModel.kt")),
            StandardCharsets.UTF_8,
        )

        assertFalse(
            "Internet validation is not backend authority",
            source.contains("connectivity.networkValidated.value"),
        )
        assertTrue(
            "Terminal reassignment facts must use the coordinator's proven-online state",
            source.between(
                "private suspend fun reassignmentFacts(",
                "private fun requireReassignmentAllowed(",
            ).contains("online = connectivity.online.value"),
        )
        assertTrue(
            "Final terminal selection guard must re-check proven-online state",
            source.between(
                "private suspend fun verifiedReassignmentChoices(",
                "fun cancelTerminalReassignment(",
            ).contains("if (!connectivity.online.value)"),
        )
    }

    private fun String.between(start: String, end: String): String {
        val from = indexOf(start)
        require(from >= 0) { "Missing source marker: $start" }
        val to = indexOf(end, startIndex = from + start.length)
        require(to > from) { "Missing source marker: $end" }
        return substring(from, to)
    }

    private fun mainSourceRoot(): Path {
        var cursor = Paths.get("").toAbsolutePath().normalize()
        repeat(8) {
            val direct = cursor.resolve("src/main/java")
            if (Files.isDirectory(direct)) return direct
            val nested = cursor.resolve("app/src/main/java")
            if (Files.isDirectory(nested)) return nested
            cursor = cursor.parent ?: return@repeat
        }
        error("Could not locate Android main source root")
    }
}

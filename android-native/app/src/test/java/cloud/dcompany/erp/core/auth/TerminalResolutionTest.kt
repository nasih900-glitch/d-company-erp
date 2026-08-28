package cloud.dcompany.erp.core.auth

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.Terminal
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class TerminalResolutionTest {

    @Test
    fun `legacy terminal payload defaults to hybrid while unknown future purpose is preserved`() {
        val legacy = ApiClient.json.decodeFromString<Terminal>(
            """{"id":"till-1","name":"Main till","branch_id":"branch-1"}""",
        )
        val future = ApiClient.json.decodeFromString<Terminal>(
            """{"id":"till-2","name":"Future till","branch_id":"branch-1","purpose":"future"}""",
        )

        assertEquals(TerminalPurpose.HYBRID, legacy.purpose)
        assertEquals("future", future.purpose)
    }

    @Test
    fun `non POS employee does not need a terminal`() {
        assertEquals(
            TerminalResolution.NotRequired,
            resolveTerminalAssignment(
                requiresPosTerminal = false,
                branchId = null,
                availableTerminals = emptyList(),
                cachedTerminalId = null,
                hasUnresolvedLocalWork = true,
            ),
        )
    }

    @Test
    fun `valid saved terminal is retained even when branch has several tills`() {
        val result = resolve(terminals = listOf(till("two", "Till 2"), till("one", "Till 1")), cached = "two")

        assertEquals(TerminalResolution.Resolved(till("two", "Till 2"), false), result)
    }

    @Test
    fun `single authorised till is assigned without asking employee`() {
        val result = resolve(terminals = listOf(till("one", "Main till")))

        assertEquals(TerminalResolution.Resolved(till("one", "Main till"), true), result)
    }

    @Test
    fun `multiple tills require an explicit stable choice`() {
        val result = resolve(
            terminals = listOf(
                till("two", "Upstairs"),
                till("one", "Main Counter"),
                till("wrong-branch", "Other shop", branchId = "branch-2"),
                till("one", "Duplicate response"),
            ),
        )

        assertTrue(result is TerminalResolution.SelectionRequired)
        val choices = (result as TerminalResolution.SelectionRequired).terminals
        assertEquals(listOf("one", "two"), choices.map(Terminal::id))
    }

    @Test
    fun `stale assignment with unresolved work blocks reassignment`() {
        val result = resolve(
            terminals = listOf(till("one", "Main Counter"), till("two", "Bar till")),
            cached = "removed-terminal",
            unresolved = true,
        )

        assertTrue(result is TerminalResolution.Blocked)
        assertTrue((result as TerminalResolution.Blocked).message.contains("saved work"))
    }

    @Test
    fun `deleted till is replaced automatically when clean and branch has one till`() {
        val result = resolve(
            terminals = listOf(till("replacement", "Replacement till")),
            cached = "deleted-till",
            unresolved = false,
        )

        assertEquals(
            TerminalResolution.Resolved(till("replacement", "Replacement till"), true),
            result,
        )
    }

    @Test
    fun `clean branch switch never reuses a till from the previous branch`() {
        val result = resolve(
            terminals = listOf(
                till("new-main", "New branch main"),
                till("old-main", "Old branch main", branchId = "branch-2"),
            ),
            cached = "old-main",
            unresolved = false,
        )

        assertEquals(
            TerminalResolution.Resolved(till("new-main", "New branch main"), true),
            result,
        )
    }

    @Test
    fun `missing branch and empty branch terminal list explain the actual correction`() {
        val missingBranch = resolveTerminalAssignment(
            requiresPosTerminal = true,
            branchId = " ",
            availableTerminals = emptyList(),
            cachedTerminalId = null,
            hasUnresolvedLocalWork = false,
        )
        val noTill = resolve(terminals = emptyList())

        assertTrue(missingBranch is TerminalResolution.Blocked)
        assertTrue((missingBranch as TerminalResolution.Blocked).message.contains("branch assignment"))
        assertTrue(noTill is TerminalResolution.Blocked)
        assertTrue((noTill as TerminalResolution.Blocked).message.contains("No POS terminal"))
        assertFalse((noTill as TerminalResolution.Blocked).message.contains("select", ignoreCase = true))
    }

    @Test
    fun `new till is remembered only after scope activation succeeds`() = runBlocking {
        val events = mutableListOf<String>()

        activateAndRememberTerminal(
            terminalId = "two",
            shouldRemember = true,
            activate = { events += "activate:$it" },
            remember = { events += "remember:$it" },
        )

        assertEquals(listOf("activate:two", "remember:two"), events)
    }

    @Test
    fun `failed scope activation never overwrites recoverable till assignment`() = runBlocking {
        var remembered = false

        val error = runCatching {
            activateAndRememberTerminal(
                terminalId = "two",
                shouldRemember = true,
                activate = { throw IllegalStateException("saved work appeared") },
                remember = { remembered = true },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(remembered)
    }

    private fun resolve(
        terminals: List<Terminal>,
        cached: String? = null,
        unresolved: Boolean = false,
    ): TerminalResolution = resolveTerminalAssignment(
        requiresPosTerminal = true,
        branchId = "branch-1",
        availableTerminals = terminals,
        cachedTerminalId = cached,
        hasUnresolvedLocalWork = unresolved,
    )

    private fun till(id: String, name: String, branchId: String = "branch-1") = Terminal(
        id = id,
        name = name,
        branchId = branchId,
    )
}

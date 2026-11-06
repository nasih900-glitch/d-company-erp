package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.LoginSessionLease
import cloud.dcompany.erp.core.net.MeResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshLoginHandoffTest {
    @Test
    fun `exact explicit login and scope arm and consume once`() {
        val login = LoginSessionLease(Any(), 4L)
        var currentLogin = login
        var scope = scopeLease(generation = 8L)
        val coordinator = coordinator(
            nextNonce = { 91L },
            isCurrent = { it === currentLogin },
            currentScope = { scope },
        )

        assertEquals(91L, coordinator.arm(profile(), login))
        assertFalse(coordinator.consume(91L, 90L, profile()))
        assertTrue(coordinator.consume(91L, 91L, profile()))
        assertFalse("The acknowledgement is one-shot", coordinator.consume(91L, 91L, profile()))
    }

    @Test
    fun `scope generation terminal and account changes revoke pending handoff`() {
        val login = LoginSessionLease(Any(), 1L)
        var scope = scopeLease(generation = 2L)
        val coordinator = coordinator(
            nextNonce = { 5L },
            isCurrent = { it === login },
            currentScope = { scope },
        )

        assertEquals(5L, coordinator.arm(profile(), login))
        scope = scopeLease(generation = 3L)
        assertFalse(coordinator.consume(5L, 5L, profile()))

        scope = scopeLease(generation = 4L)
        assertEquals(5L, coordinator.arm(profile(), login))
        assertFalse(coordinator.consume(5L, 5L, profile(user = "other-user")))

        scope = scopeLease(generation = 5L, terminal = "terminal-b")
        assertEquals(5L, coordinator.arm(profile(), login))
        scope = scopeLease(generation = 5L, terminal = "terminal-c")
        assertFalse(coordinator.consume(5L, 5L, profile()))
    }

    @Test
    fun `authority refresh preserves only same owner lineage and exact scope`() {
        val login = LoginSessionLease(Any(), 1L)
        var currentLogin = login
        var scope = scopeLease(generation = 2L)
        val coordinator = coordinator(
            nextNonce = { 17L },
            isCurrent = { it === currentLogin },
            currentScope = { scope },
        )
        val before = profile(permissions = listOf("gaming.read"))
        val after = profile(permissions = listOf("gaming.read", "pos.read"))

        assertEquals(17L, coordinator.arm(before, login))
        assertEquals(17L, coordinator.preserve(17L, before, after))

        currentLogin = LoginSessionLease(Any(), 2L)
        assertNull(coordinator.preserve(17L, after, after))

        currentLogin = login
        scope = scopeLease(generation = 3L)
        assertEquals(17L, coordinator.arm(before, login))
        scope = scopeLease(generation = 4L)
        assertNull(coordinator.preserve(17L, before, after))
    }

    @Test
    fun `late old arm and stale refresh cannot erase newer pending handoff`() {
        val oldLogin = LoginSessionLease(Any(), 1L)
        val newLogin = LoginSessionLease(Any(), 2L)
        var currentLogin = newLogin
        var nextNonce = 40L
        val coordinator = coordinator(
            nextNonce = { ++nextNonce },
            isCurrent = { it === currentLogin },
            currentScope = { scopeLease(generation = 7L) },
        )

        assertEquals(41L, coordinator.arm(profile(), newLogin))
        assertNull("A late old login must not replace or clear the current handoff", coordinator.arm(profile(), oldLogin))
        assertNull(
            "A stale refresh callback must not clear a different visible nonce",
            coordinator.preserve(9L, profile(), profile()),
        )
        assertTrue(coordinator.consume(41L, 41L, profile()))

        currentLogin = oldLogin
        assertEquals(42L, coordinator.arm(profile(), oldLogin))
    }

    @Test
    fun `forced clear is serialized with arm and cannot leave a torn binding`() {
        val login = LoginSessionLease(Any(), 1L)
        val armEntered = CountDownLatch(1)
        val releaseArm = CountDownLatch(1)
        val clearFinished = CountDownLatch(1)
        val backgroundFailure = AtomicReference<Throwable?>(null)
        val coordinator = coordinator(
            nextNonce = { 55L },
            isCurrent = { it === login },
            currentScope = {
                armEntered.countDown()
                assertTrue(releaseArm.await(2, TimeUnit.SECONDS))
                scopeLease(generation = 9L)
            },
        )

        val armThread = Thread {
            runCatching { assertEquals(55L, coordinator.arm(profile(), login)) }
                .onFailure(backgroundFailure::set)
        }
        val clearThread = Thread {
            runCatching {
                assertTrue(armEntered.await(2, TimeUnit.SECONDS))
                coordinator.clear()
                clearFinished.countDown()
            }.onFailure(backgroundFailure::set)
        }
        armThread.start()
        clearThread.start()
        assertTrue(armEntered.await(2, TimeUnit.SECONDS))
        assertFalse("Clear must wait for the atomic arm transition", clearFinished.await(100, TimeUnit.MILLISECONDS))
        releaseArm.countDown()
        armThread.join(2_000)
        clearThread.join(2_000)

        assertFalse(armThread.isAlive)
        assertFalse(clearThread.isAlive)
        backgroundFailure.get()?.let { throw AssertionError("Background transition failed", it) }
        assertFalse("The later forced clear must revoke the completed handoff", coordinator.consume(55L, 55L, profile()))
    }

    @Test
    fun `restore states never invent a fresh handoff nonce`() {
        assertNull(AuthState.SignedIn(profile()).freshLoginNonce)
    }

    @Test
    fun `late authority refresh cannot publish over same-owner newer login or forced logout`() {
        val launchedFrom = AuthState.SignedIn(profile(), freshLoginNonce = 11L)
        val sameOwnerNewLogin = AuthState.SignedIn(profile(), freshLoginNonce = 12L)

        assertTrue(authorityRefreshMayPublish(launchedFrom, launchedFrom, refreshLeaseCurrent = true))
        assertFalse(authorityRefreshMayPublish(launchedFrom, sameOwnerNewLogin, refreshLeaseCurrent = true))
        assertFalse(authorityRefreshMayPublish(launchedFrom, AuthState.Loading, refreshLeaseCurrent = true))
        assertFalse(authorityRefreshMayPublish(launchedFrom, launchedFrom, refreshLeaseCurrent = false))
    }

    @Test
    fun `session view model publishes handoff only from both explicit login completions`() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/cloud/dcompany/erp/ui/SessionViewModel.kt")),
            Charsets.UTF_8,
        )
        val directLogin = source.between("fun signIn(", "private fun freshLoginState(")
        val terminalCompletion = source.between("fun selectTerminal(", "fun refreshTerminalChoices(")
        val restore = source.between("fun restore()", "private suspend fun lockRestoredWorkspaceAsUnreachable(")
        val refresh = source.between("private suspend fun refreshSessionAuthority(", "fun restore()")
        val acknowledge = source.between(
            "fun acknowledgeFreshLoginHandoff(",
            "private suspend fun activateResolvedTerminalOrRequestSelection(",
        )

        val publication = source.between(
            "private suspend fun publishStartupWorkspace(",
            "private fun refreshReceiptHistoryAfterLogin(",
        )
        val finalGate = source.between(
            "internal fun publishVerifiedStartupState(",
            "internal enum class RestoreFailureAction",
        )
        val guardedCall = "publishStartupWorkspace(publicationLogin, verifiedCache, original, signedIn)"
        assertOrdered(
            directLogin,
            "val signedIn = freshLoginState(me, installedLogin)",
            guardedCall,
            "_state.value === expectedState && tokens.isCurrent(installedLogin)",
        )
        assertOrdered(
            terminalCompletion,
            "val signedIn = if (pending.installedLogin != null && pending.previousTerminal == null)",
            "freshLoginState(pending.me, pending.installedLogin)",
            guardedCall,
            "pendingTerminalSession === pending && _state.value === choosingState && pending.isTokenCurrent()",
        )
        assertFalse("Neither explicit path may bypass the guarded publisher",
            (directLogin + terminalCompletion).contains("_state.value = signedIn"))
        assertOrdered(
            publication,
            "cacheIsolation.commitIfCurrent(lease) {",
            "tokens.commitIfOriginalLoginCurrent(login) {",
            "published = publishVerifiedStartupState(",
            ") { _state.value = signedIn }",
        )
        assertOrdered(
            finalGate,
            "if (!ownsAttempt) return false",
            "original.isCurrent()",
            "requireOrdinaryReadiness()",
            "publish()",
        )
        assertFalse("Restores must bypass the fresh-login handoff", restore.contains("freshLoginState("))
        assertTrue(refresh.contains("freshLoginHandoff.preserve("))
        assertTrue(acknowledge.contains("freshLoginHandoff.consume("))
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        var previous = -1
        for (marker in markers) {
            val index = source.indexOf(marker, previous + 1)
            assertTrue("Missing or out-of-order handoff contract: $marker", index > previous)
            previous = index
        }
    }

    private fun coordinator(
        nextNonce: () -> Long,
        isCurrent: (LoginSessionLease) -> Boolean,
        currentScope: () -> CacheScopeLease?,
    ) = FreshLoginHandoffCoordinator(nextNonce, isCurrent, currentScope)

    private fun scopeLease(
        generation: Long,
        terminal: String = "terminal-a",
    ) = CacheScopeLease(
        scope = CacheScope(
            userId = "user-a",
            companyId = "company-a",
            branchId = "branch-a",
            terminalId = terminal,
        ),
        generation = generation,
    )

    private fun profile(
        user: String = "user-a",
        permissions: List<String> = listOf("gaming.read"),
    ) = MeResponse(
        userId = user,
        email = "$user@example.test",
        name = "Employee",
        companyId = "company-a",
        branchId = "branch-a",
        accessibleModules = listOf("gaming"),
        effectivePermissions = permissions,
    )

    private fun String.between(start: String, end: String): String {
        val from = indexOf(start)
        require(from >= 0) { "Missing source marker: $start" }
        val to = indexOf(end, from + start.length)
        require(to > from) { "Missing source marker: $end" }
        return substring(from, to)
    }
}

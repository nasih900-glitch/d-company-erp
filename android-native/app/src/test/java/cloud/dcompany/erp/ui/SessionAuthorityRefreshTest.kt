package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.sync.RealtimeEvent
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAuthorityRefreshTest {

    @Test
    fun `access control and reconnect request authority refresh`() {
        assertTrue(RealtimeEvent.Changed("access_control").requiresAuthorityRefresh())
        assertTrue(RealtimeEvent.Changed("staff").requiresAuthorityRefresh())
        assertTrue(RealtimeEvent.ReconnectedAfterGap.requiresAuthorityRefresh())
        assertFalse(RealtimeEvent.Changed("orders").requiresAuthorityRefresh())
    }

    @Test
    fun `authority changes within exact token identity can replace profile`() {
        val old = profile(modules = listOf("pos"))
        val refreshed = profile(modules = listOf("tables"))

        assertTrue(canApplyAuthorityProfile(old, refreshed, token("user-a", "company-a", "branch-a")))
        assertTrue(accessAuthorityChanged(old, refreshed))
    }

    @Test
    fun `late profile from another login or branch is rejected`() {
        val old = profile()

        assertFalse(
            canApplyAuthorityProfile(
                old,
                profile(user = "user-b"),
                token("user-b", "company-a", "branch-a"),
            ),
        )
        assertFalse(
            canApplyAuthorityProfile(
                old,
                profile(branch = "branch-b"),
                token("user-a", "company-a", "branch-b"),
            ),
        )
    }

    @Test
    fun `refreshed authority removes inaccessible navigation immediately`() {
        val before = profile(effective = listOf(ErpPermission.PosRead))
        val after = profile(effective = listOf(ErpPermission.KitchenRead))

        assertTrue(Destination.Pos in allowedDestinations(before))
        assertFalse(Destination.Pos in allowedDestinations(after))
        assertTrue(Destination.Kitchen in allowedDestinations(after))
    }

    @Test
    fun `burst is debounced into one profile refresh`() = runBlocking {
        val calls = AtomicInteger()
        val completed = CompletableDeferred<Unit>()
        val coordinator = SessionAuthorityRefreshCoordinator(
            scope = this,
            debounceMillis = 25,
        ) {
            calls.incrementAndGet()
            completed.complete(Unit)
        }

        repeat(8) { coordinator.request() }
        withTimeout(1_000) { completed.await() }
        delay(75)

        assertEquals(1, calls.get())
        coordinator.cancel()
    }

    @Test
    fun `event arriving during refresh produces one serial trailing refresh`() = runBlocking {
        val calls = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()
        val coordinator = SessionAuthorityRefreshCoordinator(
            scope = this,
            debounceMillis = 0,
        ) {
            when (calls.incrementAndGet()) {
                1 -> {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                2 -> secondCompleted.complete(Unit)
            }
        }

        coordinator.request()
        withTimeout(1_000) { firstStarted.await() }
        repeat(5) { coordinator.request() }
        releaseFirst.complete(Unit)
        withTimeout(1_000) { secondCompleted.await() }

        assertEquals(2, calls.get())
        coordinator.cancel()
    }

    private fun profile(
        user: String = "user-a",
        branch: String = "branch-a",
        modules: List<String> = listOf("pos"),
        effective: List<String>? = null,
    ) = MeResponse(
        userId = user,
        email = "$user@example.test",
        name = "Employee",
        companyId = "company-a",
        branchId = branch,
        accessibleModules = modules,
        effectivePermissions = effective,
    )

    private fun token(user: String, company: String, branch: String): String {
        val payload = """{"sub":"$user","company_id":"$company","branch_id":"$branch"}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("{}", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.encodeToByteArray()) }
    }
}

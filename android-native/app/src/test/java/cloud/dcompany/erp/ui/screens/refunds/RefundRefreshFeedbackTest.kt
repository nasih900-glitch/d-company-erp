package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundRefreshFeedbackTest {
    @Test
    fun `refresh preserves actual failure guidance and explains skipped offline refresh`() {
        assertEquals(
            "Session expired. Sign in again.",
            refundRefreshError(ResourceRefreshResult.Failed("orders", "Session expired. Sign in again."), true),
        )
        assertTrue(refundRefreshError(ResourceRefreshResult.Skipped("orders"), false)!!.contains("Reconnect"))
        assertTrue(refundRefreshError(ResourceRefreshResult.Skipped("orders"), true)!!.contains("Check access"))
        assertNull(refundRefreshError(ResourceRefreshResult.Refreshed("orders"), true))
    }

    @Test
    fun `successful reconnect clears obsolete refresh error but older cache does not`() {
        val failure = RefundRefreshFailure("Could not reach server", 200)
        assertEquals(failure.message, refundRefreshFailureAfterCache(failure, null))
        assertEquals(failure.message, refundRefreshFailureAfterCache(failure, 100))
        assertNull(refundRefreshFailureAfterCache(failure, 300))
        assertNull(refundRefreshFailureAfterCache(null, 300))
    }

    @Test
    fun `refresh has bounded progress and realtime failure observation with terminal empty state`() {
        val root = listOf("src/main/java", "app/src/main/java", "android-native/app/src/main/java")
            .map { Paths.get(it) }.first { Files.isDirectory(it) }
            .resolve("cloud/dcompany/erp/ui/screens/refunds")
        val vm = String(Files.readAllBytes(root.resolve("RefundsViewModel.kt")), Charsets.UTF_8)
        val screen = String(Files.readAllBytes(root.resolve("RefundsScreen.kt")), Charsets.UTF_8)
        assertTrue(vm.contains("resourceRefreshErrors.map { it[\"orders\"] }"))
        assertTrue(vm.contains("withTimeout(45_000L) { appCtx.sync.refresh(\"orders\") }"))
        assertTrue(vm.contains("catch (cancelled: CancellationException)"))
        assertTrue(vm.contains("refreshing.value = false"))
        assertTrue(screen.contains("!state.everSynced && state.refreshing"))
        assertTrue(screen.contains("Refund records have not loaded"))
        assertTrue(screen.contains("Refund records may be out of date"))
        assertTrue(screen.contains("busy = state.refreshing"))
    }
}

package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.LocalGamingPackageExtensionEntity
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.gaming.toPackageExtendBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingPackageExtensionReplayPolicyTest {

    @Test
    fun `ambiguous and rejected replays retain the exact original payload and action UUID`() {
        val original = action(state = GamingPackageExtensionState.PENDING)
        val afterLostResponse = original.copy(
            state = GamingPackageExtensionState.AMBIGUOUS,
            lastError = "response lost",
        )
        val afterDefinitiveRejection = original.copy(
            state = GamingPackageExtensionState.REJECTED,
            lastError = "package retired for new selections",
        )

        assertEquals(original.actionId, packageExtensionIdempotencyKey(original))
        assertEquals(original.actionId, packageExtensionIdempotencyKey(afterLostResponse))
        assertEquals(original.actionId, packageExtensionIdempotencyKey(afterDefinitiveRejection))
        assertEquals(original.toPackageExtendBody(), afterLostResponse.toPackageExtendBody())
        assertEquals(original.toPackageExtendBody(), afterDefinitiveRejection.toPackageExtendBody())
        assertEquals(60, original.toPackageExtendBody().expectedTimerMinutes)
        assertEquals(23_000L, original.toPackageExtendBody().expectedAmountMinor)
    }

    @Test
    fun `ambiguous server failure is described as pending confirmation not failed charge`() {
        val message = packageExtensionFailureMessage(
            ApiException("gateway timeout", status = 504),
        )

        assertTrue(message.contains("confirmation is pending", ignoreCase = true))
    }

    @Test
    fun `definitive refusal preserves the actionable server reason`() {
        assertEquals(
            "Package price changed. Refresh Gaming.",
            packageExtensionFailureMessage(
                ApiException("Package price changed. Refresh Gaming.", status = 409),
            ),
        )
    }

    @Test
    fun `migrated stop without captured timestamp uses the bodyless legacy route`() {
        assertEquals(GamingStopReplayMode.LEGACY_BODYLESS, gamingStopReplayMode(null))
        assertEquals(
            GamingStopReplayMode.CAPTURED_TIMESTAMP_BODY,
            gamingStopReplayMode(1_787_795_200_000L),
        )
    }

    private fun action(state: String) = LocalGamingPackageExtensionEntity(
        actionId = "9f67c667-0d4a-48ca-995f-efb700c1f0e7",
        serverSessionId = "session-1",
        localSessionId = "local-session-1",
        shiftId = "shift-1",
        packageId = "extension-1",
        expectedPackagePriceMinor = 7_500,
        expectedPackageDurationMinutes = 30,
        expectedPackageVariant = "dual",
        expectedSessionTimerMinutes = 60,
        expectedSessionAmountMinor = 23_000,
        createdAtMillis = 1_787_795_200_000L,
        state = state,
    )
}

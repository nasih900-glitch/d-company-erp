package cloud.dcompany.erp.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosRefundEffectsRefreshGateTest {

    @Test
    fun `dirty marker clears only when every required projection refreshed`() {
        val allFresh = PosRefundEffectsRefreshGate()
        assertFalse(allFresh.mayClearDirtyMarker)
        REQUIRED_POS_REFUND_EFFECT_PROJECTIONS.forEach { resource ->
            allFresh.recordRequired(resource, ResourceRefreshResult.Refreshed(resource))
        }
        assertTrue(allFresh.mayClearDirtyMarker)

        val failed = PosRefundEffectsRefreshGate()
        failed.recordRequired("orders", ResourceRefreshResult.Refreshed("orders"))
        failed.recordRequired("customers", ResourceRefreshResult.Failed("customers", "offline"))
        failed.recordRequired("finance", ResourceRefreshResult.Refreshed("finance"))
        failed.recordRequired("shifts", ResourceRefreshResult.Refreshed("shifts"))
        assertFalse(failed.mayClearDirtyMarker)

        val skipped = PosRefundEffectsRefreshGate()
        REQUIRED_POS_REFUND_EFFECT_PROJECTIONS.forEach { resource ->
            skipped.recordRequired(
                resource,
                if (resource == "finance") {
                    ResourceRefreshResult.Skipped(resource)
                } else {
                    ResourceRefreshResult.Refreshed(resource)
                },
            )
        }
        assertFalse(skipped.mayClearDirtyMarker)
    }
}

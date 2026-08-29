package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.POS_REFUND_EFFECTS_DIRTY_SYNC_KEY
import cloud.dcompany.erp.core.db.SyncMetaDao

/**
 * A terminal refund's dirty marker may be removed only when every projection
 * this account is authorised to read returned fresh server data. A skipped or
 * failed required pull keeps the durable marker for the next reconnect/pass.
 */
internal class PosRefundEffectsRefreshGate {
    private val outcomes = mutableMapOf<String, Boolean>()

    val mayClearDirtyMarker: Boolean
        get() = REQUIRED_POS_REFUND_EFFECT_PROJECTIONS.all { outcomes[it] == true }

    fun recordRequired(resource: String, result: ResourceRefreshResult) {
        require(resource in REQUIRED_POS_REFUND_EFFECT_PROJECTIONS) {
            "Unknown POS refund projection '$resource'."
        }
        outcomes[resource] = result is ResourceRefreshResult.Refreshed &&
            result.resource == resource
    }
}

internal val REQUIRED_POS_REFUND_EFFECT_PROJECTIONS = setOf(
    "orders",
    "customers",
    "finance",
    "shifts",
)

/** Couples the named refresh invariant to the durable deletion path. */
internal suspend fun PosRefundEffectsRefreshGate.clearDirtyMarkerIfReady(
    syncMetaDao: SyncMetaDao,
): Boolean {
    if (!mayClearDirtyMarker) return false
    syncMetaDao.delete(POS_REFUND_EFFECTS_DIRTY_SYNC_KEY)
    return true
}

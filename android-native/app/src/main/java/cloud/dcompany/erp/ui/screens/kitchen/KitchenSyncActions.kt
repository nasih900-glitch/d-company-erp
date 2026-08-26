package cloud.dcompany.erp.ui.screens.kitchen

/**
 * Keeps KDS polling and writes on their narrow paths. A broad SyncEngine pass
 * is deliberately not a dependency, so neither action can accidentally start
 * POS/shift work.
 */
internal class KitchenSyncActions(
    private val pullActiveQueue: () -> Unit,
    private val pullHistory: () -> Unit,
    private val drainKitchenOutbox: () -> Unit,
) {
    fun refresh(includeServed: Boolean) {
        if (includeServed) pullHistory() else pullActiveQueue()
    }

    fun advancesQueued() = drainKitchenOutbox()
}

package cloud.dcompany.erp.core.sync

/** Stable identities for replayable inventory create outbox rows. */
internal object InventoryCreateReplayPolicy {
    fun ingredientActionId(localId: String): String = "ingredient-create:$localId"

    fun supplierActionId(localId: String): String = "supplier-create:$localId"
}

package cloud.dcompany.erp.core.sync

/**
 * Expands one backend realtime resource into every Android cache affected by
 * that write. The server deliberately emits one path-derived resource, while
 * some workflows cross screen boundaries.
 *
 * `orders` owns the held-order pull in [SyncEngine], so depending on `orders`
 * is also how Gaming makes a newly handed-off bill visible at another till.
 */
internal object RealtimeRefreshPolicy {
    private val affectedResources = mapOf(
        // Creating, paying, voiding, or otherwise completing an order can
        // change whether its cafe table is occupied or available.
        "orders" to listOf("orders", "tables"),
        // Kitchen transitions update OrderLine.kitchen_status, which Tables
        // renders from its own active-bill cache. Refresh both projections so
        // a served item cannot remain actionable as "Queued" on the table.
        // This dependency is shared by realtime events from another tablet
        // and the originating tablet's narrow KDS mutation pass.
        "kitchen" to listOf("kitchen", "tables"),
        // A gaming handoff creates a held POS order. It has no cafe-table
        // relationship, so a tables refresh would only add unnecessary I/O.
        "gaming" to listOf("gaming", "orders"),
    )

    /**
     * Kitchen writes already have a dedicated durable outbox and scoped sync
     * pass. A kitchen realtime event therefore needs only its affected cache
     * pulls; using the global drain would wake unrelated POS/finance work.
     */
    fun requiresBroadOutboxDrain(changedResource: String): Boolean =
        changedResource != "kitchen"

    fun resourcesFor(changedResource: String): List<String> =
        affectedResources[changedResource] ?: listOf(changedResource)
}

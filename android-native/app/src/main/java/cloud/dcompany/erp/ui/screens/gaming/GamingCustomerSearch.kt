package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.LocalCustomerEntity

data class GamingCustomerOption(
    /** Null for an unsynced local create; it must use snapshot resolution. */
    val customerId: String?,
    val name: String?,
    val phone: String,
    val pending: Boolean,
)

internal fun projectGamingCustomers(
    cache: List<CustomerCacheEntity>,
    local: List<LocalCustomerEntity>,
): List<GamingCustomerOption> {
    val cacheById = cache.associateBy { it.id }
    // An unsynced create has no stable server identity. Offering it here would
    // race the gaming outbox ahead of the customer outbox and could split one
    // formatted phone into two records, so it remains available only through
    // the explicit Add new snapshot flow until customer sync assigns serverId.
    val stableLocal = local.filter { it.serverId != null }
    val overriddenIds = stableLocal.mapNotNull { it.serverId }.toSet()
    val projected = ArrayList<GamingCustomerOption>(cache.size + stableLocal.size)
    stableLocal.forEach { row ->
        val cached = row.serverId?.let(cacheById::get)
        projected += GamingCustomerOption(
            customerId = row.serverId,
            name = row.name ?: cached?.name,
            phone = row.phone ?: cached?.phone ?: "",
            pending = true,
        )
    }
    cache.forEach { row ->
        if (row.id !in overriddenIds) {
            projected += GamingCustomerOption(row.id, row.name, row.phone, pending = false)
        }
    }
    return projected.sortedWith(compareBy<GamingCustomerOption> { it.name?.lowercase().orEmpty() }.thenBy { it.phone })
}

internal fun filterGamingCustomerOptions(
    customers: List<GamingCustomerOption>,
    rawQuery: String,
): List<GamingCustomerOption> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return emptyList()
    return customers.filter {
        it.phone.contains(query, ignoreCase = true) ||
            it.name.orEmpty().contains(query, ignoreCase = true)
    }
}

internal fun onlineGamingCustomerQuery(rawQuery: String, online: Boolean): String? =
    rawQuery.trim().takeIf { online && it.length >= 2 }

internal fun isCurrentGamingCustomerSearch(completedGeneration: Long, currentGeneration: Long): Boolean =
    completedGeneration == currentGeneration

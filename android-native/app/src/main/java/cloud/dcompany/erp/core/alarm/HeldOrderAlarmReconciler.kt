package cloud.dcompany.erp.core.alarm

import android.content.Context
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val HELD_ORDER_ALARM_AGE_MILLIS = 15L * 60L * 1_000L

internal data class HeldOrderAlarmCandidate(
    val orderId: String,
    val sourceLabel: String,
    val heldAtMillis: Long,
) {
    val tag: String get() = "held-order-$orderId"
    val triggerAtMillis: Long get() = heldAtMillis + HELD_ORDER_ALARM_AGE_MILLIS

    fun toAlarm(): OperationalAlarmSpec = OperationalAlarmSpec(
        kind = OperationalAlarmKind.HELD_ORDER,
        tag = tag,
        triggerAtMillis = triggerAtMillis,
        title = "Held order waiting — $sourceLabel",
        body = "This bill has waited at least 15 minutes. Open POS to review and collect it.",
        target = OperationalNotificationTarget.HeldOrder(orderId),
    )
}

/** Strictly accepts timezone-bearing server timestamps; local guesses are unsafe. */
internal fun authoritativeEpochMillis(value: String?): Long? {
    val timestamp = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrElse {
        runCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrNull()
    }
}

internal fun heldOrderAlarmCandidates(
    orders: List<HeldOrderCacheEntity>,
    locallyConfirmedOrderIds: Set<String>,
): List<HeldOrderAlarmCandidate> = orders.asSequence()
    .filter { it.id !in locallyConfirmedOrderIds }
    .mapNotNull { order ->
        // Legacy rows may predate held_at. createdAt is still a server
        // timestamp (never Room arrival time), so it is the only safe fallback.
        val heldAt = authoritativeEpochMillis(order.heldAt ?: order.createdAt)
            ?: return@mapNotNull null
        HeldOrderAlarmCandidate(
            orderId = order.id,
            sourceLabel = order.sourceLabel ?: order.invoiceNo ?: "Held order",
            heldAtMillis = heldAt,
        )
    }
    .distinctBy(HeldOrderAlarmCandidate::tag)
    .sortedWith(compareBy(HeldOrderAlarmCandidate::triggerAtMillis, HeldOrderAlarmCandidate::tag))
    .toList()

internal fun overdueHeldOrderIds(
    orders: List<HeldOrderCacheEntity>,
    locallyConfirmedOrderIds: Set<String>,
    nowMillis: Long,
): List<String> = heldOrderAlarmCandidates(orders, locallyConfirmedOrderIds)
    .filter { it.triggerAtMillis <= nowMillis }
    .map(HeldOrderAlarmCandidate::orderId)

internal fun overdueHeldOrderFingerprint(orderIds: List<String>): String =
    orderIds.sorted().joinToString("|")

internal fun shouldShowOverdueHeldOrderBanner(
    overdueOrderIds: List<String>,
    mutedFingerprint: String?,
    mutedUntilMillis: Long,
    nowMillis: Long,
): Boolean {
    if (overdueOrderIds.isEmpty()) return false
    val sameWork = mutedFingerprint == overdueHeldOrderFingerprint(overdueOrderIds)
    return !sameWork || mutedUntilMillis <= nowMillis
}

object HeldOrderAlarmReconciler {
    private val mutex = Mutex()

    internal suspend fun currentAlarms(context: Context): List<OperationalAlarmSpec> {
        if (!OperationalAlarmRuntime.hasActiveOwnedScope(context)) return emptyList()
        val lease = DCompanyApp.instance.cacheIsolation.currentLease() ?: return emptyList()
        val dao = DCompanyApp.instance.db.heldOrderDao()
        val alarms = heldOrderAlarmCandidates(
            orders = dao.allForAlarms(),
            locallyConfirmedOrderIds = dao.confirmedTargetIdsForAlarms().toSet(),
        ).map(HeldOrderAlarmCandidate::toAlarm)
        return alarms.takeIf { DCompanyApp.instance.cacheIsolation.currentLease() == lease }
            ?: emptyList()
    }

    suspend fun reconcile(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            OperationalAlarmRegistry.reconcile(
                context = appContext,
                kind = OperationalAlarmKind.HELD_ORDER,
                desired = currentAlarms(appContext),
            )
        }
    }
}

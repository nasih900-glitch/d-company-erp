package cloud.dcompany.erp.ui.screens.home

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.db.ReportSnapshotDao
import cloud.dcompany.erp.core.db.ReportSnapshotEntity
import cloud.dcompany.erp.core.net.ApiClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private const val HomeSchemaVersion = 1
private const val HomeProfileBasis = "pos_sales_tip_exclusive_local_hour_profile"
private const val HomeCachePrefix = "home:v1:"

@Serializable
internal data class HomeAggregateMetric(
    val current: Long,
    val previous: Long,
    val deltaPct: Double?,
    val comparisonStatus: String,
)

@Serializable
internal data class HomeHourPoint(
    val hour: Int,
    val revenueMinor: Long,
    val ordersCount: Long,
)

@Serializable
internal data class HomeAggregateSnapshot(
    val schemaVersion: Int,
    val ownerUserId: String,
    val ownerCompanyId: String,
    val branchId: String,
    val terminalId: String?,
    val businessDate: String,
    val timezone: String,
    val snapshotAtMillis: Long,
    val revenue: HomeAggregateMetric,
    val orders: HomeAggregateMetric,
    val customerCount: Long,
    val customerScope: String,
    val customerComparisonStatus: String,
    val activeStationCapacity: Long,
    val openSessions: Long,
    val dayLengthMinutes: Int,
    val profileBasis: String,
    val profileReconcilesToDailyGross: Boolean,
    val profileRevenueMinor: Long,
    val profileOrdersCount: Long,
    val hourly: List<HomeHourPoint>,
)

internal data class HomeActivity(
    val kind: String,
    val occurredAt: Instant,
    val displayReference: String,
    val title: String,
    val amountMinor: Long?,
    val scope: String,
)

internal data class HomeActivityCapabilities(
    val pos: String,
    val gaming: String,
)

internal data class ValidatedHomePayload(
    val aggregate: HomeAggregateSnapshot,
    val activity: List<HomeActivity>,
    val capabilities: HomeActivityCapabilities,
)

internal data class CachedHomeAggregate(
    val key: String,
    val aggregate: HomeAggregateSnapshot,
    val fetchedAtMillis: Long,
)

internal data class HomeCacheRow(
    val key: String,
    val jsonBody: String,
    val fetchedAtMillis: Long,
)

internal interface HomeCacheStore {
    suspend fun latest(prefix: String): HomeCacheRow?
    suspend fun put(row: HomeCacheRow)
    suspend fun deletePrefix(prefix: String): Int
}

internal class RoomHomeCacheStore(private val dao: ReportSnapshotDao) : HomeCacheStore {
    override suspend fun latest(prefix: String): HomeCacheRow? =
        dao.latestWithPrefix(prefix)?.let {
            HomeCacheRow(it.key, it.jsonBody, it.fetchedAtMillis)
        }

    override suspend fun put(row: HomeCacheRow) {
        dao.put(ReportSnapshotEntity(row.key, row.jsonBody, row.fetchedAtMillis))
    }

    override suspend fun deletePrefix(prefix: String): Int = dao.deleteWithPrefix(prefix)
}

internal class HomeContractException(message: String) : IllegalArgumentException(message)

internal class HomeRepository(
    private val api: HomeApi,
    private val cache: HomeCacheStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun validateLease(lease: CacheScopeLease) {
        canonicalUuid(lease.scope.branchId, "selected branch")
        lease.scope.terminalId?.let { canonicalUuid(it, "selected terminal") }
    }

    suspend fun fetch(lease: CacheScopeLease): ValidatedHomePayload =
        fetch(lease, api)

    suspend fun fetch(lease: CacheScopeLease, requestApi: HomeApi): ValidatedHomePayload =
        validateHomePayload(requestApi.home(), lease, clock)

    suspend fun latestCached(lease: CacheScopeLease): CachedHomeAggregate? {
        val branchId = canonicalUuid(lease.scope.branchId, "selected branch")
        val prefix = "$HomeCachePrefix$branchId:"
        val row = try {
            cache.latest(prefix)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        return try {
            val decoded = withContext(Dispatchers.Default) {
                ApiClient.json.decodeFromString<HomeAggregateSnapshot>(row.jsonBody)
            }
            validateCachedAggregate(row.key, decoded, lease, clock)
            CachedHomeAggregate(row.key, decoded, row.fetchedAtMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun store(lease: CacheScopeLease, aggregate: HomeAggregateSnapshot): Boolean {
        validateCachedAggregate(homeCacheKey(aggregate.branchId, aggregate.businessDate), aggregate, lease, clock)
        return try {
            val body = withContext(Dispatchers.Default) {
                ApiClient.json.encodeToString(aggregate)
            }
            cache.put(HomeCacheRow(homeCacheKey(aggregate.branchId, aggregate.businessDate), body, clock.millis()))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteScope(lease: CacheScopeLease): Boolean = try {
        cache.deletePrefix(homeCachePrefix(lease))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun homeCachePrefix(lease: CacheScopeLease): String =
    "$HomeCachePrefix${canonicalUuid(lease.scope.branchId, "selected branch")}:"

internal fun homeCacheKey(branchId: String, businessDate: String): String =
    "$HomeCachePrefix${canonicalUuid(branchId, "branch")}:${LocalDate.parse(businessDate)}"

internal fun validateHomePayload(
    wire: HomeReadModelWire,
    lease: CacheScopeLease,
    clock: Clock,
): ValidatedHomePayload {
    requireContract(wire.schemaVersion == HomeSchemaVersion, "Unsupported Home schema.")
    val branchId = canonicalUuid(wire.branchId, "Home branch")
    val expectedBranch = canonicalUuid(lease.scope.branchId, "selected branch")
    requireContract(branchId == expectedBranch, "Home branch does not match the active workspace.")
    val terminalId = requiredNullableString(wire.terminalId, "Home terminal")
        ?.let { canonicalUuid(it, "Home terminal") }
    val expectedTerminal = lease.scope.terminalId?.let { canonicalUuid(it, "selected terminal") }
    requireContract(terminalId == expectedTerminal, "Home terminal does not match the active workspace.")

    val zone = parseZone(wire.timezone)
    val businessDate = parseDate(wire.businessDate, "Home business date")
    requireContract(
        businessDate == LocalDate.now(clock.withZone(zone)),
        "Home business date is not current for its timezone.",
    )
    val snapshotAt = parseInstant(wire.snapshotAt, "Home snapshot time")
    val expectedDayMinutes = Duration.between(
        businessDate.atStartOfDay(zone),
        businessDate.plusDays(1).atStartOfDay(zone),
    ).toMinutes().toInt()

    val revenue = validateMetric(wire.revenue, countMetric = false, label = "revenue")
    val orders = validateMetric(wire.orders, countMetric = true, label = "orders")
    requireContract(wire.customers.current >= 0, "Customer count cannot be negative.")
    requireContract(wire.customers.scope == "company_live_directory", "Unknown customer scope.")
    requireContract(
        wire.customers.comparisonStatus == "unavailable_no_immutable_history",
        "Unknown customer comparison state.",
    )
    requireContract(wire.gaming.activeStationCapacity >= 0, "Station capacity cannot be negative.")
    requireContract(wire.gaming.openSessions >= 0, "Open session count cannot be negative.")

    val profile = wire.localHourProfile
    requireContract(profile.timezone == wire.timezone, "Home profile timezone does not match.")
    requireContract(profile.businessDate == wire.businessDate, "Home profile date does not match.")
    requireContract(profile.dayLengthMinutes == expectedDayMinutes, "Home profile day length is invalid.")
    requireContract(profile.basis == HomeProfileBasis, "Unknown Home profile basis.")
    requireContract(!profile.reconcilesToDailyGross, "POS hourly profile cannot claim all-business reconciliation.")
    requireContract(profile.totalOrdersCount >= 0, "Profile order count cannot be negative.")
    requireContract(profile.points.size == 24, "Home profile must contain 24 local-hour bins.")
    val sortedPoints = profile.points.sortedBy(HomeHourPointWire::hour)
    requireContract(sortedPoints.map(HomeHourPointWire::hour) == (0..23).toList(), "Home profile hours must be unique 0 through 23.")
    var revenueSum = 0L
    var orderSum = 0L
    val hourly = sortedPoints.map { point ->
        requireContract(point.ordersCount >= 0, "Hourly order count cannot be negative.")
        revenueSum = addExact(revenueSum, point.revenueMinor, "Hourly revenue overflow.")
        orderSum = addExact(orderSum, point.ordersCount, "Hourly order count overflow.")
        HomeHourPoint(point.hour, point.revenueMinor, point.ordersCount)
    }
    requireContract(revenueSum == profile.totalRevenueMinor, "Hourly revenue does not conserve its POS total.")
    requireContract(orderSum == profile.totalOrdersCount, "Hourly orders do not conserve their POS total.")

    val capabilities = validateCapabilities(wire.activityCapabilities, terminalId)
    requireContract(wire.recentActivity.size <= 8, "Home activity exceeds its bounded contract.")
    val activity = wire.recentActivity.map { row -> validateActivity(row, capabilities) }
    val aggregate = HomeAggregateSnapshot(
        schemaVersion = HomeSchemaVersion,
        ownerUserId = lease.scope.userId,
        ownerCompanyId = lease.scope.companyId,
        branchId = branchId,
        terminalId = terminalId,
        businessDate = businessDate.toString(),
        timezone = zone.id,
        snapshotAtMillis = snapshotAt.toEpochMilli(),
        revenue = revenue,
        orders = orders,
        customerCount = wire.customers.current,
        customerScope = wire.customers.scope,
        customerComparisonStatus = wire.customers.comparisonStatus,
        activeStationCapacity = wire.gaming.activeStationCapacity,
        openSessions = wire.gaming.openSessions,
        dayLengthMinutes = expectedDayMinutes,
        profileBasis = profile.basis,
        profileReconcilesToDailyGross = false,
        profileRevenueMinor = profile.totalRevenueMinor,
        profileOrdersCount = profile.totalOrdersCount,
        hourly = hourly,
    )
    validateCachedAggregate(homeCacheKey(branchId, businessDate.toString()), aggregate, lease, clock)
    return ValidatedHomePayload(aggregate, activity, capabilities)
}

internal fun validateCachedAggregate(
    key: String,
    value: HomeAggregateSnapshot,
    lease: CacheScopeLease,
    clock: Clock,
) {
    requireContract(value.schemaVersion == HomeSchemaVersion, "Unsupported saved Home schema.")
    requireContract(value.ownerUserId == lease.scope.userId, "Saved Home user does not match.")
    requireContract(value.ownerCompanyId == lease.scope.companyId, "Saved Home company does not match.")
    val branch = canonicalUuid(value.branchId, "saved Home branch")
    requireContract(branch == canonicalUuid(lease.scope.branchId, "selected branch"), "Saved Home branch does not match.")
    val terminal = value.terminalId?.let { canonicalUuid(it, "saved Home terminal") }
    val expectedTerminal = lease.scope.terminalId?.let { canonicalUuid(it, "selected terminal") }
    requireContract(terminal == expectedTerminal, "Saved Home terminal does not match.")
    val zone = parseZone(value.timezone)
    val date = parseDate(value.businessDate, "saved Home date")
    requireContract(date == LocalDate.now(clock.withZone(zone)), "Saved Home snapshot is not for today.")
    requireContract(key == homeCacheKey(branch, date.toString()), "Saved Home key does not match its payload.")
    requireContract(value.customerScope == "company_live_directory", "Saved customer scope is invalid.")
    requireContract(value.customerComparisonStatus == "unavailable_no_immutable_history", "Saved customer status is invalid.")
    requireContract(value.customerCount >= 0 && value.activeStationCapacity >= 0 && value.openSessions >= 0, "Saved Home counts are invalid.")
    requireContract(value.profileBasis == HomeProfileBasis && !value.profileReconcilesToDailyGross, "Saved Home profile basis is invalid.")
    requireContract(value.hourly.map(HomeHourPoint::hour) == (0..23).toList(), "Saved Home profile hours are invalid.")
    requireContract(value.profileOrdersCount >= 0 && value.hourly.all { it.ordersCount >= 0 }, "Saved Home order counts are invalid.")
    val expectedDayMinutes = Duration.between(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone)).toMinutes().toInt()
    requireContract(value.dayLengthMinutes == expectedDayMinutes, "Saved Home day length is invalid.")
    var revenueSum = 0L
    var orderSum = 0L
    value.hourly.forEach {
        revenueSum = addExact(revenueSum, it.revenueMinor, "Saved hourly revenue overflow.")
        orderSum = addExact(orderSum, it.ordersCount, "Saved hourly order count overflow.")
    }
    requireContract(revenueSum == value.profileRevenueMinor && orderSum == value.profileOrdersCount, "Saved Home totals are invalid.")
    validateMetric(value.revenue, countMetric = false, label = "saved revenue")
    validateMetric(value.orders, countMetric = true, label = "saved orders")
    Instant.ofEpochMilli(value.snapshotAtMillis)
}

private fun validateMetric(wire: HomeMetricWire, countMetric: Boolean, label: String): HomeAggregateMetric =
    validateMetric(
        HomeAggregateMetric(
            wire.current,
            wire.previous,
            requiredNullableDouble(wire.deltaPct, "$label comparison"),
            wire.comparisonStatus,
        ),
        countMetric,
        label,
    )

private fun validateMetric(value: HomeAggregateMetric, countMetric: Boolean, label: String): HomeAggregateMetric {
    if (countMetric) requireContract(value.current >= 0 && value.previous >= 0, "$label counts cannot be negative.")
    requireContract(value.deltaPct == null || value.deltaPct.isFinite(), "$label comparison is not finite.")
    when (value.comparisonStatus) {
        "available" -> {
            requireContract(
                if (countMetric) value.previous > 0 else value.previous != 0L,
                "$label baseline must be nonzero when comparison is available.",
            )
            requireContract(value.deltaPct != null, "$label comparison is missing.")
        }
        "no_baseline" -> {
            requireContract(value.previous == 0L, "$label no-baseline state contradicts its baseline.")
            requireContract(value.deltaPct == null, "$label no-baseline state cannot contain a percentage.")
        }
        else -> throw HomeContractException("Unknown $label comparison status.")
    }
    return value
}

private fun validateCapabilities(wire: HomeActivityCapabilitiesWire, terminalId: String?): HomeActivityCapabilities {
    requireContract(wire.pos in setOf("available", "missing_permission", "missing_terminal"), "Unknown POS activity capability.")
    requireContract(wire.gaming in setOf("available", "missing_permission"), "Unknown Gaming activity capability.")
    requireContract(terminalId != null || wire.pos == "missing_terminal", "POS activity without a terminal must be suppressed.")
    requireContract(terminalId == null || wire.pos != "missing_terminal", "POS terminal capability contradicts the active terminal.")
    return HomeActivityCapabilities(wire.pos, wire.gaming)
}

private fun validateActivity(row: HomeActivityWire, capabilities: HomeActivityCapabilities): HomeActivity {
    val isPos = row.kind == "pos_order_paid" || row.kind == "pos_shift_opened"
    val isGaming = row.kind == "gaming_session_started"
    requireContract(isPos || isGaming, "Unknown Home activity kind.")
    requireContract(row.scope == if (isPos) "terminal" else "branch", "Home activity scope is invalid.")
    requireContract(!isPos || capabilities.pos == "available", "POS activity contradicts its capability.")
    requireContract(!isGaming || capabilities.gaming == "available", "Gaming activity contradicts its capability.")
    requireContract(row.displayReference.isNotBlank() && row.displayReference.length <= 96, "Home activity reference is invalid.")
    requireContract(row.title.isNotBlank() && row.title.length <= 160, "Home activity title is invalid.")
    val amountMinor = requiredNullableLong(row.amountMinor, "Home activity amount")
    requireContract(!isPos || row.kind != "pos_order_paid" || amountMinor != null, "Paid order activity amount is required.")
    requireContract(row.kind == "pos_order_paid" || amountMinor == null, "Non-order activity cannot contain money.")
    return HomeActivity(row.kind, parseInstant(row.occurredAt, "Home activity time"), row.displayReference, row.title, amountMinor, row.scope)
}

private fun requiredNullableString(value: JsonElement, label: String): String? = when (value) {
    JsonNull -> null
    is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
        ?: throw HomeContractException("$label must be a string or null.")
    else -> throw HomeContractException("$label must be a string or null.")
}

private fun requiredNullableDouble(value: JsonElement, label: String): Double? = when (value) {
    JsonNull -> null
    is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)?.doubleOrNull
        ?: throw HomeContractException("$label must be a number or null.")
    else -> throw HomeContractException("$label must be a number or null.")
}

private fun requiredNullableLong(value: JsonElement, label: String): Long? = when (value) {
    JsonNull -> null
    is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: throw HomeContractException("$label must be an integer or null.")
    else -> throw HomeContractException("$label must be an integer or null.")
}

private fun canonicalUuid(raw: String?, label: String): String {
    requireContract(!raw.isNullOrBlank(), "$label is required.")
    return try {
        UUID.fromString(raw).toString()
    } catch (_: IllegalArgumentException) {
        throw HomeContractException("$label is invalid.")
    }
}

private fun parseZone(raw: String): ZoneId = try {
    ZoneId.of(raw)
} catch (_: Exception) {
    throw HomeContractException("Home timezone is invalid.")
}

private fun parseDate(raw: String, label: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (_: Exception) {
    throw HomeContractException("$label is invalid.")
}

private fun parseInstant(raw: String, label: String): Instant = try {
    Instant.parse(raw)
} catch (_: Exception) {
    throw HomeContractException("$label is invalid.")
}

private fun addExact(left: Long, right: Long, message: String): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    throw HomeContractException(message)
}

private fun requireContract(condition: Boolean, message: String) {
    if (!condition) throw HomeContractException(message)
}

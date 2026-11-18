package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private val ReportsOverviewSchema = "reports.overview.v2"
private val ReportsAccountingBasis = "operational_receipt"
private val ReportsSourceBasis = "operational_receipt_source_aggregate"
private val ReportsPaymentBasis = "settlement_method_collections"
private val ReportsCustomerBasis = "current_order_linkage"
private val ReportsSessionsBasis = "non_cancelled_sessions_started"
private val ReportsProductsBasis = "gross_settled_pos_line_sales"
private val ReportsProductsRefundsBasis = "included_original_sales_no_refund_deduction"
private val ReportsDeviceBasis = "non_cancelled_sessions_ended_in_period"
private val ReportsDeviceTimeBasis = "whole_session_pause_adjusted_elapsed_ms"
private val ReportsQuantityPattern = Regex("[0-9]+(?:\\.[0-9]{1,3})?")
private val ReportsHourBasis = "pos_sales_tip_exclusive_local_hour_profile"

@Serializable
internal data class ReportsOverviewSourcesWire(
    val basis: String,
    @SerialName("food_minor") val foodMinor: Long,
    @SerialName("gaming_minor") val gamingMinor: Long,
    @SerialName("hookah_minor") val hookahMinor: Long,
    @SerialName("event_tickets_minor") val eventTicketsMinor: Long,
    @SerialName("memberships_minor") val membershipsMinor: Long,
    @SerialName("delivery_aggregator_minor") val deliveryAggregatorMinor: Long,
    @SerialName("other_minor") val otherMinor: Long,
    @SerialName("manual_collections_minor") val manualCollectionsMinor: Long,
    @SerialName("adjustments_minor") val adjustmentsMinor: Long,
    @SerialName("positive_source_denominator_minor") val positiveSourceDenominatorMinor: Long,
    @SerialName("total_minor") val totalMinor: Long,
    @SerialName("reconciles_to_gross") val reconcilesToGross: Boolean,
)

@Serializable
internal data class ReportsOverviewPaymentsWire(
    val basis: String,
    @SerialName("cash_minor") val cashMinor: Long,
    @SerialName("upi_minor") val upiMinor: Long,
    @SerialName("card_minor") val cardMinor: Long,
    @SerialName("bank_minor") val bankMinor: Long,
    @SerialName("qr_minor") val qrMinor: Long,
    @SerialName("wallet_minor") val walletMinor: Long,
    @SerialName("other_minor") val otherMinor: Long,
    @SerialName("total_minor") val totalMinor: Long,
)

@Serializable
internal data class ReportsOverviewCustomersWire(
    val basis: String,
    @SerialName("distinct_linked_customers_count") val distinctLinkedCustomersCount: Long,
    @SerialName("linked_orders_count") val linkedOrdersCount: Long,
    @SerialName("unlinked_orders_count") val unlinkedOrdersCount: Long,
)

@Serializable
internal data class ReportsOverviewHourPointWire(
    val hour: Int,
    @SerialName("revenue_minor") val revenueMinor: Long,
    @SerialName("orders_count") val ordersCount: Long,
)

@Serializable
internal data class ReportsOverviewHourAvailableWire(
    val availability: String,
    val timezone: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("day_length_minutes") val dayLengthMinutes: Int,
    val basis: String,
    @SerialName("reconciles_to_daily_gross") val reconcilesToDailyGross: Boolean,
    @SerialName("total_revenue_minor") val totalRevenueMinor: Long,
    @SerialName("total_orders_count") val totalOrdersCount: Long,
    val points: List<ReportsOverviewHourPointWire>,
)

@Serializable
internal data class ReportsOverviewHourUnavailableWire(
    val availability: String,
    val reason: String,
)

@Serializable
internal data class ReportsOverviewTopProduct(
    @SerialName("menu_item_id") val menuItemId: String,
    val name: String,
    @SerialName("product_type") val productType: String,
    @SerialName("quantity_sold") val quantitySold: String,
    @SerialName("gross_line_sales_minor") val grossLineSalesMinor: Long,
)

@Serializable
internal data class ReportsOverviewTopProductsWire(
    val basis: String,
    @SerialName("refunds_basis") val refundsBasis: String,
    @SerialName("reconciles_to_gross") val reconcilesToGross: Boolean,
    val limit: Int,
    val rows: List<ReportsOverviewTopProduct>,
)

@Serializable
internal data class ReportsOverviewDeviceUsageWire(
    @SerialName("device_type") val deviceType: String,
    @SerialName("completed_sessions_count") val completedSessionsCount: Long,
    // Required even when explicitly null: the shared JSON decoder omits absent nullable fields.
    @SerialName("play_elapsed_ms") val playElapsedMs: JsonElement,
    @SerialName("unknown_play_sessions_count") val unknownPlaySessionsCount: Long,
)

@Serializable
internal data class ReportsOverviewDevicesWire(
    val basis: String,
    val grouping: String,
    @SerialName("time_basis") val timeBasis: String,
    val rows: List<ReportsOverviewDeviceUsageWire>,
)

@Serializable
internal data class ReportsOverviewDeviceUsage(
    val deviceType: String,
    val completedSessionsCount: Long,
    // JsonElement also preserves required presence when reading a saved snapshot.
    val playElapsedMs: JsonElement,
    val unknownPlaySessionsCount: Long,
) {
    val knownPlayElapsedMs: Long? get() = (playElapsedMs as? JsonPrimitive)?.longOrNull
}

@Serializable
internal data class ReportsOverviewWire(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("period_start") val periodStart: String,
    @SerialName("period_end") val periodEnd: String,
    val timezone: String,
    @SerialName("snapshot_at") val snapshotAt: String,
    @SerialName("accounting_basis") val accountingBasis: String,
    @SerialName("gross_revenue_minor") val grossRevenueMinor: Long,
    @SerialName("orders_count") val ordersCount: Long,
    @SerialName("sessions_started_count") val sessionsStartedCount: Long,
    @SerialName("sessions_basis") val sessionsBasis: String,
    @SerialName("revenue_by_source") val revenueBySource: ReportsOverviewSourcesWire,
    @SerialName("payments_received") val paymentsReceived: ReportsOverviewPaymentsWire,
    @SerialName("linked_customers") val linkedCustomers: ReportsOverviewCustomersWire,
    @SerialName("local_hour_profile") val localHourProfile: JsonElement,
    @SerialName("top_products") val topProducts: ReportsOverviewTopProductsWire,
    @SerialName("device_usage") val deviceUsage: ReportsOverviewDevicesWire,
)

@Serializable
internal data class ReportsOverviewSources(
    val foodMinor: Long,
    val gamingMinor: Long,
    val hookahMinor: Long,
    val eventTicketsMinor: Long,
    val membershipsMinor: Long,
    val deliveryAggregatorMinor: Long,
    val otherMinor: Long,
    val manualCollectionsMinor: Long,
    val adjustmentsMinor: Long,
    val positiveSourceDenominatorMinor: Long,
    val totalMinor: Long,
) {
    val positiveSlices: List<Pair<String, Long>>
        get() = listOf(
            "Products / food" to foodMinor,
            "Gaming" to gamingMinor,
            "Shisha" to hookahMinor,
            "Event tickets" to eventTicketsMinor,
            "Memberships" to membershipsMinor,
            "Delivery aggregators" to deliveryAggregatorMinor,
            "Other" to otherMinor,
            "Manual collections" to manualCollectionsMinor,
        ).filter { it.second > 0L }
}

@Serializable
internal data class ReportsOverviewPayments(
    val cashMinor: Long,
    val upiMinor: Long,
    val cardMinor: Long,
    val bankMinor: Long,
    val qrMinor: Long,
    val walletMinor: Long,
    val otherMinor: Long,
    val totalMinor: Long,
) {
    val components: List<Pair<String, Long>>
        get() = listOf(
            "Cash" to cashMinor,
            "UPI" to upiMinor,
            "Card" to cardMinor,
            "Bank" to bankMinor,
            "QR" to qrMinor,
            "Wallet" to walletMinor,
            "Other" to otherMinor,
        )
}

@Serializable
internal data class ReportsOverviewHourPoint(
    val hour: Int,
    val revenueMinor: Long,
    val ordersCount: Long,
)

@Serializable
internal sealed interface ReportsOverviewHourProfile {
    @Serializable
    data class Available(
        val timezone: String,
        val businessDate: String,
        val dayLengthMinutes: Int,
        val totalRevenueMinor: Long,
        val totalOrdersCount: Long,
        val points: List<ReportsOverviewHourPoint>,
    ) : ReportsOverviewHourProfile

    @Serializable
    data object MultiDayUnavailable : ReportsOverviewHourProfile
}

@Serializable
internal data class ValidatedReportsOverview(
    val branchId: String,
    val periodStart: String,
    val periodEnd: String,
    val timezone: String,
    val snapshotAtMillis: Long,
    val grossRevenueMinor: Long,
    val ordersCount: Long,
    val sessionsStartedCount: Long,
    val distinctLinkedCustomersCount: Long,
    val linkedOrdersCount: Long,
    val unlinkedOrdersCount: Long,
    val sources: ReportsOverviewSources,
    val payments: ReportsOverviewPayments,
    val localHourProfile: ReportsOverviewHourProfile,
    val topProducts: List<ReportsOverviewTopProduct>,
    val deviceUsage: List<ReportsOverviewDeviceUsage>,
)

internal class ReportsContractException(message: String) : IllegalArgumentException(message)

internal fun validateReportsOverview(
    wire: ReportsOverviewWire,
    lease: CacheScopeLease,
    requestedStart: LocalDate?,
    requestedEnd: LocalDate?,
): ValidatedReportsOverview {
    contract(wire.schemaVersion == ReportsOverviewSchema, "Unsupported Reports Overview schema.")
    contract(wire.accountingBasis == ReportsAccountingBasis, "Unknown Reports accounting basis.")
    contract(wire.sessionsBasis == ReportsSessionsBasis, "Unknown session-count basis.")
    val branchId = canonicalReportsUuid(wire.branchId, "Reports branch")
    val expectedBranch = canonicalReportsUuid(lease.scope.branchId, "selected branch")
    contract(branchId == expectedBranch, "Reports branch does not match the active workspace.")
    val start = parseReportsDate(wire.periodStart, "period start")
    val end = parseReportsDate(wire.periodEnd, "period end")
    if (requestedStart == null || requestedEnd == null) {
        contract(requestedStart == null && requestedEnd == null,
            "Reports range parameters must both be present or both be omitted.")
        contract(start == end, "The server-default Overview must resolve to one business day.")
    } else {
        contract(start == requestedStart && end == requestedEnd, "Reports range does not match the request.")
    }
    contract(!end.isBefore(start), "Reports range is reversed.")
    contract(Duration.between(start.atStartOfDay(), end.plusDays(1).atStartOfDay()).toDays() in 1..366,
        "Reports range is outside the supported limit.")
    parseReportsZone(wire.timezone)
    val snapshotAt = parseReportsInstant(wire.snapshotAt)
    nonnegative(wire.ordersCount, "Order count")
    nonnegative(wire.sessionsStartedCount, "Session count")

    val source = wire.revenueBySource
    contract(source.basis == ReportsSourceBasis, "Unknown revenue-source basis.")
    contract(source.reconcilesToGross, "Revenue sources do not claim gross reconciliation.")
    val rawSources = listOf(
        source.foodMinor, source.gamingMinor, source.hookahMinor, source.eventTicketsMinor,
        source.membershipsMinor, source.deliveryAggregatorMinor, source.otherMinor,
        source.manualCollectionsMinor,
    )
    val denominator = reportsExactSum(rawSources.map { it.coerceAtLeast(0L) }, "Revenue-source amounts overflow.")
    contract(denominator == source.positiveSourceDenominatorMinor, "Revenue-source denominator is inconsistent.")
    contract(reportsExactAdd(denominator, source.adjustmentsMinor, "Revenue-source total overflows.") == source.totalMinor,
        "Revenue sources do not reconcile to their total.")
    contract(source.totalMinor == wire.grossRevenueMinor, "Revenue sources do not reconcile to gross revenue.")

    val payment = wire.paymentsReceived
    contract(payment.basis == ReportsPaymentBasis, "Unknown payment basis.")
    val paymentComponents = listOf(
        payment.cashMinor, payment.upiMinor, payment.cardMinor, payment.bankMinor,
        payment.qrMinor, payment.walletMinor, payment.otherMinor,
    )
    paymentComponents.forEach { nonnegative(it, "Payment amount") }
    val paymentTotal = reportsExactSum(paymentComponents, "Payment total overflows.")
    contract(paymentTotal == payment.totalMinor, "Payment methods do not reconcile to their total.")

    val customers = wire.linkedCustomers
    contract(customers.basis == ReportsCustomerBasis, "Unknown linked-customer basis.")
    nonnegative(customers.distinctLinkedCustomersCount, "Linked-customer count")
    nonnegative(customers.linkedOrdersCount, "Linked-order count")
    nonnegative(customers.unlinkedOrdersCount, "Unlinked-order count")
    contract(reportsExactAdd(
        customers.linkedOrdersCount,
        customers.unlinkedOrdersCount,
        "Linked-order count overflows.",
    ) == wire.ordersCount,
        "Linked and unlinked orders do not reconcile to the order count.")
    contract(customers.distinctLinkedCustomersCount <= customers.linkedOrdersCount,
        "Distinct linked customers cannot exceed linked orders.")

    val products = validateTopProducts(wire.topProducts)
    val devices = validateDeviceUsage(wire.deviceUsage)
    val localHour = decodeHourProfile(wire.localHourProfile, start, end, wire.timezone)
    return ValidatedReportsOverview(
        branchId = branchId,
        periodStart = start.toString(),
        periodEnd = end.toString(),
        timezone = wire.timezone,
        snapshotAtMillis = snapshotAt.toEpochMilli(),
        grossRevenueMinor = wire.grossRevenueMinor,
        ordersCount = wire.ordersCount,
        sessionsStartedCount = wire.sessionsStartedCount,
        distinctLinkedCustomersCount = customers.distinctLinkedCustomersCount,
        linkedOrdersCount = customers.linkedOrdersCount,
        unlinkedOrdersCount = customers.unlinkedOrdersCount,
        sources = ReportsOverviewSources(
            source.foodMinor, source.gamingMinor, source.hookahMinor, source.eventTicketsMinor,
            source.membershipsMinor, source.deliveryAggregatorMinor, source.otherMinor,
            source.manualCollectionsMinor, source.adjustmentsMinor,
            source.positiveSourceDenominatorMinor, source.totalMinor,
        ),
        payments = ReportsOverviewPayments(
            payment.cashMinor, payment.upiMinor, payment.cardMinor, payment.bankMinor,
            payment.qrMinor, payment.walletMinor, payment.otherMinor, payment.totalMinor,
        ),
        localHourProfile = localHour,
        topProducts = products,
        deviceUsage = devices,
    )
}

/**
 * Replays the complete wire contract over a saved aggregate. Cache bytes are
 * local input too: corruption or an older app must not bypass arithmetic,
 * range, timezone, DST or branch checks merely because the row was once
 * written by this process.
 */
internal fun validateCachedReportsOverview(
    cached: ValidatedReportsOverview,
    lease: CacheScopeLease,
    requestedStart: LocalDate,
    requestedEnd: LocalDate,
) {
    val profile = when (val local = cached.localHourProfile) {
        ReportsOverviewHourProfile.MultiDayUnavailable -> ApiClient.json.encodeToJsonElement(
            ReportsOverviewHourUnavailableWire("unavailable", "multi_day_range"),
        )
        is ReportsOverviewHourProfile.Available -> ApiClient.json.encodeToJsonElement(
            ReportsOverviewHourAvailableWire(
                availability = "available",
                timezone = local.timezone,
                businessDate = local.businessDate,
                dayLengthMinutes = local.dayLengthMinutes,
                basis = ReportsHourBasis,
                reconcilesToDailyGross = false,
                totalRevenueMinor = local.totalRevenueMinor,
                totalOrdersCount = local.totalOrdersCount,
                points = local.points.map {
                    ReportsOverviewHourPointWire(it.hour, it.revenueMinor, it.ordersCount)
                },
            ),
        )
    }
    validateReportsOverview(
        wire = ReportsOverviewWire(
            schemaVersion = ReportsOverviewSchema,
            branchId = cached.branchId,
            periodStart = cached.periodStart,
            periodEnd = cached.periodEnd,
            timezone = cached.timezone,
            snapshotAt = Instant.ofEpochMilli(cached.snapshotAtMillis).toString(),
            accountingBasis = ReportsAccountingBasis,
            grossRevenueMinor = cached.grossRevenueMinor,
            ordersCount = cached.ordersCount,
            sessionsStartedCount = cached.sessionsStartedCount,
            sessionsBasis = ReportsSessionsBasis,
            revenueBySource = cached.sources.let {
                ReportsOverviewSourcesWire(
                    ReportsSourceBasis,
                    it.foodMinor,
                    it.gamingMinor,
                    it.hookahMinor,
                    it.eventTicketsMinor,
                    it.membershipsMinor,
                    it.deliveryAggregatorMinor,
                    it.otherMinor,
                    it.manualCollectionsMinor,
                    it.adjustmentsMinor,
                    it.positiveSourceDenominatorMinor,
                    it.totalMinor,
                    reconcilesToGross = true,
                )
            },
            paymentsReceived = cached.payments.let {
                ReportsOverviewPaymentsWire(
                    ReportsPaymentBasis,
                    it.cashMinor,
                    it.upiMinor,
                    it.cardMinor,
                    it.bankMinor,
                    it.qrMinor,
                    it.walletMinor,
                    it.otherMinor,
                    it.totalMinor,
                )
            },
            linkedCustomers = ReportsOverviewCustomersWire(
                ReportsCustomerBasis,
                cached.distinctLinkedCustomersCount,
                cached.linkedOrdersCount,
                cached.unlinkedOrdersCount,
            ),
            localHourProfile = profile,
            topProducts = ReportsOverviewTopProductsWire(
                ReportsProductsBasis, ReportsProductsRefundsBasis, false, 5, cached.topProducts,
            ),
            deviceUsage = ReportsOverviewDevicesWire(
                ReportsDeviceBasis, "device_type", ReportsDeviceTimeBasis,
                cached.deviceUsage.map {
                    ReportsOverviewDeviceUsageWire(
                        it.deviceType, it.completedSessionsCount, it.playElapsedMs, it.unknownPlaySessionsCount,
                    )
                },
            ),
        ),
        lease = lease,
        requestedStart = requestedStart,
        requestedEnd = requestedEnd,
    )
}

private fun validateTopProducts(panel: ReportsOverviewTopProductsWire): List<ReportsOverviewTopProduct> {
    contract(panel.basis == ReportsProductsBasis && panel.refundsBasis == ReportsProductsRefundsBasis,
        "Unknown top-products sales basis.")
    contract(!panel.reconcilesToGross && panel.limit == 5 && panel.rows.size <= panel.limit,
        "Invalid top-products cohort.")
    panel.rows.forEach { row ->
        contract(canonicalReportsUuid(row.menuItemId, "Product identity") == row.menuItemId,
            "Product identity must be canonical.")
        contract(row.name.isNotBlank() && row.name.codePointCount(0, row.name.length) <= 200 &&
            row.productType.isNotBlank() && row.productType.codePointCount(0, row.productType.length) <= 20, "Invalid historical product identity.")
        contract(ReportsQuantityPattern.matches(row.quantitySold), "Invalid product quantity.")
        contract(BigDecimal(row.quantitySold).signum() >= 0, "Product quantity cannot be negative.")
    }
    contract(panel.rows.map { Triple(it.menuItemId, it.name, it.productType) }.distinct().size == panel.rows.size,
        "Duplicate historical products.")
    val expected = panel.rows.sortedWith { a, b ->
        b.grossLineSalesMinor.compareTo(a.grossLineSalesMinor).takeIf { it != 0 }
            ?: compareReportsUtf8(a.name, b.name).takeIf { it != 0 }
            ?: compareReportsUtf8(a.productType, b.productType).takeIf { it != 0 }
            ?: a.menuItemId.compareTo(b.menuItemId)
    }
    contract(panel.rows == expected, "Top products are not in their authoritative rank order.")
    return panel.rows
}

private fun validateDeviceUsage(panel: ReportsOverviewDevicesWire): List<ReportsOverviewDeviceUsage> {
    contract(panel.basis == ReportsDeviceBasis && panel.grouping == "device_type" &&
        panel.timeBasis == ReportsDeviceTimeBasis, "Unknown device-usage basis.")
    contract(panel.rows.map { it.deviceType }.distinct().size == panel.rows.size, "Duplicate device types.")
    val expected = panel.rows.sortedWith { a, b ->
        b.completedSessionsCount.compareTo(a.completedSessionsCount).takeIf { it != 0 }
            ?: compareReportsUtf8(a.deviceType, b.deviceType)
    }
    contract(panel.rows == expected, "Device types are not in their authoritative order.")
    return panel.rows.map { row ->
        contract(row.deviceType.isNotBlank() && row.deviceType.codePointCount(0, row.deviceType.length) <= 20, "Invalid device type.")
        contract(row.completedSessionsCount > 0 && row.unknownPlaySessionsCount in 0..row.completedSessionsCount,
            "Invalid completed-session counts.")
        if (row.unknownPlaySessionsCount > 0) {
            contract(row.playElapsedMs == JsonNull, "Unknown play time cannot expose a partial duration.")
        } else {
            val primitive = row.playElapsedMs as? JsonPrimitive
            contract(primitive != null && !primitive.isString && primitive.longOrNull != null,
                "Known play time must be an integer duration.")
            nonnegative(primitive!!.longOrNull!!, "Play duration")
        }
        ReportsOverviewDeviceUsage(row.deviceType, row.completedSessionsCount, row.playElapsedMs,
            row.unknownPlaySessionsCount)
    }
}

/** PostgreSQL COLLATE C orders UTF-8 bytes, including names outside the BMP. */
private fun compareReportsUtf8(a: String, b: String): Int {
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(left.size, right.size)) {
        val comparison = (left[index].toInt() and 255).compareTo(right[index].toInt() and 255)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

private fun decodeHourProfile(
    raw: JsonElement,
    start: LocalDate,
    end: LocalDate,
    overviewTimezone: String,
): ReportsOverviewHourProfile {
    val availability = raw.jsonObjectRequired("local-hour profile")["availability"]
        ?.toString()?.trim('"')
        ?: throw ReportsContractException("Local-hour availability is missing.")
    return when (availability) {
        "unavailable" -> {
            val unavailable = decodeContract<ReportsOverviewHourUnavailableWire>(raw, "local-hour profile")
            contract(start != end, "A single-day report must include its local-hour profile.")
            contract(unavailable.reason == "multi_day_range", "Unknown local-hour unavailability reason.")
            ReportsOverviewHourProfile.MultiDayUnavailable
        }
        "available" -> {
            val profile = decodeContract<ReportsOverviewHourAvailableWire>(raw, "local-hour profile")
            contract(start == end, "A multi-day report cannot expose a single-day hour profile.")
            contract(profile.timezone == overviewTimezone, "Local-hour timezone does not match the report.")
            val zone = parseReportsZone(profile.timezone)
            val day = parseReportsDate(profile.businessDate, "local-hour business date")
            contract(day == start, "Local-hour date does not match the report.")
            val expectedMinutes = Duration.between(day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone)).toMinutes().toInt()
            contract(profile.dayLengthMinutes == expectedMinutes && expectedMinutes in setOf(1_380, 1_440, 1_500),
                "Local-hour day length is invalid.")
            contract(profile.basis == ReportsHourBasis, "Unknown local-hour basis.")
            contract(!profile.reconcilesToDailyGross, "POS local-hour data cannot claim all-business reconciliation.")
            contract(profile.points.size == 24, "Local-hour profile must contain 24 bins.")
            val sorted = profile.points.sortedBy(ReportsOverviewHourPointWire::hour)
            contract(sorted.map(ReportsOverviewHourPointWire::hour) == (0..23).toList(),
                "Local-hour bins must be unique 0 through 23.")
            sorted.forEach { nonnegative(it.ordersCount, "Hourly order count") }
            val revenueTotal = reportsExactSum(sorted.map { it.revenueMinor }, "Hourly revenue overflows.")
            val orderTotal = reportsExactSum(sorted.map { it.ordersCount }, "Hourly order count overflows.")
            contract(revenueTotal == profile.totalRevenueMinor, "Hourly revenue does not reconcile.")
            contract(orderTotal == profile.totalOrdersCount, "Hourly orders do not reconcile.")
            ReportsOverviewHourProfile.Available(
                timezone = profile.timezone,
                businessDate = day.toString(),
                dayLengthMinutes = profile.dayLengthMinutes,
                totalRevenueMinor = profile.totalRevenueMinor,
                totalOrdersCount = profile.totalOrdersCount,
                points = sorted.map { ReportsOverviewHourPoint(it.hour, it.revenueMinor, it.ordersCount) },
            )
        }
        else -> throw ReportsContractException("Unknown local-hour availability state.")
    }
}

private inline fun <reified T> decodeContract(raw: JsonElement, label: String): T = try {
    ApiClient.json.decodeFromJsonElement(raw)
} catch (_: Exception) {
    throw ReportsContractException("$label is malformed.")
}

private fun JsonElement.jsonObjectRequired(label: String) =
    runCatching { jsonObject }.getOrElse {
        throw ReportsContractException("$label must be an object.")
    }

private fun canonicalReportsUuid(value: String?, label: String): String {
    val candidate = value?.trim().orEmpty()
    val parsed = runCatching { UUID.fromString(candidate) }.getOrNull()
        ?: throw ReportsContractException("$label is invalid.")
    return parsed.toString()
}

private fun parseReportsDate(value: String, label: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { throw ReportsContractException("Reports $label is invalid.") }

private fun parseReportsZone(value: String): ZoneId =
    runCatching { ZoneId.of(value) }.getOrElse { throw ReportsContractException("Reports timezone is invalid.") }

private fun parseReportsInstant(value: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw ReportsContractException("Reports snapshot time is invalid.") }

private fun nonnegative(value: Long, label: String) = contract(value >= 0L, "$label cannot be negative.")

private fun reportsExactSum(values: Iterable<Long>, message: String): Long =
    values.fold(0L) { total, value -> reportsExactAdd(total, value, message) }

private fun reportsExactAdd(left: Long, right: Long, message: String): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    throw ReportsContractException(message)
}

private fun contract(condition: Boolean, message: String) {
    if (!condition) throw ReportsContractException(message)
}

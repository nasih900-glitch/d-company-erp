package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CostingCoverage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import cloud.dcompany.erp.core.auth.ReportsAccess
import cloud.dcompany.erp.core.net.ManualBackendRequestAdmission
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import retrofit2.http.GET
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsOverviewContractTest {
    private val branch = "11111111-1111-1111-1111-111111111111"
    private val terminal = "22222222-2222-2222-2222-222222222222"
    private val scope = CacheScope("user-a", "company-a", branch, terminal)
    private val selection = ReportSelection(
        ReportPeriod.DAILY,
        LocalDate.parse("2026-10-27"),
        YearMonth.parse("2026-10"),
        "2026-27",
        3,
    )
    private val clock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `Overview money formatting is exact for zero signed and Long minimum values`() {
        assertEquals("₹0.00", 0L.asReportsRupees())
        assertEquals("-₹12.49", (-1_249L).asReportsRupees())
        assertEquals("-₹92,23,37,20,36,85,47,758.08", Long.MIN_VALUE.asReportsRupees())
    }

    @Test
    fun `Overview explicitly requests v2 and preserves exact quantities and pause adjusted time`() = runBlocking {
        val endpoint = ReportsApi::class.java.declaredMethods.single { it.name == "overview" }
            .getAnnotation(GET::class.java).value
        assertEquals("reports/overview?schema_version=reports.overview.v2", endpoint)
        val lease = activated().currentLease()!!
        val overview = validateReportsOverview(validWire(), lease, selection.range.start, selection.range.endInclusive)
        assertEquals("4.250", overview.topProducts.single().quantitySold)
        assertEquals("4.25", overview.topProducts.single().quantitySold.asReportsQuantity())
        assertEquals("9007199254740993.125", "9007199254740993.125".asReportsQuantity())
        assertEquals("0", "0.000".asReportsQuantity())
        assertEquals("1h 1m 1.123s", overview.deviceUsage[0].knownPlayElapsedMs!!.asReportsPlayDuration())
        assertNull(overview.deviceUsage[1].knownPlayElapsedMs)
        assertEquals("0s", overview.deviceUsage[2].knownPlayElapsedMs!!.asReportsPlayDuration())
        assertEquals("2562047788015h 12m 55.807s", Long.MAX_VALUE.asReportsPlayDuration())
    }

    @Test
    fun `v2 panels reject absent fields even under the shared lenient decoder`() {
        val encoded = ApiClient.json.encodeToJsonElement(validWire()).jsonObject
        for (key in listOf("top_products", "device_usage")) {
            assertThrows(SerializationException::class.java) {
                ApiClient.json.decodeFromJsonElement<ReportsOverviewWire>(JsonObject(encoded - key))
            }
            assertThrows(SerializationException::class.java) {
                ApiClient.json.decodeFromJsonElement<ReportsOverviewWire>(JsonObject(encoded + (key to JsonNull)))
            }
        }
        val devices = encoded.getValue("device_usage").jsonObject
        val unknown = ApiClient.json.encodeToJsonElement(validWire().deviceUsage.rows[1]).jsonObject
        for (field in unknown.keys) {
            val incomplete = JsonObject(devices + ("rows" to JsonArray(listOf(JsonObject(unknown - field)))))
            assertThrows(SerializationException::class.java) {
                ApiClient.json.decodeFromJsonElement<ReportsOverviewWire>(
                    JsonObject(encoded + ("device_usage" to incomplete)),
                )
            }
        }
        val products = encoded.getValue("top_products").jsonObject
        val product = ApiClient.json.encodeToJsonElement(validWire().topProducts.rows.single()).jsonObject
        for (field in product.keys) {
            val incomplete = JsonObject(products + ("rows" to JsonArray(listOf(JsonObject(product - field)))))
            assertThrows(SerializationException::class.java) {
                ApiClient.json.decodeFromJsonElement<ReportsOverviewWire>(
                    JsonObject(encoded + ("top_products" to incomplete)),
                )
            }
        }
    }

    @Test
    fun `v2 cohort bases ranks fractional values and unknown timing fail closed`() = runBlocking {
        val lease = activated().currentLease()!!
        val base = validWire()
        val product = base.topProducts.rows.single()
        val known = base.deviceUsage.rows.first()
        val unknown = base.deviceUsage.rows[1]
        val invalidProducts = listOf(
            base.topProducts.copy(basis = "net_sales"),
            base.topProducts.copy(refundsBasis = "refunds_deducted"),
            base.topProducts.copy(reconcilesToGross = true),
            base.topProducts.copy(limit = 10),
            base.topProducts.copy(rows = List(6) { product }),
            base.topProducts.copy(rows = listOf(product, product)),
            base.topProducts.copy(rows = listOf(product.copy(menuItemId = "invalid"))),
            base.topProducts.copy(rows = listOf(product.copy(name = " "))),
            base.topProducts.copy(rows = listOf(product.copy(productType = ""))),
            base.topProducts.copy(rows = listOf(product.copy(name = "Z"), product.copy(name = "A"))),
        ) + listOf("-1", "NaN", "1e2", "1.0001", "1,000", " 1", "").map {
            base.topProducts.copy(rows = listOf(product.copy(quantitySold = it)))
        }
        val invalidDevices = listOf(
            base.deviceUsage.copy(basis = "sessions_started"),
            base.deviceUsage.copy(grouping = "station"),
            base.deviceUsage.copy(timeBasis = "billable_minutes"),
            base.deviceUsage.copy(rows = listOf(known, known)),
            base.deviceUsage.copy(rows = base.deviceUsage.rows.reversed()),
        ) + listOf(
            known.copy(deviceType = ""), known.copy(completedSessionsCount = 0),
            known.copy(unknownPlaySessionsCount = -1), known.copy(unknownPlaySessionsCount = 5),
            known.copy(playElapsedMs = JsonNull), known.copy(playElapsedMs = JsonPrimitive(-1)),
            known.copy(playElapsedMs = JsonPrimitive("1000")), known.copy(playElapsedMs = JsonPrimitive(1.5)),
            known.copy(playElapsedMs = JsonObject(emptyMap())), unknown.copy(playElapsedMs = JsonPrimitive(0)),
        ).map { base.deviceUsage.copy(rows = listOf(it)) }
        val invalid = invalidProducts.map { base.copy(topProducts = it) } +
            invalidDevices.map { base.copy(deviceUsage = it) }
        invalid.forEach { wire ->
            assertThrows(ReportsContractException::class.java) {
                validateReportsOverview(wire, lease, selection.range.start, selection.range.endInclusive)
            }
        }
        // C-collated supplementary names sort after BMP names; do not use UTF-16 ordering.
        val unicode = base.copy(topProducts = base.topProducts.copy(rows = listOf(
            product.copy(name = "\uE000"), product.copy(name = "\uD83D\uDE00"),
        )))
        assertEquals(2, validateReportsOverview(unicode, lease, selection.range.start,
            selection.range.endInclusive).topProducts.size)
        val empty = base.copy(topProducts = base.topProducts.copy(rows = emptyList()),
            deviceUsage = base.deviceUsage.copy(rows = emptyList()))
        assertTrue(validateReportsOverview(empty, lease, selection.range.start,
            selection.range.endInclusive).deviceUsage.isEmpty())
    }

    @Test
    fun `v2 cache cannot reuse v1 or omit or corrupt the panels`() = runBlocking {
        val lease = activated().currentLease()!!
        val valid = validateReportsOverview(validWire(), lease, selection.range.start, selection.range.endInclusive)
        val cache = FakeCache()
        val repository = ReportsRepository(FakeApi(), cache, clock)
        val legacyKey = "reports:overview:v1:$branch:2026-10-27:2026-10-27"
        val encoded = ApiClient.json.encodeToJsonElement(valid).jsonObject
        cache.rows[legacyKey] = ReportsCacheRow(legacyKey, encoded.toString(), 1L)
        assertNull(repository.latestOverview(lease, selection))
        val key = reportsOverviewCacheKey(lease, selection)
        for (field in listOf("topProducts", "deviceUsage")) {
            cache.rows[key] = ReportsCacheRow(key, JsonObject(encoded - field).toString(), 1L)
            assertNull(repository.latestOverview(lease, selection))
        }
        val deviceRows = encoded.getValue("deviceUsage") as JsonArray
        val missingTime = JsonArray(listOf(JsonObject(deviceRows[1].jsonObject - "playElapsedMs")))
        cache.rows[key] = ReportsCacheRow(key, JsonObject(encoded + ("deviceUsage" to missingTime)).toString(), 1L)
        assertNull(repository.latestOverview(lease, selection))
        val corrupt = valid.copy(deviceUsage = listOf(valid.deviceUsage[1].copy(playElapsedMs = JsonPrimitive(0))))
        cache.rows[key] = ReportsCacheRow(key, ApiClient.json.encodeToString(corrupt), 1L)
        assertNull(repository.latestOverview(lease, selection))
        assertTrue(repository.storeOverview(lease, selection, valid))
        assertEquals(valid, repository.latestOverview(lease, selection)?.value)
    }

    @Test
    fun `strict Overview preserves signed adjustments and validates every reconciliation`() = runBlocking {
        val lease = activated().currentLease()!!
        val overview = validateReportsOverview(validWire(), lease, selection.range.start, selection.range.endInclusive)

        assertEquals(-100L, overview.sources.adjustmentsMinor)
        assertEquals(1_400L, overview.grossRevenueMinor)
        assertEquals(1_500L, overview.sources.positiveSourceDenominatorMinor)
        assertEquals(24, (overview.localHourProfile as ReportsOverviewHourProfile.Available).points.size)
    }

    @Test
    fun `required top-level and availability keys cannot disappear under lenient JSON`() {
        val encoded = ApiClient.json.encodeToJsonElement(ReportsOverviewWire.serializer(), validWire()).jsonObject
        val missingSchema = JsonObject(encoded - "schema_version")
        val missingPayments = JsonObject(encoded - "payments_received")
        val nullCustomers = JsonObject(encoded + ("linked_customers" to JsonNull))
        val hour = encoded.getValue("local_hour_profile").jsonObject
        val missingAvailability = JsonObject(encoded + ("local_hour_profile" to JsonObject(hour - "availability")))

        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromJsonElement(ReportsOverviewWire.serializer(), missingSchema)
        }
        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromJsonElement(ReportsOverviewWire.serializer(), missingPayments)
        }
        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromJsonElement(ReportsOverviewWire.serializer(), nullCustomers)
        }
        val decoded = ApiClient.json.decodeFromJsonElement(ReportsOverviewWire.serializer(), missingAvailability)
        assertThrows(ReportsContractException::class.java) {
            validateReportsOverview(
                decoded,
                CacheScopeLeaseForTest(scope),
                selection.range.start,
                selection.range.endInclusive,
            )
        }
    }

    @Test
    fun `schema scope basis range arithmetic and hour adversaries fail closed`() = runBlocking {
        val lease = activated().currentLease()!!
        val base = validWire()
        val validProfile = hourAvailable()
        val badProfiles = listOf(
            validProfile.copy(dayLengthMinutes = 1_439),
            validProfile.copy(points = validProfile.points.dropLast(1) + validProfile.points[22]),
            validProfile.copy(totalRevenueMinor = 1_399),
            validProfile.copy(totalOrdersCount = 3),
            validProfile.copy(reconcilesToDailyGross = true),
        )
        val invalid = mutableListOf(
            base.copy(schemaVersion = "reports.overview.v1"),
            base.copy(branchId = "33333333-3333-3333-3333-333333333333"),
            base.copy(accountingBasis = "cash"),
            base.copy(sessionsBasis = "all_sessions"),
            base.copy(timezone = "Not/A_Zone"),
            base.copy(snapshotAt = "not-an-instant"),
            base.copy(periodEnd = "2026-10-28"),
            base.copy(ordersCount = -1),
            base.copy(revenueBySource = base.revenueBySource.copy(positiveSourceDenominatorMinor = 1_499)),
            base.copy(revenueBySource = base.revenueBySource.copy(basis = "catalogue_prices")),
            base.copy(revenueBySource = base.revenueBySource.copy(reconcilesToGross = false)),
            base.copy(revenueBySource = base.revenueBySource.copy(totalMinor = 1_399)),
            base.copy(paymentsReceived = base.paymentsReceived.copy(totalMinor = 1_399)),
            base.copy(paymentsReceived = base.paymentsReceived.copy(basis = "order_tenders")),
            base.copy(linkedCustomers = base.linkedCustomers.copy(basis = "customer_history")),
            base.copy(linkedCustomers = base.linkedCustomers.copy(distinctLinkedCustomersCount = 2)),
            base.copy(linkedCustomers = base.linkedCustomers.copy(unlinkedOrdersCount = 2)),
            base.copy(
                revenueBySource = base.revenueBySource.copy(
                    foodMinor = Long.MAX_VALUE,
                    gamingMinor = Long.MAX_VALUE,
                ),
            ),
        )
        invalid += badProfiles.map { base.copy(localHourProfile = ApiClient.json.encodeToJsonElement(it)) }
        invalid.forEach { wire ->
            assertThrows(ReportsContractException::class.java) {
                validateReportsOverview(wire, lease, selection.range.start, selection.range.endInclusive)
            }
        }
    }

    @Test
    fun `single-day requires profile and multi-day requires explicit unavailable discriminator`(): Unit = runBlocking {
        val lease = activated().currentLease()!!
        val unavailable = ApiClient.json.encodeToJsonElement(
            ReportsOverviewHourUnavailableWire("unavailable", "multi_day_range"),
        )
        assertThrows(ReportsContractException::class.java) {
            validateReportsOverview(
                validWire().copy(localHourProfile = unavailable),
                lease,
                selection.range.start,
                selection.range.endInclusive,
            )
        }
        val start = LocalDate.parse("2026-10-01")
        val end = LocalDate.parse("2026-10-27")
        val multi = validWire().copy(
            periodStart = start.toString(),
            periodEnd = end.toString(),
            localHourProfile = unavailable,
        )
        assertEquals(
            ReportsOverviewHourProfile.MultiDayUnavailable,
            validateReportsOverview(multi, lease, start, end).localHourProfile,
        )
        assertThrows(ReportsContractException::class.java) {
            validateReportsOverview(multi, lease, null, null)
        }
    }

    @Test
    fun `repository uses only versioned branch-range keys and ignores legacy unscoped PnL`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache().apply {
            rows["daily:2026-10-27"] = ReportsCacheRow("daily:2026-10-27", "{}", 1L)
        }
        val repository = ReportsRepository(FakeApi(), cache, clock)

        assertNull(repository.latestOverview(lease, selection))
        assertNull(repository.latestPnl(lease, selection))
        assertEquals(
            "reports:overview:v2:$branch:2026-10-27:2026-10-27",
            reportsOverviewCacheKey(lease, selection),
        )
        assertTrue(repository.storeOverview(lease, selection, validateReportsOverview(
            validWire(), lease, selection.range.start, selection.range.endInclusive,
        )))
        assertTrue(cache.rows.containsKey("daily:2026-10-27"))
        assertEquals(2, cache.rows.size)
    }

    @Test
    fun `server default Overview omits device dates and adopts the validated company day`() = runBlocking {
        val lease = activated().currentLease()!!
        var receivedFrom: String? = "not called"
        var receivedTo: String? = "not called"
        val serverDay = LocalDate.parse("2026-11-01")
        val serverWire = validWire().copy(
            periodStart = serverDay.toString(),
            periodEnd = serverDay.toString(),
            timezone = "Asia/Dubai",
            localHourProfile = ApiClient.json.encodeToJsonElement(
                hourAvailable().copy(timezone = "Asia/Dubai", businessDate = serverDay.toString()),
            ),
        )
        val api = object : ReportsApi {
            override suspend fun overview(fromDate: String?, toDate: String?): ReportsOverviewWire {
                receivedFrom = fromDate
                receivedTo = toDate
                return serverWire
            }
            override suspend fun costingCoverage() = error("unused")
            override suspend fun daily(onDate: String?) = error("unused")
            override suspend fun monthly(yyyyMm: String?) = error("unused")
            override suspend fun quarterly(fy: String?, q: Int?) = error("unused")
            override suspend fun yearly(fy: String?) = error("unused")
        }
        val overview = ReportsRepository(api, FakeCache(), clock)
            .fetchOverview(lease, selection, useServerDefaultDate = true)

        assertNull(receivedFrom)
        assertNull(receivedTo)
        assertEquals(serverDay.toString(), overview.periodStart)
        assertEquals("Asia/Dubai", overview.timezone)
        val pending = loadedBothState(lease).copy(
            overview = null,
            overviewTimezone = null,
            overviewUsesServerDefaultDate = true,
        )
        assertFalse(reportsSelectionControlsVisible(pending))
        val adopted = reportsFreshOverviewState(
            state = pending,
            overview = overview,
            fetchedAtMillis = 2L,
            useServerDefaultDate = true,
        )
        assertEquals(serverDay, adopted.onDate)
        assertEquals(YearMonth.parse("2026-11"), adopted.month)
        assertEquals("2026-27", adopted.fiscalYear)
        assertEquals(3, adopted.quarter)
        assertEquals("Asia/Dubai", adopted.overviewTimezone)
        assertFalse(adopted.overviewUsesServerDefaultDate)
        assertNull(adopted.report)
        assertNull(adopted.fetchedAtMillis)
        assertNull(adopted.costingCoverage)
        assertTrue(reportsSelectionControlsVisible(adopted))

        val alreadyOnServerDay = pending.copy(onDate = serverDay)
        assertFalse(reportsServerDefaultChangesSelection(alreadyOnServerDay, overview, true))
        val sameDayAdoption = reportsFreshOverviewState(
            state = alreadyOnServerDay,
            overview = overview,
            fetchedAtMillis = 3L,
            useServerDefaultDate = true,
        )
        assertEquals(alreadyOnServerDay.report, sameDayAdoption.report)
        assertEquals(alreadyOnServerDay.fetchedAtMillis, sameDayAdoption.fetchedAtMillis)
        assertEquals(alreadyOnServerDay.costingCoverage, sameDayAdoption.costingCoverage)
    }

    @Test
    fun `shared selection and tender correction clear both report surfaces`() {
        val lease = runBlocking { activated().currentLease()!! }
        val loaded = loadedBothState(lease)
        val nextDay = loaded.onDate.plusDays(1)
        val changed = clearReportsPayloads(
            loaded.copy(onDate = nextDay, overviewUsesServerDefaultDate = false),
        )
        assertEquals(1_000L, loaded.overview?.payments?.cashMinor)
        val corrected = reportsStateAfterTenderCorrection(loaded)

        listOf(changed, corrected).forEach { cleared ->
            assertNull(cleared.overview)
            assertNull(cleared.overviewFetchedAtMillis)
            assertNull(cleared.report)
            assertNull(cleared.fetchedAtMillis)
            assertNull(cleared.costingCoverage)
            assertFalse(cleared.overviewLoading)
            assertFalse(cleared.loading)
            assertFalse(cleared.costingLoading)
        }
        assertEquals(nextDay, changed.onDate)
        val switchedToHiddenPnl = visibleReportsState(
            changed.copy(surface = ReportsSurface.PNL), lease, loaded.access,
        )
        assertNull(switchedToHiddenPnl.report)
        assertNull(switchedToHiddenPnl.fetchedAtMillis)
        assertEquals(loaded.selection, corrected.selection)
    }

    @Test
    fun `hour peak uses the highest signed revenue rather than absolute loss magnitude`() {
        val points = listOf(
            ReportsOverviewHourPoint(8, -900, 1),
            ReportsOverviewHourPoint(9, -100, 1),
            ReportsOverviewHourPoint(10, -400, 1),
        )
        assertEquals(9, reportsPeakHour(points)?.hour)
        assertEquals(-100L, reportsPeakHour(points)?.revenueMinor)
    }

    @Test
    fun `surface prefix deletion is isolated from the other Report class and Home`() = runBlocking {
        val lease = activated().currentLease()!!
        val cache = FakeCache()
        val repository = ReportsRepository(FakeApi(), cache, clock)
        val overviewKey = reportsOverviewCacheKey(lease, selection)
        val pnlKey = reportsPnlCacheKey(lease, selection)
        val costingKey = reportsCostingCacheKey(lease)
        val homeKey = "home:v1:$branch:2026-10-27"
        listOf(overviewKey, pnlKey, costingKey, homeKey).forEach { key ->
            cache.rows[key] = ReportsCacheRow(key, "{}", 1L)
        }

        assertTrue(repository.deleteOverviewScope(lease))
        assertEquals(setOf(pnlKey, costingKey, homeKey), cache.rows.keys)

        cache.rows[overviewKey] = ReportsCacheRow(overviewKey, "{}", 1L)
        assertTrue(repository.deletePnlScope(lease))
        assertEquals(setOf(homeKey, overviewKey), cache.rows.keys)
    }

    @Test
    fun `saved Overview is revalidated and arithmetic corruption is never admitted`() = runBlocking {
        val lease = activated().currentLease()!!
        val cache = FakeCache()
        val repository = ReportsRepository(FakeApi(), cache, clock)
        val valid = validateReportsOverview(validWire(), lease, selection.range.start, selection.range.endInclusive)
        val corrupted = valid.copy(
            sources = valid.sources.copy(positiveSourceDenominatorMinor = 1_499),
        )
        cache.rows[reportsOverviewCacheKey(lease, selection)] = ReportsCacheRow(
            reportsOverviewCacheKey(lease, selection),
            ApiClient.json.encodeToString(ValidatedReportsOverview.serializer(), corrupted),
            1L,
        )

        assertNull(repository.latestOverview(lease, selection))
    }

    @Test
    fun `cold executor never reads persisted cache until this lease earns admission`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        var cacheReads = 0
        var fetches = 0
        val result = ReportsLoadExecutor(coordinator).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheAdmissionAuthorized = { false },
            loadCached = { cacheReads += 1; error("must not read") },
            onCached = {},
            fetch = { fetches += 1; validPnl() },
            store = { true },
            revokeCacheAdmission = {},
            deleteSurfaceCache = { true },
        )

        assertTrue(result is ReportsLoadResult.Offline)
        assertNull((result as ReportsLoadResult.Offline).cached)
        assertEquals(0, cacheReads)
        assertEquals(0, fetches)
    }

    @Test
    fun `lease change during cache read cannot publish cached data or fetch`() = runBlocking {
        val coordinator = activated()
        val oldLease = coordinator.currentLease()!!
        var cachedPublications = 0
        var fetches = 0
        val cached = CachedReportsValue(
            key = reportsPnlCacheKey(oldLease, selection),
            value = validPnl(),
            fetchedAtMillis = 1L,
        )

        val result = ReportsLoadExecutor(coordinator).execute(
            lease = oldLease,
            online = true,
            attemptIsCurrent = { true },
            cacheAdmissionAuthorized = { true },
            loadCached = {
                coordinator.deactivate()
                coordinator.activateValidated(scope)
                cached
            },
            onCached = { cachedPublications += 1 },
            fetch = { fetches += 1; validPnl() },
            store = { error("must not store") },
            revokeCacheAdmission = {},
            deleteSurfaceCache = { true },
        )

        assertEquals(ReportsLoadResult.Stale, result)
        assertEquals(0, cachedPublications)
        assertEquals(0, fetches)
        assertTrue(coordinator.currentLease()!!.generation > oldLease.generation)
    }

    @Test
    fun `denial revokes before narrow deletion and deletion failure remains denied`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val events = mutableListOf<String>()
        var surfaceVisible = true
        val result = ReportsLoadExecutor(coordinator).execute<ReportData>(
            lease = lease,
            online = true,
            attemptIsCurrent = { true },
            cacheAdmissionAuthorized = { false },
            loadCached = { null },
            onCached = {},
            fetch = { throw ApiException("denied", status = 403) },
            store = { error("must not store") },
            revokeCacheAdmission = { events += "revoke"; surfaceVisible = false },
            deleteSurfaceCache = {
                assertFalse("Denied figures must be hidden before disk cleanup", surfaceVisible)
                events += "delete"
                false
            },
        )

        assertTrue(result is ReportsLoadResult.Denied)
        assertFalse((result as ReportsLoadResult.Denied).deletionSucceeded)
        assertEquals(listOf("revoke", "delete"), events)
    }

    @Test
    fun `same-scope generation change after response cannot store or publish`() = runBlocking {
        val coordinator = activated()
        val oldLease = coordinator.currentLease()!!
        var stores = 0
        val result = ReportsLoadExecutor(coordinator).execute(
            lease = oldLease,
            online = true,
            attemptIsCurrent = { true },
            cacheAdmissionAuthorized = { false },
            loadCached = { null },
            onCached = {},
            fetch = {
                coordinator.deactivate()
                coordinator.activateValidated(scope)
                validPnl()
            },
            store = { stores += 1; true },
            revokeCacheAdmission = {},
            deleteSurfaceCache = { true },
        )

        assertEquals(ReportsLoadResult.Stale, result)
        assertEquals(0, stores)
        assertTrue(coordinator.currentLease()!!.generation > oldLease.generation)
    }

    @Test
    fun `permission or serial loss after response cannot store or grant admission`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        var current = true
        var stores = 0
        var cachedPublications = 0
        val result = ReportsLoadExecutor(coordinator).execute(
            lease = lease,
            online = true,
            attemptIsCurrent = { current },
            cacheAdmissionAuthorized = { false },
            loadCached = { error("cache is not admitted") },
            onCached = { cachedPublications += 1 },
            fetch = { current = false; validPnl() },
            store = { stores += 1; true },
            revokeCacheAdmission = {},
            deleteSurfaceCache = { true },
        )

        assertEquals(ReportsLoadResult.Stale, result)
        assertEquals(0, stores)
        assertEquals(0, cachedPublications)
    }

    @Test
    fun `cache IO failure is best effort and fresh authorized fetch still proceeds`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        var fetches = 0
        val result = ReportsLoadExecutor(coordinator).execute(
            lease = lease,
            online = true,
            attemptIsCurrent = { true },
            cacheAdmissionAuthorized = { true },
            loadCached = { throw IllegalStateException("synthetic disk failure") },
            onCached = { error("must not publish") },
            fetch = { fetches += 1; validPnl() },
            store = { true },
            revokeCacheAdmission = {},
            deleteSurfaceCache = { true },
        )

        assertTrue(result is ReportsLoadResult.Fresh)
        assertEquals(1, fetches)
    }

    @Test
    fun `visible projection masks old lease and each revoked surface independently`() = runBlocking {
        val oldLease = activated().currentLease()!!
        val nextCoordinator = activated()
        nextCoordinator.deactivate()
        nextCoordinator.activateValidated(scope)
        val newLease = nextCoordinator.currentLease()!!
        val overview = validateReportsOverview(validWire(), oldLease, selection.range.start, selection.range.endInclusive)
        val raw = ReportsUiState(
            access = cloud.dcompany.erp.core.auth.ReportsAccess(true, true),
            lease = oldLease,
            onDate = selection.onDate,
            month = selection.month,
            fiscalYear = selection.fiscalYear,
            quarter = selection.quarter,
            overview = overview,
            report = validPnl(),
            costingCoverage = CostingCoverage(branchId = branch, isComplete = true),
        )

        val changedLease = visibleReportsState(raw, newLease, raw.access)
        assertNull(changedLease.overview)
        assertNull(changedLease.report)
        assertNull(changedLease.costingCoverage)

        val analyticsOnly = visibleReportsState(
            raw,
            oldLease,
            cloud.dcompany.erp.core.auth.ReportsAccess(canReadOverview = true),
        )
        assertEquals(overview, analyticsOnly.overview)
        assertNull(analyticsOnly.report)
        assertNull(analyticsOnly.costingCoverage)

        val financeOnly = visibleReportsState(
            raw,
            oldLease,
            cloud.dcompany.erp.core.auth.ReportsAccess(canReadPnl = true),
        )
        assertNull(financeOnly.overview)
        assertEquals(raw.report, financeOnly.report)
        assertEquals(raw.costingCoverage, financeOnly.costingCoverage)

        val noAccess = visibleReportsState(raw, oldLease, cloud.dcompany.erp.core.auth.ReportsAccess())
        assertNull(noAccess.overview)
        assertNull(noAccess.report)
        assertNull(noAccess.costingCoverage)
    }

    @Test
    fun `explicit Overview retry uses a fresh proof per call and preserves global offline state`() = runBlocking {
        val f = ExplicitRetryFixture()
        f.initialize()
        repeat(2) { assertTrue(f.execute() is ReportsLoadResult.Fresh) }
        assertEquals(2, f.probes)
        assertEquals(2, f.directFactories)
        assertEquals(2, f.directCalls)
        assertEquals(0, f.normalCalls)
        assertEquals(2, f.stores)
        assertFalse(f.online)
        assertEquals(1, f.cache.rows.size)
    }

    @Test
    fun `online explicit Overview retry uses normal API without loopback proof or direct client`() = runBlocking {
        val f = ExplicitRetryFixture()
        f.initialize()
        f.online = true
        assertTrue(f.execute() is ReportsLoadResult.Fresh)
        assertEquals(1, f.normalCalls)
        assertEquals(0, f.probes)
        assertEquals(0, f.directFactories)
    }

    @Test
    fun `failed readiness probe sends no Reports request and stores nothing`() = runBlocking {
        val f = ExplicitRetryFixture()
        f.initialize()
        f.probeAction = { false }
        assertTrue(f.execute() is ReportsLoadResult.Failed)
        assertEquals(1, f.probes)
        assertEquals(0, f.directFactories)
        assertEquals(0, f.directCalls)
        assertEquals(0, f.stores)
        assertTrue(f.cache.rows.isEmpty())
    }

    @Test
    fun `deferred readiness proof rejects every invalidated Overview attempt before client creation`() = runBlocking {
        for (change in listOf("lease", "serial", "surface", "access", "selection", "default-date")) {
            val f = ExplicitRetryFixture()
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.probeAction = { entered.complete(Unit); release.await(); true }
            val result = async { f.execute() }
            entered.await()
            f.invalidate(change)
            release.complete(Unit)
            assertEquals(change, ReportsLoadResult.Stale, result.await())
            assertEquals(change, 0, f.directFactories)
            assertEquals(change, 0, f.stores)
        }
    }

    @Test
    fun `deferred direct response rejects every invalidated Overview attempt before cache or publication`() = runBlocking {
        for (change in listOf("lease", "serial", "surface", "access", "selection", "default-date")) {
            val f = ExplicitRetryFixture()
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.responseAction = { entered.complete(Unit); release.await(); validWire() }
            val result = async { f.execute() }
            entered.await()
            f.invalidate(change)
            release.complete(Unit)
            assertEquals(change, ReportsLoadResult.Stale, result.await())
            assertEquals(change, 1, f.directCalls)
            assertEquals(change, 0, f.stores)
            assertTrue(f.cache.rows.isEmpty())
        }
    }

    @Test
    fun `cancellation during probe or direct response propagates without cache or denial changes`() = runBlocking {
        for (duringProbe in listOf(true, false)) {
            val f = ExplicitRetryFixture()
            f.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            if (duringProbe) f.probeAction = { entered.complete(Unit); release.await(); true }
            else f.responseAction = { entered.complete(Unit); release.await(); validWire() }
            val result = async { f.execute() }
            entered.await()
            result.cancel()
            try { result.await(); error("cancellation must propagate") } catch (_: CancellationException) { }
            assertEquals(0, f.stores)
            assertEquals(0, f.revocations)
            assertEquals(if (duringProbe) 0 else 1, f.directCalls)
        }
    }

    @Test
    fun `explicit Overview 401 and 403 revoke exact lease cache admission and delete saved surface`() = runBlocking {
        for (status in listOf(401, 403)) {
            val f = ExplicitRetryFixture()
            f.initialize()
            assertTrue(f.execute() is ReportsLoadResult.Fresh)
            f.responseAction = { throw ApiException("Denied", status = status) }
            assertTrue(f.execute() is ReportsLoadResult.Denied)
            assertEquals(1, f.revocations)
            assertTrue(f.cache.rows.isEmpty())
            assertEquals(1, f.stores)
        }
    }

    @Test
    fun `explicit direct Overview still rejects another branch and malformed totals`() = runBlocking {
        for (wrongBranch in listOf(true, false)) {
            val f = ExplicitRetryFixture()
            f.initialize()
            f.responseAction = {
                if (wrongBranch) validWire().copy(branchId = "33333333-3333-3333-3333-333333333333")
                else validWire().copy(grossRevenueMinor = 9999)
            }
            assertTrue(f.execute() is ReportsLoadResult.Failed)
            assertEquals(0, f.stores)
            assertTrue(f.cache.rows.isEmpty())
        }
    }

    private inner class ExplicitRetryFixture {
        lateinit var coordinator: CacheIsolationCoordinator
        lateinit var lease: cloud.dcompany.erp.core.auth.CacheScopeLease
        lateinit var state: ReportsUiState
        var serial = 1L
        var online = false
        var probes = 0
        var directFactories = 0
        var directCalls = 0
        var normalCalls = 0
        var stores = 0
        var revocations = 0
        val cache = FakeCache()
        var probeAction: suspend () -> Boolean = { true }
        var responseAction: suspend () -> ReportsOverviewWire = { validWire() }
        private val normal = object : ReportsApi by FakeApi() {
            override suspend fun overview(fromDate: String?, toDate: String?): ReportsOverviewWire {
                normalCalls += 1
                return validWire()
            }
        }
        private val direct = object : ReportsApi by FakeApi() {
            override suspend fun overview(fromDate: String?, toDate: String?): ReportsOverviewWire {
                directCalls += 1
                assertNull(fromDate)
                assertNull(toDate)
                return responseAction()
            }
        }
        private val repository = ReportsRepository(normal, cache, clock)
        private val admission = ManualBackendRequestAdmission(
            online = { online }, exactDebugLoopback = { true },
            probe = { probes += 1; probeAction() },
        )

        suspend fun initialize() {
            coordinator = activated()
            lease = coordinator.currentLease()!!
            state = ReportsUiState(
                access = ReportsAccess(canReadOverview = true), lease = lease,
                onDate = selection.onDate, month = selection.month,
                fiscalYear = selection.fiscalYear, quarter = selection.quarter,
                overviewUsesServerDefaultDate = true,
            )
        }

        suspend fun invalidate(change: String) {
            when (change) {
                "lease" -> { coordinator.deactivate(); coordinator.activateValidated(scope) }
                "serial" -> serial += 1
                "surface" -> state = state.copy(surface = ReportsSurface.PNL)
                "access" -> state = state.copy(access = ReportsAccess())
                "selection" -> state = state.copy(onDate = state.onDate.minusDays(1))
                "default-date" -> state = state.copy(overviewUsesServerDefaultDate = false)
                else -> error("unknown invalidation")
            }
        }

        suspend fun execute(): ReportsLoadResult<ValidatedReportsOverview> = ReportsLoadExecutor(coordinator).execute(
            lease = lease, online = online,
            attemptIsCurrent = {
                reportsOverviewAttemptIsCurrent(state, lease, coordinator.currentLease(), selection, true, 1L, serial)
            },
            cacheAdmissionAuthorized = { false }, loadCached = { error("cold cache not admitted") },
            onCached = {}, fetch = { error("explicit fetch must own the request") },
            store = { stores += 1; repository.storeOverview(lease, selection, it) },
            revokeCacheAdmission = { revocations += 1 },
            deleteSurfaceCache = { repository.deleteOverviewScope(lease) },
            freshFetch = { current ->
                fetchReportsOverviewForExplicitRefresh(lease, selection, true, current, repository, admission) {
                    directFactories += 1
                    direct
                }
            },
        )
    }

    private suspend fun activated(): CacheIsolationCoordinator = CacheIsolationCoordinator(
        purger = object : ScopeDataPurger {
            override suspend fun hasUnresolvedWork() = false
            override suspend fun purgeIfClean() = true
        },
        marker = object : CacheScopeMarker {
            private var value: CacheScope? = scope
            override fun current() = value
            override fun remember(scope: CacheScope): Boolean { value = scope; return true }
            override fun clear(): Boolean { value = null; return true }
        },
    ).also { it.activateValidated(scope) }

    private fun validWire(): ReportsOverviewWire = ReportsOverviewWire(
        schemaVersion = "reports.overview.v2",
        branchId = branch,
        periodStart = "2026-10-27",
        periodEnd = "2026-10-27",
        timezone = "Asia/Dubai",
        snapshotAt = "2026-10-27T08:00:00Z",
        accountingBasis = "operational_receipt",
        grossRevenueMinor = 1_400,
        ordersCount = 2,
        sessionsStartedCount = 3,
        sessionsBasis = "non_cancelled_sessions_started",
        revenueBySource = ReportsOverviewSourcesWire(
            "operational_receipt_source_aggregate",
            foodMinor = 1_000,
            gamingMinor = 500,
            hookahMinor = 0,
            eventTicketsMinor = 0,
            membershipsMinor = 0,
            deliveryAggregatorMinor = 0,
            otherMinor = 0,
            manualCollectionsMinor = 0,
            adjustmentsMinor = -100,
            positiveSourceDenominatorMinor = 1_500,
            totalMinor = 1_400,
            reconcilesToGross = true,
        ),
        paymentsReceived = ReportsOverviewPaymentsWire(
            "settlement_method_collections",
            cashMinor = 1_000,
            upiMinor = 0,
            cardMinor = 400,
            bankMinor = 0,
            qrMinor = 0,
            walletMinor = 0,
            otherMinor = 0,
            totalMinor = 1_400,
        ),
        linkedCustomers = ReportsOverviewCustomersWire("current_order_linkage", 1, 1, 1),
        localHourProfile = ApiClient.json.encodeToJsonElement(hourAvailable()),
        topProducts = ReportsOverviewTopProductsWire(
            "gross_settled_pos_line_sales", "included_original_sales_no_refund_deduction", false, 5,
            listOf(ReportsOverviewTopProduct(branch, "Historical tea", "FOOD", "4.250", 1_500)),
        ),
        deviceUsage = ReportsOverviewDevicesWire(
            "non_cancelled_sessions_ended_in_period", "device_type", "whole_session_pause_adjusted_elapsed_ms",
            listOf(
                ReportsOverviewDeviceUsageWire("PS5", 4, JsonPrimitive(3_661_123L), 0),
                ReportsOverviewDeviceUsageWire("VR", 2, JsonNull, 1),
                ReportsOverviewDeviceUsageWire("PS4", 1, JsonPrimitive(0L), 0),
            ),
        ),
    )

    private fun hourAvailable() = ReportsOverviewHourAvailableWire(
        availability = "available",
        timezone = "Asia/Dubai",
        businessDate = "2026-10-27",
        dayLengthMinutes = 1_440,
        basis = "pos_sales_tip_exclusive_local_hour_profile",
        reconcilesToDailyGross = false,
        totalRevenueMinor = 1_400,
        totalOrdersCount = 2,
        points = (0..23).map { hour ->
            ReportsOverviewHourPointWire(hour, if (hour == 12) 1_400 else 0, if (hour == 12) 2 else 0)
        },
    )

    private fun validPnl() = ReportData(
        branchId = branch,
        accountingBasis = "operational_receipt",
        period = "daily",
        label = "27 Oct 2026",
        periodStart = "2026-10-27",
        periodEnd = "2026-10-27",
        fiscalYear = "2026-27",
    )

    private fun loadedBothState(lease: cloud.dcompany.erp.core.auth.CacheScopeLease): ReportsUiState {
        val overview = validateReportsOverview(
            validWire(), lease, selection.range.start, selection.range.endInclusive,
        )
        return ReportsUiState(
            access = cloud.dcompany.erp.core.auth.ReportsAccess(true, true),
            lease = lease,
            surface = ReportsSurface.OVERVIEW,
            period = selection.period,
            onDate = selection.onDate,
            month = selection.month,
            fiscalYear = selection.fiscalYear,
            quarter = selection.quarter,
            overview = overview,
            overviewFetchedAtMillis = 1L,
            overviewTimezone = overview.timezone,
            overviewUsesServerDefaultDate = false,
            report = validPnl(),
            fetchedAtMillis = 1L,
            costingCoverage = CostingCoverage(branchId = branch, isComplete = true),
        )
    }

    private inner class FakeApi : ReportsApi {
        override suspend fun overview(fromDate: String?, toDate: String?) = validWire()
        override suspend fun costingCoverage() = CostingCoverage(branchId = branch, isComplete = true)
        override suspend fun daily(onDate: String?) = validPnl()
        override suspend fun monthly(yyyyMm: String?) = error("unused")
        override suspend fun quarterly(fy: String?, q: Int?) = error("unused")
        override suspend fun yearly(fy: String?) = error("unused")
    }

    private class FakeCache : ReportsCacheStore {
        val rows = linkedMapOf<String, ReportsCacheRow>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(row: ReportsCacheRow) { rows[row.key] = row }
        override suspend fun deletePrefix(prefix: String): Int {
            val keys = rows.keys.filter { it.startsWith(prefix) }
            keys.forEach(rows::remove)
            return keys.size
        }
    }

    /** Only used to reach a missing-key decoder path without mutable coordinator setup. */
    private fun CacheScopeLeaseForTest(scope: CacheScope) = runBlocking {
        CacheIsolationCoordinator(
            object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork() = false
                override suspend fun purgeIfClean() = true
            },
            object : CacheScopeMarker {
                override fun current() = scope
                override fun remember(scope: CacheScope) = true
                override fun clear() = true
            },
        ).also { it.activateValidated(scope) }.currentLease()!!
    }
}

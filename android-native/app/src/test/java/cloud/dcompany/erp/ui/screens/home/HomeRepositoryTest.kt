package cloud.dcompany.erp.ui.screens.home

import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.ManualBackendRequestAdmission
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRepositoryTest {
    private val branch = "11111111-1111-1111-1111-111111111111"
    private val terminal = "22222222-2222-2222-2222-222222222222"
    private val scope = CacheScope("user-a", "company-a", branch, terminal)
    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `strict response preserves signed minor units and validates a complete local-hour profile`() = runBlocking {
        val lease = activated().currentLease()!!
        val payload = validateHomePayload(validWire().copy(
            revenue = validWire().revenue.copy(current = -125L, previous = 0L),
            localHourProfile = validWire().localHourProfile.copy(
                totalRevenueMinor = -125L,
                points = validPoints().map { if (it.hour == 7) it.copy(revenueMinor = -125L) else it },
            ),
        ), lease, clock)

        assertEquals(-125L, payload.aggregate.revenue.current)
        assertEquals(-125L, payload.aggregate.profileRevenueMinor)
        assertEquals(24, payload.aggregate.hourly.size)
        assertEquals(listOf(0, 1, 2, 3), payload.aggregate.hourly.take(4).map(HomeHourPoint::hour))
        assertEquals(1, payload.activity.size)

        val negativeBaseline = validWire().copy(
            revenue = validWire().revenue.copy(
                current = -125L,
                previous = -250L,
                deltaPct = JsonPrimitive(-50.0),
                comparisonStatus = "available",
            ),
        )
        assertEquals(-250L, validateHomePayload(negativeBaseline, lease, clock).aggregate.revenue.previous)
    }

    @Test
    fun `schema scope profile comparison and capability adversaries fail before publication`() = runBlocking {
        val lease = activated().currentLease()!!
        val base = validWire()
        val invalid = listOf(
            base.copy(schemaVersion = 2),
            base.copy(branchId = "33333333-3333-3333-3333-333333333333"),
            base.copy(terminalId = JsonNull),
            base.copy(timezone = "Not/AZone"),
            base.copy(businessDate = "2026-10-26"),
            base.copy(localHourProfile = base.localHourProfile.copy(basis = "gross")),
            base.copy(localHourProfile = base.localHourProfile.copy(reconcilesToDailyGross = true)),
            base.copy(localHourProfile = base.localHourProfile.copy(points = validPoints().dropLast(1) + validPoints()[22])),
            base.copy(localHourProfile = base.localHourProfile.copy(totalOrdersCount = 2)),
            base.copy(
                localHourProfile = base.localHourProfile.copy(
                    points = validPoints().map {
                        when (it.hour) {
                            0 -> it.copy(revenueMinor = Long.MAX_VALUE)
                            1 -> it.copy(revenueMinor = 1L)
                            else -> it.copy(revenueMinor = 0L)
                        }
                    },
                ),
            ),
            base.copy(orders = base.orders.copy(comparisonStatus = "available", previous = 0, deltaPct = JsonPrimitive(1.0))),
            base.copy(activityCapabilities = base.activityCapabilities.copy(pos = "missing_permission")),
        )
        invalid.forEach { wire ->
            assertThrows(HomeContractException::class.java) { validateHomePayload(wire, lease, clock) }
        }
    }

    @Test
    fun `day length accepts ordinary and half-hour DST boundaries from the declared zone`() = runBlocking {
        val lease = activated().currentLease()!!
        listOf(
            DayCase("2026-10-04", "Australia/Lord_Howe", "2026-10-04T05:00:00Z", 1_410),
            DayCase("2026-03-08", "America/New_York", "2026-03-08T16:00:00Z", 1_380),
            DayCase("2026-11-01", "America/New_York", "2026-11-01T17:00:00Z", 1_500),
        ).forEach { case ->
            val caseClock = Clock.fixed(Instant.parse(case.now), ZoneId.of("UTC"))
            val wire = validWire().copy(
                businessDate = case.date,
                timezone = case.zone,
                snapshotAt = case.now,
                localHourProfile = validWire().localHourProfile.copy(
                    timezone = case.zone,
                    businessDate = case.date,
                    dayLengthMinutes = case.minutes,
                ),
            )
            assertEquals(case.minutes, validateHomePayload(wire, lease, caseClock).aggregate.dayLengthMinutes)
        }
    }

    @Test
    fun `aggregate cache contains no transient activity capability or display facts`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val repository = HomeRepository(FakeApi { validWire() }, cache, clock)
        val payload = repository.fetch(lease)

        assertTrue(repository.store(lease, payload.aggregate))
        val json = cache.rows.single().jsonBody
        listOf("recent_activity", "activity_capabilities", "display_reference", "Paid order", "INV-42")
            .forEach { assertFalse("aggregate cache leaked $it", json.contains(it)) }
        val cached = requireNotNull(repository.latestCached(lease))
        assertEquals(payload.aggregate, cached.aggregate)
        assertEquals(clock.millis(), cached.fetchedAtMillis)
    }

    @Test
    fun `required nullable wire keys cannot disappear under lenient global JSON settings`() {
        val encoded = ApiClient.json.encodeToString(HomeReadModelWire.serializer(), validWire())
        val root = ApiClient.json.parseToJsonElement(encoded).jsonObject
        val missingTerminal = JsonObject(root - "terminal_id")
        val missingDelta = JsonObject(
            root + ("revenue" to JsonObject(root.getValue("revenue").jsonObject - "delta_pct")),
        )
        val activity = root.getValue("recent_activity").jsonArray
        val missingAmount = JsonObject(
            root + (
                "recent_activity" to JsonArray(
                    listOf(JsonObject(activity.first().jsonObject - "amount_minor")) + activity.drop(1),
                )
            ),
        )
        mapOf(
            "terminal_id" to missingTerminal,
            "delta_pct" to missingDelta,
            "amount_minor" to missingAmount,
        ).forEach { (key, missing) ->
            assertThrows("missing $key must fail", SerializationException::class.java) {
                ApiClient.json.decodeFromJsonElement<HomeReadModelWire>(missing)
            }
        }
    }

    @Test
    fun `cold process never exposes persisted Home aggregate before online authorization`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        var calls = 0
        val cache = FakeCache()
        val repository = HomeRepository(FakeApi { calls += 1; validWire() }, cache, clock)
        val payload = repository.fetch(lease)
        repository.store(lease, payload.aggregate)
        calls = 0
        var published = false

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = { published = true },
        )

        assertTrue(result is HomeLoadResult.Offline)
        assertNull((result as HomeLoadResult.Offline).cached)
        assertFalse(published)
        assertEquals(0, calls)
    }

    @Test
    fun `each online load and explicit retry dispatch exactly one Home request`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val api = FakeApi { validWire() }
        val executor = HomeLoadExecutor(HomeRepository(api, FakeCache(), clock), coordinator, clock)
        repeat(2) {
            assertTrue(
                executor.execute(lease, true, { true }, { false }, {}) is HomeLoadResult.Fresh,
            )
        }
        assertEquals(2, api.calls)
    }

    @Test
    fun `explicit offline refresh proves readiness then validates and stores one direct response`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val normal = FakeApi { error("normal client must remain unused while globally offline") }
        val direct = FakeApi { validWire() }
        val repository = HomeRepository(normal, cache, clock)
        var online = false
        var probes = 0
        var directFactories = 0
        val admission = ManualBackendRequestAdmission(
            online = { online },
            exactDebugLoopback = { true },
            probe = { probes += 1; true },
        )

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(
                    lease = lease,
                    isCurrent = current,
                    repository = repository,
                    admission = admission,
                    directApi = { directFactories += 1; direct },
                )
            },
        )

        assertTrue(result is HomeLoadResult.Fresh)
        result as HomeLoadResult.Fresh
        assertTrue(result.cacheStored)
        assertEquals(1, probes)
        assertEquals(1, directFactories)
        assertEquals(1, direct.calls)
        assertEquals(0, normal.calls)
        assertFalse("manual Home refresh must not publish global connectivity", online)
        assertEquals(1, cache.rows.size)
        val published = freshHomeState(lease, result)
        assertFalse(published.stale)
        assertEquals(result.payload.activity, published.activity)
        assertEquals(result.payload.capabilities, published.activityCapabilities)
    }

    @Test
    fun `each explicit offline refresh requires a new proof and bounded direct request`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val normal = FakeApi { error("normal client must remain unused") }
        val direct = FakeApi { validWire() }
        val repository = HomeRepository(normal, FakeCache(), clock)
        var probes = 0
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = { probes += 1; true },
        )
        val executor = HomeLoadExecutor(repository, coordinator, clock)

        repeat(2) {
            val result = executor.execute(
                lease = lease,
                online = false,
                attemptIsCurrent = { true },
                cacheSuppressed = { false },
                onCached = {},
                freshFetch = { current ->
                    fetchHomeForExplicitRefresh(lease, current, repository, admission) { direct }
                },
            )
            assertTrue(result is HomeLoadResult.Fresh)
        }

        assertEquals(2, probes)
        assertEquals(2, direct.calls)
        assertEquals(0, normal.calls)
    }

    @Test
    fun `failed manual proof dispatches no Home request and writes no cache`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val normal = FakeApi { error("normal client must remain unused") }
        val direct = FakeApi { validWire() }
        val repository = HomeRepository(normal, cache, clock)
        var directFactories = 0
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = { false },
        )

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(
                    lease,
                    current,
                    repository,
                    admission,
                ) { directFactories += 1; direct }
            },
        )

        assertTrue(result is HomeLoadResult.Failed)
        assertEquals(0, directFactories)
        assertEquals(0, direct.calls)
        assertEquals(0, normal.calls)
        assertTrue(cache.rows.isEmpty())
    }

    @Test
    fun `live offline wrong-origin refresh ignores stale online observation and dispatches no request`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val api = FakeApi { validWire() }
        val repository = HomeRepository(api, FakeCache(), clock)
        val staleObservedOnline = true
        val operationOnline = false
        var probes = 0
        val admission = ManualBackendRequestAdmission(
            online = { operationOnline },
            exactDebugLoopback = { false },
            probe = { probes += 1; true },
        )
        val manualFetch: suspend (() -> Boolean) -> ValidatedHomePayload? = { current ->
            fetchHomeForExplicitRefresh(lease, current, repository, admission) { api }
        }

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = operationOnline,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = manualFetch,
        )

        assertTrue("fixture must model collector lag", staleObservedOnline)
        assertTrue(result is HomeLoadResult.Failed)
        assertEquals(0, probes)
        assertEquals(0, api.calls)
    }

    @Test
    fun `lease change during readiness prevents direct API creation and request`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val direct = FakeApi { validWire() }
        val repository = HomeRepository(FakeApi { error("normal client must remain unused") }, FakeCache(), clock)
        var factories = 0
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = {
                coordinator.deactivate()
                coordinator.activateValidated(scope)
                true
            },
        )

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(lease, current, repository, admission) {
                    factories += 1
                    direct
                }
            },
        )

        assertEquals(HomeLoadResult.StaleLease, result)
        assertEquals(0, factories)
        assertEquals(0, direct.calls)
        assertTrue(coordinator.currentLease()!!.generation > lease.generation)
    }

    @Test
    fun `lease change after direct response prevents cache replacement and publication`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val direct = FakeApi {
            coordinator.deactivate()
            coordinator.activateValidated(scope)
            validWire()
        }
        val repository = HomeRepository(FakeApi { error("normal client must remain unused") }, cache, clock)
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = { true },
        )

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(lease, current, repository, admission) { direct }
            },
        )

        assertEquals(HomeLoadResult.StaleLease, result)
        assertEquals(1, direct.calls)
        assertTrue(cache.rows.isEmpty())
    }

    @Test
    fun `explicit refresh uses normal authenticated API when validated connectivity is online`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val normal = FakeApi { validWire() }
        val direct = FakeApi { error("direct client must remain unused while online") }
        val repository = HomeRepository(normal, FakeCache(), clock)
        var probes = 0
        var directFactories = 0
        val admission = ManualBackendRequestAdmission(
            online = { true },
            exactDebugLoopback = { true },
            probe = { probes += 1; true },
        )

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = true,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(lease, current, repository, admission) {
                    directFactories += 1
                    direct
                }
            },
        )

        assertTrue(result is HomeLoadResult.Fresh)
        assertEquals(1, normal.calls)
        assertEquals(0, probes)
        assertEquals(0, directFactories)
        assertEquals(0, direct.calls)
    }

    @Test
    fun `direct authorization denial revokes exact Home admission before best effort deletion`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val warm = HomeRepository(FakeApi { validWire() }, cache, clock)
        assertTrue(warm.store(lease, warm.fetch(lease).aggregate))
        cache.rows += HomeCacheRow("daily:2026-10-27", "unrelated", 1L)
        cache.failDelete = true
        val deniedApi = FakeApi { throw ApiException("denied", status = 403) }
        val repository = HomeRepository(FakeApi { error("normal client must remain unused") }, cache, clock)
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = { true },
        )
        var cacheAdmitted = true
        cache.onDelete = {
            assertFalse("direct denial must revoke Home cache authority before deletion", cacheAdmitted)
        }

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            cacheAdmissionAuthorized = { cacheAdmitted },
            revokeCacheAdmission = { cacheAdmitted = false },
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(lease, current, repository, admission) { deniedApi }
            },
        )

        assertTrue(result is HomeLoadResult.Denied)
        assertFalse(cacheAdmitted)
        assertFalse((result as HomeLoadResult.Denied).deletionSucceeded)
        assertEquals(scope, coordinator.currentLease()?.scope)
        assertTrue(cache.rows.any { it.key == "daily:2026-10-27" })
    }

    @Test
    fun `supplied direct API still uses strict branch validation and cannot cache mismatch`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val wrongBranch = FakeApi {
            validWire().copy(branchId = "33333333-3333-3333-3333-333333333333")
        }
        val repository = HomeRepository(FakeApi { error("normal client must remain unused") }, cache, clock)
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = { true },
        )

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease = lease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            freshFetch = { current ->
                fetchHomeForExplicitRefresh(lease, current, repository, admission) { wrongBranch }
            },
        )

        assertTrue(result is HomeLoadResult.Failed)
        assertTrue(cache.rows.isEmpty())
    }

    @Test
    fun `transport failure retains only exact lease aggregate as stale and clears activity`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val payload = validateHomePayload(validWire(), lease, clock)
        val fresh = freshHomeState(
            lease,
            HomeLoadResult.Fresh(payload, clock.millis(), cacheStored = false),
        )

        val retained = retainedHomeState(lease, fresh, cached = null, error = "Backend unavailable")
        assertEquals(payload.aggregate, retained.aggregate)
        assertEquals(clock.millis(), retained.fetchedAtMillis)
        assertTrue(retained.stale)
        assertTrue(retained.activity.isEmpty())
        assertNull(retained.activityCapabilities)

        coordinator.deactivate()
        coordinator.activateValidated(scope)
        val newLease = coordinator.currentLease()!!
        val masked = retainedHomeState(newLease, fresh, cached = null, error = "Backend unavailable")
        assertNull(masked.aggregate)
        assertFalse(masked.stale)
    }

    @Test
    fun `cache read failure is best effort and still dispatches one fresh request`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val api = FakeApi { validWire() }
        val cache = FakeCache().apply { failLatest = true }

        val result = HomeLoadExecutor(HomeRepository(api, cache, clock), coordinator, clock)
            .execute(
                lease = lease,
                online = true,
                attemptIsCurrent = { true },
                cacheSuppressed = { false },
                onCached = {},
                cacheAdmissionAuthorized = { true },
            )

        assertTrue(result is HomeLoadResult.Fresh)
        assertEquals(1, api.calls)
    }

    @Test
    fun `successful cache replacement admits same-process offline aggregate`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val repository = HomeRepository(FakeApi { validWire() }, cache, clock)
        val fresh = HomeLoadExecutor(repository, coordinator, clock)
            .execute(lease, true, { true }, { false }, {})
        assertTrue(fresh is HomeLoadResult.Fresh)
        assertTrue((fresh as HomeLoadResult.Fresh).cacheStored)
        var published = false

        val result = HomeLoadExecutor(repository, coordinator, clock)
            .execute(
                lease = lease,
                online = false,
                attemptIsCurrent = { true },
                cacheSuppressed = { false },
                onCached = { published = true },
                cacheAdmissionAuthorized = { true },
            )

        assertTrue(result is HomeLoadResult.Offline)
        assertTrue(published)
        assertEquals(validWire().revenue.current, (result as HomeLoadResult.Offline).cached?.aggregate?.revenue?.current)
    }

    @Test
    fun `cache write failure shows fresh response but cannot grant offline cache admission`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val api = FakeApi { validWire() }
        val cache = FakeCache().apply { failPut = true }

        val result = HomeLoadExecutor(HomeRepository(api, cache, clock), coordinator, clock)
            .execute(lease, true, { true }, { false }, {})

        assertTrue(result is HomeLoadResult.Fresh)
        assertFalse((result as HomeLoadResult.Fresh).cacheStored)
        assertEquals(1, api.calls)
        assertTrue(cache.rows.isEmpty())

        var published = false
        val offline = HomeLoadExecutor(HomeRepository(api, cache, clock), coordinator, clock)
            .execute(
                lease = lease,
                online = false,
                attemptIsCurrent = { true },
                cacheSuppressed = { false },
                onCached = { published = true },
                cacheAdmissionAuthorized = { false },
            )
        assertTrue(offline is HomeLoadResult.Offline)
        assertNull((offline as HomeLoadResult.Offline).cached)
        assertFalse(published)
    }

    @Test
    fun `visible projection hides an old generation before the collector clears its state`() = runBlocking {
        val coordinator = activated()
        val oldLease = coordinator.currentLease()!!
        val oldState = HomeUiState(lease = oldLease, loading = false, aggregate = validateHomePayload(validWire(), oldLease, clock).aggregate)
        coordinator.deactivate()
        coordinator.activateValidated(scope)
        val newLease = coordinator.currentLease()!!

        val masked = visibleHomeState(oldState, newLease, newLease)

        assertNull(masked.aggregate)
        assertEquals(newLease, masked.lease)
        assertTrue(masked.loading)
    }

    @Test
    fun `missing selected branch fails before network dispatch`() = runBlocking {
        val branchless = scope.copy(branchId = null)
        val coordinator = CacheIsolationCoordinator(
            purger = object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork() = false
                override suspend fun purgeIfClean() = true
            },
            marker = object : CacheScopeMarker {
                override fun current() = branchless
                override fun remember(scope: CacheScope) = true
                override fun clear() = true
            },
        )
        coordinator.activateValidated(branchless)
        val lease = coordinator.currentLease()!!
        val api = FakeApi { validWire() }

        val result = HomeLoadExecutor(HomeRepository(api, FakeCache(), clock), coordinator, clock)
            .execute(lease, true, { true }, { false }, {})

        assertTrue(result is HomeLoadResult.Failed)
        assertEquals(0, api.calls)
    }

    @Test
    fun `same-scope new generation suppresses late response cache and publication`() = runBlocking {
        val coordinator = activated()
        val oldLease = coordinator.currentLease()!!
        val cache = FakeCache()
        val api = FakeApi {
            coordinator.deactivate()
            coordinator.activateValidated(scope)
            validWire()
        }
        val result = HomeLoadExecutor(HomeRepository(api, cache, clock), coordinator, clock).execute(
            lease = oldLease,
            online = true,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
        )

        assertEquals(HomeLoadResult.StaleLease, result)
        assertTrue(cache.rows.isEmpty())
        assertTrue(coordinator.currentLease()!!.generation > oldLease.generation)
    }

    @Test
    fun `authorization denial revokes cache before failed deletion and restart remains cold`() = runBlocking {
        val marker = object : CacheScopeMarker {
            private var value: CacheScope? = scope
            override fun current() = value
            override fun remember(scope: CacheScope): Boolean { value = scope; return true }
            override fun clear(): Boolean { value = null; return true }
        }
        val coordinator = CacheIsolationCoordinator(
            purger = object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork() = true
                override suspend fun purgeIfClean() = false
            },
            marker = marker,
        )
        coordinator.activateCached(scope)
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val warm = HomeRepository(FakeApi { validWire() }, cache, clock)
        warm.store(lease, warm.fetch(lease).aggregate)
        cache.rows += HomeCacheRow("daily:2026-10-27", "{}", 1L)
        cache.failDelete = true
        val denied = HomeRepository(FakeApi { throw ApiException("denied", status = 403) }, cache, clock)
        var admitted = true
        cache.onDelete = {
            assertFalse("cache authority must be revoked before deletion starts", admitted)
        }
        val result = HomeLoadExecutor(denied, coordinator, clock).execute(
            lease = lease,
            online = true,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            cacheAdmissionAuthorized = { admitted },
            revokeCacheAdmission = { admitted = false },
        )

        assertTrue(result is HomeLoadResult.Denied)
        result as HomeLoadResult.Denied
        assertFalse(result.deletionSucceeded)
        assertFalse("403 must revoke process-local Home cache authority before deletion", admitted)
        assertEquals(scope, coordinator.currentLease()?.scope)
        assertEquals(setOf(homeCacheKey(branch, "2026-10-27"), "daily:2026-10-27"), cache.rows.map { it.key }.toSet())

        coordinator.deactivate()
        coordinator.activateCached(scope)
        val restartedLease = coordinator.currentLease()!!
        var republished = false
        val offline = HomeLoadExecutor(denied, coordinator, clock).execute(
            restartedLease,
            online = false,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = { republished = true },
            cacheAdmissionAuthorized = { false },
        )
        assertTrue(offline is HomeLoadResult.Offline)
        assertNull((offline as HomeLoadResult.Offline).cached)
        assertFalse("a restarted process must reauthorize Home online before reading cache", republished)
        assertEquals("{}", cache.rows.single { it.key == "daily:2026-10-27" }.jsonBody)
    }

    @Test
    fun `authorization denial after cache IO failure revokes the admitted lease`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val warm = HomeRepository(FakeApi { validWire() }, cache, clock)
        warm.store(lease, warm.fetch(lease).aggregate)
        cache.rows += HomeCacheRow("home:v1:$branch:2026-10-26", "unreadable-old-home", 1L)
        cache.rows += HomeCacheRow("daily:2026-10-27", "unrelated", 1L)
        cache.failLatest = true
        val denied = HomeRepository(FakeApi { throw ApiException("denied", status = 401) }, cache, clock)
        var admitted = true

        val first = HomeLoadExecutor(denied, coordinator, clock)
            .execute(
                lease = lease,
                online = true,
                attemptIsCurrent = { true },
                cacheSuppressed = { false },
                onCached = {},
                cacheAdmissionAuthorized = { admitted },
                revokeCacheAdmission = { admitted = false },
            )
        assertTrue(first is HomeLoadResult.Denied)
        assertFalse(admitted)
        assertEquals(listOf("daily:2026-10-27"), cache.rows.map(HomeCacheRow::key))
        cache.failLatest = false
        var republished = false

        val second = HomeLoadExecutor(denied, coordinator, clock).execute(
            lease,
            online = true,
            attemptIsCurrent = { true },
            cacheSuppressed = { true },
            onCached = { republished = true },
            cacheAdmissionAuthorized = { admitted },
            revokeCacheAdmission = { admitted = false },
        )

        assertTrue(second is HomeLoadResult.Denied)
        assertFalse("a lease-wide denial must hide a row that becomes readable later", republished)
    }

    @Test
    fun `fresh authorized success grants admission only after aggregate replacement succeeds`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val api = FakeApi { validWire() }
        val repository = HomeRepository(api, cache, clock)

        val fresh = HomeLoadExecutor(repository, coordinator, clock)
            .execute(lease, true, { true }, { false }, {})

        assertTrue(fresh is HomeLoadResult.Fresh)
        assertTrue((fresh as HomeLoadResult.Fresh).cacheStored)
        assertEquals(1, cache.rows.count { it.key.startsWith("home:v1:$branch:") })
        var published = false
        val offline = HomeLoadExecutor(repository, coordinator, clock)
            .execute(
                lease = lease,
                online = false,
                attemptIsCurrent = { true },
                cacheSuppressed = { false },
                onCached = { published = true },
                cacheAdmissionAuthorized = { fresh.cacheStored },
            )
        assertTrue(offline is HomeLoadResult.Offline)
        assertTrue(published)
    }

    @Test
    fun `late old authorization denial cannot revoke or delete the new workspace`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        val warm = HomeRepository(FakeApi { validWire() }, cache, clock)
        warm.store(lease, warm.fetch(lease).aggregate)
        val other = scope.copy(userId = "user-b")
        val denied = HomeRepository(FakeApi {
            coordinator.activateValidated(other)
            throw ApiException("denied", status = 403)
        }, cache, clock)
        var revoked = false

        val result = HomeLoadExecutor(denied, coordinator, clock).execute(
            lease,
            online = true,
            attemptIsCurrent = { true },
            cacheSuppressed = { false },
            onCached = {},
            cacheAdmissionAuthorized = { true },
            revokeCacheAdmission = { revoked = true },
        )

        assertEquals(HomeLoadResult.StaleLease, result)
        assertEquals(other, coordinator.currentLease()?.scope)
        assertTrue(cache.rows.any { it.key == homeCacheKey(branch, "2026-10-27") })
        assertFalse(revoked)
    }

    @Test
    fun `lease loss after cached read prevents request and cached publication`() = runBlocking {
        val coordinator = activated()
        val lease = coordinator.currentLease()!!
        val cache = FakeCache()
        var calls = 0
        val repository = HomeRepository(FakeApi { calls += 1; validWire() }, cache, clock)
        repository.store(lease, repository.fetch(lease).aggregate)
        calls = 0
        var published = 0
        cache.onLatest = { coordinator.deactivate() }

        val result = HomeLoadExecutor(repository, coordinator, clock).execute(
            lease,
            online = true,
            attemptIsCurrent = { coordinator.currentLease() == lease },
            cacheSuppressed = { false },
            onCached = { published += 1 },
            cacheAdmissionAuthorized = { true },
        )

        assertEquals(0, published)
        assertEquals(HomeLoadResult.StaleLease, result)
        assertEquals(0, calls)
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

    private fun validWire(): HomeReadModelWire = HomeReadModelWire(
        schemaVersion = 1,
        branchId = branch,
        terminalId = JsonPrimitive(terminal),
        businessDate = "2026-10-27",
        timezone = "Asia/Dubai",
        snapshotAt = "2026-10-27T08:00:00Z",
        revenue = HomeMetricWire(12_345, 0, JsonNull, "no_baseline"),
        orders = HomeMetricWire(1, 0, JsonNull, "no_baseline"),
        customers = HomeCustomersWire(42, "company_live_directory", "unavailable_no_immutable_history"),
        gaming = HomeGamingWire(9, 1),
        localHourProfile = HomeLocalHourProfileWire(
            timezone = "Asia/Dubai",
            businessDate = "2026-10-27",
            dayLengthMinutes = 1_440,
            basis = "pos_sales_tip_exclusive_local_hour_profile",
            reconcilesToDailyGross = false,
            totalRevenueMinor = 12_345,
            totalOrdersCount = 1,
            points = validPoints(),
        ),
        recentActivity = listOf(HomeActivityWire("pos_order_paid", "2026-10-27T07:55:00Z", "INV-42", "Paid order", JsonPrimitive(12_345), "terminal")),
        activityCapabilities = HomeActivityCapabilitiesWire("available", "available"),
    )

    private fun validPoints(): List<HomeHourPointWire> = (0..23).map { hour ->
        HomeHourPointWire(hour, if (hour == 7) 12_345 else 0, if (hour == 7) 1 else 0)
    }

    private data class DayCase(val date: String, val zone: String, val now: String, val minutes: Int)

    private class FakeApi(private val block: suspend () -> HomeReadModelWire) : HomeApi {
        var calls = 0
        override suspend fun home(): HomeReadModelWire { calls += 1; return block() }
    }

    private class FakeCache : HomeCacheStore {
        val rows = mutableListOf<HomeCacheRow>()
        var failDelete = false
        var failLatest = false
        var failPut = false
        var onLatest: suspend () -> Unit = {}
        var onDelete: suspend () -> Unit = {}
        override suspend fun latest(prefix: String): HomeCacheRow? {
            if (failLatest) error("disk unavailable")
            val row = rows.filter { it.key.startsWith(prefix) }.maxByOrNull(HomeCacheRow::fetchedAtMillis)
            onLatest()
            return row
        }
        override suspend fun put(row: HomeCacheRow) {
            if (failPut) error("disk unavailable")
            rows.removeAll { it.key == row.key }
            rows += row
        }
        override suspend fun deletePrefix(prefix: String): Int {
            onDelete()
            if (failDelete) error("disk unavailable")
            val before = rows.size
            rows.removeAll { it.key.startsWith(prefix) }
            return before - rows.size
        }
    }

}

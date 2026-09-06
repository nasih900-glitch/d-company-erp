package cloud.dcompany.erp.core.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticOutboxDatabaseTest {
    private lateinit var database: DiagnosticDatabase
    private lateinit var dao: DiagnosticOutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DiagnosticDatabase::class.java,
        ).build()
        dao = database.outboxDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun boundedQueueKeepsNewestRowsAndDeduplicatesOsExitSource() = runBlocking {
        val dedupe = "a".repeat(64)
        assertTrue(dao.insertBounded(row(1L, localDedupeKey = dedupe), maximumRows = 3))
        assertFalse(dao.insertBounded(row(2L, localDedupeKey = dedupe), maximumRows = 3))
        dao.insertBounded(row(3L), maximumRows = 3)
        dao.insertBounded(row(4L), maximumRows = 3)
        dao.insertBounded(row(5L), maximumRows = 3)

        assertEquals(3, dao.count(DiagnosticOutboxState.PENDING))
        assertEquals(
            listOf(3L, 4L, 5L),
            dao.pendingForScope("1".repeat(64)).map { it.occurredAtMillis },
        )
    }

    @Test
    fun pendingSelectionCannotCrossAuthenticatedLocalScope() = runBlocking {
        val scopeA = "a".repeat(64)
        val scopeB = "b".repeat(64)
        dao.insertBounded(row(1L, localScopeHash = scopeA))
        dao.insertBounded(row(2L, localScopeHash = scopeB))
        dao.insertBounded(row(3L, localScopeHash = null))

        assertEquals(
            listOf(1L),
            dao.pendingForScope(scopeA).map { it.occurredAtMillis },
        )
        assertEquals(
            listOf(2L),
            dao.pendingForScope(scopeB).map { it.occurredAtMillis },
        )
        assertEquals(1, dao.quarantineUnboundPending())
        assertEquals(1, dao.count(DiagnosticOutboxState.QUARANTINED))
        assertEquals(listOf(1L), dao.pendingForScope(scopeA).map { it.occurredAtMillis })
        assertEquals(listOf(2L), dao.pendingForScope(scopeB).map { it.occurredAtMillis })
    }

    @Test
    fun captureWithoutVerifiedScopeIsQuarantinedAndNeverScheduled() = runBlocking {
        var scheduled = 0
        val outbox = DiagnosticOutbox(dao) { scheduled += 1 }
        val event = DiagnosticEvent(
            eventType = DiagnosticEventType.API_FAILURE,
            severity = DiagnosticSeverity.ERROR,
            occurredAtMillis = 10L,
            capturedVersionName = "3.1.4",
            capturedVersionCode = 15,
            capturedOsApiLevel = 35,
            component = DiagnosticComponent.NETWORK,
            reasonCode = "transport_unreachable",
            connectivity = DiagnosticConnectivity.OFFLINE,
        )

        assertTrue(outbox.capture(event, localScopeHash = null))
        assertEquals(0, scheduled)
        assertEquals(0, dao.count(DiagnosticOutboxState.PENDING))
        assertEquals(1, dao.count(DiagnosticOutboxState.QUARANTINED))
        assertTrue(dao.pendingForScope("a".repeat(64)).isEmpty())
        assertTrue(dao.pendingForScope("b".repeat(64)).isEmpty())
    }

    @Test
    fun verifiedScopeWitnessIsDurableAndRevokedOnLogout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DiagnosticVerifiedScopeStore(context)
        val scopeA = "a".repeat(64)
        try {
            assertTrue(store.clear())
            assertTrue(store.remember(scopeA))
            assertEquals(scopeA, DiagnosticVerifiedScopeStore(context).current())
            assertFalse(store.remember("not-a-scope-hash"))
            assertEquals(scopeA, store.current())
            assertTrue(store.clear())
            assertEquals(null, DiagnosticVerifiedScopeStore(context).current())
        } finally {
            store.clear()
        }
    }

    private fun row(
        occurredAt: Long,
        localDedupeKey: String? = null,
        // Most fixtures represent a verified signed-in workspace. Tests for
        // legacy/unverified evidence pass null explicitly.
        localScopeHash: String? = "1".repeat(64),
    ) = DiagnosticEnvelopeEntity(
        clientEventId = UUID.randomUUID().toString(),
        localDedupeKey = localDedupeKey,
        localScopeHash = localScopeHash,
        eventType = DiagnosticEventType.API_FAILURE.wireValue,
        severity = DiagnosticSeverity.ERROR.wireValue,
        occurredAtMillis = occurredAt,
        capturedVersionName = "3.1.4",
        capturedVersionCode = 15,
        capturedOsApiLevel = 35,
        component = DiagnosticComponent.NETWORK.wireValue,
        reasonCode = "transport_unreachable",
        failureFingerprint = null,
        httpStatus = null,
        durationBucket = null,
        connectivity = DiagnosticConnectivity.OFFLINE.wireValue,
        pendingOutboxCount = null,
    )
}

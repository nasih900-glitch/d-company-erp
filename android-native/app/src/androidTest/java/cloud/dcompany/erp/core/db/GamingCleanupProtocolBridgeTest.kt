package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.OutboxSafetyGate
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import cloud.dcompany.erp.core.remote.RemoteAssistanceJournalScope
import cloud.dcompany.erp.core.remote.RemoteRequestScopeTag
import cloud.dcompany.erp.core.sync.SyncEngine
import cloud.dcompany.erp.core.sync.cleanupAcknowledgementMatches
import cloud.dcompany.erp.core.sync.gamingCleanupCandidateSha256
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupAcknowledgeBody
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupDeviceApi
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupReconciliation
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupReportBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-runtime cleanup contract.
 *
 * The backend HTTP full-flow test creates and approves the exact directive in
 * `gaming_cleanup_full_flow_v1.json`. This instrumentation test consumes that
 * same literal response with production Android serializers, applies it to a
 * real file-backed Room database, acknowledges it with the exact backend
 * receipt, and proves both transitions survive process-style database reopen.
 * The acknowledgement leg deliberately crosses an extra reopen after the
 * server receipt is available but before the local acknowledgement is stored;
 * that is the process/coroutine interruption boundary which previously lacked
 * direct regression coverage.
 */
@RunWith(AndroidJUnit4::class)
class GamingCleanupProtocolBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun backendApprovedDirectiveRetriesExactlyAfterInterruptedAcknowledgement() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtureText = instrumentation.context.assets
            .open("gaming_cleanup_full_flow_v1.json")
            .bufferedReader()
            .use { it.readText() }
        val fixture = json.parseToJsonElement(fixtureText).jsonObject
        assertEquals(
            "dcompany.gaming-cleanup-full-flow.v1",
            fixture.getValue("schema").jsonPrimitive.content,
        )
        val report = json.decodeFromJsonElement(
            GamingCleanupReportBody.serializer(),
            fixture.getValue("approved_report"),
        )
        val directive = json.decodeFromJsonElement(
            GamingCleanupReconciliation.serializer(),
            fixture.getValue("approved_directive"),
        )
        val acknowledgement = json.decodeFromJsonElement(
            GamingCleanupReconciliation.serializer(),
            fixture.getValue("acknowledgement"),
        )
        val clock = fixture.getValue("local_clock").jsonObject
        val retiredAtMillis = clock.getValue("retired_at_millis").jsonPrimitive.long

        assertEquals(report.localSnapshotSha256, gamingCleanupSnapshotSha256(report.localSnapshot))
        assertEquals(report.candidateSha256, gamingCleanupCandidateSha256(report))
        assertEquals("approved", directive.status)
        assertEquals("cleanup_retire", directive.deviceDirective)
        assertEquals(report.installationId, directive.installationId)
        assertEquals(report.localActionId, directive.localActionId)
        assertEquals(report.serverSessionId, directive.serverSessionId)
        assertEquals(report.stationId, directive.stationId)
        assertEquals(report.localSnapshotSha256, directive.localSnapshotSha256)
        assertEquals(report.candidateSha256, directive.candidateSha256)
        assertEquals(report.localSnapshot.evidenceRevision, directive.localEvidenceRevision)

        val context = instrumentation.targetContext
        val databaseName = "cleanup-protocol-bridge-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            val captured = report.localSnapshot
            val first = Room.databaseBuilder(context, ErpDatabase::class.java, databaseName).build()
            try {
                first.gamingDao().insertLocalSession(
                    LocalGamingSessionEntity(
                        localId = report.localActionId,
                        serverId = report.serverSessionId,
                        stationId = report.stationId,
                        shiftId = captured.shiftId,
                        customerId = captured.customerId,
                        customerName = captured.customerName,
                        customerPhone = captured.customerPhone,
                        customerDirectoryRevision = captured.customerDirectoryRevision,
                        customerDirectoryCompanyId = captured.customerDirectoryCompanyId,
                        timerMinutes = captured.timerMinutes,
                        startedAtMillis = captured.startedAtMillis,
                        state = report.reportedLocalState,
                        status = "stopping",
                        endAtMillis = captured.endedAtMillis,
                        timerEndsAtMillis = captured.timerEndsAtMillis,
                        billableMinutes = captured.billableMinutes,
                        amountMinor = captured.amountMinor,
                        ratePerHourMinor = captured.ratePerHourMinor,
                        packageId = captured.packageId,
                        packagePriceMinor = captured.packagePriceMinor,
                        packageDurationMinutes = captured.packageDurationMinutes,
                        packageVariant = captured.packageVariant,
                        billingMode = captured.billingMode,
                        packageStationTypeSnapshot = captured.packageStationTypeSnapshot,
                        packagePricingTierSnapshot = captured.packagePricingTierSnapshot,
                        extraControllers = captured.extraControllers,
                        cleanupEvidenceRevision = captured.evidenceRevision,
                        startRequestHash = report.startRequestHash,
                        stopRequestHash = report.stopRequestHash,
                    ),
                )
                val inserted = requireNotNull(
                    first.gamingDao().localSessionById(report.localActionId),
                )
                assertEquals(captured, inserted.cleanupSnapshotOrNull())
                assertEquals(
                    1,
                    first.gamingDao().noteCleanupWorkflowStatusCas(
                        localId = report.localActionId,
                        serverSessionId = report.serverSessionId,
                        expectedState = report.reportedLocalState,
                        expectedEvidenceRevision = report.localSnapshot.evidenceRevision,
                        message = GamingCleanupWorkflowStatus.WAITING_FOR_OWNER.persistedMessage,
                    ),
                )
                val waitingForOwner = requireNotNull(
                    first.gamingDao().localSessionById(report.localActionId),
                )
                assertEquals(
                    GamingCleanupWorkflowStatus.WAITING_FOR_OWNER.persistedMessage,
                    waitingForOwner.lastError,
                )
                assertEquals(captured.evidenceRevision, waitingForOwner.cleanupEvidenceRevision)
                assertEquals(captured, waitingForOwner.cleanupSnapshotOrNull())
                assertEquals(
                    0,
                    first.gamingDao().noteCleanupWorkflowStatusCas(
                        localId = report.localActionId,
                        serverSessionId = "00000000-0000-4000-8000-000000000000",
                        expectedState = report.reportedLocalState,
                        expectedEvidenceRevision = report.localSnapshot.evidenceRevision,
                        message = GamingCleanupWorkflowStatus.REVIEW_REQUIRED.persistedMessage,
                    ),
                )
                assertFalse(
                    first.gamingDao().applyCleanupRetirement(
                        localId = directive.localActionId,
                        serverSessionId = directive.serverSessionId,
                        stationId = directive.stationId,
                        expectedState = directive.reportedLocalState,
                        reconciliationId = directive.id,
                        receiptAuditId = directive.cleanupReceiptAuditId,
                        candidateSha256 = directive.candidateSha256,
                        reason = requireNotNull(directive.approvalReason),
                        retiredAtMillis = retiredAtMillis,
                        branchId = directive.branchId,
                        terminalId = directive.terminalId,
                        expectedEvidenceRevision = directive.localEvidenceRevision,
                        expectedSnapshotSha256 = "0".repeat(64),
                    ),
                )
                assertEquals(
                    GamingSessionState.STOP_PENDING,
                    first.gamingDao().localSessionById(report.localActionId)?.state,
                )
                assertTrue(
                    first.gamingDao().applyCleanupRetirement(
                        localId = directive.localActionId,
                        serverSessionId = directive.serverSessionId,
                        stationId = directive.stationId,
                        expectedState = directive.reportedLocalState,
                        reconciliationId = directive.id,
                        receiptAuditId = directive.cleanupReceiptAuditId,
                        candidateSha256 = directive.candidateSha256,
                        reason = requireNotNull(directive.approvalReason),
                        retiredAtMillis = retiredAtMillis,
                        branchId = directive.branchId,
                        terminalId = directive.terminalId,
                        expectedEvidenceRevision = directive.localEvidenceRevision,
                        expectedSnapshotSha256 = directive.localSnapshotSha256,
                    ),
                )
            } finally {
                first.close()
            }

            val afterRetirement =
                Room.databaseBuilder(context, ErpDatabase::class.java, databaseName).build()
            try {
                val retained = requireNotNull(
                    afterRetirement.gamingDao().localSessionById(report.localActionId),
                )
                assertEquals(GamingSessionState.CLEANUP_RETIRED, retained.state)
                assertEquals(directive.id, retained.cleanupReconciliationId)
                assertEquals(directive.cleanupReceiptAuditId, retained.cleanupReceiptAuditId)
                assertEquals(directive.candidateSha256, retained.cleanupCandidateSha256)
                assertEquals(retiredAtMillis, retained.cleanupRetiredAtMillis)
                assertNull(retained.cleanupAcknowledgedAtMillis)
                assertNull(retained.lastError)
                assertEquals(
                    directive.localEvidenceRevision + 1,
                    retained.cleanupEvidenceRevision,
                )
                assertEquals(
                    report.localActionId,
                    afterRetirement.gamingDao().cleanupRetiredSessions().single().localId,
                )
                assertEquals(
                    report.localActionId,
                    afterRetirement.gamingDao()
                        .observeCleanupRetirementsAwaitingAcknowledgement()
                        .first()
                        .single()
                        .localId,
                )
                assertTrue(afterRetirement.gamingDao().observeActiveLocalSessions().first().isEmpty())
                assertTrue(afterRetirement.gamingDao().localSessionsForServerReconciliation().isEmpty())
                assertEquals(
                    0,
                    afterRetirement.gamingDao().unresolvedLocalSessionCount(directive.stationId),
                )

                // Replaying the same approved directive after process restart
                // is idempotent. It reports the already-applied outcome but
                // must not advance evidence, change the retirement timestamp,
                // or recreate the stale station overlay.
                assertTrue(
                    afterRetirement.gamingDao().applyCleanupRetirement(
                        localId = directive.localActionId,
                        serverSessionId = directive.serverSessionId,
                        stationId = directive.stationId,
                        expectedState = directive.reportedLocalState,
                        reconciliationId = directive.id,
                        receiptAuditId = directive.cleanupReceiptAuditId,
                        candidateSha256 = directive.candidateSha256,
                        reason = requireNotNull(directive.approvalReason),
                        retiredAtMillis = retiredAtMillis + 9_999,
                        branchId = directive.branchId,
                        terminalId = directive.terminalId,
                        expectedEvidenceRevision = directive.localEvidenceRevision,
                        expectedSnapshotSha256 = directive.localSnapshotSha256,
                    ),
                )
                val afterDirectiveReplay = requireNotNull(
                    afterRetirement.gamingDao().localSessionById(report.localActionId),
                )
                assertEquals(retiredAtMillis, afterDirectiveReplay.cleanupRetiredAtMillis)
                assertEquals(
                    directive.localEvidenceRevision + 1,
                    afterDirectiveReplay.cleanupEvidenceRevision,
                )
                assertTrue(afterRetirement.gamingDao().observeActiveLocalSessions().first().isEmpty())
                assertEquals(
                    1,
                    afterRetirement.gamingDao().noteCleanupAcknowledgementPendingCas(
                        localId = report.localActionId,
                        reconciliationId = directive.id,
                        candidateSha256 = directive.candidateSha256,
                        message = GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
                    ),
                )
                assertEquals(
                    GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
                    afterRetirement.gamingDao().localSessionById(report.localActionId)?.lastError,
                )

                assertFalse(
                    cleanupAcknowledgementMatches(
                        acknowledgement.copy(candidateSha256 = "0".repeat(64)),
                        installationId = report.installationId,
                        localId = report.localActionId,
                        reconciliationId = directive.id,
                        candidateSha256 = directive.candidateSha256,
                    ),
                )
                assertTrue(
                    cleanupAcknowledgementMatches(
                        acknowledgement,
                        installationId = report.installationId,
                        localId = report.localActionId,
                        reconciliationId = directive.id,
                        candidateSha256 = directive.candidateSha256,
                    ),
                )
                // Simulate process death after the local retirement commits
                // and before the first acknowledgement attempt. The next
                // process must discover this row from Room alone.
                assertNull(
                    afterRetirement.gamingDao()
                        .localSessionById(report.localActionId)
                        ?.cleanupAcknowledgedAtMillis,
                )
            } finally {
                afterRetirement.close()
            }

            val afterInterruptedAcknowledgement =
                Room.databaseBuilder(context, ErpDatabase::class.java, databaseName).build()
            try {
                val retained = requireNotNull(
                    afterInterruptedAcknowledgement.gamingDao()
                        .cleanupRetiredSessions()
                        .single(),
                )
                assertEquals(report.localActionId, retained.localId)
                assertEquals(directive.id, retained.cleanupReconciliationId)
                assertEquals(directive.cleanupReceiptAuditId, retained.cleanupReceiptAuditId)
                assertEquals(directive.candidateSha256, retained.cleanupCandidateSha256)
                assertEquals(retiredAtMillis, retained.cleanupRetiredAtMillis)
                assertEquals(
                    directive.localEvidenceRevision + 1,
                    retained.cleanupEvidenceRevision,
                )
                assertEquals(
                    GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
                    retained.lastError,
                )
                assertTrue(
                    afterInterruptedAcknowledgement.gamingDao()
                        .observeActiveLocalSessions()
                        .first()
                        .isEmpty(),
                )

                // A stale or mismatched receipt cannot resolve the durable
                // evidence, even if a future caller bypasses the app-level
                // response check.
                assertTrue(
                    cleanupAcknowledgementMatches(
                        acknowledgement,
                        installationId = report.installationId,
                        localId = report.localActionId,
                        reconciliationId = directive.id,
                        candidateSha256 = directive.candidateSha256,
                    ),
                )
                assertEquals(
                    0,
                    afterInterruptedAcknowledgement.gamingDao().markCleanupAcknowledgedCas(
                        localId = report.localActionId,
                        reconciliationId = directive.id,
                        candidateSha256 = "0".repeat(64),
                        acknowledgedAtMillis = System.currentTimeMillis(),
                    ),
                )

                // A fresh SyncEngine reaches the server with the exact durable
                // identity. Cancel after the successful response and before
                // the local CAS to exercise the narrowest interruption window.
                val application = context.applicationContext as DCompanyApp
                val cacheScope = CacheScope(
                    userId = "cleanup-bridge-user",
                    companyId = "cleanup-bridge-company",
                    branchId = directive.branchId,
                    terminalId = directive.terminalId,
                )
                val cacheIsolation = CacheIsolationCoordinator(
                    purger = CleanScopeDataPurger(),
                    marker = FixedCacheScopeMarker(cacheScope),
                )
                cacheIsolation.activateValidated(cacheScope)
                val lease = requireNotNull(cacheIsolation.currentLease())
                val interruptedApi = RecordingGamingCleanupDeviceApi(acknowledgement)
                val proofTag = RemoteRequestScopeTag(
                    RemoteAssistanceJournalScope(
                        companyId = cacheScope.companyId,
                        installationId = report.installationId,
                        userId = cacheScope.userId,
                    ),
                )
                val interruptedEngineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val interruptedSyncEngine = SyncEngine(
                    db = afterInterruptedAcknowledgement,
                    scope = interruptedEngineScope,
                    outboxSafety = OutboxSafetyGate(
                        afterInterruptedAcknowledgement,
                        application.outboxOwnerStore,
                        application.tokens,
                    ),
                    cacheIsolation = cacheIsolation,
                    checkoutClientInstance = { report.installationId },
                    scheduleDurableSync = {},
                    gamingCleanupApi = interruptedApi,
                    gamingCleanupProofTagProvider = { proofTag },
                    afterGamingCleanupAcknowledgeResponse = {
                        throw CancellationException("simulated process interruption")
                    },
                )
                try {
                    val interruption = runCatching {
                        interruptedSyncEngine.acknowledgeCleanupRetirements(lease)
                    }.exceptionOrNull()
                    assertTrue(interruption is CancellationException)
                    assertEquals(1, interruptedApi.acknowledgeCallCount)
                    assertEquals(directive.id, interruptedApi.lastReconciliationId)
                    assertEquals(report.installationId, interruptedApi.lastInstallationId)
                    assertEquals(report.installationId, interruptedApi.lastBody?.installationId)
                    assertEquals(
                        directive.candidateSha256,
                        interruptedApi.lastBody?.expectedCandidateSha256,
                    )
                    assertEquals(proofTag, interruptedApi.lastRequestScope)
                    val stillPending = requireNotNull(
                        afterInterruptedAcknowledgement.gamingDao()
                            .localSessionById(report.localActionId),
                    )
                    assertNull(stillPending.cleanupAcknowledgedAtMillis)
                    assertEquals(
                        GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
                        stillPending.lastError,
                    )
                } finally {
                    interruptedEngineScope.cancel()
                }
            } finally {
                afterInterruptedAcknowledgement.close()
            }

            val afterResponseInterruption =
                Room.databaseBuilder(context, ErpDatabase::class.java, databaseName).build()
            try {
                val retained = requireNotNull(
                    afterResponseInterruption.gamingDao().cleanupRetiredSessions().single(),
                )
                assertEquals(directive.id, retained.cleanupReconciliationId)
                assertEquals(directive.candidateSha256, retained.cleanupCandidateSha256)
                assertNull(retained.cleanupAcknowledgedAtMillis)

                // The next process retries the same idempotent receipt, commits
                // the exact CAS once, then excludes the row from later sweeps.
                val application = context.applicationContext as DCompanyApp
                val cacheScope = CacheScope(
                    userId = "cleanup-bridge-user",
                    companyId = "cleanup-bridge-company",
                    branchId = directive.branchId,
                    terminalId = directive.terminalId,
                )
                val cacheIsolation = CacheIsolationCoordinator(
                    purger = CleanScopeDataPurger(),
                    marker = FixedCacheScopeMarker(cacheScope),
                )
                cacheIsolation.activateValidated(cacheScope)
                val lease = requireNotNull(cacheIsolation.currentLease())
                val retryApi = RecordingGamingCleanupDeviceApi(acknowledgement)
                val proofTag = RemoteRequestScopeTag(
                    RemoteAssistanceJournalScope(
                        companyId = cacheScope.companyId,
                        installationId = report.installationId,
                        userId = cacheScope.userId,
                    ),
                )
                val retryEngineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val retrySyncEngine = SyncEngine(
                    db = afterResponseInterruption,
                    scope = retryEngineScope,
                    outboxSafety = OutboxSafetyGate(
                        afterResponseInterruption,
                        application.outboxOwnerStore,
                        application.tokens,
                    ),
                    cacheIsolation = cacheIsolation,
                    checkoutClientInstance = { report.installationId },
                    scheduleDurableSync = {},
                    gamingCleanupApi = retryApi,
                    gamingCleanupProofTagProvider = { proofTag },
                )
                val acknowledgementStartedAt = System.currentTimeMillis()
                try {
                    retrySyncEngine.acknowledgeCleanupRetirements(lease)
                    assertEquals(1, retryApi.acknowledgeCallCount)
                    assertEquals(directive.id, retryApi.lastReconciliationId)
                    assertEquals(report.installationId, retryApi.lastBody?.installationId)
                    assertEquals(
                        directive.candidateSha256,
                        retryApi.lastBody?.expectedCandidateSha256,
                    )
                    val acknowledgedRow = requireNotNull(
                        afterResponseInterruption.gamingDao()
                            .localSessionById(report.localActionId),
                    )
                    assertTrue(
                        requireNotNull(acknowledgedRow.cleanupAcknowledgedAtMillis) >=
                            acknowledgementStartedAt,
                    )
                    assertNull(acknowledgedRow.lastError)

                    retrySyncEngine.acknowledgeCleanupRetirements(lease)
                    assertEquals(1, retryApi.acknowledgeCallCount)
                } finally {
                    retryEngineScope.cancel()
                }
                assertEquals(
                    directive.localEvidenceRevision + 1,
                    afterResponseInterruption.gamingDao()
                        .localSessionById(report.localActionId)
                        ?.cleanupEvidenceRevision,
                )
                assertNull(
                    afterResponseInterruption.gamingDao()
                        .localSessionById(report.localActionId)
                        ?.lastError,
                )
            } finally {
                afterResponseInterruption.close()
            }

            val afterAcknowledgement =
                Room.databaseBuilder(context, ErpDatabase::class.java, databaseName).build()
            try {
                assertTrue(afterAcknowledgement.gamingDao().cleanupRetiredSessions().isEmpty())
                val terminal = requireNotNull(
                    afterAcknowledgement.gamingDao().localSessionById(report.localActionId),
                )
                assertEquals(GamingSessionState.CLEANUP_RETIRED, terminal.state)
                assertTrue(requireNotNull(terminal.cleanupAcknowledgedAtMillis) > 0L)
                assertEquals(directive.localEvidenceRevision + 1, terminal.cleanupEvidenceRevision)
            } finally {
                afterAcknowledgement.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private class RecordingGamingCleanupDeviceApi(
        private val acknowledgement: GamingCleanupReconciliation,
    ) : GamingCleanupDeviceApi {
        var acknowledgeCallCount = 0
            private set
        var lastReconciliationId: String? = null
            private set
        var lastBody: GamingCleanupAcknowledgeBody? = null
            private set
        var lastInstallationId: String? = null
            private set
        var lastRequestScope: RemoteRequestScopeTag? = null
            private set

        override suspend fun reportCleanupCandidate(
            body: GamingCleanupReportBody,
            installationId: String,
            requestScope: RemoteRequestScopeTag,
        ): GamingCleanupReconciliation = error("The restart sweep must not report a new candidate")

        override suspend fun acknowledgeCleanupCandidate(
            id: String,
            body: GamingCleanupAcknowledgeBody,
            installationId: String,
            requestScope: RemoteRequestScopeTag,
        ): GamingCleanupReconciliation {
            acknowledgeCallCount += 1
            lastReconciliationId = id
            lastBody = body
            lastInstallationId = installationId
            lastRequestScope = requestScope
            return acknowledgement
        }
    }

    private class CleanScopeDataPurger : ScopeDataPurger {
        override suspend fun hasUnresolvedWork(): Boolean = false
        override suspend fun purgeIfClean(): Boolean = true
    }

    private class FixedCacheScopeMarker(initial: CacheScope) : CacheScopeMarker {
        private var stored: CacheScope? = initial

        override fun current(): CacheScope? = stored

        override fun remember(scope: CacheScope): Boolean {
            stored = scope
            return true
        }

        override fun clear(): Boolean {
            stored = null
            return true
        }
    }
}

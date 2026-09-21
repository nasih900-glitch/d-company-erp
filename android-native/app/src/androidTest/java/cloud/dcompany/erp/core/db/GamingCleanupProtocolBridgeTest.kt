package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.sync.cleanupAcknowledgementMatches
import cloud.dcompany.erp.core.sync.gamingCleanupCandidateSha256
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupReconciliation
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupReportBody
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
 */
@RunWith(AndroidJUnit4::class)
class GamingCleanupProtocolBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun backendApprovedDirectiveAppliesAndAcknowledgesExactlyAcrossRoomReopen() = runBlocking {
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
        val acknowledgedAtMillis =
            clock.getValue("acknowledged_at_millis").jsonPrimitive.long

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
                assertEquals(
                    directive.localEvidenceRevision + 1,
                    retained.cleanupEvidenceRevision,
                )
                assertEquals(
                    report.localActionId,
                    afterRetirement.gamingDao().cleanupRetiredSessions().single().localId,
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
                assertEquals(
                    0,
                    afterRetirement.gamingDao().markCleanupAcknowledgedCas(
                        localId = report.localActionId,
                        reconciliationId = directive.id,
                        candidateSha256 = "0".repeat(64),
                        acknowledgedAtMillis = acknowledgedAtMillis,
                    ),
                )
                assertEquals(
                    1,
                    afterRetirement.gamingDao().markCleanupAcknowledgedCas(
                        localId = report.localActionId,
                        reconciliationId = acknowledgement.id,
                        candidateSha256 = acknowledgement.candidateSha256,
                        acknowledgedAtMillis = acknowledgedAtMillis,
                    ),
                )
                assertEquals(
                    directive.localEvidenceRevision + 1,
                    afterRetirement.gamingDao()
                        .localSessionById(report.localActionId)
                        ?.cleanupEvidenceRevision,
                )
            } finally {
                afterRetirement.close()
            }

            val afterAcknowledgement =
                Room.databaseBuilder(context, ErpDatabase::class.java, databaseName).build()
            try {
                assertTrue(afterAcknowledgement.gamingDao().cleanupRetiredSessions().isEmpty())
                val terminal = requireNotNull(
                    afterAcknowledgement.gamingDao().localSessionById(report.localActionId),
                )
                assertEquals(GamingSessionState.CLEANUP_RETIRED, terminal.state)
                assertEquals(acknowledgedAtMillis, terminal.cleanupAcknowledgedAtMillis)
                assertEquals(directive.localEvidenceRevision + 1, terminal.cleanupEvidenceRevision)
            } finally {
                afterAcknowledgement.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}

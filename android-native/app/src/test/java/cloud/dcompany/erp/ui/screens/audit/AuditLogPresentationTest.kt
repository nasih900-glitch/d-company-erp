package cloud.dcompany.erp.ui.screens.audit

import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Query

class AuditLogPresentationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `audit DTO decodes backend actor entity time and provenance contract`() {
        val entry = json.decodeFromString<AuditEntry>(
            """
            {
              "id": 42,
              "actor_user_id": "actor-id",
              "actor_name": "Protected Owner",
              "actor_email": "owner@example.com",
              "action": "create",
              "entity_type": "Payment",
              "entity_id": "payment-1234567890",
              "before": null,
              "after": {"amount_minor": 18000},
              "ip": "192.0.2.1",
              "user_agent": "DCompanyERP/2 (Android 15)",
              "terminal_id": "terminal-id",
              "request_id": "request-id",
              "client_platform": "Android",
              "client_version_code": 2,
              "client_action_id": "client-action-id",
              "client_reported_at": "2026-08-25T12:34:55Z",
              "client_was_offline": true,
              "synced_at": "2026-08-25T12:34:56Z",
              "reason": "Cash payment",
              "created_at": "2026-08-25T12:34:56Z"
            }
            """.trimIndent(),
        )

        assertEquals("Protected Owner", auditActor(entry))
        assertEquals("Payment created", auditEntryTitle(entry))
        assertEquals("Captured offline and later synced", auditConnectionLabel(entry))
        assertEquals("Android build 2", auditClientLabel(entry))
        assertEquals("25 Aug, 12:34:56", formatAuditTimestamp(entry.createdAt, ZoneOffset.UTC, Locale.UK))
        assertEquals("request-id", entry.requestId)
        assertEquals("terminal-id", entry.terminalId)
        assertEquals("Cash payment", entry.reason)
        assertNotNull(entry.after)
    }

    @Test
    fun `audit presentation is safe for legacy server rows with missing provenance`() {
        val entry = json.decodeFromString<AuditEntry>(
            """
            {
              "id": 1,
              "actor_user_id": null,
              "actor_name": null,
              "actor_email": null,
              "action": "update",
              "entity_type": "GamingSession",
              "entity_id": "short-id",
              "created_at": "not-a-date"
            }
            """.trimIndent(),
        )

        assertEquals("System / script", auditActor(entry))
        assertEquals("Legacy / server activity", auditConnectionLabel(entry))
        assertEquals(null, auditClientLabel(entry))
        assertEquals("not-a-date", formatAuditTimestamp(entry.createdAt, ZoneOffset.UTC, Locale.UK))
        assertEquals("short-id", shortAuditId(entry.entityId))
        assertFalse(auditEntryTitle(entry).isBlank())
    }

    @Test
    fun `membership acceptance settlement and withdrawal have truthful labels`() {
        assertEquals("Membership payment", auditEntityLabel("MembershipPayment"))
        assertEquals("Membership refund request", auditEntityLabel("MembershipRefund"))
        assertEquals("POS refund request", auditEntityLabel("PosRefundRequest"))
        assertEquals(
            "POS cash refund handed over",
            auditEntityLabel("PosRefundCashHandoffCompletion"),
        )
        assertEquals(
            "POS provider refund completed",
            auditEntityLabel("PosRefundProviderSettlement"),
        )
        assertEquals(
            "Membership refund settlement",
            auditEntityLabel("MembershipRefundSettlement"),
        )
        assertEquals(
            "Membership refund withdrawal",
            auditEntityLabel("MembershipRefundResolution"),
        )
    }

    @Test
    fun `audit facets decode server driven filters`() {
        val facets = json.decodeFromString<AuditFacets>(
            """{"entity_types":["Attendance","Payment"],"actions":["clock_in","create"]}""",
        )

        assertEquals(listOf("Attendance", "Payment"), facets.entityTypes)
        assertEquals(listOf("clock_in", "create"), facets.actions)
    }

    @Test
    fun `audit API exposes all supported server filters and no fake export`() {
        val listMethod = AuditLogApi::class.java.methods.single { it.name == "list" }
        val queryNames = listMethod.parameterAnnotations
            .flatMap { annotations -> annotations.filterIsInstance<Query>() }
            .map(Query::value)

        assertTrue(queryNames.containsAll(listOf("limit", "before_id", "area", "entity_type", "action", "q")))
        assertFalse(AuditLogApi::class.java.methods.any { it.name.contains("export", ignoreCase = true) })
    }
}

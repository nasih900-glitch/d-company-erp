package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST

class BugReportApiContractTest {

    @Test
    fun `durable support routing keeps evidence but removes volatile terminal authority`() {
        val request = BugReportCreateRequest(
            category = BugReportCategory.Other,
            severity = BugReportSeverity.High,
            title = "Action failed · Gaming",
            description = "The station could not be sent to POS.",
            clientContext = BugReportClientContext(
                platform = "android",
                branchId = "11111111-1111-1111-1111-111111111111",
                branchName = "Main Shop",
                terminalId = "22222222-2222-2222-2222-222222222222",
                terminalName = "Gaming Area",
                connectivity = "offline",
            ),
        )

        val durable = request.withStableSupportRouting()

        assertEquals(request.clientContext.branchId, durable.clientContext.branchId)
        assertEquals("Main Shop", durable.clientContext.branchName)
        assertEquals("Gaming Area", durable.clientContext.terminalName)
        assertNull(durable.clientContext.terminalId)
    }

    @Test
    fun `create endpoint posts with an idempotency key`() {
        val method = BugReportApi::class.java.methods.single { it.name == "create" }

        assertEquals("bug-reports", method.getAnnotation(POST::class.java)?.value)
        assertTrue(method.parameterAnnotations[0].any { it is Body })
        assertEquals(
            "Idempotency-Key",
            method.parameterAnnotations[1].filterIsInstance<Header>().single().value,
        )
    }

    @Test
    fun `reporter history and explicit attachment routes match backend`() {
        val mine = BugReportApi::class.java.methods.single { it.name == "mine" }
        val upload = BugReportApi::class.java.methods.single { it.name == "uploadAttachment" }

        assertEquals("bug-reports/mine", mine.getAnnotation(GET::class.java)?.value)
        assertEquals(
            "bug-reports/mine/{report_id}/attachments",
            upload.getAnnotation(POST::class.java)?.value,
        )
        assertNotNull(upload.getAnnotation(Multipart::class.java))
        assertEquals(
            "Idempotency-Key",
            upload.parameterAnnotations[2].filterIsInstance<Header>().single().value,
        )
    }

    @Test
    fun `request uses allowlisted contextual contract with no secrets or screenshot bytes`() {
        val request = BugReportDraft(
            reason = SupportRequestReason.SomethingFailed,
            canContinue = WorkContinuation.Blocked,
            description = "Cash payment did not complete after tapping Pay.",
        ).toRequest(
            BugReportClientContext(
                platform = "android",
                appVersion = "3.0.8",
                versionCode = 9,
                deviceModel = "Xiaomi Redmi Pad 2",
                osVersion = "Android 15 (API 35)",
                currentScreen = "POS",
                lastAction = "Opened Help from POS",
                errorCode = "server_unreachable",
                branchId = "11111111-1111-4111-8111-111111111111",
                branchName = "Main Shop",
                terminalId = "22222222-2222-4222-8222-222222222222",
                terminalName = "Main workspace",
                connectivity = "online",
                occurredAt = "2026-08-28T10:15:30Z",
            ),
        )
        val root = ApiClient.json.parseToJsonElement(ApiClient.json.encodeToString(request)).jsonObject
        val context = root.getValue("client_context").jsonObject

        assertEquals(JsonPrimitive("other"), root["category"])
        assertEquals(JsonPrimitive("high"), root["severity"])
        assertEquals(JsonPrimitive("POS"), context["current_screen"])
        assertEquals(JsonPrimitive("Opened Help from POS"), context["last_action"])
        assertEquals(JsonPrimitive("server_unreachable"), context["error_code"])
        assertNull(root["reproduction_steps"])

        val encoded = root.toString().lowercase()
        for (forbidden in listOf(
            "company_id",
            "reporter_id",
            "access_token",
            "refresh_token",
            "password",
            "order_content",
            "customer_content",
            "log_dump",
            "screenshot",
        )) {
            assertFalse("Unexpected diagnostic field: $forbidden", encoded.contains(forbidden))
        }
    }

    @Test
    fun `mine response decodes public replies and ignores owner-only additions`() {
        val result = ApiClient.json.decodeFromString<BugReportMinePage>(
            """{
              "items":[{
                "id":"33333333-3333-4333-8333-333333333333",
                "title":"Staff needs help · Gaming",
                "status":"acknowledged",
                "public_replies":[{
                  "id":"44444444-4444-4444-8444-444444444444",
                  "author_name":"Nasih",
                  "message":"Use Retry send; I corrected the setting.",
                  "created_at":"2026-08-28T10:20:00Z"
                }],
                "created_at":"2026-08-28T10:16:00Z",
                "updated_at":"2026-08-28T10:20:00Z",
                "internal_resolution_note":"must never be required by Android"
              }],
              "total":1,"limit":20,"offset":0
            }""".trimIndent(),
        )

        assertEquals("acknowledged", result.items.single().status)
        assertEquals("Nasih", result.items.single().publicReplies.single().authorName)
        assertEquals(1, result.total)
    }
}

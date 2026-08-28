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
import retrofit2.http.Header
import retrofit2.http.POST

class BugReportApiContractTest {

    @Test
    fun `create endpoint posts to bug reports with an idempotency key`() {
        val method = BugReportApi::class.java.methods.single { it.name == "create" }

        assertEquals("bug-reports", method.getAnnotation(POST::class.java)?.value)
        assertTrue(method.parameterAnnotations[0].any { it is Body })
        assertEquals(
            "Idempotency-Key",
            method.parameterAnnotations[1].filterIsInstance<Header>().single().value,
        )
    }

    @Test
    fun `request uses the exact snake case backend contract and no identity fields`() {
        val request = BugReportDraft(
            category = BugReportCategory.Payment,
            severity = BugReportSeverity.High,
            title = "Cash payment total is wrong",
            description = "The displayed total changed after payment.",
            reproductionSteps = "Open POS and pay cash",
            expectedBehavior = "Total remains unchanged",
            actualBehavior = "Total increased",
        ).toRequest(
            BugReportClientContext(
                platform = "android",
                appVersion = "3.0.5",
                versionCode = 6,
                deviceModel = "Xiaomi Redmi Pad 2",
                osVersion = "Android 15 (API 35)",
                currentScreen = "Settings",
                branchId = "11111111-1111-4111-8111-111111111111",
                branchName = "Main Branch",
                terminalId = "22222222-2222-4222-8222-222222222222",
                terminalName = "Main POS",
                connectivity = "online",
                occurredAt = "2026-08-28T10:15:30Z",
            ),
        )
        val root = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(request),
        ).jsonObject
        val context = root.getValue("client_context").jsonObject

        assertEquals(JsonPrimitive("payment"), root["category"])
        assertEquals(JsonPrimitive("high"), root["severity"])
        assertEquals(JsonPrimitive("Open POS and pay cash"), root["reproduction_steps"])
        assertEquals(JsonPrimitive("Total remains unchanged"), root["expected_behavior"])
        assertEquals(JsonPrimitive("Total increased"), root["actual_behavior"])
        assertEquals(JsonPrimitive("android"), context["platform"])
        assertEquals(JsonPrimitive("3.0.5"), context["app_version"])
        assertEquals(JsonPrimitive(6), context["version_code"])
        assertEquals(JsonPrimitive("online"), context["connectivity"])

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
    fun `blank optional details are omitted rather than sent as empty data`() {
        val request = BugReportDraft(
            title = "Button does nothing",
            description = "Pressing the action gives no feedback.",
        ).toRequest(BugReportClientContext(platform = "android", connectivity = "unknown"))
        val root = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(request),
        ).jsonObject

        assertNull(root["reproduction_steps"])
        assertNull(root["expected_behavior"])
        assertNull(root["actual_behavior"])
        assertNotNull(root["client_context"])
    }

    @Test
    fun `response tolerates server owned fields beyond the Android receipt`() {
        val result = ApiClient.json.decodeFromString<BugReportCreateResponse>(
            """{
              "id":"33333333-3333-4333-8333-333333333333",
              "status":"open",
              "created_at":"2026-08-28T10:16:00Z",
              "company_id":"server-owned",
              "reporter_name":"Rafi"
            }""".trimIndent(),
        )

        assertEquals("open", result.status)
        assertEquals("2026-08-28T10:16:00Z", result.createdAt)
    }
}

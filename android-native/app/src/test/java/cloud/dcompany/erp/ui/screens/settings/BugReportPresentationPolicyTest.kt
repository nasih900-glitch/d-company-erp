package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.net.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportPresentationPolicyTest {

    @Test
    fun `required fields match backend minimums and optional fields match maximums`() {
        val short = BugReportDraft(title = "Four", description = "Too short")
        val valid = BugReportDraft(
            title = "Valid title",
            description = "A clear description of the issue.",
            reproductionSteps = "x".repeat(BUG_REPORT_DETAIL_MAX_LENGTH),
        )
        val tooLong = valid.copy(actualBehavior = "x".repeat(BUG_REPORT_DETAIL_MAX_LENGTH + 1))

        assertFalse(short.validate().isValid)
        assertTrue(short.validate().title.orEmpty().contains("at least 5"))
        assertTrue(short.validate().description.orEmpty().contains("at least 10"))
        assertTrue(valid.validate().isValid)
        assertTrue(tooLong.validate().actualBehavior.orEmpty().contains("4000"))
    }

    @Test
    fun `trimmed required values and blank optional values form a clean request`() {
        val request = BugReportDraft(
            title = "  Shift cannot close  ",
            description = "  Closing gives no explanation.  ",
            reproductionSteps = "   ",
        ).toRequest(BugReportClientContext(platform = "android", connectivity = "unknown"))

        assertEquals("Shift cannot close", request.title)
        assertEquals("Closing gives no explanation.", request.description)
        assertNull(request.reproductionSteps)
    }

    @Test
    fun `safe diagnostics normalize limits and discard malformed ids`() {
        val context = buildBugReportClientContext(
            appVersion = "3.0.5" + "x".repeat(60),
            versionCode = 6,
            manufacturer = "Xiaomi",
            model = "Redmi Pad 2",
            osRelease = "15",
            apiLevel = 35,
            currentScreen = "Settings",
            branchId = "not-a-uuid",
            branchName = "B".repeat(200),
            terminalId = "22222222-2222-4222-8222-222222222222",
            terminalName = "Main POS",
            connectivity = BugReportConnectivity.Online,
            occurredAt = "2026-08-28T10:15:30Z",
        )

        assertEquals("android", context.platform)
        assertEquals(40, context.appVersion?.length)
        assertEquals("Xiaomi Redmi Pad 2", context.deviceModel)
        assertEquals("Android 15 (API 35)", context.osVersion)
        assertNull(context.branchId)
        assertEquals(200, context.branchName?.length)
        assertEquals("22222222-2222-4222-8222-222222222222", context.terminalId)
        assertEquals("online", context.connectivity)
    }

    @Test
    fun `connectivity distinguishes definite offline from backend uncertainty`() {
        assertEquals(
            BugReportConnectivity.Online,
            bugReportConnectivity(effectiveOnline = true, networkValidated = true),
        )
        assertEquals(
            BugReportConnectivity.Offline,
            bugReportConnectivity(effectiveOnline = false, networkValidated = false),
        )
        assertEquals(
            BugReportConnectivity.Unknown,
            bugReportConnectivity(effectiveOnline = false, networkValidated = true),
        )
    }

    @Test
    fun `report failures are actionable and never expose raw technical text`() {
        val missing = ApiException("Request failed (HTTP 404)", status = 404).bugReportReadable()
        val oldBuild = ApiException("upgrade", status = 426).bugReportReadable()
        val limited = ApiException("rate limit", status = 429).bugReportReadable()
        val idempotencyInProgress = ApiException(
            "still checking",
            status = 409,
            code = "idempotency_in_progress",
        ).bugReportReadable()
        val definitiveConflict = ApiException(
            "request hash changed",
            status = 409,
            code = "idempotency_conflict",
        ).bugReportReadable()
        val ambiguous = ApiException("socket timeout secret internals").bugReportReadable()
        val unexpected = IllegalStateException("database stack trace").bugReportReadable()

        assertTrue(missing.contains("not available", ignoreCase = true))
        assertTrue(missing.contains("draft", ignoreCase = true))
        assertTrue(oldBuild.contains("latest", ignoreCase = true))
        assertTrue(limited.contains("hourly", ignoreCase = true))
        assertTrue(limited.contains("wait", ignoreCase = true))
        assertTrue(idempotencyInProgress.contains("not be created twice", ignoreCase = true))
        assertFalse(idempotencyInProgress.contains("web inbox", ignoreCase = true))
        assertTrue(definitiveConflict.contains("system owner", ignoreCase = true))
        assertTrue(definitiveConflict.contains("web bug-report inbox", ignoreCase = true))
        assertTrue(ambiguous.contains("not be created twice", ignoreCase = true))
        assertFalse(ambiguous.contains("socket", ignoreCase = true))
        assertTrue(unexpected.contains("draft", ignoreCase = true))
        assertFalse(unexpected.contains("database", ignoreCase = true))
    }

    @Test
    fun `all backend category and severity wire values are represented`() {
        assertEquals(
            setOf(
                "crash",
                "incorrect_data",
                "payment",
                "sync",
                "permission",
                "performance",
                "usability",
                "other",
            ),
            BugReportCategory.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf("low", "medium", "high", "critical"),
            BugReportSeverity.entries.map { it.wireValue }.toSet(),
        )
    }
}

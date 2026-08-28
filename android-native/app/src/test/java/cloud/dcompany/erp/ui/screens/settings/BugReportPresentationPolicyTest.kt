package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.net.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportPresentationPolicyTest {

    @Test
    fun `one concise description enforces the backend limits`() {
        val short = BugReportDraft(description = "Too short")
        val valid = BugReportDraft(description = "A clear description of the issue.")
        val tooLong = valid.copy(description = "x".repeat(BUG_REPORT_DETAIL_MAX_LENGTH + 1))

        assertFalse(short.validate().isValid)
        assertTrue(short.validate().description.orEmpty().contains("at least 10"))
        assertTrue(valid.validate().isValid)
        assertTrue(tooLong.validate().description.orEmpty().contains("4000"))
    }

    @Test
    fun `staff choices map to backend category severity and generated title`() {
        val request = BugReportDraft(
            reason = SupportRequestReason.IncorrectInformation,
            canContinue = WorkContinuation.Blocked,
            description = "The displayed cash total is higher than expected.",
        ).toRequest(
            BugReportClientContext(
                platform = "android",
                currentScreen = "Shift",
                connectivity = "online",
            ),
        )

        assertEquals(BugReportCategory.IncorrectData, request.category)
        assertEquals(BugReportSeverity.High, request.severity)
        assertEquals("Incorrect information · Shift", request.title)
        assertEquals("The displayed cash total is higher than expected.", request.description)
        assertNull(request.reproductionSteps)
    }

    @Test
    fun `safe diagnostics normalise action error and discard malformed ids`() {
        val context = buildBugReportClientContext(
            appVersion = "3.0.8" + "x".repeat(60),
            versionCode = 9,
            manufacturer = "Xiaomi",
            model = "Redmi Pad 2",
            osRelease = "15",
            apiLevel = 35,
            currentScreen = "Gaming",
            lastAction = "Opened Help from Gaming",
            errorCode = "server_unreachable",
            branchId = "not-a-uuid",
            branchName = "Main Shop",
            terminalId = "22222222-2222-4222-8222-222222222222",
            terminalName = "Main workspace",
            connectivity = BugReportConnectivity.Online,
            occurredAt = "2026-08-28T10:15:30Z",
        )

        assertEquals("Xiaomi Redmi Pad 2", context.deviceModel)
        assertEquals("Gaming", context.currentScreen)
        assertEquals("Opened Help from Gaming", context.lastAction)
        assertEquals("server_unreachable", context.errorCode)
        assertNull(context.branchId)
        assertEquals("22222222-2222-4222-8222-222222222222", context.terminalId)
    }

    @Test
    fun `unsafe error strings never enter client context`() {
        val context = buildBugReportClientContext(
            appVersion = "3.0.8",
            versionCode = 9,
            manufacturer = "Xiaomi",
            model = "Pad",
            osRelease = "15",
            apiLevel = 35,
            currentScreen = "POS\npassword=secret",
            errorCode = "HTTP 500 customer=123",
            branchId = null,
            branchName = null,
            terminalId = null,
            terminalName = null,
            connectivity = BugReportConnectivity.Unknown,
            occurredAt = "2026-08-28T10:15:30Z",
        )

        assertNull(context.currentScreen)
        assertNull(context.errorCode)
    }

    @Test
    fun `connectivity distinguishes offline from backend uncertainty`() {
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
    fun `delivery failures are actionable without raw technical text`() {
        val missing = ApiException("raw URL and stack", status = 404).bugReportReadable()
        val limited = ApiException("rate limit internals", status = 429).bugReportReadable()
        val ambiguous = IllegalStateException("socket secret").bugReportReadable()

        assertTrue(missing.contains("not available", ignoreCase = true))
        assertFalse(missing.contains("stack", ignoreCase = true))
        assertTrue(limited.contains("retry later", ignoreCase = true))
        assertTrue(ambiguous.contains("remains saved", ignoreCase = true))
        assertFalse(ambiguous.contains("socket", ignoreCase = true))
        assertEquals(
            BugReportFailureDisposition.Retry,
            bugReportFailureDisposition(ApiException("timeout")),
        )
        assertEquals(
            BugReportFailureDisposition.ActionRequired,
            bugReportFailureDisposition(ApiException("forbidden", status = 403)),
        )
    }
}

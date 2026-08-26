package cloud.dcompany.erp.core.net

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalHeaderPolicyTest {

    @Test
    fun `clean branch or company switch can verify profile without stale till`() {
        val request = request("auth/me", callerHeader = "old-company-till")
            .withResolvedTerminal("old-company-till")

        assertNull(request.header(TERMINAL_ID_HEADER))
    }

    @Test
    fun `deleted till can recover through terminal discovery`() {
        val request = request("settings/terminals?branch_id=new-branch")
            .withResolvedTerminal("deleted-till")

        assertNull(request.header(TERMINAL_ID_HEADER))
    }

    @Test
    fun `login and compatibility bootstrap never inherit a till`() {
        assertNull(request("auth/login").withResolvedTerminal("stale").header(TERMINAL_ID_HEADER))
        assertNull(
            request("public/client-compatibility")
                .withResolvedTerminal("stale")
                .header(TERMINAL_ID_HEADER),
        )
    }

    @Test
    fun `normal POS write keeps mandatory resolved till header`() {
        val request = request("pos/orders").withResolvedTerminal("main-counter")

        assertEquals("main-counter", request.header(TERMINAL_ID_HEADER))
    }

    @Test
    fun `cashier A logout then kitchen B cannot inherit cashier terminal`() {
        val context = ActiveTerminalHeaderContext()
        context.activate("branch-a-counter")
        assertEquals(
            "branch-a-counter",
            context.apply(request("kitchen/tickets")).header(TERMINAL_ID_HEADER),
        )

        // Logout/blocked/bootstrap transitions revoke runtime authority but
        // deliberately do not erase the persisted terminal candidate.
        context.deactivate()

        assertNull(
            context.apply(request("kitchen/tickets", callerHeader = "branch-a-counter"))
                .header(TERMINAL_ID_HEADER),
        )
    }

    @Test
    fun `POS branch B gets only terminal activated after validation`() {
        val context = ActiveTerminalHeaderContext()
        context.activate("branch-a-counter")
        context.deactivate()
        assertNull(context.apply(request("pos/orders")).header(TERMINAL_ID_HEADER))

        context.activate("branch-b-counter")

        assertEquals(
            "branch-b-counter",
            context.apply(request("pos/orders")).header(TERMINAL_ID_HEADER),
        )
    }

    private fun request(path: String, callerHeader: String? = null): Request {
        val builder = Request.Builder().url("https://example.test/api/v1/$path")
        if (callerHeader != null) builder.header(TERMINAL_ID_HEADER, callerHeader)
        return builder.build()
    }
}

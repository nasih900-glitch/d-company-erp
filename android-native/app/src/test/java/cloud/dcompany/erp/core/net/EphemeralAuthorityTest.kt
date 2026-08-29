package cloud.dcompany.erp.core.net

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EphemeralAuthorityTest {

    @Test
    fun `bootstrap removes caller authority and never sends a terminal`() {
        val request = Request.Builder()
            .url("https://example.test/api/v1/auth/login")
            .header("Authorization", "Bearer staff-token")
            .header(TERMINAL_ID_HEADER, "stale-terminal")
            .build()

        val isolated = request.withEphemeralAuthority(
            accessToken = null,
            terminalId = "current-terminal",
        )

        assertNull(isolated.header("Authorization"))
        assertNull(isolated.header(TERMINAL_ID_HEADER))
    }

    @Test
    fun `recovery request uses only transient owner bearer and exact terminal`() {
        val request = Request.Builder()
            .url("https://example.test/api/v1/gaming/legacy-outbox-resolutions")
            .header("Authorization", "Bearer staff-token")
            .header(TERMINAL_ID_HEADER, "stale-terminal")
            .build()

        val isolated = request.withEphemeralAuthority(
            accessToken = "owner-token",
            terminalId = "current-terminal",
        )

        assertEquals("Bearer owner-token", isolated.header("Authorization"))
        assertEquals("current-terminal", isolated.header(TERMINAL_ID_HEADER))
        // withEphemeralAuthority is a pure Request transform. It has no
        // TokenStore/cache-scope dependency and therefore cannot install the
        // owner bearer over the active staff session.
        assertEquals("Bearer staff-token", request.header("Authorization"))
        assertEquals("stale-terminal", request.header(TERMINAL_ID_HEADER))
    }
}

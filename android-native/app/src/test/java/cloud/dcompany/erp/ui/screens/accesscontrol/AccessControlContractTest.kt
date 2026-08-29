package cloud.dcompany.erp.ui.screens.accesscontrol

import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessControlContractTest {

    @Test
    fun `partial access decodes exact backend ceiling evidence`() {
        val cell = Json.decodeFromString<AccessCell>(
            """
            {
              "role_code":"staff",
              "module":"finance",
              "default_allowed":false,
              "override":true,
              "allowed":true,
              "default_access_level":"blocked",
              "access_level":"partial",
              "effective_permissions":["finance.read"],
              "unavailable_permissions":["finance.write","finance.partner.write"],
              "ceiling_limited_permissions":["finance.write","finance.partner.write"]
            }
            """.trimIndent(),
        )

        assertEquals("partial", cell.accessLevel)
        assertEquals(listOf("finance.read"), cell.effectivePermissions)
        assertEquals(2, cell.unavailablePermissions.size)
        assertEquals(2, cell.ceilingLimitedPermissions.size)
        assertTrue(accessChangeNotice(cell).contains("partial", ignoreCase = true))
        assertTrue(accessChangeNotice(cell).contains("2 that cannot be granted"))
    }

    @Test
    fun `legacy response retains safe display fallback`() {
        val cell = Json.decodeFromString<AccessCell>(
            """{"role_code":"cashier","module":"pos","default_allowed":true,"override":null,"allowed":true}""",
        )
        assertEquals("partial", cell.accessLevel)
        assertEquals("partial", cell.defaultAccessLevel)
        assertTrue(accessChangeNotice(cell).contains("exact permission evidence"))
    }

    @Test
    fun `reset override always sends explicit JSON null`() {
        val encoded = ApiClient.json.encodeToString(
            AccessControlUpdateBody("cashier", "pos", JsonNull),
        )
        assertTrue(encoded.contains("\"allowed\":null"))
    }

    @Test
    fun `transport failure freezes permission edits until authoritative refresh`() {
        val transport = accessMutationFailure(IOException("connection reset"))
        assertTrue(transport.authorityUnknown)
        assertTrue(transport.message.contains("result is unknown"))

        val refusal = accessMutationFailure(
            cloud.dcompany.erp.core.net.ApiException(
                "role cannot be granted",
                status = 422,
                code = "business_rule",
            ),
        )
        assertEquals(false, refusal.authorityUnknown)
        assertEquals("role cannot be granted", refusal.message)
    }
}

package cloud.dcompany.erp.core.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeResponseBranchNameCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `named branch decodes without changing branch id authority`() {
        val profile = json.decodeFromString<MeResponse>(payload("\"branch_name\":\"Main Cafe\","))

        assertEquals("branch-1", profile.branchId)
        assertEquals("Main Cafe", profile.branchName)
    }

    @Test
    fun `legacy profile without branch name remains readable`() {
        val profile = json.decodeFromString<MeResponse>(payload(""))

        assertEquals("branch-1", profile.branchId)
        assertNull(profile.branchName)
    }

    private fun payload(optionalField: String) =
        """
        {
          "user_id": "user-1",
          "email": "staff@example.test",
          "name": "Staff",
          "roles": ["staff"],
          "company_id": "company-1",
          "branch_id": "branch-1",
          $optionalField
          "accessible_modules": []
        }
        """.trimIndent()
}

package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BugReportRunAuthorityTest {
    private val ownerA = OutboxOwnerIdentity("user-a", "company-1", "branch-1")

    @Test
    fun `same company employee switch cannot supply the next request bearer`() {
        var bearer = jwt("user-a", "company-1", "branch-1", marker = "first")
        val authority = BugReportRunAuthority(ownerA) { bearer }

        assertEquals(bearer, authority.currentBearer())

        bearer = jwt("user-b", "company-1", "branch-1", marker = "second")
        assertNull(authority.currentBearer())
    }

    @Test
    fun `same login may rotate its token but may not change branch`() {
        var bearer = jwt("user-a", "company-1", "branch-1", marker = "first")
        val authority = BugReportRunAuthority(ownerA) { bearer }

        bearer = jwt("user-a", "company-1", "branch-1", marker = "rotated")
        assertEquals(bearer, authority.currentBearer())

        bearer = jwt("user-a", "company-1", "branch-2", marker = "moved")
        assertNull(authority.currentBearer())
    }

    private fun jwt(
        userId: String,
        companyId: String,
        branchId: String,
        marker: String,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload =
            """{"sub":"$userId","company_id":"$companyId","branch_id":"$branchId","marker":"$marker"}"""
        return listOf("{}", payload, marker)
            .joinToString(".") { encoder.encodeToString(it.encodeToByteArray()) }
    }
}

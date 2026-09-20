package cloud.dcompany.erp.core.alarm

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.CachedScopeLeaseAdoption
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationalAlarmRuntimeTest {

    @Test
    fun `receiver scope requires token profile identity and exact operational workspace`() {
        val profile = profile(effectivePermissions = listOf(ErpPermission.PosRead))

        assertEquals(
            CacheScope("employee-1", "company-1", "branch-1", "terminal-1"),
            cachedOperationalAlarmScope(token(), profile, " terminal-1 "),
        )
        assertNull(cachedOperationalAlarmScope(token(), profile, null))
        assertNull(cachedOperationalAlarmScope(token(userId = "other"), profile, "terminal-1"))
    }

    @Test
    fun `gaming receiver uses the same terminal scoped cache as foreground`() {
        val profile = profile(effectivePermissions = listOf(ErpPermission.GamingRead))

        assertEquals(
            CacheScope("employee-1", "company-1", "branch-1", "terminal-1"),
            cachedOperationalAlarmScope(token(), profile, "terminal-1"),
        )
        assertNull(cachedOperationalAlarmScope(token(), profile, null))
    }

    @Test
    fun `non operational receiver scope never adopts a stale terminal`() {
        val profile = profile(effectivePermissions = listOf(ErpPermission.FinanceRead))

        assertEquals(
            CacheScope("employee-1", "company-1", "branch-1", null),
            cachedOperationalAlarmScope(token(), profile, "terminal-from-another-role"),
        )
    }

    @Test
    fun `malformed or cross company token cannot reactivate alarms`() {
        val profile = profile(effectivePermissions = listOf(ErpPermission.GamingRead))

        assertNull(cachedOperationalAlarmScope("not-a-jwt", profile, null))
        assertNull(cachedOperationalAlarmScope(token(companyId = "other-company"), profile, null))
        assertNull(cachedOperationalAlarmScope(token(branchId = "other-branch"), profile, null))
    }

    @Test
    fun `only disproved saved owner maps to definitive no owner`() {
        assertNull(
            operationalAlarmAdoptionOrNull(CachedScopeLeaseAdoption.StoredScopeMismatch),
        )
        assertThrows(IllegalStateException::class.java) {
            operationalAlarmAdoptionOrNull(CachedScopeLeaseAdoption.ActiveScopeConflict)
        }

        val ready = CachedScopeLeaseAdoption.Ready(
            lease = CacheScopeLease(
                CacheScope("employee-1", "company-1", "branch-1", "terminal-1"),
                generation = 7,
            ),
            adopted = true,
        )
        assertEquals(ready, operationalAlarmAdoptionOrNull(ready))
    }

    @Test
    fun `post adoption ownership loss is retryable and never definitive no owner`() {
        requireOperationalAlarmOwnershipAfterAdoption(
            tokenLineageStillOwned = true,
            exactLeaseStillOwned = true,
        )
        assertThrows(IllegalStateException::class.java) {
            requireOperationalAlarmOwnershipAfterAdoption(
                tokenLineageStillOwned = false,
                exactLeaseStillOwned = true,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireOperationalAlarmOwnershipAfterAdoption(
                tokenLineageStillOwned = true,
                exactLeaseStillOwned = false,
            )
        }
    }

    private fun profile(effectivePermissions: List<String>) = MeResponse(
        userId = "employee-1",
        email = "employee@example.invalid",
        name = "Employee",
        companyId = "company-1",
        branchId = "branch-1",
        effectivePermissions = effectivePermissions,
    )

    private fun token(
        userId: String = "employee-1",
        companyId: String = "company-1",
        branchId: String = "branch-1",
    ): String {
        fun encode(value: String): String = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray())
        return "${encode("{\"alg\":\"none\"}")}.${encode(
            "{\"sub\":\"$userId\",\"company_id\":\"$companyId\",\"branch_id\":\"$branchId\"}",
        )}.signature"
    }
}

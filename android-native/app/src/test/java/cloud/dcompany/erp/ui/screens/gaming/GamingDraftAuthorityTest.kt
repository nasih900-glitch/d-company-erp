package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.*
import org.junit.Test

class GamingDraftAuthorityTest {
    private val me = MeResponse("owner", "owner@example.test", "Owner", listOf("owner"),
        companyId = "company", branchId = null, accessibleModules = listOf("gaming"),
        effectivePermissions = listOf("gaming.sessions.manage"))
    private fun lease(generation: Long = 1) = CacheScopeLease(CacheScope("owner", "company", null, "till"), generation)

    @Test fun equalAuthoritySurvivesWrapperRecreationButEveryAuthorityChangeRekeysDraft() {
        val original = gamingDraftAuthority(me, lease(), "till")!!
        assertEquals(original, gamingDraftAuthority(me.copy(), lease(), "till"))
        assertNotEquals(original, gamingDraftAuthority(me, lease(2), "till"))
        for (changed in listOf(
            me.copy(roles = listOf("cashier")), me.copy(effectivePermissions = emptyList()),
            me.copy(accessibleModules = emptyList()), me.copy(auditAccess = true),
            me.copy(protectedAccess = true), me.copy(name = "Changed"),
        )) assertNotEquals(original, gamingDraftAuthority(changed, lease(), "till"))
        assertNotEquals(original, original.copy(processNonce = "another-process"))
        val otherBranch = me.copy(branchId = "branch")
        assertNotEquals(original, gamingDraftAuthority(otherBranch,
            CacheScopeLease(CacheScope("owner", "company", "branch", "till"), 1), "till"))
        assertNotEquals(original, gamingDraftAuthority(me,
            CacheScopeLease(CacheScope("owner", "company", null, "other-till"), 1), "other-till"))
    }

    @Test fun missingOrMismatchedLeaseNeverProducesDraftAuthority() {
        assertNull(gamingDraftAuthority(me, null, "till"))
        assertNull(gamingDraftAuthority(me, lease(), null))
        assertNull(gamingDraftAuthority(me, CacheScopeLease(CacheScope("owner", "company", null, null), 1), null))
        assertNull(gamingDraftAuthority(me, CacheScopeLease(CacheScope("owner", "company", null, ""), 1), ""))
        assertNull(gamingDraftAuthority(me, lease(), "other-till"))
        assertNull(gamingDraftAuthority(me.copy(userId = "other"), lease(), "till"))
        assertNull(gamingDraftAuthority(me.copy(companyId = "other"), lease(), "till"))
        assertNull(gamingDraftAuthority(me.copy(branchId = "branch"), lease(), "till"))
    }

    @Test fun initialEmptyProjectionPreservesSelectionButLoadedRemovalResolvesIt() {
        assertFalse(GamingUiState().catalogueLoaded)
        assertEquals("ps5-2", resolveGamingCommandStationId(emptyList(), "ps5-2", false))
        assertEquals("ps5", resolveGamingFilterId(listOf("all"), "ps5", false))
        assertEquals("ps5-2", resolveGamingCommandStationId(listOf("ps5-1", "ps5-2"), "ps5-2", true))
        assertEquals("ps5-1", resolveGamingCommandStationId(listOf("ps5-1"), "ps5-2", true))
        assertNull(resolveGamingCommandStationId(emptyList(), "ps5-2", true))
        assertEquals("all", resolveGamingFilterId(listOf("all"), "ps5", true))
    }
}

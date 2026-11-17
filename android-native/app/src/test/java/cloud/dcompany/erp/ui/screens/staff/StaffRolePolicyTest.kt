package cloud.dcompany.erp.ui.screens.staff

import cloud.dcompany.erp.core.auth.roleDisplayLabel
import cloud.dcompany.erp.core.auth.rolePurposeSummary
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StaffRolePolicyTest {
    private fun editor(role: String = "owner") = StaffEditor(
        id = "u", name = "Employee", phone = "", status = "active", roleCode = role,
        originalStatus = "active", originalRoleCode = role, isSelf = false,
        accessChangesLocked = role == "owner", canManageOwnerAccess = true,
    )

    private fun fresh(vararg exact: String, public: String = "owner") = StaffUser(
        id = "u", email = "employee@example.test", name = "Employee", status = "active",
        roles = listOf(public), managedRoles = exact.toList(),
    )

    @Test fun `online owner metadata keeps exact operational tier through unrelated saves`() {
        val resolved = resolveStaffEditorAccess(editor(), fresh("co_owner"))
        assertEquals("co_owner", resolved.roleCode)
        assertFalse(resolved.accessChangesLocked)
        assertNull(staffRoleUpdate(resolved.copy(name = "Updated")))
        assertNull(staffRoleUpdate(resolved.copy(status = "suspended")))
        assertEquals("manager", staffRoleUpdate(resolved.copy(roleCode = "manager")))
        assertEquals("Owner (operational override)", roleDisplayLabel(resolved.roleCode, true))
    }

    @Test fun `public cached owner stays locked when exact metadata is absent`() {
        val publicOnly = fresh().copy(managedRoles = null)
        val resolved = resolveStaffEditorAccess(editor(), publicOnly)
        assertTrue(resolved.accessChangesLocked)
        assertTrue(resolved.roleChangesLocked)
        assertNull(staffRoleUpdate(resolved.copy(roleCode = "manager")))
        assertEquals("Owner", roleDisplayLabel("co_owner"))
        assertEquals(listOf("owner"), publicOnly.roles)
    }

    @Test fun `protected owner is identified only from returned exact metadata and remains immutable`() {
        val resolved = resolveStaffEditorAccess(editor(), fresh("super_owner"))
        assertTrue(resolved.accessChangesLocked)
        assertNull(staffRoleUpdate(resolved.copy(roleCode = "owner")))
        assertEquals("Owner (protected audit)", roleDisplayLabel(resolved.roleCode, true))
    }

    @Test fun `delayed role lookup preserves an operator selection and status edit`() {
        val changed = editor("staff").copy(roleCode = "manager", status = "suspended")
        val resolved = resolveStaffEditorAccess(changed, fresh("staff", public = "staff"))
        assertEquals("manager", resolved.roleCode)
        assertEquals("suspended", resolved.status)
        assertEquals("manager", staffRoleUpdate(resolved))
    }

    @Test fun `three everyday existing codes coexist with advanced roles and preserve legacy roundtrip`() {
        assertEquals(listOf("owner", "store_staff", "auditor"), EVERYDAY_STAFF_ROLES)
        val catalog = listOf("owner", "co_owner", "super_owner", "manager", "gaming_cashier", "auditor", "partner", "pos_discount")
            .map { StaffRole(it, it) }
        val publicOptions = editableStaffRoles(catalog, editor("partner"))
        assertFalse(publicOptions.any { it.code in listOf("co_owner", "super_owner", "pos_discount") })
        assertTrue(publicOptions.any { it.code == "partner" })
        assertNull(staffRoleUpdate(editor("partner")))
        assertTrue(editableStaffRoles(emptyList(), editor("legacy_custom")).any { it.code == "legacy_custom" })
        val exactOptions = editableStaffRoles(catalog, resolveStaffEditorAccess(editor(), fresh("co_owner")))
        assertTrue(exactOptions.any { it.code == "co_owner" })
    }

    @Test fun `supplement never becomes a primary role or write`() {
        assertEquals("gaming_cashier", primaryStaffRole(listOf("pos_discount", "gaming_cashier")))
        assertNull(staffRoleUpdate(editor("gaming_cashier").copy(roleCode = "pos_discount")))
        assertTrue(rolePurposeSummary("gaming_cashier").contains("POS sales and voids"))
        assertTrue(rolePurposeSummary("manager").contains("No payroll access by default"))
        assertTrue(roleDisplayLabel("partner").contains("configurable"))
    }

    @Test fun `wire metadata decodes without changing the public role contract or update body`() {
        val decoded = ApiClient.json.decodeFromString<StaffUser>(
            """{"id":"u","email":"e@test","name":"Owner","status":"active","roles":["owner"],"managed_roles":["co_owner"]}""",
        )
        assertEquals(listOf("owner"), decoded.roles)
        assertEquals(listOf("co_owner"), decoded.managedRoles)
        val encoded = ApiClient.json.encodeToString(StaffUserUpdateBody(name = "Updated"))
        assertFalse(encoded.contains("role_code"))
        assertFalse(encoded.contains("managed_roles"))
    }

    @Test fun `late exact metadata requires original actor company lease and editor generation`() {
        val lease = CacheScopeLease(CacheScope("actor", "company", null, null), 1)
        val actor = MeResponse(userId = "actor", email = "a@test", name = "Actor", companyId = "company")
        val binding = StaffEditorLookupBinding(lease, 1, "u")
        assertTrue(binding.isCurrent(lease, 1, editor(), actor))
        assertFalse(binding.isCurrent(lease.copy(generation = 2), 1, editor(), actor))
        assertFalse(binding.isCurrent(lease, 2, editor(), actor))
        assertFalse(binding.isCurrent(lease, 1, null, actor))
        assertFalse(binding.isCurrent(lease, 1, editor().copy(id = "different"), actor))
        assertFalse(binding.isCurrent(lease, 1, editor(), actor.copy(userId = "other")))
        assertFalse(binding.isCurrent(lease, 1, editor(), actor.copy(companyId = "other")))
    }

    @Test fun `mixed roles remain untouched until explicit replacement even when first role is Viewer`() {
        val mixed = editor("auditor").copy(publicRoles = listOf("auditor", "manager", "pos_discount"))
        assertTrue(mixed.hasMultiplePrimaryRoles)
        assertNull(staffRoleUpdate(mixed))
        assertEquals("auditor", staffRoleUpdate(mixed.copy(replaceMultipleRoles = true)))
        assertNull(staffRoleUpdate(mixed.copy(replaceMultipleRoles = true, accessChangesLocked = true)))
        val supplementOnly = mixed.copy(publicRoles = listOf("gaming_cashier", "pos_discount"), roleCode = "gaming_cashier", originalRoleCode = "gaming_cashier")
        assertFalse(supplementOnly.hasMultiplePrimaryRoles)
        assertNull(staffRoleUpdate(supplementOnly.copy(replaceMultipleRoles = true)))
    }
}

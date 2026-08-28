package cloud.dcompany.erp.ui.screens.staff

import cloud.dcompany.erp.core.db.LocalStaffEntity
import cloud.dcompany.erp.core.db.StaffCacheEntity
import cloud.dcompany.erp.core.db.StaffWriteState
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffContractPolicyTest {

    @Test
    fun `validated terminal branch is fallback only for branchless account`() {
        assertEquals("profile-branch", resolveAttendanceBranchId(" profile-branch ", "terminal-branch"))
        assertEquals("terminal-branch", resolveAttendanceBranchId(null, " terminal-branch "))
        assertNull(resolveAttendanceBranchId(" ", " "))
    }

    @Test
    fun `staff forms enforce backend length contract with actionable errors`() {
        val editor = StaffEditor(
            id = "user", name = "", phone = "", status = "active", roleCode = "staff",
            originalStatus = "active", originalRoleCode = "staff", isSelf = false,
            accessChangesLocked = false,
        )
        assertEquals("Enter the staff member's name.", editor.validationError)
        assertEquals(
            "Phone must be 20 characters or fewer.",
            editor.copy(name = "Employee", phone = "1".repeat(21)).validationError,
        )

        val login = CreateLoginDraft(
            name = "Employee",
            email = "employee@example.test",
            password = "1234567890",
            confirmPassword = "different1",
        )
        assertEquals("Passwords don't match.", login.validationError)
        assertEquals(
            "New password must be 256 characters or fewer.",
            PasswordResetDraft("id", "Employee", "employee@example.test", "x".repeat(257), "x".repeat(257))
                .validationError,
        )
    }

    @Test
    fun `empty phone is encoded as explicit clear while null remains omitted`() {
        val clear = ApiClient.json.encodeToString(StaffUserUpdateBody(phone = ""))
        val unchanged = ApiClient.json.encodeToString(StaffUserUpdateBody(name = "Employee"))

        assertTrue(clear.contains("\"phone\":\"\""))
        assertFalse(unchanged.contains("phone"))
    }

    @Test
    fun `masked owner access is locked unless caller is protected owner`() {
        val cache = listOf(
            StaffCacheEntity(
                id = "owner-id",
                email = "owner@example.test",
                name = "Owner",
                phone = null,
                status = "active",
                rolesCsv = "owner",
                lastLoginAt = null,
            ),
        )

        val managerView = mergeStaff(cache, emptyList<LocalStaffEntity>(), "manager-id", false).single()
        assertTrue(managerView.accessChangesLocked)
        assertFalse(managerView.canDelete)

        val protectedOwnerView = mergeStaff(cache, emptyList(), "protected-id", true).single()
        assertFalse(protectedOwnerView.accessChangesLocked)
        assertTrue(protectedOwnerView.canDelete)

        val selfView = mergeStaff(cache, emptyList(), "owner-id", true).single()
        assertTrue(selfView.accessChangesLocked)
        assertFalse(selfView.canDelete)
    }

    @Test
    fun `rejected access edit never masquerades as current server authority`() {
        val cache = listOf(
            StaffCacheEntity(
                id = "owner-id", email = "owner@example.test", name = "Owner", phone = "123",
                status = "active", rolesCsv = "owner", lastLoginAt = null,
            ),
        )
        val rejected = LocalStaffEntity(
            localId = "write", serverId = "owner-id", name = "Wrong overlay", phone = "",
            status = "suspended", roleCode = "manager", createdAtMillis = 1,
            state = StaffWriteState.REJECTED, lastError = "Only the protected owner can change owner access.",
        )

        val row = mergeStaff(cache, listOf(rejected), "manager-id", false).single()
        assertEquals("Owner", row.name)
        assertEquals("123", row.phone)
        assertEquals("active", row.status)
        assertEquals(listOf("owner"), row.roles)
        assertTrue(row.accessChangesLocked)
        assertFalse(row.canDelete)
    }

    @Test
    fun `only definitive rejected edits can be discarded`() {
        val base = StaffRow(
            id = "id", email = "e@test", name = "Employee", phone = null,
            status = "active", roles = listOf("staff"), lastLoginAt = null,
            localWriteId = "local", localWriteVersion = 2,
        )
        assertFalse(base.copy(pendingLocalId = "local").canDiscardRejectedChange)
        assertTrue(base.copy(rejectedError = "Server refused this.").canDiscardRejectedChange)
        assertFalse(
            base.copy(rejectedError = "Server refused delete.", hasQueuedDelete = true)
                .canDiscardRejectedChange,
        )
    }
}

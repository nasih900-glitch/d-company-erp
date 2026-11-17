package cloud.dcompany.erp.ui.screens.staff

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.MeResponse

internal val EVERYDAY_STAFF_ROLES = listOf("owner", "store_staff", "auditor")
private val OWNER_ROLES = setOf("owner", "co_owner", "super_owner")

internal data class StaffEditorLookupBinding(
    val lease: CacheScopeLease,
    val editGeneration: Long,
    val targetUserId: String,
) {
    fun isCurrent(
        currentLease: CacheScopeLease?, currentGeneration: Long,
        editor: StaffEditor?, actor: MeResponse?,
    ): Boolean = currentLease == lease && currentGeneration == editGeneration &&
        editor?.id == targetUserId && actor?.userId == lease.scope.userId &&
        actor?.companyId == lease.scope.companyId
}

internal fun primaryStaffRole(roles: List<String>): String =
    roles.firstOrNull { it != "pos_discount" }.orEmpty()

internal fun editableStaffRoles(roles: List<StaffRole>, editor: StaffEditor): List<StaffRole> {
    val withCurrent = if (roles.any { it.code == editor.originalRoleCode } || editor.originalRoleCode.isBlank()) {
        roles
    } else roles + StaffRole(editor.originalRoleCode, editor.originalRoleCode)
    return withCurrent.filter {
        it.code != "super_owner" && it.code != "pos_discount" &&
            (it.code !in OWNER_ROLES || editor.canManageOwnerAccess) &&
            (it.code != "co_owner" || editor.managedRoles != null)
    }
}

internal fun staffRoleUpdate(editor: StaffEditor): String? =
    editor.roleCode.takeUnless {
        editor.roleChangesLocked || it.isBlank() || it == "pos_discount" ||
            (it == editor.originalRoleCode && !(editor.replaceMultipleRoles && editor.hasMultiplePrimaryRoles))
    }

/** Refresh only transient editor metadata; never persist exact roles in the public cache. */
internal fun resolveStaffEditorAccess(editor: StaffEditor, fresh: StaffUser): StaffEditor {
    require(editor.id == fresh.id)
    val exactRoles = fresh.managedRoles
    val currentRole = primaryStaffRole(exactRoles ?: fresh.roles)
    val isOwner = (fresh.roles + exactRoles.orEmpty()).any { it in OWNER_ROLES }
    val isProtected = exactRoles?.contains("super_owner") == true
    val locked = editor.isSelf || isProtected ||
        (isOwner && (!editor.canManageOwnerAccess || exactRoles == null))
    return editor.copy(
        roleCode = if (editor.roleCode == editor.originalRoleCode) currentRole else editor.roleCode,
        originalRoleCode = currentRole,
        managedRoles = exactRoles,
        publicRoles = fresh.roles,
        accessChangesLocked = locked,
    )
}

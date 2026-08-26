package cloud.dcompany.erp.core.auth

import cloud.dcompany.erp.core.net.MeResponse

/** Permission names shared with backend/app/core/permissions.py. */
object ErpPermission {
    const val PosRead = "pos.read"
    const val PosWrite = "pos.write"
    const val PosVoid = "pos.void"
    const val PosShiftOpen = "pos.shift.open"
    const val PosShiftClose = "pos.shift.close"
    const val PosRefund = "pos.refund"
    const val TablesRead = "tables.read"
    const val TablesWrite = "tables.write"
    const val MenuRead = "menu.read"
    const val MenuWrite = "menu.write"
    const val InventoryRead = "inventory.read"
    const val InventoryWrite = "inventory.write"
    const val InventoryAdjustLarge = "inventory.adjust.large"
    const val GamingRead = "gaming.read"
    const val GamingWrite = "gaming.write"
    const val GamingTournamentManage = "gaming.tournament.manage"
    const val KitchenRead = "kitchen.read"
    const val KitchenWrite = "kitchen.write"
    const val FinanceRead = "finance.read"
    const val FinanceWrite = "finance.write"
    const val FinancePartnerWrite = "finance.partner.write"
    const val FinanceAssetsWrite = "finance.assets.write"
    const val StaffRead = "staff.read"
    const val StaffWrite = "staff.write"
    const val StaffAttendanceWrite = "staff.attendance.write"
    const val AnalyticsRead = "analytics.read"
    const val AdminAuditRead = "admin.audit.read"
    const val AdminSystem = "admin.system"
}

data class StaffAccess(
    val canReadDirectory: Boolean,
    val canManageDirectory: Boolean,
    val canUseAttendance: Boolean,
)

data class PosAccess(val canCreateAndCollect: Boolean = false)
data class GamingAccess(val canManageSessions: Boolean = false)
data class KitchenAccess(val canAdvanceTickets: Boolean = false)
data class TablesAccess(
    val canCreateOrders: Boolean = false,
    /** Backend requires tables.write + pos.void for reasoned line cancellation. */
    val canCancelItems: Boolean = false,
    /** Backend requires tables.write + pos.write for table handoff. */
    val canSendToPos: Boolean = false,
)
data class CustomersAccess(val canManageCustomers: Boolean = false)
data class MenuAccess(val canManageMenu: Boolean = false)
data class InventoryAccess(
    val canManageInventory: Boolean = false,
    val canMakeLargeAdjustment: Boolean = false,
)
data class FinanceAccess(
    val canRecordExpenses: Boolean = false,
    val canManageAssets: Boolean = false,
    val canRecordPartnerCapital: Boolean = false,
) {
    val isViewOnly: Boolean
        get() = !canRecordExpenses && !canManageAssets && !canRecordPartnerCapital
}
data class EventsAccess(
    val canManageEvents: Boolean = false,
    val canCheckInTickets: Boolean = false,
) {
    val isViewOnly: Boolean get() = !canManageEvents && !canCheckInTickets
}
data class ShiftAccess(
    val canOpen: Boolean = false,
    val canClose: Boolean = false,
) {
    val isViewOnly: Boolean get() = !canOpen && !canClose
}

const val VIEW_ONLY_MESSAGE = "View only — ask a manager if this action is required."

/** ViewModels repeat the UI check before any API call or outbox mutation. */
internal inline fun authorizeAction(allowed: Boolean, onDenied: () -> Unit): Boolean {
    if (!allowed) onDenied()
    return allowed
}

data class StaffLoadPlan(
    val loadRoles: Boolean,
    val pullDirectory: Boolean,
    val pullAttendance: Boolean,
    val pushManagementOutbox: Boolean,
)

fun StaffAccess.loadPlan(): StaffLoadPlan = StaffLoadPlan(
    loadRoles = canReadDirectory,
    pullDirectory = canReadDirectory,
    pullAttendance = canReadDirectory || canUseAttendance,
    pushManagementOutbox = canManageDirectory,
)

/**
 * Server-authoritative permissions with a conservative compatibility path for
 * profiles cached before `/auth/me` exposed `effective_permissions`.
 *
 * The fallback exists only for old payloads (null, not an empty list). New
 * responses always use the exact post-override server set, including a
 * deliberately empty set.
 */
class EffectivePermissions private constructor(private val granted: Set<String>) {
    fun has(permission: String): Boolean = permission in granted

    fun hasAny(vararg permissions: String): Boolean = permissions.any(::has)

    fun posAccess() = PosAccess(has(ErpPermission.PosWrite))

    fun staffAccess(): StaffAccess = StaffAccess(
        canReadDirectory = has(ErpPermission.StaffRead),
        canManageDirectory = has(ErpPermission.StaffWrite),
        canUseAttendance = has(ErpPermission.StaffAttendanceWrite),
    )

    fun gamingAccess() = GamingAccess(has(ErpPermission.GamingWrite))

    fun kitchenAccess() = KitchenAccess(has(ErpPermission.KitchenWrite))

    fun tablesAccess() = TablesAccess(
        canCreateOrders = has(ErpPermission.TablesWrite) && has(ErpPermission.PosWrite),
        canCancelItems = has(ErpPermission.TablesWrite) && has(ErpPermission.PosVoid),
        canSendToPos = has(ErpPermission.TablesWrite) && has(ErpPermission.PosWrite),
    )

    fun customersAccess() = CustomersAccess(has(ErpPermission.PosWrite))

    fun menuAccess() = MenuAccess(has(ErpPermission.MenuWrite))

    fun inventoryAccess() = InventoryAccess(
        canManageInventory = has(ErpPermission.InventoryWrite),
        canMakeLargeAdjustment = has(ErpPermission.InventoryAdjustLarge),
    )

    fun financeAccess() = FinanceAccess(
        canRecordExpenses = has(ErpPermission.FinanceWrite),
        canManageAssets = has(ErpPermission.FinanceAssetsWrite),
        canRecordPartnerCapital = has(ErpPermission.FinancePartnerWrite),
    )

    fun eventsAccess() = EventsAccess(
        canManageEvents = has(ErpPermission.GamingTournamentManage),
        canCheckInTickets = has(ErpPermission.GamingWrite),
    )

    fun shiftAccess() = ShiftAccess(
        canOpen = has(ErpPermission.PosShiftOpen),
        canClose = has(ErpPermission.PosShiftClose),
    )

    companion object {
        fun from(profile: MeResponse): EffectivePermissions {
            profile.effectivePermissions?.let { return EffectivePermissions(it.toSet()) }

            val legacy = when {
                profile.protectedAccess -> LEGACY_OPERATIONAL_PERMISSIONS
                else -> profile.roles.flatMapTo(mutableSetOf()) {
                    LEGACY_ROLE_PERMISSIONS[it].orEmpty()
                }
            }.toMutableSet()
            if (profile.auditAccess) {
                legacy += ErpPermission.AdminAuditRead
                legacy += ErpPermission.AdminSystem
            }

            // A present list is authoritative even when empty. A null list is
            // an older-than-modules cached profile, so role defaults are the
            // only safe compatibility signal available.
            profile.accessibleModules?.toSet()?.let { modules ->
                legacy.removeAll { permission ->
                    PERMISSION_MODULE[permission]?.let { it !in modules } == true
                }
            }
            return EffectivePermissions(legacy)
        }
    }
}

private val LEGACY_STAFF = setOf(
    ErpPermission.PosRead,
    ErpPermission.PosWrite,
    ErpPermission.TablesRead,
    ErpPermission.TablesWrite,
    ErpPermission.MenuRead,
    ErpPermission.KitchenRead,
    ErpPermission.StaffAttendanceWrite,
)

private val LEGACY_CASHIER = LEGACY_STAFF + setOf(
    ErpPermission.PosVoid,
    ErpPermission.PosShiftOpen,
    ErpPermission.PosShiftClose,
    ErpPermission.GamingRead,
)

private val LEGACY_MANAGER = LEGACY_CASHIER + setOf(
    ErpPermission.PosRefund,
    ErpPermission.InventoryRead,
    ErpPermission.InventoryWrite,
    ErpPermission.InventoryAdjustLarge,
    ErpPermission.MenuWrite,
    ErpPermission.GamingWrite,
    ErpPermission.GamingTournamentManage,
    ErpPermission.KitchenWrite,
    ErpPermission.FinanceRead,
    ErpPermission.FinanceWrite,
    ErpPermission.StaffRead,
    ErpPermission.StaffWrite,
    ErpPermission.AnalyticsRead,
)

private val LEGACY_PARTNER = setOf(
    ErpPermission.PosRead,
    ErpPermission.TablesRead,
    ErpPermission.MenuRead,
    ErpPermission.InventoryRead,
    ErpPermission.GamingRead,
    ErpPermission.KitchenRead,
    ErpPermission.FinanceRead,
    ErpPermission.StaffRead,
    ErpPermission.StaffAttendanceWrite,
    ErpPermission.AnalyticsRead,
)

private val LEGACY_AUDITOR = LEGACY_PARTNER - ErpPermission.StaffAttendanceWrite

private val LEGACY_ROLE_PERMISSIONS = mapOf(
    "super_owner" to LEGACY_MANAGER,
    "owner" to LEGACY_MANAGER,
    "co_owner" to LEGACY_MANAGER,
    "manager" to LEGACY_MANAGER,
    "partner" to LEGACY_PARTNER,
    "cashier" to LEGACY_CASHIER,
    "kitchen" to setOf(
        ErpPermission.MenuRead,
        ErpPermission.KitchenRead,
        ErpPermission.KitchenWrite,
        ErpPermission.StaffAttendanceWrite,
    ),
    "gaming_supervisor" to setOf(
        ErpPermission.PosRead,
        ErpPermission.MenuRead,
        ErpPermission.GamingRead,
        ErpPermission.GamingWrite,
        ErpPermission.GamingTournamentManage,
        ErpPermission.StaffAttendanceWrite,
    ),
    "auditor" to LEGACY_AUDITOR,
    "staff" to LEGACY_STAFF,
)

private val LEGACY_OPERATIONAL_PERMISSIONS = LEGACY_MANAGER + LEGACY_PARTNER + setOf(
    ErpPermission.FinancePartnerWrite,
    ErpPermission.FinanceAssetsWrite,
)

private val PERMISSION_MODULE = mapOf(
    ErpPermission.PosRead to "pos",
    ErpPermission.PosWrite to "pos",
    ErpPermission.PosVoid to "pos",
    ErpPermission.PosShiftOpen to "pos",
    ErpPermission.PosShiftClose to "pos",
    ErpPermission.PosRefund to "pos",
    ErpPermission.TablesRead to "tables",
    ErpPermission.TablesWrite to "tables",
    ErpPermission.MenuRead to "menu",
    ErpPermission.MenuWrite to "menu",
    ErpPermission.InventoryRead to "inventory",
    ErpPermission.InventoryWrite to "inventory",
    ErpPermission.InventoryAdjustLarge to "inventory",
    ErpPermission.GamingRead to "gaming",
    ErpPermission.GamingWrite to "gaming",
    ErpPermission.GamingTournamentManage to "gaming",
    ErpPermission.KitchenRead to "kitchen",
    ErpPermission.KitchenWrite to "kitchen",
    ErpPermission.FinanceRead to "finance",
    ErpPermission.FinanceWrite to "finance",
    ErpPermission.FinancePartnerWrite to "finance",
    ErpPermission.FinanceAssetsWrite to "finance",
    ErpPermission.StaffRead to "staff",
    ErpPermission.StaffWrite to "staff",
    ErpPermission.StaffAttendanceWrite to "staff",
    ErpPermission.AnalyticsRead to "insights_reports",
)

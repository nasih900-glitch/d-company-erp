package cloud.dcompany.erp.ui

/**
 * Product capabilities are deliberately separate from server permissions.
 *
 * Permissions answer whether this authenticated identity may perform an
 * operation. A feature profile answers whether this installation presents the
 * operation at all. Keeping both checks means the Gaming Centre release can
 * stay focused without deleting the restaurant and membership implementation
 * that a future hospitality profile may re-enable.
 */
enum class WorkspaceFeature {
    Dashboard,
    Pos,
    Gaming,
    Tables,
    Reservations,
    Kitchen,
    Shift,
    Customers,
    Menu,
    Staff,
    Inventory,
    Reports,
    Analytics,
    Finance,
    Events,
    Memberships,
    Refunds,
    AuditLog,
    AccessControl,
    Settings,
    SupportInbox,
    Help,
}

data class WorkspaceFeatureProfile(
    val id: String,
    val enabled: Set<WorkspaceFeature>,
    val navigationOrder: List<Destination>,
    val ownerOnly: Set<WorkspaceFeature> = emptySet(),
    val managementOnly: Set<WorkspaceFeature> = emptySet(),
) {
    init {
        require(id.isNotBlank())
        require(navigationOrder.distinct().size == navigationOrder.size) {
            "Feature-profile navigation destinations must be unique"
        }
    }

    fun includes(destination: Destination): Boolean =
        DESTINATION_FEATURES.getValue(destination) in enabled

    fun requiresOwner(destination: Destination): Boolean =
        DESTINATION_FEATURES.getValue(destination) in ownerOnly

    fun requiresManagement(destination: Destination): Boolean =
        DESTINATION_FEATURES.getValue(destination) in managementOnly
}

/** One centralized destination-to-capability registry for sidebar, command
 * search, restored routes, notification routes and future deep links. */
private val DESTINATION_FEATURES = mapOf(
    Destination.Dashboard to WorkspaceFeature.Dashboard,
    Destination.Pos to WorkspaceFeature.Pos,
    Destination.Gaming to WorkspaceFeature.Gaming,
    Destination.Tables to WorkspaceFeature.Tables,
    Destination.Reservations to WorkspaceFeature.Reservations,
    Destination.Kitchen to WorkspaceFeature.Kitchen,
    Destination.Shift to WorkspaceFeature.Shift,
    Destination.Customers to WorkspaceFeature.Customers,
    Destination.Menu to WorkspaceFeature.Menu,
    Destination.Staff to WorkspaceFeature.Staff,
    Destination.Inventory to WorkspaceFeature.Inventory,
    Destination.Reports to WorkspaceFeature.Reports,
    Destination.Analytics to WorkspaceFeature.Analytics,
    Destination.Finance to WorkspaceFeature.Finance,
    Destination.Events to WorkspaceFeature.Events,
    Destination.Memberships to WorkspaceFeature.Memberships,
    Destination.Refunds to WorkspaceFeature.Refunds,
    Destination.AuditLog to WorkspaceFeature.AuditLog,
    Destination.AccessControl to WorkspaceFeature.AccessControl,
    Destination.Settings to WorkspaceFeature.Settings,
    Destination.SupportInbox to WorkspaceFeature.SupportInbox,
    Destination.Help to WorkspaceFeature.Help,
)

object WorkspaceFeatureProfiles {
    /** Active 3.1 product shape: gaming operations first, owner controls second. */
    val GamingCentre = WorkspaceFeatureProfile(
        id = "gaming_centre_3_1",
        enabled = setOf(
            WorkspaceFeature.Dashboard,
            WorkspaceFeature.Pos,
            WorkspaceFeature.Gaming,
            WorkspaceFeature.Shift,
            WorkspaceFeature.Inventory,
            WorkspaceFeature.Menu,
            WorkspaceFeature.Finance,
            WorkspaceFeature.Reports,
            WorkspaceFeature.Staff,
            WorkspaceFeature.Settings,
            WorkspaceFeature.AuditLog,
            WorkspaceFeature.SupportInbox,
            WorkspaceFeature.Help,
        ),
        navigationOrder = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Inventory,
            Destination.Menu,
            Destination.Finance,
            Destination.Reports,
            Destination.Staff,
            Destination.Settings,
            Destination.AuditLog,
            Destination.SupportInbox,
            Destination.Help,
        ),
        ownerOnly = setOf(
            WorkspaceFeature.Dashboard,
            WorkspaceFeature.Finance,
            WorkspaceFeature.Reports,
            WorkspaceFeature.Staff,
            WorkspaceFeature.Settings,
            WorkspaceFeature.AuditLog,
            WorkspaceFeature.SupportInbox,
        ),
        managementOnly = setOf(WorkspaceFeature.Menu),
    )

    /** Dormant reference profile proving hidden hospitality code is retained. */
    val FullHospitality = WorkspaceFeatureProfile(
        id = "full_hospitality",
        enabled = WorkspaceFeature.entries.toSet(),
        navigationOrder = Destination.entries.filterNot {
            it in setOf(Destination.Dashboard, Destination.SupportInbox, Destination.Help)
        },
    )

    val Active: WorkspaceFeatureProfile = GamingCentre
}

/** Defensive route resolver used for restored state and every in-app route
 * request. A hidden destination is never returned to the content switch. */
fun resolveWorkspaceDestination(
    requested: Destination?,
    allowed: List<Destination>,
): Destination = requested?.takeIf { it in allowed }
    ?: allowed.firstOrNull()
    ?: Destination.Help

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
    /** Hide terminal choice/setup and require one server-confirmed hybrid identity. */
    val singleHybridTerminalOnly: Boolean = false,
    /**
     * Limits products offered for a new operational sale. This deliberately
     * does not filter Products management, saved carts, receipts, or reports:
     * those surfaces must retain the full catalogue and historical evidence.
     */
    val operationalCatalogPolicy: OperationalCatalogPolicy =
        OperationalCatalogPolicy.AllowEveryAvailableItem,
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

/**
 * A release-profile bridge until the backend exposes an explicit sales-channel
 * field on menu categories. Category taxonomy is less fragile than matching
 * individual product names, and the rule fails closed if a category is renamed
 * or assigned an incompatible item type.
 */
data class OperationalCatalogPolicy(
    private val allowedTypesByCategoryName: Map<String, Set<String>>,
    private val allowEveryAvailableItem: Boolean = false,
) {
    init {
        require(allowEveryAvailableItem || allowedTypesByCategoryName.isNotEmpty())
        require(allowedTypesByCategoryName.keys.all(String::isNotBlank))
        require(allowedTypesByCategoryName.values.all(Set<String>::isNotEmpty))
    }

    fun allows(
        categoryName: String?,
        itemType: String,
        isAvailable: Boolean,
    ): Boolean {
        if (!isAvailable) return false
        if (allowEveryAvailableItem) return true
        val allowedTypes = allowedTypesByCategoryName[normalizeCatalogValue(categoryName)]
            ?: return false
        return normalizeCatalogValue(itemType) in allowedTypes
    }

    companion object {
        val AllowEveryAvailableItem = OperationalCatalogPolicy(
            allowedTypesByCategoryName = emptyMap(),
            allowEveryAvailableItem = true,
        )

        fun restricted(rules: Map<String, Set<String>>): OperationalCatalogPolicy =
            OperationalCatalogPolicy(
                allowedTypesByCategoryName = rules.mapKeys { (name, _) ->
                    normalizeCatalogValue(name)
                }.mapValues { (_, types) -> types.mapTo(linkedSetOf(), ::normalizeCatalogValue) },
            )
    }
}

private fun normalizeCatalogValue(value: String?): String = value.orEmpty().trim().lowercase()

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
        singleHybridTerminalOnly = true,
        operationalCatalogPolicy = OperationalCatalogPolicy.restricted(
            mapOf(
                // The current server import creates this category for cans.
                "Soft Drinks" to setOf("drink"),
                // Existing Gaming Centre catalogues use one combined shelf
                // category for cans and packaged crisps.
                "Drinks & Snacks" to setOf("drink", "food"),
                // Either owner-facing taxonomy is accepted for packaged crisps.
                "Snacks" to setOf("food"),
                "Crisps" to setOf("food"),
            ),
        ),
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

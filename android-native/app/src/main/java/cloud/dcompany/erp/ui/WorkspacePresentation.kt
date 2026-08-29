package cloud.dcompany.erp.ui

/**
 * Copy and grouping rules derived from the active product profile.
 *
 * Feature flags decide what operators can enter or manage; they must never
 * erase historical accounting evidence.  Screens therefore use these labels
 * to keep hidden-module money visible under a neutral legacy category while a
 * full hospitality profile may continue to name the original module.
 */
data class WorkspacePresentationPolicy(
    val showsMemberships: Boolean,
    val showsRestaurantOperations: Boolean,
    val showsCustomers: Boolean,
    val showsEvents: Boolean,
) {
    val prepaidRevenueLabel: String
        get() = if (showsMemberships) "Memberships" else "Legacy/other prepaid revenue"

    val includedPrepaidRevenueLabel: String
        get() = if (showsMemberships) {
            "Included: membership receipts"
        } else {
            "Included: legacy/other prepaid revenue"
        }

    val prepaidRevenueDetail: String
        get() = if (showsMemberships) {
            "prepaid terms already included in net revenue above"
        } else {
            "historical hidden-module receipts already included in net revenue above"
        }

    val shiftPrepaidReceiptsLabel: String
        get() = if (showsMemberships) "Membership receipts" else "Legacy/other receipts"

    val shiftPrepaidRefundsLabel: String
        get() = if (showsMemberships) {
            "Settled membership refunds"
        } else {
            "Settled legacy/other refunds"
        }

    fun settledPrepaidReversalDetail(amount: String): String = if (showsMemberships) {
        "includes $amount in settled membership reversals"
    } else {
        "includes $amount in settled legacy/other prepaid reversals"
    }

    fun prepaidComparisonDetail(current: String, previous: String, previousLabel: String): String =
        if (showsMemberships) {
            "Paid memberships: $current this period, $previous in $previousLabel. " +
                "They do not increase POS order counts or top-item totals."
        } else {
            "Legacy/other prepaid revenue: $current this period, $previous in $previousLabel. " +
                "Historical hidden-module money remains included in revenue but does not " +
                "increase POS order counts or top-item totals."
        }

    val posOnlyTerminalLabel: String
        get() = if (showsRestaurantOperations) "Cafe POS" else "POS only (legacy)"

    val gamingTerminalLabel: String get() = if (showsRestaurantOperations) "Gaming Area" else "Gaming"

    val hybridTerminalLabel: String
        get() = if (showsRestaurantOperations) "Hybrid" else "Gaming + POS"
}

fun WorkspaceFeatureProfile.presentationPolicy(): WorkspacePresentationPolicy =
    WorkspacePresentationPolicy(
        showsMemberships = WorkspaceFeature.Memberships in enabled,
        showsRestaurantOperations = enabled.any {
            it in setOf(
                WorkspaceFeature.Tables,
                WorkspaceFeature.Reservations,
                WorkspaceFeature.Kitchen,
            )
        },
        showsCustomers = WorkspaceFeature.Customers in enabled,
        showsEvents = WorkspaceFeature.Events in enabled,
    )

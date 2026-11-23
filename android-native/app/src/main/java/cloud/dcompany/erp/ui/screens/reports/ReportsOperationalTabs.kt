package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheScopeLease

internal enum class ReportsOperationalTab(val label: String) {
    OVERVIEW("Overview"), SALES("Sales"), SESSIONS("Sessions"), CUSTOMERS("Customers"),
    INVENTORY("Inventory"), PAYMENTS("Payments"), DEVICES("Devices"),
}

internal fun availableReportsOperationalTabs(state: ReportsUiState): List<ReportsOperationalTab> =
    if (!state.access.canReadOverview) emptyList()
    else ReportsOperationalTab.entries.filter { it != ReportsOperationalTab.INVENTORY || state.access.canReadInventory }

internal fun reportsSelectOperationalTab(state: ReportsUiState, tab: ReportsOperationalTab): ReportsUiState =
    if (tab !in availableReportsOperationalTabs(state)) state
    else state.copy(surface = ReportsSurface.OVERVIEW, operationalTab = tab)

internal enum class ReportsOperationalLoad { NONE, OVERVIEW, INVENTORY }

/** Shared-data tabs reuse a read; returning from another surface rechecks freshness/offline state. */
internal fun reportsOperationalLoadNeeded(
    state: ReportsUiState,
    previous: ReportsUiState? = null,
): ReportsOperationalLoad = when {
    state.surface != ReportsSurface.OVERVIEW || !state.access.canReadOverview -> ReportsOperationalLoad.NONE
    state.operationalTab == ReportsOperationalTab.INVENTORY ->
        if (state.access.canReadInventory && !state.inventoryLoading) ReportsOperationalLoad.INVENTORY else ReportsOperationalLoad.NONE
    !state.overviewLoading && (state.overview == null ||
        previous?.surface == ReportsSurface.PNL ||
        previous?.operationalTab == ReportsOperationalTab.INVENTORY) -> ReportsOperationalLoad.OVERVIEW
    else -> ReportsOperationalLoad.NONE
}

internal fun reportsInventoryAttemptIsCurrent(
    state: ReportsUiState, lease: CacheScopeLease, currentLease: CacheScopeLease?, serial: Long, currentSerial: Long,
): Boolean = serial == currentSerial && lease == currentLease && state.lease == lease &&
    state.surface == ReportsSurface.OVERVIEW && state.operationalTab == ReportsOperationalTab.INVENTORY &&
    state.access.canReadOverview && state.access.canReadInventory

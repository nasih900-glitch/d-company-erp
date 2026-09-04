package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.ServerOpenShiftEntity
import cloud.dcompany.erp.core.db.ShiftHistoryCacheEntity
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail

/**
 * Pure wire-to-cache mappings shared by sync and contract tests.
 *
 * The collection/refund fields are intentionally copied without defaults.
 * A backend that predates those fields has not asserted that the values are
 * zero, so the local cache must retain null as "breakdown unavailable".
 */
internal fun ShiftDetail.toServerOpenShiftCache(
    terminalId: String,
    openedAtMillis: Long,
    verifiedAtMillis: Long,
): ServerOpenShiftEntity = ServerOpenShiftEntity(
    terminalId = terminalId,
    serverShiftId = id,
    branchId = branchId,
    status = status,
    openingFloatMinor = openingFloatMinor,
    expectedMinor = expectedMinor,
    posCollectionsMinor = posSalesMinor,
    membershipCollectionsMinor = membershipSalesMinor,
    grossCollectionsMinor = grossCollectionsMinor,
    cashCollectionsMinor = cashCollectionsMinor,
    cardCollectionsMinor = cardCollectionsMinor,
    upiCollectionsMinor = upiCollectionsMinor,
    otherCollectionsMinor = otherCollectionsMinor,
    settledPosRefundsMinor = settledPosRefundsMinor,
    settledMembershipRefundsMinor = settledMembershipRefundsMinor,
    totalRefundsMinor = totalRefundsMinor,
    netCollectionsMinor = netCollectionsMinor,
    openedAtMillis = openedAtMillis,
    openedByUserId = openedByUserId,
    openedByName = openedByName,
    openedByEmail = openedByEmail,
    verifiedAtMillis = verifiedAtMillis,
)

internal fun ShiftDetail.toShiftHistoryCache(
    terminalId: String,
    openedAtMillis: Long,
    closedAtMillis: Long?,
    fetchedAtMillis: Long,
): ShiftHistoryCacheEntity = ShiftHistoryCacheEntity(
    id = id,
    branchId = branchId,
    terminalId = terminalId,
    status = status,
    openedAtMillis = openedAtMillis,
    closedAtMillis = closedAtMillis,
    openingFloatMinor = openingFloatMinor,
    expectedMinor = expectedMinor,
    countedMinor = countedMinor,
    varianceMinor = varianceMinor,
    posSalesMinor = posSalesMinor,
    membershipSalesMinor = membershipSalesMinor,
    grossCollectionsMinor = grossCollectionsMinor,
    cashCollectionsMinor = cashCollectionsMinor,
    cardCollectionsMinor = cardCollectionsMinor,
    upiCollectionsMinor = upiCollectionsMinor,
    otherCollectionsMinor = otherCollectionsMinor,
    settledPosRefundsMinor = settledPosRefundsMinor,
    settledMembershipRefundsMinor = settledMembershipRefundsMinor,
    totalRefundsMinor = totalRefundsMinor,
    netCollectionsMinor = netCollectionsMinor,
    openedByUserId = openedByUserId,
    openedByName = openedByName,
    openedByEmail = openedByEmail,
    closedByUserId = closedByUserId,
    closedByName = closedByName,
    closedByEmail = closedByEmail,
    fetchedAtMillis = fetchedAtMillis,
)

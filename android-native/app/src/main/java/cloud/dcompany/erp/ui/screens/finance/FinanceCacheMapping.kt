package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.CapitalEntryCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity

internal fun Expense.toFinanceCache() = ExpenseCacheEntity(
    id = id, branchId = branchId, categoryId = categoryId, supplierId = supplierId,
    amountMinor = amountMinor, paidVia = paidVia, paidAt = paidAt,
    vendorName = vendorName, invoiceNo = invoiceNo, note = note,
)

internal fun Asset.toFinanceCache() = AssetCacheEntity(
    id = id, branchId = branchId, name = name, type = type,
    purchaseMinor = purchaseMinor, purchaseDate = purchaseDate,
    usefulLifeMonths = usefulLifeMonths, salvageMinor = salvageMinor,
    depreciationMethod = depreciationMethod, notes = notes,
    accumulatedDepreciationMinor = accumulatedDepreciationMinor, bookValueMinor = bookValueMinor,
)

internal fun CapitalEntry.toFinanceCache() = CapitalEntryCacheEntity(
    id = id, partnerId = partnerId, type = type, amountMinor = amountMinor,
    effectiveAt = effectiveAt, settlementAccount = settlementAccount, sourceRef = sourceRef,
    note = note, createdByName = createdByName, createdAt = createdAt,
    voidedAt = voidedAt, voidReason = voidReason, isVoided = isVoided,
)

package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.CapitalEntryCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity
import cloud.dcompany.erp.core.db.LocalExpenseEntity

internal const val SAVED_CLIENT_VERSION_CODE_HEADER = "X-Saved-Client-Version-Code"
internal const val CODE21_CASH_EXPENSE_ORIGIN_VERSION = 21

internal fun Expense.toFinanceCache() = ExpenseCacheEntity(
    id = id, branchId = branchId, categoryId = categoryId, supplierId = supplierId,
    amountMinor = amountMinor, paidVia = paidVia, paidAt = paidAt,
    vendorName = vendorName, invoiceNo = invoiceNo, note = note,
    receiptCount = receiptCount, receiptStatus = receiptStatus,
    shiftId = shiftId, createdBy = createdBy, isVoided = isVoided,
    sourceShiftStatus = sourceShiftStatus, isCorrected = isCorrected,
    correctionReason = correction?.reason, correctionAt = correction?.correctedAt,
)

/** One immutable outbox row always produces the same request body on every retry. */
internal fun LocalExpenseEntity.toExpenseCreate() = ExpenseCreate(
    branchId = branchId,
    shiftId = shiftId,
    categoryId = categoryId,
    supplierId = supplierId,
    amountMinor = amountMinor,
    paidVia = paidVia,
    paidAt = paidAt,
    vendorName = vendorName,
    invoiceNo = invoiceNo,
    note = note,
)

internal fun expenseIdempotencyKey(localId: String): String = "expense:$localId"

/** Route only a migration-marked Code21 row through legacy cash recovery. */
internal fun LocalExpenseEntity.legacyCashExpenseRecoveryHeaders(): Map<String, String> {
    val origin = legacyOriginVersionCode ?: return emptyMap()
    require(origin == CODE21_CASH_EXPENSE_ORIGIN_VERSION) {
        "Unsupported saved cash-expense origin version."
    }
    require(paidVia == "cash" && shiftId == null) {
        "Only an unresolved shiftless cash expense can use legacy recovery."
    }
    return mapOf(SAVED_CLIENT_VERSION_CODE_HEADER to origin.toString())
}

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

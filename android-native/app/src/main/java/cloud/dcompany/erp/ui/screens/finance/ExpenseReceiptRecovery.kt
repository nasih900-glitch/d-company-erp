package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import java.io.File
import java.time.OffsetDateTime

internal const val EXPENSE_RECEIPT_CAMERA_DIRECTORY = "expense_receipt_camera"
internal const val EXPENSE_RECEIPT_CAMERA_PREFIX = "expense-receipt-"
internal const val EXPENSE_RECEIPT_CAMERA_SUFFIX = ".jpg"
internal const val EXPENSE_RECEIPT_CAMERA_STALE_MILLIS = 24L * 60L * 60L * 1_000L
internal const val MAX_EXPENSE_RECEIPT_CAMERA_PRUNE_FILES = 64

internal sealed interface ExpenseReceiptReconciliation {
    data class AlreadyAccepted(val serverReceiptId: String) : ExpenseReceiptReconciliation
    data object ConfirmedAbsent : ExpenseReceiptReconciliation
    data class Unverifiable(val message: String) : ExpenseReceiptReconciliation
}

internal fun expenseReceiptDiscardScopeMatches(
    capturedOwner: OutboxOwnerIdentity,
    currentOwner: OutboxOwnerIdentity?,
    durableOutboxOwner: OutboxOwnerIdentity?,
    cacheScope: CacheScope?,
    hasFinanceWrite: Boolean,
): Boolean = hasFinanceWrite &&
    currentOwner == capturedOwner &&
    durableOutboxOwner == capturedOwner &&
    cacheScope != null &&
    cacheScope.userId == capturedOwner.userId &&
    cacheScope.companyId == capturedOwner.companyId &&
    cacheScope.branchId == capturedOwner.branchId

/**
 * A 200 response from the exact expense's tenant/branch-scoped list proves the
 * expense exists. Hashes prove whether this immutable evidence exists without
 * downloading its private bytes. Old servers that omit hashes fail closed.
 */
internal fun reconcileRejectedExpenseReceipt(
    expenseServerId: String,
    contentSha256: String,
    serverReceipts: List<ExpenseReceipt>,
): ExpenseReceiptReconciliation {
    if (!SHA_256_HEX.matches(contentSha256)) {
        return ExpenseReceiptReconciliation.Unverifiable(
            "The saved receipt identity is invalid. Nothing was removed; ask support to inspect it.",
        )
    }
    if (serverReceipts.any { it.expenseId != expenseServerId }) {
        return ExpenseReceiptReconciliation.Unverifiable(
            "The server returned an unexpected expense receipt. Nothing was removed; refresh Finance and try again.",
        )
    }
    serverReceipts.firstOrNull { it.sha256 == contentSha256 }?.let {
        return ExpenseReceiptReconciliation.AlreadyAccepted(it.id)
    }
    if (serverReceipts.any { it.sha256?.let(SHA_256_HEX::matches) != true }) {
        return ExpenseReceiptReconciliation.Unverifiable(
            "The server could not provide receipt fingerprints. Nothing was removed; update the server before retrying.",
        )
    }
    return ExpenseReceiptReconciliation.ConfirmedAbsent
}

/** Exact immutable-parent comparison for an accepted saved action receipt. */
internal fun authoritativeExpenseMatchesLocal(
    local: LocalExpenseEntity,
    server: Expense,
    expectedUserId: String,
): Boolean {
    val sameInstant = runCatching {
        OffsetDateTime.parse(local.paidAt).toInstant() ==
            OffsetDateTime.parse(server.paidAt).toInstant()
    }.getOrDefault(false)
    return server.createdBy == expectedUserId &&
        server.branchId == local.branchId &&
        server.shiftId == local.shiftId &&
        server.categoryId == local.categoryId &&
        server.supplierId == local.supplierId &&
        server.amountMinor == local.amountMinor &&
        server.paidVia == local.paidVia &&
        sameInstant &&
        server.vendorName == local.vendorName &&
        server.invoiceNo == local.invoiceNo &&
        server.note == local.note
}

internal data class ExpenseReceiptCameraPruneResult(
    val inspected: Int,
    val deleted: Int,
)

/**
 * Removes only old camera targets created by this feature. Work per entry is
 * capped, fresh files and the active capture target are always retained, and
 * unknown timestamps fail closed.
 */
internal fun pruneExpenseReceiptCameraFiles(
    directory: File,
    activeFilename: String?,
    nowMillis: Long,
    staleAfterMillis: Long = EXPENSE_RECEIPT_CAMERA_STALE_MILLIS,
    maximumFiles: Int = MAX_EXPENSE_RECEIPT_CAMERA_PRUNE_FILES,
): ExpenseReceiptCameraPruneResult {
    require(nowMillis >= 0)
    require(staleAfterMillis > 0)
    require(maximumFiles > 0)
    val canonicalActive = activeFilename
        ?.substringAfterLast('/')
        ?.takeIf { it.startsWith(EXPENSE_RECEIPT_CAMERA_PREFIX) && it.endsWith(EXPENSE_RECEIPT_CAMERA_SUFFIX) }
    val candidates = directory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { file ->
            file.isFile &&
                file.name.startsWith(EXPENSE_RECEIPT_CAMERA_PREFIX) &&
                file.name.endsWith(EXPENSE_RECEIPT_CAMERA_SUFFIX)
        }
        .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        .take(maximumFiles)
        .toList()
    var deleted = 0
    candidates.forEach { file ->
        val modified = file.lastModified()
        val oldEnough = modified > 0 && nowMillis >= modified && nowMillis - modified >= staleAfterMillis
        if (file.name != canonicalActive && oldEnough && file.delete()) deleted += 1
    }
    return ExpenseReceiptCameraPruneResult(inspected = candidates.size, deleted = deleted)
}

private val SHA_256_HEX = Regex("^[0-9a-f]{64}$")

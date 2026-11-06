package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.ui.screens.finance.SharedPreferencesFinanceWriteRecoveryStore
import cloud.dcompany.erp.ui.screens.finance.TenderJournal
import cloud.dcompany.erp.ui.screens.finance.privateTenderJournalStorage
import cloud.dcompany.erp.ui.screens.staff.time.StaffTimePendingPurpose
import cloud.dcompany.erp.ui.screens.staff.time.AtomicStaffTimePendingStore
import cloud.dcompany.erp.ui.screens.staff.time.SHARED_ATTENDANCE_DIRECTORY
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

internal enum class LocalHandoverWorkKind { ROOM, STAFF_TIME, SHARED_ATTENDANCE, FINANCE, TENDER, REMOTE }
internal data class LocalHandoverWork(
    val verified: Boolean,
    val blockers: Set<LocalHandoverWorkKind>,
    val unverified: Set<LocalHandoverWorkKind> = emptySet(),
    val roomPendingCount: Int = 0,
    val tenderReviewCount: Int = 0,
) {
    val isClear: Boolean get() = verified && blockers.isEmpty()
}

/** Actual private domains, read only. No store is erased or reassigned to obtain a clean result. */
internal suspend fun inspectLocalHandoverWork(app: DCompanyApp): LocalHandoverWork = withTimeout(10_000) {
    var checking = LocalHandoverWorkKind.ROOM
    val blockers = linkedSetOf<LocalHandoverWorkKind>()
    var roomCount = 0
    try {
        val groups = app.db.outboxSafetyDao().unresolvedGroups()
        roomCount = groups.sumOf { it.count.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (groups.isNotEmpty()) blockers += LocalHandoverWorkKind.ROOM
        checking = LocalHandoverWorkKind.STAFF_TIME
        val root = app.noBackupFilesDir.canonicalFile
        val ownTime = AtomicStaffTimePendingStore(File(root, "staff-time-intents-v1")).inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER)
        checking = LocalHandoverWorkKind.SHARED_ATTENDANCE
        val sharedTime = AtomicStaffTimePendingStore(File(root, SHARED_ATTENDANCE_DIRECTORY)).inspectRetainedWork(StaffTimePendingPurpose.SHARED)
        checking = LocalHandoverWorkKind.TENDER
        val tender = TenderJournal(privateTenderJournalStorage(app)).readiness()
        checking = LocalHandoverWorkKind.REMOTE
        val remote = app.remoteAssistance.inspectRetainedWork()
        val unverified = buildSet {
            if (!ownTime.verified) add(LocalHandoverWorkKind.STAFF_TIME)
            if (!sharedTime.verified) add(LocalHandoverWorkKind.SHARED_ATTENDANCE)
            if (!tender.verified) add(LocalHandoverWorkKind.TENDER)
            if (!remote.verified) add(LocalHandoverWorkKind.REMOTE)
        }
        if (unverified.isNotEmpty()) {
            return@withTimeout LocalHandoverWork(false, blockers, unverified, roomCount, tender.reviewCount)
        }
        if (ownTime.pending) blockers += LocalHandoverWorkKind.STAFF_TIME
        if (sharedTime.pending) blockers += LocalHandoverWorkKind.SHARED_ATTENDANCE
        checking = LocalHandoverWorkKind.FINANCE
        if (SharedPreferencesFinanceWriteRecoveryStore(app).hasSavedWork()) blockers += LocalHandoverWorkKind.FINANCE
        if (!tender.isClear) blockers += LocalHandoverWorkKind.TENDER
        if (remote.pending) blockers += LocalHandoverWorkKind.REMOTE
        LocalHandoverWork(true, blockers, roomPendingCount = roomCount, tenderReviewCount = tender.reviewCount)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        // Only bounded category/count metadata may leave this check.
        LocalHandoverWork(false, blockers, setOf(checking), roomCount)
    }
}

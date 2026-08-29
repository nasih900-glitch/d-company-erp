package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BugReportDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: LocalBugReportEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachment(row: LocalBugReportAttachmentEntity)

    @Transaction
    suspend fun insertBundle(
        report: LocalBugReportEntity,
        attachment: LocalBugReportAttachmentEntity?,
    ) {
        require(
            attachment == null || (
                attachment.reportLocalId == report.localId &&
                    attachment.ownerCompanyId == report.ownerCompanyId &&
                    attachment.ownerUserId == report.ownerUserId
                )
        ) { "Support attachment owner must match its parent report" }
        insert(report)
        attachment?.let { insertAttachment(it) }
    }

    @Query(
        "SELECT report.* FROM local_bug_reports report " +
            "WHERE report.ownerCompanyId = :companyId AND report.ownerUserId = :userId AND (" +
            "report.state IN ('pending', 'action_required') OR EXISTS (" +
            "SELECT 1 FROM local_bug_report_attachments attachment " +
            "WHERE attachment.reportLocalId = report.localId " +
            "AND attachment.ownerCompanyId = report.ownerCompanyId " +
            "AND attachment.ownerUserId = report.ownerUserId " +
            "AND attachment.state IN ('pending', 'action_required')) OR report.localId IN (" +
            "SELECT recent.localId FROM local_bug_reports recent " +
            "WHERE recent.ownerCompanyId = :companyId AND recent.ownerUserId = :userId " +
            "AND recent.state = 'sent' ORDER BY recent.createdAtMillis DESC, recent.localId DESC " +
            "LIMIT :recentResolvedLimit)) " +
            "ORDER BY report.createdAtMillis DESC, report.localId DESC",
    )
    fun observeForOwner(
        companyId: String,
        userId: String,
        recentResolvedLimit: Int = 20,
    ): Flow<List<LocalBugReportEntity>>

    @Query(
        "SELECT * FROM local_bug_report_attachments " +
            "WHERE ownerCompanyId = :companyId AND ownerUserId = :userId " +
            "ORDER BY createdAtMillis DESC",
    )
    fun observeAttachmentsForOwner(
        companyId: String,
        userId: String,
    ): Flow<List<LocalBugReportAttachmentEntity>>

    @Query(
        "SELECT * FROM local_bug_reports " +
        "WHERE ownerCompanyId = :companyId AND ownerUserId = :userId AND state = 'pending' " +
            "AND (:afterCreatedAtMillis IS NULL OR createdAtMillis > :afterCreatedAtMillis " +
            "OR (createdAtMillis = :afterCreatedAtMillis AND localId > :afterLocalId)) " +
            "ORDER BY createdAtMillis ASC, localId ASC LIMIT :limit",
    )
    suspend fun pendingForOwner(
        companyId: String,
        userId: String,
        afterCreatedAtMillis: Long? = null,
        afterLocalId: String = "",
        limit: Int = 20,
    ): List<LocalBugReportEntity>

    @Query(
        "SELECT attachment.* FROM local_bug_report_attachments attachment " +
            "INNER JOIN local_bug_reports report ON report.localId = attachment.reportLocalId " +
            "WHERE attachment.ownerCompanyId = :companyId AND attachment.ownerUserId = :userId " +
            "AND report.ownerCompanyId = attachment.ownerCompanyId " +
            "AND report.ownerUserId = attachment.ownerUserId " +
            "AND attachment.state = 'pending' AND report.state = 'sent' AND report.serverId IS NOT NULL " +
            "AND (:afterCreatedAtMillis IS NULL OR attachment.createdAtMillis > :afterCreatedAtMillis " +
            "OR (attachment.createdAtMillis = :afterCreatedAtMillis AND attachment.localId > :afterLocalId)) " +
            "ORDER BY attachment.createdAtMillis ASC, attachment.localId ASC LIMIT :limit",
    )
    suspend fun pushableAttachments(
        companyId: String,
        userId: String,
        afterCreatedAtMillis: Long? = null,
        afterLocalId: String = "",
        limit: Int = 20,
    ): List<LocalBugReportAttachmentEntity>

    @Query(
        "SELECT * FROM local_bug_reports WHERE localId = :localId " +
            "AND ownerCompanyId = :companyId AND ownerUserId = :userId LIMIT 1",
    )
    suspend fun reportForOwner(
        localId: String,
        companyId: String,
        userId: String,
    ): LocalBugReportEntity?

    @Query(
        "UPDATE local_bug_reports SET state = 'sent', serverId = :serverId, " +
            "serverStatus = :serverStatus, serverCreatedAt = :serverCreatedAt, " +
            "attemptCount = attemptCount + 1, lastAttemptAtMillis = :attemptedAtMillis, " +
            "lastError = NULL WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'pending'",
    )
    suspend fun markSent(
        localId: String,
        companyId: String,
        userId: String,
        serverId: String,
        serverStatus: String,
        serverCreatedAt: String,
        attemptedAtMillis: Long,
    ): Int

    /** Keep retryable/ambiguous failures pending with the exact same body/key. */
    @Query(
        "UPDATE local_bug_reports SET attemptCount = attemptCount + 1, " +
            "lastAttemptAtMillis = :attemptedAtMillis, lastError = :message " +
            "WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'pending'",
    )
    suspend fun noteRetryableFailure(
        localId: String,
        companyId: String,
        userId: String,
        message: String,
        attemptedAtMillis: Long,
    ): Int

    /** Definitive schema/permission refusals require a human or app update. */
    @Query(
        "UPDATE local_bug_reports SET state = 'action_required', " +
            "attemptCount = attemptCount + 1, lastAttemptAtMillis = :attemptedAtMillis, " +
            "lastError = :message WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'pending'",
    )
    suspend fun markActionRequired(
        localId: String,
        companyId: String,
        userId: String,
        message: String,
        attemptedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_bug_reports SET state = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'action_required'",
    )
    suspend fun retry(
        localId: String,
        companyId: String,
        userId: String,
    ): Int

    @Query(
        "UPDATE local_bug_report_attachments SET state = 'sent', serverAttachmentId = :serverId, " +
            "content = X'', " +
            "attemptCount = attemptCount + 1, lastAttemptAtMillis = :attemptedAtMillis, " +
            "lastError = NULL WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'pending'",
    )
    suspend fun markAttachmentSent(
        localId: String,
        companyId: String,
        userId: String,
        serverId: String,
        attemptedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_bug_report_attachments SET attemptCount = attemptCount + 1, " +
            "lastAttemptAtMillis = :attemptedAtMillis, lastError = :message " +
            "WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'pending'",
    )
    suspend fun noteAttachmentRetryableFailure(
        localId: String,
        companyId: String,
        userId: String,
        message: String,
        attemptedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_bug_report_attachments SET state = 'action_required', " +
            "attemptCount = attemptCount + 1, lastAttemptAtMillis = :attemptedAtMillis, " +
            "lastError = :message WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'pending'",
    )
    suspend fun markAttachmentActionRequired(
        localId: String,
        companyId: String,
        userId: String,
        message: String,
        attemptedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_bug_report_attachments SET state = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'action_required'",
    )
    suspend fun retryAttachment(
        localId: String,
        companyId: String,
        userId: String,
    ): Int

    @Query(
        "UPDATE local_bug_report_attachments SET state = 'discarded', content = X'', " +
            "lastError = :message WHERE reportLocalId = :reportLocalId " +
            "AND ownerCompanyId = :companyId AND ownerUserId = :userId " +
            "AND state IN ('pending', 'action_required')",
    )
    suspend fun discardAttachmentsForReport(
        reportLocalId: String,
        companyId: String,
        userId: String,
        message: String,
    ): Int

    @Query(
        "UPDATE local_bug_reports SET state = 'discarded', requestJson = '{}', " +
            "title = 'Discarded help request', screen = 'Support', lastError = NULL " +
            "WHERE localId = :localId AND ownerCompanyId = :companyId " +
            "AND ownerUserId = :userId AND state = 'action_required'",
    )
    suspend fun discardActionRequiredReport(
        localId: String,
        companyId: String,
        userId: String,
    ): Int

    /**
     * Explicit review action. If the report itself was refused, erase its
     * free-form body and any image. If only its image was refused, preserve
     * the already-delivered server report and discard only the local image.
     */
    @Transaction
    suspend fun discardAfterReview(
        localId: String,
        companyId: String,
        userId: String,
    ): Boolean {
        val report = reportForOwner(localId, companyId, userId) ?: return false
        val attachments = discardAttachmentsForReport(
            reportLocalId = localId,
            companyId = companyId,
            userId = userId,
            message = "Image removed from this tablet after staff review.",
        )
        val discardedReport = if (report.state == BugReportOutboxState.ACTION_REQUIRED) {
            discardActionRequiredReport(localId, companyId, userId)
        } else {
            0
        }
        return attachments > 0 || discardedReport > 0
    }

    /** Erase screenshot bytes after 30 days without weakening report retry. */
    @Query(
        "UPDATE local_bug_report_attachments SET state = 'discarded', content = X'', " +
            "lastError = :message WHERE createdAtMillis < :cutoffMillis " +
            "AND state IN ('pending', 'action_required')",
    )
    suspend fun expireAttachmentContent(
        cutoffMillis: Long,
        message: String,
    ): Int
}

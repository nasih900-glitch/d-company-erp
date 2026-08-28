package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A help request is captured before any network call. [localId] is also the
 * server idempotency key, so a process death or an ambiguous timeout can only
 * ever create one report.
 *
 * Owner columns are deliberately local-only. A report written by one employee
 * must never be replayed with the next employee's bearer token on a shared
 * tablet. The worker sends rows only after the live `/me` identity matches
 * both values.
 */
@Entity(
    tableName = "local_bug_reports",
    indices = [
        Index(value = ["ownerCompanyId", "ownerUserId", "state"]),
        Index(value = ["createdAtMillis"]),
        // The composite key lets the attachment FK prove that the parent and
        // child belong to the same company and employee, not merely that a
        // report with the supplied local id happens to exist.
        Index(value = ["localId", "ownerCompanyId", "ownerUserId"], unique = true),
    ],
)
data class LocalBugReportEntity(
    @PrimaryKey val localId: String,
    val ownerCompanyId: String,
    val ownerUserId: String,
    /** Stable, already-sanitised JSON body captured at the time of the issue. */
    val requestJson: String,
    /** Small display-only snapshots; the UI never has to parse arbitrary JSON. */
    val title: String,
    val screen: String,
    val createdAtMillis: Long,
    val state: String = BugReportOutboxState.PENDING,
    val serverId: String? = null,
    val serverStatus: String? = null,
    val serverCreatedAt: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "local_bug_report_attachments",
    foreignKeys = [
        ForeignKey(
            entity = LocalBugReportEntity::class,
            parentColumns = ["localId", "ownerCompanyId", "ownerUserId"],
            childColumns = ["reportLocalId", "ownerCompanyId", "ownerUserId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["reportLocalId"]),
        Index(value = ["ownerCompanyId", "ownerUserId", "state"]),
        Index(value = ["reportLocalId", "ownerCompanyId", "ownerUserId"]),
    ],
)
data class LocalBugReportAttachmentEntity(
    @PrimaryKey val localId: String,
    val reportLocalId: String,
    val ownerCompanyId: String,
    val ownerUserId: String,
    val filename: String,
    val contentType: String,
    /** Sanitised, metadata-free image bytes; never an arbitrary device file. */
    val content: ByteArray,
    val byteSize: Int,
    val createdAtMillis: Long,
    val state: String = BugReportOutboxState.PENDING,
    val serverAttachmentId: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
    val lastError: String? = null,
)

object BugReportOutboxState {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val ACTION_REQUIRED = "action_required"
    const val DISCARDED = "discarded"
}

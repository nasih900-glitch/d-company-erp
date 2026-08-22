package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The staff list, wholesale-replaced on every pull — same shape as
 * [CustomerCacheEntity]. "Staff" has no dedicated backend table: these are
 * plain `User` rows (see backend/app/models/user.py) — there is no pay-rate,
 * hire-date, or staff-profile field anywhere in the schema, so none is
 * modelled here either.
 */
@Entity(tableName = "staff_cache")
data class StaffCacheEntity(
    @PrimaryKey val id: String,
    val email: String,
    val name: String,
    val phone: String?,
    val status: String,
    /** Comma-joined role codes (`UserRead.roles`) — never contains a comma itself, so no real converter is needed. */
    val rolesCsv: String,
    val lastLoginAt: String?,
)

object StaffWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * One row per in-flight staff write — edit and delete only, never create.
 *
 * Unlike [LocalCustomerEntity], `serverId` is never null here: creating a
 * login is a two-phase, human-in-the-loop OTP round trip
 * (`POST /auth/register/request` sends a code to the company's security
 * mailbox, `POST /auth/register/confirm` redeems it) that cannot be queued —
 * there is nothing to retry offline, since the whole point of the second
 * phase is a code that has to be read from an email and typed in while
 * connected. `POST /staff/users` (a plain create) is dead code server-side
 * for exactly this reason (see backend/app/api/v1/staff/router.py
 * create_user) — it always refuses. So StaffViewModel.createLogin() talks to
 * the API directly, online-only, the same way item pricing and item creation
 * do in Menu (see MenuWriteEntities.kt) and for an analogous reason: the
 * write fundamentally cannot happen without a live round trip, not just "is
 * gated behind one."
 *
 * `pendingDelete` folds a soft-delete into the same row shape as an edit
 * rather than adding a second outbox table: at most one write is ever queued
 * per staff member (same "amend, don't stack" invariant as Customers), and a
 * delete is really just a terminal edit — SyncEngine.pushStaffOne checks this
 * flag first and calls DELETE instead of PATCH when it's set, ignoring
 * whatever field edits happen to also be sitting on the row (a delete
 * supersedes them).
 *
 * A deleted-then-retried row needs one more piece of care a plain PATCH edit
 * doesn't: `DELETE /staff/users/{id}` 404s on an already-deleted user (see
 * backend/app/api/v1/staff/router.py delete_user — it checks `u.deleted_at`
 * and raises NotFoundError, it does not silently no-op). If the first DELETE
 * actually succeeded but the connection dropped before the 204 arrived, a
 * naive retry would see that 404 and mark this row rejected — "could not
 * delete" for a delete that, in fact, already happened. SyncEngine.
 * pushStaffOne treats a 404 specifically on the delete path as success, not
 * a failure, for exactly this reason.
 */
@Entity(tableName = "local_staff", indices = [Index("state"), Index("serverId")])
data class LocalStaffEntity(
    @PrimaryKey val localId: String,
    val serverId: String,
    val name: String? = null,
    val phone: String? = null,
    val status: String? = null,
    val roleCode: String? = null,
    val pendingDelete: Boolean = false,
    val createdAtMillis: Long,
    val state: String = StaffWriteState.PENDING,
    val lastError: String? = null,
    val version: Long = 0,
)

/**
 * Who's currently clocked in, company- (or branch-, per the backend's own
 * scoping) wide — a plain wholesale-replaced read cache, no outbox. Clock-in
 * and clock-out are never queued offline: `ClockInRequest` carries no
 * `user_id` at all, the acting user comes solely from the JWT
 * (backend/app/api/v1/staff/router.py:320), and holding a JWT already
 * requires a completed login against an already-committed `User` row — so
 * "clock in a not-yet-synced staff member" cannot arise, not even as an edge
 * case to sidestep. Matching the web app's own choice (StaffScreen.tsx's
 * AttendanceWidget calls the API directly, no queue), clock-in/out here are
 * plain synchronous calls too — see StaffViewModel.clockIn/clockOut.
 */
@Entity(tableName = "on_shift_cache")
data class OnShiftEntity(
    @PrimaryKey val attendanceId: String,
    val userId: String,
    val userName: String?,
    val userEmail: String?,
    val branchId: String,
    val branchName: String?,
    val clockInAt: String,
)

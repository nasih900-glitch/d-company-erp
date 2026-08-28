package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Settings — lowest-frequency screen in this rebuild, scoped accordingly.
 * Company profile edits and new Branch/Terminal creation are real offline
 * outbox writes (the plausible "no signal at the back office" cases);
 * editing an existing branch/terminal, deleting a terminal, and the
 * Account tab's password-change (an inherently live OTP round-trip) all
 * stay online-only — rare, deliberate admin actions typically done once at
 * setup. See SettingsApi's class doc for the full reasoning.
 */

@Entity(tableName = "company_cache")
data class CompanyCacheEntity(
    @PrimaryKey val id: String,
    val name: String,
    val legalName: String?,
    val currency: String,
    val timezone: String,
    val country: String?,
    val gstin: String?,
    val pan: String?,
    val gstRegistrationType: String,
    val isComposition: Boolean,
    val eInvoicingEnabled: Boolean,
    val fiscalYearStartMonth: Int,
    val upiVpa: String?,
)

object SettingsWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/** At most one un-synced row ever exists — a fresh edit before the last one
 * synced replaces it rather than queueing a second (see SettingsDao). */
@Entity(tableName = "local_company_edits")
data class LocalCompanyEditEntity(
    @PrimaryKey val localId: String,
    val name: String,
    val legalName: String?,
    val timezone: String,
    val gstin: String?,
    val pan: String?,
    val gstRegistrationType: String,
    val isComposition: Boolean,
    val eInvoicingEnabled: Boolean,
    val upiVpa: String,
    val createdAtMillis: Long,
    val syncState: String = SettingsWriteState.PENDING,
    val lastError: String? = null,
)

@Entity(tableName = "branch_cache")
data class BranchCacheEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String?,
    /** Null only for a cache row migrated before the first v34 server refresh. */
    val invoiceSeriesCode: String? = null,
    val address: String?,
    val timezone: String?,
    val opensAt: String?,
    val closesAt: String?,
    val stateCode: String?,
    val fssaiLicenseNo: String?,
    val tradeLicenseNo: String?,
    val branchGstin: String?,
)

/** Shape D — create-only, never edited offline. Editing an existing branch
 * stays online-only (see class doc). */
@Entity(tableName = "local_branches")
data class LocalBranchEntity(
    @PrimaryKey val localId: String,
    val name: String,
    val code: String?,
    /** Null only for a branch create queued by a pre-v34 client. */
    val invoiceSeriesCode: String? = null,
    val address: String?,
    val timezone: String?,
    val opensAt: String?,
    val closesAt: String?,
    val stateCode: String?,
    val fssaiLicenseNo: String?,
    val tradeLicenseNo: String?,
    val branchGstin: String?,
    val createdAtMillis: Long,
    val syncState: String = SettingsWriteState.PENDING,
    val lastError: String? = null,
)

@Entity(tableName = "terminal_cache", indices = [Index("branchId")])
data class TerminalCacheEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val name: String,
    val deviceId: String?,
    val lastSeenAt: String?,
)

/** Shape D — create-only. branchId must reference an already-synced branch
 * from branch_cache (dependency-sidestep, same pattern as Memberships'
 * customer_cache picker) — a terminal can't be registered under a branch
 * that hasn't synced yet, since the backend needs a real branch id. */
@Entity(tableName = "local_terminals")
data class LocalTerminalEntity(
    @PrimaryKey val localId: String,
    val branchId: String,
    val name: String,
    val deviceId: String?,
    val createdAtMillis: Long,
    val syncState: String = SettingsWriteState.PENDING,
    val lastError: String? = null,
)

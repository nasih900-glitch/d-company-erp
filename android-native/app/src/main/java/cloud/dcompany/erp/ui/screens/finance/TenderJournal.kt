package cloud.dcompany.erp.ui.screens.finance

import android.content.Context
import cloud.dcompany.erp.core.net.MeResponse
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No passwords, bearer tokens or purpose grants belong in this durable journal. */
@Serializable
internal data class TenderScope(val companyId: String, val ownerId: String, val branchId: String) {
    fun validate() {
        listOf(companyId, ownerId, branchId).forEach {
            require(UUID.fromString(it).toString() == it) { "Correction scope identity is not canonical." }
        }
    }
}

internal fun MeResponse.tenderScope(branch: String): TenderScope? =
    if (auditAccess && effectivePermissions?.contains("finance.tender.correct") == true &&
        (branchId == null || branchId == branch)
    ) runCatching { TenderScope(companyId, userId, branch).also { it.validate() } }.getOrNull()
    else null

@Serializable
internal enum class TenderKind { PAYMENT, DAILY }

@Serializable
internal data class PendingTenderCommand(
    val schemaVersion: Int = 1,
    val scope: TenderScope,
    val kind: TenderKind,
    val idempotencyKey: String,
    val bodyJson: String,
    val preparedAtMillis: Long,
) {
    fun validate() {
        require(schemaVersion == 1) { "Unknown saved correction format. Keep its data for recovery." }
        scope.validate()
        require(idempotencyKey.length in 1..160 && idempotencyKey.all { it.code in 33..126 })
        require(preparedAtMillis > 0 && bodyJson.length <= 8_192)
        when (kind) {
            TenderKind.PAYMENT -> journalJson.decodeFromString<PaymentTenderCommit>(bodyJson).also {
                require(UUID.fromString(it.paymentId).toString() == it.paymentId)
                require(journalJson.encodeToString(it) == bodyJson) { "Saved request is not canonical. Keep it for recovery." }
                validateSource(it.sourceFingerprint, it.reason, it.evidenceReference)
            }
            TenderKind.DAILY -> journalJson.decodeFromString<DailyTenderCommit>(bodyJson).also {
                require(it.branchId == scope.branchId) { "Saved correction belongs to another branch." }
                require(LocalDate.parse(it.businessDate).toString() == it.businessDate)
                require(journalJson.encodeToString(it) == bodyJson) { "Saved request is not canonical. Keep it for recovery." }
                require(it.actualCashMinor in 0..9_000_000_000_000_000L)
                require(it.actualUpiMinor in 0..9_000_000_000_000_000L)
                validateSource(it.sourceFingerprint, it.reason, it.evidenceReference)
            }
        }
    }

    fun matches(receipt: TenderReceipt): Boolean {
        if (receipt.companyId != scope.companyId || receipt.branchId != scope.branchId ||
            receipt.recordedBy != scope.ownerId || receipt.idempotencyKey != idempotencyKey
        ) return false
        return when (kind) {
            TenderKind.PAYMENT -> journalJson.decodeFromString<PaymentTenderCommit>(bodyJson).let {
                receipt.operation == "payment_method_corrected" && receipt.financialEffect &&
                    receipt.totalDifferenceMinor == 0L && receipt.sourceAllocation == "identified_payment" &&
                    receipt.kind == null && (receipt.amountMinor ?: 0L) > 0L &&
                    receipt.originalMethod == "cash" && receipt.effectiveMethod == "upi" &&
                    receipt.paymentId == it.paymentId && receipt.sourceFingerprint == it.sourceFingerprint &&
                    receipt.reason == it.reason.trim() && receipt.evidenceReference == it.evidenceReference
            }
            TenderKind.DAILY -> journalJson.decodeFromString<DailyTenderCommit>(bodyJson).let {
                receipt.dailyVariantIsConsistent() &&
                    receipt.businessDate == it.businessDate && receipt.sourceFingerprint == it.sourceFingerprint &&
                    receipt.actualCashMinor == it.actualCashMinor && receipt.actualUpiMinor == it.actualUpiMinor &&
                    receipt.reason == it.reason.trim() && receipt.evidenceReference == it.evidenceReference
            }
        }
    }
}

private fun TenderReceipt.dailyVariantIsConsistent(): Boolean {
    if (sourceAllocation != "unallocated_owner_attestation") return false
    return when (operation) {
        "daily_collections_reconciled" -> kind == "applied" && financialEffect &&
            totalDifferenceMinor == 0L && cashDeltaMinor != null && upiDeltaMinor != null
        "daily_actuals_recorded" -> kind == "unresolved" && !financialEffect &&
            totalDifferenceMinor != 0L && cashDeltaMinor == 0L && upiDeltaMinor == 0L
        else -> false
    }
}

private fun validateSource(fingerprint: String, reason: String, evidence: String?) {
    require(fingerprint.matches(Regex("[a-f0-9]{64}")))
    require(reason.trim().length in 3..500)
    require(evidence == null || evidence.length <= 200)
}

internal val journalJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }

internal interface TenderJournalStorage {
    /** Implementations distinguish absent data from a read error. */
    fun read(key: String): String?
    fun put(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

/** Resolve the app-private directory only when a journal IO operation runs. */
internal fun privateTenderJournalStorage(context: Context): TenderJournalStorage {
    val app = context.applicationContext
    return FileTenderJournalStorage(directory = { app.filesDir.toPath().resolve("tender-journal-v1") })
}

/**
 * One exact command per owner/company/branch. Instances share a mutex; no worker
 * drains it. Corrupt/read-failed data is an error, never an empty slot. Scope
 * changes never clear uncertain work belonging to another scope.
 */
internal class TenderJournal(private val storage: TenderJournalStorage) {
    suspend fun read(scope: TenderScope): PendingTenderCommand? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked(scope) }
    }

    suspend fun reserve(command: PendingTenderCommand): PendingTenderCommand = withContext(Dispatchers.IO) {
        mutex.withLock {
            command.validate()
            val existing = readLocked(command.scope)
            check(existing == null || existing == command) {
                "A different saved correction needs recovery before starting another."
            }
            // commit() may update the in-memory preferences map and still fail
            // to persist. Equal cached data is not proof of a durable prior save.
            val raw = journalJson.encodeToString(command)
            check(storage.put(key(command.scope), raw)) { "Could not save correction safely. Nothing was sent." }
            check(storage.read(key(command.scope)) == raw) { "Correction save could not be verified. Nothing was sent." }
            command
        }
    }

    suspend fun clearExact(command: PendingTenderCommand): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            command.validate()
            val current = readLocked(command.scope) ?: return@withLock false
            if (current != command) return@withLock false
            check(storage.remove(key(command.scope))) { "Result received; saved recovery could not be cleared." }
            check(storage.read(key(command.scope)) == null) { "Correction recovery clear could not be verified." }
            true
        }
    }

    private fun readLocked(scope: TenderScope): PendingTenderCommand? {
        scope.validate()
        val raw = storage.read(key(scope)) ?: return null
        require(raw.length <= 16_384) { "Saved correction is unreadable. Keep app storage for recovery." }
        return journalJson.decodeFromString<PendingTenderCommand>(raw).also {
            it.validate()
            require(it.scope == scope) { "Saved correction scope does not match. Keep it for recovery." }
        }
    }

    private fun key(scope: TenderScope): String = "pending:" + MessageDigest.getInstance("SHA-256")
        .digest(journalJson.encodeToString(scope).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object { val mutex = Mutex() }
}

internal fun paymentTenderCommand(
    scope: TenderScope,
    body: PaymentTenderCommit,
    key: String = "tender:${UUID.randomUUID()}",
    nowMillis: Long = System.currentTimeMillis(),
) = PendingTenderCommand(scope = scope, kind = TenderKind.PAYMENT, idempotencyKey = key,
    bodyJson = journalJson.encodeToString(body), preparedAtMillis = nowMillis).also { it.validate() }

internal fun dailyTenderCommand(
    scope: TenderScope,
    body: DailyTenderCommit,
    key: String = "tender:${UUID.randomUUID()}",
    nowMillis: Long = System.currentTimeMillis(),
) = PendingTenderCommand(scope = scope, kind = TenderKind.DAILY, idempotencyKey = key,
    bodyJson = journalJson.encodeToString(body), preparedAtMillis = nowMillis).also { it.validate() }

/** Grant stays in memory. An HTTP error never silently retires this journal. */
internal suspend fun executeTenderCommand(
    api: FinanceApi,
    token: String,
    command: PendingTenderCommand,
): TenderReceipt {
    command.validate()
    val receipt = when (command.kind) {
        TenderKind.PAYMENT -> api.commitPaymentTender(token, command.idempotencyKey,
            journalJson.decodeFromString(command.bodyJson))
        TenderKind.DAILY -> api.commitDailyTender(token, command.idempotencyKey,
            journalJson.decodeFromString(command.bodyJson))
    }
    check(command.matches(receipt)) { "Returned receipt did not match the saved action. Keep it for recovery." }
    return receipt
}

/** Short-lived authority is deliberately separate from the serializable intent. */
internal data class TenderGrant(
    val scope: TenderScope,
    val kind: TenderKind,
    val token: String,
    val historyToken: String,
    val expiresAtMillis: Long,
) {
    fun allows(command: PendingTenderCommand, nowMillis: Long): Boolean =
        token.isNotBlank() && historyToken.isNotBlank() && scope == command.scope &&
            kind == command.kind && nowMillis < expiresAtMillis
}

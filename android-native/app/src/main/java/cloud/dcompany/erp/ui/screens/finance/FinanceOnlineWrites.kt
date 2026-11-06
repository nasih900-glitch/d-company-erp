package cloud.dcompany.erp.ui.screens.finance

import android.content.Context
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.handover.OperationDispatchGate
import cloud.dcompany.erp.core.handover.OperationTaggedCallFactory
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import cloud.dcompany.erp.core.errors.RecoveryRisk
import cloud.dcompany.erp.core.errors.recoveryGuidance
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.asRupees
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Manual collections and tip payouts are live accounting operations, not an
 * offline outbox. This checkpoint stores one exact request before it is sent
 * so a lost response or process restart can only retry the same server
 * idempotency key/body (or the same naturally-idempotent void), never invent a
 * second financial row.
 */
@Serializable
internal data class FinanceWriteScope(
    val userId: String,
    val companyId: String,
    val branchId: String? = null,
)

internal fun MeResponse.financeWriteScope(): FinanceWriteScope? {
    val user = userId.trim()
    val company = companyId.trim()
    if (user.isEmpty() || company.isEmpty()) return null
    return FinanceWriteScope(
        userId = user,
        companyId = company,
        branchId = branchId?.trim()?.takeIf(String::isNotEmpty),
    )
}

@Serializable
internal enum class FinanceOnlineWriteKind {
    @SerialName("manual_collection_create") MANUAL_COLLECTION_CREATE,
    @SerialName("manual_collection_void") MANUAL_COLLECTION_VOID,
    @SerialName("tip_payout_create") TIP_PAYOUT_CREATE,
    @SerialName("tip_payout_void") TIP_PAYOUT_VOID,
}

@Serializable
internal data class PendingFinanceOnlineWrite(
    val scope: FinanceWriteScope,
    val kind: FinanceOnlineWriteKind,
    val bodyJson: String,
    val idempotencyKey: String? = null,
    val recordId: String? = null,
    val preparedAtMillis: Long,
) {
    fun summary(): String = when (kind) {
        FinanceOnlineWriteKind.MANUAL_COLLECTION_CREATE ->
            runCatching { ApiClient.json.decodeFromString<ManualCollectionCreate>(bodyJson) }
                .fold(
                    onSuccess = {
                        "Manual collection ${it.amountMinor.asRupees()} · " +
                            "${paidViaLabel(it.method)} · ${it.businessDate.asDay()}"
                    },
                    onFailure = { "Manual collection" },
                )
        FinanceOnlineWriteKind.MANUAL_COLLECTION_VOID -> "Void manual collection"
        FinanceOnlineWriteKind.TIP_PAYOUT_CREATE ->
            runCatching { ApiClient.json.decodeFromString<TipPayoutCreate>(bodyJson) }
                .fold(
                    onSuccess = {
                        "Tip payout ${it.amountMinor.asRupees()} · ${paidViaLabel(it.method)}"
                    },
                    onFailure = { "Tip payout" },
                )
        FinanceOnlineWriteKind.TIP_PAYOUT_VOID -> "Void tip payout"
    }
}

internal fun pendingManualCollectionCreate(
    scope: FinanceWriteScope,
    body: ManualCollectionCreate,
    idempotencyKey: String = "manual-collection:${UUID.randomUUID()}",
    nowMillis: Long = System.currentTimeMillis(),
): PendingFinanceOnlineWrite = PendingFinanceOnlineWrite(
    scope = scope,
    kind = FinanceOnlineWriteKind.MANUAL_COLLECTION_CREATE,
    bodyJson = ApiClient.json.encodeToString(body),
    idempotencyKey = idempotencyKey,
    preparedAtMillis = nowMillis,
)

internal fun pendingManualCollectionVoid(
    scope: FinanceWriteScope,
    collectionId: String,
    reason: String,
    nowMillis: Long = System.currentTimeMillis(),
): PendingFinanceOnlineWrite = PendingFinanceOnlineWrite(
    scope = scope,
    kind = FinanceOnlineWriteKind.MANUAL_COLLECTION_VOID,
    bodyJson = ApiClient.json.encodeToString(FinanceVoidRequest(reason)),
    recordId = collectionId,
    preparedAtMillis = nowMillis,
)

internal fun pendingTipPayoutCreate(
    scope: FinanceWriteScope,
    body: TipPayoutCreate,
    idempotencyKey: String = "tip-payout:${UUID.randomUUID()}",
    nowMillis: Long = System.currentTimeMillis(),
): PendingFinanceOnlineWrite = PendingFinanceOnlineWrite(
    scope = scope,
    kind = FinanceOnlineWriteKind.TIP_PAYOUT_CREATE,
    bodyJson = ApiClient.json.encodeToString(body),
    idempotencyKey = idempotencyKey,
    preparedAtMillis = nowMillis,
)

internal fun pendingTipPayoutVoid(
    scope: FinanceWriteScope,
    payoutId: String,
    reason: String,
    nowMillis: Long = System.currentTimeMillis(),
): PendingFinanceOnlineWrite = PendingFinanceOnlineWrite(
    scope = scope,
    kind = FinanceOnlineWriteKind.TIP_PAYOUT_VOID,
    bodyJson = ApiClient.json.encodeToString(FinanceVoidRequest(reason)),
    recordId = payoutId,
    preparedAtMillis = nowMillis,
)

internal interface FinanceWriteRecoveryStore {
    fun load(scope: FinanceWriteScope): PendingFinanceOnlineWrite?
    fun save(write: PendingFinanceOnlineWrite): Boolean
    fun clear(write: PendingFinanceOnlineWrite): Boolean
}

internal const val FINANCE_RECOVERY_BLOCKED_MESSAGE = "Pending financial work needs recovery. Its saved evidence has been retained; do not enter a replacement request."
/** No parser cause or other account's private command escapes this storage boundary. */
internal class FinanceRecoveryUnavailable : java.io.IOException(FINANCE_RECOVERY_BLOCKED_MESSAGE)

internal class SharedPreferencesFinanceWriteRecoveryStore(context: Context) :
    FinanceWriteRecoveryStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun load(scope: FinanceWriteScope): PendingFinanceOnlineWrite? = synchronized(lock) {
        try {
            val raw = preferences.getString(key(scope), null) ?: return@synchronized null
            ApiClient.json.decodeFromString<PendingFinanceOnlineWrite>(raw).also {
                if (it.scope != scope) throw FinanceRecoveryUnavailable()
            }
        } catch (_: Exception) { throw FinanceRecoveryUnavailable() }
    }

    override fun save(write: PendingFinanceOnlineWrite): Boolean = synchronized(lock) {
        try {
            val existing = load(write.scope)
            val raw = ApiClient.json.encodeToString(write)
            if (existing != null && (existing != write || preferences.getString(key(write.scope), null) != raw)) return@synchronized false
            preferences.edit().putString(key(write.scope), raw).commit()
        } catch (_: Exception) { false }
    }

    override fun clear(write: PendingFinanceOnlineWrite): Boolean = synchronized(lock) {
        try {
            val key = key(write.scope)
            val current = preferences.getString(key, null)
            val expected = ApiClient.json.encodeToString(write)
            if (current != expected) return@synchronized false
            preferences.edit().remove(key).commit()
        } catch (_: Exception) { false }
    }

    /** Presence only, across scopes; malformed retained bytes can never become a clean result. */
    fun hasSavedWork(): Boolean = synchronized(lock) {
        try { preferences.all.keys.any { it.startsWith("pending:") } }
        catch (_: Exception) { throw FinanceRecoveryUnavailable() }
    }

    private fun key(scope: FinanceWriteScope): String = buildString {
        append("pending:")
        append(scope.userId)
        append(':')
        append(scope.companyId)
        append(':')
        append(scope.branchId.orEmpty())
    }

    private companion object {
        val lock = Any()
        const val PREFERENCES = "dcompany_finance_online_write_recovery_v1"
    }
}

internal sealed interface FinanceOnlineWriteResult {
    data class ManualCollectionResult(val row: ManualCollection) : FinanceOnlineWriteResult
    data class TipPayoutResult(val row: TipPayout) : FinanceOnlineWriteResult
}

internal interface FinanceOnlineWriteExecutor {
    suspend fun execute(write: PendingFinanceOnlineWrite): FinanceOnlineWriteResult
}

/** Confirmed accounting writes have one physical attempt; only the saved request may be retried explicitly. */
internal fun singleAttemptFinanceWriteExecutor(
    accessToken: String,
    terminalId: String?,
    beforeDispatch: () -> Unit,
    baseUrl: String = BuildConfig.API_BASE_URL,
    operationGate: OperationDispatchGate? = null,
): FinanceOnlineWriteExecutor {
    // An omitted test override uses the mandatory production startup/original-operation gate.
    val client = if (operationGate == null) {
        ApiClient.ephemeralAuthorityClient(accessToken, terminalId, beforeDispatch, singleAttempt = true)
    } else {
        ApiClient.ephemeralAuthorityClient(accessToken, terminalId, beforeDispatch, singleAttempt = true, operationGate = operationGate)
    }
    val api = Retrofit.Builder().baseUrl(baseUrl)
        .callFactory(OperationTaggedCallFactory(client))
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build().create(FinanceApi::class.java)
    return RetrofitFinanceOnlineWriteExecutor(api)
}

internal class RetrofitFinanceOnlineWriteExecutor(
    private val api: FinanceApi,
) : FinanceOnlineWriteExecutor {
    override suspend fun execute(write: PendingFinanceOnlineWrite): FinanceOnlineWriteResult =
        when (write.kind) {
            FinanceOnlineWriteKind.MANUAL_COLLECTION_CREATE -> {
                val body = ApiClient.json.decodeFromString<ManualCollectionCreate>(write.bodyJson)
                val key = requireNotNull(write.idempotencyKey) {
                    "Manual collection recovery is missing its idempotency key"
                }
                FinanceOnlineWriteResult.ManualCollectionResult(
                    api.createManualCollection(body, key),
                )
            }
            FinanceOnlineWriteKind.MANUAL_COLLECTION_VOID -> {
                val body = ApiClient.json.decodeFromString<FinanceVoidRequest>(write.bodyJson)
                FinanceOnlineWriteResult.ManualCollectionResult(
                    api.voidManualCollection(requireNotNull(write.recordId), body),
                )
            }
            FinanceOnlineWriteKind.TIP_PAYOUT_CREATE -> {
                val body = ApiClient.json.decodeFromString<TipPayoutCreate>(write.bodyJson)
                val key = requireNotNull(write.idempotencyKey) {
                    "Tip payout recovery is missing its idempotency key"
                }
                FinanceOnlineWriteResult.TipPayoutResult(api.createTipPayout(body, key))
            }
            FinanceOnlineWriteKind.TIP_PAYOUT_VOID -> {
                val body = ApiClient.json.decodeFromString<FinanceVoidRequest>(write.bodyJson)
                FinanceOnlineWriteResult.TipPayoutResult(
                    api.voidTipPayout(requireNotNull(write.recordId), body),
                )
            }
        }
}

internal fun preserveFinanceWriteForRetry(error: Throwable, retainedBeforeAttempt: Boolean = false): Boolean =
    retainedBeforeAttempt || error !is ApiException || error.mustPreserveOutbox

internal fun financeWriteFailureMessage(error: Throwable, preserved: Boolean): String {
    if (preserved) {
        return "The server result could not be confirmed. Do not enter or pay this again. " +
            "Reconnect, then retry the exact saved request below."
    }
    return recoveryGuidance(
        risk = RecoveryRisk.FINANCIAL_WRITE,
        failure = error,
        subject = "this Finance entry",
    ).message
}

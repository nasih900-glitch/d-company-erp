package cloud.dcompany.erp.ui.screens.finance

import android.content.Context
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

internal class SharedPreferencesFinanceWriteRecoveryStore(context: Context) :
    FinanceWriteRecoveryStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun load(scope: FinanceWriteScope): PendingFinanceOnlineWrite? {
        val key = key(scope)
        val raw = preferences.getString(key, null) ?: return null
        val restored = runCatching {
            ApiClient.json.decodeFromString<PendingFinanceOnlineWrite>(raw)
        }.getOrNull()
        if (restored?.scope == scope) return restored
        preferences.edit().remove(key).commit()
        return null
    }

    override fun save(write: PendingFinanceOnlineWrite): Boolean = runCatching {
        preferences.edit()
            .putString(key(write.scope), ApiClient.json.encodeToString(write))
            .commit()
    }.getOrDefault(false)

    override fun clear(write: PendingFinanceOnlineWrite): Boolean {
        val key = key(write.scope)
        val current = preferences.getString(key, null)
        val expected = runCatching { ApiClient.json.encodeToString(write) }.getOrNull()
            ?: return false
        if (current != expected) return false
        return preferences.edit().remove(key).commit()
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

internal class RetrofitFinanceOnlineWriteExecutor(
    private val api: FinanceApi = ApiClient.create(),
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

internal fun preserveFinanceWriteForRetry(error: Throwable): Boolean =
    error !is ApiException || error.mustPreserveOutbox

internal fun financeWriteFailureMessage(error: Throwable, preserved: Boolean): String {
    if (preserved) {
        return "The server result could not be confirmed. Do not enter or pay this again. " +
            "Reconnect, then retry the exact saved request below."
    }
    return (error as? ApiException)?.message?.takeIf(String::isNotBlank)
        ?: "The server rejected this entry. Nothing was queued offline; check the details and try again."
}

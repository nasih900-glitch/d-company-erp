package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.net.ReceiptMistakeRequest
import cloud.dcompany.erp.ui.screens.finance.FileTenderJournalStorage
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ReceiptMistakeAttempt(val key: String, val body: ReceiptMistakeRequest)

@Serializable
internal data class ReceiptMistakeRecord(
    val attempt: ReceiptMistakeAttempt? = null,
    val acknowledgedKey: String? = null,
)

internal fun receiptMistakeJournalKey(server: String, scope: CacheScope, orderId: String): String {
    val fields = listOf(server, scope.companyId, requireNotNull(scope.branchId), scope.userId, orderId)
    val digest = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(fields).encodeToByteArray())
    return "pending:" + digest.joinToString("") { "%02x".format(it) }
}

internal data class PreparedReceiptMistake(val attempt: ReceiptMistakeAttempt, val newlySaved: Boolean)

internal class ReceiptMistakeJournal(private val storage: FileTenderJournalStorage) {
    private companion object {
        val fileLock = Any()
        val activeDispatches = ConcurrentHashMap.newKeySet<String>()
    }

    suspend fun read(key: String): ReceiptMistakeRecord? = onDisk { readStored(key) }

    suspend fun prepare(
        key: String,
        draft: ReceiptMistakeRequest?,
        acknowledgedKey: String? = null,
    ): PreparedReceiptMistake? = onDisk {
        val record = readStored(key)
        val saved = record?.attempt
        if (saved == null && record?.acknowledgedKey != acknowledgedKey) return@onDisk null
        val attempt = saved ?: draft?.let { ReceiptMistakeAttempt(UUID.randomUUID().toString(), it) }
            ?: return@onDisk null
        if (saved == null) write(key, ReceiptMistakeRecord(attempt = attempt))
        else storage.syncRetained(key)
        PreparedReceiptMistake(attempt, newlySaved = saved == null)
    }

    suspend fun finish(key: String, expected: ReceiptMistakeAttempt, acknowledgedKey: String?) = onDisk {
        check(readStored(key)?.attempt == expected)
        if (acknowledgedKey == null) check(storage.remove(key))
        else write(key, ReceiptMistakeRecord(acknowledgedKey = acknowledgedKey))
    }

    suspend fun dispatch(key: String, block: suspend () -> Unit): Boolean {
        if (!activeDispatches.add(key)) return false
        try {
            block()
            return true
        } finally {
            activeDispatches.remove(key)
        }
    }

    private fun write(key: String, record: ReceiptMistakeRecord) {
        check(storage.put(key, Json.encodeToString(record)))
    }

    private fun readStored(key: String): ReceiptMistakeRecord? = storage.readRetained(key) { raw ->
        try {
            decode(raw)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }?.let(::decode)

    private fun decode(raw: String): ReceiptMistakeRecord = Json.decodeFromString<ReceiptMistakeRecord>(raw).also {
        require((it.attempt == null) != (it.acknowledgedKey == null))
        val key = it.attempt?.key ?: requireNotNull(it.acknowledgedKey)
        require(UUID.fromString(key).toString() == key)
    }

    private suspend fun <T> onDisk(block: () -> T): T = withContext(Dispatchers.IO) {
        synchronized(fileLock) { block() }
    }
}

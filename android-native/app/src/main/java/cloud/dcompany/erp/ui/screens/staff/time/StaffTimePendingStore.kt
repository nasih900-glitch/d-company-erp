package cloud.dcompany.erp.ui.screens.staff.time

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class PendingTimeAttempt(val intent: StaffTimeIntent, val rejectedStatus: Int? = null) {
    override fun toString() = "PendingTimeAttempt(intent=<redacted>, rejectedStatus=$rejectedStatus)"
    fun validate() {
        intent.validate()
        require(rejectedStatus == null || rejectedStatus in DEFINITIVE_TIME_REJECTIONS)
    }
}
internal val DEFINITIVE_TIME_REJECTIONS = setOf(400, 401, 403, 404, 422)
internal interface StaffTimePendingStore {
    suspend fun read(scopeKey: String): PendingTimeAttempt?
    suspend fun save(scopeKey: String, attempt: PendingTimeAttempt)
    suspend fun reject(scopeKey: String, key: String, status: Int): PendingTimeAttempt
    suspend fun complete(scopeKey: String, key: String)
    suspend fun acknowledgeRejected(scopeKey: String, key: String)
}

/** One unconfirmed intent per workspace. No background replay, credentials or Room/outbox changes. */
internal class AtomicStaffTimePendingStore(private val directory: File) : StaffTimePendingStore {
    private companion object { val lock = Mutex(); const val MAX_BYTES = 65_536 }
    private fun checkDirectory() {
        val parent = directory.parentFile ?: throw IOException("Staff-time storage is unavailable.")
        require(parent.isDirectory && parent.canonicalFile == parent.absoluteFile)
        if (!directory.exists()) {
            if (!directory.mkdir()) throw IOException("Could not retain the staff-time request.")
            Os.chmod(directory.path, 0x1c0) // 0700
        }
        val stat = Os.lstat(directory.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
            stat.st_mode and 0x3f == 0) { "Staff-time storage is not private." }
    }
    private fun checkFile(file: File) {
        val stat = try { Os.lstat(file.path) } catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return else throw e
        }
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
            stat.st_nlink == 1L && stat.st_mode and 0x3f == 0 && stat.st_size in 0..MAX_BYTES.toLong()) { "Unsafe staff-time request file." }
    }
    private fun file(scopeKey: String): File {
        require(scopeKey.matches(Regex("[0-9a-f]{64}")))
        checkDirectory()
        return File(directory, "$scopeKey.json").also(::checkVariants)
    }
    private fun checkVariants(file: File) {
        checkFile(file); checkFile(File(file.path + ".bak")); checkFile(File(file.path + ".new"))
    }
    private fun readFile(file: File): PendingTimeAttempt? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val raw = AtomicFile(file).openRead().use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val n = input.read(bytes, size, bytes.size - size)
                if (n < 0) break
                size += n
            }
            require(size <= MAX_BYTES)
            bytes.copyOf(size).decodeToString(throwOnInvalidSequence = true)
        }
        return StaffTimeJson.decodeFromString(PendingTimeAttempt.serializer(), raw).also { it.validate() }
    }
    private fun write(file: File, attempt: PendingTimeAttempt) {
        attempt.validate(); checkVariants(file)
        val bytes = StaffTimeJson.encodeToString(attempt).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            Os.fchmod(stream.fd, 0x180) // 0600: attendance, reasons and draft pay stay private.
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            syncDirectory()
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
    private fun syncDirectory() {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    private fun remove(file: File) {
        checkVariants(file)
        AtomicFile(file).delete()
        check(!file.exists() && !File(file.path + ".new").exists() && !File(file.path + ".bak").exists()) {
            "The retained request could not be cleared."
        }
        syncDirectory()
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block() }
    }
    override suspend fun read(scopeKey: String) = locked { readFile(file(scopeKey)) }
    override suspend fun save(scopeKey: String, attempt: PendingTimeAttempt) = locked {
        val f = file(scopeKey)
        val prior = readFile(f)
        check(prior == null || prior == attempt) { "Resolve the retained request before recording another one." }
        write(f, attempt)
    }
    override suspend fun reject(scopeKey: String, key: String, status: Int): PendingTimeAttempt = locked {
        require(status in DEFINITIVE_TIME_REJECTIONS)
        val f = file(scopeKey)
        val prior = requireNotNull(readFile(f))
        require(prior.intent.key == key)
        prior.copy(rejectedStatus = status).also { write(f, it) }
    }
    override suspend fun complete(scopeKey: String, key: String) = locked {
        val f = file(scopeKey)
        val prior = requireNotNull(readFile(f))
        require(prior.intent.key == key)
        remove(f)
    }
    override suspend fun acknowledgeRejected(scopeKey: String, key: String) = locked {
        val f = file(scopeKey)
        val prior = requireNotNull(readFile(f))
        require(prior.intent.key == key && prior.rejectedStatus in DEFINITIVE_TIME_REJECTIONS)
        timeUuid(key)
        val archive = File(directory, "$scopeKey-rejected-$key.json")
        checkVariants(archive)
        val archived = readFile(archive)
        check(archived == null || archived == prior)
        if (archived == null) write(archive, prior)
        remove(f)
    }
}

package cloud.dcompany.erp.ui.screens.shift.work

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

internal interface StartWorkStore {
    suspend fun pending(): List<PendingStartWork>
    suspend fun save(expected: PendingStartWork?, next: PendingStartWork)
    suspend fun archive(expected: PendingStartWork, outcome: StartWorkOutcome, stillCurrent: () -> Boolean)
}
internal data class StartWorkReadiness(val verified: Boolean, val pending: Boolean = false)

/** A separate credential-free journal; never changes the old Room shift outbox. */
internal class AtomicStartWorkStore(private val directory: File) : StartWorkStore {
    companion object { private val files = Mutex(); const val MAX_BYTES = 32_768 }
    private fun directory(create: Boolean): Boolean {
        val parent = requireNotNull(directory.parentFile)
        require(parent.isDirectory && parent.canonicalFile == parent.absoluteFile)
        val present = try { Os.lstat(directory.path); true } catch (missing: android.system.ErrnoException) {
            if (missing.errno == OsConstants.ENOENT) false else throw missing
        }
        if (!present) {
            if (!create) return false
            check(directory.mkdir()); Os.chmod(directory.path, 0x1c0)
            val parentFd = Os.open(parent.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { Os.fsync(parentFd) } finally { Os.close(parentFd) }
        }
        val s = Os.lstat(directory.path)
        require(OsConstants.S_ISDIR(s.st_mode) && s.st_uid == Process.myUid() && s.st_mode and 0x3f == 0)
        require(directory.canonicalFile == directory.absoluteFile)
        return true
    }
    private fun safe(file: File) {
        val s = try { Os.lstat(file.path) } catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return else throw e
        }
        require(OsConstants.S_ISREG(s.st_mode) && s.st_uid == Process.myUid() && s.st_nlink == 1L &&
            s.st_mode and 0x3f == 0 && s.st_size in 1..MAX_BYTES.toLong())
    }
    private fun variants(file: File) {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach(::safe)
    }
    private fun read(file: File): PendingStartWork? {
        variants(file)
        require(!File(file.path + ".new").exists()) { "A partially retained start needs Owner review." }
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = AtomicFile(file).openRead().use { it.readBytesLimited() }
        return StartWorkJson.decodeFromString<PendingStartWork>(bytes.decodeToString(throwOnInvalidSequence = true)).also { it.validate() }
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val buffer = ByteArray(MAX_BYTES + 1); var size = 0
        while (size < buffer.size) { val n = read(buffer, size, buffer.size - size); if (n < 0) break; size += n }
        require(size in 1..MAX_BYTES); return buffer.copyOf(size)
    }
    private fun sync() {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun write(file: File, value: PendingStartWork) {
        value.validate(); variants(file)
        val raw = StartWorkJson.encodeToString(value).toByteArray(); require(raw.size in 1..MAX_BYTES)
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { Os.fchmod(stream.fd, 0x180); stream.write(raw); stream.fd.sync(); atomic.finishWrite(stream); sync() }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    private fun all(): List<PendingStartWork> {
        if (!directory(false)) return emptyList()
        val result = mutableListOf<PendingStartWork>()
        for (file in requireNotNull(directory.listFiles()).sortedBy { it.name }) {
            require(file.name.matches(Regex("[0-9a-f]{64}(?:-resolved-[0-9a-f-]{36})?\\.json"))) { "Unverified saved start-work file." }
            val value = requireNotNull(read(file))
            val base = value.reference.scopeKey
            if (file.name.contains("-resolved-")) {
                require(file.name == "$base-resolved-${value.reference.actionId}.json" && value.phase == "RESOLVED")
            } else {
                require(file.name == "$base.json" && value.phase != "RESOLVED"); result += value
            }
        }
        return result
    }
    override suspend fun pending() = withContext(Dispatchers.IO) { files.withLock { all() } }
    suspend fun readiness(): StartWorkReadiness = try { StartWorkReadiness(true, pending().isNotEmpty()) }
        catch (_: Exception) { StartWorkReadiness(false) }
    override suspend fun save(expected: PendingStartWork?, next: PendingStartWork) = withContext(Dispatchers.IO) { files.withLock {
        directory(true); val retained = all(); if (expected == null) check(retained.isEmpty())
        val f = File(directory, "${next.reference.scopeKey}.json")
        check(read(f) == expected) { "The saved shift start changed. Keep its original request." }
        require(expected == null || expected.reference == next.reference && expected.authBinding == next.authBinding)
        write(f, next)
    } }
    override suspend fun archive(expected: PendingStartWork, outcome: StartWorkOutcome, stillCurrent: () -> Boolean) = withContext(Dispatchers.IO) { files.withLock {
        outcome.validate(expected.reference); require(outcome.state in setOf("committed", "cancelled"))
        directory(false); val f = File(directory, "${expected.reference.scopeKey}.json")
        check(read(f) == expected)
        val archive = File(directory, "${expected.reference.scopeKey}-resolved-${expected.reference.actionId}.json")
        val resolved = expected.copy(phase = "RESOLVED", resolution = outcome)
        val old = read(archive)
        if (old != null) validateArchivedStartWork(expected, old, outcome)
        if (old == null) write(archive, resolved)
        check(stillCurrent()); variants(f); AtomicFile(f).delete()
        check(!f.exists() && !File(f.path + ".bak").exists() && !File(f.path + ".new").exists()); sync()
    } }
}

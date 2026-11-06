package cloud.dcompany.erp.core.handover

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** One tablet-wide fence, outside user caches. Mirrors the existing attendance AtomicFile safety. */
internal class AtomicHandoverIntentStore(private val directory: File) : HandoverIntentStore {
    private companion object { val mutex = Mutex(); const val MAX_BYTES = 16_384 }
    private fun file(): File {
        val parent = directory.parentFile ?: throw IOException("Handover storage unavailable.")
        require(parent.isDirectory && parent.canonicalFile == parent.absoluteFile)
        if (!directory.exists()) {
            check(directory.mkdir()) { "Handover storage could not be created." }
            Os.chmod(directory.path, 0x1c0)
        }
        val s = Os.lstat(directory.path)
        require(OsConstants.S_ISDIR(s.st_mode) && s.st_uid == Process.myUid() && s.st_mode and 0x3f == 0)
        return File(directory, "pending.json").also(::variants)
    }
    private fun checkFile(file: File) {
        val s = try { Os.lstat(file.path) } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return else throw e
        }
        require(OsConstants.S_ISREG(s.st_mode) && s.st_uid == Process.myUid() && s.st_nlink == 1L &&
            s.st_mode and 0x3f == 0 && s.st_size in 0..MAX_BYTES.toLong()) { "Unsafe handover intent file." }
    }
    private fun variants(file: File) { checkFile(file); checkFile(File(file.path + ".new")); checkFile(File(file.path + ".bak")) }
    private fun read(file: File): HandoverIntent? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = AtomicFile(file).openRead().use { input ->
            val result = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < result.size) {
                val n = input.read(result, size, result.size - size)
                if (n < 0) break
                size += n
            }
            require(size <= MAX_BYTES)
            result.copyOf(size)
        }
        return HandoverJson.decodeFromString<HandoverIntent>(bytes.decodeToString(throwOnInvalidSequence = true)).also { it.validate() }
    }
    private fun syncDirectory() {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val s = Os.fstat(fd)
            require(OsConstants.S_ISDIR(s.st_mode) && s.st_uid == Process.myUid())
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    private fun write(file: File, intent: HandoverIntent) {
        intent.validate(); variants(file)
        val bytes = HandoverJson.encodeToString(intent).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            Os.fchmod(stream.fd, 0x180)
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream); syncDirectory()
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    override suspend fun read() = locked { read(file()) }
    override suspend fun begin(intent: HandoverIntent) = locked {
        intent.validate(); require(intent.stage == HandoverStage.NOT_SENT)
        val f = file(); val existing = read(f)
        check(existing == null || existing == intent) { "Another handover remains unresolved." }
        write(f, intent)
    }
    override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) = locked {
        validateHandoverTransition(expected, next)
        val f = file(); check(read(f) == expected) { "The retained handover changed." }
        write(f, next)
    }
    override suspend fun cancelNotSent(expected: HandoverIntent) = locked {
        require(expected.stage == HandoverStage.NOT_SENT && !expected.priorMutationUncertainty)
        removeExact(expected)
    }

    override suspend fun completeAccepted(expected: HandoverIntent) = locked {
        require(expected.stage == HandoverStage.ACCEPTED && !expected.priorMutationUncertainty)
        removeExact(expected)
    }

    private fun removeExact(expected: HandoverIntent) {
        expected.validate()
        val f = file(); check(read(f) == expected)
        AtomicFile(f).delete()
        check(!f.exists() && !File(f.path + ".bak").exists() && !File(f.path + ".new").exists())
        syncDirectory()
    }
}

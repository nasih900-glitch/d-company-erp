package cloud.dcompany.erp.ui.screens.finance

import android.system.Os
import android.system.OsConstants
import java.io.IOException
import kotlinx.serialization.decodeFromString
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/**
 * A bounded private JSON intent file rather than SharedPreferences, whose XML
 * reader silently substitutes an empty map for a corrupt file. All calls run
 * under TenderJournal's shared mutex on IO. Readiness inspection never creates files.
 */
internal class FileTenderJournalStorage(
    private val directory: () -> Path,
    private val syncDirectory: (Path) -> Unit = ::syncTenderDirectory,
    private val moveAtomically: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
    },
) : TenderJournalStorage {
    override fun read(key: String): String? {
        val parent = checkedDirectory(create = false) ?: return null
        val base = path(parent, key)
        val stored = readRegular(base)
        val preparing = readRegular(temporary(base))
        if (stored != null && preparing != null && stored != preparing) {
            throw IOException("Conflicting saved correction files. Keep app storage for recovery.")
        }
        // A complete interrupted preparation retains its identity too. A partial
        // preparation is passed to the strict decoder and never overwritten.
        return stored ?: preparing
    }

    override fun inspectSavedWork(): TenderJournalReadiness {
        val parent = checkedDirectory(create = false) ?: return TenderJournalReadiness(verified = true)
        val keys = linkedSetOf<String>()
        Files.newDirectoryStream(parent).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                require(name.matches(Regex("pending_[a-f0-9]{64}\\.json(\\.preparing)?"))) {
                    "Unrecognised saved correction data. Keep it for owner review."
                }
                keys += name.removeSuffix(".preparing").removeSuffix(".json").replaceFirst("pending_", "pending:")
                require(keys.size <= 128) { "Saved correction inventory cannot be verified." }
            }
        }
        for (key in keys) {
            val raw = requireNotNull(read(key)) { "Saved correction changed while being inspected." }
            val command = journalJson.decodeFromString<PendingTenderCommand>(raw).also { it.validate() }
            require(tenderJournalKey(command.scope) == key) { "Saved correction scope does not match its retained file." }
        }
        return TenderJournalReadiness(reviewCount = keys.size, verified = true)
    }

    override fun put(key: String, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Saved correction exceeds the supported size." }
        val parent = requireNotNull(checkedDirectory(create = true))
        // A prior mkdir may have succeeded while its parent fsync failed.
        // Existing in-memory/path visibility does not prove directory durability.
        syncDirectory(requireNotNull(parent.parent))
        val base = path(parent, key)
        val temporary = temporary(base)
        FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        // Unsupported atomic replacement is a hard failure, never a weaker copy.
        moveAtomically(temporary, base)
        syncDirectory(parent)
        return true
    }

    override fun remove(key: String): Boolean {
        val parent = checkedDirectory(create = false) ?: return true
        val base = path(parent, key)
        Files.deleteIfExists(base)
        Files.deleteIfExists(temporary(base))
        syncDirectory(parent)
        return true
    }

    private fun checkedDirectory(create: Boolean): Path? {
        val parent = directory()
        val attributes = try {
            Files.readAttributes(parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (missing: NoSuchFileException) {
            if (!create) return null
            Files.createDirectory(parent)
            Files.readAttributes(parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        }
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "Unsafe correction journal directory." }
        return parent
    }

    private fun path(parent: Path, key: String): Path {
        require(key.matches(Regex("pending:[a-f0-9]{64}"))) { "Invalid correction journal key." }
        return parent.resolve(key.replace(':', '_') + ".json")
    }

    private fun temporary(base: Path): Path = base.resolveSibling(base.fileName.toString() + ".preparing")

    private fun readRegular(file: Path): String? {
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return null }
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() <= MAX_BYTES) {
            "Saved correction file is unsafe or unreadable. Keep it for recovery."
        }
        return Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
            // InputStream.readNBytes is unavailable on older supported Android.
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Saved correction file is too large. Keep it for recovery." }
                output.write(buffer, 0, count)
            }
            // Reject invalid UTF-8 instead of replacing corrupt bytes with text.
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }
    }

    private companion object { const val MAX_BYTES = 16_384 }
}

private fun syncTenderDirectory(directory: Path) {
    val descriptor = Os.open(directory.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
    try {
        check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Journal parent is not a directory." }
        Os.fsync(descriptor)
    } finally { Os.close(descriptor) }
}

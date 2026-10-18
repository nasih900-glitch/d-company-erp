package cloud.dcompany.erp.ui.screens.finance

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTenderJournalStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = TenderScope("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333")
    private fun command() = paymentTenderCommand(scope,PaymentTenderCommit("44444444-4444-4444-4444-444444444444","a".repeat(64),"Confirmed source"),"retained-file-action",1000)
    private fun folder(): Path = temporary.root.toPath().resolve("journal")
    private fun storage(sync: (Path)->Unit = {}) = FileTenderJournalStorage({folder()},sync)
    private fun onlyFile(): Path = Files.list(folder()).use { it.findFirst().get() }

    @Test fun actualPrivateFileReopenPreservesExactIntentAcrossNewAdapterInstance() = runBlocking {
        assertNull(TenderJournal(storage()).read(scope))
        val command = TenderJournal(storage()).reserve(command())
        assertEquals(command,TenderJournal(storage()).read(scope))
        assertTrue(Files.size(onlyFile()) > 0)
        assertTrue(TenderJournal(storage()).clearExact(command))
        assertNull(TenderJournal(storage()).read(scope))
    }

    @Test fun malformedUnderlyingFileOnRestartIsNeverInterpretedAsMissing() = runBlocking {
        TenderJournal(storage()).reserve(command())
        val path=onlyFile()
        val corrupt="{truncated financial recovery"
        Files.write(path,corrupt.toByteArray())
        val restarted=TenderJournal(storage())
        assertTrue(runCatching { restarted.read(scope) }.isFailure)
        assertTrue(runCatching { restarted.reserve(command().copy(idempotencyKey="new-action")) }.isFailure)
        assertEquals(corrupt,String(Files.readAllBytes(path)))
    }

    @Test fun invalidUtf8AndSymlinkNeverBecomeAnEmptyStore() = runBlocking {
        TenderJournal(storage()).reserve(command())
        val path=onlyFile()
        Files.write(path,byteArrayOf(0xC3.toByte()))
        assertTrue(runCatching { TenderJournal(storage()).read(scope) }.isFailure)
        Files.delete(path)
        val target=temporary.root.toPath().resolve("outside.json")
        Files.write(target,"{}".toByteArray())
        Files.createSymbolicLink(path,target)
        assertTrue(runCatching { TenderJournal(storage()).read(scope) }.isFailure)
        assertEquals("{}",String(Files.readAllBytes(target)))
    }

    @Test fun failedDirectorySyncAfterRenameStillRequiresSuccessfulSyncBeforeSend() = runBlocking {
        TenderJournal(storage()).reserve(command())
        var fail=true
        val journal=TenderJournal(storage { if(fail) throw IOException("fsync failed") })
        assertTrue(runCatching { journal.reserve(command()) }.isFailure)
        assertEquals(command(),TenderJournal(storage()).read(scope))
        assertTrue(runCatching { journal.reserve(command()) }.isFailure)
        fail=false
        assertEquals(command(),journal.reserve(command()))
    }

    @Test fun unsupportedAtomicMoveHasNoCopyFallbackAndRetainsCompletePreparationForExactRecovery() = runBlocking {
        val failing=FileTenderJournalStorage({folder()},{},{from,to ->
            throw AtomicMoveNotSupportedException(from.toString(),to.toString(),"unsupported")
        })
        assertTrue(runCatching { TenderJournal(failing).reserve(command()) }.isFailure)
        assertTrue(onlyFile().fileName.toString().endsWith(".preparing"))
        val restarted=TenderJournal(storage())
        assertEquals(command(),restarted.read(scope))
        assertEquals(command(),restarted.reserve(command()))
        assertTrue(onlyFile().fileName.toString().endsWith(".json"))
    }
}

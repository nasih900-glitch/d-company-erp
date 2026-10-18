package cloud.dcompany.erp.ui.screens.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TenderJournalPersistenceTest {
    @Test fun realAndroidFsyncAtomicRenameReopenAndCorruptFileRefusal() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val parent=Files.createTempDirectory(context.cacheDir.toPath(),"build51-tender-test-")
        val directory=parent.resolve("journal")
        val scope=TenderScope("11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222","33333333-3333-3333-3333-333333333333")
        val command=paymentTenderCommand(scope,PaymentTenderCommit("44444444-4444-4444-4444-444444444444","a".repeat(64),"Synthetic test"),"android-exact",1000)
        try {
            val first=TenderJournal(FileTenderJournalStorage({directory}))
            assertNull(first.read(scope))
            assertEquals(command,first.reserve(command))
            val restarted=TenderJournal(FileTenderJournalStorage({directory}))
            assertEquals(command,restarted.read(scope))
            val file=Files.list(directory).use { it.findFirst().get() }
            Files.write(file,"{truncated".toByteArray())
            assertTrue(runCatching { TenderJournal(FileTenderJournalStorage({directory})).read(scope) }.isFailure)
            assertTrue(runCatching { restarted.reserve(command.copy(idempotencyKey="new-action")) }.isFailure)
            assertEquals("{truncated",String(Files.readAllBytes(file)))
        } finally {
            // Only this test's freshly created unique directory, never the app journal.
            parent.toFile().deleteRecursively()
        }
    }
}

package cloud.dcompany.erp.ui.screens.shift.work

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicStartWorkStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = File(context.noBackupFilesDir.canonicalFile,"start-work-test-${UUID.randomUUID()}").also { check(it.mkdir());Os.chmod(it.path,0x1c0) }
    private val dir = File(root,START_WORK_DIRECTORY)
    private val store=AtomicStartWorkStore(dir)
    private fun uuid()=UUID.randomUUID().toString()
    private fun pending():PendingStartWork {
        val r=StartWorkReference(uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),openingFloatMinor=0,requestHash="")
        return PendingStartWork(reference=r.copy(requestHash=r.expectedHash()),actorName="Employee",authBinding="a".repeat(64))
    }
    private fun outcome(p:PendingStartWork,status:String):StartWorkOutcome {
        val r=p.reference
        val receipt=StartWorkReceipt(uuid(),r.companyId,r.actorId,r.branchId,r.terminalId,r.installationId,uuid(),"open",0,uuid(),"2026-11-12T00:00:00Z",true,false,"2026-11-12T00:00:00Z")
        return StartWorkOutcome("committed",receipt,currentShift=StartWorkCurrentShift(receipt.shiftId,r.companyId,r.branchId,r.terminalId,status,"android",r.installationId))
    }
    @After fun removeOnlyTemporaryTestJournal() { root.deleteRecursively() }
    @Test fun writeReadCASAndCrossInstanceSinglePending()=runBlocking {
        val p=pending();store.save(null,p);assertEquals(listOf(p),AtomicStartWorkStore(dir).pending())
        assertTrue(runCatching{AtomicStartWorkStore(dir).save(null,pending())}.isFailure)
        assertTrue(runCatching{store.save(p.copy(actorName="changed"),p)}.isFailure)
        assertEquals(listOf(p),store.pending())
    }
    @Test fun crashAfterPositiveArchiveBeforeRetirementCanResumeAfterActualShiftCloses()=runBlocking {
        val p=pending();store.save(null,p);val first=outcome(p,"open")
        val archive=File(dir,"${p.reference.scopeKey}-resolved-${p.reference.actionId}.json")
        archive.writeText(StartWorkJson.encodeToString(p.copy(phase="RESOLVED",resolution=first)));Os.chmod(archive.path,0x180)
        val later=first.copy(currentShift=first.currentShift!!.copy(status="closed"))
        store.archive(p,later){true};assertTrue(store.pending().isEmpty())
        assertEquals(first,StartWorkJson.decodeFromString<PendingStartWork>(archive.readText()).resolution)
    }
    @Test fun partialWrongModeSymlinkAndHardlinkEvidenceAllBlockReadiness()=runBlocking {
        val p=pending();store.save(null,p);val f=File(dir,"${p.reference.scopeKey}.json")
        Os.chmod(f.path,0x1b6);assertFalse(store.readiness().verified);Os.chmod(f.path,0x180)
        val partial=File(f.path+".new");partial.writeText("partial");Os.chmod(partial.path,0x180)
        assertFalse(store.readiness().verified);check(partial.delete())
        val original=f.readBytes();val linksBefore=Os.lstat(f.path).st_nlink
        val link=File(root,"hard-link")
        val linked=try {
            Os.link(f.path,link.path)
            true
        } catch (error:ErrnoException) {
            if(error.errno!=OsConstants.EACCES&&error.errno!=OsConstants.EPERM)throw error
            assertFalse(link.exists());assertArrayEquals(original,f.readBytes())
            assertEquals(linksBefore,Os.lstat(f.path).st_nlink)
            android.util.Log.i("StartWorkStoreTest","Platform denied hard-link creation; journal link-count guard was not exercised in this branch (errno=${error.errno})")
            false
        }
        if(linked) { assertFalse(store.readiness().verified);check(link.delete()) }
        check(f.delete());val target=File(root,"target");target.writeBytes(original);Os.chmod(target.path,0x180);Os.symlink(target.path,f.path)
        assertFalse(store.readiness().verified)
    }
    @Test fun trustedFrameworkBaseIsCapturedCanonicallyAndJournalSymlinksRefuse()=runBlocking {
        android.util.Log.i("StartWorkStoreTest", "Framework private base=${context.noBackupFilesDir.absolutePath}; canonical=${context.noBackupFilesDir.canonicalPath}")
        assertEquals(root.parentFile, context.noBackupFilesDir.canonicalFile)
        val target=File(root,"journal-target");check(target.mkdir());Os.chmod(target.path,0x1c0)
        Os.symlink(target.path,dir.path)
        assertFalse(store.readiness().verified)
        assertTrue(runCatching{store.save(null,pending())}.isFailure)
        check(dir.delete())
        val alias=File(root,"parent-alias");Os.symlink(target.path,alias.path)
        assertFalse(AtomicStartWorkStore(File(alias,START_WORK_DIRECTORY)).readiness().verified)
    }
    @Test fun contradictoryImmutableArchivedReceiptCannotRetirePending()=runBlocking {
        val p=pending();store.save(null,p);val first=outcome(p,"open")
        val archive=File(dir,"${p.reference.scopeKey}-resolved-${p.reference.actionId}.json")
        archive.writeText(StartWorkJson.encodeToString(p.copy(phase="RESOLVED",resolution=first)));Os.chmod(archive.path,0x180)
        val contradictory=first.copy(receipt=first.receipt!!.copy(attendanceId=uuid()))
        assertTrue(runCatching{store.archive(p,contradictory){true}}.isFailure)
        assertEquals(listOf(p),store.pending())
    }
    @Test fun changedAuthorityCannotRetirePendingEvenWithValidOutcome()=runBlocking {
        val p=pending();store.save(null,p)
        assertTrue(runCatching{store.archive(p,outcome(p,"closed")){false}}.isFailure)
        assertEquals(listOf(p),store.pending())
    }
}

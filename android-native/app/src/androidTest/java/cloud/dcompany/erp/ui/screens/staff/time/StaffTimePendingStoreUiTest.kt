package cloud.dcompany.erp.ui.screens.staff.time

import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaffTimePendingStoreUiTest {
    private lateinit var dir: File
    private lateinit var store: AtomicStaffTimePendingStore
    private val scope = "a".repeat(64)
    private val uid = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private fun attempt(key: String = UUID.randomUUID().toString()) = PendingTimeAttempt(StaffTimeIntent(
        key, uid, company, branch, null, "b".repeat(64), uid, "Synthetic filesystem worker", TimeWriteKind.SELF_IN,
        payload = StaffTimeJson.encodeToString(TimeSelfClockIn(branch)),
    ))
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dir = File(context.noBackupFilesDir.canonicalFile, "staff-time-qa-${UUID.randomUUID()}")
        store = AtomicStaffTimePendingStore(dir)
    }
    @After fun cleanup() {
        // Only this test's random directory; never follows symbolic links.
        if (::dir.isInitialized) {
            if (dir.exists() && OsConstants.S_ISDIR(Os.lstat(dir.path).st_mode)) {
                dir.listFiles()?.forEach { check(it.delete()) }
                check(dir.delete())
            } else if (runCatching { Os.lstat(dir.path) }.isSuccess) check(dir.delete())
        }
    }
    private suspend fun mustRefuse(block: suspend () -> Unit) {
        var refused = false
        try { block() } catch (_: Exception) { refused = true }
        assertTrue("Unsafe or conflicting persistence must be refused", refused)
    }
    @Test fun saveAndRestartReadRetainExactIntentPrivatelyThenMatchingReceiptClears() = runBlocking {
        val a = attempt(); store.save(scope, a)
        assertEquals(a, AtomicStaffTimePendingStore(dir).read(scope))
        assertEquals(0x1c0, Os.lstat(dir.path).st_mode and 0x1ff)
        val f = File(dir, "$scope.json")
        assertEquals(0x180, Os.lstat(f.path).st_mode and 0x1ff)
        assertEquals(1L, Os.lstat(f.path).st_nlink)
        store.complete(scope, a.intent.key); assertNull(store.read(scope))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
    @Test fun competingRequestOrWrongReceiptCannotOverwriteOrClearOriginal() = runBlocking {
        val a = attempt(); store.save(scope, a)
        mustRefuse { store.save(scope, attempt()) }
        mustRefuse { store.complete(scope, UUID.randomUUID().toString()) }
        assertEquals(a, store.read(scope))
    }
    @Test fun unknownCannotBeArchivedButKnownRefusalNeedsExplicitAcknowledgment() = runBlocking {
        val a = attempt(); store.save(scope, a)
        mustRefuse { store.acknowledgeRejected(scope, a.intent.key) }
        assertEquals(a, store.read(scope))
        val rejected = store.reject(scope, a.intent.key, 422)
        assertEquals(rejected, store.read(scope))
        store.acknowledgeRejected(scope, a.intent.key)
        assertNull(store.read(scope))
        val archive = File(dir, "$scope-rejected-${a.intent.key}.json")
        assertEquals(rejected, StaffTimeJson.decodeFromString(PendingTimeAttempt.serializer(), archive.readText()))
        assertEquals(0x180, Os.lstat(archive.path).st_mode and 0x1ff)
    }
    @Test fun legacyAtomicBackupRecoversExactRequestWithoutNewKey() = runBlocking {
        val a = attempt(); store.save(scope, a)
        val f = File(dir, "$scope.json"); val bak = File(f.path + ".bak")
        f.copyTo(bak); Os.chmod(bak.path, 0x180); check(f.delete())
        assertEquals(a, store.read(scope)); assertTrue(f.exists()); assertFalse(bak.exists())
    }
    @Test fun malformedOrOversizedFileFailsClosedWithoutDeletingIt() = runBlocking {
        store.read(scope)
        val f = File(dir, "$scope.json"); f.writeText("not-json"); Os.chmod(f.path, 0x180)
        mustRefuse { store.read(scope) }; assertEquals("not-json", f.readText())
        f.writeBytes(ByteArray(65_537)); Os.chmod(f.path, 0x180)
        mustRefuse { store.read(scope) }; assertEquals(65_537L, f.length())
    }
    @Test fun symlinkIsRejectedAndHardlinkIsBlockedByPlatformOrStore() = runBlocking {
        store.read(scope)
        val target = File(dir, "owned-target"); target.writeText("owned canary"); Os.chmod(target.path, 0x180)
        val f = File(dir, "$scope.json"); Os.symlink(target.path, f.path)
        mustRefuse { store.read(scope) }; assertEquals("owned canary", target.readText())
        check(f.delete())
        try {
            Os.link(target.path, f.path)
            mustRefuse { store.save(scope, attempt()) }
            assertEquals(2L, Os.lstat(target.path).st_nlink)
        } catch (denial: android.system.ErrnoException) {
            // Android SELinux may reject creating hardlinks before persistence can see one.
            assertEquals(OsConstants.EACCES, denial.errno)
            assertFalse(f.exists())
        }
        assertEquals("owned canary", target.readText())
    }
    @Test fun unsafePermissionsAndTraversalScopeAreRejected() = runBlocking {
        store.read(scope)
        val f = File(dir, "$scope.json"); f.writeText("owned canary"); Os.chmod(f.path, 0x1a4)
        mustRefuse { store.read(scope) }; assertEquals("owned canary", f.readText())
        mustRefuse { store.read("../outside") }
    }
    @Test fun directorySymlinkIsRejectedWithoutWritingIntoTarget() = runBlocking {
        val target = File(dir.parentFile, dir.name + "-target")
        check(target.mkdir()); Os.chmod(target.path, 0x1c0)
        try {
            Os.symlink(target.path, dir.path)
            mustRefuse { store.save(scope, attempt()) }
            assertTrue(target.listFiles().isNullOrEmpty())
        } finally { check(dir.delete()); check(target.delete()) }
    }
    @Test fun rejectedRetryTransitionIsDurableUnknownAndRefusesArchiveAfterRecreation() = runBlocking {
        val original = attempt(); store.save(scope, original)
        val rejected = store.reject(scope, original.intent.key, 401)
        val unknown = store.prepareForDispatch(scope, rejected)
        assertEquals(original.intent, unknown.intent); assertNull(unknown.rejectedStatus)
        val restarted = AtomicStaffTimePendingStore(dir)
        assertEquals(unknown, restarted.read(scope))
        mustRefuse { restarted.acknowledgeRejected(scope, original.intent.key) }
        assertEquals(unknown, restarted.read(scope))
        // A genuine new definitive response can make only this exact request reviewable again.
        val deniedAgain = restarted.reject(scope, original.intent.key, 401)
        assertEquals(401, deniedAgain.rejectedStatus)
    }
    @Test fun prepareDispatchRefusesChangedIntentAndCannotAlterStoredRejection() = runBlocking {
        val original = attempt(); store.save(scope, original)
        val rejected = store.reject(scope, original.intent.key, 401)
        mustRefuse { store.prepareForDispatch(scope, rejected.copy(intent = rejected.intent.copy(targetName = "Different fields"))) }
        mustRefuse { store.prepareForDispatch(scope, attempt()) }
        assertEquals(rejected, AtomicStaffTimePendingStore(dir).read(scope))
    }

    @Test fun handoverPresenceIncludesOtherScopesAndMalformedAtomicVariantsWithoutChangingBytes() = runBlocking {
        assertTrue(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).isClear)
        assertFalse(dir.exists())
        check(dir.mkdir()); Os.chmod(dir.path, 0x1c0)
        val retained = File(dir, "${"c".repeat(64)}.json.bak")
        retained.writeText("private malformed evidence"); Os.chmod(retained.path, 0x180)
        assertTrue(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).verified)
        assertTrue(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).pending)
        assertFalse(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).isClear)
        assertEquals("private malformed evidence", retained.readText())
    }
    @Test fun handoverPresenceRefusesAliasedOrNonprivateDirectories() = runBlocking {
        check(dir.mkdir()); Os.chmod(dir.path, 0x1ed)
        try { assertFalse(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).verified); assertFalse(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).isClear) }
        finally { Os.chmod(dir.path, 0x1c0) }
        check(dir.delete())
        val target = File(dir.parentFile, dir.name + "-presence-target")
        check(target.mkdir()); Os.chmod(target.path, 0x1c0)
        try {
            Os.symlink(target.path, dir.path)
            assertFalse(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).verified)
            assertFalse(store.inspectRetainedWork(StaffTimePendingPurpose.PERSONAL_AND_OWNER).isClear)
            assertTrue(target.listFiles().isNullOrEmpty())
        } finally { check(dir.delete()); check(target.delete()) }
    }

    @Test fun acknowledgedHistoryOnlyIsClearButActiveOrForgedArchivesRemainBlockingInBothNamespaces() = runBlocking {
        for (purpose in StaffTimePendingPurpose.entries) {
            val original = attempt()
            val entry = if (purpose == StaffTimePendingPurpose.SHARED) original.copy(intent = original.intent.copy(
                terminalId = "44444444-4444-4444-8444-444444444444", kind = TimeWriteKind.SHARED_IN,
                payload = StaffTimeJson.encodeToString(SharedTimeFields(uid)))) else original
            val actualScope = timeDigest(listOf(uid, company, branch, entry.intent.terminalId.orEmpty()).joinToString("|"))
            store.save(actualScope, entry); store.reject(actualScope, entry.intent.key, 422)
            store.acknowledgeRejected(actualScope, entry.intent.key)
            val history = File(dir, "$actualScope-rejected-${entry.intent.key}.json")
            val bytes = history.readBytes()
            assertTrue(store.inspectRetainedWork(purpose).isClear)
            assertArrayEquals(bytes, history.readBytes())
            // A valid archive in the other namespace is not proof of this namespace's protocol.
            val other = StaffTimePendingPurpose.entries.single { it != purpose }
            assertFalse(store.inspectRetainedWork(other).isClear)
            val active = entry.copy(intent = entry.intent.copy(key = UUID.randomUUID().toString()))
            store.save(actualScope, active)
            assertFalse(store.inspectRetainedWork(purpose).isClear)
            store.complete(actualScope, active.intent.key)
            assertTrue(store.inspectRetainedWork(purpose).isClear)
            val forged = File(dir, "${"f".repeat(64)}-rejected-${entry.intent.key}.json")
            forged.writeBytes(bytes); Os.chmod(forged.path, 0x180)
            assertFalse(store.inspectRetainedWork(purpose).isClear)
            assertArrayEquals(bytes, forged.readBytes()); check(forged.delete())
            history.writeText("malformed private history")
            assertFalse(store.inspectRetainedWork(purpose).isClear)
            assertEquals("malformed private history", history.readText())
            check(history.delete())
            val unknown = File(dir, "unknown-rejected-history.json")
            unknown.writeBytes(bytes); Os.chmod(unknown.path, 0x180)
            assertFalse(store.inspectRetainedWork(purpose).isClear)
            assertArrayEquals(bytes, unknown.readBytes()); check(unknown.delete())
        }
    }

}

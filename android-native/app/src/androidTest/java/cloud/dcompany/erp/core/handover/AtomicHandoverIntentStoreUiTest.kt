package cloud.dcompany.erp.core.handover

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AtomicHandoverIntentStoreUiTest {
    private lateinit var dir: File
    private lateinit var store: AtomicHandoverIntentStore
    private val scope = HandoverScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private fun intent() = HandoverIntent(HandoverAction(UUID.randomUUID().toString(), HandoverAuthority(scope, 1),
        "66666666-6666-4666-8666-666666666666", "55555555-5555-4555-8555-555555555555", 0), priorMutationUncertainty = false)
    private fun receipt(i: HandoverIntent) = AcceptedHandover(UUID.randomUUID().toString(), i.action.key,
        scope.companyId, requireNotNull(scope.branchId), requireNotNull(scope.terminalId), i.action.shiftId, scope.actorId, i.action.incomingUserId, 0, 1)
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dir = File(context.noBackupFilesDir.canonicalFile, "handover-qa-${UUID.randomUUID()}")
        store = AtomicHandoverIntentStore(dir)
    }
    @After fun cleanup() {
        if (::dir.isInitialized) {
            val s = runCatching { Os.lstat(dir.path) }.getOrNull()
            if (s != null) {
                if (OsConstants.S_ISDIR(s.st_mode)) dir.listFiles()?.forEach { check(it.delete()) }
                check(dir.delete())
            }
        }
    }
    private suspend fun refuses(block: suspend () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Unsafe persistence must be refused", denied)
    }
    @Test fun uncertainAndLegacyRecordsSurviveRecreationAndCannotBeDeletedAsUnsent() = runBlocking {
        val uncertain = intent().copy(priorMutationUncertainty = true)
        store.begin(uncertain)
        val recreated = AtomicHandoverIntentStore(dir)
        refuses { recreated.cancelNotSent(uncertain) }
        val file = File(dir, "pending.json")
        val raw = HandoverJson.encodeToString(uncertain).replace(",\"priorMutationUncertainty\":true", "")
        assertFalse(raw.contains("priorMutationUncertainty"))
        file.writeText(raw); Os.chmod(file.path, 0x180)
        assertEquals(uncertain, AtomicHandoverIntentStore(dir).read())
        refuses { recreated.cancelNotSent(uncertain) }
        assertTrue(file.exists())
    }
    @Test fun exactIntentSurvivesRecreationPrivatelyAndNotSentCanCancel() = runBlocking {
        val i = intent(); store.begin(i)
        assertEquals(i, AtomicHandoverIntentStore(dir).read())
        assertEquals(0x1c0, Os.lstat(dir.path).st_mode and 0x1ff)
        val file = File(dir, "pending.json")
        assertEquals(0x180, Os.lstat(file.path).st_mode and 0x1ff)
        assertEquals(1L, Os.lstat(file.path).st_nlink)
        store.cancelNotSent(i); assertNull(store.read())
    }
    @Test fun sentUnknownSurvivesRestartAndCannotBeCancelledOrResetByMissingReceipt() = runBlocking {
        val i = intent(); store.begin(i); val unknown = i.copy(stage = HandoverStage.SENT_UNKNOWN)
        store.compareAndSet(i, unknown)
        val restarted = AtomicHandoverIntentStore(dir)
        assertEquals(unknown, restarted.read())
        refuses { restarted.cancelNotSent(unknown) }
        refuses { restarted.compareAndSet(unknown, i) }
        refuses { restarted.begin(i) }
        assertEquals(unknown, restarted.read())
    }
    @Test fun currentRecordCasRefusesWrongKeyAndWrongScopeWithoutReplacingOriginal() = runBlocking {
        val i = intent(); store.begin(i)
        refuses { store.begin(intent()) }
        refuses { store.compareAndSet(intent(), intent().copy(stage = HandoverStage.SENT_UNKNOWN)) }
        refuses { store.compareAndSet(i, i.copy(action = i.action.copy(origin = i.action.origin.copy(scope = scope.copy(actorId = i.action.incomingUserId))))) }
        assertEquals(i, AtomicHandoverIntentStore(dir).read())
    }
    @Test fun acceptedReceiptMustMatchOriginalAndRemainsImmutableAfterRecreation() = runBlocking {
        val i = intent(); store.begin(i); val unknown = i.copy(stage = HandoverStage.SENT_UNKNOWN)
        store.compareAndSet(i, unknown)
        refuses { store.compareAndSet(unknown, unknown.copy(stage = HandoverStage.ACCEPTED, receipt = receipt(i).copy(toRevision = 3))) }
        val accepted = unknown.copy(stage = HandoverStage.ACCEPTED, receipt = receipt(i))
        store.compareAndSet(unknown, accepted)
        assertEquals(accepted, AtomicHandoverIntentStore(dir).read())
        refuses { store.cancelNotSent(accepted) }
        refuses { store.compareAndSet(accepted, accepted.copy(receipt = receipt(i))) }
        assertEquals(accepted, store.read())
    }
    @Test fun malformedOversizedAndCredentialInjectedFilesFailClosedWithoutDeletingEvidence() = runBlocking {
        store.read(); val file = File(dir, "pending.json")
        for (raw in listOf("not-json", "x".repeat(16_385), HandoverJson.encodeToString(intent()).dropLast(1) + ",\"password\":\"secret\"}")) {
            file.writeText(raw); Os.chmod(file.path, 0x180)
            refuses { store.read() }; assertEquals(raw, file.readText())
        }
    }
    @Test fun backupRecoversLastDurableExactFence() = runBlocking {
        val i = intent(); store.begin(i)
        val file = File(dir, "pending.json"); val backup = File(dir, "pending.json.bak")
        file.copyTo(backup); Os.chmod(backup.path, 0x180); check(file.delete())
        assertEquals(i, AtomicHandoverIntentStore(dir).read())
        assertTrue(file.isFile); assertFalse(backup.exists())
    }
    @Test fun unsafeFilePermissionsAndSymbolicLinkCannotBeReadOrOverwritten() = runBlocking {
        val i = intent(); store.begin(i); val file = File(dir, "pending.json")
        Os.chmod(file.path, 0x1a4); refuses { store.read() }
        Os.chmod(file.path, 0x180); val target = File(dir, "canary"); check(file.renameTo(target))
        Os.symlink(target.path, file.path)
        refuses { store.read() }; refuses { store.begin(intent()) }
        assertEquals(HandoverJson.encodeToString(i), target.readText())
    }
    @Test fun directorySymlinkIsRefusedWithoutTouchingTarget() = runBlocking {
        val target = File(dir.parentFile, dir.name + "-target"); check(target.mkdir()); Os.chmod(target.path, 0x1c0)
        try {
            Os.symlink(target.path, dir.path)
            refuses { store.begin(intent()) }; assertTrue(target.listFiles().isNullOrEmpty())
        } finally { check(dir.delete()); check(target.delete()) }
    }
}

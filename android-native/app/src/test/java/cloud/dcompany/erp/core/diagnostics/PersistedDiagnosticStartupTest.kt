package cloud.dcompany.erp.core.diagnostics

import cloud.dcompany.erp.PersistedStartupFailure
import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.importPersistedDiagnosticHistoryIfReady
import cloud.dcompany.erp.runBoundedPersistedStartupRestore
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedDiagnosticStartupTest {

    @Test
    fun `delayed restore uses witnesses captured before live scope is cleared`() = runBlocking {
        var liveWitness: PersistedDiagnosticStartupWitness? = witnessA()
        val frozenWitness = requireNotNull(liveWitness)
        val identityCapture = PersistedDiagnosticStartupIdentityCapture()
        val tokenLoaded = CompletableDeferred<Unit>()
        val releaseOtherStore = CompletableDeferred<Unit>()
        val importedScopes = mutableListOf<String?>()
        val gate = PersistedDiagnosticHistoryImportGate(frozenWitness) { importedScopes += it }

        val restore = async {
            runBoundedPersistedStartupRestore(
                timeoutMillis = 1_000L,
                loaders = listOf(
                    {
                        identityCapture.capture(identityA())
                        tokenLoaded.complete(Unit)
                    },
                    { releaseOtherStore.await() },
                ),
            )
        }
        tokenLoaded.await()

        // Models SessionViewModel's normal pre-await onScopeUnavailable call.
        liveWitness = null
        assertNull(liveWitness)
        assertFalse(restore.isCompleted)
        assertTrue(importedScopes.isEmpty())

        releaseOtherStore.complete(Unit)
        val scheduled = mutableListOf<PersistedDiagnosticStartupIdentity>()
        importPersistedDiagnosticHistoryIfReady(
            restore.await(),
            identityCapture.current(),
            { scheduled += it },
        )
        gate.importOnce(scheduled.single())

        assertEquals(listOf(scopeHashA()), importedScopes)
    }

    @Test
    fun `other store failure preserves startup A through retry and current account B`() = runBlocking {
        val identityCapture = PersistedDiagnosticStartupIdentityCapture()
        val tokenCaptured = CompletableDeferred<Unit>()
        val importedScopes = mutableListOf<String?>()
        val gate = PersistedDiagnosticHistoryImportGate(witnessA()) { importedScopes += it }

        val first = runBoundedPersistedStartupRestore(
            timeoutMillis = 1_000L,
            loaders = listOf(
                {
                    identityCapture.capture(identityA())
                    tokenCaptured.complete(Unit)
                },
                {
                    tokenCaptured.await()
                    throw IOException("another startup store failed")
                },
            ),
        )
        val scheduled = mutableListOf<PersistedDiagnosticStartupIdentity>()
        importPersistedDiagnosticHistoryIfReady(first, identityCapture.current()) {
            scheduled += it
        }
        assertEquals(
            PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE),
            first,
        )
        assertTrue(scheduled.isEmpty())

        // A later account cannot replace the first completed persisted-token load.
        identityCapture.capture(identityB())
        val retry = runBoundedPersistedStartupRestore(1_000L, listOf({ Unit }))
        importPersistedDiagnosticHistoryIfReady(retry, identityCapture.current()) {
            scheduled += it
        }
        gate.importOnce(scheduled.single())

        assertEquals(listOf(scopeHashA()), importedScopes)
    }

    @Test
    fun `successful signed out startup cannot borrow a later account`() = runBlocking {
        val identityCapture = PersistedDiagnosticStartupIdentityCapture()
        identityCapture.capture(null)
        identityCapture.capture(identityB())
        val scheduled = mutableListOf<PersistedDiagnosticStartupIdentity>()
        importPersistedDiagnosticHistoryIfReady(
            PersistedStartupStateResult.Ready,
            identityCapture.current(),
            { scheduled += it },
        )
        val importedScopes = mutableListOf<String?>()
        PersistedDiagnosticHistoryImportGate(witnessA()) { importedScopes += it }
            .importOnce(scheduled.single())

        assertEquals(listOf<String?>(null), importedScopes)
    }

    @Test
    fun `concurrent duplicate ready hooks import exactly once`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var imports = 0
        val gate = PersistedDiagnosticHistoryImportGate(witnessA()) {
            imports += 1
            entered.complete(Unit)
            release.await()
        }

        val first = async { gate.importOnce(PersistedDiagnosticStartupIdentity(identityA())) }
        entered.await()
        val second = async { gate.importOnce(PersistedDiagnosticStartupIdentity(identityB())) }
        release.complete(Unit)
        awaitAll(first, second)

        assertEquals(1, imports)
    }

    @Test
    fun `failed durable import releases claim for a later retry`() = runBlocking {
        var attempts = 0
        var durableRowExists = false
        var markerAcknowledged = false
        val gate = PersistedDiagnosticHistoryImportGate(witnessA()) {
            attempts += 1
            val inserted = !durableRowExists
            durableRowExists = true
            if (attempts == 1) {
                assertTrue(inserted)
                throw IOException("marker acknowledgement failed after insert")
            }
            // INSERT IGNORE reports false on retry because the immutable UUID
            // already exists; that durable duplicate permits acknowledgement.
            assertFalse(inserted)
            markerAcknowledged = true
        }

        val first = runCatching {
            gate.importOnce(PersistedDiagnosticStartupIdentity(identityA()))
        }
        assertTrue(first.isFailure)
        assertTrue(durableRowExists)
        assertFalse(markerAcknowledged)

        gate.importOnce(PersistedDiagnosticStartupIdentity(identityA()))
        assertEquals(2, attempts)
        assertTrue(markerAcknowledged)

        gate.importOnce(PersistedDiagnosticStartupIdentity(identityB()))
        assertEquals(2, attempts)
    }

    @Test
    fun `cancelled import releases claim and never marks history complete`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val gate = PersistedDiagnosticHistoryImportGate(witnessA()) {
            attempts += 1
            if (attempts == 1) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        val cancelled = launch {
            gate.importOnce(PersistedDiagnosticStartupIdentity(identityA()))
        }
        entered.await()
        cancelled.cancelAndJoin()

        gate.importOnce(PersistedDiagnosticStartupIdentity(identityA()))
        assertEquals(2, attempts)
    }

    @Test
    fun `production wiring snapshots before ready and never imports from install provider`() {
        val app = read("src/main/java/cloud/dcompany/erp/DCompanyApp.kt")
        val runtime = read(
            "src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticRuntime.kt",
        )
        val tokenLoader = app.substringAfter("loaders = listOf(")
            .substringBefore("shiftCache::loadProfile")
        val onCreate = app.substringAfter("override fun onCreate()")
            .substringBefore("private fun startPersistedStartupStateRestoration")
        val importMethod = runtime.substringAfter(
            "suspend fun importPersistedStartupHistory",
        ).substringBefore("/** Called from the one central HTTP error interceptor")

        assertTrue(tokenLoader.indexOf("tokens.load()") < tokenLoader.indexOf(".capture("))
        assertTrue("importPersistedDiagnosticHistoryIfReady" in app)
        assertTrue(
            onCreate.indexOf("DiagnosticsRuntime.install(") <
                onCreate.indexOf("startPersistedDiagnosticHistoryImport()"),
        )
        assertFalse("accessTokenProvider" in importMethod)
        assertTrue("diagnosticOutbox.capture(it.event, provenMarkerScope)" in runtime)
        assertTrue("diagnosticOutbox.capture(it, persistedScopeHash)" in runtime)
    }

    private fun witnessA() = PersistedDiagnosticStartupWitness(
        cacheScope = CacheScope("user-a", "company-a", "branch-a", "terminal-a"),
        verifiedScopeHash = scopeHashA(),
    )

    private fun identityA() = OutboxOwnerIdentity("user-a", "company-a", "branch-a")

    private fun identityB() = OutboxOwnerIdentity("user-b", "company-a", "branch-a")

    private fun scopeHashA() = diagnosticScopeHash("company-a", "user-a", "branch-a")

    private fun read(relativePath: String): String =
        Files.newBufferedReader(projectRoot().resolve(relativePath)).use { it.readText() }

    private fun projectRoot(): Path {
        var cursor = Paths.get("").toAbsolutePath().normalize()
        repeat(8) {
            val direct = cursor.resolve("src/main/AndroidManifest.xml")
            if (Files.exists(direct)) return cursor
            val nested = cursor.resolve("app/src/main/AndroidManifest.xml")
            if (Files.exists(nested)) return cursor.resolve("app")
            val repositoryNested = cursor.resolve("android-native/app/src/main/AndroidManifest.xml")
            if (Files.exists(repositoryNested)) return cursor.resolve("android-native/app")
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate Android app root from ${Paths.get("").toAbsolutePath()}")
    }
}

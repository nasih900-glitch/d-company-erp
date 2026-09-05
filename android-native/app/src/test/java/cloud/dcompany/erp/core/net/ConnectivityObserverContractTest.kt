package cloud.dcompany.erp.core.net

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android callback itself cannot be instantiated in a local JVM test, but
 * this source contract ensures callbacks consume the framework's ordered
 * facts instead of making synchronous reads that can return stale state.
 * The pure tracker tests cover handover, loss and outdated capabilities.
 */
class ConnectivityObserverContractTest {

    @Test
    fun `default network callbacks use ordered facts without synchronous rereads`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/net/ConnectivityCoordinator.kt",
            ),
        ).use { it.readText() }
        val callback = source.substringAfter("registerDefaultNetworkCallback(")
            .substringBefore("private suspend fun process(")

        assertTrue("networkCallbacks.available(network)" in callback)
        assertTrue("networkCallbacks.lost(network)" in callback)
        assertTrue("networkCallbacks.capabilitiesChanged(network, caps.isValidatedInternet())" in callback)
        assertFalse("Callbacks must not re-read an old default network", "currentlyValidated()" in callback)
        assertFalse("Callbacks must use their ordered capabilities argument", "getNetworkCapabilities(" in callback)
        assertFalse("Callbacks must not query default-network state", "activeNetwork" in callback)
        assertFalse("Startup must not seed a snapshot that can become stale before registration", "manager?.activeNetwork" in source)
        assertFalse("Only ordered callback capabilities may establish network state", "getNetworkCapabilities(" in source)
        assertTrue("First ordered capabilities must skip the recovery-only delay", "initialObservation = !receivedNetworkCapabilities" in callback)
    }

    private fun projectRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/AndroidManifest.xml") to Paths.get(""),
            Paths.get("app/src/main/AndroidManifest.xml") to Paths.get("app"),
            Paths.get("android-native/app/src/main/AndroidManifest.xml") to
                Paths.get("android-native/app"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.first) }?.second
            ?.toAbsolutePath()?.normalize()
            ?: error("Could not locate the Android app module from ${Paths.get("").toAbsolutePath()}")
    }
}

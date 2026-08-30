package cloud.dcompany.erp.core.net

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android callback itself cannot be instantiated in a local JVM test, but
 * this source contract protects the handover rule that prevents a departing
 * Wi-Fi network from flashing the whole ERP offline while a replacement
 * default network is already validated.
 */
class ConnectivityObserverContractTest {

    @Test
    fun `default network callbacks re-read the active network`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/core/net/ConnectivityCoordinator.kt",
            ),
        ).use { it.readText() }
        val callback = source.substringAfter("registerDefaultNetworkCallback(")
            .substringBefore("private fun refresh()")

        assertTrue("override fun onAvailable(network: Network) = refresh()" in callback)
        assertTrue("override fun onLost(network: Network) = refresh()" in callback)
        assertTrue(
            Regex(
                """override fun onCapabilitiesChanged\([\s\S]*?\) = refresh\(\)""",
            ).containsMatchIn(callback),
        )
        assertFalse(
            "A lost callback is not proof that the replacement default network is offline",
            Regex("""onLost\([^)]*\)\s*=\s*offerNetworkState\(false\)""")
                .containsMatchIn(callback),
        )
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

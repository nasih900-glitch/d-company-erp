package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.ErpApi
import cloud.dcompany.erp.core.sync.requireCheckoutClientInstanceForReconciliation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header

class CheckoutClientInstanceContractTest {

    @Test
    fun `checkout claim API requires the installation identity header`() {
        val method = ErpApi::class.java.methods.single { it.name == "acquireCheckoutClaim" }
        val headerParameter = method.parameterAnnotations.indexOfFirst { annotations ->
            annotations.any { it is Header && it.value == CHECKOUT_CLIENT_INSTANCE_HEADER }
        }

        assertTrue("The checkout identity header is missing", headerParameter >= 0)
        assertEquals(String::class.java, method.parameterTypes[headerParameter])

        val apiSource = readSource(NET_PATH.resolve("ErpApi.kt")).replace(Regex("\\s+"), " ")
        val signature = apiSource.between(
            "suspend fun acquireCheckoutClaim(",
            "): CheckoutClaimResult",
        )
        assertTrue(
            signature.contains(
                "@Header(\"$CHECKOUT_CLIENT_INSTANCE_HEADER\") checkoutClientInstance: String",
            ),
        )
        assertFalse("Android must not permit an anonymous claim", signature.contains("String?"))
    }

    @Test
    fun `interactive and recovery claims both use the one stable installation provider`() {
        val app = readSource(APP_PATH).replace(Regex("\\s+"), " ")
        val pos = readSource(POS_PATH).replace(Regex("\\s+"), " ")
        val sync = readSource(SYNC_PATH).replace(Regex("\\s+"), " ")
        val allMainSources = mainKotlinSources().map(::readSource)

        assertEquals(
            "Every claim call must be reviewed when another checkout path is added",
            2,
            allMainSources.sumOf { it.countOccurrences("ApiClient.api.acquireCheckoutClaim(") },
        )
        assertTrue(
            app.contains(
                "checkoutClientInstance = updateTelemetry.installation::installationId",
            ),
        )
        assertTrue(
            pos.contains(
                "CheckoutClientInstancePolicy.requireStable( app.updateTelemetry.installation.installationId(), )",
            ),
        )
        assertTrue(pos.contains("checkoutClientInstance = checkoutClientInstance"))
        assertTrue(
            sync.contains(
                "checkoutClientInstance = requireCheckoutClientInstanceForReconciliation( checkoutClientInstance, )",
            ),
        )
    }

    @Test
    fun `identity policy accepts only canonical random UUID and never invents a fallback`() {
        assertEquals(
            INSTALLATION_ID,
            CheckoutClientInstancePolicy.requireStable(INSTALLATION_ID.uppercase()),
        )

        listOf(null, "", "not-a-uuid", VERSION_ONE_UUID).forEach { unavailable ->
            val failure = assertThrows(CheckoutClientInstanceUnavailableException::class.java) {
                CheckoutClientInstancePolicy.requireStable(unavailable)
            }
            assertEquals(CheckoutClientInstancePolicy.UNAVAILABLE_MESSAGE, failure.message)
        }
    }

    @Test
    fun `reacquisition reads identity once and fails closed with retained-payment guidance`() {
        var providerCalls = 0
        val resolved = requireCheckoutClientInstanceForReconciliation {
            providerCalls += 1
            INSTALLATION_ID
        }

        assertEquals(INSTALLATION_ID, resolved)
        assertEquals(1, providerCalls)

        val failure = assertThrows(ApiException::class.java) {
            requireCheckoutClientInstanceForReconciliation { null }
        }
        assertEquals(409, failure.status)
        assertEquals("checkout_client_instance_unavailable", failure.code)
        assertTrue(failure.message.orEmpty().contains("Do not collect again"))
    }

    private fun mainKotlinSources(): List<Path> = Files.walk(mainSourceRoot()).use { files ->
        files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .toList()
    }

    private fun readSource(path: Path): String =
        Files.newBufferedReader(mainSourceRoot().resolve(path)).use { it.readText() }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull(Files::isDirectory)?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root from ${Paths.get("").toAbsolutePath()}")
    }

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var cursor = 0
        while (true) {
            val match = indexOf(needle, cursor)
            if (match < 0) return count
            count += 1
            cursor = match + needle.length
        }
    }

    private companion object {
        const val CHECKOUT_CLIENT_INSTANCE_HEADER = "X-Checkout-Client-Instance"
        const val INSTALLATION_ID = "11111111-1111-4111-8111-111111111111"
        const val VERSION_ONE_UUID = "11111111-1111-1111-8111-111111111111"
        val APP_PATH: Path = Paths.get("cloud/dcompany/erp/DCompanyApp.kt")
        val NET_PATH: Path = Paths.get("cloud/dcompany/erp/core/net")
        val POS_PATH: Path = Paths.get("cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val SYNC_PATH: Path = Paths.get("cloud/dcompany/erp/core/sync/SyncEngine.kt")
    }
}

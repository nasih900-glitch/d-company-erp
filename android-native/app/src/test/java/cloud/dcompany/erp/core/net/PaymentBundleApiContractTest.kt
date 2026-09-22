package cloud.dcompany.erp.core.net

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

class PaymentBundleApiContractTest {

    @Test
    fun `bundle endpoint requires one body one stable key and the checkout claim`() {
        val method = ErpApi::class.java.methods.single { it.name == "recordPaymentBundle" }
        assertEquals(
            "pos/orders/{id}/payment-bundle",
            requireNotNull(method.getAnnotation(POST::class.java)).value,
        )

        val bodyIndex = method.parameterAnnotations.indexOfFirst { annotations ->
            annotations.any { Body::class.java.isInstance(it) }
        }
        val headers = method.parameterAnnotations.flatMap { annotations ->
            annotations.mapNotNull { (it as? Header)?.value }
        }
        assertEquals(PaymentBundleRequest::class.java, method.parameterTypes[bodyIndex])
        assertTrue("Idempotency-Key" in headers)
        assertTrue("X-Checkout-Claim" in headers)
    }

    @Test
    fun `sync routes a saved split plan to bundle once with no sequential fallback`() {
        val source = Files.newBufferedReader(mainSourceRoot().resolve(SYNC_ENGINE)).use {
            it.readText()
        }
        val function = source.substringAfter("private suspend fun pushHeldOrderPaymentOne(")
            .substringBefore("private suspend fun acquireMatchingClaimForConfirmedPayment(")

        assertEquals(1, function.countOccurrences("ApiClient.api.recordPaymentBundle("))
        assertEquals(1, function.countOccurrences("ApiClient.api.recordPayment("))
        assertTrue(function.contains("val singlePaid = if (splitPlan == null)"))
        assertTrue(
            "A post-bundle invoice read failure must remain an ambiguous durable retry",
            function.contains("The split payment may already be committed") &&
                function.contains("throw IllegalStateException("),
        )
        assertFalse(
            "A split row must never loop over legs and call the single-payment endpoint",
            function.contains("plan.legs.forEach") || function.contains("for (leg in plan.legs)"),
        )
    }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull(Files::isDirectory)?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var cursor = 0
        while (true) {
            val next = indexOf(needle, cursor)
            if (next < 0) return count
            count += 1
            cursor = next + needle.length
        }
    }

    private companion object {
        val SYNC_ENGINE: Path = Paths.get("cloud/dcompany/erp/core/sync/SyncEngine.kt")
    }
}

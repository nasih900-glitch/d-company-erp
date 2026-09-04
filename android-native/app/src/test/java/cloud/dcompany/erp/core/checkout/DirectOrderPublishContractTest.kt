package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.net.ErpApi
import cloud.dcompany.erp.core.net.PublishDirectCheckoutClaimRequest
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DirectOrderPublishContractTest {
    @Test
    fun `retrofit publication requires expected version key and installation identity`() {
        val method = ErpApi::class.java.methods.single { it.name == "publishDirectCheckoutClaim" }
        assertEquals(
            "pos/orders/{id}/publish-checkout-claim",
            requireNotNull(method.getAnnotation(POST::class.java)).value,
        )

        fun parameterWith(annotation: Class<out Annotation>, value: String? = null): Int =
            method.parameterAnnotations.indexOfFirst { annotations ->
                annotations.any { current ->
                    annotation.isInstance(current) &&
                        (value == null || (current as Header).value == value)
                }
            }

        val body = parameterWith(Body::class.java)
        val key = parameterWith(Header::class.java, "Idempotency-Key")
        val installation = parameterWith(Header::class.java, "X-Checkout-Client-Instance")
        assertEquals(PublishDirectCheckoutClaimRequest::class.java, method.parameterTypes[body])
        assertEquals(String::class.java, method.parameterTypes[key])
        assertEquals(String::class.java, method.parameterTypes[installation])
        assertEquals(
            "{\"expected_checkout_version\":7}",
            Json.encodeToString(PublishDirectCheckoutClaimRequest(expectedCheckoutVersion = 7)),
        )
    }

    @Test
    fun `online flow saves claim before exposing payment and does not edit points afterward`() {
        val viewModel = source(POS_VIEW_MODEL).normalised()
        val screen = source(POS_SCREEN).normalised()
        val sync = source(SYNC_ENGINE).normalised()
        assertEquals(
            "Every publish call site must preserve the installation-bound contract",
            2,
            (viewModel + sync).countOccurrences("ApiClient.api.publishDirectCheckoutClaim("),
        )
        val publish = viewModel.between(
            "private suspend fun publishDirectCheckoutAndPersist(",
            "fun dismissDirectCheckout()",
        )

        assertOrdered(
            publish,
            "ApiClient.api.publishDirectCheckoutClaim(",
            "db.orderDao().markDraftPrepared(",
            "directReviewOrderId.value = null",
        )
        assertTrue(publish.contains("claimToken = claim.claimToken"))
        assertTrue(viewModel.contains("claimToken = prepared.claimToken"))
        assertTrue(
            viewModel.contains(
                "initialLocal.syncState == SyncState.AWAITING_PAYMENT && " +
                    "!initialLocal.checkoutClaimToken.isNullOrBlank()",
            ),
        )

        val directPayment = screen.between(
            "state.preparedDirectCheckout",
            "state.preparedHeldCheckout",
        )
        assertFalse(
            "Published direct payment UI must not mutate points",
            directPayment.contains("onApplyPoints ="),
        )
        assertTrue(screen.contains("state.directCheckoutReview"))
        assertTrue(screen.contains("onContinue = onContinueDirectCheckout"))

        val dismiss = viewModel.between(
            "fun dismissDirectCheckout()",
            "fun redeemDirectPoints(",
        )
        assertOrdered(
            dismiss,
            "db.orderDao().removeExactPreparedDirect(",
            "releaseClaimBestEffort(prepared.orderId, prepared.claimToken)",
        )

        val zeroTotal = viewModel.between(
            "fun confirmDirectZero()",
            "fun voidOrder(orderId: String, reason: String)",
        )
        assertOrdered(
            zeroTotal,
            "ApiClient.api.finalizeZeroTotalOrder(",
            "db.orderDao().markExactZeroDirectFinalized(",
            "ApiClient.api.order(prepared.orderId)",
        )
        assertOrdered(
            zeroTotal,
            "db.orderDao().returnPublishedDirectToRecovery(",
            "releaseClaimBestEffort(prepared.orderId, prepared.claimToken)",
        )
        assertTrue(zeroTotal.contains("The detailed receipt is still syncing."))

        val heldZeroTotal = viewModel.between(
            "fun confirmHeldOrderZero(orderId: String)",
            "private suspend fun refreshHeldOrdersBestEffort()",
        )
        assertOrdered(
            heldZeroTotal,
            "ApiClient.api.finalizeZeroTotalOrder(",
            "acceptedInvoiceNo = result.invoiceNo",
            "ApiClient.api.order(prepared.orderId)",
        )
        val acceptedHeldZeroTotal = heldZeroTotal.substringAfter(
            "acceptedInvoiceNo = result.invoiceNo",
        )
        assertOrdered(
            acceptedHeldZeroTotal,
            "preparedHeldCheckout.compareAndSet(prepared, null)",
            "ApiClient.api.order(prepared.orderId)",
        )
        assertTrue(heldZeroTotal.contains("The detailed receipt is still syncing."))

        val void = viewModel.between(
            "fun voidOrder(orderId: String, reason: String)",
            "fun captureSale(",
        )
        assertOrdered(
            void,
            "db.orderDao().removeExactPreparedDirect(",
            "ApiClient.api.voidOrder(",
        )
        assertOrdered(
            void,
            "preparedHeldCheckout.compareAndSet(held, null)",
            "ApiClient.api.voidOrder(",
        )
        assertTrue(void.contains("direct?.claimToken ?: held?.claimToken"))
        assertFalse(void.contains("releaseClaimBestEffort(orderId, it.claimToken)"))
        assertFalse(void.contains("releaseClaimBestEffort(it.orderId, it.claimToken)"))
    }

    @Test
    fun `offline flow checkpoints publishes persists and only then pays`() {
        val sync = source(SYNC_ENGINE).normalised()
        val pushOne = sync.substringAfter("private suspend fun pushOne(")

        assertOrdered(
            pushOne,
            "dao.checkpointPendingServerOrder(",
            "ApiClient.api.publishDirectCheckoutClaim(",
            "dao.savePendingDirectClaim(",
            "ApiClient.api.recordPayment(",
        )
        assertTrue(pushOne.contains("checkoutClaimToken = claimToken"))
        assertTrue(pushOne.contains("DirectOrderPublishPolicy.idempotencyKey(order.localId)"))
        assertTrue(pushOne.contains("requireCheckoutClientInstanceForReconciliation("))
        assertTrue(sync.contains("DirectOrderPublishPolicy.paymentNeedsClaim(row)"))
    }

    private fun assertOrdered(text: String, vararg markers: String) {
        var cursor = -1
        markers.forEach { marker ->
            val next = text.indexOf(marker)
            assertTrue("Missing contract marker: $marker", next >= 0)
            assertTrue("Contract marker is out of order: $marker", next > cursor)
            cursor = next
        }
    }

    private fun source(path: Path): String =
        Files.newBufferedReader(mainSourceRoot().resolve(path)).use { it.readText() }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull(Files::isDirectory)?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root")
    }

    private fun String.normalised(): String = replace(Regex("\\s+"), " ")

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
            val next = indexOf(needle, cursor)
            if (next < 0) return count
            count += 1
            cursor = next + needle.length
        }
    }

    private companion object {
        val POS_VIEW_MODEL: Path = Paths.get("cloud/dcompany/erp/ui/screens/PosViewModel.kt")
        val POS_SCREEN: Path = Paths.get("cloud/dcompany/erp/ui/screens/PosScreen.kt")
        val SYNC_ENGINE: Path = Paths.get("cloud/dcompany/erp/core/sync/SyncEngine.kt")
    }
}

package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ErpApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class TenderApiContractTest {
    private val scope = TenderScope("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333")
    private val body = PaymentTenderCommit("44444444-4444-4444-4444-444444444444", "a".repeat(64), "Confirmed source")
    private fun retrofit(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType())).build()
    private fun receipt(command: PendingTenderCommand) = TenderReceipt(
        id = UUID.randomUUID().toString(), receiptReference = "TC-receipt", operation = "payment_method_corrected",
        financialEffect = true, companyId = scope.companyId, branchId = scope.branchId,
        businessDate = "2026-10-18", businessTimezone = "Asia/Kolkata", recordedBy = scope.ownerId,
        recordedAt = "2026-10-19T10:00:00Z", reason = body.reason, evidenceKind = "owner_attestation",
        sourceFingerprint = body.sourceFingerprint, idempotencyKey = command.idempotencyKey,
        sourceAllocation = "identified_payment", totalDifferenceMinor = 0, paymentId = body.paymentId,
        originalMethod = "cash", effectiveMethod = "upi", amountMinor = 27000,
    )

    @Test fun lostReplyRecoveryUsesSamePathKeyAndBodyWithFreshMemoryGrant() = runBlocking {
        MockWebServer().use { server ->
            val api = retrofit(server).create(FinanceApi::class.java)
            val command = paymentTenderCommand(scope, body, "fixed-160-safe-key", 1000)
            val row = receipt(command)
            repeat(2) { server.enqueue(MockResponse().setResponseCode(201).setBody(ApiClient.json.encodeToString(row))) }
            assertEquals(row, executeTenderCommand(api, "first-grant", command))
            assertEquals(row, executeTenderCommand(api, "fresh-grant", command))
            val first = server.takeRequest()
            val replay = server.takeRequest()
            assertEquals("/api/v1/finance/tender-corrections/payment/commit", first.path)
            assertEquals(first.path, replay.path)
            assertEquals(command.idempotencyKey, first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), replay.getHeader("Idempotency-Key"))
            val firstBody = first.body.readUtf8()
            assertEquals(command.bodyJson, firstBody)
            assertEquals(firstBody, replay.body.readUtf8())
            assertEquals("fresh-grant", replay.getHeader("X-Tender-Token"))
        }
    }

    @Test fun mismatchedServerReceiptCannotRetireSavedCommand() = runBlocking {
        MockWebServer().use { server ->
            val api = retrofit(server).create(FinanceApi::class.java)
            val command = paymentTenderCommand(scope, body, "exact", 1000)
            server.enqueue(MockResponse().setResponseCode(201).setBody(ApiClient.json.encodeToString(receipt(command).copy(idempotencyKey = "other-action"))))
            assertTrue(runCatching { executeTenderCommand(api, "grant", command) }.isFailure)
        }
    }

    @Test fun singlePasswordUnlockCarriesSeparatePurposeTokensAndHistoryUsesReadGrant() = runBlocking {
        MockWebServer().use { server ->
            val api = retrofit(server).create(FinanceApi::class.java)
            server.enqueue(MockResponse().setBody("""{"token":"write-only","history_token":"read-only","expires_in":300}"""))
            server.enqueue(MockResponse().setBody("[]"))
            val result = api.unlockTender(TenderUnlockRequest(scope.branchId, "payment", "local-test-password"))
            assertEquals(300, result.expiresIn)
            api.tenderHistory(result.historyToken, scope.branchId, paymentId = body.paymentId)
            assertEquals("/api/v1/finance/tender-corrections/unlock", server.takeRequest().path)
            val history = server.takeRequest()
            assertEquals("read-only", history.getHeader("X-Tender-Token"))
            assertTrue(requireNotNull(history.path).contains("payment_id=${body.paymentId}"))
        }
    }

    @Test fun rejectedOrUncertainResponsePreservesExactSavedCommandUntilExplicitReview() = runBlocking {
        MockWebServer().use { server ->
            val api = retrofit(server).create(FinanceApi::class.java)
            val rows = mutableMapOf<String,String>()
            val journal = TenderJournal(object : TenderJournalStorage {
                override fun read(key: String) = rows[key]
                override fun put(key: String,value: String): Boolean { rows[key]=value; return true }
                override fun remove(key: String): Boolean { rows.remove(key); return true }
            })
            val command = journal.reserve(paymentTenderCommand(scope,body,"retain-across-http",1000))
            listOf(401,403,409,500).forEach { status ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"code":"conflict","message":"Review saved action","details":{"retryable":true}}}"""))
                assertTrue(runCatching { executeTenderCommand(api,"fresh-grant",command) }.isFailure)
                assertEquals(command,journal.read(scope))
                assertEquals(command.idempotencyKey,server.takeRequest().getHeader("Idempotency-Key"))
            }
        }
    }

    @Test fun receiptSearchSendsEntireAuthorisedFilterTupleWithoutLocalPageFiltering() = runBlocking {
        MockWebServer().use { server ->
            val api = retrofit(server).create(ErpApi::class.java)
            server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":null,"has_more":false,"date_filter_basis":"invoice_issued_at_in_shop_timezone","shop_timezone":"Asia/Kolkata"}"""))
            val result = api.receiptHistory(cursor = "bound-cursor", query = "D/MN/26", dateFrom = "2026-10-17", dateTo = "2026-10-18", stationId = "station", amountMinor = 27000, method = "upi", staffUserId = "staff")
            val query = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("bound-cursor", query.queryParameter("cursor"))
            assertEquals("2026-10-17", query.queryParameter("date_from"))
            assertEquals("2026-10-18", query.queryParameter("date_to"))
            assertEquals("station", query.queryParameter("station_id"))
            assertEquals("27000", query.queryParameter("amount_minor"))
            assertEquals("upi", query.queryParameter("method"))
            assertEquals("staff", query.queryParameter("staff_user_id"))
            assertEquals("D/MN/26", query.queryParameter("q"))
            assertEquals("Asia/Kolkata", result.shopTimezone)
        }
    }
}

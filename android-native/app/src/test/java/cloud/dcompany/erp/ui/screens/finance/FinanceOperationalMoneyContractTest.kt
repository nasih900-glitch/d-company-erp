package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class FinanceOperationalMoneyContractTest {

    @Test
    fun manualCollectionTipPayoutAndTrialBalanceDecodeServerFields() {
        val collection = ApiClient.json.decodeFromString<ManualCollection>(
            """{
              "id":"mc-1","company_id":"company","branch_id":"shop",
              "business_date":"2026-08-28","method":"upi","amount_minor":162000,
              "source_kind":"manual_daily","source_ref":"Daily collection 2026-08-28 UPI",
              "note":"Closing total","idempotency_key":"manual-collection:key",
              "created_by":"user","created_by_name":"Rafi","created_at":"2026-08-28T18:00:00Z",
              "voided_at":null,"voided_by":null,"voided_by_name":null,"void_reason":null,
              "is_voided":false
            }""".trimIndent(),
        )
        val payout = ApiClient.json.decodeFromString<TipPayout>(
            """{
              "id":"tip-1","company_id":"company","branch_id":"shop",
              "amount_minor":5000,"method":"cash","paid_at":"2026-08-28T18:10:00Z",
              "note":"Split among shift staff","idempotency_key":"tip-payout:key",
              "created_by":"user","created_by_name":"Shemeer","created_at":"2026-08-28T18:10:00Z",
              "voided_at":null,"voided_by":null,"voided_by_name":null,"void_reason":null,
              "is_voided":false
            }""".trimIndent(),
        )
        val balance = ApiClient.json.decodeFromString<TrialBalance>(
            """{
              "as_of":"2026-08-28","lines":[{
                "account_code":"2400","account_name":"Tips Payable","account_type":"liability",
                "debit_minor":0,"credit_minor":7500,"balance_minor":7500
              }],"total_debit_minor":7500,"total_credit_minor":7500,"is_balanced":true
            }""".trimIndent(),
        )

        assertEquals(162_000L, collection.amountMinor)
        assertEquals("Rafi", collection.createdByName)
        assertEquals(5_000L, payout.amountMinor)
        assertEquals("Shemeer", payout.createdByName)
        assertEquals(7_500L, balance.tipsPayableMinor())
    }

    @Test
    fun retrofitContractUsesAuthoritativeListCreateVoidAndTrialBalanceRoutes() {
        val annotations = FinanceApi::class.java.declaredMethods.associate { method ->
            method.name to (method.getAnnotation(GET::class.java)?.value
                ?: method.getAnnotation(POST::class.java)?.value)
        }

        assertEquals("finance/manual-collections", annotations["manualCollections"])
        assertEquals("finance/manual-collections", annotations["createManualCollection"])
        assertEquals(
            "finance/manual-collections/{collection_id}/void",
            annotations["voidManualCollection"],
        )
        assertEquals("finance/tip-payouts", annotations["tipPayouts"])
        assertEquals("finance/tip-payouts", annotations["createTipPayout"])
        assertEquals("finance/tip-payouts/{payout_id}/void", annotations["voidTipPayout"])
        assertEquals("accounting/trial-balance", annotations["trialBalance"])
    }

    @Test
    fun exactRequestRecoveryRetainsTheOriginalBodyAndIdempotencyKey() {
        val pending = pendingManualCollectionCreate(
            scope = FinanceWriteScope("rafi", "d-company", "main-shop"),
            body = ManualCollectionCreate(
                branchId = "main-shop",
                businessDate = "2026-08-28",
                method = "cash",
                amountMinor = 21_000,
                sourceRef = "Daily collection 2026-08-28 Cash",
            ),
            idempotencyKey = "manual-collection:stable-key",
            nowMillis = 123L,
        )

        val restored = ApiClient.json.decodeFromString<PendingFinanceOnlineWrite>(
            ApiClient.json.encodeToString(pending),
        )
        val restoredBody = ApiClient.json.decodeFromString<ManualCollectionCreate>(restored.bodyJson)

        assertEquals(pending, restored)
        assertEquals("manual-collection:stable-key", restored.idempotencyKey)
        assertEquals(21_000L, restoredBody.amountMinor)
        assertEquals("main-shop", restoredBody.branchId)
    }

    @Test
    fun onlyAmbiguousOrUpgradeFailuresKeepTheFinancialRecoveryCheckpoint() {
        assertTrue(preserveFinanceWriteForRetry(ApiException("timeout")))
        assertTrue(preserveFinanceWriteForRetry(ApiException("server", status = 500)))
        assertTrue(preserveFinanceWriteForRetry(ApiException("upgrade", status = 426)))
        assertFalse(
            preserveFinanceWriteForRetry(
                ApiException("amount exceeds Tips Payable", status = 422, code = "business_rule"),
            ),
        )
        assertTrue(financeWriteFailureMessage(ApiException("timeout"), true).contains("Do not enter or pay"))
    }
}

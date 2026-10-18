package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.ShiftHistoryMergePolicy
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CanonicalReceiptPayment
import cloud.dcompany.erp.core.net.ClientCompatibilityErrorDetails
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TenderReconstructionContractTest {
    private val original = """{"id":"shift","branch_id":"branch","status":"closed","opened_at":"2026-10-17T10:00:00Z","expected_minor":95000,"counted_minor":95000,"variance_minor":0,"cash_collections_minor":95000,"upi_collections_minor":304000}"""

    @Test fun legacyReconstructionRemainsUnknownThroughCacheAndMerge() {
        val row = ApiClient.json.decodeFromString<ShiftDetail>(original)
        val cache = row.toShiftHistoryCache("terminal", 1000, 2000, 3000)
        val merged = ShiftHistoryMergePolicy.merge(listOf(cache), emptyList()).single()
        assertNull(cache.collectionBasis)
        assertNull(merged.tenderCorrectionMinor)
        assertNull(merged.reconstructedExpectedMinor)
        assertEquals(95000L, merged.expectedMinor)
        assertEquals(95000L, merged.countedMinor)
        assertEquals(0L, merged.varianceMinor)
    }

    @Test fun exactReconstructionDoesNotReplaceRecordedDrawerFacts() {
        val json = original.dropLast(1) + """, "collection_basis":"original_recorded_shift","tender_correction_minor":27000,"reconstructed_cash_collections_minor":68000,"reconstructed_upi_collections_minor":331000,"reconstructed_expected_minor":68000}"""
        val row = ApiClient.json.decodeFromString<ShiftDetail>(json)
        val cache = row.toShiftHistoryCache("terminal", 1000, 2000, 3000)
        val merged = ShiftHistoryMergePolicy.merge(listOf(cache), emptyList()).single()
        assertEquals("original_recorded_shift", merged.collectionBasis)
        assertEquals(27000L, merged.tenderCorrectionMinor)
        assertEquals(68000L, merged.reconstructedCashCollectionsMinor)
        assertEquals(331000L, merged.reconstructedUpiCollectionsMinor)
        assertEquals(68000L, merged.reconstructedExpectedMinor)
        assertEquals(95000L, merged.expectedMinor)
        assertEquals(95000L, merged.countedMinor)
        assertEquals(95000L, merged.cashCollectionsMinor)
        assertEquals(304000L, merged.upiCollectionsMinor)
    }

    @Test fun canonicalPaymentRoundTripKeepsOriginalAndEffectiveTenderSeparate() {
        val json = """{"id":"p","shift_id":"s","method":"upi","amount_minor":27000,"tendered_minor":null,"change_minor":null,"paid_at":"2026-10-18T10:00:00Z","created_at":"2026-10-18T10:00:00Z","original_method":"cash","original_tendered_minor":30000,"original_change_minor":3000,"tender_correction_id":"c","tender_correction_recorded_at":"2026-10-19T10:00:00Z"}"""
        val first = ApiClient.json.decodeFromString<CanonicalReceiptPayment>(json)
        val cached = ApiClient.json.decodeFromString<CanonicalReceiptPayment>(ApiClient.json.encodeToString(first))
        assertEquals(first, cached)
        assertEquals("upi", cached.method)
        assertNull(cached.tenderedMinor)
        assertNull(cached.changeMinor)
        assertEquals("cash", cached.originalMethod)
        assertEquals(30000L, cached.originalTenderedMinor)
        assertEquals(3000L, cached.originalChangeMinor)
        assertEquals("c", cached.tenderCorrectionId)
    }

    @Test fun retryableSourceConflictDetailsSurviveEnvelopeDecoding() {
        val details = ApiClient.json.decodeFromString<ClientCompatibilityErrorDetails>("""{"retryable":true}""")
        assertEquals(true, details.retryable)
        assertNull(ApiClient.json.decodeFromString<ClientCompatibilityErrorDetails>("{}").retryable)
    }
}

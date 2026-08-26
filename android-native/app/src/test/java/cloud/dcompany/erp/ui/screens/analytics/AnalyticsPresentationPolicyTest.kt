package cloud.dcompany.erp.ui.screens.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsPresentationPolicyTest {

    @Test
    fun `cached dashboard is labelled stale after refresh failure`() {
        assertEquals(
            CachedDataPresentation.STALE,
            cachedDataPresentation(hasData = true, loading = false, error = "Offline"),
        )
        assertEquals(
            CachedDataPresentation.BLOCKING_ERROR,
            cachedDataPresentation(hasData = false, loading = false, error = "Offline"),
        )
    }

    @Test
    fun `top items cannot show a false empty state before a successful snapshot`() {
        assertEquals(
            SupplementalListPresentation.INITIAL_LOADING,
            supplementalListPresentation(hasSnapshot = false, isEmpty = true, error = null),
        )
        assertEquals(
            SupplementalListPresentation.BLOCKING_ERROR,
            supplementalListPresentation(hasSnapshot = false, isEmpty = true, error = "Offline"),
        )
    }

    @Test
    fun `saved empty and populated top items are labelled stale after failure`() {
        assertEquals(
            SupplementalListPresentation.STALE_EMPTY,
            supplementalListPresentation(hasSnapshot = true, isEmpty = true, error = "Offline"),
        )
        assertEquals(
            SupplementalListPresentation.STALE_CONTENT,
            supplementalListPresentation(hasSnapshot = true, isEmpty = false, error = "Offline"),
        )
        assertEquals(
            SupplementalListPresentation.FRESH_EMPTY,
            supplementalListPresentation(hasSnapshot = true, isEmpty = true, error = null),
        )
    }

    @Test
    fun `unexpected analytics failures are actionable and hide technical details`() {
        val message = analyticsLoadError(
            IllegalArgumentException("serializer internals"),
            "Could not load top items.",
        )

        assertTrue(message.contains("try again", ignoreCase = true))
        assertTrue(!message.contains("serializer internals"))
    }
}

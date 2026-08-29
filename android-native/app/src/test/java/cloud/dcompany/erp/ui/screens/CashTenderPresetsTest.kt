package cloud.dcompany.erp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class CashTenderPresetsTest {
    @Test
    fun `cash presets include exact due and useful rounded handovers`() {
        assertEquals(
            listOf(15_500L, 20_000L, 50_000L),
            cashTenderPresets(15_500L),
        )
    }

    @Test
    fun `cash presets remove duplicates for already rounded totals`() {
        assertEquals(
            listOf(50_000L),
            cashTenderPresets(50_000L),
        )
    }

    @Test
    fun `cash presets are empty when no payment is due`() {
        assertEquals(emptyList<Long>(), cashTenderPresets(0L))
    }
}

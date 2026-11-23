package cloud.dcompany.erp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoidReasonInputTest {
    @Test
    fun `preset contract has stable unique non-empty audit reasons`() {
        assertTrue(VOID_REASON_PRESETS.isNotEmpty())
        assertEquals(VOID_REASON_PRESETS.size, VOID_REASON_PRESETS.map { it.id }.distinct().size)
        assertTrue(VOID_REASON_PRESETS.none { it.id == VOID_REASON_OTHER_ID })
        assertTrue(VOID_REASON_PRESETS.all { it.id.isNotBlank() })
        assertTrue(
            VOID_REASON_PRESETS.all {
                it.reason.isNotBlank() && it.reason.length <= VOID_REASON_MAX_LENGTH
            },
        )
    }

    @Test
    fun `known preset resolves to its audit text and ignores custom draft`() {
        val preset = VOID_REASON_PRESETS.first()

        assertEquals(preset.reason, resolvedVoidReason(preset.id, "ignored custom text"))
    }

    @Test
    fun `other reason is capped and trimmed`() {
        val raw = "  ${"x".repeat(VOID_REASON_MAX_LENGTH + 20)}  "

        val result = resolvedVoidReason(VOID_REASON_OTHER_ID, raw)

        assertTrue(result.length <= VOID_REASON_MAX_LENGTH)
        assertEquals(result, result.trim())
    }

    @Test
    fun `missing or unknown selection cannot submit stale custom text`() {
        assertEquals("", resolvedVoidReason(null, "stale custom text"))
        assertEquals("", resolvedVoidReason("not-a-real-preset", "stale custom text"))
    }

    @Test
    fun `input limiter preserves prefix and caps at backend limit`() {
        val raw = "a".repeat(VOID_REASON_MAX_LENGTH + 1)

        assertEquals("a".repeat(VOID_REASON_MAX_LENGTH), limitVoidReasonInput(raw))
    }

    @Test fun `multiline reason retains every reviewed character within audit limit`() {
        val reason = "Reviewed retained item detail.\n".repeat(20).take(499) + "Z"
        assertEquals(500, reason.length)
        assertTrue(reason.contains('\n'))
        assertEquals(reason, limitVoidReasonInput(reason))
        assertEquals(reason, resolvedVoidReason(VOID_REASON_OTHER_ID, reason))
    }
}

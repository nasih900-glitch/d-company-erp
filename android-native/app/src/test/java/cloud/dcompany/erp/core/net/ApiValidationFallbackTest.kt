package cloud.dcompany.erp.core.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ApiValidationFallbackTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy validation names the field without echoing rejected secret`() {
        val body = """
            {
              "detail": [{
                "type": "string_too_long",
                "loc": ["body", "password"],
                "msg": "String should have at most 256 characters",
                "input": "never-show-this-password"
              }]
            }
        """.trimIndent()

        val result = fastApiValidationMessage(json, body)

        assertEquals("Password: String should have at most 256 characters.", result)
        assertFalse(result.orEmpty().contains("never-show"))
    }

    @Test
    fun `nested list location is actionable and bounded`() {
        val body = """
            {"detail":[{"loc":["body","lines",2,"qty"],"msg":"Input should be greater than 0"}]}
        """.trimIndent()

        assertEquals(
            "Lines item 2.qty: Input should be greater than 0.",
            fastApiValidationMessage(json, body),
        )
    }

    @Test
    fun `malformed payload returns no guessed message`() {
        assertNull(fastApiValidationMessage(json, "not-json"))
        assertNull(fastApiValidationMessage(json, "{}"))
    }
}

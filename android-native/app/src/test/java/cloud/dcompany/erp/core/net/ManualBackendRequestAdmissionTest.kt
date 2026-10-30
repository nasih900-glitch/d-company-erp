package cloud.dcompany.erp.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualBackendRequestAdmissionTest {

    @Test
    fun `manual origin accepts only the exact debug loopback API`() {
        assertTrue(isExactManualLoopbackOrigin(true, "http://127.0.0.1:8041/api/v1/"))
        listOf(
            false to "http://127.0.0.1:8041/api/v1/",
            true to "http://localhost:8041/api/v1/",
            true to "http://127.0.0.1:8042/api/v1/",
            true to "https://127.0.0.1:8041/api/v1/",
            true to "http://user@127.0.0.1:8041/api/v1/",
            true to "http://127.0.0.1:8041/api/v1/?candidate=1",
            true to "http://127.0.0.1:8041/api/v1/#candidate",
            true to "http://127.0.0.1:8041/api/v1/extra/",
        ).forEach { (debug, url) ->
            assertFalse(url, isExactManualLoopbackOrigin(debug, url))
        }
    }

    @Test
    fun `online uses normal client without probing or direct admission`() = runBlocking {
        var probes = 0
        var normal = 0
        var direct = 0
        val admission = ManualBackendRequestAdmission(
            online = { true },
            exactDebugLoopback = { true },
            probe = { probes += 1; true },
        )

        val result = admission.execute(
            ManualBackendOperation.DIRECT_AUDITED_MUTATION,
            normal = { normal += 1; "normal" },
            direct = { direct += 1; "direct" },
        )

        assertEquals("normal", result)
        assertEquals(0, probes)
        assertEquals(1, normal)
        assertEquals(0, direct)
    }

    @Test
    fun `offline direct operation requires a fresh successful proof each time`() = runBlocking {
        var probes = 0
        var calls = 0
        val admission = ManualBackendRequestAdmission(
            online = { false },
            exactDebugLoopback = { true },
            probe = { probes += 1; true },
        )

        repeat(2) {
            admission.execute(
                ManualBackendOperation.DIRECT_AUDITED_MUTATION,
                normal = { error("normal path must remain closed") },
                direct = { calls += 1 },
            )
        }

        assertEquals(2, probes)
        assertEquals(2, calls)
    }

    @Test
    fun `offline admission fails before request when origin or probe is invalid`() {
        for ((exact, ready) in listOf(false to true, true to false)) {
            var calls = 0
            val admission = ManualBackendRequestAdmission(
                online = { false },
                exactDebugLoopback = { exact },
                probe = { ready },
            )
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    admission.execute(
                        ManualBackendOperation.MANUAL_READ,
                        normal = { error("normal path must remain closed") },
                        direct = { calls += 1 },
                    )
                }
            }
            assertEquals(0, calls)
        }
    }
}

package cloud.dcompany.erp.core.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftCloseServerCacheCoverageTest {

    @Test
    fun `close gate includes cache-only gaming and terminal held orders`() {
        val source = Files.newBufferedReader(
            mainSourceRoot().resolve(SHIFT_CLOSE_SAFETY_DAO_PATH),
        ).use { it.readText() }.replace(Regex("\\s+"), " ")

        assertTrue("gaming server cache is absent from close readiness", source.contains(
            "FROM gaming_session_cache AS cached_session",
        ))
        assertTrue("gaming cache is not tied to the exact shift", source.contains(
            "cached_session.shiftId = :serverShiftId",
        ))
        assertTrue("running and stopped-unbilled cache states are not both protected", source.contains(
            "cached_session.status IN ('active', 'paused') OR (cached_session.status = 'ended'",
        ))
        assertTrue("local gaming outbox overlays would be double-counted", source.contains(
            "local_session.serverId = cached_session.id",
        ))
        assertTrue("terminal-scoped held-order cache is absent from close readiness", source.contains(
            "FROM held_order_cache AS cached_order",
        ))
        assertTrue("captured held payments would be double-counted", source.contains(
            "local_payment.targetOrderId = cached_order.id",
        ))
    }

    private fun mainSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        return candidates.firstOrNull(Files::isDirectory)?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android main source root from ${Paths.get("").toAbsolutePath()}")
    }

    private companion object {
        const val SHIFT_CLOSE_SAFETY_DAO_PATH =
            "cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt"
    }
}

package cloud.dcompany.erp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedStartupWorkerRetryContractTest {

    @Test
    fun `every authority sensitive background worker refreshes a cached startup failure on retry`() {
        val consumers = listOf(
            "core/sync/BackgroundSyncWorker.kt" to "class BackgroundSyncWorker(",
            "core/diagnostics/DiagnosticOutbox.kt" to "class DiagnosticSyncWorker(",
            "ui/screens/settings/BugReportOutbox.kt" to "class BugReportSyncWorker(",
        )

        consumers.forEach { (relativePath, classMarker) ->
            val source = read("src/main/java/cloud/dcompany/erp/$relativePath")
            assertTrue("Missing worker class marker in $relativePath", classMarker in source)
            val worker = source.substringAfter(classMarker)
            assertTrue(
                "$relativePath must retry a completed persisted-startup failure after WorkManager retries",
                "retryFailed = retryFailedPersistedStartupForWorkAttempt(runAttemptCount)" in worker,
            )
            assertTrue(
                "$relativePath must still fail closed when startup authority is unavailable",
                ") !is PersistedStartupStateResult.Ready" in worker &&
                    "return Result.retry()" in worker,
            )
        }
    }

    @Test
    fun `alarm redelivery refreshes cached startup failure without changing reschedule ordering`() {
        val receiver = read("src/main/java/cloud/dcompany/erp/core/alarm/AlarmReceiver.kt")
        val runtime = read(
            "src/main/java/cloud/dcompany/erp/core/alarm/OperationalAlarmRuntime.kt",
        )
        val rescheduleWorker = read(
            "src/main/java/cloud/dcompany/erp/core/alarm/AlarmRescheduleWorker.kt",
        )
        val delivery = receiver.substringAfter("class AlarmReceiver : BroadcastReceiver()")
            .substringBefore("internal object OperationalAlarmNotifier")

        assertTrue(
            "AlarmManager redelivery must refresh a completed transient startup failure",
            "retryFailedStartup = true" in delivery,
        )
        assertTrue(
            "Alarm authority activation must pass the explicit retry policy to the shared restorer",
            "retryFailed = retryFailedStartup" in runtime,
        )
        assertTrue(
            "System reschedule WorkManager remains an explicit fresh-restore retry lane",
            "app.awaitPersistedStartupState(retryFailed = true)" in rescheduleWorker,
        )
    }

    private fun read(relativePath: String): String =
        Files.newBufferedReader(projectRoot().resolve(relativePath)).use { it.readText() }

    private fun projectRoot(): Path {
        var cursor = Paths.get("").toAbsolutePath().normalize()
        repeat(8) {
            val direct = cursor.resolve("src/main/AndroidManifest.xml")
            if (Files.exists(direct)) return cursor
            val nested = cursor.resolve("app/src/main/AndroidManifest.xml")
            if (Files.exists(nested)) return cursor.resolve("app")
            val repositoryNested = cursor.resolve("android-native/app/src/main/AndroidManifest.xml")
            if (Files.exists(repositoryNested)) return cursor.resolve("android-native/app")
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate Android app root from ${Paths.get("").toAbsolutePath()}")
    }
}

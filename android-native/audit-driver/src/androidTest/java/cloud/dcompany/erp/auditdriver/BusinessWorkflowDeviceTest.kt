package cloud.dcompany.erp.auditdriver

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the actual installed ERP through Android accessibility, not mocked
 * composables. A separate target process permits genuine force-stop recovery.
 * Only the isolated physicalAudit package can be controlled. Test credentials
 * and plans are provided to disposable devices; neither is bundled in the APK.
 */
@RunWith(AndroidJUnit4::class)
class BusinessWorkflowDeviceTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val args = InstrumentationRegistry.getArguments()
    private val appPackage = "cloud.dcompany.erp.physicalaudit"
    private val runId = args.getString("auditRunId") ?: System.currentTimeMillis().toString()
    private lateinit var output: File
    private val timings = JSONArray()
    private lateinit var credentials: JSONObject

    @Test
    fun completeBusinessWorkflow() {
        val planPath = args.getString("auditPlan")
            ?: "/sdcard/Download/dcompany-audit-plan.json"
        val credentialPath = args.getString("auditCredentials")
            ?: "/sdcard/Download/dcompany-audit-credentials.json"
        runBusinessWorkflow(planPath, credentialPath)
    }

    @Test
    fun invalidPlanPathStillRemovesValidatedCredentialFile() {
        val credentialPath = "/sdcard/Download/dcompany-audit-invalid-plan-cleanup.json"
        device.executeShellCommand("echo '{}' > $credentialPath")
        var rejected = false
        try {
            runBusinessWorkflow("/sdcard/Download/../unsafe-plan.json", credentialPath)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Unsafe audit plan path was not rejected", rejected)
        val stillExists = device.executeShellCommand("test -e $credentialPath; echo \$?").trim() == "0"
        assertFalse("Validated audit credentials were retained after plan rejection", stillExists)
    }

    private fun runBusinessWorkflow(planPath: String, credentialPath: String) {
        var validatedCredentialPath: String? = null
        try {
            // Validate and remember the credential path first so a later plan
            // validation/parse failure cannot bypass credential cleanup.
            requireSafeInputPath(credentialPath)
            validatedCredentialPath = credentialPath
            requireSafeInputPath(planPath)
            require(runId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
            output = File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "business-audit/$runId",
            ).apply {
                check(mkdirs()) { "Each audit run must use a fresh evidence directory" }
            }
            val plan = JSONObject(device.executeShellCommand("cat $planPath"))
            credentials = JSONObject(device.executeShellCommand("cat $credentialPath"))
            require(credentials.optString("fixture") in setOf("main", "lenovo", "samsung", "backend")) {
                "Only explicit synthetic audit fixtures are accepted"
            }
            val steps = plan.getJSONArray("steps")
            require(steps.length() in 1..500) { "An explicit bounded workflow is required" }
            device.wakeUp()
            device.setOrientationNatural()
            device.waitForIdle(1_000)
            if (device.displayWidth < device.displayHeight) device.setOrientationLeft()
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                val label = step.getString("name")
                val started = SystemClock.elapsedRealtime()
                try {
                    execute(step, index + 1, label)
                    val duration = SystemClock.elapsedRealtime() - started
                    timings.put(JSONObject().put("step", index + 1).put("name", label)
                        .put("duration_ms", duration).put("status", "passed"))
                    capture("%03d-%s".format(index + 1, label))
                    Log.i("DCompanyBusinessAudit", "step=${index + 1} PASS duration_ms=$duration name=$label")
                } catch (failure: Throwable) {
                    timings.put(JSONObject().put("step", index + 1).put("name", label)
                        .put("duration_ms", SystemClock.elapsedRealtime() - started)
                        .put("status", "failed").put("error_type", failure.javaClass.simpleName))
                    capture("%03d-FAILED-%s".format(index + 1, label))
                    throw AssertionError("Business workflow failed at step ${index + 1}: $label", failure)
                } finally {
                    File(output, "steps.json").writeText(timings.toString(2))
                }
            }
        } finally {
            // Restore the disposable device even if an offline assertion fails.
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("cmd deviceidle unforce")
            device.executeShellCommand("cmd power set-mode 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.executeShellCommand(
                    "pm grant $appPackage android.permission.POST_NOTIFICATIONS",
                )
            }
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.unfreezeRotation()
            // Do not retain even synthetic login material in pulled artifacts.
            validatedCredentialPath?.let { device.executeShellCommand("rm $it") }
        }
    }

    private fun requireSafeInputPath(path: String) {
        require(path.matches(Regex("/sdcard/Download/[A-Za-z0-9._-]+\\.json")))
    }

    private fun execute(step: JSONObject, stepNumber: Int, label: String) {
        val timeout = step.optLong("timeoutMs", 20_000L).coerceIn(500L, 60_000L)
        when (val action = step.getString("action")) {
            "launch", "restart" -> {
                if (action == "restart") device.executeShellCommand("am force-stop $appPackage")
                device.executeShellCommand("am start -W -n $appPackage/cloud.dcompany.erp.MainActivity")
                assertTrue("ERP did not become visible", device.wait(Until.hasObject(By.pkg(appPackage)), timeout))
                if (step.optBoolean("assertAlarmRegistered", false)) {
                    assertTrue(
                        "ERP restart did not rebuild its active-session AlarmManager entry",
                        waitUntil(timeout) { alarmRegistered() },
                    )
                    File(output, "alarm-after-restart.txt").writeText(
                        device.executeShellCommand("dumpsys alarm"),
                    )
                }
            }
            "click" -> {
                val target = find(step, timeout)
                check(target.isEnabled) { "Target is disabled" }
                target.click()
                step.optJSONObject("then")?.let { waitFor(it, timeout) }
            }
            "fill" -> {
                val target = find(step, timeout)
                val value = if (step.has("valueKey")) credentialValue(step.getString("valueKey"))
                    else step.getString("value")
                check(target.isEnabled) { "Input is disabled" }
                target.text = value
            }
            "wait" -> waitFor(step, timeout)
            "absent" -> assertTrue("Unexpected UI remained present", device.wait(Until.gone(selector(step)), timeout))
            "back" -> device.pressBack()
            "home" -> device.pressHome()
            "scroll" -> {
                val target = find(step, timeout)
                target.scroll(Direction.valueOf(step.getString("direction").uppercase()),
                    step.optDouble("amount", 0.75).toFloat().coerceIn(0.1f, 2f))
            }
            "offline" -> {
                device.executeShellCommand("cmd connectivity airplane-mode enable")
                device.executeShellCommand("svc wifi disable")
            }
            "online" -> {
                device.executeShellCommand("cmd connectivity airplane-mode disable")
                device.executeShellCommand("svc wifi enable")
            }
            "capture" -> Unit
            "alarmConstraints" -> exerciseAlarmConstraints(timeout)
            "idleFrames", "idleStability" -> {
                val base = safeLabel("%03d-%s".format(stepNumber, label))
                capture("idle-$base-start")
                if (action == "idleFrames") {
                    checkedShell(
                        "frames-$base-reset.txt",
                        "dumpsys gfxinfo $appPackage reset",
                    )
                }
                val duration = step.optLong("durationMs", 10_000L).coerceIn(1_000L, 30_000L)
                val firstHalf = duration / 2
                SystemClock.sleep(firstHalf)
                capture("idle-$base-mid")
                SystemClock.sleep(duration - firstHalf)
                capture("idle-$base-end")
                if (action == "idleFrames") {
                    File(output, "frames-$base.txt").writeText(
                        device.executeShellCommand("dumpsys gfxinfo $appPackage framestats"),
                    )
                }
            }
            else -> error("Unsupported audit action: $action")
        }
    }

    /**
     * Feasible cloud-device constraints are exercised against the real audit
     * APK while a fixed Gaming session is pending offline. A true reboot would
     * kill this instrumentation process, so target-Redmi reboot and OEM battery
     * policy remain explicit external gates in the runner evidence.
     */
    private fun exerciseAlarmConstraints(timeout: Long) {
        val evidence = JSONObject()
        val alarmBefore = device.executeShellCommand("dumpsys alarm")
        File(output, "alarm-before-constraints.txt").writeText(alarmBefore)
        evidence.put("alarm_registered_before", operationalAlarmRegistered(alarmBefore))
        check(evidence.getBoolean("alarm_registered_before")) {
            "No AlarmManager entry exists for the active fixed-time session"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkedShell(
                "notification-revoke.txt",
                "pm revoke $appPackage android.permission.POST_NOTIFICATIONS",
            )
            evidence.put("notification_denied", !notificationPermissionGranted())
            check(evidence.getBoolean("notification_denied")) {
                "Android notification permission did not enter the denied state"
            }
            checkedShell(
                "notification-grant.txt",
                "pm grant $appPackage android.permission.POST_NOTIFICATIONS",
            )
            evidence.put(
                "notification_regranted",
                waitUntil(timeout) { notificationPermissionGranted() },
            )
        } else {
            evidence.put("notification_denied", true)
            evidence.put("notification_regranted", true)
            evidence.put("notification_permission_not_runtime_on_api", Build.VERSION.SDK_INT)
        }
        check(evidence.getBoolean("notification_regranted")) {
            "Android notification permission was not restored"
        }

        device.sleep()
        SystemClock.sleep(1_000L)
        evidence.put("screen_locked", !device.isScreenOn)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        evidence.put("screen_woken", waitUntil(timeout) { device.isScreenOn })
        check(evidence.getBoolean("screen_locked") && evidence.getBoolean("screen_woken")) {
            "Lock/wake recovery could not be exercised"
        }

        val dozeEnter = checkedShell("doze-enter.txt", "cmd deviceidle force-idle")
        val dozeState = device.executeShellCommand("dumpsys deviceidle")
        File(output, "doze-state.txt").writeText(dozeState)
        evidence.put(
            "doze_entered",
            dozeEnter.contains("forced", ignoreCase = true) ||
                dozeState.contains("mState=IDLE") || dozeState.contains("mForceIdle=true"),
        )
        checkedShell("doze-exit.txt", "cmd deviceidle unforce")
        val dozeAfter = device.executeShellCommand("dumpsys deviceidle")
        evidence.put(
            "doze_exited",
            !dozeAfter.contains("mForceIdle=true") && !dozeAfter.contains("mState=IDLE"),
        )
        check(evidence.getBoolean("doze_entered") && evidence.getBoolean("doze_exited")) {
            "Doze force/restore constraint did not complete"
        }

        // PowerManagerService applies this asynchronously on physical Lenovo
        // hardware. A one-shot settings read can observe the old value even
        // after the shell command has been accepted, so prove each transition
        // with the same bounded polling contract used by the other constraints.
        // Restoration belongs in finally so a polling/interruption failure
        // cannot leave the disposable tablet in power-save mode.
        evidence.put("battery_saver_enabled", false)
        evidence.put("battery_saver_disabled", false)
        try {
            checkedShell("battery-saver-enable.txt", "cmd power set-mode 1")
            evidence.put(
                "battery_saver_enabled",
                waitUntil(timeout) { powerSaveModeEnabled() },
            )
        } finally {
            checkedShell("battery-saver-disable.txt", "cmd power set-mode 0")
            evidence.put(
                "battery_saver_disabled",
                waitUntil(timeout) { !powerSaveModeEnabled() },
            )
        }
        check(
            evidence.getBoolean("battery_saver_enabled") &&
                evidence.getBoolean("battery_saver_disabled"),
        ) { "Battery-saver enable/restore constraint did not complete" }

        val alarmAfter = device.executeShellCommand("dumpsys alarm")
        File(output, "alarm-after-constraints.txt").writeText(alarmAfter)
        evidence.put("alarm_registered_after", operationalAlarmRegistered(alarmAfter))
        check(evidence.getBoolean("alarm_registered_after")) {
            "Active-session alarm disappeared while exercising device constraints"
        }
        evidence.put("true_reboot_performed", false)
        evidence.put("oem_background_policy_proven", false)
        File(output, "alarm-constraints.json").writeText(evidence.toString(2))
    }

    private fun notificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val packageState = device.executeShellCommand("dumpsys package $appPackage")
        return Regex(
            "android\\.permission\\.POST_NOTIFICATIONS: granted=true",
        ).containsMatchIn(packageState)
    }

    private fun powerSaveModeEnabled(): Boolean =
        device.executeShellCommand("settings get global low_power").trim() == "1"

    private fun alarmRegistered(): Boolean =
        operationalAlarmRegistered(device.executeShellCommand("dumpsys alarm"))

    private fun operationalAlarmRegistered(alarmDump: String): Boolean {
        val action = "cloud.dcompany.erp.action.DELIVER_ALARM"
        // Package presence alone is insufficient: WorkManager and sync jobs can
        // also put this APK in dumpsys alarm even when the Gaming deadline was
        // never scheduled. Require the dedicated operational-alarm action in
        // the same bounded AlarmManager record neighbourhood.
        val escapedPackage = Regex.escape(appPackage)
        val escapedAction = Regex.escape(action)
        return Regex(
            "(?s)(?:$escapedPackage.{0,1600}$escapedAction|$escapedAction.{0,1600}$escapedPackage)",
        ).containsMatchIn(alarmDump)
    }

    private fun checkedShell(filename: String, command: String): String {
        val result = device.executeShellCommand(command)
        File(output, filename).writeText("$command\n$result")
        check(
            !result.contains("permission denial", ignoreCase = true) &&
                !result.contains("unknown command", ignoreCase = true) &&
                !result.contains("security exception", ignoreCase = true) &&
                !result.startsWith("Error", ignoreCase = true),
        ) { "Device constraint command failed: $command" }
        return result
    }

    private fun waitUntil(timeout: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return true
            SystemClock.sleep(250L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    private fun credentialValue(path: String): String {
        require(path.matches(Regex("users\\.[0-3]\\.(email|password)")))
        val parts = path.split('.')
        return credentials.getJSONArray("users").getJSONObject(parts[1].toInt()).getString(parts[2])
    }

    private fun selector(spec: JSONObject): BySelector {
        var query = when {
            spec.has("text") -> By.text(spec.getString("text"))
            spec.has("textContains") -> By.textContains(spec.getString("textContains"))
            spec.has("textRegex") -> By.text(Pattern.compile(spec.getString("textRegex")))
            spec.has("description") -> By.desc(spec.getString("description"))
            spec.has("descriptionContains") -> By.descContains(spec.getString("descriptionContains"))
            spec.has("class") -> By.clazz(spec.getString("class"))
            spec.has("scrollable") -> By.scrollable(spec.getBoolean("scrollable"))
            else -> error("A visible accessibility selector is required")
        }
        if (spec.has("class") && (spec.has("text") || spec.has("textContains") ||
                spec.has("textRegex") || spec.has("description") || spec.has("descriptionContains"))) {
            query = query.clazz(spec.getString("class"))
        }
        if (spec.has("clickable")) query = query.clickable(spec.getBoolean("clickable"))
        if (spec.has("scrollable") && spec.has("class")) query = query.scrollable(spec.getBoolean("scrollable"))
        return query
    }

    private fun find(spec: JSONObject, timeout: Long): UiObject2 {
        val query = selector(spec)
        val index = spec.optInt("index", 0)
        require(index >= 0) { "Control index must not be negative" }
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            // UIAutomator can retain a stale Compose accessibility subtree across
            // a rapid network transition. Read the live tree on supported audit
            // devices; do not relax the selector, timeout or visibility assertion.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val cachedCount = device.findObjects(query).size
                check(InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()) {
                    "Could not invalidate the audit accessibility cache"
                }
                val freshTargets = device.findObjects(query)
                if (cachedCount <= index && freshTargets.size > index) {
                    Log.i("DCompanyBusinessAudit", "Fresh accessibility read found a control absent from cached tree")
                }
                freshTargets.getOrNull(index)?.let { return it }
            } else {
                device.findObjects(query).getOrNull(index)?.let { return it }
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) SystemClock.sleep(minOf(250L, remaining))
        } while (SystemClock.elapsedRealtime() < deadline)
        error("No matching visible control at index $index within ${timeout}ms")
    }

    private fun waitFor(spec: JSONObject, timeout: Long) {
        find(spec, timeout)
    }

    private fun capture(label: String) {
        val safeLabel = safeLabel(label)
        device.takeScreenshot(File(output, "$safeLabel.png"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()) {
                "Could not invalidate the audit evidence accessibility cache"
            }
        }
        device.dumpWindowHierarchy(File(output, "$safeLabel.xml"))
    }

    private fun safeLabel(label: String): String =
        label.replace(Regex("[^A-Za-z0-9._-]"), "-").take(110)
}

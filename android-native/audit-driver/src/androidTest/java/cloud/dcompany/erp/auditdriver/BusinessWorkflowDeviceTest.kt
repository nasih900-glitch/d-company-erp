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
                    execute(step)
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
            device.unfreezeRotation()
            // Do not retain even synthetic login material in pulled artifacts.
            validatedCredentialPath?.let { device.executeShellCommand("rm $it") }
        }
    }

    private fun requireSafeInputPath(path: String) {
        require(path.matches(Regex("/sdcard/Download/[A-Za-z0-9._-]+\\.json")))
    }

    private fun execute(step: JSONObject) {
        val timeout = step.optLong("timeoutMs", 20_000L).coerceIn(500L, 60_000L)
        when (val action = step.getString("action")) {
            "launch", "restart" -> {
                if (action == "restart") device.executeShellCommand("am force-stop $appPackage")
                device.executeShellCommand("am start -W -n $appPackage/cloud.dcompany.erp.MainActivity")
                assertTrue("ERP did not become visible", device.wait(Until.hasObject(By.pkg(appPackage)), timeout))
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
            "idleFrames" -> {
                device.executeShellCommand("dumpsys gfxinfo $appPackage reset")
                SystemClock.sleep(step.optLong("durationMs", 10_000L).coerceIn(1_000L, 30_000L))
                File(output, "frames-${timings.length()}.txt").writeText(
                    device.executeShellCommand("dumpsys gfxinfo $appPackage framestats"),
                )
            }
            else -> error("Unsupported audit action: $action")
        }
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
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "-").take(110)
        device.takeScreenshot(File(output, "$safeLabel.png"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()) {
                "Could not invalidate the audit evidence accessibility cache"
            }
        }
        device.dumpWindowHierarchy(File(output, "$safeLabel.xml"))
    }
}

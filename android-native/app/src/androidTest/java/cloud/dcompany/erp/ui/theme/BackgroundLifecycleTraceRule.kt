package cloud.dcompany.erp.ui.theme

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Best-effort exact-case facts. Fatal process death may prevent any final XML record. */
internal class BackgroundLifecycleTraceRule : TestRule {
    private val guard = Any()
    @Volatile private var trace: BackgroundLifecycleTrace? = null
    @Volatile private var owner: Activity? = null

    fun bindOwner(activity: Activity) {
        runCatching { synchronized(guard) {
            if (trace != null) {
                owner = activity
                trace?.record(BackgroundPhase.OWNER_BOUND, publish = ::log)
            }
        } }
    }

    fun mark(phase: BackgroundPhase) {
        runCatching { synchronized(guard) { trace?.record(phase, publish = ::log) } }
    }

    private fun lifecycle(activity: Activity, phase: BackgroundPhase) {
        runCatching { synchronized(guard) {
            if (activity !== owner) return
            val state = (activity as? ComponentActivity)?.lifecycle?.currentState ?: return
            trace?.record(phase, BackgroundLifecycle.valueOf(state.name), activity.isFinishing, activity.isDestroyed, ::log)
        } }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = lifecycle(activity, BackgroundPhase.ACTIVITY_STARTED)
        override fun onActivityResumed(activity: Activity) = lifecycle(activity, BackgroundPhase.ACTIVITY_RESUMED)
        override fun onActivityPaused(activity: Activity) = lifecycle(activity, BackgroundPhase.ACTIVITY_PAUSED)
        override fun onActivityStopped(activity: Activity) = lifecycle(activity, BackgroundPhase.ACTIVITY_STOPPED)
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityPreDestroyed(activity: Activity) = lifecycle(activity, BackgroundPhase.ACTIVITY_PRE_DESTROY)
        override fun onActivityDestroyed(activity: Activity) = lifecycle(activity, BackgroundPhase.ACTIVITY_DESTROYED)
    }

    override fun apply(base: Statement, description: Description): Statement {
        if (description.className != "cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiTest" ||
            description.methodName != "settingsCardIsReachableAtCompactFontScaleInDayAndNight") return base
        return object : Statement() {
            override fun evaluate() {
                var application: Application? = null
                runCatching {
                    trace = BackgroundLifecycleTrace(SystemClock::uptimeMillis)
                    mark(BackgroundPhase.RULE_ENTER)
                    application = ApplicationProvider.getApplicationContext<Application>()
                    application?.registerActivityLifecycleCallbacks(callbacks)
                }
                evaluateBackgroundRuleBody(
                    body = { base.evaluate() },
                    afterSuccess = { mark(BackgroundPhase.RULE_RETURNED) },
                    observeFailure = { failure ->
                        synchronized(guard) { trace?.close() }?.let { snapshot ->
                            failure.addSuppressed(AssertionError(
                                "DCOMPANY_BACKGROUND_LIFECYCLE_FAILURE_V1=" + snapshot.json(),
                            ).apply { stackTrace = emptyArray() })
                        }
                    },
                    cleanup = {
                        runCatching { synchronized(guard) {
                            try { trace?.close() } finally {
                                owner = null
                                trace = null
                            }
                        } }
                        runCatching { application?.unregisterActivityLifecycleCallbacks(callbacks) }
                    },
                )
            }

        }
    }

    private fun log(event: BackgroundEvent) {
        // record() admits at most32 events; dropped/late events never emit logs.
        Log.i("DCompanyBackgroundPhase", "DCOMPANY_BACKGROUND_LIFECYCLE_EVENT_V1=" + event.json())
    }
}

private fun BackgroundEvent.json(): JSONObject = JSONObject()
    .put("sequence", sequence).put("uptime_ms", uptimeMs).put("phase", phase.name)
    .put("lifecycle", lifecycle?.name ?: JSONObject.NULL)
    .put("finishing", finishing ?: JSONObject.NULL).put("destroyed", destroyed ?: JSONObject.NULL)

private fun BackgroundSnapshot.json(): JSONObject = JSONObject()
    .put("schema_version", 1).put("surface", "workspace_background_settings")
    .put("atomic_observation", false).put("dropped", dropped)
    .put("events", JSONArray().also { list -> events.forEach { list.put(it.json()) } })

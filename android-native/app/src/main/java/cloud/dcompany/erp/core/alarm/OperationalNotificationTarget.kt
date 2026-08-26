package cloud.dcompany.erp.core.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal enum class OperationalNotificationDestination {
    POS,
    GAMING,
}

internal sealed interface OperationalNotificationTarget {
    val destination: OperationalNotificationDestination
    val primaryId: String

    data class HeldOrder(val orderId: String) : OperationalNotificationTarget {
        override val destination = OperationalNotificationDestination.POS
        override val primaryId: String get() = orderId
    }

    data class GamingSession(
        val sessionId: String,
        val stationId: String,
    ) : OperationalNotificationTarget {
        override val destination = OperationalNotificationDestination.GAMING
        override val primaryId: String get() = sessionId
    }
}

internal enum class OperationalRouteDecision {
    WAIT_FOR_SIGN_IN,
    NAVIGATE,
    ACCESS_DENIED,
}

/** Authentication and module access are resolved before any target is opened. */
internal fun operationalRouteDecision(
    signedIn: Boolean,
    destinationAllowed: Boolean,
): OperationalRouteDecision = when {
    !signedIn -> OperationalRouteDecision.WAIT_FOR_SIGN_IN
    destinationAllowed -> OperationalRouteDecision.NAVIGATE
    else -> OperationalRouteDecision.ACCESS_DENIED
}

/** Stable intent contract shared by AlarmReceiver and MainActivity. */
internal object OperationalNotificationIntents {
    const val ACTION_OPEN_TARGET = "cloud.dcompany.erp.action.OPEN_OPERATIONAL_TARGET"
    const val EXTRA_TARGET_TYPE = "operational_target_type"
    const val EXTRA_TARGET_ID = "operational_target_id"
    const val EXTRA_STATION_ID = "operational_station_id"
    const val EXTRA_OPEN_TOKEN = "operational_open_token"

    private const val TYPE_HELD_ORDER = "held_order"
    private const val TYPE_GAMING_SESSION = "gaming_session"
    private const val MAX_ID_LENGTH = 200

    fun putTarget(intent: Intent, target: OperationalNotificationTarget): Intent =
        intent.apply {
            action = ACTION_OPEN_TARGET
            when (target) {
                is OperationalNotificationTarget.HeldOrder -> {
                    putExtra(EXTRA_TARGET_TYPE, TYPE_HELD_ORDER)
                    putExtra(EXTRA_TARGET_ID, target.orderId)
                    removeExtra(EXTRA_STATION_ID)
                }
                is OperationalNotificationTarget.GamingSession -> {
                    putExtra(EXTRA_TARGET_TYPE, TYPE_GAMING_SESSION)
                    putExtra(EXTRA_TARGET_ID, target.sessionId)
                    putExtra(EXTRA_STATION_ID, target.stationId)
                }
            }
        }

    fun readTarget(intent: Intent?): OperationalNotificationTarget? {
        if (intent?.action != ACTION_OPEN_TARGET) return null
        val id = intent.getStringExtra(EXTRA_TARGET_ID).validOperationalId() ?: return null
        return when (intent.getStringExtra(EXTRA_TARGET_TYPE)) {
            TYPE_HELD_ORDER -> OperationalNotificationTarget.HeldOrder(id)
            TYPE_GAMING_SESSION -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
                    .validOperationalId() ?: return null
                OperationalNotificationTarget.GamingSession(id, stationId)
            }
            else -> null
        }
    }

    private fun String?.validOperationalId(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH }
}

/**
 * Persists only opaque target ids while a notification route is waiting for
 * authentication. It expires automatically so an abandoned tap cannot route
 * an unrelated employee days later on a shared tablet.
 */
@SuppressLint("ApplySharedPref") // Routes/tokens must survive immediate process death after a tap.
internal class OperationalNotificationRouteStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val _pending = MutableStateFlow(readPersisted(System.currentTimeMillis()))
    val pending: StateFlow<OperationalNotificationTarget?> = _pending.asStateFlow()
    private val _rejectedOpenNotice = MutableStateFlow<String?>(null)
    val rejectedOpenNotice: StateFlow<String?> = _rejectedOpenNotice.asStateFlow()

    /**
     * MainActivity is exported for the launcher, so target extras alone are
     * forgeable by another app. A notification receives a one-time app-private
     * token; only that exact immutable PendingIntent can install a route.
     */
    @Synchronized
    fun authorizeOpenIntent(
        intent: Intent,
        target: OperationalNotificationTarget,
        nowMillis: Long = System.currentTimeMillis(),
    ): Intent {
        OperationalNotificationIntents.putTarget(intent, target)
        val token = UUID.randomUUID().toString()
        val key = target.storageKey()
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(AUTH_TIME_PREFIX) }
            .forEach { timeKey ->
                val issuedAt = preferences.getLong(timeKey, 0L)
                if (nowMillis - issuedAt !in 0..MAX_AGE_MILLIS) {
                    val suffix = timeKey.removePrefix(AUTH_TIME_PREFIX)
                    editor.remove(timeKey).remove("$AUTH_TOKEN_PREFIX$suffix")
                }
            }
        val committed = editor
            .putString("$AUTH_TOKEN_PREFIX$key", token)
            .putLong("$AUTH_TIME_PREFIX$key", nowMillis)
            .commit()
        if (!committed) return intent
        return intent.putExtra(OperationalNotificationIntents.EXTRA_OPEN_TOKEN, token)
    }

    @Synchronized
    fun accept(intent: Intent, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (intent.action != OperationalNotificationIntents.ACTION_OPEN_TARGET) return false
        val target = OperationalNotificationIntents.readTarget(intent)
        if (target == null) {
            _rejectedOpenNotice.value =
                "This alert link is invalid. No order or session was opened or changed."
            return false
        }
        val key = target.storageKey()
        val expectedToken = preferences.getString("$AUTH_TOKEN_PREFIX$key", null)
        val issuedAt = preferences.getLong("$AUTH_TIME_PREFIX$key", 0L)
        val suppliedToken = intent.getStringExtra(OperationalNotificationIntents.EXTRA_OPEN_TOKEN)
        if (!operationalOpenTokenValid(expectedToken, suppliedToken, issuedAt, nowMillis)) {
            _rejectedOpenNotice.value =
                "This alert has expired or belongs to a previous tablet workspace. " +
                    "Open POS or Gaming manually and refresh; nothing was changed."
            return false
        }
        if (!persistAndConsumeToken(target, key, nowMillis)) return false
        _rejectedOpenNotice.value = null
        _pending.value = target
        return true
    }

    @Synchronized
    fun consume(expected: OperationalNotificationTarget) {
        if (_pending.value != expected) return
        preferences.edit()
            .remove(KEY_TYPE)
            .remove(KEY_ID)
            .remove(KEY_STATION_ID)
            .remove(KEY_ACCEPTED_AT)
            .commit()
        _pending.value = null
    }

    /** Deliberate logout/account change must not route the next employee. */
    @Synchronized
    fun clearPending() {
        preferences.edit()
            .remove(KEY_TYPE)
            .remove(KEY_ID)
            .remove(KEY_STATION_ID)
            .remove(KEY_ACCEPTED_AT)
            .commit()
        _pending.value = null
        _rejectedOpenNotice.value = null
    }

    /** A validated account/branch/terminal scope purge invalidates every old notification token. */
    @Synchronized
    fun clearAllForScopeChange() {
        preferences.edit().clear().commit()
        _pending.value = null
        _rejectedOpenNotice.value = null
    }

    fun dismissRejectedOpenNotice() {
        _rejectedOpenNotice.value = null
    }

    private fun persistAndConsumeToken(
        target: OperationalNotificationTarget,
        storageKey: String,
        nowMillis: Long,
    ): Boolean {
        val editor = preferences.edit()
            .putLong(KEY_ACCEPTED_AT, nowMillis)
            .putString(KEY_ID, target.primaryId)
            .remove("$AUTH_TOKEN_PREFIX$storageKey")
            .remove("$AUTH_TIME_PREFIX$storageKey")
        when (target) {
            is OperationalNotificationTarget.HeldOrder -> editor
                .putString(KEY_TYPE, TYPE_HELD)
                .remove(KEY_STATION_ID)
            is OperationalNotificationTarget.GamingSession -> editor
                .putString(KEY_TYPE, TYPE_GAMING)
                .putString(KEY_STATION_ID, target.stationId)
        }
        // A tap may immediately background/kill the process at Login. Commit
        // keeps the target durable before MainActivity renders SignedOut.
        return editor.commit()
    }

    private fun readPersisted(nowMillis: Long): OperationalNotificationTarget? {
        val acceptedAt = preferences.getLong(KEY_ACCEPTED_AT, 0L)
        if (acceptedAt <= 0L || nowMillis - acceptedAt !in 0..MAX_AGE_MILLIS) {
            preferences.edit()
                .remove(KEY_TYPE)
                .remove(KEY_ID)
                .remove(KEY_STATION_ID)
                .remove(KEY_ACCEPTED_AT)
                .commit()
            return null
        }
        val id = preferences.getString(KEY_ID, null)?.takeIf(String::isNotBlank) ?: return null
        return when (preferences.getString(KEY_TYPE, null)) {
            TYPE_HELD -> OperationalNotificationTarget.HeldOrder(id)
            TYPE_GAMING -> preferences.getString(KEY_STATION_ID, null)
                ?.takeIf(String::isNotBlank)
                ?.let { OperationalNotificationTarget.GamingSession(id, it) }
            else -> null
        }
    }

    private companion object {
        const val PREFERENCES = "dcompany_pending_notification_route"
        const val KEY_TYPE = "type"
        const val KEY_ID = "id"
        const val KEY_STATION_ID = "station_id"
        const val KEY_ACCEPTED_AT = "accepted_at"
        const val TYPE_HELD = "held"
        const val TYPE_GAMING = "gaming"
        const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val AUTH_TOKEN_PREFIX = "auth_token:"
        const val AUTH_TIME_PREFIX = "auth_time:"
    }
}

internal fun operationalOpenTokenValid(
    expectedToken: String?,
    suppliedToken: String?,
    issuedAtMillis: Long,
    nowMillis: Long,
): Boolean = expectedToken != null &&
    suppliedToken != null &&
    expectedToken == suppliedToken &&
    issuedAtMillis > 0L &&
    nowMillis - issuedAtMillis in 0..(24L * 60L * 60L * 1_000L)

private fun OperationalNotificationTarget.storageKey(): String = when (this) {
    is OperationalNotificationTarget.HeldOrder -> "held:$orderId"
    is OperationalNotificationTarget.GamingSession -> "gaming:$sessionId:$stationId"
}

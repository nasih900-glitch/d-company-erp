package cloud.dcompany.erp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.coroutines.runBlocking

class DCompanyApp : Application() {

    companion object {
        /**
         * Importance is fixed when a channel is first created and cannot be
         * raised later — recreating with the same id is a no-op. If this ever
         * needs to change, the id must change with it.
         */
        const val ALARM_CHANNEL_ID = "dcompany_alarms_v1"
    }

    lateinit var tokens: TokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore(this)
        // Blocking here is deliberate and bounded: it is a single small disk
        // read, and every screen downstream assumes the session is known.
        runBlocking { tokens.load() }
        ApiClient.init(tokens)
        createAlarmChannel()
    }

    private fun createAlarmChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            "Session & order alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Gaming session overtime and held orders waiting too long."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }
}

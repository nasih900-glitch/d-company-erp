package cloud.dcompany.erp.core.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.MainActivity
import cloud.dcompany.erp.R

/**
 * Alarms are handed to the OS scheduler at the moment the deadline becomes
 * known (a gaming session starting, an order being held), not polled by a timer
 * inside the app.
 *
 * This is the difference the WebView build could not close: a JS setTimeout
 * only runs while the WebView is alive and awake, so a session that ran over
 * while the tablet was asleep alerted late or never. setExactAndAllowWhileIdle
 * fires through Doze whether or not the app is running.
 */
object AlarmScheduler {

    fun schedule(context: Context, tag: String, atEpochMillis: Long, title: String, body: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TAG, tag)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // canScheduleExactAlarms() is only meaningful on S+; on older releases
        // the exact call is always permitted.
        val allowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        if (allowed) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pending)
        } else {
            // Degrade rather than crash: still fires, just batched by the OS.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pending)
        }
    }

    fun cancel(context: Context, tag: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            tag.hashCode(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(pending)
    }

    const val EXTRA_TAG = "tag"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
}

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(AlarmScheduler.EXTRA_TAG) ?: return
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "D Company"
        val body = intent.getStringExtra(AlarmScheduler.EXTRA_BODY).orEmpty()

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, DCompanyApp.ALARM_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(tag.hashCode(), notification)
    }
}

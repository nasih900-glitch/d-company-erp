package cloud.dcompany.erp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.room.Room
import cloud.dcompany.erp.core.auth.ShiftCache
import cloud.dcompany.erp.core.auth.TerminalStore
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.db.ALL_MIGRATIONS
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.sync.ConnectivityObserver
import cloud.dcompany.erp.core.sync.RealtimeClient
import cloud.dcompany.erp.core.sync.RealtimeEvent
import cloud.dcompany.erp.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DCompanyApp : Application() {

    companion object {
        /**
         * Importance is fixed when a channel is first created and cannot be
         * raised later — recreating with the same id is a no-op. If this ever
         * needs to change, the id must change with it.
         */
        const val ALARM_CHANNEL_ID = "dcompany_alarms_v1"

        lateinit var instance: DCompanyApp
            private set
    }

    lateinit var tokens: TokenStore
        private set
    lateinit var shiftCache: ShiftCache
        private set
    lateinit var terminalStore: TerminalStore
        private set
    lateinit var db: ErpDatabase
        private set
    lateinit var sync: SyncEngine
        private set
    lateinit var connectivity: ConnectivityObserver
        private set
    lateinit var realtime: RealtimeClient
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this

        tokens = TokenStore(this)
        shiftCache = ShiftCache(this)
        // Blocking here is deliberate and bounded: a single small disk read,
        // and every screen downstream assumes the session is known.
        terminalStore = TerminalStore(this)
        runBlocking { tokens.load(); terminalStore.load() }
        ApiClient.init(tokens, terminalStore)

        db = Room.databaseBuilder(this, ErpDatabase::class.java, "dcompany.db")
            // No destructive fallback: this database holds captured sales that
            // exist nowhere else until they sync. Wiping it on a schema change
            // would destroy real money. Any future version needs a migration.
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        sync = SyncEngine(db, appScope)
        realtime = RealtimeClient(tokens, appScope)
        connectivity = ConnectivityObserver(this)
        // Draining the queue the instant the link returns is the whole point:
        // staff should never have to remember to press a sync button.
        connectivity.start {
            if (tokens.hasSession()) {
                sync.requestSync()
                realtime.connect()
            }
        }
        if (tokens.hasSession()) realtime.connect()

        // The one place a "changed" event turns into an action: drain the
        // outbox (cheap, and a changed message is proof the link is alive
        // right now) and pull whatever resource just changed. A reconnect
        // that followed a real gap refreshes every on-demand resource, the
        // same way the web app's refetchEverything() does, since anything
        // could have changed while this device wasn't listening.
        appScope.launch {
            realtime.changes.collect { event ->
                when (event) {
                    is RealtimeEvent.Changed -> {
                        sync.requestSync()
                        sync.refresh(event.resource)
                    }
                    RealtimeEvent.ReconnectedAfterGap -> {
                        sync.requestSync()
                        sync.refreshAllOnDemand()
                    }
                }
            }
        }

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

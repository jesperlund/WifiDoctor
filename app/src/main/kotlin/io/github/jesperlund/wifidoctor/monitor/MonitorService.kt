package io.github.jesperlund.wifidoctor.monitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jesperlund.wifidoctor.MainActivity
import io.github.jesperlund.wifidoctor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonitorServiceKeeper @Inject constructor(@ApplicationContext private val context: Context) : ForegroundKeeper {
    override fun start() {
        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }

    override fun stop() {
        context.stopService(Intent(context, MonitorService::class.java))
    }
}

/**
 * Foreground service that keeps WifiDoctor alive while the user is in a call in another app.
 * The measuring itself happens in [CallMonitorController]; this only shows a live notification.
 */
@AndroidEntryPoint
class MonitorService : Service() {

    @Inject lateinit var runner: MonitorRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runner.stop()
            return START_NOT_STICKY
        }
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch {
            runner.state.collectLatest { state ->
                when (state) {
                    is MonitorState.Running -> update(state)
                    else -> stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun update(state: MonitorState.Running) {
        val p = state.progress ?: return
        val left = ((p.durationMs - p.elapsedMs).coerceAtLeast(0) / 1000)
        val text = "Median ${p.internet.p50Ms?.toInt() ?: "–"} ms · worst 5% ${p.internet.p95Ms?.toInt() ?: "–"} ms · " +
            "loss ${"%.1f".format(p.internet.lossPercent)}% · drop-outs ${p.dropouts} · ${left / 60}:${"%02d".format(left % 60)} left"
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Monitoring your connection")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0, "Stop",
            PendingIntent.getService(
                this, 2,
                Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Call monitor", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shown while WifiDoctor monitors your connection during a call" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "call_monitor"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "io.github.jesperlund.wifidoctor.STOP_MONITOR"
    }
}

package com.example.hlphonerl.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.hlphonerl.R
import kotlinx.coroutines.*

class RlForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = AppRuntime.engine
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLearner()
                return START_NOT_STICKY
            }
            else -> startLearner()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        engine.stop()
        super.onDestroy()
    }

    private fun startLearner() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting", "Connecting to HyperLiquid L2…"))
        engine.start()
        notificationJob?.cancel()
        notificationJob = scope.launch {
            engine.state.collect { s ->
                val title = if (s.running) "HL Phone RL running" else "HL Phone RL paused"
                val text = "${s.coin} • PnL ${"%+.4f".format(s.equity)} • ${s.action}"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(title, text))
            }
        }
    }

    private fun stopLearner() {
        notificationJob?.cancel()
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HL Phone RL learner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the virtual L2 RL learner running while the screen is off"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, RlForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            1001,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rl)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.hlphonerl.START_LEARNER"
        const val ACTION_STOP = "com.example.hlphonerl.STOP_LEARNER"
        private const val CHANNEL_ID = "hl_phone_rl_learner"
        private const val NOTIFICATION_ID = 42
    }
}

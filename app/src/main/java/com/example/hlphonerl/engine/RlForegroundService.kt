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
    private var lastSavedUpdates = -1
    private var skipSaveOnDestroy = false

    override fun onCreate() {
        super.onCreate()
        engine.attachPersistenceDir(filesDir.resolve("learning_state"))
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLearner()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                resetLearner()
                return START_NOT_STICKY
            }
            else -> startLearner()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!skipSaveOnDestroy) saveLearningState(compactReplay = true)
        notificationJob?.cancel()
        scope.cancel()
        engine.stop()
        super.onDestroy()
    }

    private fun startLearner() {
        engine.attachPersistenceDir(filesDir.resolve("learning_state"))
        startForeground(NOTIFICATION_ID, buildNotification("Starting", "Restoring policy + replay…"))
        loadPolicy()
        engine.start()
        notificationJob?.cancel()
        notificationJob = scope.launch {
            engine.state.collect { s ->
                // Policy is checkpointed every 100 learner updates. Replay is appended immediately
                // by RlEngine on every transition and compacted every 500 updates / clean stop.
                if (s.updates > 0 && s.updates / 100 > lastSavedUpdates / 100) {
                    saveLearningState(compactReplay = s.updates % 500 == 0)
                }
                val title = if (s.running) "HL Phone RL running" else "HL Phone RL paused"
                val text = "${s.coin} • PnL ${"%+.4f".format(s.equity)} • ${s.action} • replay ${s.replay}"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(title, text))
            }
        }
    }

    private fun resetLearner() {
        skipSaveOnDestroy = true
        engine.attachPersistenceDir(filesDir.resolve("learning_state"))
        notificationJob?.cancel()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_POLICY_JSON).apply()
        engine.resetLearning()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopLearner() {
        saveLearningState(compactReplay = true)
        notificationJob?.cancel()
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveLearningState(compactReplay: Boolean) {
        try {
            val dir = filesDir.resolve("learning_state").also { it.mkdirs() }
            val tmp = dir.resolve("policy.json.tmp")
            val target = dir.resolve("policy.json")
            tmp.writeText(engine.exportPolicyJson())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            if (compactReplay) engine.compactReplayFile()
            lastSavedUpdates = engine.state.value.updates
        } catch (_: Exception) {
            // Best-effort checkpointing; never crash the foreground service while saving.
        }
    }

    private fun loadPolicy() {
        val policyFile = filesDir.resolve("learning_state").resolve("policy.json")
        val legacyJson = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_POLICY_JSON, null)
        val json = when {
            policyFile.exists() -> policyFile.readText()
            legacyJson != null -> legacyJson
            else -> null
        } ?: return
        try { engine.importPolicyJson(json) } catch (_: Exception) { }
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
        const val ACTION_RESET = "com.example.hlphonerl.RESET_LEARNING"
        private const val CHANNEL_ID = "hl_phone_rl_learner"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "hl_phone_rl_policy"
        private const val KEY_POLICY_JSON = "linear_double_q_policy_json"
    }
}

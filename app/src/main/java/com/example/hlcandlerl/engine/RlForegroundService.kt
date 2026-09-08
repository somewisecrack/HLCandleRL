package com.example.hlcandlerl.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.hlcandlerl.R
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
        if (intent == null) {
            // A sticky restart re-delivers a null intent while the app is in the background. Calling
            // startForeground() from there throws ForegroundServiceStartNotAllowedException on
            // Android 12+, so the learner is never silently resurrected; the user restarts it.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> stopLearner()
            ACTION_RESET -> resetLearner()
            else -> startLearner()
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ times out long-running dataSync foreground services. If the service does not stop
     * itself when told, the platform crashes the app, so a timeout is treated as a clean stop.
     */
    override fun onTimeout(startId: Int) {
        handleForegroundTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout()
    }

    private fun handleForegroundTimeout() {
        engine.stop()
        stopLearner()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!skipSaveOnDestroy) saveLearningStateBlocking(compactReplay = false)
        notificationJob?.cancel()
        scope.cancel()
        engine.stop()
        super.onDestroy()
    }

    private fun startLearner() {
        engine.attachPersistenceDir(filesDir.resolve("learning_state"))
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting", "Restoring policy + replay…"))
        } catch (e: Exception) {
            // Foreground start can be refused (background start restrictions, missing notification
            // permission). Refusing to run is correct; crashing the app is not.
            stopSelf()
            return
        }
        // Policy/replay restore happens inside engine.start() on Dispatchers.IO. Reading them here
        // would put a multi-MB parse on the service main thread before the learner even runs.
        migrateLegacyPolicy()
        engine.start()
        notificationJob?.cancel()
        notificationJob = scope.launch {
            engine.state.collect { s ->
              try {
                // Policy is checkpointed every 100 learner updates. Replay is appended immediately
                // by RlEngine on every transition and compacted every 500 updates / clean stop.
                if (s.updates > 0 && s.updates / 100 > lastSavedUpdates / 100) {
                    saveLearningState(compactReplay = s.updates % 500 == 0)
                }
                val title = if (s.running) "HL Candle RL running" else "HL Candle RL paused"
                val text = "${s.coin} • PnL ${"%+.4f".format(s.equity)} • ${s.action} • replay ${s.replay}"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(title, text))
              } catch (e: CancellationException) {
                  throw e
              } catch (_: Exception) {
                  // A failed notification refresh must never take the learner down with it.
              }
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
        saveLearningStateBlocking(compactReplay = false)
        notificationJob?.cancel()
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveLearningState(compactReplay: Boolean) {
        // Callers are on the service main thread; writing the policy and compacting replay are disk
        // operations and must not block it.
        scope.launch(Dispatchers.IO) { saveLearningStateBlocking(compactReplay) }
    }

    private fun saveLearningStateBlocking(compactReplay: Boolean) {
        try {
            val target = engine.policyFile() ?: return
            val parent = target.parentFile ?: return
            parent.mkdirs()
            val tmp = parent.resolve("policy.json.tmp")
            tmp.writeText(engine.exportPolicyJson())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            // Replay file size is bounded by the engine itself while appending; the service only
            // needs to checkpoint the small policy file.
            if (compactReplay) engine.compactReplayFile()
            lastSavedUpdates = engine.state.value.updates
        } catch (_: Exception) {
            // Best-effort checkpointing; never crash the foreground service while saving.
        }
    }

    /** One-time move of a pre-file-storage policy out of SharedPreferences, off the main thread. */
    private fun migrateLegacyPolicy() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val legacyJson = prefs.getString(KEY_POLICY_JSON, null) ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val target = engine.policyFile() ?: return@launch
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    target.writeText(legacyJson)
                }
                prefs.edit().remove(KEY_POLICY_JSON).apply()
            } catch (_: Exception) { }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HL Candle RL learner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the virtual OHLCV RL learner running while the screen is off"
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
        const val ACTION_START = "com.example.hlcandlerl.START_LEARNER"
        const val ACTION_STOP = "com.example.hlcandlerl.STOP_LEARNER"
        const val ACTION_RESET = "com.example.hlcandlerl.RESET_LEARNING"
        private const val CHANNEL_ID = "hl_phone_rl_learner"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "hl_phone_rl_policy"
        private const val KEY_POLICY_JSON = "linear_double_q_policy_json"
    }
}

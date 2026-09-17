package com.ghost.assistant.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ghost.assistant.GhostBrain
import com.ghost.assistant.GhostOverlayService
import com.ghost.assistant.MainActivity
import com.ghost.assistant.theme.ThemeManager

class GhostWakeWordService : Service() {

    companion object {
        const val CHANNEL_ID = "ghost_wake_word_service"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING = "com.ghost.assistant.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.ghost.assistant.ACTION_STOP_LISTENING"
        const val ACTION_TOGGLE_LISTENING = "com.ghost.assistant.ACTION_TOGGLE_LISTENING"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var isListening: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, GhostWakeWordService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, GhostWakeWordService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }
    }

    private var wakeWordManager: WakeWordManager? = null
    private var brain: GhostBrain? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(isListening = true))

        brain = GhostBrain(this) { state ->
            // Notify HUD if connected
        }

        wakeWordManager = WakeWordManager(
            context = this,
            onWakeWordDetected = { speechText ->
                handleWakeWordDetected(speechText)
            },
            onListeningStateChanged = { listening ->
                isListening = listening
                updateNotification(listening)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                wakeWordManager?.stop()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LISTENING -> {
                if (isListening) {
                    wakeWordManager?.stop()
                    isListening = false
                    updateNotification(false)
                } else {
                    wakeWordManager?.start()
                    isListening = true
                    updateNotification(true)
                }
            }
            else -> {
                if (ThemeManager.isWakeWordEnabled(this)) {
                    wakeWordManager?.start()
                }
            }
        }
        return START_STICKY
    }

    private fun handleWakeWordDetected(speechText: String?) {
        // Automatically pause wake word listener while processing & speaking
        wakeWordManager?.pauseForTts()

        // Launch / trigger the overlay HUD or brain directly
        val overlayIntent = Intent(this, GhostOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }

        brain?.let { b ->
            val queryToProcess = speechText ?: "Ghost"
            b.processIntent(queryToProcess) {
                // Once speaking finishes, resume listening
                wakeWordManager?.resumeAfterTts()
            }
        } ?: run {
            wakeWordManager?.resumeAfterTts()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost Wake Word Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent microphone listener for 'Ghost' hotword"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isListening: Boolean): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val pendingAppIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, GhostWakeWordService::class.java).apply {
            action = ACTION_TOGGLE_LISTENING
        }
        val pendingToggleIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isListening) "Listening for \"Ghost\"..." else "Wake word paused"
        val toggleActionText = if (isListening) "Pause" else "Resume"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("G.H.O.S.T. Autonomous Listener")
            .setContentText(statusText)
            .setContentIntent(pendingAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, toggleActionText, pendingToggleIntent)
            .build()
    }

    private fun updateNotification(isListening: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(isListening))
    }

    override fun onDestroy() {
        wakeWordManager?.stop()
        wakeWordManager = null
        brain?.destroy()
        brain = null
        isServiceRunning = false
        isListening = false
        super.onDestroy()
    }
}

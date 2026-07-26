package com.jarvis.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.os.OverlayActivity

/**
 * Foreground service that keeps JARVIS listening for the wake word in the
 * background. On "Hey JARVIS" it launches the translucent [OverlayActivity].
 * While an app screen owns the mic, [pauseWake]/[resumeWake] stop/restart it.
 */
class JarvisService : Service() {

    private var listener: WakeWordListener? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
        listener = WakeWordListener(applicationContext) { command -> onWake(command) }
        startWake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWake()
        listener?.destroy()
        listener = null
        instance = null
        super.onDestroy()
    }

    private fun startWake() {
        // Don't grab the mic while an app screen (MainActivity/OverlayActivity) is using it.
        if (!appActive) listener?.start()
    }

    private fun stopWake() {
        listener?.stop()
    }

    private fun onWake(command: String?) {
        stopWake()
        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_COMMAND, command ?: "")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Missing "display over other apps" permission — resume listening.
            startWake()
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS background listening",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is listening")
            .setContentText("Say \"Hey JARVIS\" any time")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "jarvis_bg"
        private const val NOTIF_ID = 1001

        @Volatile
        private var instance: JarvisService? = null

        // True while an app screen owns the mic; the service must stay quiet then.
        @Volatile
        private var appActive = false

        fun isRunning(): Boolean = instance != null

        /** Pause background wake listening (an app screen is using the mic). */
        fun pauseWake() {
            appActive = true
            instance?.stopWake()
        }

        /** Resume background wake listening. */
        fun resumeWake() {
            appActive = false
            instance?.startWake()
        }
    }
}

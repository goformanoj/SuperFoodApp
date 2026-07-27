package com.jarvis.os.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.jarvis.os.MainActivity

/**
 * Keeps JARVIS legally and technically able to hear you while you are in another
 * app during a work session.
 *
 * Note what this service does NOT do: it does not open the microphone. The
 * engine owns the one and only [com.jarvis.os.voice.VoiceController] in the
 * process, so a second listener cannot exist — that was the bug that broke the
 * earlier background-wake attempt. Microphone access on Android is granted per
 * process based on its state, so all this service has to do is hold the app in
 * the foreground state (with type `microphone`) and show the user an honest,
 * dismissible-by-Stop notification while it does.
 */
class WorkSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stop(this)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Work session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while JARVIS is listening for follow-up commands in another app."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WorkSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is listening")
            .setContentText("Say \"thank you Jarvis\" to stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_work_session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.jarvis.os.STOP_WORK_SESSION"

        /** Set by the engine so the notification's Stop action can end the session. */
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, WorkSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkSessionService::class.java))
        }
    }
}

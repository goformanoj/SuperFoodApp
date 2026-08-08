package com.jarvis.os.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import com.jarvis.os.MainActivity
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.voice.OpenWakeWord

/**
 * Background "hey jarvis" wake word: a microphone foreground service that runs the
 * on-device [OpenWakeWord] detector so JARVIS can be summoned from any app, with
 * the app closed and the heavy speech recogniser switched off.
 *
 * This service DOES open the microphone (unlike [WorkSessionService], which only
 * holds foreground state). That is safe here precisely because it never runs at
 * the same time as the engine's recogniser: the engine starts this only while
 * JARVIS is backgrounded and asleep, and stops it the instant JARVIS comes to the
 * foreground or a work session claims the mic. One owner, always — the rule the
 * earlier two-recogniser attempt broke.
 *
 * On detection it brings JARVIS to the front (which stops this service, handing
 * the mic to the recogniser) so the user can speak their command.
 */
class HotwordService : Service() {

    private var oww: OpenWakeWord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onDisableRequested?.invoke()
            stop(this)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!running) startListening()
        return START_STICKY
    }

    private fun startListening() {
        val detector = OpenWakeWord(applicationContext)
        if (!detector.available) {
            // Models missing or TFLite unsupported — nothing to do, stand down so
            // the engine's in-app gate stays the wake word.
            DebugLog.log(DebugLog.Stage.ERROR, "hotword service: openWakeWord unavailable, stopping")
            detector.close()
            onDisableRequested?.invoke()
            stop(this)
            return
        }
        oww = detector
        running = true
        thread = Thread { audioLoop(detector) }.apply { priority = Thread.NORM_PRIORITY; start() }
    }

    private fun audioLoop(detector: OpenWakeWord) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, OpenWakeWord.CHUNK * 8)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufSize,
            )
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "hotword service: mic init failed: ${e.message}")
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            running = false
            stopSelfSafe()
            return
        }
        val buf = ShortArray(OpenWakeWord.CHUNK)
        try {
            recorder.startRecording()
            DebugLog.log(DebugLog.Stage.HEARD, "hotword listening — say “Hey Jarvis”")
            while (running) {
                var off = 0
                while (off < buf.size) {
                    val n = recorder.read(buf, off, buf.size - off)
                    if (n <= 0) { off = -1; break }
                    off += n
                }
                if (off != buf.size) continue
                val score = detector.process(buf)
                if (score >= OpenWakeWord.DETECT_THRESHOLD) {
                    DebugLog.log(DebugLog.Stage.HEARD, "hotword — heard “Hey Jarvis” (score ${"%.2f".format(score)})")
                    detector.reset()
                    wakeTheApp()
                    running = false
                }
            }
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "hotword loop error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
            stopSelfSafe()
        }
    }

    /** Bring JARVIS to the front, flagged so it wakes and listens for the command. */
    private fun wakeTheApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WOKE_BY_HOTWORD, true)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "hotword: could not open JARVIS: ${e.message}")
        }
    }

    private fun stopSelfSafe() {
        try { stop(this) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        running = false
        thread?.let { try { it.join(500) } catch (e: InterruptedException) {} }
        thread = null
        oww?.close()
        oww = null
        super.onDestroy()
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
            "Wake word",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while JARVIS is listening for \"Hey Jarvis\" in the background."
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
            Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is listening for \"Hey Jarvis\"")
            .setContentText("On-device — nothing is recorded or sent. Tap Stop to turn off.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "com.jarvis.os.STOP_HOTWORD"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Set by the engine so the notification's Stop turns off background wake. */
        var onDisableRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException if the OS refuses a
                // background start. Logged so a trace shows it rather than failing mute.
                DebugLog.log(DebugLog.Stage.ERROR, "hotword service start refused: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, HotwordService::class.java))
            } catch (e: Exception) {
                // already gone
            }
        }
    }
}

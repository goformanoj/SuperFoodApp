package com.jarvis.os.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.os.debug.DebugLog

/**
 * Sets alarms and timers through the device's own clock app.
 *
 * Uses the standard [AlarmClock] intents rather than scheduling anything
 * ourselves: the alarm then lives in the user's real clock app, survives JARVIS
 * being uninstalled, and rings even if JARVIS is not running. `SET_ALARM` is a
 * normal permission, granted at install, so nothing has to be asked for at
 * runtime.
 */
object AlarmSetter {

    /** Returns true if the clock app accepted it. */
    fun set(context: Context, action: AlarmAction): Boolean = when (action) {
        is AlarmAction.SetAlarm -> send(
            context,
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                // Set it outright instead of dumping the user in the clock app
                // with a half-filled form.
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (action.days.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_DAYS, ArrayList(action.days))
                }
            },
            "alarm ${"%02d:%02d".format(action.hour, action.minute)} \"${action.label}\"" +
                if (action.days.isNotEmpty()) " repeating ${action.days.size} day(s)" else "",
        )

        is AlarmAction.StartTimer -> send(
            context,
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            },
            "timer ${action.seconds}s \"${action.label}\"",
        )
    }

    private fun send(context: Context, intent: Intent, describe: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            DebugLog.log(DebugLog.Stage.ALARM, "set $describe")
            true
        } catch (e: Exception) {
            // No clock app that handles the standard intent — rare, but real on
            // some stripped-down ROMs.
            DebugLog.log(DebugLog.Stage.ALARM, "could not set $describe: ${e.javaClass.simpleName}")
            false
        }
    }
}

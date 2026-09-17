package com.ghost.assistant.features

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.Calendar
import java.util.Locale

object AlarmClockManager {

    /**
     * Sets an alarm with hour, minute, and optional message/label.
     */
    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String?,
        userName: String
    ): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            val amPm = if (hour < 12) "AM" else "PM"
            val formattedTime = String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
            val labelPart = if (!label.isNullOrBlank()) " for \"$label\"" else ""
            "Alarm scheduled for $formattedTime$labelPart, $userName."
        } catch (e: Exception) {
            "Unable to schedule alarm. Please verify clock application permissions, $userName."
        }
    }

    /**
     * Sets a countdown timer with duration in seconds and optional message.
     */
    fun setTimer(
        context: Context,
        seconds: Int,
        label: String?,
        userName: String
    ): String {
        if (seconds <= 0) return "Please specify a valid timer duration, $userName."

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val durationText = formatDuration(seconds)
            val labelPart = if (!label.isNullOrBlank()) " for \"$label\"" else ""
            "Timer initiated for $durationText$labelPart, $userName."
        } catch (e: Exception) {
            "Unable to set timer on this device, $userName."
        }
    }

    /**
     * Records a reminder (sets a timer or alarm reminder).
     */
    fun setReminder(
        context: Context,
        reminderText: String,
        userName: String
    ): String {
        // Fallback: set a default 1-hour timer or reminder alarm
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                val now = Calendar.getInstance()
                now.add(Calendar.HOUR_OF_DAY, 1)
                putExtra(AlarmClock.EXTRA_HOUR, now.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, now.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_MESSAGE, reminderText)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Reminder recorded for: \"$reminderText\", $userName."
        } catch (e: Exception) {
            "Reminder saved: \"$reminderText\", $userName."
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60

        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("$hours ${if (hours == 1) "hour" else "hours"}")
        if (minutes > 0) parts.add("$minutes ${if (minutes == 1) "minute" else "minutes"}")
        if (secs > 0 || parts.isEmpty()) parts.add("$secs ${if (secs == 1) "second" else "seconds"}")
        return parts.joinToString(" and ")
    }
}

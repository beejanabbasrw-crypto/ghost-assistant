package com.ghost.assistant.features

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CalendarManager {

    data class CalendarEvent(
        val title: String,
        val startTime: Long,
        val endTime: Long,
        val location: String?
    )

    /**
     * Queries calendar events for today and returns a formatted voice readout.
     */
    fun getTodayEventsReadout(context: Context, userName: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Calendar permission is required to review your schedule, $userName."
        }

        val events = mutableListOf<CalendarEvent>()

        try {
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val endOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis

            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startOfDay)
            ContentUris.appendId(builder, endOfDay)

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION
            )

            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            cursor?.use { c ->
                val titleIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = c.getColumnIndex(CalendarContract.Instances.END)
                val locIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

                while (c.moveToNext()) {
                    val title = if (titleIdx >= 0) c.getString(titleIdx) else "Event"
                    val begin = if (beginIdx >= 0) c.getLong(beginIdx) else 0L
                    val end = if (endIdx >= 0) c.getLong(endIdx) else 0L
                    val loc = if (locIdx >= 0) c.getString(locIdx) else null

                    events.add(CalendarEvent(title = title ?: "Untitled Event", startTime = begin, endTime = end, location = loc))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error querying calendar: ${e.message}", e)
            return "Unable to access calendar archives at this moment, $userName."
        }

        if (events.isEmpty()) {
            return "Your calendar is completely clear for today, $userName."
        }

        val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        val builder = StringBuilder("You have ${events.size} event${if (events.size > 1) "s" else ""} scheduled today, $userName: ")

        for ((idx, ev) in events.take(4).withIndex()) {
            val timeStr = if (ev.startTime > 0) timeFormatter.format(Date(ev.startTime)) else "All day"
            if (idx > 0) builder.append("; ")
            builder.append("${ev.title} at $timeStr")
            if (!ev.location.isNullOrBlank()) {
                builder.append(" at ${ev.location}")
            }
        }

        if (events.size > 4) {
            builder.append("; and ${events.size - 4} more.")
        } else {
            builder.append(".")
        }

        return builder.toString()
    }
}

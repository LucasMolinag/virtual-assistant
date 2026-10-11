package com.lucasmolina.vela

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

object AppointmentReminders {
    const val EXTRA_TITLE = "title"
    const val EXTRA_ID = "id"
    private const val PREFS = "vella_reminders"
    private const val ALARMS = "alarms"
    private const val OWNED = "owned"
    private const val CHANNEL = "vella_reminders"
    private val minutesBefore = intArrayOf(60, 24 * 60)

    fun sync(context: Context, eventId: Long, title: String, startMillis: Long, important: Boolean) {
        if (!important) {
            cancel(context, eventId)
            return
        }
        val owned = ownedIds(context)
        owned.add(eventId.toString())
        saveOwned(context, owned)
        clearAlarms(context, eventId)
        if (startMillis <= System.currentTimeMillis()) return
        val now = System.currentTimeMillis()
        for (minutes in minutesBefore) {
            val at = startMillis - minutes * 60_000L
            if (at <= now + 5_000) continue
            ensureCalendarReminder(context, eventId, minutes)
            schedule(context, eventId, title, at, minutes)
        }
    }

    fun cancel(context: Context, eventId: Long) {
        val owned = ownedIds(context)
        val removeRows = owned.remove(eventId.toString())
        saveOwned(context, owned)
        clearAlarms(context, eventId)
        if (!removeRows) return
        for (minutes in minutesBefore) {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=? AND ${CalendarContract.Reminders.MINUTES}=?",
                arrayOf(eventId.toString(), minutes.toString()),
            )
        }
    }

    private fun clearAlarms(context: Context, eventId: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (minutes in minutesBefore) {
            alarm.cancel(pending(context, eventId, minutes, "Appointment"))
        }
        val kept = JSONArray()
        val current = stored(context)
        for (index in 0 until current.length()) {
            val item = current.getJSONObject(index)
            if (item.optLong("id") != eventId) kept.put(item)
        }
        save(context, kept)
    }

    fun restore(context: Context) {
        val current = stored(context)
        val now = System.currentTimeMillis()
        for (index in 0 until current.length()) {
            val item = current.getJSONObject(index)
            val at = item.optLong("at")
            if (at <= now) continue
            schedule(context, item.optLong("id"), item.optString("title"), at, item.optInt("minutes"))
        }
    }

    private fun schedule(context: Context, eventId: Long, title: String, at: Long, minutes: Int) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pending(context, eventId, minutes, title)
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        val kept = JSONArray()
        val current = stored(context)
        for (index in 0 until current.length()) {
            val item = current.getJSONObject(index)
            if (!(item.optLong("id") == eventId && item.optInt("minutes") == minutes)) kept.put(item)
        }
        kept.put(JSONObject().put("id", eventId).put("title", title).put("at", at).put("minutes", minutes))
        save(context, kept)
    }

    private fun ensureCalendarReminder(context: Context, eventId: Long, minutes: Int) {
        val exists = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID}=? AND ${CalendarContract.Reminders.MINUTES}=?",
            arrayOf(eventId.toString(), minutes.toString()),
            null,
        )?.use { it.moveToFirst() } == true
        if (exists) return
        val values = android.content.ContentValues()
        values.put(CalendarContract.Reminders.EVENT_ID, eventId)
        values.put(CalendarContract.Reminders.MINUTES, minutes)
        values.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
    }

    private fun pending(context: Context, eventId: Long, minutes: Int, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, eventId)
            putExtra(EXTRA_TITLE, title)
        }
        val request = (eventId xor minutes.toLong()).toInt()
        return PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ownedIds(context: Context): MutableSet<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(OWNED, emptySet()).orEmpty().toMutableSet()
    }

    private fun saveOwned(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(OWNED, ids.toHashSet()).apply()
    }

    private fun stored(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ALARMS, "[]").orEmpty()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun save(context: Context, alarms: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ALARMS, alarms.toString()).apply()
    }

    fun notify(context: Context, eventId: Long, title: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Appointment reminders", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Appointment" })
            .setContentText("Coming up soon")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify((eventId % Int.MAX_VALUE).toInt(), notification)
    }
}
